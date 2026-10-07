(ns newsroom.search-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jdbc.core :as jdbc]
            [newsroom.search :as search]
            [newsroom.store :as store]))

;; --- the pure core -----------------------------------------------------------------

(deftest the-distance-between-two-words
  (is (= 0 (search/distance "tariff" "tariff")))
  (is (= 1 (search/distance "tariff" "tarif")) "a letter dropped")
  (is (= 1 (search/distance "tariff" "tairff")) "two letters swapped")
  (is (= 2 (search/distance "tarrifs" "tariffs")))
  (is (= 6 (search/distance "" "tariff"))))

(deftest a-query-is-split-into-words-and-phrases
  (is (= [{:word "federal"} {:word "reserve"}] (search/query-terms "Federal  Reserve")))
  (is (= [{:phrase ["interest" "rates"]} {:word "fed"}] (search/query-terms "\"interest rates\" fed")))
  (testing "what the index's own syntax would read is just text"
    (is (= [{:word "and"} {:word "near"} {:word "x"}] (search/query-terms "AND (NEAR x*) -")))
    (is (= [] (search/query-terms "  \" ( ) * ")))
    (is (= [{:word "café"}] (search/query-terms "Café")))))

(deftest near-words-are-found-within-a-few-letters
  (let [vocab {"tariffs" 12 "tariff" 30 "traffic" 4 "terrific" 1 "bond" 9 "bonds" 20}]
    (is (= ["tariffs" "tariff"] (search/near "tarrifs" vocab)) "the closest first, then the commonest")
    (is (= [] (search/near "bnd" vocab)) "short words aren't guessed at")
    (is (= ["bonds"] (search/near "bomds" vocab)) "bond is two letters off, too far for a short word")
    (is (= [] (search/near "tariffs" vocab)) "a word the index has needs no guess")
    (testing "a word two letters off must start the same"
      (is (= ["tariffs"] (search/near "tarrifs" {"tariffs" 5 "carries" 5}))))
    (testing "a word far rarer than the best guess isn't guessed"
      (is (= ["china"] (search/near "chna" {"china" 120 "chan" 2}))))))

(deftest the-match-expression-quotes-every-word
  (is (= "(\"fed\"*) AND (\"interest\" + \"rates\")"
         (search/match-expr [{:word "fed"} {:phrase ["interest" "rates"]}] {})))
  (is (= "(\"tarrifs\"* OR \"tariffs\" OR \"tariff\") AND (\"china\"*)"
         (search/match-expr [{:word "tarrifs"} {:word "china"}] {"tarrifs" ["tariffs" "tariff"]})))
  (is (= "(\"say \"\"hi\"\"\"*)" (search/match-expr [{:word "say \"hi\""}] {}))))

;; --- searching the store -----------------------------------------------------------

(defn- fresh [] (store/open "sqlite::memory:"))

(def briefing
  (str "# Tariffs bite\n\n> Washington raised tariffs on steel.\n\n"
       "## Economics\n\nThe tariffs [[1]](https://e.com/1) hit Shenzhen exporters.\n\n"
       "```mermaid\ngraph LR; zebrafish-->x\n```\n\n"
       "## Sources\n\n- [1] [Steel duties rise](https://e.com/1) — Wire\n"))

(defn- seed! [db]
  (store/save-day! db {:day "2026-09-30"
                       :sources [{:n 1 :title "Steel duties rise" :url "https://e.com/1" :source "Wire"
                                  :summary "The levy doubles on Canadian steel." :published nil}
                                 {:n 2 :title "Bakery wins prize" :url "https://e.com/2" :source "Local"
                                  :summary "A family bakery won the county fair." :published nil}]
                       :cited [1] :markdown briefing :model "m" :provider "p"}))

(defn- hits [db q & [opts]] (:results (search/search db q (or opts {}))))

