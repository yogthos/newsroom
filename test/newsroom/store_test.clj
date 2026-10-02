(ns newsroom.store-test
  (:require [clojure.test :refer [deftest is testing]]
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
                           :sources [(assoc (first sources) :also ["AP" "Reuters"])]
                           :cited [1] :tldr "Rates up, tech down"
                           :markdown "# The day\n\n> Rates up, tech down" :model "m" :provider "p"})
      (let [day (store/day db "2026-09-30")]
        (is (= "Rates up, tech down" (:tldr day)))
        (is (= ["AP" "Reuters"] (:also (first (:sources day))))))
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
          (is (nil? (:last-error (h "Dead feed"))))))
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
