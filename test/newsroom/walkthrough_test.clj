(ns newsroom.walkthrough-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.datastar.html :as h]
            [newsroom.walkthrough :as walk]))

(def ^:private sources
  [{:n 1 :title "Oil jumps" :url "https://e.com/1" :source "Wire"}
   {:n 2 :title "Rates hold" :url "https://e.com/2" :source "Post"}])

(def ^:private briefing
  (str/join
   "\n"
   ["# Oil squeezes the Fed"
    ""
    "> The strait closed and rates stopped mattering."
    ""
    "## What changed"
    ""
    "Oil took the day [[1]](https://e.com/1)."
    ""
    "### Risks to watch"
    ""
    "- **Tanker insurance**: It could double within weeks [[2]](https://e.com/2)."
    "- A strike is possible [[1]](https://e.com/1)."
    ""
    "### Outlook"
    ""
    "Prices stay high through the winter."
    ""
    "## Step 1: The strait closes"
    ""
    "**Iran shut the strait.**"
    ""
    "Why it matters: Importers pay first [[1]](https://e.com/1)."
    ""
    "- Brent passed 100 [[1]](https://e.com/1)."
    ""
    "```mermaid"
    "flowchart BT"
    "  a[\"Strait shut\"] ==> b[\"Freight costs\"]"
    "  class a news"
    "  class b trend"
    "```"
    ""
    "## Step 2: The Fed holds"
    ""
    "**The Fed held [2].**"
    ""
    "```mermaid"
    "flowchart BT"
    "  b -->|\"lifts prices\"| c[\"Cost of debt\"]"
    "  d[\"Rate hold\"] --x c"
    "  class c force"
    "  class d news"
    "```"
    ""
    "## The analysis"
    ""
    "The freight chain is the point [[2]](https://e.com/2)."
    ""
    "## What held"
    ""
    "Equities didn't move."
    ""
    "## Base case"
    ""
    "**3 in 5**"
    ""
    "Oil stays above 90."
    ""
    "Confirms: Brent over 90 by Friday."
    "Overturns: The strait reopens."
    ""
    "## The calendar"
    ""
    "The dates test it."
    ""
    "- **7 Nov**: OPEC meets"
    ""
    "## Sources"
    ""
    "- [1] [Oil jumps](https://e.com/1)"]))

(defn- drawn [markdown] (str (h/html (walk/walkthrough markdown sources))))

(defn- before? [page a b]
  (let [i (str/index-of page a) j (str/index-of page b)]
    (and i j (< i j))))

