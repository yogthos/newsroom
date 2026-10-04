(ns newsroom.store-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [jdbc.core :as jdbc]
            [newsroom.store :as store]))

(defn- fresh [] (store/open "sqlite::memory:"))

(def sources
  [{:n 1 :title "A" :url "https://e.com/a" :source "Wire" :summary "sa" :published "2026-09-30"}
   {:n 2 :title "B" :url "https://e.com/b" :source "Web" :summary "" :published nil}
   {:n 3 :title "C" :url "https://e.com/c" :source "Wire" :summary "sc" :published nil}])

(deftest a-day-round-trips
  (let [db (fresh)]
    (try
      (store/save-day! db {:day "2026-09-30" :sources sources :cited [1 3]
                           :markdown "# Brief\n\n[1] and [3]" :model "m" :provider "p"})
      (let [day (store/day db "2026-09-30")]
        (is (= "# Brief\n\n[1] and [3]" (:markdown day)))
        (is (= "m" (:model day)))
        (is (= "p" (:provider day)))
        (is (string? (:created-at day)))
        (is (= sources (:sources day)) "every gathered source, in order")
        (is (= [1 3] (map :n (:cited day))) "the sources the briefing cites"))
      (finally (store/close db)))))

(deftest a-day-with-a-tldr-and-corroborated-source-round-trips
  (let [db (fresh)]
    (try
      (store/save-day! db {:day "2026-09-30"
                           :sources [(assoc (first sources) :also [{:source "AP" :url "https://ap.example.com/1" :title "AP copy"}
                                                                   {:source "Reuters" :url "https://r.example.com/1" :title "Reuters copy"}])]
                           :cited [1] :tldr "Rates up, tech down"
                           :markdown "# The day\n\n> Rates up, tech down" :model "m" :provider "p"})
      (let [day (store/day db "2026-09-30")]
        (is (= "Rates up, tech down" (:tldr day)))
        (is (= ["AP" "Reuters"] (map :source (:also (first (:sources day))))))
        (is (= "https://ap.example.com/1" (:url (first (:also (first (:sources day))))))))
      (is (= [{:day "2026-09-30" :tldr "Rates up, tech down"}] (store/archive db)))
      (finally (store/close db)))))

(deftest source-health-rolls-up-over-runs
  (let [db (fresh)]
    (try
      (store/record-source-health! db [{:source "BBC World" :error nil}
                                       {:source "Dead feed" :error "timed out after 30s"}])
      (let [h (into {} (map (juxt :name identity)) (store/source-health db))]
        (is (= 0 (:consecutive-failures (h "BBC World"))))
        (is (string? (:last-ok (h "BBC World"))))
        (is (= 1 (:consecutive-failures (h "Dead feed"))))
        (is (= "timed out after 30s" (:last-error (h "Dead feed"))))
        (is (nil? (:last-ok (h "Dead feed")))))
      (testing "failures accumulate until a success resets them"
        (store/record-source-health! db [{:source "Dead feed" :error "connection refused"}])
        (is (= 2 (:consecutive-failures (get (into {} (map (juxt :name identity))
                                                          (store/source-health db))
                                             "Dead feed")))
            "the second failure bumps the count rather than restarting it")
        (store/record-source-health! db [{:source "Dead feed" :error nil}])
        (let [h (into {} (map (juxt :name identity)) (store/source-health db))]
          (is (= 0 (:consecutive-failures (h "Dead feed"))))
          (is (nil? (:last-error (h "Dead feed"))))
          (is (nil? (h "BBC World")) "a source the run no longer fetches is forgotten")))
      (finally (store/close db)))))

