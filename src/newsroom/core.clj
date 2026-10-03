(ns newsroom.core
  "The HTTP edge and the boot sequence.

    GET  /                  the latest briefing (or today's page)
    GET  /day/YYYY-MM-DD    a day's page; the same path, as datastar's SSE
                            stream, re-renders it live
    GET  /day/YYYY-MM-DD.md the day's briefing as markdown
    GET  /week/YYYY-Www     a week's digest, live like a day's page, and
    GET  /month/YYYY-MM     a month's; either with .md as markdown
    GET  /stories           every storyline the briefings keep notes on
    GET  /story/DAY/N       a storyline's notes and coverage
    GET  /config            the settings, and a form to change them
    POST /config            save the settings and run with them from now on
    GET  /config/export     the settings as EDN, in config.edn's form
    POST /config/import     save the settings in an EDN file or text
    POST /config/test-source
                            read one source of the config page's form, as
                            a run would, without saving it
    POST /run?day=...       gather and analyse a day (today by default)
    POST /digest?kind=week&period=YYYY-Www
                            write a digest (kind week or month)
    POST /cancel            cancel the run in flight"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [jolt.datastar.core :as ds]
            [ring-chez.adapter :as adapter]
            [ring-chez.middleware.multipart :as multipart]
            [ring.middleware.params :as params]
            [ruuter.core :as ruuter]
            [newsroom.config :as config]
            [newsroom.config-page :as config-page]
            [newsroom.news :as news]
            [newsroom.pipeline :as pipeline]
            [newsroom.plugin :as plugin]
            [newsroom.settings :as settings]
            [newsroom.sources :as sources]
            [newsroom.store :as store]
            [newsroom.trends :as trends]
            [newsroom.ui :as ui]))

(defonce system (atom nil))

(defn- ctx [] (select-keys @system [:config :store]))

