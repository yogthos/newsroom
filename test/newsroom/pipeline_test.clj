(ns newsroom.pipeline-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ebb.core :as m]
            [newsroom.embed :as embed]
            [newsroom.pipeline :as pipeline]
            [newsroom.sources :as sources]
            [newsroom.store :as store]
            [newsroom.ui :as ui]))

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

(defmethod sources/fetch-items ::distinct [{:keys [name]} {:keys [day]}]
  ;; two genuinely different stories in the same broad area: a rate decision
  ;; and an election, both "economics-flavoured" words
  (case name
    "Fed watcher" [{:title "Federal Reserve holds rates steady at September meeting"
                    :url "https://fed.example.com/rates-held"
                    :source name
                    :summary "The Federal Reserve left its policy rate unchanged, citing steady inflation progress."
                    :published (str day "T08:00:00Z")}]
    "Pollster" [{:title "Opposition party widens lead in national polling average"
                 :url "https://polls.example.com/average"
                 :source name
                 :summary "The opposition widened its polling lead, weeks before the general election."
                 :published (str day "T08:00:00Z")}]))

(defmethod sources/fetch-items ::dupe [{:keys [name suffix]} {:keys [day]}]
  ;; the same wire story, word for word, at another outlet's address
  [{:title "Central banks warn of rising sovereign debt"
    :url (str "https://" suffix ".example.com/debt-warning")
    :source name
    :summary "Finance ministers from the G7 met to discuss rising sovereign debt levels."
    :published (str day "T08:00:00Z")}])

(defmethod sources/fetch-items ::running [{:keys [name]} {:keys [day]}]
  ;; the outlet's headlines for the day: listed first is a one-off, then a
  ;; story that has been running all week
  (case name
    "Desk" [{:title "Local bakery wins regional bread award"
             :url "https://desk.example.com/bread" :source name
             :summary "A family bakery took first prize at the county fair."
             :published (str day "T08:00:00Z")}
            {:title "Federal Reserve signals it will hold interest rates steady again"
             :url "https://desk.example.com/fed" :source name
             :summary "The central bank kept its benchmark rate unchanged and pointed to inflation."
             :published (str day "T08:00:00Z")}]))

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
    (is (= :cancelled (:state @pipeline/status)) (pr-str (select-keys @pipeline/status [:error :state])))
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
    (is (= :cancelled (:state @pipeline/status)) (pr-str (select-keys @pipeline/status [:error :state])))
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

;; --- storylines and digests ----------------------------------------------------

(def ^:private model (delay (embed/load-model)))

(defn- stored-source
  "A source as an earlier day stored it, with its embedding and storyline."
  [n title story]
  {:n n :title title :url (str "https://old.example.com/" n) :source "Wire" :summary "" :published nil
   :story story :vector (embed/embed @model title)})

(deftest a-story-that-keeps-running-comes-first-with-its-coverage
  (let [prompts (atom [])
        c (-> (ctx [{:type ::running :name "Desk"}]
                   (fn [_ req] (swap! prompts conj (-> req :messages first :content))
                     {:content "# Today\n\nRates [1]." :model "fake"}))
              (assoc-in [:config :story-threshold] 0.5))
        st (:store c)]
    (doseq [[d n] [["2026-09-27" 1] ["2026-09-28" 1] ["2026-09-29" 2]]]
      (store/save-day! st {:day d
                           :sources [(stored-source n "Federal Reserve holds rates steady as inflation cools"
                                                    "2026-09-27/1")]
                           :cited [] :markdown d :model "m" :provider "p"}))
    (m/? (pipeline/run-task c "2026-09-30"))
    (let [[first-source second-source] (:sources (store/day st "2026-09-30"))]
      (is (= "https://desk.example.com/fed" (:url first-source)) "the running story outranks the one-off")
      (is (= "2026-09-27/1" (:story first-source)) "and continues its storyline")
      (is (> (:weight first-source) (:weight second-source)))
      (is (= "2026-09-30/2" (:story second-source)) "a story new today is named by its day and number"))
    (is (str/includes? (first @prompts) "Coverage: in the news on 4 days"))
    (is (some #(str/includes? (:text %) "Followed 1 stories from earlier days")
              (:events @pipeline/status)))))

