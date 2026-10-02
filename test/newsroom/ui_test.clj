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

(deftest a-digest-page-and-the-digests-in-the-sidebar
  (with-store
    (fn [st]
      (store/save-digest! st {:kind :week :period "2026-W40"
                              :sources [{:n 1 :day "2026-09-30" :title "Wire story" :url "https://e.com/1" :source "Wire"}]
                              :cited [1] :markdown "# The week\n\nClaim [[1]](https://e.com/1)." :tldr "Rates held."
                              :model "m" :provider "p"})
      (store/save-digest! st {:kind :week :period "2026-W39" :sources [] :cited []
                              :markdown "# Earlier" :model "m" :provider "p"})
      (let [page (ui/page st {:kind :week :period "2026-W40"})]
        (is (str/includes? page "<title>The week of 28 September 2026 · The Newsroom</title>"))
        (is (str/includes? page "<h1>The week</h1>"))
        (is (str/includes? page "Rates held.") "the digest's standfirst in the sidebar")
        (is (str/includes? page "href=\"/week/2026-W39\">← Earlier") "stepping goes week to week")
        (is (str/includes? page "Wire, 30 September 2026") "its sources, by day"))
      (testing "the days' pages list the digests too"
        (is (str/includes? (ui/page st "2026-09-30") "href=\"/week/2026-W40\"")))
      (testing "a digest not yet written"
        (is (str/includes? (ui/page st {:kind :month :period "2026-09"}) "Write this digest"))
        (is (str/includes? (ui/page st {:kind :month :period "2026-08"}) "There are no briefings for this month"))
        (is (str/includes? (ui/page st {:kind :month :period "2999-01"}) "isn’t over yet")))
      (reset! core/system {:store st :config {}})
      (is (= 200 (:status (core/app {:uri "/week/2026-W40" :request-method :get}))))
      (is (= "# The week\n\nClaim [[1]](https://e.com/1)."
             (:body (core/app {:uri "/week/2026-W40.md" :request-method :get}))))
      (is (= 404 (:status (core/app {:uri "/month/2026-09.md" :request-method :get}))))
      (is (= 404 (:status (core/app {:uri "/week/2025-W53" :request-method :get}))))
      (is (= 400 (:status (core/app {:uri "/digest" :query-string "kind=year&period=2026"
                                     :request-method :post})))))))

(deftest storyline-pages
  (let [st (store/open "sqlite::memory:")
        source (fn [n outlets] {:n n :title (str "Fed story " n) :url (str "https://e.com/" n) :source "Wire"
                                :summary "" :published nil :story "2026-09-29/1"
                                :also (vec (for [i (range (dec outlets))] {:source "Other" :url (str "https://o" i ".org/x")}))})]
    (try
      (store/save-day! st {:day "2026-09-29" :sources [(source 1 1)] :cited [1] :markdown "x" :model "m" :provider "p"})
      (store/save-day! st {:day "2026-09-30" :sources [(source 1 3)] :cited [] :markdown "y" :model "m" :provider "p"})
      (store/save-notes! st {"2026-09-29/1" {:title "The Fed's <pause>" :summary "Held twice."
                                             :first-day "2026-09-29" :last-day "2026-09-30"
                                             :facts [{:day "2026-09-29" :text "The Fed held." :url "https://e.com/1"
                                                      :source "Wire" :headline "Fed holds"}]}})
      (testing "a storyline's page"
        (let [page (ui/page st {:story "2026-09-29/1"})]
          (is (str/includes? page "<title>The Fed&apos;s &lt;pause&gt; · The Newsroom</title>")
              "named by its note, escaped")
          (is (str/includes? page "Held twice."))
          (is (str/includes? page "The Fed held."))
          (is (str/includes? page "href=\"https://e.com/1\""))
          (is (str/includes? page "29 September 2026 to 30 September 2026"))
          (is (str/includes? page "width:100%") "the widest day fills the bar")
          (is (str/includes? page "1 outlet · cited"))
          (is (str/includes? page "href=\"/day/2026-09-30\">30 September 2026"))))
      (testing "the list of them, and the sidebar"
        (let [page (ui/page st {:stories true})]
          (is (str/includes? page "The stories the briefings follow"))
          (is (str/includes? page "1 fact"))
          (is (str/includes? page "href=\"/story/2026-09-29/1\""))))
      (testing "a day's sources link to their storyline"
        (is (str/includes? (ui/page st "2026-09-30") "storyline: <a href=\"/story/2026-09-29/1\">")))
      (is (str/includes? (ui/page st {:story "2026-01-01/9"}) "No notes on this storyline"))
      (reset! core/system {:store st :config {}})
      (is (= 200 (:status (core/app {:uri "/stories" :request-method :get}))))
      (is (str/includes? (:body (core/app {:uri "/story/2026-09-29/1" :request-method :get})) "Held twice."))
      (is (= 404 (:status (core/app {:uri "/story/nope" :request-method :get}))))
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
