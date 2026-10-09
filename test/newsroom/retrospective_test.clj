(ns newsroom.retrospective-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [newsroom.retrospective :as retro]))

(defn- long-date [day] (str "the " day))

(deftest subjects-are-read-loosely
  (is (= "economy" (retro/subject-of "Economy")))
  (is (= "markets" (retro/subject-of "bond markets")))
  (is (= "conflict" (retro/subject-of "the war in Sudan")))
  (is (= "technology" (retro/subject-of "AI chips")))
  (is (= "domestic politics" (retro/subject-of "domestic politics")))
  (is (= "geopolitics" (retro/subject-of "something else")) "what fits nothing is geopolitics")
  (is (= "geopolitics" (retro/subject-of nil))))

(deftest a-projection-falls-due-within-bounds
  (is (= "2026-10-30" (retro/due-of "2026-09-30" "2026-10-30")))
  (is (= "2026-10-30" (retro/due-of "2026-09-30" nil)) "a month when it says nothing")
  (is (= "2026-10-30" (retro/due-of "2026-09-30" "next spring")) "or nothing that reads as a day")
  (is (= "2026-10-03" (retro/due-of "2026-09-30" "2026-09-30")) "a few days at the least")
  (is (= "2027-03-29" (retro/due-of "2026-09-30" "2030-01-01")) "half a year at the most"))

;; --- newsroom's own projections ----------------------------------------------------

(def sources
  [{:n 1 :title "Fed holds" :source "Wire" :url "https://wire.org/1" :story "2026-09-28/1"}
   {:n 2 :title "Chips" :source "Tech" :url "https://tech.org/2" :story "2026-09-30/2"}
   {:n 3 :title "The 1973 shock" :source "Precedent search" :url "https://p.org/3" :precedent true}])

(deftest the-extraction-prompt-shows-the-text
  (let [p (retro/extraction-prompt "the briefing for 30 September 2026" "2026-09-30"
                                   "# Day\n\n## Outlook\n\nThe Fed cuts by December [1].")]
    (is (str/includes? p "the briefing for 30 September 2026"))
    (is (str/includes? p "The Fed cuts by December [1]."))
    (is (str/includes? p "\"projections\""))
    (doseq [s retro/subjects] (is (str/includes? p s)))))

