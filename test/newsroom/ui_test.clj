(ns newsroom.ui-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [glimmer.ratom :as ratom]
            [newsroom.core :as core]
            [newsroom.pipeline :as pipeline]
            [newsroom.store :as store]
            [newsroom.ui :as ui]))

(defn- with-store [f]
  (let [st (store/open "sqlite::memory:")]
    (try
      (store/save-day! st {:day "2026-09-29" :sources [] :cited [] :markdown "old" :model "m" :provider "p"})
      (store/save-day! st {:day "2026-09-30"
                           :sources [{:n 1 :title "<script>alert(1)</script>" :url "https://e.com/1"
                                      :source "Wire" :summary "s" :published nil}]
                           :cited [1]
                           :markdown "# Big day\n\nClaim [[1]](https://e.com/1).\n\n## Sources\n\n- [1] [x](https://e.com/1)"
                           :model "deepseek-v4-flash" :provider "deepseek"})
      (f st)
      (finally (store/close st)))))

(deftest a-day-page
  (with-store
    (fn [st]
      (let [page (ui/page st "2026-09-30")]
        (is (str/includes? page "<h1>Big day</h1>"))
        (is (str/includes? page "<a href=\"https://e.com/1\">[1]</a>"))
        (testing "a gathered title is escaped"
          (is (str/includes? page "&lt;script&gt;alert(1)&lt;/script&gt;"))
          (is (not (str/includes? page "<script>alert(1)"))))
        (testing "the sidebar steps to the day before and lists the archive"
          (is (str/includes? page "href=\"/day/2026-09-29\">← Earlier"))
          (is (str/includes? page "29 September 2026")))
        (is (str/includes? page "deepseek / deepseek-v4-flash"))
        (is (str/includes? page "src=\"/js/diagrams.js\"") "the page can draw diagrams")))))

(deftest the-archive-shows-each-days-standfirst-and-the-desk-shows-feed-health
  (let [st (store/open "sqlite::memory:")]
    (try
      (store/save-day! st {:day "2026-09-29" :sources [] :cited [] :markdown "old"
                           :tldr "A quiet day before the storm." :model "m" :provider "p"})
      (store/save-day! st {:day "2026-09-30"
                           :sources [{:n 1 :title "Wire story" :url "https://e.com/1"
                                      :source "Reuters" :summary "s" :published nil
                                      :also ["BBC" "Al Jazeera"]}]
                           :cited [1] :markdown "# Big day" :model "m" :provider "p"})
      (store/record-source-health! st [{:source "Reuters" :error nil}
                                       {:source "Broken feed" :error "feed is down"}])
      (let [page (ui/page st "2026-09-30")]
        (is (str/includes? page "A quiet day before the storm.") "archive tldr")
        (is (str/includes? page "also BBC, Al Jazeera") "corroboration on the item")
        (is (str/includes? page "Feed health (2)") "desk lists both sources")
        (is (str/includes? page "1 failed in a row") "and the failing one says so"))
      (finally (store/close st)))))

(deftest a-day-with-no-briefing
  (with-store
    (fn [st]
      (is (str/includes? (ui/page st "2026-01-01") "No briefing for this day")))))

(deftest routes
  (with-store
    (fn [st]
      (reset! core/system {:store st :config {}})
      (is (= "/day/2026-09-30" (get-in (core/app {:uri "/" :request-method :get}) [:headers "Location"])))
      (is (= 200 (:status (core/app {:uri "/day/2026-09-30" :request-method :get}))))
      (is (str/starts-with? (:body (core/app {:uri "/day/2026-09-30.md" :request-method :get})) "# Big day"))
      (is (= 404 (:status (core/app {:uri "/day/2026-02-31" :request-method :get}))))
      (is (= 404 (:status (core/app {:uri "/day/2026-01-01.md" :request-method :get}))))
      (is (= 404 (:status (core/app {:uri "/nope" :request-method :get}))))
      (testing "the page's scripts and stylesheet come from resources"
        (doseq [[uri type text] [["/js/datastar.js" "application/javascript" "Datastar"]
                                 ["/js/diagrams.js" "application/javascript" "mermaid"]
                                 ["/css/style.css" "text/css; charset=utf-8" "--paper"]]]
          (let [resp (core/app {:uri uri :request-method :get})]
            (is (= 200 (:status resp)) uri)
            (is (= type (get-in resp [:headers "Content-Type"])) uri)
            (is (str/includes? (:body resp) text) uri)))
        (is (= 404 (:status (core/app {:uri "/js/nope.js" :request-method :get})))))
      (reset! core/system nil))))

