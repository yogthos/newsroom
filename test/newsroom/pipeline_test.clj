(ns newsroom.pipeline-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ebb.core :as m]
            [newsroom.embed :as embed]
            [newsroom.pipeline :as pipeline]
            [newsroom.sources :as sources]
            [newsroom.store :as store]))

(defn- item [n day]
  {:title (str "Story " n) :url (str "https://e.com/" n) :source "Fixture"
   :summary (str "About " n) :published (str day "T08:00:00Z")})

(defmethod sources/fetch-items ::fixture [{:keys [ns name]} {:keys [day] :as ctx}]
  (sources/emit! ctx (str "Reading fixture " name) {:url (str "https://e.com/feed/" name)})
  (mapv #(item % day) ns))

(defmethod sources/fetch-items ::slow [_ _]
  (Thread/sleep 5000)
  [(item 99 "2026-09-30")])

(defmethod sources/fetch-items ::broken [_ _]
  (throw (ex-info "feed is down" {})))

(defmethod sources/fetch-items ::dupe [{:keys [name suffix]} {:keys [day]}]
  ;; the same wire story, word for word, at another outlet's address
  [{:title "Central banks warn of rising sovereign debt"
    :url (str "https://" suffix ".example.com/debt-warning")
    :source name
    :summary "Finance ministers from the G7 met to discuss rising sovereign debt levels."
    :published (str day "T08:00:00Z")}])

(def dir (str (System/getProperty "java.io.tmpdir") "/newsroom-test-" (System/currentTimeMillis)))

(defn- ctx [sources chat]
  {:config {:sources sources :source-timeout-ms 500 :lookback-days 1
            :max-items-per-source 2 :max-items 10
            ;; the fixture stories are all near-alike text, so embedding
            ;; must stay off unless a test turns it on
            :dupe-threshold nil
            :providers {} :roles {:analyst :local}}
   :store (store/open "sqlite::memory:")
   :template "Brief {{date}}.\n\n{{sources}}"
   :markdown-dir dir
   :chat chat})

(deftest a-run-gathers-analyses-and-stores-the-day
  (let [prompts (atom [])
        c (ctx [{:type ::fixture :name "A" :ns [1 2 3]}
                {:type ::fixture :name "B" :ns [2 4]}
                {:type ::slow :name "Slow"}
                {:type ::broken :name "Broken"}]
               (fn [llm req]
                 (swap! prompts conj (-> req :messages first :content))
                 (doseq [d [{:reasoning "Let me "} {:reasoning "think."}
                            {:text "# Today\n\n"} {:text "## The mechanics\n\nOne [1], "}
                            {:text "two [2, 3], unknown [9]."}]]
                   ((:on-delta req) d))
                 {:content "# Today\n\n## The mechanics\n\nOne [1], two [2, 3], unknown [9]."
                  :model "fake"}))
        summary (m/? (pipeline/run-task c "2026-09-30"))
        day (store/day (:store c) "2026-09-30")]
    (testing "each source is capped, and a story two sources carry is kept once"
      (is (= ["https://e.com/1" "https://e.com/2" "https://e.com/4"] (map :url (:sources day)))))
    (testing "a slow and a broken source cost only their own items"
      (is (= :failed (get-in @pipeline/status [:sources "Slow" :state])))
      (is (str/includes? (get-in @pipeline/status [:sources "Slow" :error]) "timed out"))
      (is (= "feed is down" (get-in @pipeline/status [:sources "Broken" :error]))))
    (testing "the prompt numbers the sources"
      (is (str/starts-with? (first @prompts) "Brief 30 September 2026 (2026-09-30)."))
      (is (str/includes? (first @prompts) "[3] Story 4 (Fixture)")))
    (testing "the briefing links its citations and lists what it cited"
      (is (str/includes? (:markdown day) "One [[1]](https://e.com/1)"))
      (is (str/includes? (:markdown day) "unknown [9]"))
      (is (= [1 2 3] (map :n (:cited day))))
      (is (= "fake" (:model day)))
      (is (= "local" (:provider day))))
    (is (= (:markdown day) (slurp (io/file dir "2026-09-30.md"))))
    (testing "the run tells the page what it is doing"
      (let [events (:events @pipeline/status)
            texts (map :text events)
            has? (fn [s] (some #(str/includes? % s) texts))]
        (is (has? "Reading fixture A"))
        (is (some #(= "https://e.com/feed/A" (:url %)) events) "a fetch links to what it reads")
        (is (has? "A: 3 items"))
        (is (has? "Broken failed: feed is down"))
        (is (has? "Slow timed out"))
        (is (has? "3 stories to analyse"))
        (is (has? "Asking local (local-model) to write the briefing"))
        (is (has? "The model is thinking"))
        (is (has? "The model is writing"))
        (is (has? "Filed the briefing: 3 of 3 sources cited"))
        (is (every? :at events))
        (is (= 1 (count (filter #(str/includes? % "is writing") texts))) "said once, not per delta"))
      (let [w (:writing @pipeline/status)]
        (is (= 6 (:words w)) "words of prose, not markup or citations")
        (is (= "The mechanics" (:section w)))
        (is (str/ends-with? (:tail w) "unknown [9]."))))
    (is (= {:day "2026-09-30" :items 3 :cited 3} (select-keys summary [:day :items :cited])))))

(deftest a-run-with-nothing-gathered-fails-and-stores-nothing
  (let [c (ctx [{:type ::broken :name "Broken"}] (fn [_ _] (throw (ex-info "not called" {}))))]
    (is (thrown-with-msg? Exception #"no items were gathered"
                          (m/? (pipeline/run-task c "2026-09-30"))))
    (is (nil? (store/day (:store c) "2026-09-30")))))

(deftest a-failed-model-call-stores-nothing
  (let [c (ctx [{:type ::fixture :name "A" :ns [1]}]
               (fn [_ _] (throw (ex-info "DeepSeek 401: bad key" {}))))]
    (is (thrown-with-msg? Exception #"401" (m/? (pipeline/run-task c "2026-09-30"))))
    (is (nil? (store/day (:store c) "2026-09-30")))))

(deftest only-one-run-at-a-time-and-a-cancel-stops-it
  (let [c (ctx [{:type ::slow :name "Slow"}] (fn [_ _] {:content "x [1]"}))]
    (is (pipeline/start-run! c "2026-09-30"))
    (is (not (pipeline/start-run! c "2026-09-30")) "a second run is refused")
    (is (pipeline/cancel-run!))
    (loop [i 0] (when (and (pipeline/running?) (< i 50)) (Thread/sleep 20) (recur (inc i))))
    (is (not (pipeline/running?)))
    (is (= :cancelled (:state @pipeline/status)))
    (is (nil? (store/day (:store c) "2026-09-30")))))

(deftest the-item-cap-trims-every-source-evenly
  (let [results [{:items (mapv #(item % "2026-09-30") [1 2 3])}
                 {:items (mapv #(item % "2026-09-30") [10 11])}
                 {:items []}
                 {:items (mapv #(item % "2026-09-30") [20])}]]
    (is (= ["https://e.com/1" "https://e.com/10" "https://e.com/20"
            "https://e.com/2" "https://e.com/11" "https://e.com/3"]
           (map :url (pipeline/day-items results {:max-items-per-source 5} "2026-09-30"))))))

(deftest the-window-runs-from-the-lookback-to-the-next-day
  (let [dated (fn [n day] (assoc (item n day) :published (str day "T08:00:00Z")))
        results [{:items [(dated 1 "2026-09-28") (dated 2 "2026-09-29") (dated 3 "2026-09-30")
                          (dated 4 "2026-10-01") (dated 5 "2026-10-02")]}]]
    (is (= ["https://e.com/2" "https://e.com/3" "https://e.com/4"]
           (map :url (pipeline/day-items results {:lookback-days 1 :max-items-per-source 9}
                                         "2026-09-30"))))))

(deftest a-model-that-runs-out-of-tokens-says-so
  (let [c (ctx [{:type ::fixture :name "A" :ns [1]}]
               (fn [_ _] {:content "" :reasoning "lots" :finish-reason "length"}))]
    (is (thrown-with-msg? Exception #"whole token budget .* raise :max-tokens"
                          (m/? (pipeline/run-task c "2026-09-30"))))))

(deftest a-run-drops-the-days-past-the-limit
  (let [c (-> (ctx [{:type ::fixture :name "A" :ns [1]}] (fn [_ _] {:content "x [1]"}))
              (assoc-in [:config :keep-days] 2))
        old-file (io/file dir "2026-09-27.md")]
    (doseq [d ["2026-09-27" "2026-09-28" "2026-09-29"]]
      (store/save-day! (:store c) {:day d :sources [] :cited [] :markdown d :model "m" :provider "p"}))
    (.mkdirs (io/file dir))
    (spit old-file "old")
    (m/? (pipeline/run-task c "2026-09-30"))
    (is (= ["2026-09-30" "2026-09-29"] (store/days (:store c))))
    (is (not (.exists old-file)) "the markdown goes with the day")
    (is (some #(str/includes? (:text %) "Dropped 2 old days past the limit of 2")
              (:events @pipeline/status)))))

(deftest no-limit-keeps-every-day
  (let [c (-> (ctx [{:type ::fixture :name "A" :ns [1]}] (fn [_ _] {:content "x [1]"}))
              (assoc-in [:config :keep-days] -1))]
    (doseq [d ["2026-09-27" "2026-09-28" "2026-09-29"]]
      (store/save-day! (:store c) {:day d :sources [] :cited [] :markdown d :model "m" :provider "p"}))
    (m/? (pipeline/run-task c "2026-09-30"))
    (is (= 4 (count (store/days (:store c)))))))

(deftest a-cancel-during-the-model-call-reads-as-cancelled
  (let [c (ctx [{:type ::fixture :name "A" :ns [1]}]
               (fn [_ _] (Thread/sleep 5000) {:content "x [1]"}))]
    (is (pipeline/start-run! c "2026-09-30"))
    (loop [i 0] (when (and (not= :analysing (:state @pipeline/status)) (< i 100)) (Thread/sleep 20) (recur (inc i))))
    (is (pipeline/cancel-run!))
    (loop [i 0] (when (and (pipeline/running?) (< i 100)) (Thread/sleep 20) (recur (inc i))))
    (is (not (pipeline/running?)))
    (is (= :cancelled (:state @pipeline/status)))
    (is (nil? (store/day (:store c) "2026-09-30")))))

(deftest stories-in-the-last-briefing-are-left-out
  (let [c (assoc-in (ctx [{:type ::fixture :name "A" :ns [1 2 3]}]
                         (fn [_ _] {:content "# Today\n\nTwo [1]." :model "fake"}))
                    [:config :max-items-per-source] 3)
        st (:store c)
        source (fn [n url] {:n n :title (str "Old " n) :url url :source "Fixture" :summary "" :published nil})]
    ;; the latest briefing before the day, however far back
    (store/save-day! st {:day "2026-09-27" :sources [(source 1 "https://e.com/1?utm_source=rss")
                                                     (source 2 "https://e.com/3/")]
                         :cited [] :markdown "old" :model "m" :provider "p"})
    ;; a later day is not the last briefing
    (store/save-day! st {:day "2026-10-02" :sources [(source 1 "https://e.com/2")]
                         :cited [] :markdown "later" :model "m" :provider "p"})
    (m/? (pipeline/run-task c "2026-09-30"))
    (is (= ["https://e.com/2"] (map :url (:sources (store/day st "2026-09-30")))))
    (is (some #(str/includes? (:text %) "Left out 2 stories already in the briefings up to 27 September 2026")
              (:events @pipeline/status)))))

(deftest a-day-of-nothing-new-says-so
  (let [c (ctx [{:type ::fixture :name "A" :ns [1]}] (fn [_ _] {:content "x" :model "fake"}))]
    (store/save-day! (:store c) {:day "2026-09-29" :sources [{:n 1 :title "Story 1" :url "https://e.com/1"
                                                              :source "Fixture" :summary "" :published nil}]
                                 :cited [] :markdown "old" :model "m" :provider "p"})
    (let [e (try (m/? (pipeline/run-task c "2026-09-30")) nil (catch Exception e e))]
      (is (str/includes? (ex-message e) "already in the briefings up to 29 September 2026")))))

;; --- the schedule ----------------------------------------------------------------

(deftest a-story-older-than-the-seen-days-window-is-new-again
  (let [c (ctx [{:type ::fixture :name "A" :ns [1]}] (fn [_ _] {:content "x [1]" :model "fake"}))
        st (:store c)
        source (fn [n url] {:n n :title (str "Old " n) :url url :source "S" :summary "" :published nil})]
    ;; seen-days is 3: a story that ran only four briefings back, was left
    ;; out of the three since, and returns today is told again
    (store/save-day! st {:day "2026-09-23" :sources [(source 1 "https://e.com/1")] :cited []
                         :markdown "old" :model "m" :provider "p"})
    (doseq [d ["2026-09-25" "2026-09-27" "2026-09-29"]]
      (store/save-day! st {:day d :sources [(source 2 "https://e.com/other")] :cited []
                           :markdown d :model "m" :provider "p"}))
    (m/? (pipeline/run-task c "2026-09-30"))
    (is (= ["https://e.com/1"] (map :url (:sources (store/day st "2026-09-30"))))
        "four briefings back is past the seen window, so the story is told again")))

(deftest near-duplicate-wire-stories-across-outlets-are-collapsed
  (let [dupe-src (fn [name suffix]
                   {:type ::dupe :name name :suffix suffix})
        prompts (atom [])
        c (-> (ctx [(dupe-src "Reuters" "reuters")
                    (dupe-src "BBC" "bbc")]
                   (fn [_ req] (swap! prompts conj (-> req :messages first :content))
                     {:content "# Today\n\nStory [1]." :model "fake"}))
              (assoc-in [:config :dupe-threshold] 0.55)
              (assoc-in [:config :max-items-per-source] 5))]
    (m/? (pipeline/run-task c "2026-09-30"))
    (let [day (store/day (:store c) "2026-09-30")]
      (is (= 1 (count (:sources day))) "one wire story, not one copy per outlet")
      (is (= ["BBC"] (:also (first (:sources day)))) "the other outlet is credited")
      (is (str/includes? (first @prompts) "[1] Central banks warn of rising sovereign debt (Reuters)"))
      (is (some #(str/includes? (:text %) "Collapsed 1 near-duplicate")
                (:events @pipeline/status))))))

(deftest the-standfirst-under-the-title-is-saved-as-the-tldr
  (let [c (ctx [{:type ::fixture :name "A" :ns [1]}]
               (fn [_ _] {:content "# 30 September 2026\n\n> Markets calm after a quiet week.\n\n## Story\n\nText [1]." :model "fake"}))]
    (m/? (pipeline/run-task c "2026-09-30"))
    (is (= "Markets calm after a quiet week."
           (:tldr (store/day (:store c) "2026-09-30"))))))

(deftest source-health-survives-the-run
  (let [c (ctx [{:type ::fixture :name "A" :ns [1]}
                {:type ::broken :name "Broken"}]
               (fn [_ _] {:content "x [1]" :model "fake"}))
        health (fn [] (into {} (map (juxt :name identity)) (store/source-health (:store c))))]
    (m/? (pipeline/run-task c "2026-09-30"))
    (let [h (health)]
      (is (= 0 (:consecutive-failures (h "A"))))
      (is (string? (:last-ok (h "A"))))
      (is (= 1 (:consecutive-failures (h "Broken"))))
      (is (= "feed is down" (:last-error (h "Broken")))))))

(def ^:private hour (* 60 60 1000))

(deftest the-schedule-wakes-at-run-at-then-every-interval
  (let [seven 1000000000000]               ; today's :run-at, as an instant
    (testing "a day apart: the next run-at"
      (is (= seven (pipeline/next-wake seven (* 24 hour) (- seven (* 5 hour)))))
      (is (= (+ seven (* 24 hour)) (pipeline/next-wake seven (* 24 hour) (+ seven 1)))))
    (testing "six hours apart, counted from run-at either way"
      (is (= (- seven (* 6 hour)) (pipeline/next-wake seven (* 6 hour) (- seven (* 7 hour)))))
      (is (= (+ seven (* 12 hour)) (pipeline/next-wake seven (* 6 hour) (+ seven (* 7 hour))))))
    (testing "a wake exactly at a slot looks for the next one"
      (is (= (+ seven (* 6 hour)) (pipeline/next-wake seven (* 6 hour) seven))))))

(deftest a-wake-gathers-unless-the-briefing-is-fresh
  (is (pipeline/due? nil (* 24 hour)) "no briefing yet")
  (is (not (pipeline/due? hour (* 24 hour))) "run by hand an hour before")
  (is (pipeline/due? (* 6 hour) (* 6 hour)) "a whole interval old")
  (is (pipeline/due? (- (* 6 hour) 1000) (* 6 hour)) "a timer firing a moment early still counts"))

(deftest the-interval-comes-from-the-config
  (is (= (* 24 hour) (pipeline/interval-ms {})) "a day by default")
  (is (= (* 6 hour) (pipeline/interval-ms {:run-every-hours 6})))
  (is (= (* 90 60 1000) (pipeline/interval-ms {:run-every-hours 1.5})))
  (is (thrown? Exception (pipeline/interval-ms {:run-every-hours 0})))
  (is (thrown? Exception (pipeline/interval-ms {:run-every-hours "6"}))))

(deftest the-model-builds-on-the-last-briefing
  (let [prompts (atom [])
        c (ctx [{:type ::fixture :name "A" :ns [5]}]
               (fn [_ req] (swap! prompts conj (-> req :messages first :content))
                 {:content "# Today\n\nNew [1]." :model "fake"}))]
    (store/save-day! (:store c) {:day "2026-09-29" :sources []
                                 :cited [] :model "m" :provider "p"
                                 :markdown (str "# Yesterday\n\n## Overview\n\nTariffs went up [[3]](https://e.com/3)."
                                                "\n\n## Politics\n\nDetail that stays out.")})
    (m/? (pipeline/run-task c "2026-09-30"))
    (let [p (first @prompts)]
      (is (str/includes? p "29 September 2026"))
      (is (str/includes? p "Tariffs went up."))
      (is (not (str/includes? p "Detail that stays out")))
      (is (not (str/includes? p "e.com/3")) "yesterday's citations don't come along")
      (is (< (str/index-of p "Tariffs went up") (str/index-of p "[1] Story 5"))
          "the context comes before today's sources"))
    (is (some #(str/includes? (:text %) "Building on the briefing for 29 September 2026")
              (:events @pipeline/status)))))