(deftest projections-are-read-from-the-answer
  (let [answer (str "```json\n{\"projections\": ["
                    "{\"claim\": \"The Fed cuts rates by December.\", \"subject\": \"Economy\", "
                    "\"by\": \"2026-12-31\", \"cites\": [3, 1]},"
                    "{\"claim\": \"Chip exports fall.\", \"subject\": \"tech\", \"by\": \"soon\", \"cites\": \"[2]\"},"
                    "{\"claim\": \"\", \"subject\": \"economy\"},"
                    "{\"claim\": \"An untied claim.\", \"subject\": \"geopolitics\", \"by\": \"2026-11-01\"}]}\n```")
        ps (retro/parse-projections answer "2026-09-30" sources)]
    (is (= ["The Fed cuts rates by December." "Chip exports fall." "An untied claim."] (map :claim ps))
        "a projection with no claim is dropped")
    (is (= ["economy" "technology" "geopolitics"] (map :subject ps)))
    (is (= ["2026-12-31" "2026-10-30" "2026-11-01"] (map :due ps)))
    (is (= ["2026-09-28/1" "2026-09-30/2" nil] (map :story ps))
        "the storyline of the first of today's sources it cites, past the precedents")
    (is (every? #(= "2026-09-30" (:made-on %)) ps)))
  (is (= [] (retro/parse-projections "{\"projections\": []}" "2026-09-30" sources)))
  (is (nil? (retro/parse-projections "No projections here." "2026-09-30" sources)))
  (testing "at most a handful are kept"
    (is (= retro/max-own (count (retro/parse-projections
                                 (str "{\"projections\": ["
                                      (str/join ", " (repeat 20 "{\"claim\": \"x\", \"subject\": \"economy\"}"))
                                      "]}")
                                 "2026-09-30" sources))))))

;; --- what the outlets expect -------------------------------------------------------

(deftest the-outlets-expectations-become-their-projections
  (let [stories [{:key "G1" :story "2026-09-28/1" :sources [(first sources)]}
                 {:key "G2" :story "2026-09-30/2" :sources [(second sources)]}]
        dossiers {"G1" {:expectations [{:claim "Rates will be cut in December." :subject "economy"
                                        :due "2026-12-31" :by "Wire's economists" :cites [1]
                                        :source "Wire" :outlet "wire.org" :url "https://wire.org/1"}]}
                  "G2" {:summary "No expectations."}}
        ps (retro/outlet-projections stories dossiers "2026-09-30")]
    (is (= [{:made-on "2026-09-30" :claim "Rates will be cut in December." :subject "economy"
             :due "2026-12-31" :by "Wire's economists" :story "2026-09-28/1"
             :source "Wire" :outlet "wire.org" :url "https://wire.org/1"}]
           ps)))
  (testing "a day records a bounded number, the main stories' first"
    (let [many (vec (for [i (range 30)] {:key (str "G" i) :story (str "s" i)}))
          ds (into {} (for [i (range 30)]
                        [(str "G" i) {:expectations [{:claim (str "Claim " i) :subject "economy" :source "Wire"
                                                      :outlet "wire.org"}]}]))
          ps (retro/outlet-projections many ds "2026-09-30")]
      (is (= retro/max-outlet (count ps)))
      (is (= "Claim 0" (:claim (first ps)))))))

;; --- judging -----------------------------------------------------------------------

(def ^:private due-rows
  [{:id 7 :made-on "2026-09-01" :claim "The Fed cuts by October." :subject "economy" :due "2026-10-01"
    :story "fed" :source nil}
   {:id 9 :made-on "2026-09-10" :claim "Chip exports fall." :subject "technology" :due "2026-10-02"
    :story nil :source "Tech" :by "Tech's analysts"}])

(deftest the-judge-is-shown-each-projection-and-what-happened-since
  (let [p (retro/judge-prompt "4 October 2026" (retro/number-projections due-rows)
                              {"fed" {:title "The Fed's pause"
                                      :facts [{:day "2026-08-30" :text "Before it was made."}
                                              {:day "2026-09-20" :text "The Fed held again."}]}}
                              "## The week\n\nThe Fed held." long-date)]
    (is (str/includes? p "P1, made the 2026-09-01 by newsroom, due the 2026-10-01 (economy): The Fed cuts by October."))
    (is (str/includes? p "P2, made the 2026-09-10 by Tech (Tech's analysts), due the 2026-10-02 (technology)"))
    (is (str/includes? p "Since then on its storyline, The Fed's pause:\n- the 2026-09-20: The Fed held again."))
    (is (not (str/includes? p "Before it was made.")) "only what happened after it was made")
    (is (str/includes? p "The Fed held."))
    (is (str/includes? p "\"verdicts\""))))

(deftest verdicts-are-read-strictly
  (let [answer (str "{\"verdicts\": ["
                    "{\"id\": \"P1\", \"verdict\": \"Failed\", \"closeness\": 0.2, \"reason\": \"It held instead.\"},"
                    "{\"id\": \"p2\", \"verdict\": \"partly\", \"closeness\": 60, \"reason\": \"Fell, but less.\"},"
                    "{\"id\": \"P3\", \"verdict\": \"held\", \"reason\": \"Not one of ours.\"},"
                    "{\"id\": \"P1\", \"verdict\": \"held\", \"reason\": \"A second answer for P1.\"}]}")
        vs (retro/parse-verdicts answer #{"P1" "P2"})]
    (is (= {"P1" {:verdict "failed" :closeness 0.2 :reason "It held instead."}
            "P2" {:verdict "partly" :closeness 0.6 :reason "Fell, but less."}}
           vs)
        "a percentage is read as a share, an unknown id is dropped, the first answer stands"))
  (testing "a verdict with no closeness gets its verdict's"
    (is (= {:verdict "held" :closeness 1.0 :reason nil}
           (get (retro/parse-verdicts "{\"verdicts\": [{\"id\": \"P1\", \"verdict\": \"held\"}]}" #{"P1"}) "P1"))))
  (testing "an unknown verdict leaves the projection alone"
    (is (= {} (retro/parse-verdicts "{\"verdicts\": [{\"id\": \"P1\", \"verdict\": \"maybe\"}]}" #{"P1"}))))
  (is (nil? (retro/parse-verdicts "I can't tell." #{"P1"}))))

(deftest a-verdict-settles-a-projection-or-gives-it-longer
  (let [numbered (retro/number-projections
                  (conj due-rows {:id 11 :made-on "2026-01-02" :claim "Old." :subject "economy" :due "2026-09-01"}))
        out (retro/apply-verdicts numbered
                                  {"P1" {:verdict "failed" :closeness 0.1 :reason "It held."}
                                   "P2" {:verdict "open" :closeness nil :reason "Too early."}
                                   "P3" {:verdict "open" :closeness nil :reason "Still nothing."}}
                                  {:kind :week :period "2026-W40" :to "2026-10-04"})
        by-id (into {} (map (juxt :id identity)) out)]
    (is (= {:id 7 :status "failed" :closeness 0.1 :reason "It held." :checked-kind "week"
            :checked-period "2026-W40" :due "2026-10-01"}
           (select-keys (by-id 7) [:id :status :closeness :reason :checked-kind :checked-period :due])))
    (is (= {:status "open" :due "2026-10-11" :reason "Too early."}
           (select-keys (by-id 9) [:status :due :reason]))
        "an open one is given another period")
    (is (= "unresolved" (:status (by-id 11))) "one open for far too long is given up on")
    (is (= 3 (count out)))
    (is (not-any? :pid out))))

;; --- the lessons -------------------------------------------------------------------

(deftest the-lessons-are-kept-and-rewritten
  (let [p (retro/lessons-prompt "the week of 28 September 2026"
                                [{:claim "The Fed cuts by October." :subject "economy" :status "failed"
                                  :closeness 0.1 :reason "It held." :source nil}
                                 {:claim "Chips fall." :subject "technology" :status "held"
                                  :closeness 1.0 :reason "They did." :source "Tech"}]
                                [{:subject "economy" :lesson "Central banks move slower than markets price."}])]
    (is (str/includes? p "- (economy, newsroom) failed, closeness 0.1: The Fed cuts by October. It held."))
    (is (str/includes? p "- (technology, Tech) held, closeness 1.0: Chips fall. They did."))
    (is (str/includes? p "- (economy) Central banks move slower than markets price.")))
  (is (= {:summary "Two of three held."
          :lessons [{:subject "economy" :lesson "Don't price cuts early."}]}
         (retro/parse-lessons (str "{\"summary\": \"Two of three held.\", \"lessons\": ["
                                   "{\"subject\": \"Economy\", \"lesson\": \"Don't price cuts early.\"},"
                                   "{\"subject\": \"economy\"}]}"))))
  (is (nil? (retro/parse-lessons "nothing"))))

;; --- the standings -----------------------------------------------------------------

(def ^:private judged
  (concat
   ;; Wire is reliable on technology but not on geopolitics
   (for [_ (range 4)] {:source "Wire" :outlet "wire.org" :subject "technology" :status "held" :closeness 1.0})
   (for [_ (range 4)] {:source "Wire" :outlet "wire.org" :subject "geopolitics" :status "failed" :closeness 0.0})
   [{:source "Paper" :outlet "paper.org" :subject "geopolitics" :status "held" :closeness 0.9}
    {:source "Paper" :outlet "paper.org" :subject "geopolitics" :status "partly" :closeness 0.5}
    {:source "Paper copy" :outlet "paper.org" :subject "geopolitics" :status "held" :closeness 1.0}
    {:source nil :outlet nil :subject "geopolitics" :status "partly" :closeness 0.6}
    {:source "AP" :outlet "AP" :subject "technology" :status "held" :closeness 1.0}
    {:source "Lucky" :outlet "lucky.org" :subject "technology" :status "held" :closeness 1.0}
    {:source "Wire" :outlet "wire.org" :subject "economy" :status "open" :closeness nil}]))

(deftest sources-are-ranked-within-each-subject
  (let [st (retro/standings judged)]
    (is (= ["geopolitics" "technology"] (sort (keys st))) "an open projection counts for nothing yet")
    (testing "a source's record on one subject says nothing about another"
      (is (= "wire.org" (:outlet (first (st "technology")))))
      (is (= "wire.org" (:outlet (last (st "geopolitics"))))))
    (testing "a record counts its verdicts and is scored by closeness, held to the middle until it's long"
      (let [wire (first (st "technology"))]
        (is (= {:name "Wire" :judged 4 :held 4 :partly 0 :failed 0 :closeness 1.0}
               (select-keys wire [:name :judged :held :partly :failed :closeness])))
        (is (< 0.8 (:score wire) 1.0)))
      (is (> (:score (first (st "technology"))) (:score (second (st "technology"))))
          "four calls right outrank one"))
    (testing "an outlet's feeds are one record, named by the name it goes by most"
      (let [paper (first (st "geopolitics"))]
        (is (= "Paper" (:name paper)))
        (is (= 3 (:judged paper)))))
    (testing "newsroom's own projections are ranked with the rest"
      (is (some #(and (nil? (:outlet %)) (= "Newsroom" (:name %))) (st "geopolitics"))))))

(deftest a-source-that-reads-many-outlets-doesnt-name-them
  (let [st (retro/standings [{:source "Web search" :outlet "npr.org" :subject "conflict" :status "held" :closeness 1.0}
                             {:source "Web search" :outlet "dw.com" :subject "conflict" :status "held" :closeness 1.0}
                             {:source "Al Jazeera" :outlet "aljazeera.com" :subject "conflict" :status "held" :closeness 1.0}])]
    (is (= #{"npr.org" "dw.com" "Al Jazeera"} (set (map :name (st "conflict"))))
        "a name several outlets share gives way to the outlet's own address")))

(deftest the-record-block-tells-the-analyst-how-things-have-gone
  (let [st (retro/standings judged)
        block (retro/record-block {:kind :week :period "2026-W40" :label "the week of 28 September 2026"
                                   :lessons [{:subject "geopolitics" :lesson "Sanctions bite slower than announced."}]}
                                  st
                                  #{"wire.org" "paper.org"})]
    (is (str/starts-with? block "## The track record"))
    (is (str/includes? block "the week of 28 September 2026"))
    (is (str/includes? block "- (geopolitics) Sanctions bite slower than announced."))
    (is (str/includes? block "- geopolitics: 1 judged, 0 held, 1 partly, 0 failed, closeness 0.6")
        "newsroom's own record by subject")
    (is (str/includes? block "Wire: 4 judged, 4 held, 0 partly, 0 failed, closeness 1.0"))
    (is (not (str/includes? block "Lucky")) "only the outlets in today's sources")
    (testing "nothing to tell, no block"
      (is (nil? (retro/record-block nil {} #{}))))))

(def ^:private still-open
  [{:made-on "2026-09-28" :claim "Oil climbs past 100." :subject "energy" :due "2026-12-31" :story "oil"}
   {:made-on "2026-09-30" :claim "The Fed cuts by December." :subject "economy" :due "2026-12-15" :story "fed"}
   {:made-on "2026-09-29" :claim "Chip exports fall." :subject "technology" :due "2026-10-20" :story "chips"}])

(deftest the-forecasts-today-bears-on-come-first
  (is (= ["The Fed cuts by December." "Chip exports fall." "Oil climbs past 100."]
         (map :claim (retro/forecasts-shown still-open #{"fed"})))
      "today's storylines first, then the soonest due")
  (is (= 2 (count (retro/forecasts-shown (concat still-open still-open still-open still-open still-open) #{}
                                         2)))))

(deftest the-record-block-lists-the-forecasts-still-open
  (let [block (retro/record-block nil {} #{} still-open)]
    (is (str/starts-with? block "## The track record"))
    (is (str/includes? block "- (energy, made 2026-09-28, due 2026-12-31) Oil climbs past 100."))
    (is (str/includes? block "say how today moves"))
    (is (= (retro/record-block nil {} #{}) (retro/record-block nil {} #{} [])))))

(deftest an-outlets-record-on-a-subject-is-looked-up
  (let [rec (retro/record-of (retro/standings judged))]
    (is (= 4 (:held (rec ["wire.org" "technology"]))))
    (is (= 4 (:failed (rec ["wire.org" "geopolitics"]))))
    (is (nil? (rec ["wire.org" "economy"])))))