(defn- digest-ctx [chat]
  (-> (ctx [] chat)
      (assoc :digest-template "Digest of {{period}}.\n\n{{days}}\n\n{{stories}}")))

(defn- store-week!
  "A week of briefings in which one story runs every day and another once."
  [st]
  (doseq [[i d] (map-indexed vector ["2026-09-28" "2026-09-29" "2026-09-30" "2026-10-01"])]
    (store/save-day! st {:day d
                         :sources [(assoc (stored-source 1 "Federal Reserve holds rates steady" "2026-09-28/1")
                                          :also [{:source "Other" :url (str "https://other.example.com/" i)}])
                                   (stored-source 2 (str "One-off story " i) (str d "/2"))]
                         :cited [1] :tldr (str "Day " i ".") :markdown d :model "m" :provider "p"})))

(deftest a-weekly-digest-is-written-from-the-stored-days
  (let [prompts (atom [])
        c (digest-ctx (fn [_ req] (swap! prompts conj (-> req :messages first :content))
                        {:content "# The week\n\n> Rates held all week [1].\n\n## Overview\n\nSteady [1, 2]." :model "fake"}))
        st (:store c)]
    (store-week! st)
    (is (= {:digest {:kind :week :period "2026-W40"} :items 4 :cited 2}
           (m/? (pipeline/digest-task c :week "2026-W40"))))
    (let [prompt (first @prompts)
          d (store/digest st :week "2026-W40")]
      (is (str/starts-with? prompt "Digest of the week of 28 September 2026."))
      (is (str/includes? prompt "- 28 September 2026: Day 0."))
      (is (str/includes? prompt "### Federal Reserve holds rates steady\nTrend: persistent"))
      (is (not (str/includes? prompt "One-off story")) "a one-outlet one-off isn't a storyline")
      (is (= "Rates held all week." (:tldr d)))
      (is (= 4 (count (:sources d))))
      (is (str/includes? (:markdown d) "## Sources"))
      (is (.exists (io/file dir "2026-W40.md"))))
    (testing "the next week's digest builds on it"
      (store/save-day! st {:day "2026-10-05" :sources [(stored-source 1 "Federal Reserve holds rates steady" "2026-09-28/1")
                                                       (assoc (stored-source 2 "Fed again" "2026-09-28/1") :source "Other")]
                           :cited [] :markdown "x" :model "m" :provider "p"})
      (m/? (pipeline/digest-task c :week "2026-W41"))
      (is (str/includes? (second @prompts) "Digest of the week of 5 October 2026."))
      (is (str/includes? (second @prompts) "in the periods before")))))

(deftest a-digest-reads-days-stored-before-storylines-were-kept
  ;; sources from before storylines and notes: no :story, no vector
  (let [c (digest-ctx (fn [_ _] {:content "# September\n\n## Overview\n\nRates [1]." :model "fake"}))
        st (:store c)]
    (doseq [d ["2026-09-28" "2026-09-29"]]
      (store/save-day! st {:day d
                           :sources [{:n 1 :title (str "Fed holds, " d) :url (str "https://wire.org/" d) :source "Wire"
                                      :summary "" :published nil :also [{:source "Other"}]}]
                           :cited [1] :markdown d :model "m" :provider "p"}))
    (is (= {:digest {:kind :month :period "2026-09"} :items 2 :cited 1}
           (m/? (pipeline/digest-task c :month "2026-09"))))))

