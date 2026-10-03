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
    POST /run?day=...       gather and analyse a day (today by default)
    POST /digest?kind=week&period=YYYY-Www
                            write a digest (kind week or month)
    POST /cancel            cancel the run in flight"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [jolt.datastar.core :as ds]
            [ring-chez.adapter :as adapter]
            [ring-chez.multipart.core :as form]
            [newsroom.config :as config]
            [newsroom.news :as news]
            [newsroom.pipeline :as pipeline]
            [newsroom.plugin :as plugin]
            [newsroom.settings :as settings]
            [newsroom.store :as store]
            [newsroom.trends :as trends]
            [newsroom.ui :as ui]))

(defonce system (atom nil))

(defn- ctx [] (select-keys @system [:config :store]))

(defn- html [body] {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"} :body body})

(defn- not-found [] {:status 404 :headers {"Content-Type" "text/plain"} :body "not found\n"})

(defn- redirect [to] {:status 302 :headers {"Location" to} :body ""})

(defn- query-param [req k]
  (some->> (:query-string req)
           (re-find (re-pattern (str "(?:^|&)" k "=([^&]*)")))
           second))

(defn- day-route [uri]
  (second (re-matches #"/day/(\d{4}-\d{2}-\d{2})(\.md)?" uri)))

(defn- digest-route
  "The [kind period] a digest's path names, when it names a real one."
  [uri]
  (when-let [[_ kind period] (re-matches #"/(week|month)/([0-9W-]+?)(?:\.md)?" uri)]
    (let [kind (keyword kind)]
      (when (trends/period-range kind period) [kind period]))))

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

(defn- form-data
  "A form's {:params {name value} :files {name part}}. The config page's
  form has a field for every key of every source, so far more than the
  parser allows by default."
  [req]
  (form/parse-form-data req {:part-limit 100000 :memory-limit (* 16 1024 1024)}))

(defn- form-params [req] (:params (form-data req)))

(defn- current-form [] (settings/to-form (:config @system)))

(defn- import-settings
  "Save the settings in the EDN of an uploaded file or the pasted text,
  over the ones there are."
  [req]
  (let [{:keys [params files]} (form-data req)
        file (get files "file")
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
      (let [params (form-params req)
            {:keys [settings errors]} (settings/from-form params)]
        (if (seq errors)
          (assoc (html (ui/page st {:config {:tree (settings/form-tree params) :errors errors}})) :status 422)
          (do (settings/save! st settings)
              (reload! settings)
              {:status 303 :headers {"Location" "/config?saved=1"} :body ""})))
      (html (ui/page st {:config {:tree (current-form)
                                  :saved? (= "1" (query-param req "saved"))
                                  :imported? (= "1" (query-param req "imported"))}})))))

(def ^:private assets
  "The files served from resources/public, by path, with their content type."
  {"/js/datastar.js" "application/javascript"
   "/js/diagrams.js" "application/javascript"
   "/js/config.js"   "application/javascript"
   "/css/style.css"  "text/css; charset=utf-8"})

(defn app [{:keys [uri request-method jolt.datastar/sse-request] :as req}]
  (let [st (:store @system)]
    (cond
      (= uri "/")
      (redirect (str "/day/" (or (first (store/days st)) (pipeline/today))))

      (and (= :post request-method) (= uri "/run"))
      (let [day (or (query-param req "day") (pipeline/today))]
        (if (news/valid-day? day)
          (do (pipeline/start-run! (ctx) day)
              (ds/patch-signals {}))
          {:status 400 :body "bad day\n"}))

      (and (= :post request-method) (= uri "/digest"))
      (let [kind (some-> (query-param req "kind") keyword)
            period (query-param req "period")]
        (if (and (contains? trends/kinds kind) (trends/period-range kind period))
          (do (pipeline/start-digest! (ctx) kind period)
              (ds/patch-signals {}))
          {:status 400 :body "bad digest\n"}))

      (and (= :post request-method) (= uri "/cancel"))
      (do (pipeline/cancel-run!) (ds/patch-signals {}))

      (= uri "/config/export")
      {:status 200
       :headers {"Content-Type" "application/edn; charset=utf-8"
                 "Content-Disposition" "attachment; filename=\"newsroom-settings.edn\""}
       :body (settings/export-edn (:config @system))}

      (and (= :post request-method) (= uri "/config/import"))
      (import-settings req)

      (= uri "/config")
      (if sse-request
        (html (str (h/html (ui/fragment st {:config true} (:jolt.datastar/selector req)))))
        (config-page req))

      (assets uri)
      {:status 200
       :headers {"Content-Type" (assets uri) "Cache-Control" "max-age=86400"}
       :body (slurp (io/resource (str "public" uri)))}

      (or (= uri "/stories") (re-matches #"/story/\d{4}-\d{2}-\d{2}/\d+" uri))
      (let [current (if (= uri "/stories") {:stories true} {:story (subs uri (count "/story/"))})]
        (if sse-request
          (html (str (h/html (ui/fragment st current (:jolt.datastar/selector req)))))
          (html (ui/page st current))))

      (re-matches #"/(week|month)/.*" uri)
      (if-let [[kind period] (digest-route uri)]
        (cond
          (str/ends-with? uri ".md")
          (if-let [d (store/digest st kind period)]
            {:status 200 :headers {"Content-Type" "text/markdown; charset=utf-8"} :body (:markdown d)}
            (not-found))

          sse-request (html (str (h/html (ui/fragment st {:kind kind :period period}
                                                      (:jolt.datastar/selector req)))))
          :else (html (ui/page st {:kind kind :period period})))
        (not-found))

      :else
      (let [day (day-route uri)]
        (cond
          (not (and day (news/valid-day? day))) (not-found)

          (str/ends-with? uri ".md")
          (if-let [d (store/day st day)]
            {:status 200 :headers {"Content-Type" "text/markdown; charset=utf-8"} :body (:markdown d)}
            (not-found))

          sse-request (html (str (h/html (ui/fragment st day (:jolt.datastar/selector req)))))
          :else (html (ui/page st day)))))))

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
