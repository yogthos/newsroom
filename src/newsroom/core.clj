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
    POST /run?day=...       gather and analyse a day (today by default)
    POST /digest?kind=week&period=YYYY-Www
                            write a digest (kind week or month)
    POST /cancel            cancel the run in flight"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [jolt.datastar.core :as ds]
            [ring-chez.adapter :as adapter]
            [newsroom.config :as config]
            [newsroom.news :as news]
            [newsroom.pipeline :as pipeline]
            [newsroom.plugin :as plugin]
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

(def ^:private assets
  "The files served from resources/public, by path, with their content type."
  {"/js/datastar.js" "application/javascript"
   "/js/diagrams.js" "application/javascript"
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
  "Open the store, load plugins, start the schedule and the server."
  []
  (config/ensure-home!)
  (let [cfg (config/load-config)
        plugins (plugin/load-all! (config/path "plugins") cfg)
        st (store/open (config/db-file cfg))
        _ (reset! system {:config cfg :store st})
        pruned (pipeline/prune-days! (ctx))
        schedule (pipeline/start-schedule! (ctx))
        handler (ds/wrap-datastar app {:rate-limit-ms 200})
        {:keys [host port] :as opts} (server-opts cfg)
        server (adapter/run-server handler opts)]
    (swap! system assoc :schedule schedule :server server)
    (println (str "newsroom on http://" host ":" port "  (config: " (config/home) ")"))
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
