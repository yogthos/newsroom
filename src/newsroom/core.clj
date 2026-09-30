(ns newsroom.core
  "The HTTP edge and the boot sequence.

    GET  /                  the latest briefing (or today's page)
    GET  /day/YYYY-MM-DD    a day's page; the same path, as datastar's SSE
                            stream, re-renders it live
    GET  /day/YYYY-MM-DD.md the day's briefing as markdown
    POST /run?day=...       gather and analyse a day (today by default)
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

      (and (= :post request-method) (= uri "/cancel"))
      (do (pipeline/cancel-run!) (ds/patch-signals {}))

      (= uri "/js/datastar.js")
      {:status 200
       :headers {"Content-Type" "application/javascript" "Cache-Control" "max-age=86400"}
       :body (slurp (io/resource "public/js/datastar.js"))}

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
