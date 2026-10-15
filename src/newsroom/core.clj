(ns newsroom.core
  "The HTTP edge and the boot sequence.

    GET  /                  the latest briefing (or today's page)
    GET  /day/YYYY-MM-DD    a day's page; the same path, as datastar's SSE
                            stream, re-renders it live
    GET  /day/YYYY-MM-DD.md the day's briefing as markdown
    GET  /day/YYYY-MM-DD.mp3
                            the day's podcast, a Range of it when one is
                            asked for
    GET  /week/YYYY-Www     a week's digest, live like a day's page, and
    GET  /month/YYYY-MM     a month's; either with .md as markdown
    GET  /stories           every storyline the briefings keep notes on
    GET  /story/DAY/N       a storyline's notes and coverage
    GET  /record            how the projections held, and the sources'
                            standings within each subject
    GET  /search?q=...      search the briefings, digests, sources and
                            storylines; &kind= narrows to one kind,
                            &sort=newest puts the latest first, &page=
                            pages through
    GET  /config            the settings, and a form to change them
    POST /config            save the settings and run with them from now on
    GET  /config/export     the settings as EDN, in config.edn's form
    POST /config/import     save the settings in an EDN file or text
    POST /config/test-source
                            read one source of the config page's form, as
                            a run would, without saving it
    POST /config/test-provider
                            ask one provider of the form, or a built-in, a
                            one-word question, without saving it
    POST /run?day=...       gather and analyse a day (today by default);
                            &again=true analyses the news its last run
                            read again, &fresh=true asks the models afresh
    POST /digest?kind=week&period=YYYY-Www
                            write a digest (kind week or month)
    POST /cancel            cancel the run in flight
    POST /podcast?day=...   write and record a stored day's podcast again,
                            when :podcast is on
    POST /ask               a report on the reader's topics, or an answer
                            in a conversation about them, streamed as
                            server-sent events (see newsroom.ask)
    GET  /login?next=...    sign in with the OpenID Connect server, with
                            several readers (see newsroom.auth)
    GET  /oauth/callback    where the server sends the browser back
    POST /logout            sign out

  Every page and action is the reader's own: with several, the reader a
  request's session or bearer token names, and otherwise the local
  reader. Each has their settings, schedule and runs; `system` keeps the
  local reader's at :config and :schedule and every other's under :users,
  by id."
  (:require [clojure.core.async :as async]
            [newsroom.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.datastar.html :as h]
            [jolt.datastar.core :as ds]
            [ring-chez.adapter :as adapter]
            [ring-chez.middleware.multipart :as multipart]
            [ring-chez.sse :as sse]
            [ring.middleware.params :as params]
            [ruuter.core :as ruuter]
            [newsroom.ask :as ask]
            [newsroom.auth :as auth]
            [newsroom.config :as config]
            [newsroom.config-page :as config-page]
            [newsroom.llm.client :as llm]
            [newsroom.llm.provider :as provider]
            [newsroom.llm.providers :as providers]
            [newsroom.narration :as narration]
            [newsroom.tts :as tts]
            [newsroom.news :as news]
            [newsroom.pipeline :as pipeline]
            [newsroom.plugin :as plugin]
            [newsroom.screen :as screen]
            [newsroom.search :as search]
            [newsroom.settings :as settings]
            [newsroom.sources :as sources]
            [newsroom.store :as store]
            [newsroom.trends :as trends]
            [newsroom.ui :as ui]))

(defonce system (atom nil))

(defn- local? [uid] (or (nil? uid) (= store/local-user-id uid)))

(defn- static [] (:file @system))

(defn- seen-config
  "The config the reader `uid` runs with, read from their settings the
  first time it is wanted."
  [uid]
  (if (local? uid)
    (:config @system)
    (or (get-in @system [:users uid :config])
        (let [st (store/for-user (:store @system) uid)
              _ (settings/seed-prompts! st)
              cfg (config/effective (static) (settings/stored st))]
          (get-in (swap! system update-in [:users uid :config] #(or % cfg)) [:users uid :config])))))

(defn- user-ctx
  "What a run, a page or an answer for the reader `uid` works with:
  {:config :store}, their config and the store scoped to them."
  [uid]
  {:config (seen-config uid) :store (store/for-user (:store @system) (or uid store/local-user-id))})

(defn- ctx
  "The ctx of the reader `req` comes from."
  [req]
  (user-ctx (:newsroom/user req)))

(defn- html [body] {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"} :body body})

(defn- not-found [] {:status 404 :headers {"Content-Type" "text/plain"} :body "not found\n"})

(defn- redirect [to] {:status 302 :headers {"Location" to} :body ""})

(defn- start-schedule!
  "The reader `uid`'s schedule, reading their config as it is at each
  wake."
  [uid]
  (pipeline/start-schedule! (assoc (user-ctx uid) :current-config #(seen-config uid))))

(defn- schedule-path [uid] (if (local? uid) [:schedule] [:users uid :schedule]))

(defn reload!
  "Run with `stored`, the settings the reader `uid` (the local one by
  default) just saved, from now on: the config their next run and the
  plugins read, and their schedule, restarted when its times changed. A
  run already going keeps the config it started with."
  ([stored] (reload! nil stored))
  ([uid stored]
   (let [path (if (local? uid) [:config] [:users uid :config])
         old (get-in @system path)
         cfg (config/effective (static) stored)]
     (swap! system assoc-in path cfg)
     (when (local? uid)
       (reset! ui/config cfg)
       (plugin/set-config! cfg))
     (when (not= (select-keys old [:run-at :run-every-hours]) (select-keys cfg [:run-at :run-every-hours]))
       (when-let [schedule (get-in @system (schedule-path uid))] (schedule))
       (swap! system assoc-in (schedule-path uid) (start-schedule! uid)))
     cfg)))

(defn- ensure-reader!
  "Make sure the reader `uid`'s schedule runs, as it does from their first
  sign-in."
  [uid]
  (seen-config uid)
  (when-not (get-in @system (schedule-path uid))
    (swap! system assoc-in (schedule-path uid) (start-schedule! uid))))

(defn test-source
  "Read the source the form `params` holds at `prefix` as a run would, for
  today, under its policy, retries and all: {:errors} when its fields are
  wrong, else {:items :events}, or {:error :events} when reading it failed
  or took too long. Nothing is saved."
  [cfg params prefix]
  (let [{:keys [source errors]} (settings/source-at params prefix)]
    (if (seq errors)
      {:errors errors}
      (let [events (atom [])
            timeout (:timeout-ms (sources/policy source cfg))
            task (future
                   (try {:items (sources/read-source source {:day (pipeline/today) :config cfg
                                                             :emit #(swap! events conj %)})}
                        (catch Throwable e {:error (or (ex-message e) (str e))})))
            result (deref task timeout ::timeout)]
        (if (= ::timeout result)
          (do (future-cancel task)
              {:error (str "took longer than " (quot timeout 1000) "s, its timeout") :events @events})
          (assoc result :events @events :name (sources/source-name source)))))))

(def ^:private provider-test-ms
  "The longest a provider's test waits on it: a short answer, not a briefing."
  60000)

(defn test-provider
  "Ask the provider `prefix` names a one-word question, as a run would ask
  it: the entry the form `params` holds there, providers.3 say, or else
  the built-in of that name as it is. {:errors} when the entry's fields are
  wrong, {:error} when it can't be resolved or reached, else {:alias :model
  :base-url :reply :ms}. Nothing is saved, and the key isn't returned.
  `chat` is the model call, llm/chat by default."
  ([cfg params prefix] (test-provider cfg params prefix llm/chat))
  ([cfg params prefix chat]
   (let [{:keys [alias entry errors]} (if (str/starts-with? prefix "providers.")
                                        (settings/provider-at params prefix)
                                        {:alias (keyword prefix)})]
     (if (seq errors)
       {:errors errors}
       (let [task (future
                    (try
                      (let [llm (-> (providers/resolve-provider (assoc cfg :providers (if entry {alias entry} {}))
                                                                alias)
                                    (update :timeout-ms #(min (or % provider-test-ms) provider-test-ms)))
                            _ (when (and (contains? llm :api-key) (nil? (:api-key llm)))
                                (throw (ex-info (str "it has no key: set "
                                                     (or (:api-key-env entry) (:key-env (provider/preset (:type llm))))
                                                     " in the environment or secrets.edn, or type one in")
                                                {})))
                            start (System/currentTimeMillis)
                            reply (chat llm {:messages [{:role "user" :content "Reply with the one word: ready"}]
                                             :max-tokens (min (or (:max-tokens llm) 1024) 1024)})]
                        {:alias alias :model (or (:model reply) (:model llm)) :base-url (:base-url llm)
                         :reply (str/trim (str (:content reply))) :ms (- (System/currentTimeMillis) start)})
                      (catch Throwable e {:error (or (ex-message e) (str e))})))
             result (deref task (+ provider-test-ms 5000) ::timeout)]
         (if (= ::timeout result)
           (do (future-cancel task)
               {:error (str "it didn't answer in " (quot provider-test-ms 1000) "s")})
           result))))))

(defn- current-form [req] (settings/to-form (:config (ctx req))))

(defn- import-settings
  "Save the settings in the EDN of an uploaded file or the pasted text,
  over the ones there are."
  [req]
  (let [{:keys [params]} req
        {:keys [config store]} (ctx req)
        file (get params "file")
        file (if (vector? file) (peek file) file)
        text (if (pos? (count (:bytes file)))
               (String. ^bytes (:bytes file) "UTF-8")
               (str (get params "edn")))
        {:keys [settings errors]} (settings/from-edn text)]
    (cond
      (str/blank? text)
      (assoc (html (ui/page store {:config {:tree (current-form req)
                                            :import {:errors {"EDN" "is empty: choose a file or paste some"}}}}))
             :status 422)

      (seq errors)
      (assoc (html (ui/page store {:config {:tree (current-form req)
                                            :import {:errors errors :text text}}}))
             :status 422)

      :else
      (let [all (merge (select-keys config settings/setting-keys) settings)]
        (settings/save! store all)
        (reload! (:newsroom/user req) all)
        {:status 303 :headers {"Location" "/config?imported=1"} :body ""}))))

(defn- config-page
  [req]
  (let [st (:store (ctx req))]
    (if (= :post (:request-method req))
      (let [params (:params req)
            {:keys [settings errors]} (settings/from-form params)]
        (if (seq errors)
          (assoc (html (ui/page st {:config {:tree (settings/form-tree params) :errors errors}})) :status 422)
          (do (settings/save! st settings)
              (reload! (:newsroom/user req) settings)
              {:status 303 :headers {"Location" "/config?saved=1"} :body ""})))
      (html (ui/page st {:config {:tree (current-form req)
                                  :saved? (= "1" (get-in req [:params "saved"]))
                                  :imported? (= "1" (get-in req [:params "imported"]))}})))))

(def ^:private assets
  "The files served from resources/public, by path, with their content type.
  They are read through io/resource, which reaches the copies baked into a
  built binary, where Ring's resource middleware can't. The page asks for
  each with a version from its content (see newsroom.ui/asset), so one can
  be cached for long and a changed one is still fetched anew."
  {"/js/datastar.js" "application/javascript"
   "/js/diagrams.js" "application/javascript"
   "/js/config.js"   "application/javascript"
   "/js/topics.js"   "application/javascript"
   "/css/style.css"  "text/css; charset=utf-8"})

(defn- asset [{:keys [uri]}]
  (if-let [type (assets uri)]
    {:status 200
     :headers {"Content-Type" type "Cache-Control" "max-age=31536000, immutable"}
     :body (slurp (io/resource (str "public" uri)))}
    (not-found)))

(defn- live
  "The page for `current`, or, when the request is datastar's stream, the
  part of it the stream patches."
  [{:keys [jolt.datastar/sse-request jolt.datastar/selector] :as req} current]
  (let [st (:store (ctx req))]
    (if sse-request
      ;; the string h/html returned, as is: the stream finds its parts by it
      (html (h/html (ui/fragment st current selector)))
      (html (ui/page st current)))))

(defn- markdown [doc]
  (if doc
    {:status 200 :headers {"Content-Type" "text/markdown; charset=utf-8"} :body (:markdown doc)}
    (not-found)))

(defn- with-md
  "A path part and whether it ended in .md: [\"2026-09-30\" true]."
  [part]
  (if (str/ends-with? part ".md") [(subs part 0 (- (count part) 3)) true] [part false]))

(defn byte-range
  "The bytes the Range `header` asks for of a body `size` long, as [from
  to], both inclusive; :unsatisfiable when none of them are there, and nil
  for the whole body, when there is no header or one it can't read. A
  header asking for several ranges is answered with the whole body, which
  a server may always do."
  [header size]
  (when-let [[_ from to] (some->> header str/trim (re-matches #"bytes=(\d{0,15})-(\d{0,15})"))]
    (let [from (when-not (str/blank? from) (parse-long from))
          to (when-not (str/blank? to) (parse-long to))]
      (cond
        (and (nil? from) (nil? to)) nil
        (zero? size) :unsatisfiable
        ;; bytes=-n is the last n
        (nil? from) (if (pos? to) [(max 0 (- size to)) (dec size)] :unsatisfiable)
        (and to (< to from)) nil
        (>= from size) :unsatisfiable
        :else [from (min (dec size) (or to (dec size)))]))))

(defn- audio
  "The bytes `mp3` as an answer to `req`: all of them, or the range its
  Range header asks for."
  [req mp3]
  (if-not mp3
    (not-found)
    (let [size (alength ^bytes mp3)
          range (byte-range (get-in req [:headers "range"]) size)
          headers {"Content-Type" "audio/mpeg" "Accept-Ranges" "bytes" "Cache-Control" "no-cache"}]
      (cond
        (nil? range) {:status 200 :headers headers :body mp3}
        (= :unsatisfiable range) {:status 416 :headers (assoc headers "Content-Range" (str "bytes */" size))
                                  :body (byte-array 0)}
        :else (let [[from to] range]
                {:status 206
                 :headers (assoc headers "Content-Range" (str "bytes " from "-" to "/" size))
                 :body (java.util.Arrays/copyOfRange ^bytes mp3 (int from) (int (inc to)))})))))

(defn- day-page [{:keys [params] :as req}]
  (let [part (:day params)
        mp3? (str/ends-with? part ".mp3")
        [day md?] (with-md (if mp3? (subs part 0 (- (count part) 4)) part))]
    (cond
      (not (news/valid-day? day)) (not-found)
      mp3? (audio req (store/narration-audio (:store (ctx req)) day))
      md? (markdown (store/day (:store (ctx req)) day))
      :else (live req day))))

(defn- digest-page [kind]
  (fn [{:keys [params] :as req}]
    (let [[period md?] (with-md (:period params))]
      (cond
        (not (trends/period-range kind period)) (not-found)
        md? (markdown (store/digest (:store (ctx req)) kind period))
        :else (live req {:kind kind :period period})))))

(defn- start-run [{:keys [params] :as req}]
  (let [day (get params "day" (pipeline/today))]
    (if (news/valid-day? day)
      (do (pipeline/start-run! (ctx req) day {:reread? (= "true" (get params "again"))
                                                :fresh? (= "true" (get params "fresh"))})
          (ds/patch-signals {}))
      {:status 400 :body "bad day\n"})))

(defn- start-podcast [{:keys [params] :as req}]
  (let [day (get params "day")
        c (ctx req)]
    (cond
      (not (and day (news/valid-day? day))) {:status 400 :body "bad day\n"}
      (false? (get-in c [:config :podcast])) {:status 409 :body "podcasts are turned off\n"}
      :else (do (pipeline/narrate! c day)
                (ds/patch-signals {})))))

(defn- start-digest [{:keys [params] :as req}]
  (let [kind (some-> (get params "kind") keyword)
        period (get params "period")]
    (if (and (contains? trends/kinds kind) (trends/period-range kind period))
      (do (pipeline/start-digest! (ctx req) kind period)
          (ds/patch-signals {}))
      {:status 400 :body "bad digest\n"})))

(defn- story-page [{:keys [params] :as req}]
  (if (and (news/valid-day? (:day params)) (re-matches #"\d+" (:n params)))
    (live req {:story (str (:day params) "/" (:n params))})
    (not-found)))

(defn- search-query
  "A search's query from the request's params; a kind or sort it doesn't
  know is ignored."
  [{:keys [params]}]
  (let [kind (some-> (get params "kind") keyword)
        page (some->> (get params "page") (re-matches #"\d{1,6}") parse-long)]
    {:q (str/trim (str (get params "q")))
     :kind (some #{kind} search/kinds)
     :sort (when (= "newest" (get params "sort")) :newest)
     :page page}))

(defn- ask-page
  "The reader's topics put to the analyst: the request's JSON read (see
  newsroom.ask/read-request) and the answer streamed back as server-sent
  events, each event's data JSON. The answer is worked out on a thread of
  its own, and the stream ends with it."
  [{:keys [body] :as req}]
  (if-let [request (ask/read-request (try (some-> body slurp json/read-str) (catch Throwable _ nil)))]
    (let [ch (async/chan 256)
          c (ctx req)
          emit (fn [event data] (async/>!! ch (sse/format-event {:event event :data (json/write-str data)})))]
      (future
        (try (ask/answer! c request emit)
             (finally (async/close! ch))))
      (sse/event-response ch))
    {:status 400 :headers {"Content-Type" "text/plain"} :body "bad request\n"}))

(defn- multi? [] (not (config/single-user? (static))))

(defn- oauth [] (auth/oauth-config (static)))

(defn- sign-in-failed [why]
  {:status 403
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (str "<!DOCTYPE html>"
              (h/html [:html {:lang "en"}
                       [:head [:meta {:charset "utf-8"}] [:title "Sign-in failed · The Newsroom"]
                        [:link {:rel "stylesheet" :href (ui/asset "/css/style.css")}]]
                       [:body [:main.signin [:h1 "You couldn't be signed in"] [:p why]
                               [:p [:a {:href "/login"} "Try again"]]]]]))})

(defn- login [{:keys [params]}]
  (if-not (multi?)
    (redirect "/")
    (try (redirect (auth/begin-login (oauth) (get params "next")))
         (catch Throwable e (sign-in-failed (ex-message e))))))

(defn- oauth-callback
  "Where the sign-in server sends the browser back: the code traded for
  the reader's tokens, a session started for them, and the browser sent
  on to where it was going."
  [{:keys [params]}]
  (if-not (multi?)
    (redirect "/")
    (let [oauth (oauth)
          {:keys [claims id-token next error]} (try (auth/finish-login oauth params)
                                                    (catch Throwable e {:error (ex-message e)}))]
      (if error
        (sign-in-failed error)
        (let [st (:store @system)
              uid (auth/user-for st oauth claims)
              cookie (auth/new-session! st oauth uid id-token)]
          (ensure-reader! uid)
          {:status 302
           :headers {"Location" (auth/safe-next next)
                     "Set-Cookie" (auth/session-cookie oauth cookie (* (:session-days oauth) 24 60 60))
                     "Cache-Control" "no-store"}
           :body ""})))))

(defn- logout
  "Sign the browser out here, and send it to the sign-in server to sign
  out there too."
  [req]
  (if-not (multi?)
    (redirect "/")
    (let [st (:store @system)
          session (auth/session-of st req)
          oauth (oauth)]
      (when session (store/delete-session! st (:id session)))
      {:status 303
       :headers {"Location" (or (try (auth/logout-url oauth (:id-token session)) (catch Throwable _ nil)) "/")
                 "Set-Cookie" (auth/session-cookie oauth "" 0)}
       :body ""})))

(def routes
  "Every path the server answers, as ruuter routes; see the namespace doc."
  [{:path "/" :method :get
    :response (fn [req] (redirect (str "/day/" (or (first (store/days (:store (ctx req)))) (pipeline/today)))))}
   {:path "/login" :method :get :response login}
   {:path "/oauth/callback" :method :get :response oauth-callback}
   {:path "/logout" :method :post :response logout}
   {:path "/day/:day" :method :get :response day-page}
   {:path "/week/:period" :method :get :response (digest-page :week)}
   {:path "/month/:period" :method :get :response (digest-page :month)}
   {:path "/stories" :method :get :response #(live % {:stories true})}
   {:path "/record" :method :get :response #(live % {:record true})}
   {:path "/story/:day/:n" :method :get :response story-page}
   {:path "/search" :method :get :response #(live % {:search (search-query %)})}
   {:path "/run" :method :post :response start-run}
   {:path "/digest" :method :post :response start-digest}
   {:path "/podcast" :method :post :response start-podcast}
   {:path "/ask" :method :post :response ask-page}
   {:path "/cancel" :method :post
    :response (fn [req] (pipeline/cancel-run! (:newsroom/user req)) (ds/patch-signals {}))}
   {:path "/config" :method :get
    :response #(if (:jolt.datastar/sse-request %) (live % {:config true}) (config-page %))}
   {:path "/config" :method :post :response config-page}
   {:path "/config/export" :method :get
    :response (fn [req]
                {:status 200
                 :headers {"Content-Type" "application/edn; charset=utf-8"
                           "Content-Disposition" "attachment; filename=\"newsroom-settings.edn\""}
                 :body (settings/export-edn (:config (ctx req)))})}
   {:path "/config/import" :method :post :response import-settings}
   {:path "/config/test-source" :method :post
    :response (fn [{:keys [params] :as req}]
                (html (str (h/html (config-page/test-result
                                    (test-source (:config (ctx req)) params (str (get params "_test")))
                                    (pipeline/today))))))}
   {:path "/config/test-provider" :method :post
    :response (fn [{:keys [params] :as req}]
                (html (str (h/html (config-page/provider-test-result
                                    (test-provider (:config (ctx req)) params (str (get params "_test"))))))))}
   {:path "/js/:file" :method :get :response asset}
   {:path "/css/:file" :method :get :response asset}
   {:path :not-found :response (fn [_] (not-found))}])

(defn- drawn-for
  "`handler` with the pages drawn for the request's reader: with their
  settings, and, with several readers, saying who is signed in."
  [handler]
  (fn [req]
    (let [uid (:newsroom/user req)]
      (binding [ui/*config* (when-not (local? uid) (seen-config uid))
                ui/*reader* (when (and uid (multi?)) (store/user (:store @system) uid))]
        (handler req)))))

(def app
  "The Ring handler: ruuter routes over the query string's and the form's
  params, under :params by name, the path's by keyword. A multipart form,
  an import's upload, is read by the adapter's own middleware, since
  Ring's is written against a JVM library. The reader is named first, so
  a request from no one is turned away before anything is read."
  (-> #(ruuter/route routes %)
      drawn-for
      params/wrap-params
      multipart/wrap-multipart-params
      (auth/wrap-reader {:store #(:store @system) :config static})))

(defn server-opts
  "How the server listens: :host (an IPv4 address, loopback unless the config
  says otherwise; \"0.0.0.0\" for every interface) and :port from config.edn.
  Fibers rather than a thread per connection, since every open tab holds an
  SSE stream."
  [cfg]
  {:host (:host cfg "127.0.0.1")
   :port (:port cfg 3000)
   :strategy :fibers})

(defn- readers
  "The readers whose schedules run: the local reader alone with one; with
  several, everyone who has signed in, and the local reader too when
  :local-subject says who signs in as them."
  [st file]
  (if (config/single-user? file)
    [store/local-user-id]
    (cond->> (mapv :id (store/users st))
      (not (get-in file [:oauth :local-subject])) (remove local?))))

(defn- check-oauth!
  "Refuse to start with several readers and no way for them to sign in."
  [file]
  (when-not (config/single-user? file)
    (let [missing (remove #(get-in file [:oauth %]) [:client-id :redirect-uri])]
      (when (seq missing)
        (throw (ex-info (str "config.edn says :single-user false, so readers sign in, and :oauth needs "
                             (str/join " and " (map pr-str missing)))
                        {:missing missing}))))))

(defn start!
  "Read secrets.edn, open the store, bring in any settings and briefings
  still in files and the default prompts it doesn't have, load plugins,
  start each reader's schedule and the server."
  []
  (config/ensure-home!)
  (config/load-secrets!)
  (let [file (config/read-file)
        _ (check-oauth! file)
        st (store/open (config/db-file file))
        imported (settings/import! st file)
        moved (pipeline/import-markdown! st)
        _ (doseq [{:keys [id]} (store/users st)] (settings/seed-prompts! (store/for-user st id)))
        cfg (config/effective file (settings/stored st))
        plugins (plugin/load-all! (config/path "plugins") cfg)
        _ (reset! system {:config cfg :store st :file (select-keys file config/static-keys)})
        _ (reset! ui/config cfg)
        ids (readers st file)
        pruned (reduce + (map #(count (pipeline/prune-days! (user-ctx %))) ids))
        ;; what the last stop cut short is picked up before the schedules
        ;; start, which wait for it
        _ (narration/start!)
        _ (doseq [uid ids]
            ;; a reader's records that can't be picked up don't keep the
            ;; server, or the other readers, from starting
            (try (pipeline/resume! (user-ctx uid))
                 (catch Throwable e
                   (binding [*out* *err*]
                     (println "couldn't pick up what the last stop cut short for reader" uid ":" (ex-message e)))))
            (ensure-reader! uid))
        ;; every open page re-renders at most once a tick, however many
        ;; writes land in it: sources reporting from several threads and the
        ;; model's progress come in bursts
        handler (ds/wrap-datastar app {:tick-ms 200})
        {:keys [host port] :as opts} (server-opts cfg)
        server (adapter/run-server handler opts)]
    (swap! system assoc :server server)
    (println (str "newsroom on http://" host ":" port "  (config: " (config/home) ")"))
    (when-not (config/single-user? file)
      (println (str "readers sign in with " (:issuer (auth/oauth-config file)) "; " (count ids)
                    (if (= 1 (count ids)) " reader has" " readers have") " signed in so far")))
    (when (seq imported)
      (println "moved into the database:" (str/join " " (map name imported))
               "(the files they came from are kept as .bak; edit them at /config now)"))
    (when (seq moved)
      (println "moved" (count moved) "briefings and digests from briefings/ into the database"
               "(the folder is kept as briefings.bak)"))
    (doseq [{:keys [plugin ok]} plugins :when ok]
      (println "loaded plugin" plugin))
    (println (tts/engines-line cfg))
    (println (screen/status-line cfg))
    (when (pos? pruned)
      (println "dropped" pruned "days past :keep-days"))
    @system))

(defn stop! []
  (when-let [{:keys [schedule server store users]} @system]
    ;; what this cuts short stays recorded as cut short, for the next start
    ;; to pick up (pipeline/resume!)
    (doseq [uid (cons nil (keys users))]
      (pipeline/cancel-run! uid :stopping? true))
    (narration/stop!)
    (doseq [s (cons schedule (map :schedule (vals users))) :when s] (s))
    (when server (adapter/stop-server server))
    (when store (store/close store))
    (reset! system nil)))

(defn -main [& _]
  (jolt.host/block-sigint)
  (start!)
  (jolt.host/add-shutdown-hook stop!)
  (jolt.host/park-until-interrupt)
  (stop!))
