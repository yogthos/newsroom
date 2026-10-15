(ns newsroom.auth
  "Who is reading.

  When newsroom runs for one person, as it does unless config.edn says
  :single-user false, there is no sign-in: every request is the local
  reader's (see newsroom.store/local-user-id).

  With several readers, each signs in with an OpenID Connect server, the
  one config.edn's :oauth names, TODAQ's central-auth unless it names
  another:

    :single-user false
    :oauth {:issuer \"https://auth.biz.todaq.net\"
            :client-id \"newsroom\"
            :client-secret \"${NEWSROOM_OAUTH_CLIENT_SECRET}\"
            :redirect-uri \"https://news.example.com/oauth/callback\"}

  The server's endpoints are read from its discovery document, at
  /.well-known/openid-configuration under the issuer.

  A browser signs in with the authorization code flow and PKCE: /login
  sends it to the server's authorization endpoint, and /oauth/callback
  trades the code it comes back with for the reader's tokens at the token
  endpoint. The ID token comes straight from the server over TLS, so its
  claims are read without checking its signature, as OpenID Connect allows
  for the code flow, and checked: its issuer, its audience, its expiry and
  the nonce the sign-in sent. Userinfo adds the reader's name, email and
  groups. A reader is their issuer and sub. The browser is then given a
  session cookie, random, which the database keeps only the hash of, for
  :session-days, 30 by default.

  A program can send an access token the server issued instead, as
  Authorization: Bearer <token>. It is checked with the server, by its
  introspection endpoint, which needs newsroom's client secret, and what
  the server says is kept for a few minutes, so that each of a page's live
  updates doesn't ask again. Only a token issued to one of :token-clients,
  newsroom's own :client-id by default, is taken, and only when it isn't
  bound to another audience: a server lets any client sign in its users,
  and a token one of those clients was given mustn't read here as its
  user. :token-clients :any takes any token the server says is active,
  and lets a public client, which can't introspect, check one by its
  userinfo endpoint instead.

  :groups keeps the readers to those whose groups claim names one of
  them, and :local-subject names the sub whose sign-in is the local
  reader, so whoever ran newsroom for themselves keeps what it holds once
  it takes readers. :scopes are the scopes asked for, openid, profile and
  email by default."
  (:require [newsroom.json :as json]
            [clojure.string :as str]
            [jolt.http-client :as http]
            [newsroom.config :as config]
            [newsroom.store :as store]
            [ring.util.codec :as codec]))

(def default-issuer
  "The server readers sign in with when :oauth names none: TODAQ's
  central-auth."
  "https://auth.biz.todaq.net")

(defn oauth-config
  "config.edn's :oauth with its defaults."
  [cfg]
  (merge {:issuer default-issuer :scopes ["openid" "profile" "email"] :session-days 30}
         (:oauth cfg)))

(defn- now-ms [] (System/currentTimeMillis))

;; --- talking to the server -------------------------------------------------------

(defn request-json
  "Send `req`, as jolt.http-client takes it, and read the answer: {:status
  :body}, the body read as JSON, keys as keywords, when it is JSON."
  [req]
  (let [resp (http/request (merge {:socket-timeout 15000 :conn-timeout 10000 :throw-exceptions false} req))
        body (str (:body resp))]
    {:status (:status resp)
     :body (try (json/read-str body :key-fn keyword) (catch Throwable _ body))}))

(defonce ^:private discovered (atom {}))

(defonce ^:private checked (atom {}))

(defonce ^:private pending (atom {}))

(defn forget!
  "Forget the servers' discovery documents, the tokens checked and the
  sign-ins under way."
  []
  (reset! discovered {})
  (reset! checked {})
  (reset! pending {}))

