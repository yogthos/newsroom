(ns newsroom.notes-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [newsroom.notes :as notes]))

(defn- src [n story & {:keys [weight days] :or {weight 1 days 1}}]
  {:n n :story story :weight weight :days days :title (str "Report " n) :source (str "Outlet " n)
   :url (str "https://e.com/" n) :summary (str "What report " n " says.")})

(def today
  [(src 1 "fed" :weight 2.5 :days 3)
   (src 2 "fed")
   (src 3 "bread")
   (src 4 "chips" :weight 2)
   (src 5 "cited-one")])

(def fed-note
  {:story "fed" :title "The Fed's pause" :summary "The Fed has held rates for two meetings."
   :first-day "2026-09-27" :last-day "2026-09-29"
   :facts [{:day "2026-09-27" :text "The Fed held rates." :url "https://old.example.com/1"
            :source "Wire" :headline "Fed holds"}
           {:day "2026-09-29" :text "Powell hinted at a cut." :url "https://old.example.com/2"
            :source "Wire" :headline "Powell hints"}]})

(deftest the-storylines-that-mattered-get-notes
  (let [cs (notes/candidates today [5] #{"fed"})]
    (is (= ["fed" "cited-one"] (map first cs))
        "heaviest first: one already noted, one the briefing cited, not one it passed over")
    (is (= [1 2] (map :n (second (first cs)))) "with every report of the day on it"))
  (testing "what a gap search found isn't the day's reporting"
    (let [cs (notes/candidates (conj today (assoc (src 6 "fed") :gap "why now")) [5 6] #{"fed"})]
      (is (= [1 2] (map :n (second (first cs))))))))

(deftest the-prompt-names-each-fact-and-report
  (let [cs (notes/candidates today [4 5] #{"fed"})
        {:keys [prompt ids]} (notes/compaction-prompt "2026-09-30" cs {"fed" fed-note} 12)]
    (is (= #{"E1" "E2"} (set (keys ids))))
    (is (str/includes? prompt "### id: fed\nNotes so far:\nTitle: The Fed's pause"))
    (is (str/includes? prompt "E2 2026-09-29: Powell hinted at a cut. (Wire)"))
    (is (str/includes? prompt "### id: chips\nNo notes yet"))
    (is (str/includes? prompt "[4] Report 4 (Outlet 4)\nWhat report 4 says."))
    (is (str/includes? prompt "at most 12 facts"))))

(deftest an-answer-updates-the-notes
  (let [cs (notes/candidates today [4 5] #{"fed"})
        {:keys [ids]} (notes/compaction-prompt "2026-09-30" cs {"fed" fed-note} 12)
        answer (str "Here you go:\n```json\n"
                    "{\"storylines\": ["
                    "{\"id\": \"fed\", \"title\": \"The Fed's pause\", \"summary\": \"Held again, cut signalled.\","
                    " \"facts\": [{\"day\": \"2026-09-30\", \"fact\": \"The Fed held for a third time.\", \"cite\": \"[1]\"},"
                    "            {\"day\": \"2026-09-27\", \"fact\": \"The Fed first paused.\", \"cite\": \"E1\"},"
                    "            {\"day\": \"2026-09-30\", \"fact\": \"Unfounded.\", \"cite\": \"E9\"}]},"
                    "{\"id\": \"chips\", \"title\": \"Chip curbs\", \"summary\": \"New.\","
                    " \"facts\": [{\"day\": \"2026-09-30\", \"fact\": \"Curbs announced.\", \"cite\": 4}]},"
                    "{\"id\": \"cited-one\", \"title\": \"Nothing\", \"summary\": \"x\", \"facts\": []},"
                    "{\"id\": \"stranger\", \"title\": \"Not asked\", \"facts\": [{\"fact\": \"x\", \"cite\": 1}]}]}\n```")
        updated (notes/apply-answer "2026-09-30" cs {"fed" fed-note} ids answer 12)]
    (is (= #{"fed" "chips"} (set (keys updated))) "only the storylines asked about, and only with facts")
    (let [fed (updated "fed")]
      (is (= ["The Fed first paused." "The Fed held for a third time."] (map :text (:facts fed)))
          "in the order they happened, a fact with an unknown cite dropped")
      (is (= "https://old.example.com/1" (:url (first (:facts fed)))) "a kept fact keeps its report")
      (is (= {:day "2026-09-30" :url "https://e.com/1" :source "Outlet 1" :headline "Report 1"}
             (dissoc (second (:facts fed)) :text))
          "a new fact takes today's report")
      (is (= "2026-09-27" (:first-day fed)))
      (is (= "2026-09-30" (:last-day fed))))
    (is (= "2026-09-30" (:first-day (updated "chips"))) "a new storyline starts today")
    (testing "at most the given number of facts, the latest kept"
      (is (= ["The Fed held for a third time."]
             (map :text (:facts ((notes/apply-answer "2026-09-30" cs {"fed" fed-note} ids answer 1) "fed"))))))
    (testing "an answer with no JSON updates nothing"
      (is (= {} (notes/apply-answer "2026-09-30" cs {} ids "I can't do that." 12)))
      (is (nil? (notes/parse-answer "{not json}"))))))

(deftest the-briefing-hears-about-the-stories-still-running
  (let [bg (notes/background {"fed" fed-note} today identity)]
    (is (str/includes? bg "### The Fed's pause\nThe Fed has held rates for two meetings."))
    (is (str/includes? bg "- 2026-09-29: Powell hinted at a cut.")))
  (is (nil? (notes/background {"fed" fed-note} [(src 1 "fed")] identity))
      "a story new today has no running notes to give")
  (is (nil? (notes/background {} today identity))))

(deftest a-digest-cites-the-facts-of-its-period
  (is (= [{:day "2026-09-29" :title "Powell hints" :url "https://old.example.com/2"
           :source "Wire" :fact "Powell hinted at a cut."}]
         (notes/facts-between fed-note "2026-09-28" "2026-10-04"))))

(deftest a-note-keeps-where-the-story-stands
  (let [cs (notes/candidates today [] #{"fed"})
        {:keys [prompt ids]} (notes/compaction-prompt "2026-09-30" cs {"fed" (assoc fed-note :status "static")} 12
                                                      {"fed" [{:fact "Rates held at 4%." :cites [1 2]}]})
        answer (fn [status]
                 (str "{\"storylines\": [{\"id\": \"fed\", \"summary\": \"Held.\", \"status\": " status ","
                      " \"facts\": [{\"day\": \"2026-09-30\", \"fact\": \"Held at 4%.\", \"cite\": 1}]}]}"))]
    (is (str/includes? prompt "Status: static\n") "the note's status is shown")
    (is (str/includes? prompt "The desk's facts from today's reports:\n- Rates held at 4%. [1, 2]"))
    (is (= "escalating" (:status ((notes/apply-answer "2026-09-30" cs {"fed" fed-note} ids (answer "\"Escalating\"") 12) "fed"))))
    (is (= "static" (:status ((notes/apply-answer "2026-09-30" cs {"fed" (assoc fed-note :status "static")} ids
                                                  (answer "\"boiling\"") 12) "fed")))
        "a status that isn't one keeps the last")
    (is (str/includes? (notes/background {"fed" (assoc fed-note :status "escalating")} today identity)
                       "### The Fed's pause (escalating)\n"))))
