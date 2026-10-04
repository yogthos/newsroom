(ns newsroom.analysis
  "The pure core of the desk's work before the briefing: no IO, no clock,
  no storage.

  The analyst used to get the day's reports raw and had to sort, weigh,
  read and connect them in the one pass that writes the briefing. The desk
  does that in stages first, each a narrower model call:

  1. Sorting. The day's reports, after the near-duplicates are collapsed,
     are grouped into stories: reports on the same event from different
     sides are one story, related events stay apart. Each story gets a
     neutral title, an importance from 1 to 10 and a status, and reports
     that aren't news at all are dropped as junk. The reports of a story
     share its storyline and are ranked together (newsroom.trends).
  2. Dossiers. The main stories' reports are read in full where the page
     allows, and each story gets a dossier written from them alone: what
     happened, the salient facts with their citations, the actors with
     their stated positions and their interests, where accounts disagree,
     how outlets frame it, what's missing, the forces it is an instance
     of, and why it matters.
  3. The map. From the dossiers, the trends running through the day, each
     tied to the stories that show it and to the trend it continues from
     the days before, and the links of cause and effect between stories.
     The links are drawn as the briefing's graph.

  The briefing is then written from the dossiers and the map, with the
  numbered sources to cite. Every stage is optional, and a stage that
  fails leaves the briefing to be written from what there is."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [newsroom.notes :as notes]))

;; --- answers ---------------------------------------------------------------------

(defn json-object
  "The JSON object in `answer`, read with string keys, or nil. A fence or
  prose around it is ignored."
  [answer]
  (let [s (str answer)
        from (str/index-of s "{")
        to (str/last-index-of s "}")]
    (when (and from to (< from to))
      (let [parsed (try (json/read-str (subs s from (inc to))) (catch Throwable _ nil))]
        (when (map? parsed) parsed)))))

(defn- clip [s n]
  (let [s (str/trim (str s))]
    (if (> (count s) n) (str (subs s 0 (dec n)) "…") s)))

(defn- text [v n]
  (when (or (string? v) (number? v))
    (let [s (clip v n)]
      (when-not (str/blank? s) s))))

(defn- texts [v n per]
  (->> (if (sequential? v) v [v]) (keep #(text % per)) distinct (take n) vec))

(defn- maps [v] (filterv map? (if (sequential? v) v [])))

(defn- numbers
  "The numbers `v` names, 3, \"3\", \"[3]\" or a list of them."
  [v]
  (->> (if (sequential? v) v [v])
       (keep #(cond (integer? %) (long %)
                    (string? %) (some-> (re-find #"\d+" %) parse-long)))
       distinct
       vec))

(def statuses notes/statuses)

(def status-of notes/status-of)

(defn- importance-of [v]
  (let [n (cond (number? v) (long v)
                (string? v) (some-> (re-find #"\d+" v) parse-long))]
    (when n (max 1 (min 10 n)))))

;; --- 1. sorting the reports into stories -------------------------------------------

(def ^:private grouping-summary-chars
  "How much of a report's summary the sorting is shown: enough to tell one
  event from another."
  200)

(defn- grouping-line [i {:keys [title source summary story]} notes]
  (str "[" i "] " title (when-not (str/blank? source) (str " (" source ")"))
       (when-not (str/blank? summary) (str "\n    " (clip summary grouping-summary-chars)))
       (when-let [t (:title (get notes story))]
         (str "\n    Continues the storyline: " t))))

(defn grouping-prompt
  "The prompt that has the desk sort `items`, numbered from 1 in order,
  into stories. `notes` are the notes on the storylines the items
  continue, {story note}."
  [day items notes]
  (str "You sort the reports gathered for a daily news briefing into stories. Today is " day ".\n\n"
       "Below are the day's reports, numbered. Many outlets report the same events, and one event "
       "reaches the news through several reports at once, each on a different side of it: a central "
       "bank's decision, the market's reaction to it and a minister's answer are one story. Group the "
       "reports into stories.\n\n"
       "Put reports in one story when they describe the same specific event, situation or decision and "
       "its direct consequences, so that reading one without the others would leave it incomplete. "
       "Keep apart reports that are only related, like two trade disputes with different countries, or "
       "a trade deal and the protests against it at home, which can each be told on their own. A story "
       "of one report is fine.\n\n"
       "For each story give:\n"
       "- a title that is factual and neutral, naming who did what and where, with no opinion in it;\n"
       "- its importance from 1 to 10 for a reader who wants to understand the world: 10 when it moves "
       "the balance of power between states, war and peace, trade, markets, prices, production, energy "
       "or a major country's political direction, 1 for a local, human-interest, celebrity, sport or "
       "lifestyle item with no wider consequence;\n"
       "- its status: " (str/join ", " statuses) ".\n\n"
       "Leave out as junk only the reports that aren't news at all: a section or index page, an advert, "
       "a login or error page, a quiz, a list of links, a headline with nothing behind it. Put every "
       "other report in exactly one story.\n\n"
       "Answer with JSON only, in this shape:\n"
       "{\"stories\": [{\"title\": \"...\", \"reports\": [3, 7, 12], \"importance\": 7, "
       "\"status\": \"escalating\"}], \"junk\": [5]}\n\n"
       "Reports:\n\n"
       (str/join "\n\n" (map-indexed (fn [i item] (grouping-line (inc i) item notes)) items))))

(defn parse-grouping
  "The stories of the desk's sorting `answer` for `n` reports:
  {:groups [{:title :members :importance :status}] :junk #{i}}, the
  reports by their number. A report is in the first story that names it,
  a number out of range is dropped, and a report named as junk and in a
  story is kept. nil when the answer has no stories."
  [answer n]
  (let [m (json-object answer)
        in-range? #(<= 1 % n)]
    (when (sequential? (get m "stories"))
      (let [[groups placed]
            (reduce (fn [[groups placed] g]
                      (let [members (->> (numbers (or (get g "reports") (get g "items")))
                                         (filter in-range?)
                                         (remove placed)
                                         vec)]
                        (if (seq members)
                          [(conj groups {:title (text (get g "title") 160)
                                         :members members
                                         :importance (importance-of (get g "importance"))
                                         :status (status-of (get g "status"))})
                           (into placed members)]
                          [groups placed])))
                    [[] #{}]
                    (maps (get m "stories")))]
        {:groups groups
         :junk (set (remove placed (filter in-range? (numbers (get m "junk")))))}))))

(defn- shared-story
  "The storyline most of `members` continue, the first of them on a tie,
  or nil when none continues one."
  [members]
  (let [stories (keep :story members)]
    (when (seq stories)
      (let [freq (frequencies stories)
            most (apply max (vals freq))]
        (first (filter #(= most (freq %)) stories))))))

(defn apply-grouping
  "The `items` sorted as the desk's `grouping` says: junk left out, and
  each item with its story's :group, a key like G3, :group-title,
  :importance and :status. The reports of a story share the storyline most
  of them continue, when any does. An item no story names is a story of
  its own."
  [items {:keys [groups junk]}]
  (let [items (vec items)
        named (mapcat :members groups)
        loose (remove (set named) (range 1 (inc (count items))))
        groups (concat groups (map (fn [i] {:members [i]}) loose))]
    (->> groups
         (remove (fn [g] (every? junk (:members g))))
         (map-indexed
          (fn [k {:keys [title members importance status]}]
            (let [members (mapv #(nth items (dec %)) (remove junk members))
                  story (shared-story members)
                  key (str "G" (inc k))]
              (mapv (fn [item]
                      (cond-> (assoc item :group key :group-title (or title (:title (first members))))
                        importance (assoc :importance importance)
                        status (assoc :status status)
                        story (assoc :story story)))
                    members))))
         (apply concat)
         vec)))

(defn story-ids
  "The day's numbered `sources` with a storyline each: the one it
  continues, else the one its story starts, named by the day and the
  number of the story's first source, so the reports of a new story start
  one storyline together."
  [sources day]
  (let [started (reduce (fn [m {:keys [group n]}]
                          (if (and group (not (contains? m group))) (assoc m group (str day "/" n)) m))
                        {}
                        sources)
        continued (reduce (fn [m {:keys [group story]}]
                            (if (and group story (not (contains? m group))) (assoc m group story) m))
                          {}
                          sources)]
    (mapv (fn [{:keys [group story n] :as s}]
            (assoc s :story (or story (get continued group) (get started group) (str day "/" n))))
          sources)))

(defn story-key
  "The key of the story a numbered source belongs to: its :group, else
  its own, named by its number."
  [s]
  (or (:group s) (str "S" (:n s))))

(defn stories
  "The day's numbered `sources` as stories, in order: {:key :title :status
  :importance :story :sources}. A source with no :group is a story of its
  own."
  [sources]
  (let [keyed (map #(assoc % ::key (story-key %)) sources)
        by-key (group-by ::key keyed)]
    (vec (for [k (distinct (map ::key keyed))
               :let [members (mapv #(dissoc % ::key) (get by-key k))
                     lead (first members)]]
           {:key k
            :title (or (:group-title lead) (:title lead))
            :status (:status lead)
            :importance (:importance lead)
            :story (:story lead)
            :sources members}))))

;; --- 2. dossiers -------------------------------------------------------------------

(def dossier-batch
  "How many stories one dossier call writes up: few enough that each gets
  the model's full attention, enough that the calls stay few."
  4)

(def ^:private reports-per-dossier
  "The most reports a dossier is written from."
  6)

(defn readers
  "The sources of `story` worth reading in full: up to `n`, one a source,
  the most widely carried first, which is the order they come in."
  [story n]
  (->> (:sources story)
       (reduce (fn [[seen out] s]
                 (let [o (or (:source s) (:url s))]
                   (if (contains? seen o) [seen out] [(conj seen o) (conj out s)])))
               [#{} []])
       second
       (take n)
       vec))

(defn- report-block [{:keys [n title source published text summary also]}]
  (str "[" n "] " title (when-not (str/blank? source) (str " (" source ")"))
       (when published (str ", " published))
       (when (seq also)
         (str "\nAlso carried by " (str/join ", " (distinct (keep :source also)))))
       (cond
         (not (str/blank? text)) (str "\nFull text:\n" text)
         (not (str/blank? summary)) (str "\nSummary: " summary))))

(defn- note-background [note long-date]
  (when note
    (str "The storyline so far, from earlier days (background, not a source):\n"
         (:summary note)
         (str/join "" (for [f (take-last 3 (:facts note))]
                        (str "\n- " (long-date (:day f)) ": " (:text f)))))))

(defn- dossier-story-block [{:keys [key title status sources story]} notes long-date]
  (str "### " key ": " title "\n"
       (when status (str "Status as sorted: " status "\n"))
       (when-let [bg (note-background (get notes story) long-date)] (str bg "\n"))
       "Reports:\n\n"
       (str/join "\n\n" (map report-block (take reports-per-dossier sources)))))

(defn dossier-prompt
  "The prompt that has the desk write a dossier on each of `stories`, with
  the `notes` on their storylines as background."
  [day stories notes long-date]
  (str "You are the desk analyst who prepares the dossiers a daily briefing is written from. Today is "
       day ".\n\n"
       "For each story below you get its reports, numbered as the briefing will cite them, with the "
       "full text where it could be read and the outlet's summary where it couldn't. Write a dossier on "
       "each from the reports alone. Don't bring in what you know from elsewhere: the analyst who writes "
       "the briefing adds the history and the projections, and needs to know which is which. Where a "
       "story has notes from earlier days, use them to see what is new, not as a source.\n\n"
       "A dossier holds:\n"
       "- summary: two or three sentences on what happened and where the story stands.\n"
       "- status: " (str/join ", " statuses) ", judged by what the reports show.\n"
       "- domain: politics, economics or technology, whichever the story mostly moves.\n"
       "- label: the story in four words or fewer, for a node in a graph.\n"
       "- facts: the salient facts, the events, decisions, figures and dates that matter, each in one "
       "sentence citing the reports that carry it. Prefer what several reports agree on, and say so in the "
       "fact when only one outlet reports it, or only an outlet tied to a party to the story.\n"
       "- actors: the main actors, each with the position they state and the interests that drive them, "
       "what they stand to gain or lose materially.\n"
       "- disputes: where reports or actors give different accounts of the same thing, the issue and each "
       "claim with who makes it and its citation. Don't settle them here.\n"
       "- framing: how outlets frame the story differently, where they do, as outlet and angle.\n"
       "- gaps: what a reader needs to know that the reports don't say, each with a web search query that "
       "could find it in current reporting, or an empty query when no search would.\n"
       "- forces: the broader forces and trends the story is an instance of, such as debt piling up while "
       "rates stay high, trade splitting into blocs, or a technology shifting the balance of production, "
       "a short phrase each.\n"
       "- significance: a sentence or two on why it matters beyond its borders, or that it doesn't.\n\n"
       "Answer with JSON only, in this shape:\n"
       "{\"dossiers\": [{\"story\": \"G1\", \"summary\": \"...\", \"status\": \"escalating\", "
       "\"domain\": \"economics\", \"label\": \"...\", \"facts\": [{\"fact\": \"...\", \"cites\": [3, 5]}], "
       "\"actors\": [{\"name\": \"...\", \"position\": \"...\", \"interests\": \"...\"}], "
       "\"disputes\": [{\"issue\": \"...\", \"claims\": [{\"claim\": \"...\", \"by\": \"...\", \"cites\": [4]}]}], "
       "\"framing\": [{\"outlet\": \"...\", \"angle\": \"...\"}], "
       "\"gaps\": [{\"gap\": \"...\", \"query\": \"...\"}], \"forces\": [\"...\"], \"significance\": \"...\"}]}\n\n"
       "Stories:\n\n"
       (str/join "\n\n" (map #(dossier-story-block % notes long-date) stories))))

(def domains
  "The domains a story is drawn under in the graph."
  ["politics" "economics" "technology"])

(defn- domain-of [v]
  (let [s (some-> v str str/trim str/lower-case)]
    (cond
      (some #{s} domains) s
      (and s (re-find #"econom|market|trade|financ|energy" s)) "economics"
      (and s (re-find #"tech|science|ai\b" s)) "technology"
      :else "politics")))

(defn- read-dossier
  "A dossier from the answer's `d`, its citations kept to `ns`, the numbers
  of its story's sources. A fact that cites none of them is dropped."
  [d ns]
  (let [ns (set ns)
        cites #(filterv ns (numbers %))]
    {:summary (text (get d "summary") 800)
     :status (status-of (get d "status"))
     :domain (domain-of (get d "domain"))
     :label (text (get d "label") 60)
     :facts (vec (for [f (maps (get d "facts"))
                       :let [t (text (get f "fact") 400) cs (cites (or (get f "cites") (get f "cite")))]
                       :when (and t (seq cs))]
                   {:fact t :cites cs}))
     :actors (vec (for [a (maps (get d "actors"))
                        :let [name (text (get a "name") 100)]
                        :when name]
                    {:name name :position (text (get a "position") 300)
                     :interests (text (get a "interests") 300)}))
     :disputes (vec (for [x (maps (get d "disputes"))
                          :let [issue (text (get x "issue") 200)
                                claims (vec (for [c (maps (get x "claims"))
                                                  :let [t (text (get c "claim") 300)]
                                                  :when t]
                                              {:claim t :by (text (get c "by") 100)
                                               :cites (cites (get c "cites"))}))]
                          :when (and issue (seq claims))]
                      {:issue issue :claims claims}))
     :framing (vec (for [f (maps (get d "framing"))
                         :let [o (text (get f "outlet") 100) a (text (get f "angle") 200)]
                         :when (and o a)]
                     {:outlet o :angle a}))
     :gaps (vec (for [g (maps (get d "gaps"))
                      :let [gap (text (get g "gap") 300)]
                      :when gap]
                  {:gap gap :query (text (get g "query") 200)}))
     :forces (texts (get d "forces") 6 120)
     :significance (text (get d "significance") 500)}))

(defn parse-dossiers
  "The dossiers of the desk's `answer` on `stories`, {key dossier}. A
  dossier with no summary and no facts is no dossier."
  [answer stories]
  (let [by-key (into {} (map (juxt :key identity)) stories)]
    (into {}
          (keep (fn [d]
                  (let [k (str/trim (str (get d "story")))]
                    (when-let [story (by-key k)]
                      (let [dossier (read-dossier d (map :n (:sources story)))]
                        (when (or (:summary dossier) (seq (:facts dossier)))
                          [k dossier]))))))
          (maps (get (json-object answer) "dossiers")))))

(defn gap-queries
  "The searches the dossiers ask for, at most `n`, the main stories' first,
  each {:key :gap :query}, one a story until every story with a gap has
  had one."
  [stories dossiers n]
  (let [per-story (for [{:keys [key]} stories
                        :let [gaps (filter :query (:gaps (get dossiers key)))]
                        :when (seq gaps)]
                    (map #(assoc % :key key) gaps))]
    (->> (loop [lists per-story out []]
           (if (seq lists)
             (recur (remove empty? (map rest lists)) (into out (map first lists)))
             out))
         (take n)
         vec)))

;; --- 3. the map --------------------------------------------------------------------

(def directions ["strengthening" "weakening" "holding"])

(defn- direction-of [v]
  (let [s (some-> v str str/trim str/lower-case)]
    (or (some #{s} directions)
        (cond (nil? s) nil
              (re-find #"strong|grow|rising|gather|intens" s) "strengthening"
              (re-find #"weak|fad|eas|declin" s) "weakening"
              :else "holding"))))

(def confidences ["high" "medium" "low"])

(defn- source-numbers [story] (mapv :n (:sources story)))

(defn- cite-list [ns] (str "[" (str/join ", " ns) "]"))

(defn- map-story-block [{:keys [key title] :as story} dossier]
  (let [{:keys [label domain status summary forces facts]} dossier]
    (str "### " key ": " (or label title) " (" domain (when status (str ", " status)) ")\n"
         (when summary (str summary "\n"))
         (when (seq forces) (str "Forces: " (str/join "; " forces) "\n"))
         (str/join "\n" (for [{:keys [fact]} (take 5 facts)] (str "- " fact))))))

(defn- recent-trend-line [long-date {:keys [thread name direction summary day]}]
  (str "- " thread ": " name " (last seen " (long-date day)
       (when direction (str ", " direction)) ")"
       (when summary (str ". " summary))))

(defn connection-prompt
  "The prompt that has the desk map the trends running through the day's
  `stories`, written up in `dossiers`, and the links between them.
  `recent` are the trends of the days before, the latest of each thread,
  which a trend today can continue."
  [day stories dossiers recent long-date]
  (str "You map the day's news for a daily briefing. Today is " day ".\n\n"
       "Below are the dossiers on the day's main stories. Find the trends running through them and how "
       "the stories drive one another, across politics, economics, and science and technology.\n\n"
       "A trend is a force that shows in several stories at once, like debt piling up while rates stay "
       "high, trade splitting into blocs, an energy squeeze, a rising power pressing an established one, "
       "or a technology shifting the balance of production. Name each one plainly, list the stories that "
       "show it, say whether today's stories show it strengthening, weakening or holding, and say in two "
       "sentences what the force is and how today's stories show it. A trend that continues one of the "
       "recent days' gives that trend's id in \"continues\", so it can be followed from day to day, and "
       "keeps its name unless it has changed. A force that shows in only one story is not a trend.\n\n"
       "A link is a chain of cause and effect from one story to another: a sanction that tightens supply "
       "and pushes up a price, a rate rise that squeezes a government's budget. Give the mechanism in "
       "five words or fewer for a graph, like \"raises oil prices\", the chain in a sentence, and how "
       "sure the evidence makes you: high when the reports show the effect, medium when the mechanism is well known and its conditions are there, "
       "low when it's a possibility worth watching. Only link stories where you can name the mechanism. "
       "Links across domains matter most.\n\n"
       "Answer with JSON only, in this shape:\n"
       "{\"trends\": [{\"name\": \"...\", \"continues\": \"an id from the recent trends, or null\", "
       "\"direction\": \"strengthening\", \"stories\": [\"G1\", \"G4\"], \"summary\": \"...\"}], "
       "\"links\": [{\"from\": \"G1\", \"to\": \"G4\", \"mechanism\": \"...\", \"explanation\": \"...\", "
       "\"confidence\": \"high\"}]}\n\n"
       (when (seq recent)
         (str "Recent trends:\n\n" (str/join "\n" (map #(recent-trend-line long-date %) recent)) "\n\n"))
       "Stories:\n\n"
       (str/join "\n\n" (for [s stories :let [d (get dossiers (:key s))] :when d]
                          (map-story-block s d)))))

(defn parse-connections
  "The trends and links of the desk's `answer` over the stories keyed
  `keys`: {:trends [{:thread :name :direction :stories :summary}] :links
  [{:from :to :mechanism :explanation :confidence}]}. A trend continues the
  thread it names when that is one of `threads`, else starts its own,
  named by the day and its place; it needs a story of the day. A link
  needs two different stories."
  [answer keys threads day]
  (let [m (json-object answer)
        keys (set keys)
        threads (set threads)
        story-keys #(->> (if (sequential? %) % [%]) (map (fn [k] (str/trim (str k)))) (filter keys) distinct vec)]
    {:trends (vec (keep-indexed
                   (fn [i t]
                     (let [name (text (get t "name") 120)
                           stories (story-keys (get t "stories"))
                           continues (some-> (get t "continues") str str/trim)]
                       (when (and name (seq stories))
                         {:thread (if (contains? threads continues) continues (str day "/T" (inc i)))
                          :name name
                          :direction (direction-of (get t "direction"))
                          :stories stories
                          :summary (text (get t "summary") 600)})))
                   (maps (get m "trends"))))
     :links (vec (for [l (maps (get m "links"))
                       :let [from (str/trim (str (get l "from")))
                             to (str/trim (str (get l "to")))
                             mechanism (text (get l "mechanism") 60)]
                       :when (and (keys from) (keys to) (not= from to) mechanism)]
                   {:from from :to to :mechanism mechanism
                    :explanation (text (get l "explanation") 400)
                    :confidence (or (some #{(some-> (get l "confidence") str str/lower-case str/trim)} confidences)
                                    "medium")}))}))

;; --- the graph ---------------------------------------------------------------------

(def ^:private subgraphs
  [["politics" "pol" "Politics"]
   ["economics" "eco" "Economics"]
   ["technology" "tech" "Science and technology"]])

(defn- label-text
  "Text that can sit in a mermaid label: no brackets, quotes, pipes or
  anything else its syntax reads."
  [s n]
  (-> (str s)
      (str/replace #"\[\d+(,\s*\d+)*\]" "")
      (str/replace #"[\[\]{}()<>\"'`|#;:&]" " ")
      (str/replace #"\s+" " ")
      str/trim
      (clip n)))

(defn- words
  "The first `n` words of `s`."
  [s n]
  (str/join " " (take n (str/split (str/trim s) #"\s+"))))

(defn graph
  "The day's links drawn as a mermaid flowchart, the stories they join as
  nodes under a subgraph for each domain, a link the evidence is least
  sure of dashed. nil when there are no links."
  [stories dossiers links]
  (when (seq links)
    (let [linked (set (mapcat (juxt :from :to) links))
          nodes (vec (for [{:keys [key title]} stories
                           :when (contains? linked key)
                           :let [d (get dossiers key)]]
                       {:key key
                        :id (str/lower-case key)
                        :label (label-text (or (:label d) title) 40)
                        :domain (or (:domain d) "politics")}))
          id-of (into {} (map (juxt :key :id)) nodes)]
      (str "flowchart LR\n"
           (str/join ""
                     (for [[domain id title] subgraphs
                           :let [in (filter #(= domain (:domain %)) nodes)]
                           :when (seq in)]
                       (str "  subgraph " id "[\"" title "\"]\n"
                            (str/join "" (for [n in] (str "    " (:id n) "[\"" (:label n) "\"]\n")))
                            "  end\n")))
           (str/join "" (for [{:keys [from to mechanism confidence]} links
                              :when (and (id-of from) (id-of to))]
                          (str "  " (id-of from) (if (= "low" confidence) " -.->" " -->")
                               "|\"" (words (label-text mechanism 200) 6) "\"| " (id-of to) "\n")))))))

(defn with-graph
  "The briefing `answer` with the `graph` drawn at the start of its section
  on how things connect, else before its last section, else at its end.
  An answer that drew a graph of its own keeps it, and no graph leaves the
  answer as it is."
  [answer graph]
  (if (or (str/blank? graph) (str/includes? answer "```mermaid"))
    answer
    (let [block (str "```mermaid\n" graph "```")
          lines (str/split-lines answer)
          headings (keep-indexed (fn [i l] (when (re-find #"^##\s" l) i)) lines)
          connect (first (keep-indexed (fn [i l] (when (re-find #"(?i)^##\s.*connect" l) i)) lines))
          splice (fn [at middle] (str/join "\n" (concat (take at lines) middle (drop at lines))))]
      (cond
        connect (splice (inc connect) ["" block])
        (> (count headings) 1) (splice (last headings) [block ""])
        :else (str answer "\n\n" block)))))

;; --- what the analyst is given -----------------------------------------------------

(defn- dossier-text [{:keys [key title] :as story} {:keys [label status summary facts actors disputes
                                                          framing gaps forces significance]}
                     found]
  (str "### " (or label title) ": " title "\n"
       "Sources " (cite-list (source-numbers story)) (when status (str ". Status: " status)) "\n"
       (when summary (str "\n" summary "\n"))
       (when (seq facts)
         (str "\nFacts:\n" (str/join "\n" (for [{:keys [fact cites]} facts] (str "- " fact " " (cite-list cites)))) "\n"))
       (when (seq actors)
         (str "\nActors:\n"
              (str/join "\n" (for [{:keys [name position interests]} actors]
                               (str "- " name (when position (str ": " position))
                                    (when interests (str " Interests: " interests)))))
              "\n"))
       (when (seq disputes)
         (str "\nDisputed:\n"
              (str/join "\n" (for [{:keys [issue claims]} disputes]
                               (str "- " issue ": "
                                    (str/join "; " (for [{:keys [claim by cites]} claims]
                                                     (str (when by (str by ": ")) claim
                                                          (when (seq cites) (str " " (cite-list cites)))))))))
              "\n"))
       (when (seq framing)
         (str "\nFraming:\n" (str/join "\n" (for [{:keys [outlet angle]} framing] (str "- " outlet ": " angle))) "\n"))
       (when (seq gaps)
         (str "\nNot in the reports:\n"
              (str/join "\n" (for [{:keys [gap]} gaps
                                   :let [fills (filter #(= gap (:gap %)) found)]]
                               (str "- " gap
                                    (when (seq fills)
                                      (str " Searched for, and found " (cite-list (map :n fills)))))))
              "\n"))
       (when (seq forces) (str "\nForces: " (str/join "; " forces) "\n"))
       (when significance (str "\nWhy it matters: " significance "\n"))))

(defn analysis-block
  "What the analyst is told of the desk's work: the trends and links of
  the map, with the stories by their source numbers, then the dossiers.
  `found` are the sources the gap searches found, each with its :key and
  :gap. nil when there are no dossiers."
  [stories dossiers {:keys [trends links]} found graph?]
  (when (seq dossiers)
    (let [by-key (into {} (map (juxt :key identity)) stories)
          name-of (fn [k] (let [s (by-key k) d (get dossiers k)]
                            (str (or (:label d) (:title s)) " " (cite-list (source-numbers s)))))
          found-by (group-by :key found)]
      (str "## The desk's analysis\n\n"
           "Before you write, the desk sorted the day's reports into stories, read the main stories' reports "
           "and wrote a dossier on each from them alone, then mapped the trends running through them and how "
           "they drive one another. The dossiers' facts cite the numbered sources below, and so should you. "
           "Take what happened from the dossiers, weigh the disputed accounts and say which way the evidence "
           "leans, and build how things connect on the map, checking each link against the facts."
           (when graph? " The graph of the links is drawn for you, so don't draw one.")
           "\n\n"
           (when (seq trends)
             (str "### Trends\n\n"
                  (str/join "\n" (for [{:keys [name direction stories summary]} trends]
                                   (str "- **" name "**" (when direction (str " (" direction ")"))
                                        ", in " (str/join ", " (map name-of stories))
                                        (when summary (str ": " summary)))))
                  "\n\n"))
           (when (seq links)
             (str "### How the stories connect\n\n"
                  (str/join "\n" (for [{:keys [from to mechanism explanation confidence]} links]
                                   (str "- " (name-of from) " → " (name-of to) ": " mechanism
                                        (when explanation (str ". " explanation))
                                        " (" confidence " confidence)")))
                  "\n\n"))
           "### Dossiers\n\n"
           (str/join "\n" (for [s stories :let [d (get dossiers (:key s))] :when d]
                            (dossier-text s d (found-by (:key s)))))))))

(defn trends-block
  "The day's trends as the researcher looking for precedents is told
  them, with their stories by source number. nil when there are none."
  [stories trends]
  (when (seq trends)
    (let [by-key (into {} (map (juxt :key identity)) stories)]
      (str "The trends the desk found running through the day:\n\n"
           (str/join "\n" (for [{:keys [name direction stories summary]} trends]
                            (str "- " name (when direction (str " (" direction ")"))
                                 ", in stories " (cite-list (mapcat #(source-numbers (by-key %)) stories))
                                 (when summary (str ": " summary)))))))))

(defn dossier-notes
  "The dossiers by storyline, as the notes are updated from them: each
  story's facts, with the reports they cite. {story [{:fact :cites}]}"
  [stories dossiers]
  (into {} (for [{:keys [key story]} stories
                 :let [d (get dossiers key)]
                 :when (and story (seq (:facts d)))]
             [story (:facts d)])))