(defn- html [body] {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"} :body body})

(defn- not-found [] {:status 404 :headers {"Content-Type" "text/plain"} :body "not found\n"})

(defn- redirect [to] {:status 302 :headers {"Location" to} :body ""})

(defn- start-schedule!
  "The schedule, reading the config as it is at each wake."
  []
  (pipeline/start-schedule! (assoc (ctx) :current-config #(:config @system))))

(defn reload!
  "Run with `stored`, the settings just saved, from now on: the config the
  next run and the plugins read, and the schedule, restarted when its times
  changed. A run already going keeps the config it started with."
  [stored]
  (let [{old :config :keys [file schedule]} @system
        cfg (config/effective file stored)]
    (swap! system assoc :config cfg)
    (plugin/set-config! cfg)
    (when (not= (select-keys old [:run-at :run-every-hours]) (select-keys cfg [:run-at :run-every-hours]))
      (when schedule (schedule))
      (swap! system assoc :schedule (start-schedule!)))
    cfg))

(defn test-source
  "Read the source the form `params` holds at `prefix` as a run would, for
  today, within the run's :source-timeout-ms: {:errors} when its fields
  are wrong, else {:items :events}, or {:error :events} when reading it
  failed or took too long. Nothing is saved."
  [cfg params prefix]
  (let [{:keys [source errors]} (settings/source-at params prefix)]
    (if (seq errors)
      {:errors errors}
      (let [events (atom [])
            timeout (:source-timeout-ms cfg 30000)
            task (future
                   (try {:items (vec (sources/fetch-items source {:day (pipeline/today) :config cfg
                                                                  :emit #(swap! events conj %)}))}
                        (catch Throwable e {:error (or (ex-message e) (str e))})))
            result (deref task timeout ::timeout)]
        (if (= ::timeout result)
          (do (future-cancel task)
              {:error (str "took longer than " (quot timeout 1000) "s, the source timeout") :events @events})
          (assoc result :events @events :name (sources/source-name source)))))))

(defn- current-form [] (settings/to-form (:config @system)))

(defn- import-settings
  "Save the settings in the EDN of an uploaded file or the pasted text,
  over the ones there are."
  [req]
  (let [{:keys [params]} req
        file (get params "file")
        file (if (vector? file) (peek file) file)
        text (if (pos? (count (:bytes file)))
               (String. ^bytes (:bytes file) "UTF-8")
               (str (get params "edn")))
        {:keys [settings errors]} (settings/from-edn text)]
    (cond
      (str/blank? text)
      (assoc (html (ui/page (:store @system) {:config {:tree (current-form)
                                                       :import {:errors {"EDN" "is empty: choose a file or paste some"}}}}))
             :status 422)

      (seq errors)
      (assoc (html (ui/page (:store @system) {:config {:tree (current-form)
                                                       :import {:errors errors :text text}}}))
             :status 422)

      :else
      (let [all (merge (select-keys (:config @system) settings/setting-keys) settings)]
        (settings/save! (:store @system) all)
        (reload! all)
        {:status 303 :headers {"Location" "/config?imported=1"} :body ""}))))

(defn- config-page
  [req]
  (let [st (:store @system)]
    (if (= :post (:request-method req))
      (let [params (:params req)
            {:keys [settings errors]} (settings/from-form params)]
        (if (seq errors)
          (assoc (html (ui/page st {:config {:tree (settings/form-tree params) :errors errors}})) :status 422)
          (do (settings/save! st settings)
              (reload! settings)
              {:status 303 :headers {"Location" "/config?saved=1"} :body ""})))
      (html (ui/page st {:config {:tree (current-form)
                                  :saved? (= "1" (get-in req [:params "saved"]))
                                  :imported? (= "1" (get-in req [:params "imported"]))}})))))

(def ^:private assets
  "The files served from resources/public, by path, with their content type.
  They are read through io/resource, which reaches the copies baked into a
  built binary, where Ring's resource middleware can't."
  {"/js/datastar.js" "application/javascript"
   "/js/diagrams.js" "application/javascript"
   "/js/config.js"   "application/javascript"
   "/css/style.css"  "text/css; charset=utf-8"})

(defn- asset [{:keys [uri]}]
  (if-let [type (assets uri)]
    {:status 200
     :headers {"Content-Type" type "Cache-Control" "max-age=86400"}
     :body (slurp (io/resource (str "public" uri)))}
    (not-found)))

(defn- live
  "The page for `current`, or, when the request is datastar's stream, the
  part of it the stream patches."
  [{:keys [jolt.datastar/sse-request jolt.datastar/selector]} current]
  (let [st (:store @system)]
    (if sse-request
      (html (str (h/html (ui/fragment st current selector))))
      (html (ui/page st current)))))

(defn- markdown [doc]
  (if doc
    {:status 200 :headers {"Content-Type" "text/markdown; charset=utf-8"} :body (:markdown doc)}
    (not-found)))

(defn- with-md
  "A path part and whether it ended in .md: [\"2026-09-30\" true]."
  [part]
  (if (str/ends-with? part ".md") [(subs part 0 (- (count part) 3)) true] [part false]))

(defn- day-page [{:keys [params] :as req}]
  (let [[day md?] (with-md (:day params))]
    (cond
      (not (news/valid-day? day)) (not-found)
      md? (markdown (store/day (:store @system) day))
      :else (live req day))))

(defn- digest-page [kind]
  (fn [{:keys [params] :as req}]
    (let [[period md?] (with-md (:period params))]
      (cond
        (not (trends/period-range kind period)) (not-found)
        md? (markdown (store/digest (:store @system) kind period))
        :else (live req {:kind kind :period period})))))

(defn- start-run [{:keys [params]}]
  (let [day (get params "day" (pipeline/today))]
    (if (news/valid-day? day)
      (do (pipeline/start-run! (ctx) day)
          (ds/patch-signals {}))
      {:status 400 :body "bad day\n"})))

(defn- start-digest [{:keys [params]}]
  (let [kind (some-> (get params "kind") keyword)
        period (get params "period")]
    (if (and (contains? trends/kinds kind) (trends/period-range kind period))
      (do (pipeline/start-digest! (ctx) kind period)
          (ds/patch-signals {}))
      {:status 400 :body "bad digest\n"})))

(defn- story-page [{:keys [params] :as req}]
  (if (and (news/valid-day? (:day params)) (re-matches #"\d+" (:n params)))
    (live req {:story (str (:day params) "/" (:n params))})
    (not-found)))

(def routes
  "Every path the server answers, as ruuter routes; see the namespace doc."
  [{:path "/" :method :get
    :response (fn [_] (redirect (str "/day/" (or (first (store/days (:store @system))) (pipeline/today)))))}
   {:path "/day/:day" :method :get :response day-page}
   {:path "/week/:period" :method :get :response (digest-page :week)}
   {:path "/month/:period" :method :get :response (digest-page :month)}
   {:path "/stories" :method :get :response #(live % {:stories true})}
   {:path "/story/:day/:n" :method :get :response story-page}
   {:path "/run" :method :post :response start-run}
   {:path "/digest" :method :post :response start-digest}
   {:path "/cancel" :method :post :response (fn [_] (pipeline/cancel-run!) (ds/patch-signals {}))}
   {:path "/config" :method :get
    :response #(if (:jolt.datastar/sse-request %) (live % {:config true}) (config-page %))}
   {:path "/config" :method :post :response config-page}
   {:path "/config/export" :method :get
    :response (fn [_]
                {:status 200
                 :headers {"Content-Type" "application/edn; charset=utf-8"
                           "Content-Disposition" "attachment; filename=\"newsroom-settings.edn\""}
                 :body (settings/export-edn (:config @system))})}
   {:path "/config/import" :method :post :response import-settings}
   {:path "/config/test-source" :method :post
    :response (fn [{:keys [params]}]
                (html (str (h/html (config-page/test-result
                                    (test-source (:config @system) params (str (get params "_test")))
                                    (pipeline/today))))))}
   {:path "/js/:file" :method :get :response asset}
   {:path "/css/:file" :method :get :response asset}
   {:path :not-found :response (fn [_] (not-found))}])

(def app
  "The Ring handler: ruuter routes over the query string's and the form's
  params, under :params by name, the path's by keyword. A multipart form,
  an import's upload, is read by the adapter's own middleware, since
  Ring's is written against a JVM library."
  (-> #(ruuter/route routes %)
      params/wrap-params
      multipart/wrap-multipart-params))

(defn server-opts
  "How the server listens: :host (an IPv4 address, loopback unless the config
  says otherwise; \"0.0.0.0\" for every interface) and :port from config.edn.
  Fibers rather than a thread per connection, since every open tab holds an
  SSE stream."
  [cfg]
  {:host (:host cfg "127.0.0.1")
   :port (:port cfg 3000)
   :strategy :fibers})

(defn start!
  "Open the store, bring in any settings still in files, load plugins,
  start the schedule and the server."
  []
  (config/ensure-home!)
  (let [file (config/read-file)
        st (store/open (config/db-file file))
        imported (settings/import! st file)
        cfg (config/effective file (settings/stored st))
        plugins (plugin/load-all! (config/path "plugins") cfg)
        _ (reset! system {:config cfg :store st :file (select-keys file config/static-keys)})
        pruned (pipeline/prune-days! (ctx))
        schedule (start-schedule!)
        handler (ds/wrap-datastar app {:rate-limit-ms 200})
        {:keys [host port] :as opts} (server-opts cfg)
        server (adapter/run-server handler opts)]
    (swap! system assoc :schedule schedule :server server)
    (println (str "newsroom on http://" host ":" port "  (config: " (config/home) ")"))
    (when (seq imported)
      (println "moved into the database:" (str/join " " (map name imported))
               "(the files they came from are kept as .bak; edit them at /config now)"))
    (doseq [{:keys [plugin ok]} plugins :when ok]
      (println "loaded plugin" plugin))
    (when (seq pruned)
      (println "dropped" (count pruned) "days past :keep-days" (:keep-days cfg)))
    @system))

(defn stop! []
  (when-let [{:keys [schedule server store]} @system]
    (pipeline/cancel-run!)
    (when schedule (schedule))
    (when server (adapter/stop-server server))
    (when store (store/close store))
    (reset! system nil)))

(defn -main [& _]
  (jolt.host/block-sigint)
  (start!)
  (jolt.host/add-shutdown-hook stop!)
  (jolt.host/park-until-interrupt)
  (stop!))