(deftest the-day-reads-from-the-cover-to-the-footer
  (let [page (drawn briefing)]
    (is (str/includes? page "<h1>Oil squeezes the Fed</h1>"))
    (is (str/includes? page "The strait closed and rates stopped mattering."))
    (testing "in the order a reader wants it"
      (doseq [[a b] (partition 2 1 ["walk-hero" "What changed" "Risks to watch" "walk-map" "The analysis"
                                    "What held" "Base case" "The calendar"])]
        (is (before? page a b) (str a " comes before " b))))
    (testing "the risks are columns, each opening on its name, citations kept"
      (is (str/includes? page "<h3>Tanker insurance</h3>"))
      (is (re-find #"It could double within weeks.*href=\"https://e.com/2\"" page))
      (testing "and one with a single sentence keeps its citation with it"
        (is (re-find #"<h3>A strike is possible.</h3>.*href=\"https://e.com/1\"" page))))
    (is (re-find #"Prices stay high through the winter" page) "the outlook is written out at the top")
    (testing "the footer closes on the odds, what tests them, and the dates"
      (is (str/includes? page "3 in 5"))
      (is (not (str/includes? page "**3 in 5**")))
      (is (re-find #"(?s)confirms.*Brent over 90" page))
      (is (re-find #"(?s)overturns.*The strait reopens" page))
      (is (str/includes? page "<h3>7 Nov</h3>"))
      (is (re-find #"<footer class=\"walk-close\"" page)))
    (is (not (re-find #"<h2[^>]*>Sources" page)) "the page lists the sources itself")))

(deftest the-steps-are-cards-on-the-stage
  (let [page (drawn briefing)]
    (is (str/includes? page "data-canvas"))
    (is (str/includes? page "data-step=\"1\""))
    (is (str/includes? page "data-step=\"2\""))
    (is (str/includes? page "The strait closes") "the title, without its number")
    (is (not (str/includes? page "Step 1:")))
    (is (not (str/includes? page "pre class=\"mermaid\"")) "the fragments are the stage's now, not diagrams")
    (testing "a bare citation links to its source, titled with it"
      (is (re-find #"title=\"Rates hold, Post\" href=\"https://e.com/2\"" page)))
    (testing "the map goes to the page's script as JSON"
      (let [payload (second (re-find #"(?s)data-map[^>]*>(.*?)</script>" page))
            {:keys [nodes edges sources]} (json/read-str payload :key-fn keyword)]
        (is (= [["a" "Strait shut" "news" 1] ["b" "Freight costs" "trend" 1]
                ["c" "Cost of debt" "force" 2] ["d" "Rate hold" "news" 2]]
               (map (juxt :id :label :kind :step) nodes)))
        (is (= [["a" "b" "strong" "strengthens" nil 1]
                ["b" "c" "normal" "strengthens" "lifts prices" 2]
                ["d" "c" "normal" "weakens" nil 2]]
               (map (juxt :from :to :weight :effect :label :step) edges)))
        (is (= ["Oil jumps"] (map :title (get sources :1))) "and the reports each step leans on")))
    (testing "with fewer than two forces, the cover says what its first events are"
      (is (re-find #"(?s)Two threads in today.*The strait closes.*Iran shut the strait" page)))
    (testing "a narrow page heads each card with its own pieces"
      (is (re-find #"(?s)data-step=\"2\".*step-chain.*chain-node force fresh\">Cost of debt" page)))))

(deftest a-label-cant-close-the-script
  (let [page (drawn (str/replace briefing "Strait shut" "</script><b>Shut"))]
    (is (not (re-find #"</script><b>" page)))))

(deftest a-briefing-from-before-the-steps
  (let [graph (str "```mermaid\nflowchart BT\n  %% caption: read it upward\n  a[\"Vote\"] --> f[\"Debt\"]\n"
                   "  g[\"Tariff\"] --> f\n  h[\"Chips\"] --> i[\"Blocs\"]\n"
                   "  class a,g,h news\n  class f,i force\n```")
        page (drawn (str "# Old day\n\n> It moved.\n\n## What changed\n\nThings [[1]](https://e.com/1).\n\n"
                         "### Risks to watch\n\n- A risk: it bites.\n\n### Outlook\n\nMore of it.\n\n"
                         "## The analysis\n\n" graph "\n\nThe reasoning.\n\n## The trends\n\n### Debt\n\nThe facts."))]
    (testing "it keeps its one diagram in its analysis, with no stage"
      (is (str/includes? page "pre class=\"mermaid\""))
      (is (not (str/includes? page "data-canvas")))
      (is (str/includes? page "The reasoning."))
      (is (str/includes? page "The facts.")))
    (is (str/includes? page "<h3>A risk</h3>"))
    (is (re-find #"(?s)Two forces in play.*Debt.*Fed by Vote · Tariff" page)
        "its cover still reads the forces, and what feeds them, off the diagram")
    (is (not (str/includes? page "walk-close")) "and it has no footer to show")))

(deftest a-briefing-with-steps-and-no-fragments
  (let [page (drawn "# Day\n\n## Step 1: One\n\n**It happened.**\n\n- A fact.")]
    (is (str/includes? page "It happened."))
    (is (not (str/includes? page "data-canvas")) "there's no stage with nothing to draw on it")))

(deftest a-store-written-briefing-closes-on-its-outlook
  (let [page (drawn "# Day\n\n## Step 1: One\n\n**It.**\n\n## What could break the picture\n\n### Oil spikes\n\nIt could.\n\n## Outlook\n\n**2 in 5**\n\nA base.\n\nConfirms: X.\nOverturns: Y.")]
    (is (str/includes? page "<h3>Oil spikes</h3>"))
    (is (re-find #"(?s)walk-close.*2 in 5" page))))

(deftest a-cover-list-of-the-steps-isnt-shown-twice
  (is (not (str/includes? (drawn "# Day\n\n- One\n\n## Step 1: One\n\n**It.**") "<li>One")))
  (is (str/includes? (drawn "# Day\n\n- Kept\n\nText.") "<li>Kept")))

(deftest a-citation-with-spaces-in-its-address-is-still-a-link
  (let [page (drawn "# Day\n\n## The analysis\n\nA claim [[1]](https://e.com/China Vows To).")]
    (is (str/includes? page "href=\"https://e.com/China%20Vows%20To\""))
    (is (not (str/includes? page "(https://e.com/China")))))

(deftest a-briefing-of-plain-text
  (let [page (drawn "# Big day\n\nClaim [[1]](https://e.com/1).")]
    (is (str/includes? page "<h1>Big day</h1>"))
    (is (str/includes? page "Claim"))
    (is (re-find #"href=\"https://e.com/1\"[^>]*>1</a>" page))))

(deftest the-map-is-read-off-the-fragments
  (testing "a node keeps its first label and step, an edge its first drawing"
    (let [{:keys [nodes edges]} (walk/map-of ["flowchart BT\n  a[\"One\"] --> b\n" "flowchart BT\n  a[\"Two\"] --> b\n  b -.-> c\n"])]
      (is (= [["a" "One" 1] ["b" "b" 1] ["c" "c" 2]] (map (juxt :id :label :step) nodes)))
      (is (= [["a" "b" 1] ["b" "c" 2]] (map (juxt :from :to :step) edges)))
      (is (= :possible (:weight (second edges))))))
  (testing "what only draws or decorates names nothing"
    (is (nil? (walk/map-of ["flowchart BT\n  %% caption: a\n  classDef x fill:#fff\n"]))))
  (testing "a node with no class is a pressure"
    (is (= :trend (:kind (first (:nodes (walk/map-of ["a --> b"]))))))))

(deftest the-script-and-the-markup-agree
  (let [js (slurp (io/resource "public/js/walkthrough.js"))
        graph (slurp (io/resource "public/js/story-graph.js"))
        css (slurp (io/resource "public/css/style.css"))]
    (doseq [hook ["\\.walkthrough" "\\[data-step\\]" "\\[data-canvas\\]" "\\[data-map\\]" "\\[data-sel\\]" "MutationObserver"]]
      (is (re-find (re-pattern hook) js) hook))
    (is (re-find #"two\.module\.js" graph))
    (is (re-find #"prefers-reduced-motion" graph))
    (testing "every colour the drawing reads is one the stylesheet gives, plain, in light and in dark"
      (doseq [token (distinct (map second (re-seq #"v\('(--[a-z-]+)'\)" graph)))
              :when (not= "--sans" token)]
        (is (= 2 (count (re-seq (re-pattern (str token ": #")) css))) token)))
    (is (re-find #"container:\s*walk / inline-size" css) "the cards measure the width the graph does")
    (testing "a storyline's facts and a card's are styled apart"
      (is (re-find #"\.storyline \.facts \{" css))
      (is (not (re-find #"(?m)^\.facts \{" css))))
    (testing "the topics pane still makes room beside the stage"
      (is (re-find #"\.topics-open main:has\(\.walkthrough\)" css)))
    (testing "and a passage of the new page can still be picked as a topic"
      (is (re-find #"\.walkthrough \.cited p" (slurp (io/resource "public/js/topics.js")))))))
