(ns newsroom.world-test
  (:require [bnm.core :as bnm]
            [bnm.store :as bnm-store]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [newsroom.store :as store]
            [newsroom.world :as world]))

(def ^:private sources
  [{:n 1 :title "Strikes on Beirut" :url "https://e.com/1" :summary "Israel struck." :published "2026-10-14"}
   {:n 2 :title "Protests in Kyiv" :url "https://e.com/2" :summary "Crowds gathered." :published nil}
   {:n 3 :title "Why it won't hold" :url "https://e.com/3" :summary "An argument." :kind :analysis}
   {:n 4 :title "The 1982 invasion" :url "https://e.com/4" :summary "History." :precedent true}])

(deftest the-coder-reads-the-days-reports-in-full-where-they-were-read
  (let [as (world/articles sources {"https://e.com/1" (str "Israeli jets struck Beirut. " (str/join (repeat 4000 "x")))}
                           "2026-10-15")]
    (is (= [1 2] (map :n as)) "neither the analysis nor the precedents")
    (is (= ["https://e.com/1" "https://e.com/2"] (map :url as)))
    (is (str/starts-with? (:text (first as)) "Israeli jets struck Beirut."))
    (is (<= (count (:text (first as))) 3001) "clipped")
    (is (= "Crowds gathered." (:text (second as))) "the summary of one not read")
    (is (= "2026-10-15" (:published (second as))) "undated, the day's")))