(defn- trim-slash [s] (str/replace (str s) #"/+$" ""))

(defn endpoints
  "The sign-in server's endpoints, from its discovery document, read once,
  as the document names them (:authorization_endpoint, :token_endpoint,
  ...), with the :issuer it says it is. Throws when there is none."
  [oauth]
  (let [issuer (trim-slash (:issuer oauth))]
    (or (get @discovered issuer)
        (let [{:keys [status body]} (request-json {:url (str issuer "/.well-known/openid-configuration")
                                                   :request-method :get
                                                   :headers {"Accept" "application/json"}})]
          (if (and (= 200 status) (map? body) (:authorization_endpoint body) (:token_endpoint body))
            (get (swap! discovered assoc issuer body) issuer)
            (throw (ex-info (str "the sign-in server at " issuer " has no OpenID configuration (HTTP " status ")")
                            {:issuer issuer :status status})))))))

(defn- client-secret
  "The client secret, written in config.edn or as ${VAR} read from the
  environment or secrets.edn; nil for a public client."
  [oauth]
  (let [s (:client-secret oauth)]
    (if-let [[_ v] (and (string? s) (re-matches #"\$\{([A-Za-z_][A-Za-z0-9_]*)\}" s))]
      (config/secret v)
      (not-empty s))))

(defn- b64url [^bytes bs]
  (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder)) bs))

(defn- random-token
  "`n` random bytes, base64url."
  [n]
  (let [bs (byte-array n)]
    (.nextBytes (java.security.SecureRandom.) bs)
    (b64url bs)))

(defn challenge
  "The PKCE S256 challenge of `verifier`."
  [verifier]
  (b64url (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes (str verifier) "US-ASCII"))))

(defn- basic [id secret]
  (str "Basic " (.encodeToString (java.util.Base64/getEncoder)
                                 (.getBytes (str (codec/form-encode id) ":" (codec/form-encode secret)) "UTF-8"))))

(defn- form-post
  "POST `params` as a form to `url`, as the client: with its secret as
  Basic authorization when it has one, else naming itself in the form."
  [oauth url params]
  (let [secret (client-secret oauth)]
    (request-json {:url url
                   :request-method :post
                   :headers (cond-> {"Content-Type" "application/x-www-form-urlencoded"
                                     "Accept" "application/json"}
                              secret (assoc "Authorization" (basic (:client-id oauth) secret)))
                   :body (codec/form-encode (cond-> params
                                              (not secret) (assoc "client_id" (:client-id oauth))))})))

(defn- userinfo
  "What the server's userinfo endpoint says of the holder of
  `access-token`, or nil when it won't say."
  [oauth access-token]
  (when-let [url (:userinfo_endpoint (endpoints oauth))]
    (let [{:keys [status body]} (request-json {:url url :request-method :get
                                               :headers {"Authorization" (str "Bearer " access-token)
                                                         "Accept" "application/json"}})]
      (when (and (= 200 status) (map? body) (string? (:sub body)))
        body))))

;; --- what a reader may be ----------------------------------------------------------

(defn- groups-of [claims]
  (let [g (:groups claims)] (if (string? g) [g] (seq g))))

(defn refused
  "Why `claims` aren't a reader's, or nil when they are: no subject, or,
  when :groups is set, a groups claim naming none of them."
  [oauth claims]
  (cond
    (str/blank? (str (:sub claims))) "the sign-in named no one"
    (and (seq (:groups oauth)) (not (some (set (:groups oauth)) (groups-of claims))))
    (str "you aren't in a group allowed to read here (" (str/join ", " (:groups oauth)) ")")))

(defn user-for
  "The id of the reader `claims` name, the sign-in of :local-subject the
  local reader's, a new one added."
  [st oauth claims]
  (if (and (:local-subject oauth) (= (:local-subject oauth) (:sub claims)))
    store/local-user-id
    (store/ensure-user! st {:issuer (:issuer (endpoints oauth))
                            :subject (str (:sub claims))
                            :name (or (:name claims) (:preferred_username claims) (:nickname claims))
                            :email (:email claims)})))

;; --- a browser's sign-in ---------------------------------------------------------

(def ^:private pending-ms
  "How long a sign-in may take, from leaving for the server to coming back."
  (* 10 60 1000))

(def ^:private max-pending
  "The most sign-ins under way at once, so that asking for /login over and
  over can't fill the memory."
  10000)

(defn safe-next
  "`next` when it is a path on this site, else \"/\": a sign-in only ever
  comes back here."
  [next]
  (if (and (string? next) (re-matches #"/(?![/\\]).*" next) (not (re-find #"[\r\n]" next)))
    next
    "/"))

(defn begin-login
  "The address to send a browser to so the reader signs in and comes back
  to `next`: the authorization endpoint, with a fresh state, nonce and
  PKCE challenge, the sign-in kept for ten minutes."
  [oauth next]
  (let [ep (:authorization_endpoint (endpoints oauth))
        state (random-token 24)
        nonce (random-token 24)
        verifier (random-token 32)
        now (now-ms)]
    (swap! pending (fn [m]
                     (let [live (into {} (filter (fn [[_ v]] (< (- now (:at v)) pending-ms))) m)]
                       (assoc (if (>= (count live) max-pending) {} live)
                              state {:verifier verifier :nonce nonce :next (safe-next next) :at now}))))
    (str ep (if (str/includes? ep "?") "&" "?")
         (codec/form-encode {"response_type" "code"
                             "client_id" (:client-id oauth)
                             "redirect_uri" (:redirect-uri oauth)
                             "scope" (str/join " " (:scopes oauth))
                             "state" state
                             "nonce" nonce
                             "code_challenge" (challenge verifier)
                             "code_challenge_method" "S256"}))))

(defn jwt-claims
  "The claims of the JWT `token`, unverified, or nil when it isn't one."
  [token]
  (try
    (let [[_ payload] (str/split (str token) #"\.")]
      (json/read-str (String. ^bytes (.decode (java.util.Base64/getUrlDecoder) ^String payload) "UTF-8")
                     :key-fn keyword))
    (catch Throwable _ nil)))

(defn- id-token-error
  "Why the ID token's `claims` aren't for this sign-in, or nil."
  [oauth claims nonce]
  (let [aud (:aud claims)
        auds (if (string? aud) #{aud} (set aud))]
    (cond
      (nil? claims) "the sign-in server's ID token couldn't be read"
      (not= (:issuer (endpoints oauth)) (:iss claims)) "the ID token is from another server"
      (not (contains? auds (:client-id oauth))) "the ID token is for another application"
      (not (number? (:exp claims))) "the ID token has no expiry"
      (<= (* 1000 (:exp claims)) (now-ms)) "the ID token has expired"
      (not= nonce (:nonce claims)) "the ID token isn't for this sign-in"
      (str/blank? (str (:sub claims))) "the ID token names no one")))

(defn finish-login
  "The reader the server's answer to a sign-in names, from the params the
  browser came back to /oauth/callback with: {:claims :id-token :next},
  or {:error why}. A sign-in can be finished once."
  [oauth {:strs [code state error error_description iss]}]
  (let [[old _] (swap-vals! pending dissoc state)
        p (get old state)]
    (cond
      error {:error (str "the sign-in server said " error (when error_description (str ": " error_description)))}
      (or (nil? p) (>= (- (now-ms) (:at p)) pending-ms)) {:error "this sign-in has expired or wasn't started here"}
      (and iss (not= iss (:issuer (endpoints oauth)))) {:error "the answer came from another sign-in server"}
      (str/blank? code) {:error "the sign-in server sent no code"}
      :else
      (let [{:keys [status body]} (form-post oauth (:token_endpoint (endpoints oauth))
                                             {"grant_type" "authorization_code"
                                              "code" code
                                              "redirect_uri" (:redirect-uri oauth)
                                              "code_verifier" (:verifier p)})]
        (if-not (and (= 200 status) (map? body) (:id_token body))
          {:error (str "the sign-in server wouldn't trade the code (HTTP " status
                       (when (map? body) (some->> (:error body) (str ": ")))
                       ")")}
          (let [claims (jwt-claims (:id_token body))]
            (if-let [why (id-token-error oauth claims (:nonce p))]
              {:error why}
              (let [info (some->> (:access_token body) (userinfo oauth))
                    claims (merge (when (= (:sub info) (:sub claims)) info) claims)]
                (if-let [why (refused oauth claims)]
                  {:error why}
                  {:claims claims :id-token (:id_token body) :next (:next p)})))))))))

(defn logout-url
  "Where to send a browser once it has signed out here, so the server
  signs it out too: its end-session endpoint, with the ID token it signed
  in with as the hint, and :post-logout-redirect-uri when it is set; nil
  when the server has none."
  [oauth id-token]
  (when-let [ep (:end_session_endpoint (endpoints oauth))]
    (str ep (if (str/includes? ep "?") "&" "?")
         (codec/form-encode (cond-> {"client_id" (:client-id oauth)}
                              id-token (assoc "id_token_hint" id-token)
                              (:post-logout-redirect-uri oauth)
                              (assoc "post_logout_redirect_uri" (:post-logout-redirect-uri oauth)))))))

;; --- a program's token ------------------------------------------------------------

(def ^:private check-ms
  "How long what the server said of a token is kept: a few minutes, so a
  token revoked there stops working here soon after."
  (* 5 60 1000))

(def ^:private refused-ms
  "How long a token the server refused is remembered as refused."
  30000)

(defn token-for-us?
  "Whether the introspection answer `info` is of a token newsroom takes:
  issued to one of :token-clients, and with no audience but newsroom."
  [oauth info]
  (let [clients (:token-clients oauth)
        aud (:aud info)
        auds (if (string? aud) #{aud} (set aud))]
    (or (= :any clients)
        (and (contains? (set (or clients [(:client-id oauth)])) (:client_id info))
             (or (empty? auds) (contains? auds (:client-id oauth)))))))

(defn- check-token
  "What the server says of the holder of access token `token`, or nil
  when it isn't an active token of a reader's, issued for newsroom."
  [oauth token]
  (let [ep (endpoints oauth)]
    (cond
      (and (client-secret oauth) (:introspection_endpoint ep))
      (let [{:keys [status body]} (form-post oauth (:introspection_endpoint ep)
                                             {"token" token "token_type_hint" "access_token"})]
        (when (and (= 200 status) (map? body) (true? (:active body)) (string? (:sub body))
                   (token-for-us? oauth body))
          (if (or (:email body) (:name body) (not (:userinfo_endpoint ep)))
            body
            ;; introspection says whose it is; userinfo, given the scope, who they are
            (merge (let [info (userinfo oauth token)] (when (= (:sub info) (:sub body)) info)) body))))

      ;; userinfo can't say whom a token was issued to
      (= :any (:token-clients oauth)) (userinfo oauth token))))

(defn token-claims
  "What the server says of the holder of the access token `token`, kept
  for a few minutes, or nil when it isn't a reader's."
  [oauth token]
  (let [k (config/sha-256 token)
        now (now-ms)
        hit (get @checked k)]
    (if (and hit (< now (:until hit)))
      (:claims hit)
      (let [claims (check-token oauth token)
            until (if claims
                    (min (+ now check-ms) (if (number? (:exp claims)) (* 1000 (:exp claims)) Long/MAX_VALUE))
                    (+ now refused-ms))]
        (swap! checked (fn [m]
                         (assoc (into {} (filter (fn [[_ v]] (< now (:until v)))) m)
                                k {:claims claims :until until})))
        claims))))

;; --- sessions ----------------------------------------------------------------------

(def cookie-name "newsroom_session")

(defn session-cookie
  "The Set-Cookie value for the session `value`, lasting `max-age`
  seconds: kept from scripts and from requests other sites make, and only
  sent over https when the sign-in comes back over https."
  [oauth value max-age]
  (str cookie-name "=" value "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" max-age
       (when (str/starts-with? (str (:redirect-uri oauth)) "https:") "; Secure")))

(defn new-session!
  "A session for the reader `uid`, for :session-days: the cookie's value,
  which the database keeps only the hash of."
  [st oauth uid id-token]
  (let [value (random-token 32)]
    (store/save-session! st {:id (config/sha-256 value) :user-id uid :id-token id-token
                             :expires-at (+ (now-ms) (* (:session-days oauth) 24 60 60 1000))})
    value))

(defn cookies
  "The request's cookies, {name value}."
  [req]
  (into {} (for [part (str/split (str (get-in req [:headers "cookie"])) #";\s*")
                 :let [[k v] (str/split part #"=" 2)]
                 :when (and (not (str/blank? k)) v)]
             [(str/trim k) (str/trim v)])))

(defn session-of
  "The session the request's cookie names, or nil."
  [st req]
  (when-let [c (get (cookies req) cookie-name)]
    (store/session st (config/sha-256 c))))

(defn- bearer [req]
  (some->> (get-in req [:headers "authorization"]) (re-matches #"(?i)Bearer\s+(\S+)") second))

;; --- the middleware ---------------------------------------------------------------

(def ^:private open-paths
  "What a reader who hasn't signed in may ask for: signing in and out, and
  the page's scripts and styles."
  #{"/login" "/oauth/callback" "/logout"})

(defn- open? [uri]
  (or (contains? open-paths uri) (str/starts-with? (str uri) "/js/") (str/starts-with? (str uri) "/css/")))

(defn reader-of
  "The id of the reader `req` comes from, or nil when it names none: the
  local reader, when newsroom runs for one; else the holder of its bearer
  token or its session."
  [st cfg req]
  (if (config/single-user? cfg)
    store/local-user-id
    (let [oauth (oauth-config cfg)]
      (if-let [token (bearer req)]
        (when-let [claims (token-claims oauth token)]
          (when-not (refused oauth claims)
            (user-for st oauth claims)))
        (:user-id (session-of st req))))))

(defn- page-request? [{:keys [request-method headers] :as req}]
  (and (= :get request-method)
       (not (:jolt.datastar/sse-request req))
       (str/includes? (str (get headers "accept")) "text/html")))

(defn wrap-reader
  "Name the reader each request comes from as :newsroom/user, the id of
  their user row. `opts` has :store and :config, functions returning the
  store and the config newsroom runs with now. A request that names no
  reader, with several, is sent to sign in when it is a browser's page,
  else answered 401; signing in and the assets need no reader."
  [handler {:keys [store config]}]
  (fn [req]
    (let [cfg (config)
          uid (try (reader-of (store) cfg req)
                   (catch Throwable e
                     (binding [*out* *err*] (println "couldn't check who is reading:" (ex-message e)))
                     nil))]
      (cond
        uid (handler (assoc req :newsroom/user uid))
        (open? (:uri req)) (handler req)
        (page-request? req)
        {:status 302
         :headers {"Location" (str "/login?next=" (codec/url-encode (str (:uri req) (some->> (:query-string req) (str "?")))))}
         :body ""}
        :else
        {:status 401
         :headers {"Content-Type" "text/plain" "WWW-Authenticate" "Bearer realm=\"newsroom\""}
         :body "sign in, or send a bearer token from the sign-in server\n"}))))