(deftest a-briefing-and-its-sources-can-be-searched
  (let [db (fresh)]
    (try
      (seed! db)
      (testing "the briefing is found, by a stem of the word"
        (let [[r] (filter #(= :day (:kind %)) (hits db "tariff"))]
          (is (= "/day/2026-09-30" (:href r)))
          (is (= "2026-09-30" (:day r)))
          (is (= "Tariffs bite" (:title r)) "a briefing goes by its headline")
          (is (some (fn [[text mark?]] (and mark? (= "tariffs" (str/lower-case text)))) (:snippet r))
              "the snippet marks what matched")))
      (testing "a source is found by its summary, linked to the day it was gathered"
        (let [[r] (hits db "canadian")]
          (is (= :source (:kind r)))
          (is (= "Steel duties rise" (:title r)))
          (is (= "/day/2026-09-30#source-1" (:href r)))
          (is (= "https://e.com/1" (:url r)))
          (is (:cited? r))))
      (testing "the diagram and the Sources list aren't searched"
        (is (empty? (hits db "zebrafish"))))
      (testing "every word must match"
        (is (= 1 (count (hits db "bakery county"))))
        (is (empty? (hits db "bakery steel"))))
      (testing "a phrase matches only in order"
        (is (seq (hits db "\"family bakery\"")))
        (is (empty? (hits db "\"bakery family\""))))
      (testing "a kind narrows the results"
        (is (= #{:source} (set (map :kind (hits db "steel" {:kind :source})))))
        (is (= #{:day} (set (map :kind (hits db "steel" {:kind :day}))))))
      (testing "a query that is only syntax finds nothing, and doesn't throw"
        (is (= [] (hits db "\"(* AND")))
        (is (= [] (hits db ""))))
      (finally (store/close db)))))

(deftest a-misspelled-word-finds-what-it-meant
  (let [db (fresh)]
    (try
      (seed! db)
      (let [{:keys [results corrections]} (search/search db "tarrifs shenzen" {})]
        (is (= #{"tarrifs" "shenzen"} (set (keys corrections))))
        (is (some #{"tariffs"} (corrections "tarrifs")))
        (is (= ["/day/2026-09-30"] (map :href results))))
      (testing "the last word matches as a prefix, as it is typed"
        (is (seq (hits db "bake"))))
      (finally (store/close db)))))

(deftest saving-again-replaces-what-is-searched
  (let [db (fresh)]
    (try
      (seed! db)
      (store/save-day! db {:day "2026-09-30" :sources [] :cited [] :markdown "# Quiet\n\nNothing happened."
                           :model "m" :provider "p"})
      (is (empty? (hits db "tariffs")))
      (is (= 1 (count (hits db "quiet"))))
      (finally (store/close db)))))

(deftest digests-and-storyline-facts-are-searched
  (let [db (fresh)]
    (try
      (store/save-digest! db {:kind :week :period "2026-W40" :sources [] :cited []
                              :markdown "# The week\n\nRates held across the week." :model "m" :provider "p"})
      (store/save-notes! db {"2026-09-28/1" {:title "The Fed's long pause" :summary "Held all week."
                                             :first-day "2026-09-28" :last-day "2026-10-01"
                                             :facts [{:day "2026-09-29" :text "The Fed held at 4.25%."
                                                      :url "https://e.com/f" :source "Wire" :headline "Fed holds"}]}})
      (let [[r] (hits db "across" {:kind :digest})]
        (is (= "/week/2026-W40" (:href r))))
      (let [[r] (hits db "held" {:kind :fact})]
        (is (= "/story/2026-09-28/1" (:href r)))
        (is (= "The Fed's long pause" (:title r)))
        (is (= "2026-09-29" (:day r)))
        (is (= "https://e.com/f" (:url r))))
      (testing "a storyline is found by its title"
        (is (= ["/story/2026-09-28/1"] (map :href (hits db "pause" {:kind :story})))))
      (testing "a note saved again replaces its facts"
        (store/save-notes! db {"2026-09-28/1" {:title "The Fed's long pause" :summary "Cut at last."
                                               :first-day "2026-09-28" :last-day "2026-10-02"
                                               :facts [{:day "2026-10-02" :text "The Fed cut."}]}})
        (is (empty? (hits db "4.25")))
        (is (= 1 (count (hits db "cut" {:kind :fact})))))
      (finally (store/close db)))))

(deftest pruning-drops-the-days-from-the-index
  (let [db (fresh)]
    (try
      (doseq [d ["2026-09-28" "2026-09-29" "2026-09-30"]]
        (store/save-day! db {:day d :sources [{:n 1 :title (str "Report " d) :url "https://e.com" :source "W"
                                               :summary "walrus" :published nil}]
                             :cited [] :markdown (str "# " d "\n\nwalrus") :model "m" :provider "p"}))
      (store/prune! db 1)
      (is (= #{"2026-09-30"} (set (map :day (hits db "walrus")))))
      (finally (store/close db)))))

(deftest a-database-from-before-search-is-indexed-when-opened
  (let [f (io/file (System/getProperty "java.io.tmpdir") (str "newsroom-search-" (System/currentTimeMillis) ".db"))
        path (.getPath f)]
    (try
      (let [db (store/open path)]
        (seed! db)
        (doseq [t ["search_docs" "search_fts" "search_words" "search_vocab"]]
          (jdbc/execute! (:conn db) (str "drop table if exists " t)))
        (store/close db))
      (let [db (store/open path)]
        (try
          (is (seq (hits db "tariffs")))
          (is (= 2 (count (hits db "steel"))) "the briefing and the source, once each")
          (finally (store/close db))))
      (finally (.delete f)))))