(deftest the-coders-answer-is-read-into-events-by-article
  (let [as (world/articles sources {} "2026-10-15")
        answer (str "```json\n{\"events\": ["
                    "{\"article\": 1, \"date\": \"2026-10-14\", \"actor\": \"ISR\", \"actor_sector\": \"MIL\","
                    " \"target\": \"LBN\", \"where\": \"LBN\", \"type\": \"assault\", \"mode\": \"did\", \"quote\": \"struck\"},"
                    "{\"article\": \"2\", \"actor\": \"Ukraine\", \"type\": \"protest\", \"mode\": \"did\"},"
                    "{\"article\": 9, \"actor\": \"RUS\", \"target\": \"UKR\", \"type\": \"consult\"},"
                    "{\"article\": 1, \"actor\": \"ISR\", \"type\": \"assault\", \"retrospective\": true},"
                    "{\"article\": 1, \"actor\": \"ISR\", \"type\": \"gossip\"}]}\n```")
        es (world/coded answer as "2026-10-15")]
    (is (= [["https://e.com/1" "20261014" "ISR" "MIL" "LBN" :assault]
            ["https://e.com/2" "20261015" "UKR" nil nil :protest]
            ["https://e.com/1" "20261015" "RUS" nil "UKR" :consult]]
           (map (juxt :url :date :a1 :s1 :a2 :type) es))
        "an article out of the batch is credited to its first; the history and the unknown type are dropped")
    (is (not-any? #(contains? % :article) es))
    (is (empty? (world/coded "not json" as "2026-10-15")))))

(defn- days-of-events
  "`n` days of events from `from`: fighting between RUS and UKR that flares
  in its last weeks, protests in FRA, talks between USA and CHN."
  [from n]
  (for [d (range n)
        :let [date (str/replace (str (.plusDays (java.time.LocalDate/parse from) d)) "-" "")
              flare (if (< (- n 60) d) 5 1)]
        e (concat (repeat (* 3 flare) {:a1 "RUS" :a2 "UKR" :type :assault :mode :did})
                  (repeat 3 {:a1 "FRA" :type :protest :mode :did})
                  (repeat 5 {:a1 "USA" :a2 "CHN" :type :consult :mode :did}))]
    (assoc e :date date)))

(deftest the-model-scores-its-quarterly-outlooks-and-recalibrates
  (let [db (store/open "sqlite::memory:")
        registered (atom 0)]
    (try
      (is (nil? (world/outlook! db 10)) "nothing before a week is learned")
      (doseq [day (partition-by :date (days-of-events "2026-01-05" 300))]
        (world/learn! db day)
        (when (bnm/period-eve? (world/model db))
          (swap! registered inc)
          (world/outlook! db 10)))
      (is (< 2 @registered) "an outlook on each quarter's eve")
      (is (not (bnm/period-eve? (world/model db))) "and registered with the model")
      (let [record (world/record db)]
        (is (pos? (get-in record [[:country :violence] :n])) "the quarters over were scored")
        (is (number? (world/skill (get record [:country :violence])))))
      (testing "what it learned is in the database, the history and record with it"
        (let [back (bnm/load (store/world-model-store db))]
          (is (= (:bin (world/model db)) (:bin back)))
          (is (= (world/record db) (bnm/record back)))
          (is (seq (:calibration back)))))
      (testing "a place's drivers are read from the database"
        (is (seq (world/drivers db "UKR" :violence 3))))
      (finally (store/close db)))))

(deftest the-model-starts-from-the-pretrained-snapshot
  (let [trained (reduce #(bnm/add-events %1 "gdelt" %2) (bnm/create)
                        (partition-by :date (days-of-events "2025-01-06" 120)))
        file (java.io.File/createTempFile "seed" ".bnm")
        db (store/open "sqlite::memory:")
        said (atom [])]
    (try
      (.delete file)
      (bnm-store/write-file trained (str file))
      (world/seed! db nil #(swap! said conj %))
      (is (store/world-model-empty? db) "no seed, no model")
      (world/seed! db (str file) #(swap! said conj %))
      (is (= (:bin trained) (world/learned-bin db)) "the snapshot, as trained")
      (is (= ["Loaded the world model's pretrained snapshot"] @said))
      (world/learn! db (days-of-events "2025-05-12" 3))
      (world/seed! db (str file) #(swap! said conj %))
      (is (= 1 (count @said)) "only into an empty database")
      (finally (store/close db) (.delete file)))))

(def ^:private stored
  [{:made "2026-10-15" :horizon 13 :node "SDN" :channel :violence :kind :country :recent 10.0 :last 10.0
    :expected 15.0 :low 8.0 :high 24.0 :p-escalate 0.6 :p-cal 0.55}
   {:made "2026-10-15" :horizon 13 :node "FRA" :channel :protest :kind :country :recent 30.0 :last 20.0
    :expected 33.0 :low 25.0 :high 45.0 :p-escalate 0.2 :p-cal 0.25}
   {:made "2026-10-15" :horizon 13 :node "RUS|UKR" :channel :violence :kind :dyad :recent 50.0 :last 40.0
    :expected 70.0 :low 40.0 :high 90.0 :p-escalate 0.7 :p-cal 0.65}
   {:made "2026-10-15" :horizon 13 :node "UKR:OPP" :channel :protest :kind :actor :recent 5.0 :last 4.0
    :expected 7.0 :low 3.0 :high 12.0 :p-escalate 0.4 :p-cal 0.45}
   {:made "2026-10-15" :horizon 13 :node "s/eastern-africa" :channel :violence :kind :subregion :recent 50.0
    :last 50.0 :expected 70.0 :low 40.0 :high 90.0 :p-escalate 0.9 :p-cal 0.9}
   {:made "2026-10-15" :horizon 13 :node "USA" :channel :verbal-coop :kind :country :recent 90.0 :last 90.0
    :expected 95.0 :low 80.0 :high 110.0 :p-escalate 0.9 :p-cal 0.9}])

(deftest the-analyst-is-told-the-likeliest-escalations-at-every-level
  (let [record {[:country :violence] {:n 40 :periods 3 :positives 6 :brier-cal 0.08 :brier-clim 0.1}}
        block (world/outlook-block "2026-10-15" stored record
                                   [[["UKR" :violence] [{:source "UKR" :kind :self :scale :slow :channel :violence
                                                         :share 0.45}
                                                        {:source "RUS|UKR" :kind :parent :scale :fast
                                                         :channel :violence :share 0.2}]]]
                                   identity)]
    (is (str/starts-with? block "## The world model's outlook"))
    (is (str/includes? block "Between countries, the likeliest escalations:\n- Russia – Ukraine: violence, P(at least 1.5× last quarter) = 0.65; expected 1.8×, 80% range 1.0×–2.3×"))
    (is (str/includes? block "Within countries, the likeliest escalations:\n- Ukraine: opposition: protest"))
    (is (str/includes? block "- Sudan: violence, P(at least 1.5× its usual quarter) = 0.55;"))
    (is (< (str/index-of block "Sudan") (str/index-of block "France")) "the likeliest first")
    (is (str/includes? block "Regions, the likeliest escalations:\n- Eastern africa: violence"))
    (is (not (str/includes? block "United States")) "only the channels that escalate")
    (is (str/includes? block "- Violence: 40 forecasts over 3 quarters, 6 escalations; 20% better than the base rate"))
    (is (str/includes? block "- Ukraine's violence: its own violence (slow, 45%); Russia – Ukraine's violence (fast, 20%)"))
    (is (nil? (world/outlook-block "2026-10-15" [] {} [] identity)) "nothing to tell")))

(deftest the-likeliest-escalations-between-and-of-countries-are-the-models-projections
  (let [ps (world/projections stored "2026-10-15" 5 (fn [d n] (str d "+" n)))]
    (is (= ["Russia – Ukraine" "Sudan"] (map #(first (str/split (:claim %) #"'s ")) ps))
        "those it gives at least even odds, not the regions'")
    (is (= {:made-on "2026-10-15" :subject "conflict" :source "World model" :outlet "World model" :due "2026-10-15+91"}
           (dissoc (first ps) :claim)))
    (is (str/includes? (:claim (first ps)) "rises to at least 1.5× last quarter's level over the 13 weeks"))))

(deftest the-places-of-the-days-stories-are-its-countries-and-their-pairs
  (is (= ["UKR" "RUS" "RUS|UKR" "FRA" "FRA|UKR"]
         (world/story-places [{:story "a" :name "Ukraine" :kind "country"}
                              {:story "a" :name "Russia" :kind "country"}
                              {:story "a" :name "Kyiv" :kind "place"}
                              {:story "b" :name "Ukraine" :kind "country"}
                              {:story "b" :name "Russia" :kind "country"}
                              {:story "c" :name "France" :kind "country"}
                              {:story "c" :name "Ukraine" :kind "country"}]))))

(deftest places-are-named-as-a-reader-knows-them
  (is (= ["Ukraine" "Russia – Ukraine" "Eastern africa" "the world" "Ukraine (the rest)" "Ukraine: opposition"]
         (map world/label ["UKR" "RUS|UKR" "s/eastern-africa" "world" "UKR:*" "UKR:OPP"])))
  (is (= "UKR" (world/place "Ukraine")))
  (is (= "UKR" (world/place "ukr")))
  (is (nil? (world/place "Atlantis"))))