(deftest days-stored-before-storylines-are-followed-for-a-digest
  ;; as the main branch stored them: no storylines, no embeddings, and
  ;; every outlet's copy a source of its own
  (let [prompts (atom [])
        c (digest-ctx (fn [_ req] (swap! prompts conj (-> req :messages first :content))
                        {:content "# September\n\n## Overview\n\nRates [1]." :model "fake"}))
        st (:store c)
        source (fn [n outlet title] {:n n :title title :url (str "https://" outlet ".org/" n) :source outlet
                                     :summary "The central bank left its benchmark rate unchanged." :published nil})]
    (doseq [[d fed] [["2026-09-28" "Federal Reserve holds interest rates steady"]
                     ["2026-09-29" "Federal Reserve keeps interest rates on hold again"]]]
      (store/save-day! st {:day d
                           :sources (cond-> [(source 1 "wire" fed)
                                             (source 2 "paper" (str fed ", citing inflation"))]
                                      (= d "2026-09-28")
                                      (conj (assoc (source 3 "blog" "Local bakery wins a bread award")
                                                   :summary "A family bakery took first prize at the fair.")))
                           :cited [] :markdown d :model "m" :provider "p"}))
    (m/? (pipeline/digest-task c :month "2026-09"))
    (let [prompt (first @prompts)]
      (is (str/includes? prompt "4 outlet reports over 2 days") "both outlets' copies on both days, as one storyline")
      (is (not (str/includes? prompt "bakery")) "a one-off stays out"))
    (is (empty? (store/days-without-storylines st "2026-09-01" "2026-09-30")) "and the days keep their storylines")
    (is (some #(str/includes? (:text %) "Followed the stories through 2 days stored before storylines were kept")
              (:events @pipeline/status)))))

(deftest a-period-with-nothing-to-digest-is-not-a-failure
  (let [c (digest-ctx (fn [_ _] {:content "x" :model "fake"}))]
    (store/save-day! (:store c) {:day "2026-09-28" :cited [] :markdown "x" :model "m" :provider "p"
                                 :sources [{:n 1 :title "Lone story" :url "https://one.org/1" :source "One"
                                            :summary "" :published nil :story "2026-09-28/1"}]})
    (is (pipeline/start-digest! c :month "2026-09"))
    (loop [i 0] (when (and (pipeline/running?) (< i 100)) (Thread/sleep 50) (recur (inc i))))
    (is (= :done (:state @pipeline/status)))
    (is (str/includes? (:nothing @pipeline/status) "no story in September 2026"))
    (is (str/includes? (ui/page (:store c) "2026-09-28") "Nothing to digest for September 2026"))))

(deftest a-period-with-no-briefings-has-no-digest
  (let [c (digest-ctx (fn [_ _] {:content "x" :model "fake"}))
        e (try (m/? (pipeline/digest-task c :month "2026-01")) nil (catch Exception e e))]
    (is (str/includes? (ex-message e) "there are no briefings for January 2026"))
    (is (nil? (store/digest (:store c) :month "2026-01")))))

(deftest digests-fall-due-once-their-period-is-over
  (let [c (digest-ctx (fn [_ _] {:content "# W\n\n## O\n\nx [1]" :model "fake"}))
        st (:store c)]
    (store-week! st)
    (let [weekly (assoc (:config c) :digests [:week])]
      (is (= [] (pipeline/due-digests st (:config c) "2026-09-30")) "no period with briefings is over")
      (is (= [] (pipeline/due-digests st weekly "2026-10-04")) "the week isn't over")
      (is (= [[:week "2026-W40"]] (pipeline/due-digests st weekly "2026-10-05")))
      (is (= [[:week "2026-W40"] [:month "2026-09"]] (pipeline/due-digests st (:config c) "2026-10-05"))
          "and September's, which ended the week before")
      (m/? (pipeline/digest-task c :week "2026-W40"))
      (is (= [] (pipeline/due-digests st weekly "2026-10-05")) "a written digest isn't due again")
      (is (= [] (pipeline/due-digests st (assoc (:config c) :digests []) "2026-10-05")) "[] turns them off"))))

;; --- story notes -----------------------------------------------------------------