(deftest a-source-keeps-its-storyline-for-later-days
  (let [db (fresh)
        v (mapv #(/ % (Math/sqrt 2)) [1.0 1.0])]
    (try
      (store/save-day! db {:day "2026-09-29" :sources [(assoc (first sources) :vector v :story "2026-09-29/1" :weight 2.5)]
                           :cited [1] :markdown "x" :model "m" :provider "p"})
      (store/save-day! db {:day "2026-09-30" :sources [(second sources)] :cited [] :markdown "y" :model "m" :provider "p"})
      (store/save-day! db {:day "2026-10-01" :sources [(nth sources 2)] :cited [] :markdown "z" :model "m" :provider "p"})
      (let [[a b :as between] (store/sources-between db "2026-09-29" "2026-09-30")]
        (is (= ["2026-09-29" "2026-09-30"] (map :day between)) "both ends included, nothing past them")
        (is (= "2026-09-29/1" (:story a)))
        (is (= 2.5 (:weight a)))
        (is (true? (:cited? a)))
        (is (< (Math/abs (- 1.0 (reduce + (map * v (:vector a))))) 1e-4) "the vector comes back")
        (is (nil? (:vector b)))
        (is (false? (:cited? b))))
      (is (nil? (:vector (first (:sources (store/day db "2026-09-29"))))) "a day's page doesn't decode vectors")
      (finally (store/close db)))))

(deftest coverage-outlives-the-days
  (let [db (fresh)
        day (fn [d] {:day d :sources [(assoc (first sources) :story "s1" :url "https://one.org/a"
                                             :also [{:source "Two" :url "https://two.org/a"}])]
                     :cited [1] :markdown d :model "m" :provider "p"})]
    (try
      (doseq [d ["2026-09-28" "2026-09-29" "2026-09-30"]] (store/save-day! db (day d)))
      (is (= [{:day "2026-09-28" :n 1 :story "s1" :outlets 2 :cited? true}]
             (store/coverage-between db "2026-09-28" "2026-09-28")))
      (store/prune! db 1)
      (is (= ["2026-09-30"] (store/days db)) "the days go")
      (is (= 3 (count (store/coverage-between db "2026-01-01" "2026-12-31"))) "their coverage stays")
      (finally (store/close db)))))

(deftest notes-and-standfirsts-outlive-the-days
  (let [db (fresh)
        note {:title "The Fed's pause" :summary "Held." :first-day "2026-09-28" :last-day "2026-09-28"
              :facts [{:day "2026-09-28" :text "Held." :url "https://e.com/a" :source "Wire" :headline "A"}]}]
    (try
      (doseq [d ["2026-09-28" "2026-09-29"]]
        (store/save-day! db {:day d :sources [(assoc (first sources) :story "fed")] :cited []
                             :tldr (str "Day " d) :markdown d :model "m" :provider "p"}))
      (store/save-notes! db {"fed" note})
      (is (= {"fed" (assoc note :story "fed")} (store/notes db ["fed" "other"])))
      (is (= {} (store/notes db [])))
      (store/prune! db 1)
      (is (= ["2026-09-29"] (store/days db)))
      (is (= ["Day 2026-09-28" "Day 2026-09-29"] (map :tldr (store/standfirsts-between db "2026-09-01" "2026-09-30")))
          "the standfirsts stay with the coverage")
      (is (some? (get (store/notes db ["fed"]) "fed")) "and so do the notes")
      (finally (store/close db)))))

(deftest coverage-is-filled-in-for-an-older-database
  (let [f (str (System/getProperty "java.io.tmpdir") "/newsroom-coverage-" (System/currentTimeMillis) ".sqlite3")]
    (try
      (let [db (store/open f)]
        (store/save-day! db {:day "2026-09-30" :sources sources :cited [3] :markdown "x" :model "m" :provider "p"})
        ;; as a database from before coverage was kept
        (jdbc/execute! (:conn db) "delete from coverage")
        (store/close db))
      (let [db (store/open f)]
        (try
          (is (= [[1 false] [2 false] [3 true]]
                 (map (juxt :n :cited?) (store/coverage-between db "2026-09-30" "2026-09-30"))))
          (finally (store/close db))))
      (finally (io/delete-file f true)))))

(deftest a-digest-round-trips
  (let [db (fresh)]
    (try
      (store/save-digest! db {:kind :week :period "2026-W40"
                              :sources [{:n 1 :day "2026-09-29" :title "A" :url "https://e.com/a" :source "Wire"}
                                        {:n 2 :day "2026-09-30" :title "B" :url "https://e.com/b" :source "Web"}]
                              :cited [2] :markdown "# Week" :tldr "A week." :model "m" :provider "p"})
      (store/save-digest! db {:kind :month :period "2026-09" :sources [] :cited []
                              :markdown "# Month" :model "m" :provider "p"})
      (let [d (store/digest db :week "2026-W40")]
        (is (= "# Week" (:markdown d)))
        (is (= "A week." (:tldr d)))
        (is (= [false true] (map :cited? (:sources d))))
        (is (= "2026-09-30" (:day (second (:sources d))))))
      (is (nil? (store/digest db :week "2026-W39")))
      (is (= #{{:kind :week :period "2026-W40" :tldr "A week."} {:kind :month :period "2026-09" :tldr nil}}
             (set (store/digests db))))
      (testing "pruning the days leaves the digests"
        (store/prune! db 0)
        (is (some? (store/digest db :week "2026-W40"))))
      (finally (store/close db)))))

(deftest saving-a-day-again-replaces-it
  (let [db (fresh)]
    (try
      (store/save-day! db {:day "2026-09-30" :sources sources :cited [1 2 3]
                           :markdown "first" :model "m" :provider "p"})
      (store/save-day! db {:day "2026-09-30" :sources (take 1 sources) :cited [1]
                           :markdown "second" :model "m" :provider "p"})
      (let [day (store/day db "2026-09-30")]
        (is (= "second" (:markdown day)))
        (is (= 1 (count (:sources day))))
        (is (= [1] (map :n (:cited day)))))
      (finally (store/close db)))))

(deftest days-are-listed-newest-first
  (let [db (fresh)]
    (try
      (doseq [d ["2026-09-28" "2026-09-30" "2026-09-29"]]
        (store/save-day! db {:day d :sources [] :cited [] :markdown d :model "m" :provider "p"}))
      (is (= ["2026-09-30" "2026-09-29" "2026-09-28"] (store/days db)))
      (is (nil? (store/day db "2026-01-01")))
      (finally (store/close db)))))

(deftest pruning-keeps-the-newest-days
  (let [db (fresh)
        days ["2026-09-26" "2026-09-27" "2026-09-28" "2026-09-29" "2026-09-30"]]
    (try
      (doseq [d days]
        (store/save-day! db {:day d :sources sources :cited [1] :markdown d :model "m" :provider "p"}))
      (testing "unlimited keeps everything"
        (is (= [] (store/prune! db -1)))
        (is (= 5 (count (store/days db)))))
      (testing "a limit drops the oldest days, with their sources"
        (is (= ["2026-09-27" "2026-09-26"] (store/prune! db 3)))
        (is (= ["2026-09-30" "2026-09-29" "2026-09-28"] (store/days db)))
        (is (nil? (store/day db "2026-09-26")))
        (is (= 3 (count (:sources (store/day db "2026-09-28"))))))
      (testing "nothing more to drop"
        (is (= [] (store/prune! db 3))))
      (finally (store/close db)))))

(deftest the-desks-trends-are-kept-with-the-coverage
  (let [db (fresh)]
    (try
      (store/save-trends! db "2026-09-29" [{:thread "2026-09-29/T1" :name "Energy squeeze" :direction "holding"
                                            :summary "Oil up." :stories ["fed" "oil"]}])
      (store/save-trends! db "2026-09-30" [{:thread "2026-09-29/T1" :name "Energy squeeze" :direction "strengthening"
                                            :summary "Oil higher." :stories ["oil"]}
                                           {:thread "2026-09-30/T2" :name "Chip blocs" :stories []}])
      (is (= [{:day "2026-09-29" :thread "2026-09-29/T1" :name "Energy squeeze" :direction "holding"
               :summary "Oil up." :stories ["fed" "oil"]}]
             (store/trends-between db "2026-09-29" "2026-09-29")))
      (is (= ["2026-09-29/T1" "2026-09-29/T1" "2026-09-30/T2"]
             (map :thread (store/trends-between db "2026-09-01" "2026-09-30"))))
      (store/save-trends! db "2026-09-30" [])
      (is (= 1 (count (store/trends-between db "2026-09-01" "2026-09-30"))) "saving a day again replaces its trends")
      (store/save-notes! db {"fed" {:title "T" :summary "S" :status "escalating" :facts [] :first-day "2026-09-29"
                                    :last-day "2026-09-30"}})
      (is (= "escalating" (:status (get (store/notes db ["fed"]) "fed"))) "a note keeps its status")
      (finally (store/close db)))))

(deftest a-gap-searchs-result-is-kept-but-isnt-coverage
  (let [db (fresh)]
    (try
      (store/save-day! db {:day "2026-09-30"
                           :sources [(assoc (first sources) :story "fed")
                                     (assoc (second sources) :story "fed" :gap "The vote")]
                           :cited [1 2] :markdown "m" :model "m" :provider "p"})
      (is (= "The vote" (:gap (second (:sources (store/day db "2026-09-30"))))))
      (is (= [1] (map :n (store/coverage-between db "2026-09-30" "2026-09-30")))
          "a result found for a story isn't counted as an outlet carrying it")
      (is (= "The vote" (:gap (second (store/sources-between db "2026-09-30" "2026-09-30")))))
      (finally (store/close db)))))
