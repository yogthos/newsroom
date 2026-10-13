(ns newsroom.auth-test
  "Signing readers in with an OpenID Connect server, against a fake one:
  the code flow with PKCE for a browser, a bearer token for a program, and
  the sessions and checks in between."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [newsroom.auth :as auth]
            [newsroom.config :as config]
            [newsroom.store :as store]
            [ring.util.codec :as codec]))

(def ^:private issuer "https://auth.example")

(def ^:private oauth
  (auth/oauth-config {:oauth {:issuer issuer :client-id "newsroom" :redirect-uri "https://news.example/oauth/callback"}}))

(def ^:private discovery
  {:issuer issuer
   :authorization_endpoint (str issuer "/auth")
   :token_endpoint (str issuer "/token")
   :userinfo_endpoint (str issuer "/me")
   :introspection_endpoint (str issuer "/token/introspection")
   :end_session_endpoint (str issuer "/session/end")})

(defn- b64 [s]
  (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder)) (.getBytes s "UTF-8")))

(defn- jwt [claims]
  (str (b64 (json/write-str {:alg "RS256" :kid "k"})) "." (b64 (json/write-str claims)) ".c2lnbmF0dXJl"))

(defn- query [url]
  (codec/form-decode (second (str/split url #"\?" 2))))

(defn- now-s [] (quot (System/currentTimeMillis) 1000))

(defn- fake-server
  "A request-json that answers as the sign-in server would, its requests
  kept in `seen`. `id-claims` are the ID token's, over sensible ones."
  [seen {:keys [id-claims userinfo token-status introspection]}]
  (fn [{:keys [url] :as req}]
    (swap! seen conj req)
    (cond
      (str/ends-with? url "/.well-known/openid-configuration") {:status 200 :body discovery}
      (= url (:token_endpoint discovery))
      (let [form (codec/form-decode (:body req))]
        (if (= 200 (or token-status 200))
          {:status 200
           :body {:access_token "at-1" :token_type "Bearer"
                  :id_token (jwt (merge {:iss issuer :aud "newsroom" :sub "reader-1"
                                         :exp (+ (now-s) 600) :iat (now-s)
                                         ;; the tests' codes carry the nonce, which the
                                         ;; real server keeps from the authorization request
                                         :nonce (get form "code")}
                                        id-claims))}}
          {:status token-status :body {:error "invalid_grant"}}))
      (= url (:userinfo_endpoint discovery))
      (if (= "Bearer at-1" (get-in req [:headers "Authorization"]))
        {:status 200 :body (merge {:sub "reader-1" :name "Rea Der" :email "r@example.com"} userinfo)}
        {:status 401 :body {:error "invalid_token"}})
      (= url (:introspection_endpoint discovery))
      {:status 200 :body (or introspection {:active false})}
      :else {:status 404 :body "not found"})))

(defn- with-server [opts f]
  (let [seen (atom [])]
    (auth/forget!)
    (with-redefs [auth/request-json (fake-server seen opts)]
      (f seen))))

(defn- sign-in
  "The sign-in's answer once the browser comes back from the server with
  a code: begin, then finish with the state the server hands back."
  ([] (sign-in {}))
  ([extra]
   (let [q (query (auth/begin-login oauth "/day/2026-09-30"))]
     (auth/finish-login oauth (merge {"code" (get q "nonce") "state" (get q "state")} extra)))))

(deftest the-pkce-challenge-is-s256
  (is (= "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
         (auth/challenge "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
      "RFC 7636's example"))

(deftest a-browser-signs-in-with-the-code-flow
  (with-server {}
    (fn [seen]
      (let [url (auth/begin-login oauth "/day/2026-09-30")
            q (query url)]
        (testing "the browser is sent to the authorization endpoint with a challenge"
          (is (str/starts-with? url (str issuer "/auth?")))
          (is (= {"response_type" "code" "client_id" "newsroom" "redirect_uri" "https://news.example/oauth/callback"
                  "scope" "openid profile email" "code_challenge_method" "S256"}
                 (select-keys q ["response_type" "client_id" "redirect_uri" "scope" "code_challenge_method"])))
          (is (every? #(not (str/blank? (get q %))) ["state" "nonce" "code_challenge"])))
        (let [{:keys [claims id-token next error]} (auth/finish-login oauth {"code" (get q "nonce") "state" (get q "state")})
              token-req (some #(when (= (:token_endpoint discovery) (:url %)) %) @seen)
              form (codec/form-decode (:body token-req))]
          (is (nil? error))
          (testing "the code is traded with its verifier"
            (is (= "authorization_code" (get form "grant_type")))
            (is (= (get q "nonce") (get form "code")))
            (is (= (get q "code_challenge") (auth/challenge (get form "code_verifier"))))
            (is (= "newsroom" (get form "client_id")) "a public client names itself"))
          (is (= "reader-1" (:sub claims)))
          (is (= "Rea Der" (:name claims)) "with what userinfo says of the reader")
          (is (string? id-token))
          (is (= "/day/2026-09-30" next)))
        (testing "a state is good for one sign-in"
          (is (:error (auth/finish-login oauth {"code" (get q "nonce") "state" (get q "state")}))))))))

(deftest an-id-token-that-isnt-for-this-sign-in-is-refused
  (doseq [[why claims] [["another issuer" {:iss "https://evil.example"}]
                        ["another client" {:aud "someone-else"}]
                        ["expired" {:exp (- (now-s) 10)}]
                        ["another nonce" {:nonce "replayed"}]
                        ["no subject" {:sub nil}]]]
    (with-server {:id-claims claims}
      (fn [_] (is (:error (sign-in)) why)))))

(deftest a-sign-in-the-server-turns-down-says-why
  (with-server {:token-status 400}
    (fn [_] (is (str/includes? (:error (sign-in)) "400"))))
  (with-server {}
    (fn [_]
      (is (str/includes? (:error (sign-in {"error" "access_denied"})) "access_denied"))
      (is (:error (auth/finish-login oauth {"code" "c" "state" "never-made"})))))
  (with-server {}
    (fn [_] (is (:error (sign-in {"iss" "https://evil.example"})) "the answer came from another server"))))

(deftest a-reader-outside-the-groups-is-refused
  (let [oauth (assoc oauth :groups ["newsroom"])]
    (with-server {:userinfo {:groups ["staff"]}}
      (fn [_] (is (str/includes? (str (:error (let [url (auth/begin-login oauth "/")]
                                                (auth/finish-login oauth {"code" (get (query url) "nonce") "state" (get (query url) "state")}))))
                                 "group"))))
    (with-server {:userinfo {:groups ["staff" "newsroom"]}}
      (fn [_] (is (nil? (:error (let [url (auth/begin-login oauth "/")]
                                  (auth/finish-login oauth {"code" (get (query url) "nonce") "state" (get (query url) "state")})))))))))

(deftest a-bearer-token-is-checked-with-the-server-and-kept-a-while
  (with-server {}
    (fn [seen]
      (is (= "reader-1" (:sub (auth/token-claims oauth "at-1"))))
      (is (= "reader-1" (:sub (auth/token-claims oauth "at-1"))))
      (is (= 1 (count (filter #(= (:userinfo_endpoint discovery) (:url %)) @seen))) "asked once")
      (is (nil? (auth/token-claims oauth "forged")))))
  (testing "with a client secret, by introspection"
    (with-server {:introspection {:active true :sub "reader-2" :exp (+ (now-s) 600)}}
      (fn [seen]
        (let [oauth (assoc oauth :client-secret "s3cret")]
          (is (= "reader-2" (:sub (auth/token-claims oauth "opaque"))))
          (let [req (some #(when (= (:introspection_endpoint discovery) (:url %)) %) @seen)]
            (is (= (str "Basic " (.encodeToString (java.util.Base64/getEncoder) (.getBytes "newsroom:s3cret" "UTF-8")))
                   (get-in req [:headers "Authorization"])))
            (is (= "opaque" (get (codec/form-decode (:body req)) "token")))))))
    (with-server {:introspection {:active false}}
      (fn [_] (is (nil? (auth/token-claims (assoc oauth :client-secret "s3cret") "revoked")))))))

(deftest the-next-page-is-only-ever-this-sites
  (is (= "/day/2026-09-30?x=1" (auth/safe-next "/day/2026-09-30?x=1")))
  (doseq [bad [nil "" "https://evil.example/" "//evil.example/x" "/\\evil.example" "javascript:alert(1)"]]
    (is (= "/" (auth/safe-next bad)) (pr-str bad))))

(defn- app [st cfg]
  (auth/wrap-reader (fn [req] {:status 200 :body (str "reader " (:newsroom/user req))})
                    {:store (constantly st) :config (constantly cfg)}))

(deftest one-reader-needs-no-sign-in
  (let [st (store/open "sqlite::memory:")]
    (try
      (is (= (str "reader " store/local-user-id)
             (:body ((app st {}) {:uri "/day/2026-09-30" :request-method :get :headers {}}))))
      (finally (store/close st)))))

(deftest with-several-readers-each-request-names-its-reader
  (let [st (store/open "sqlite::memory:")
        cfg {:single-user false :oauth oauth}
        handler (app st cfg)]
    (try
      (with-server {}
        (fn [_]
          (testing "a page asked for without signing in sends the browser to sign in"
            (let [resp (handler {:uri "/day/2026-09-30" :query-string "a=1" :request-method :get
                                 :headers {"accept" "text/html"}})]
              (is (= 302 (:status resp)))
              (is (= "/login?next=%2Fday%2F2026-09-30%3Fa%3D1" (get-in resp [:headers "Location"])))))
          (testing "a program is told it needs a token"
            (let [resp (handler {:uri "/run" :request-method :post :headers {}})]
              (is (= 401 (:status resp)))
              (is (str/starts-with? (get-in resp [:headers "WWW-Authenticate"]) "Bearer"))))
          (testing "the sign-in pages and the assets are open"
            (is (= 200 (:status (handler {:uri "/login" :request-method :get :headers {}}))))
            (is (= 200 (:status (handler {:uri "/css/style.css" :request-method :get :headers {}})))))
          (testing "a bearer token names its reader"
            (let [resp (handler {:uri "/run" :request-method :post :headers {"authorization" "Bearer at-1"}})
                  uid (store/ensure-user! st {:issuer issuer :subject "reader-1"})]
              (is (= (str "reader " uid) (:body resp)))
              (is (not= store/local-user-id uid))
              (is (= "Rea Der" (:name (store/user st uid))))))
          (testing "a forged one is refused"
            (is (= 401 (:status (handler {:uri "/run" :request-method :post
                                          :headers {"authorization" "Bearer forged"}})))))
          (testing "a session cookie names its reader"
            (let [uid (store/ensure-user! st {:issuer issuer :subject "reader-9"})
                  cookie (auth/new-session! st (auth/oauth-config cfg) uid "idt")]
              (is (= (str "reader " uid)
                     (:body (handler {:uri "/" :request-method :get
                                      :headers {"cookie" (str "other=1; " auth/cookie-name "=" cookie)}}))))
              (is (= 302 (:status (handler {:uri "/" :request-method :get
                                            :headers {"accept" "text/html" "cookie" (str auth/cookie-name "=nope")}}))))
              (is (nil? (store/session st cookie)) "the database keeps only the cookie's hash")
              (is (some? (store/session st (config/sha-256 cookie))))))
          (testing "the sign-in of :local-subject is the local reader"
            (let [handler (app st (assoc-in cfg [:oauth :local-subject] "reader-1"))]
              (is (= (str "reader " store/local-user-id)
                     (:body (handler {:uri "/run" :request-method :post :headers {"authorization" "Bearer at-1"}}))))))))
      (finally (store/close st)))))

(deftest the-session-cookie-is-kept-from-scripts-and-other-sites
  (let [c (auth/session-cookie (auth/oauth-config {:oauth oauth}) "v" 60)]
    (is (str/starts-with? c (str auth/cookie-name "=v;")))
    (doseq [part ["Path=/" "HttpOnly" "SameSite=Lax" "Max-Age=60" "Secure"]]
      (is (str/includes? c part) part)))
  (is (not (str/includes? (auth/session-cookie (auth/oauth-config {:oauth (assoc oauth :redirect-uri "http://127.0.0.1:3000/oauth/callback")}) "v" 60)
                          "Secure"))
      "a plain http address can't keep a Secure cookie"))