(defn- notes-chat
  "A model that writes the briefing, and answers a notes prompt by noting
  every storyline it is asked about with a fact from its first report."
  [prompts]
  (fn [_ req]
    (let [prompt (-> req :messages first :content)]
      (swap! prompts conj prompt)
      (if (str/starts-with? prompt "You keep the running notes")
        {:content (str "{\"storylines\": ["
                       (str/join ", " (for [[_ id n] (re-seq #"### id: (\S+)\n[\s\S]*?\[(\d+)\]" prompt)]
                                        (str "{\"id\": \"" id "\", \"title\": \"Noted " id "\", "
                                             "\"summary\": \"Where " id " stands.\", \"facts\": "
                                             "[{\"day\": \"2026-09-30\", \"fact\": \"Fact from " n ".\", "
                                             "\"cite\": \"" n "\"}]}")))
                       "]}")
         :model "fake"}
        {:content "# Today\n\n> Steady.\n\n## Overview\n\nRates [1]." :model "fake"}))))

(deftest a-run-keeps-notes-and-the-next-day-hears-them
  (let [prompts (atom [])
        c (-> (ctx [{:type ::running :name "Desk"}] (notes-chat prompts))
              (assoc-in [:config :story-threshold] 0.5)
              (assoc-in [:config :story-notes] 12))
        st (:store c)]
    (store/save-day! st {:day "2026-09-29"
                         :sources [(stored-source 1 "Federal Reserve holds rates steady as inflation cools" "2026-09-29/1")]
                         :cited [] :markdown "x" :model "m" :provider "p"})
    (store/save-notes! st {"2026-09-29/1" {:title "The Fed's pause" :summary "The Fed keeps holding."
                                           :first-day "2026-09-29" :last-day "2026-09-29"
                                           :facts [{:day "2026-09-29" :text "The Fed held." :url "https://old.example.com/1"
                                                    :source "Wire" :headline "Fed holds"}]}})
    (m/? (pipeline/run-task c "2026-09-30"))
    (let [[briefing compaction] @prompts]
      (is (str/includes? briefing "## The stories still running") "the briefing gets the running story's notes")
      (is (str/includes? briefing "The Fed keeps holding."))
      (is (str/includes? compaction "Notes so far:\nTitle: The Fed's pause"))
      (is (not (str/includes? compaction "bread")) "a one-off the briefing didn't cite gets no note"))
    (let [note (get (store/notes st ["2026-09-29/1"]) "2026-09-29/1")]
      (is (= "Where 2026-09-29/1 stands." (:summary note)))
      (is (= "https://desk.example.com/fed" (:url (first (:facts note)))) "the fact keeps its report")
      (is (= "2026-09-29" (:first-day note))))))

(deftest a-failed-compaction-leaves-the-briefing-filed
  (let [c (-> (ctx [{:type ::fixture :name "A" :ns [1]}]
                   (fn [_ req] (if (str/starts-with? (-> req :messages first :content) "You keep")
                                 (throw (ex-info "rate limited" {}))
                                 {:content "x [1]" :model "fake"})))
              (assoc-in [:config :story-notes] 12))]
    (m/? (pipeline/run-task c "2026-09-30"))
    (is (some? (store/day (:store c) "2026-09-30")))
    (is (some #(str/includes? (:text %) "The notes weren't updated: rate limited") (:events @pipeline/status)))))

(deftest a-digest-is-written-from-the-notes-once-the-days-are-gone
  (let [prompts (atom [])
        c (-> (digest-ctx (fn [_ req] (swap! prompts conj (-> req :messages first :content))
                            {:content "# The week\n\n## Overview\n\nHeld [1]." :model "fake"}))
              (assoc-in [:config :keep-days] 1))
        st (:store c)]
    (store-week! st)
    (store/save-notes! st {"2026-09-28/1" {:title "The Fed's long pause" :summary "Held all week."
                                           :first-day "2026-09-28" :last-day "2026-10-01"
                                           :facts [{:day "2026-09-20" :text "Before the week." :url "https://e.com/0"
                                                    :source "Wire" :headline "Earlier"}
                                                   {:day "2026-09-29" :text "The Fed held." :url "https://e.com/1"
                                                    :source "Wire" :headline "Fed holds"}]}})
    (pipeline/prune-days! c)
    (is (= ["2026-10-01"] (store/days st)) "only the last day's sources are left")
    (m/? (pipeline/digest-task c :week "2026-W40"))
    (let [prompt (first @prompts)]
      (is (str/includes? prompt "### The Fed's long pause\nTrend: persistent"))
      (is (str/includes? prompt "Where it stands: Held all week."))
      (is (str/includes? prompt "[1] 29 September 2026: The Fed held.\nFed holds (Wire), https://e.com/1"))
      (is (not (str/includes? prompt "Before the week.")) "only the period's facts")
      (is (str/includes? prompt "- 28 September 2026: Day 0.") "the standfirsts outlive the days"))
    (is (= ["https://e.com/1"] (map :url (:sources (store/digest st :week "2026-W40")))))))

;; --- the schedule ----------------------------------------------------------------

(deftest a-scheduled-job-writes-its-digests-even-when-the-day-fails
  (let [token (Object.)
        c (-> (digest-ctx (fn [_ _] {:content "# The week\n\n## Overview\n\nSteady [1]." :model "fake"}))
              ;; as start-job! runs it, reporting to a status of its own
              (assoc :run-id token))
        _ (reset! pipeline/status {:run token :state :starting})
        st (:store c)
        ;; nothing to gather, so the day's run fails
        e (do (store-week! st)
              (try (m/? (#'pipeline/scheduled-task c "2026-10-05" true [[:month "2026-08"] [:week "2026-W40"]]))
                   nil
                   (catch Exception e e)))]
    (is (str/includes? (ex-message e) "no items were gathered") "the job ends as the day's run did")
    (is (some? (store/digest st :week "2026-W40")) "the week's digest was written all the same")
    (is (some #(str/includes? (:text %) "The digest for August 2026 failed: there are no briefings")
              (:events @pipeline/status))
        "and a digest that failed is logged, without stopping the next")))

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
      (is (= ["BBC"] (map :source (:also (first (:sources day))))) "the other outlet is credited")
      (is (= "https://bbc.example.com/debt-warning" (:url (first (:also (first (:sources day))))))
          "with the copy's address, so it counts as told tomorrow")
      (is (str/includes? (first @prompts) "[1] Central banks warn of rising sovereign debt (Reuters)"))
      (is (some #(str/includes? (:text %) "Collapsed 1 near-duplicate")
                (:events @pipeline/status))))))

(deftest the-standfirst-under-the-title-is-saved-as-the-tldr
  (let [c (ctx [{:type ::fixture :name "A" :ns [1]}]
               (fn [_ _] {:content "# 30 September 2026\n\n> Markets calm after a quiet week.\n\n## Story\n\nText [1]." :model "fake"}))]
    (m/? (pipeline/run-task c "2026-09-30"))
    (is (= "Markets calm after a quiet week."
           (:tldr (store/day (:store c) "2026-09-30"))))))

(deftest distinct-stories-from-different-outlets-stay-apart
  (let [c (-> (ctx [{:type ::distinct :name "Fed watcher"}
                    {:type ::distinct :name "Pollster"}]
                   (fn [_ _] {:content "x [1]" :model "fake"}))
              (assoc-in [:config :dupe-threshold] 0.55))]
    (m/? (pipeline/run-task c "2026-09-30"))
    (is (= 2 (count (:sources (store/day (:store c) "2026-09-30"))))
        "a rate decision and an election are two stories, not one")
    (is (nil? (:also (first (:sources (store/day (:store c) "2026-09-30"))))))))

(deftest a-second-round-of-research-grounds-the-day-in-history
  (let [prompts (atom [])
        searches (atom [])
        c (-> (ctx [{:type ::fixture :name "A" :ns [1]}]
                   (fn [_ req]
                     (let [p (-> req :messages first :content)]
                       (swap! prompts conj p)
                       (if (str/starts-with? p "You find the historical background")
                         {:content "{\"queries\": [\"history of central bank rate pause cycles\"]}" :model "fake"}
                         {:content "# Today\n\n## Overview\n\nToday [1] echoes [2]: the last time this happened, markets took months to settle."
                          :model "fake"}))))
              (assoc-in [:config :precedents] 3)
              (assoc :search (fn [source _]
                               (swap! searches conj source)
                               (m/sp {:items [{:title "The Fed's long pause of 2015"
                                               :url "https://reuters.com/2015-fed-pause"
                                               :source "Web search"
                                               :summary "When the Fed paused in 2015 after years of near-zero rates, ..."
                                               :published "16 December 2015"}]
                                      :source "Precedent search"}))))
        st (:store c)]
    (m/? (pipeline/run-task c "2026-09-30"))
    (testing "the model picks the searches from the day's stories"
      (is (= 2 (count @prompts)) "one call for the queries, one for the briefing")
      (is (str/includes? (first @prompts) "[1] Story 1") "it sees the day's stories")
      (is (= ["history of central bank rate pause cycles"] (:queries (first @searches))))
      (is (= :web-search (:type (first @searches)))))
    (testing "the precedents join the day's sources, after today's, marked apart"
      (let [prompt (second @prompts)]
        (is (str/includes? prompt "[1] Story 1 (Fixture)"))
        (is (str/includes? prompt "[2] The Fed's long pause of 2015"))
        (is (< (str/index-of prompt "[1] Story 1") (str/index-of prompt "Historical precedents")
               (str/index-of prompt "[2] The Fed's long pause of 2015"))
            "a note introduces them between today's sources and theirs")))
    (testing "they are stored, citeable and remembered"
      (let [day (store/day st "2026-09-30")]
        (is (= 2 (count (:sources day))))
        (is (= "https://reuters.com/2015-fed-pause" (:url (second (:sources day)))))
        (is (= "2026-09-30/1" (:story (first (:sources day)))) "today's stories keep their storylines")
        (is (nil? (:story (second (:sources day)))) "a precedent starts no storyline")
        (is (= [1 2] (map :n (:cited day))))
        (is (str/includes? (:markdown day) "[[2]](https://reuters.com/2015-fed-pause)")))
      (is (some #(str/includes? (:text %) "1 historical precedent")
                (:events @pipeline/status))))))

(deftest precedent-research-off-or-empty-costs-nothing
  (testing "nil turns the round off"
    (let [prompts (atom [])
          searches (atom [])
          c (-> (ctx [{:type ::fixture :name "A" :ns [1]}]
                     (fn [_ req] (swap! prompts conj (-> req :messages first :content))
                       {:content "x [1]" :model "fake"}))
                (assoc-in [:config :precedents] nil)
                (assoc :search (fn [source _] (swap! searches conj source) (m/sp {:items [] :source "x"}))))]
      (m/? (pipeline/run-task c "2026-09-30"))
      (is (= 1 (count @prompts)) "no call for queries")
      (is (empty? @searches))))
  (testing "an answer with no queries in it searches nothing"
    (let [searches (atom [])
          c (-> (ctx [{:type ::fixture :name "A" :ns [1]}]
                     (fn [_ req]
                       (let [p (-> req :messages first :content)]
                         (if (str/starts-with? p "You find the historical background")
                           {:content "I see no instructive past here." :model "fake"}
                           {:content "x [1]" :model "fake"}))))
                  (assoc-in [:config :precedents] 3)
                  (assoc :search (fn [source _] (swap! searches conj source) (m/sp {:items [] :source "x"}))))]
      (m/? (pipeline/run-task c "2026-09-30"))
      (is (empty? @searches))
      (is (= 1 (count (:sources (store/day (:store c) "2026-09-30")))))))
  (testing "a failed search is logged and the day is filed without precedents"
    (let [c (-> (ctx [{:type ::fixture :name "A" :ns [1]}]
                     (fn [_ req]
                       (let [p (-> req :messages first :content)]
                         (if (str/starts-with? p "You find the historical background")
                           {:content "{\"queries\": [\"q\"]}" :model "fake"}
                           {:content "x [1]" :model "fake"}))))
                  (assoc-in [:config :precedents] 3)
                  (assoc :search (fn [_ _] (m/sp {:error "web search answered 429"}))))]
      (m/? (pipeline/run-task c "2026-09-30"))
      (is (= 1 (count (:sources (store/day (:store c) "2026-09-30")))))
      (is (some #(str/includes? (:text %) "search for precedents failed")
                (filter #(= :error (:level %)) (:events @pipeline/status)))))
    (let [calls (atom 0)
          c (-> (ctx [{:type ::fixture :name "A" :ns [1]}]
                     (fn [_ req]
                       (let [p (-> req :messages first :content)]
                         (if (str/starts-with? p "You find the historical background")
                           (do (swap! calls inc) (throw (ex-info "DeepSeek 429: rate limited" {})))
                           {:content "x [1]" :model "fake"}))))
                  (assoc-in [:config :precedents] 3))]
      (m/? (pipeline/run-task c "2026-09-30"))
      (is (= 1 @calls) "the query round ran and failed")
      (is (= 1 (count (:sources (store/day (:store c) "2026-09-30")))) "the day is still filed")
      (is (some #(str/includes? (:text %) "precedent research failed")
                (filter #(= :error (:level %)) (:events @pipeline/status)))))))

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