(deftest a-run-in-progress-shows-what-it-is-doing
  (with-store
    (fn [st]
      (let [before @pipeline/status]
        (try
          (reset! pipeline/status
                  {:state :analysing :day "2026-09-30" :items 42 :provider "deepseek"
                   :model "deepseek-v4-flash" :sources {}
                   :events [{:at 1790766000000 :text "Reading BBC World"
                             :url "https://feeds.bbci.co.uk/news/world/rss.xml"}
                            {:at 1790766001000 :text "Broken failed: <bad>" :level :error}
                            {:at 1790766002000 :text "The model is writing the briefing"}]
                   :writing {:words 1234 :reasoning-words 300 :section "How it works"
                             :tail "…and so the <price> of grain rises"}})
          (let [page (ui/page st "2026-09-30")]
            (is (str/includes? page "1234 words"))
            (is (str/includes? page "How it works"))
            (is (str/includes? page "…and so the &lt;price&gt; of grain rises"))
            (is (str/includes? page "href=\"https://feeds.bbci.co.uk/news/world/rss.xml\""))
            (is (str/includes? page "Broken failed: &lt;bad&gt;"))
            (is (< (str/index-of page "The model is writing") (str/index-of page "Reading BBC World"))
                "newest first"))
          (finally (reset! pipeline/status before)))))))

(defn- fires-on-status-change?
  "Whether rendering `selector` subscribes it to the run status: a stream
  that does re-renders on every event of a run."
  [st selector]
  (let [fired (atom false)
        before @pipeline/status]
    (try
      (binding [ratom/*current-watcher* (fn [_] (reset! fired true))]
        (ui/fragment st "2026-09-30" selector))
      (swap! pipeline/status assoc :probe (Object.))
      @fired
      (finally (reset! pipeline/status before)))))

(deftest a-run-re-renders-the-sidebar-but-not-the-article
  (with-store
    (fn [st]
      (is (fires-on-status-change? st "#sidebar"))
      (is (not (fires-on-status-change? st "#article"))
          "the article, and its diagram, stay still while a run reports progress"))))

(deftest the-page-streams-the-sidebar-and-the-article-apart
  (with-store
    (fn [st]
      (let [page (ui/page st "2026-09-30")]
        (is (str/includes? page "datastar-selector=%23sidebar"))
        (is (str/includes? page "datastar-selector=%23article"))))))

(deftest the-page-starts-over-plain-http
  ;; crypto.randomUUID is missing outside a secure context, so a page read at
  ;; http://<lan address> threw from the sidebar's signals and the article's
  ;; stream never opened: a finished run's briefing didn't show until reload
  (with-store
    (fn [st]
      (let [page (ui/page st "2026-09-30")]
        (is (not (re-find #"(?<!\? )self\.crypto\.randomUUID\(\)" page))
            "randomUUID is only called where it exists")))))

(deftest the-server-binds-where-the-config-says
  (is (= {:host "127.0.0.1" :port 3000 :strategy :fibers} (core/server-opts {}))
      "loopback on 3000 unless told otherwise")
  (is (= {:host "0.0.0.0" :port 8080 :strategy :fibers}
         (core/server-opts {:host "0.0.0.0" :port 8080}))))
