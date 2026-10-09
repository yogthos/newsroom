(ns newsroom.analysis-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [newsroom.analysis :as analysis]
            [newsroom.feed :as feed]
            [newsroom.trends :as trends]))

(defn- item [i & {:as more}]
  (merge {:title (str "Report " i) :url (str "https://o" i ".com/" i) :source (str "Outlet " i)
          :summary (str "What report " i " says.")}
         more))

(def five (mapv item (range 1 6)))

;; --- sorting -----------------------------------------------------------------------

(deftest the-sorting-prompt-numbers-the-reports
  (let [p (analysis/grouping-prompt "30 September 2026" [(item 1 :story "fed") (item 2)]
                                    {"fed" {:title "The Fed's pause"}})]
    (is (str/includes? p "[1] Report 1 (Outlet 1)\n    What report 1 says.\n    Continues the storyline: The Fed's pause"))
    (is (str/includes? p "[2] Report 2 (Outlet 2)"))
    (is (str/includes? p "\"junk\""))))

(deftest a-sorting-answer-is-read-strictly
  (let [answer "Here: ```json\n{\"stories\": [{\"title\": \"Fed holds\", \"reports\": [1, \"3\", 9],
                \"importance\": 14, \"status\": \"Escalating\"},
               {\"title\": \"Again\", \"reports\": [3, 2], \"importance\": \"4\", \"status\": \"boiling\"}],
                \"junk\": [2, 5]}```"
        {:keys [groups junk]} (analysis/parse-grouping answer 5)]
    (is (= [{:title "Fed holds" :members [1 3] :importance 10 :status "escalating"}
            {:title "Again" :members [2] :importance 4 :status nil}]
           groups)
        "a report out of range is dropped, one already placed stays where it was first named")
    (is (= #{5} junk) "a report put in a story isn't junk"))
  (is (nil? (analysis/parse-grouping "no idea" 5)))
  (testing "an answer that sorts nothing into a story is no sorting"
    (is (nil? (analysis/parse-grouping "{\"stories\": [], \"junk\": [1, 2]}" 2))))
  (testing "a number too big to mean anything is dropped, not thrown on"
    (is (= [{:title "Big" :members [1] :importance nil :status nil}]
           (:groups (analysis/parse-grouping
                     "{\"stories\": [{\"title\": \"Big\", \"reports\": [1, 12345678901234567890, \"99999999999999999999\"],
                       \"importance\": 1e300}]}" 2))))))

(deftest grouping-sorts-the-items-into-stories
  (let [items [(item 1 :story "fed") (item 2) (item 3 :story "old") (item 4 :story "fed") (item 5)]
        grouped (analysis/apply-grouping items {:groups [{:title "The Fed" :members [2 1 4] :importance 8
                                                          :status "escalating"}
                                                         {:title "Junk only" :members [5]}]
                                                :junk #{5}})]
    (is (= [2 1 4 3] (map #(parse-long (subs (:title %) 7)) grouped))
        "junk is left out, and an item no story names is a story of its own")
    (is (= ["G1" "G1" "G1" "G2"] (map :group grouped)))
    (is (= ["fed" "fed" "fed" "old"] (map :story grouped))
        "a story's reports share the storyline most of them continue")
    (is (= "The Fed" (:group-title (first grouped))))
    (is (= "Report 3" (:group-title (last grouped))) "a story of its own is titled by its report")
    (is (= [8 8 8 nil] (map :importance grouped)))))

(deftest a-new-story-starts-one-storyline
  (let [sources [{:n 1 :group "G1"} {:n 2 :group "G2" :story "fed"} {:n 3 :group "G1"} {:n 4 :group "G2"} {:n 5}]]
    (is (= ["2026-09-30/1" "fed" "2026-09-30/1" "fed" "2026-09-30/5"]
           (map :story (analysis/story-ids sources "2026-09-30"))))))

(deftest the-stories-of-the-day
  (let [ss (analysis/stories [{:n 1 :group "G1" :group-title "Fed" :status "static" :story "fed"}
                              {:n 2 :group "G1"} {:n 3 :title "Alone"}])]
    (is (= ["G1" "S3"] (map :key ss)))
    (is (= ["Fed" "Alone"] (map :title ss)))
    (is (= [[1 2] [3]] (map #(map :n (:sources %)) ss)))))

(deftest stories-are-ranked-whole
  (let [g (fn [i group & {:as more}] (merge (item i :group group) more))
        ranked (trends/rank-groups [(g 1 "G1") (g 2 "G2" :importance 4) (g 3 "G2" :importance 4)
                                    (g 4 "G3" :importance 10)]
                                   [] "2026-09-30" 2)]
    (is (= [4 2 3 1] (map #(parse-long (subs (:title %) 7)) ranked))
        "two outlets beat one, and an importance of 10 doubles a story's weight where 4 cuts it")
    (is (= [1.0 2.0 2.0 1.0] (map :weight ranked)))
    (is (= [1 2 2 1] (map :group-outlets ranked)))))

;; --- dossiers ----------------------------------------------------------------------

(def story
  {:key "G1" :title "The Fed holds" :status "static" :story "fed"
   :sources [(assoc (item 1) :n 1 :text "The full text.") (assoc (item 2) :n 2)
             (assoc (item 3) :n 3 :source "Outlet 1")]})

(deftest one-report-an-outlet-is-read
  (is (= [1 2] (map :n (analysis/readers story 5))))
  (is (= [1] (map :n (analysis/readers story 1)))))

(deftest the-dossier-prompt-shows-the-reports
  (let [p (analysis/dossier-prompt "30 September 2026" [story]
                                   {"fed" {:summary "Held twice." :facts [{:day "2026-09-29" :text "Held."}]}}
                                   identity)]
    (is (str/includes? p "### G1: The Fed holds\nStatus as sorted: static"))
    (is (str/includes? p "background, not a source):\nHeld twice.\n- 2026-09-29: Held."))
    (is (str/includes? p "[1] Report 1 (Outlet 1)\nFull text:\nThe full text."))
    (is (str/includes? p "[2] Report 2 (Outlet 2)\nSummary: What report 2 says."))))

(deftest a-dossier-cites-only-its-own-reports
  (let [answer (str "{\"dossiers\": [{\"story\": \"G1\", \"summary\": \"It held.\", \"status\": \"static\","
                    " \"domain\": \"Economy and markets\", \"label\": \"Fed holds\","
                    " \"facts\": [{\"fact\": \"Rates held at 4%.\", \"cites\": [1, 9]},"
                    "            {\"fact\": \"Made up.\", \"cites\": [9]}],"
                    " \"actors\": [{\"name\": \"Fed\", \"position\": \"Wait\", \"interests\": \"Credibility\"}],"
                    " \"disputes\": [{\"issue\": \"Next move\", \"claims\": [{\"claim\": \"Cut\", \"by\": \"Markets\", \"cites\": [\"[2]\"]}]}],"
                    " \"gaps\": [{\"gap\": \"The vote\", \"query\": \"FOMC vote September 2026\"}],"
                    " \"forces\": [\"rates high\", \"rates high\"]},"
                    " {\"story\": \"G9\", \"summary\": \"Unknown\"}]}")
        d (get (analysis/parse-dossiers answer [story]) "G1")]
    (is (= ["G1"] (keys (analysis/parse-dossiers answer [story]))) "a story it wasn't asked about is dropped")
    (is (= [{:fact "Rates held at 4%." :cites [1]}] (:facts d)) "a fact that cites no report of its story is dropped")
    (is (= "economics" (:domain d)))
    (is (= [2] (-> d :disputes first :claims first :cites)))
    (is (= ["rates high"] (:forces d)))
    (is (= [{:gap "The vote" :query "FOMC vote September 2026" :key "G1"}]
           (analysis/gap-queries [story] {"G1" d} 5)))))

(deftest a-dossier-keeps-what-the-outlets-expect
  (let [answer (str "{\"dossiers\": [{\"story\": \"G1\", \"summary\": \"It held.\","
                    " \"expectations\": ["
                    "  {\"claim\": \"The Fed cuts in December.\", \"by\": \"Outlet 2's economists\","
                    "   \"subject\": \"Economy\", \"due\": \"2026-12-31\", \"cites\": [2, 1]},"
                    "  {\"claim\": \"Cited nowhere.\", \"subject\": \"economy\", \"cites\": [9]},"
                    "  {\"claim\": \"\", \"cites\": [1]}]}]}")
        d (get (analysis/parse-dossiers answer [story]) "G1")]
    (is (= [{:claim "The Fed cuts in December." :by "Outlet 2's economists" :subject "economy"
             :due "2026-12-31" :cites [2 1] :source "Outlet 2" :outlet "o2.com" :url "https://o2.com/2"}]
           (:expectations d))
        "credited to the outlet of the first report that carries it, and dropped when none of the story's does")))

(deftest gap-searches-go-round-the-stories
  (let [ds {"G1" {:gaps [{:gap "a" :query "qa"} {:gap "b" :query "qb"}]}
            "G2" {:gaps [{:gap "c" :query nil} {:gap "d" :query "qd"}]}}]
    (is (= ["qa" "qd" "qb"] (map :query (analysis/gap-queries [{:key "G1"} {:key "G2"}] ds 5))))
    (is (= ["qa"] (map :query (analysis/gap-queries [{:key "G1"} {:key "G2"}] ds 1))))))

;; --- the map -----------------------------------------------------------------------

(def stories
  [{:key "G1" :title "Fed holds" :sources [{:n 1} {:n 2}]}
   {:key "G2" :title "Sanctions on oil" :sources [{:n 3}]}
   {:key "G3" :title "Chip controls" :sources [{:n 4}]}])

(def dossiers
  {"G1" {:label "Fed holds" :domain "economics" :summary "Held." :facts [{:fact "Held at 4%." :cites [1]}]
         :disputes [{:issue "Next" :claims [{:claim "Cut" :by "Markets" :cites [2]}]}]}
   "G2" {:label "Oil sanctions [3]" :domain "politics" :summary "Tightened."}
   "G3" {:label "Chip \"controls\"" :domain "technology" :summary "Widened."}})

(deftest the-map-is-read-strictly
  (let [answer (str "{\"forces\": [{\"id\": \"F1\", \"name\": \"Energy security over cost\", \"continues\": \"2026-09-29/F1\","
                    " \"direction\": \"strengthening\", \"summary\": \"F.\"},"
                    " {\"id\": \"F2\", \"name\": \"Tech stacks split\", \"continues\": \"made-up\"},"
                    " {\"id\": \"F3\"}],"
                    " \"trends\": [{\"id\": \"T1\", \"name\": \"Energy squeeze\", \"continues\": \"2026-09-29/T1\","
                    " \"direction\": \"gathering strength\", \"stories\": [\"G2\", \"G1\", \"G7\"], \"summary\": \"S.\"},"
                    " {\"id\": \"T2\", \"name\": \"Inflation expectations\", \"stories\": [], \"implied\": true},"
                    " {\"id\": \"T3\", \"name\": \"Nothing\", \"stories\": [\"G8\"]},"
                    " {\"id\": \"T4\", \"name\": \"Unreached\", \"implied\": true}],"
                    " \"links\": [{\"from\": \"G2\", \"to\": \"T1\", \"mechanism\": \"tightens supply\", \"confidence\": \"HIGH\","
                    " \"effect\": \"strengthens\", \"channel\": \"energy\", \"lag\": \"days\", \"explanation\": \"E.\"},"
                    " {\"from\": \"T1\", \"to\": \"T2\", \"mechanism\": \"raises prices\", \"lag\": \"forever\"},"
                    " {\"from\": \"T2\", \"to\": \"F1\", \"mechanism\": \"buyers pay for security\", \"effect\": \"weakens\"},"
                    " {\"from\": \"G3\", \"to\": \"F2\", \"mechanism\": \"forces own chips\", \"confidence\": \"sure\"},"
                    " {\"from\": \"G1\", \"to\": \"G1\", \"mechanism\": \"self\"},"
                    " {\"from\": \"G1\", \"to\": \"T3\", \"mechanism\": \"to a dropped trend\"},"
                    " {\"from\": \"G1\", \"to\": \"F1\"}],"
                    " \"risks\": [{\"risk\": \"Oil spikes\", \"likelihood\": \"possible\", \"horizon\": \"weeks\","
                    " \"exposed\": \"oil +, airlines -\", \"signpost\": \"Brent over 100\", \"nodes\": [\"T1\", \"T9\"]},"
                    " {\"likelihood\": \"likely\"}],"
                    " \"outlook\": {\"base\": \"Prices stay high.\", \"confirm\": \"C.\", \"overturn\": \"O.\"}}")
        {:keys [forces trends links risks outlook]}
        (analysis/parse-connections answer ["G1" "G2" "G3"] ["2026-09-29/T1" "2026-09-29/F1"] "2026-09-30")]
    (is (= [["F1" "2026-09-29/F1" "force"] ["F2" "2026-09-30/F2" "force"]] (map (juxt :id :thread :level) forces))
        "a force continues a thread it names from the days before, else starts its own; one with no name is none")
    (is (= [["T1" "2026-09-29/T1" false] ["T2" "2026-09-30/T2" true]] (map (juxt :id :thread :implied) trends))
        "a trend needs a story of the day, or to be implied and reached by a link")
    (is (= ["G2" "G1"] (:stories (first trends))))
    (is (= ["G2" "G1"] (:stories (second trends)) (:stories (first forces)))
        "an implied trend and a force are shown by the stories whose chains reach them")
    (is (= ["G3"] (:stories (second forces))))
    (is (= ["strengthening" nil] (map :direction trends)))
    (is (= [["G2" "T1" "high" "strengthens" "days"] ["T1" "T2" "medium" "strengthens" nil]
            ["T2" "F1" "medium" "weakens" nil] ["G3" "F2" "medium" "strengthens" nil]]
           (map (juxt :from :to :confidence :effect :lag) links))
        "a link needs a mechanism and two different nodes of the map")
    (is (= [{:risk "Oil spikes" :likelihood "possible" :horizon "weeks" :exposed "oil +, airlines -"
             :signpost "Brent over 100" :nodes ["T1"]}]
           risks))
    (is (= {:base "Prices stay high." :confirm "C." :overturn "O."} outlook))))

(deftest a-map-in-the-old-shape-still-reads
  (let [answer (str "{\"trends\": [{\"name\": \"Energy squeeze\", \"stories\": [\"G2\", \"G1\"]}],"
                    " \"links\": [{\"from\": \"G2\", \"to\": \"G1\", \"mechanism\": \"raises prices\"}]}")
        {:keys [forces trends links]} (analysis/parse-connections answer ["G1" "G2"] [] "2026-09-30")]
    (is (empty? forces))
    (is (= [["2026-09-30/T1" ["G2" "G1"]]] (map (juxt :thread :stories) trends)))
    (is (= [["G2" "G1"]] (map (juxt :from :to) links)))))

(def the-map
  {:forces [{:id "F1" :name "Energy security over cost" :direction "strengthening" :summary "F." :stories ["G2"]}]
   :trends [{:id "T1" :name "Energy (squeeze)" :direction "strengthening" :stories ["G2"] :summary "S."}
            {:id "T2" :name "Inflation expectations" :implied true :stories ["G2"] :summary "I."}]
   :links [{:from "G2" :to "T1" :mechanism "tightens \"supply\"" :confidence "high" :effect "strengthens"
            :explanation "Sanctions cut barrels." :lag "days" :channel "energy"}
           {:from "T1" :to "T2" :mechanism "raises | prices" :confidence "medium" :effect "strengthens" :lag "weeks"}
           {:from "T2" :to "F1" :mechanism "makes buyers pay" :confidence "low" :effect "weakens"}
           {:from "G3" :to "F1" :mechanism "chips" :confidence "high" :effect "weakens"}]
   :risks [{:risk "Oil spikes" :likelihood "possible" :horizon "weeks" :exposed "oil +, airlines -"
            :signpost "Brent over 100" :nodes ["T1"]}]
   :outlook {:base "Prices stay high." :confirm "Brent holds." :overturn "A deal."}})

(deftest the-graph-is-drawn-from-the-map
  (let [g (analysis/graph stories dossiers the-map)]
    (is (str/starts-with? g "flowchart BT\n  %% caption: Read it from the bottom up")
        "the forces sit on top, today's news at the bottom")
    (is (not (str/includes? g "subgraph")) "the levels are told apart by their look, which lays out far better")
    (is (str/includes? g "  f1[\"Energy security over cost\"]\n"))
    (is (str/includes? g "  t1[\"Energy squeeze\"]\n  t2[\"Inflation expectations\"]\n"))
    (is (str/includes? g "  g2[\"Oil sanctions\"]\n  g3[\"Chip controls\"]\n"))
    (is (not (str/includes? g "g1[")) "a story no link touches is left out")
    (is (str/includes? g "  g2 ==> t1\n") "a story's link is drawn bare, the analysis is in the links above it")
    (is (str/includes? g "  t1 -->|\"raises prices\"| t2\n"))
    (is (str/includes? g "  t2 -.-x|\"makes buyers pay\"| f1\n") "a weakening link ends in a cross, a possibility is dotted")
    (is (str/includes? g "  g3 ==x f1\n") "a link the reports show is drawn bold")
    (is (str/includes? g "  class f1 force\n"))
    (is (str/includes? g "  class t2 implied\n") "an implied trend is marked as one")
    (is (str/includes? g "  class g2,g3 news\n")))
  (testing "only the main stories are drawn"
    (let [many (vec (for [i (range 1 15)] {:key (str "G" i) :title (str "Story " i) :sources [{:n i}]}))
          m {:trends [{:id "T1" :name "Squeeze" :stories (mapv :key many)}]
             :links (vec (for [s many] {:from (:key s) :to "T1" :mechanism "adds" :confidence "medium"}))}
          g (analysis/graph many {} m)]
      (is (str/includes? g "g10[\"Story 10\"]"))
      (is (not (str/includes? g "g11")))))
  (is (nil? (analysis/graph stories dossiers {:links []})))
  (is (nil? (analysis/graph stories dossiers nil))))

(deftest the-graph-goes-where-things-connect
  (let [answer "# Day\n\n## Overview\n\nText.\n\n## How it all connects\n\nChains.\n\n## Outlook\n\nNext."]
    (is (= "# Day\n\n## Overview\n\nText.\n\n## How it all connects\n\n```mermaid\nflowchart LR\n```\n\nChains.\n\n## Outlook\n\nNext."
           (analysis/with-graph answer "flowchart LR\n")))
    (is (= answer (analysis/with-graph answer nil)))
    (is (= "x\n```mermaid\nA\n```" (analysis/with-graph "x\n```mermaid\nA\n```" "flowchart LR\n"))
        "a graph the analyst drew is kept"))
  (is (= "# Day\n\n## What changed\n\nA\n\n## The analysis\n\n```mermaid\ng```\n\nB\n\n## The trends\n\nC"
         (analysis/with-graph "# Day\n\n## What changed\n\nA\n\n## The analysis\n\nB\n\n## The trends\n\nC" "g"))
      "the graph opens the analysis")
  (is (= "## One\n\nA\n\n```mermaid\ng```\n\n## Two\n\nB"
         (analysis/with-graph "## One\n\nA\n\n## Two\n\nB" "g"))
      "without a section on connections it goes before the last")
  (is (= "Just text\n\n```mermaid\ng```" (analysis/with-graph "Just text" "g"))))

(deftest the-analyst-is-told-the-desks-work
  (let [block (analysis/analysis-block stories dossiers the-map [{:n 5 :key "G1" :gap "The vote"}] true)]
    (is (str/includes? block "### Structural forces\n\n- **Energy security over cost** (strengthening): F."))
    (is (str/includes? block "- **Energy (squeeze)** (strengthening), in Oil sanctions [3] [3]: S."))
    (is (str/includes? block "- **Inflation expectations** (implied, not yet in the reports): I."))
    (is (str/includes? block (str "- Oil sanctions [3] [3] → Energy (squeeze): tightens \"supply\", strengthens it"
                                  " within days. Sanctions cut barrels. (high confidence)")))
    (is (str/includes? block "- Inflation expectations → Energy security over cost: makes buyers pay, weakens it. (low confidence)"))
    (is (str/includes? block "### Risks the desk sees\n\n- Oil spikes (possible, weeks). Exposed: oil +, airlines -. Signpost: Brent over 100."))
    (is (str/includes? block "### The desk's outlook\n\nPrices stay high. Would confirm it: Brent holds. Would overturn it: A deal."))
    (is (str/includes? block "### Fed holds: Fed holds\nSources [1, 2]\n\nHeld.\n\nFacts:\n- Held at 4%. [1]"))
    (is (str/includes? block "Disputed:\n- Next: Markets: Cut [2]"))
    (is (str/includes? block "so don't draw one")))
  (is (nil? (analysis/analysis-block stories {} nil [] false))))

(deftest the-analyst-sees-each-outlets-record-on-what-it-expects
  (let [ds (assoc-in dossiers ["G1" :expectations]
                     [{:claim "The Fed cuts in December." :by "Wire's economists" :subject "economy"
                       :cites [2] :source "Wire" :outlet "wire.org"}
                      {:claim "Inflation returns." :subject "economy" :cites [1] :source "New" :outlet "new.org"}])
        block (analysis/analysis-block stories ds nil [] false
                                       {["wire.org" "economy"] {:judged 5 :held 1 :partly 1 :failed 3 :closeness 0.3}
                                        ["wire.org" "technology"] {:judged 9 :held 9 :partly 0 :failed 0 :closeness 1.0}})]
    (is (str/includes? block (str "Expected:\n- Wire (Wire's economists): The Fed cuts in December. [2]"
                                  " Its record on economy: 1 of 5 held, 1 partly, closeness 0.3.")))
    (is (str/includes? block "- New: Inflation returns. [1] No record on economy yet."))))

(deftest the-researcher-is-told-the-trends
  (is (= "The trends the desk found running through the day:\n\n- Energy squeeze (strengthening), in stories [3, 1, 2]: Oil up."
         (analysis/trends-block stories {:trends [{:name "Energy squeeze" :direction "strengthening"
                                                   :stories ["G2" "G1"] :summary "Oil up."}]})))
  (is (= (str "The structural forces the desk found the day feeding:\n\n- Energy security over cost (strengthening), in stories [3]: F."
              "\n\nThe trends the desk found running through the day:\n\n- Energy (squeeze) (strengthening), in stories [3]: S."
              "\n- Inflation expectations, in stories [3]: I.")
         (analysis/trends-block stories the-map)))
  (is (nil? (analysis/trends-block stories {:trends []}))))

;; --- article pages -----------------------------------------------------------------

(def para (apply str (repeat 12 "The minister said the plan would cut costs for importers. ")))

(deftest an-article-page-gives-its-text
  (testing "structured data's articleBody comes first"
    (is (= (str/trim (str "He said \"no\". " para))
           (feed/article-text (str "<script type=\"application/ld+json\">{\"articleBody\": \"He said \\\"no\\\". "
                                   para "\"}</script><p>" para "</p>")))))
  (testing "else the article's paragraphs, without the page around them"
    (let [html (str "<nav><p>" para "</p></nav><article><p>Short.</p><p>" para "</p>"
                    "<p>Subscribe to our newsletter to keep reading the latest news every day.</p></article>")]
      (is (= (str/trim para) (feed/article-text html)))))
  (testing "a teaser is not the article"
    (is (nil? (feed/article-text "<p>Only a short teaser behind the paywall, nothing more to read here.</p>"))))
  (testing "an empty or teaser articleBody leaves the paragraphs to give the text"
    (is (= (str/trim para)
           (feed/article-text (str "<script type=\"application/ld+json\">{\"articleBody\": \"\"}</script>"
                                   "<article><p>" para "</p></article>")))))
  (testing "a paragraph left open ends where the next starts"
    (is (= (str (str/trim para) "\n\n" (str/trim para))
           (feed/article-text (str "<article><p>" para "<p>" para "</article>")))))
  (testing "a long page of unclosed paragraphs is read quickly"
    (let [html (apply str (repeat 20000 "<p>x"))
          start (System/currentTimeMillis)]
      (is (nil? (feed/article-text html)))
      (is (< (- (System/currentTimeMillis) start) 5000)))))
