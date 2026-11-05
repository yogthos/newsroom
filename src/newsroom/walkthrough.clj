(ns newsroom.walkthrough
  "A day's briefing as the page shows it, from its markdown: the cover, what
  changed with its risks and outlook, the day's map on a stage held still
  while the step cards travel over it, the analysis, and the footer.

  Each `## Step N` of a briefing is one event, and ends with a mermaid
  fragment naming its piece of the map. The fragments are read here into one
  graph, each node owned by the step that names it first, which
  resources/public/js/walkthrough.js brings on as the reader reaches that
  step. Everything is in the markup, so the page reads whole without a
  script, and a briefing from before the steps shows its one diagram in its
  analysis instead."
  (:require [clojure.string :as str]
            [jolt.datastar.html :as h]
            [newsroom.json :as json]
            [newsroom.markdown :as md]))

;; --- the day's map ------------------------------------------------------------------

(def ^:private arrow
  "What separates two nodes on a line of a fragment: `-->`, `---`,
  `-.->`, `==>`, and their `x` forms. A label the line hangs off it,
  `|like this|`, opens the next piece, which `chain-of` peels."
  #"\s*(?:==+[>x]|-\.-[>x]|--+[>x]|--+|==+)\s*")

(def ^:private arrow-mark
  "The arrows themselves, in the order a line draws them, so an edge keeps
  the weight and the direction of effect its arrow states."
  #"==+[>x]|-\.-[>x]|--+[>x]|--+|==+")

(def ^:private kind-of-node
  "The kinds a node can be classed as. The map reads them as three layers:
  the day's news at the foot, the pressure it puts on above it, the
  structural forces at the top."
  #{"news" "trend" "implied" "force"})

(def ^:private not-a-node
  "The lines of a fragment that draw or decorate rather than name a node."
  #"^(?:(?:flowchart|graph|subgraph|end|classDef|class|style|linkStyle|click|direction)(?:\s|$)|%%)")

(defn- node-label
  "The text a node's shape carries, without the shape or its quotes: the
  `\"Central bank\"` of `Fed[\"Central bank\"]`. Empty when it carries
  none, as a bare `A` does."
  [s]
  (let [inner (str/trim (or (second (re-matches #"^[\[\(\{>]+(.*?)[\]\)\}]+\s*$" (str/trim s))) ""))
        inner (if (and (> (count inner) 1) (str/starts-with? inner "\"") (str/ends-with? inner "\""))
                (subs inner 1 (dec (count inner)))
                inner)]
    (str/trim inner)))

(defn- node-of
  "The node a piece of a line names: its id and the label it carries, its
  own id when it carries none."
  [s]
  (when-let [[_ id] (re-find #"^([A-Za-z0-9_][A-Za-z0-9_.-]*)" (str/trim s))]
    (let [label (node-label (subs (str/trim s) (count id)))]
      {:id id :label (if (str/blank? label) id label)})))

(defn- arrow-label
  "The text an arrow carries, without its pipes or the quotes mermaid
  lets you write around it: the `cuts` of `-->|cuts|`."
  [s]
  (let [t (str/trim (or s ""))
        t (if (and (> (count t) 1) (str/starts-with? t "\"") (str/ends-with? t "\""))
            (subs t 1 (dec (count t)))
            t)]
    (not-empty (str/trim t))))

(defn- chain-of
  "A line of a fragment split into the nodes it names, the label on each
  arrow between them, nil where the arrow carries none, and the arrows."
  [line]
  (let [pieces (map-indexed
                (fn [i s]
                  (if-let [[_ label node] (and (pos? i) (re-matches #"\|([^|]*)\|\s*(.*)" (str/trim s)))]
                    [node (arrow-label label)]
                    [(str/trim s) nil]))
                (str/split line arrow))]
    [(keep (comp node-of first) pieces)
     (map second (rest pieces))
     (re-seq arrow-mark line)]))

(defn- edge-style
  "What an arrow says about the link it draws: a heavy `==>` is the link
  the reports show, a dashed `-.->` one that is only possible, and a
  trailing `x` a link that weakens its target rather than feeding it."
  [op]
  (let [op (or op "-->")
        weight (cond (str/starts-with? op "==") :strong
                     (str/starts-with? op "-.") :possible
                     :else :normal)]
    {:weight weight
     :effect (if (str/ends-with? op "x") :weakens :strengthens)}))

(defn- fragment-graph
  "The nodes and edges one step's fragment draws, in the order it names
  them: a node once, an edge between two different nodes it links. And the
  kinds its `class` lines give them, keyed by id."
  [source]
  (let [lines (str/split (str source) #"\n")
        drawn (reduce
               (fn [{:keys [nodes edges]} line]
                 (let [t (str/trim line)]
                   (if (or (str/blank? t) (re-find not-a-node t))
                     {:nodes nodes :edges edges}
                     (let [[named labels ops] (chain-of t)
                           ids (map :id named)]
                       {:nodes (concat nodes (remove (fn [n] (some #(= (:id %) (:id n)) nodes)) named))
                        :edges (concat edges
                                       (remove #(= (:from %) (:to %))
                                               (map (fn [from to label op]
                                                      (cond-> (merge {:from from :to to} (edge-style op))
                                                        label (assoc :label label)))
                                                    ids (rest ids) labels (concat ops (repeat nil)))))}))))
               {:nodes [] :edges []}
               lines)
        kinds (into {} (mapcat (fn [line]
                                 (when-let [[_ ids kind] (re-matches #"class\s+([^\s,]+(?:,[^\s,]+)*)\s+(\S+)\s*" (str/trim line))]
                                   (when (contains? kind-of-node kind)
                                     (map (fn [id] [id (keyword kind)]) (str/split ids #",")))))
                               lines))]
    (assoc drawn :kinds kinds)))

(defn map-of
  "The day's map, read off `fragments`, one mermaid fragment for each step in
  order: its nodes and edges, each with the :step, from 1, that first names
  it. A node keeps that step's label; nil when no fragment names a node."
  [fragments]
  (let [parsed (map-indexed (fn [i source] [(fragment-graph source) (inc i)]) fragments)
        kinds (apply merge (map (fn [[g _]] (:kinds g)) parsed))
        nodes (->> parsed
                   (mapcat (fn [[g i]] (map #(assoc % :step i) (:nodes g))))
                   (reduce (fn [acc n] (if (some #(= (:id %) (:id n)) acc) acc (conj acc n))) [])
                   (mapv #(assoc % :kind (get kinds (:id %) :trend))))
        ids (into #{} (map :id) nodes)
        edges (->> parsed
                   (mapcat (fn [[g i]] (map #(assoc % :step i) (:edges g))))
                   (filter #(and (contains? ids (:from %)) (contains? ids (:to %))))
                   (reduce (fn [acc e]
                             (if (some #(and (= (:from %) (:from e)) (= (:to %) (:to e))) acc)
                               acc (conj acc e)))
                           []))]
    (when (seq nodes) {:nodes nodes :edges edges})))

(defn script-json
  "JSON meant to sit in a script's body: a script is raw text, so the
  markup is not entity-decoded there and the JSON has to go in as it is,
  only with `<` written so a label can never close the element early."
  [x]
  (h/raw (str/replace (json/write-str x :escape-slash false) "<" "\\u003c")))


;; --- the steps ---------------------------------------------------------------------

(defn- plain
  "A line of markdown as the text a person reads: a link its label, a
  citation its number gone with the link, the emphasis marks off. What
  the steps show, so a card never reads as raw markdown."
  [s]
  (-> s
      (str/replace #"(?m)^\s*#{1,6}\s+" "")
      (str/replace #"(?m)^\s*>\s+" "")
      ;; a run of citations goes whole, with the commas between them, so
      ;; none is left hanging after the sentence they follow
      (str/replace #"\s*\[\[\d+\]\]\((?:[^()]|\([^()]*\))*\)(?:\s*,\s*\[\[\d+\]\]\((?:[^()]|\([^()]*\))*\))*" "")
      (str/replace #"\[([^\]]*)\]\([^)]*\)" "$1")
      ;; a citation mark, [3] or [2,7], is a pointer to the sources, not
      ;; something to read on the stage
      (str/replace #"\[\d[\d,\s\u2013-]*\]" "")
      (str/replace #"(\*\*|__|`|\*|_)" "")
      (str/replace #"\s+" " ")
      (str/replace #"\s+([,.;:?!])" "$1")
      str/trim))

(defn- sentence
  "The first sentence of [text], cut at a word once it runs past
  [limit], for the one-line caption the stage shows."
  [text limit]
  (let [lead (str/trim (or (re-find #"(?s)^.+?[.?!](?=\s|$)" text) text))]
    (if (<= (count lead) limit)
      lead
      (let [head (subs lead 0 limit)
            break (str/last-index-of head " ")]
        (str (subs head 0 (or break (- limit 1))) "…")))))

(defn- step-parts
  "The lines of one step's markdown into the parts the runway shows
  apart: its `##` heading the title, a bold line the hook, a line
  opening \"Why it matters:\" the why, a `- ` list the facts, and a
  fenced block the step's piece of the day's map, kept whole. A step
  written as plain paragraphs, as older briefings were, gives its first
  paragraph the hook and folds the rest away, so past days read as well
  as new ones."
  [markdown]
  (let [parts (reduce
               (fn [acc line]
                 (let [t (str/trim line)]
                   (cond
                     (:fence acc)
                     (if (str/starts-with? t "```")
                       (assoc acc :fence nil)
                       (update acc :diagram (fnil conj []) t))

                     (str/starts-with? t "```")
                     (assoc acc :fence true)

                     (and (nil? (:title acc)) (str/starts-with? t "## "))
                     (assoc acc :title (or (second (re-matches #"## (?:Step \d+: )?(.*)" t))
                                           (subs t 3)))

                     (and (nil? (:hook acc)) (str/starts-with? t "**") (str/ends-with? t "**"))
                     (assoc acc :hook (subs t 2 (- (count t) 2)))

                     (and (nil? (:why acc)) (str/starts-with? t "Why it matters:"))
                     (assoc acc :why (str/trim (subs t (count "Why it matters:"))))

                     (str/starts-with? t "- ")
                     (update acc :facts (fnil conj []) (subs t 2))

                     ;; a step's own sub-heading is not its hook: it
                     ;; belongs with the paragraphs folded away
                     (str/starts-with? t "### ")
                     (update acc :prose (fnil conj []) t)

                     (seq t)
                     (update acc :prose (fnil conj []) t)

                     :else acc)))
               {}
               (str/split markdown #"\n"))
        prose (:prose parts)
        hook (or (:hook parts) (first prose))
        fold (if (:hook parts) prose (rest prose))
        ;; the sources a step leans on, in the order it leans on them,
        ;; across both citation forms the briefings use: [[1]](url) links
        ;; and bare [2,7] markers. A briefing brackets years the same way
        ;; — "since [2019]", "[2026](outlook)" — so a bracketed number
        ;; long enough to be a year is not a source number.
        cited (->> (re-seq #"\[\[(\d+)\]\]\(|\[(\d[\d,\s]*)\]" markdown)
                   (keep (fn [[_ link bare]] (or link bare)))
                   (mapcat #(str/split % #"[\s,]+"))
                   (remove str/blank?)
                   (remove #(>= (count %) 4))
                   distinct
                   vec)]
    ;; plain for the captions and the cover; the card shows the raw line, so
    ;; its citations can stay on it as links (see card-text)
    (cond-> (assoc (select-keys parts [:title :diagram]) :cited cited)
      (seq (:why parts)) (assoc :why (plain (:why parts)) :why-raw (:why parts))
      (seq (:facts parts)) (assoc :facts (map plain (:facts parts)) :facts-raw (:facts parts))
      hook (assoc :hook (plain hook) :hook-raw hook)
      (seq fold) (assoc :fold fold))))


(defn- kind-of-step
  "The kind a card's pieces mostly are, for the tag the card carries: the
  layer of the map that step brings on, most structural first, and the
  day's news when the map says nothing about it."
  [graph index]
  (let [kinds (map :kind (filter #(= index (:step %)) (:nodes graph)))]
    (or (some (set (map name kinds)) ["force" "implied" "trend" "news"])
        "news")))


(defn- sentence-case [s] (str (str/upper-case (subs s 0 1)) (subs s 1)))

(def ^:private section-kinds
  "The sections with a place of their own on the page, by their headings."
  [[:changed #"(?i)what changed"]
   [:held #"(?i)what held"]
   [:risks #"(?i)what could break.*|risks(?: to watch)?"]
   [:outlook #"(?i)outlook.*"]
   [:base #"(?i)(?:the )?base case.*"]
   [:calendar #"(?i)(?:the )?calendar"]
   [:sources #"(?i)sources"]])

(defn- section-kind [title]
  (some (fn [[kind re]] (when (re-matches re title) kind)) section-kinds))

(defn- nested-sections
  "A briefing can nest its risks and outlook as `###` under what changed, as
  those of early October do. The sections with those lifted out to their
  own places; one the briefing also wrote at the top level keeps that one."
  [{:keys [changed] :as sections}]
  (if-not changed
    sections
    (let [[lead & subs] (str/split changed #"(?m)^### ")
          parts (for [sub subs :let [[title body] (str/split sub #"\n" 2)]]
                  {:kind (section-kind (str/trim title)) :whole (str "### " sub) :body (str/trim (str body))})
          lifted (into {} (for [{:keys [kind body]} parts :when (and kind (seq body))] [kind body]))
          kept (remove :kind parts)]
      (assoc (merge lifted sections)
             :changed (str/trim (str/join "\n\n" (cons lead (map :whole kept))))))))

(defn- marks
  "Markdown with each citation a link labelled by its number alone, without
  the brackets or the comma between two of them."
  [markdown]
  (-> (str markdown)
      ;; a briefing stored with an address with spaces in it, which would end the link
      (str/replace #"\[\[(\d+)\]\]\(([^)\n]*)\)"
                   (fn [[_ n url]] (str "[[" n "]](" (str/replace url " " "%20") ")")))
      (str/replace #"(\]\((?:[^()\s]|\([^()\s]*\))*\))\s*,\s*(?=\[\[\d)" "$1 ")
      (str/replace #"\[\[(\d+)\]\]\(" "[$1](")))

(defn- apart
  "Rendered links to elsewhere set to open in a new tab."
  [html]
  (str/replace html "<a href=\"http" "<a target=\"_blank\" rel=\"noopener\" href=\"http"))

(defn- cited-text
  "Markdown as HTML with its citations as small links that open apart."
  [markdown]
  (h/raw (-> markdown marks md/html apart)))

(defn- card-text
  "One line of a card as HTML: emphasis off, since the card styles its own
  parts, and every citation a numbered link to the report it names, titled
  with it. `by-n` is the day's sources by number as a string. A bare [2,7]
  is linked the same way; a number no source has is dropped."
  [by-n line]
  (let [link (fn [n]
               (when-let [{:keys [url]} (get by-n n)]
                 (str "[[" n "]](" url ")")))
        linked (str/replace (str line) #"(?<!\[)\[(\d{1,3}(?:\s*,\s*\d{1,3})*)\](?!\()"
                            (fn [[_ ns]]
                              (str/join ", " (keep link (str/split ns #"\s*,\s*")))))
        unbold (-> linked
                   (str/replace #"\*\*" "")
                   (str/replace #"^\s*(?:#{1,6}|>)\s+" ""))]
    (h/raw
     (-> (md/inline (marks unbold))
         (str/replace #"\s+,\s" ", ")
         (str/replace #"<a href=\"([^\"]*)\">(\d+)</a>"
                      (fn [[_ url n]]
                        (let [{:keys [title source]} (get by-n n)
                              label (str/join ", " (remove str/blank? [title source]))]
                          (str "<a target=\"_blank\" rel=\"noopener\""
                               (when (seq label) (str " title=\"" (md/escape label) "\""))
                               " href=\"" url "\">" n "</a>"))))))))

(defn- card-sources
  "The reports a card leans on, listed by number, for a reader who wants to
  see them all without opening each or going by way of the map."
  [by-n numbers]
  (when-let [found (seq (keep by-n numbers))]
    [:details.card-sources
     [:summary (str "Sources (" (count found) ")")]
     [:ol
      (for [{:keys [n title url source]} found]
        [:li {:value n}
         [:a {:href url :target "_blank" :rel "noopener"} title]
         (when-not (str/blank? source) [:span.muted (str " " source)])])]]))


(def ^:private map-kind-names
  "What the legend calls each kind of piece, and so what a card calls it."
  {"news" "News" "trend" "Pressure" "implied" "Not yet shown" "force" "Force"})

(defn- chain-columns
  "The nodes `ids`, in the order given, as columns by how far along the links
  `edges` they sit: what nothing here feeds first. A loop stops counting once
  every node has been walked, rather than running on."
  [ids edges]
  (let [ids (vec ids)
        step (fn [depth]
               (reduce (fn [d {:keys [from to]}]
                         (assoc d to (max (get d to 0) (inc (get d from 0)))))
                       depth edges))
        depth (nth (iterate step (zipmap ids (repeat 0))) (count ids))
        top (min (dec (max 1 (count ids))) (reduce max 0 (vals depth)))
        at (fn [id] (min top (get depth id 0)))]
    (remove empty? (for [d (range (inc top))] (filter #(= d (at %)) ids)))))

(defn- step-chain
  "The pieces of the map one card brings on, and those it connects them to,
  as a header across the card for a screen too narrow to hold the map beside
  it. Nothing for a card that draws none."
  [{:keys [nodes edges]} index]
  (let [mine (filter #(= index (:step %)) edges)
        linked (set (mapcat (juxt :from :to) mine))
        shown (filter #(or (= index (:step %)) (contains? linked (:id %))) nodes)
        by-id (into {} (map (juxt :id identity) shown))]
    (when (seq shown)
      [:div.step-chain {:aria-label "The pieces of the map this card adds"}
       (for [col (chain-columns (map :id shown) mine)]
         [:div.chain-col
          (for [id col
                :let [{:keys [label kind step]} (by-id id)]]
            [:span {:class (str "chain-node " (name kind) (when (= index step) " fresh"))} label])])])))


;; --- the sections around the map ----------------------------------------------------

(defn- pad [n] (if (< n 10) (str "0" n) (str n)))

(defn- columns
  "Numbered columns under a rule, one to an item {:title :body}, where body
  is hiccup: the cover's forces, the risks and the calendar are all these."
  [class items]
  [:ol {:class (str "cols " class) :style (str "--cols:" (count items))}
   (for [[n {:keys [title body]}] (map-indexed vector items)]
     [:li [:span.num (pad (inc n))] [:h3 title] body])])


(def ^:private small-numbers
  ["zero" "one" "two" "three" "four" "five" "six" "seven" "eight" "nine" "ten"])

(defn- counted
  "\"Three threads\", from 3 and \"thread\"."
  [n noun]
  (let [word (get small-numbers n (str n))]
    (str (str/upper-case (subs word 0 1)) (subs word 1) " " noun (when (not= 1 n) "s"))))


(defn- cover-parts
  "What comes before a briefing's first section: its `#` title, its `>`
  standfirst, and whatever else it wrote. A list there only names the steps,
  which the page sets out itself, so `steps?` leaves it out."
  [head steps?]
  (let [lines (cond->> (str/split-lines (str head))
                steps? (remove #(str/starts-with? % "- ")))
        title? #(str/starts-with? % "# ")
        quote? #(str/starts-with? % ">")]
    {:title (some #(when (title? %) (subs % 2)) lines)
     :standfirst (not-empty (str/join " " (map #(str/trim (subs % 1)) (filter quote? lines))))
     :prose (not-empty (str/trim (str/join "\n" (remove #(or (title? %) (quote? %)) lines))))}))

(defn- forces-in-play
  "The forces the day's map names, each with what feeds it."
  [{:keys [nodes edges]}]
  (let [label (into {} (map (juxt :id :label) nodes))]
    (for [{:keys [id] :as node} (filter #(= :force (:kind %)) nodes)
          :let [feeds (keep #(when (= id (:to %)) (label (:from %))) edges)]]
      {:title (:label node)
       :body (when (seq feeds) [:p (str "Fed by " (str/join " · " (take 4 feeds)))])})))

(defn- in-play
  "The cover's account of the day: the forces its map names when it names a
  few, else its first few events with what each says."
  [graph steps]
  (let [forces (take 4 (forces-in-play graph))
        threads (for [{:keys [title hook]} (take 3 steps)]
                  {:title title :body (when hook [:p (sentence hook 170)])})
        forces? (>= (count forces) 2)]
    (when (or forces? (seq threads))
      [:section.in-play
       [:h2.kicker (if forces?
                     (str (counted (count forces) "force") " in play today")
                     (str (counted (count threads) "thread") " in today's briefing"))]
       (columns "forces" (if forces? forces threads))])))

(defn- prose-row
  "A closing section set as a heading beside its text."
  [heading markdown]
  (when markdown
    [:section.prose-row.walk-band [:h2 heading] [:div.cited (cited-text markdown)]]))


(defn- bullet-item
  "A risk written as one `- ` line: what it opens with, up to a short label's
  colon or the end of its first sentence, as its title, and the rest. The
  citations a title loses go on with what follows it."
  [line]
  (let [t (str/trim (str/replace (str/trim line) #"^-\s+" ""))
        [_ title body] (or (re-matches #"(?s)\*\*([^*]{3,70}?):?\*\*:?\s+(.+)" t)
                           (re-matches #"(?s)([^:.\[]{3,70}):\s+(.+)" t)
                           (re-matches #"(?s)(.+?[.?!])\s+(?=[A-Z\[])(.+)" t)
                           [nil t nil])
        carried (str/join " " (re-seq #"\[\[\d+\]\]\([^)]*\)" (str title)))
        body (str/trim (str body " " carried))]
    {:title (plain title) :body (when (seq body) [:div.cited (cited-text (sentence-case body))])}))

(defn- risks-section
  "What could go wrong: each `###` of the section a column, or each `- ` when
  it was written as a list. One written as neither is shown as it was written."
  [markdown]
  (when markdown
    (let [[intro & items] (str/split markdown #"(?m)^### ")
          lines (str/split-lines intro)
          bullet? #(str/starts-with? (str/trim %) "- ")
          intro (str/trim (str/join "\n" (remove bullet? lines)))
          parsed (concat
                  (map bullet-item (filter bullet? lines))
                  (for [item items
                        :let [[title body] (str/split item #"\n" 2)]]
                    {:title (plain title) :body [:div.cited (cited-text body)]}))]
      [:section.risks.walk-band
       [:header.band-head [:h2 "Risks to watch"]]
       (when-not (str/blank? intro) [:div.cited (cited-text intro)])
       (when (seq parsed) (columns "risk-list" parsed))])))

(defn- outlook-parts
  "The outlook's lines apart: the odds of its base case, what would confirm
  it, what would overturn it, and its text."
  [markdown]
  (reduce
   (fn [acc line]
     (let [t (str/trim line)
           labelled #(second (re-matches % t))]
       (if-let [[_ n m] (re-matches #"\*{0,2}\s*(?:about |around )?(\d+) in (\d+)\s*\*{0,2}\.?" t)]
         (assoc acc :odds [(parse-long n) (parse-long m)])
         (if-let [c (labelled #"(?i)\*{0,2}confirms?[^:*]*\*{0,2}:\*{0,2}\s*(.+)")]
           (assoc acc :confirms c)
           (if-let [o (labelled #"(?i)\*{0,2}overturns?[^:*]*\*{0,2}:\*{0,2}\s*(.+)")]
             (assoc acc :overturns o)
             (update acc :prose (fnil conj []) line))))))
   {}
   (str/split-lines (str markdown))))


(defn- odds-figure
  "The base case's odds, large, with a tile for each chance in them."
  [[n m]]
  [:div.odds
   [:span.odds-figure (str n " in " m)]
   (when (<= 0 n m 10)
     [:div.odds-meter
      [:div.tiles {:role "img" :aria-label (str n " chances in " m)}
       (for [i (range m)] [:span {:class (if (< i n) "tile on" "tile")}])]
      (when (and (get small-numbers n) (get small-numbers m))
        [:span.odds-label (str "around " (small-numbers n) " in " (small-numbers m))])])])


(defn- calendar-section [markdown]
  (when markdown
    (let [lines (str/split-lines markdown)
          item? #(str/starts-with? (str/trim %) "- ")
          intro (str/trim (str/join "\n" (remove item? lines)))
          items (for [line (filter item? lines)
                      :let [t (plain (subs (str/trim line) 2))
                            [_ on what] (re-matches #"(.+?):\s+(.+)" t)]]
                  {:title (or on t) :body (when what [:p what])})]
      [:section.calendar.walk-band
       [:div.calendar-head
        [:h2.kicker "The calendar"]
        (when-not (str/blank? intro) [:div.cited (cited-text intro)])]
       (when (seq items) (columns "dates" items))])))


(defn- base-case
  "The base case: its odds on the one loud band of the page, then what would
  confirm it and what would overturn it."
  [markdown]
  (when markdown
    (let [{:keys [odds confirms overturns prose]} (outlook-parts markdown)]
      (list
       [:section.walk-outlook
        [:div.walk-band
         [:p.kicker "Base case"]
         [:div.outlook-body
          (when odds (odds-figure odds))
          [:div.cited.outlook-text (cited-text (str/join "\n\n" prose))]]]]
       (when (or confirms overturns)
         [:section.base-tests.walk-band
          (when confirms [:div.confirms [:h2.kicker "Confirms it"] [:div.cited (cited-text (sentence-case confirms))]])
          (when overturns [:div.overturns [:h2.kicker "Overturns it"] [:div.cited (cited-text (sentence-case overturns))]])])))))

(defn- footer
  "What the day closes on, past its analysis. A briefing from before these
  were asked for has none of them, and no footer."
  [{:keys [held base calendar]}]
  (when (or held base calendar)
    [:footer.walk-close
     (prose-row "What held" held)
     (base-case base)
     (calendar-section calendar)]))

;; --- the day ------------------------------------------------------------------------

(defn- split-section
  "One `##` block as [its title, its body], with any mermaid off."
  [block]
  (let [[heading body] (str/split block #"\n" 2)]
    [(str/trim (str/replace heading #"^##\s+" ""))
     (str/trim (str/replace (str body) #"(?s)```.*?(?:```|$)" ""))]))

(defn- odds-or-tests?
  "Whether an outlook gives the odds or the tests the footer's band is for,
  as one written as a section of its own does, rather than running prose."
  [body]
  (let [{:keys [odds confirms overturns]} (outlook-parts body)]
    (boolean (or odds confirms overturns))))

(defn- sections-of
  "The placed sections of `blocks` by kind, the first written of each winning,
  with the risks and outlook nested under what changed lifted out to them."
  [blocks]
  (nested-sections
   (into {} (for [block (reverse blocks)
                  :let [[title body] (split-section block)
                        kind (section-kind title)
                        kind (if (and (= :outlook kind) (odds-or-tests? body)) :base kind)]
                  :when (and kind (seq body))]
              [kind body]))))

(defn- step-sources
  "What each step cites, {\"1\" [source...]}, so a piece of the map can show
  the reports behind it when a reader asks about it."
  [steps by-n]
  (into {} (keep (fn [[i {:keys [cited]}]]
                   (when-let [found (seq (keep by-n cited))]
                     [(str (inc i)) (mapv #(select-keys % [:n :title :url :source]) found)])))
        (map-indexed vector steps)))

(defn- step-card
  [by-n graph i {:keys [title hook-raw why-raw facts-raw cited fold]}]
  (let [n (inc i)]
    [:div.walk-step
     [:article.step-card {"data-step" (str n)}
      (step-chain graph n)
      [:div.card-kicker
       [:span.tag (map-kind-names (kind-of-step graph n))]
       [:span.num (pad n)]]
      (when title [:h3 title])
      (when hook-raw [:p.hook (card-text by-n hook-raw)])
      ;; the piece the reader asks about opens here, on the card the stage is
      ;; holding, so the detail arrives beside the graph rather than over it
      [:div.step-sel {"data-sel" "" :data-ignore-morph "" :hidden ""}]
      (when why-raw [:p.why (card-text by-n why-raw)])
      (when (seq facts-raw)
        [:ul.facts (for [f facts-raw] [:li (card-text by-n f)])])
      (when (seq fold)
        [:details.fold
         [:summary "More"]
         [:div.cited (cited-text (str/join "\n\n" fold))]])
      (card-sources by-n cited)]]))

(defn- the-map
  "The day's events on the stage, or simply one after the other for a day
  whose steps name no pieces of a map."
  [graph steps by-n]
  (when (seq steps)
    [:section.walk-map
     [:div.walk-intro
      [:div.kicker "The map"]
      [:div.walk-intro-body
       [:h2 "How today's news travels up the chain"]
       (when graph
         (list [:p.on-stage "Scroll through the day's events, and press a piece of the map to see what it pushes on."]
               [:p.on-cards "Each card opens with the pieces of the map it adds."]))]]
     [:div.walk-stage-wrap {:class (when-not graph "no-stage")}
      (when graph
        [:div.walk-stage {"data-stage" "" :aria-hidden "true"}
         [:div.walk-canvas {"data-canvas" "" :data-ignore-morph ""}]
         [:div.walk-legend
          [:span.item [:i.sw.news] "News"]
          [:span.item [:i.sw.trend] "Pressure"]
          [:span.item [:i.sw.implied] "Not yet shown"]
          [:span.item [:i.sw.force] "Force"]
          [:span.item [:i.sw.shown] "Shown"]
          [:span.item [:b "×"] "Weakens"]]])
      [:div.walk-steps {"data-steps" ""}
       (map-indexed (partial step-card by-n graph) steps)]]]))

(def ^:private fenced #"(?s)```mermaid\s*\n(.*?)```")

(defn walkthrough
  "The day's `markdown` as the page draws it, with `sources`, its numbered
  reports, behind the citations on its cards."
  [markdown sources]
  (let [blocks (str/split (str markdown) #"(?m)(?=^## )")
        section? #(str/starts-with? % "## ")
        head (first (remove section? blocks))
        blocks (filter section? blocks)
        step? #(re-find #"^## Step \d+" %)
        placed? #(section-kind (first (split-section %)))
        sections (sections-of (filter placed? (remove step? blocks)))
        steps (mapv step-parts (filter step? blocks))
        by-n (into {} (map (juxt #(str (:n %)) identity)) sources)
        fragments (map #(str/join "\n" (:diagram %)) steps)
        graph (some-> (map-of fragments)
                      (assoc :sources (step-sources steps by-n)))
        ;; a day from before the steps can still say what's in play, off its one diagram
        whole (or graph (map-of (map second (re-seq fenced (str markdown)))))
        cover (cover-parts head (seq steps))
        reasoning (remove #(or (step? %) (placed? %)) blocks)]
    [:div.walkthrough {"data-runway" ""}
     (when graph
       [:script {:type "application/json" :data-map ""} (script-json graph)])
     [:header.walk-hero
      (when (:title cover) [:h1 (h/raw (md/inline (:title cover)))])
      (when (:standfirst cover) [:div.walk-standfirst (cited-text (:standfirst cover))])
      (when (:prose cover) [:div.cited.walk-lede (cited-text (:prose cover))])
      (in-play whole steps)]
     (prose-row "What changed" (:changed sections))
     (risks-section (:risks sections))
     (prose-row "Outlook" (:outlook sections))
     (the-map graph steps by-n)
     (when (seq reasoning)
       [:section.walk-analysis.walk-band
        [:div.prose (cited-text (str/join "\n\n" (map str/trim reasoning)))]])
     (footer sections)]))

