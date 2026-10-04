(ns newsroom.precedents-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [newsroom.config :as config]
            [newsroom.precedents :as precedents]
            [newsroom.sources :as sources]
            [newsroom.template :as template]))

(def ^:private stories
  [{:n 1 :title "Fed holds rates" :source "Reuters" :url "https://reuters.com/fed" :summary "The Fed held."
    :story "2026-09-24/3" :days 5 :also [{:source "AP"}]}
   {:n 2 :title "EU raises steel tariffs" :source "FT" :url "https://ft.com/steel" :story "2026-09-30/2"}])

(def ^:private notes
  {"2026-09-24/3" {:summary "Rates have stayed high as inflation sticks." :first-day "2026-09-24"}})

(defn- render [template]
  (precedents/render-prompt template "30 September 2026" stories notes 12 sources/long-date))

(deftest the-prompt-shows-the-day-and-the-budget
  (let [p (render "On {{date}}, {{searches}} searches in {{rounds}} rounds.\n\n{{stories}}")]
    (is (str/starts-with? p (str "On 30 September 2026, 12 searches in " precedents/max-rounds " rounds.")))
    (is (str/includes? p "[2] EU raises steel tariffs (FT)")))
  (testing "it sees which stories are trends, and what the trend is"
    (is (str/includes? (render "{{stories}}")
                       (str "[1] Fed holds rates (Reuters)\nCoverage: 2 outlets today, in the news on 5 days\n"
                            "The Fed held.\nThe storyline so far, since 24 September 2026: "
                            "Rates have stayed high as inflation sticks."))))
  (testing "a template with no place for the stories gets them at the end"
    (is (str/ends-with? (render "Look.") "[2] EU raises steel tariffs (FT)")))
  (testing "the default is a template that asks for both answers"
    (let [t (config/default-text "precedents.md")]
      (is (nil? (template/error t)))
      (is (str/includes? t "{\"queries\""))
      (is (str/includes? t "{\"precedents\"")))))

(deftest the-answer-is-queries-or-picks
  (is (= {:queries ["a" "b"]} (precedents/parse-answer "```json\n{\"queries\": [\" a \", \"b\", \"a\", \"\"]}\n```")))
  (is (= {:picks [{:id "R4" :stories [2 3] :note "It took a year."}]}
         (precedents/parse-answer "{\"precedents\": [{\"id\": \"r4\", \"stories\": [2, \"[3]\"], \"note\": \"It took a year.\"}]}")))
  (is (= {:picks [{:id "R1" :stories [5] :note ""}]}
         (precedents/parse-answer "{\"precedents\": [{\"id\": \"R1\", \"story\": 5}, {\"note\": \"no id\"}]}"))
      "one story will do, and a pick without an id is dropped")
  (is (= {:picks []} (precedents/parse-answer "{\"queries\": [\"a\"], \"precedents\": []}"))
      "picking ends the research, even with queries alongside")
  (is (nil? (precedents/parse-answer "Nothing here.")))
  (is (nil? (precedents/parse-answer "{\"other\": 1}"))))

(deftest results-are-new-and-numbered-across-rounds
  (let [shown [{:id "R1" :title "Steel safeguards of 2018 and what followed them" :url "https://a.com/1"}]
        found [{:title "EU raises steel tariffs" :url "https://ft.com/steel" :vector [1.0]}
               {:title "Steel safeguards of 2018 and what followed them" :url "https://a.com/1"}
               {:title "The 2002 US steel tariffs and their repeal" :url "https://b.com/2" :vector [1.0]}
               {:title "The 2002 US steel tariffs and their repeal" :url "https://b.com/2"}]
        fresh (precedents/new-results found stories shown)]
    (is (= [{:title "The 2002 US steel tariffs and their repeal" :url "https://b.com/2" :id "R2"}] fresh)
        "today's story, one already shown and a second copy are left out")
    (is (str/includes? (precedents/results-message fresh 3 2) "R2. The 2002 US steel tariffs"))
    (is (str/includes? (precedents/results-message fresh 3 2) "3 searches left over 2 rounds"))
    (is (str/includes? (precedents/results-message [] 0 2) "found nothing new"))
    (is (str/includes? (precedents/results-message [] 0 2) "Pick now"))))

(deftest picks-become-precedents
  (let [results [{:id "R1" :title "A" :url "https://a.com/1" :source "Precedent search"}
                 {:id "R2" :title "B" :url "https://b.com/2" :source "Precedent search"}]
        found (precedents/picked [{:id "R2" :stories [2 9] :note "It took a year."}
                                  {:id "R9" :stories [1] :note "not shown"}
                                  {:id "R2" :stories [1] :note "again"}
                                  {:id "R1" :stories [] :note ""}]
                                 results [1 2])]
    (is (= [{:title "B" :url "https://b.com/2" :source "Precedent search"
             :precedent true :informs [2] :note "It took a year."}
            {:title "A" :url "https://a.com/1" :source "Precedent search" :precedent true}]
           found))))
