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
            [newsroom.news :as news]
            [newsroom.notes :as notes]
            [newsroom.retrospective :as retrospective]))

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

(defn- small-number
  "The whole number `v` is or names, 3 or \"[3]\", when it is small enough
  to mean anything here, else nil."
  [v]
  (cond (number? v) (when (< -1e9 v 1e9) (long v))
        (string? v) (some-> (re-find #"\d+" v) (as-> d (when (<= (count d) 9) (parse-long d))))))

(defn- numbers
  "The numbers `v` names, 3, \"3\", \"[3]\" or a list of them."
  [v]
  (->> (if (sequential? v) v [v])
       (keep #(when (or (integer? %) (string? %)) (small-number %)))
       distinct
       vec))

(def statuses notes/statuses)

(def status-of notes/status-of)

(defn- importance-of [v]
  (when-let [n (small-number v)]
    (max 1 (min 10 n))))

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
  story is kept. nil when the answer sorts no report into a story."
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
        (when (seq groups)
          {:groups groups
           :junk (set (remove placed (filter in-range? (numbers (get m "junk")))))})))))

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

(defn dossier-sources
  "The sources of `story` its dossier is written from."
  [story]
  (take reports-per-dossier (:sources story)))

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
       (str/join "\n\n" (map report-block (dossier-sources {:sources sources})))))

(def ^:private max-expectations
  "The most of the outlets' expectations a dossier keeps."
  2)

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
       "- significance: a sentence or two on why it matters beyond its borders, or that it doesn't.\n"
       "- expectations: at most " max-expectations " forecasts the reports themselves put forward on how "
       "the story will go, in their own voice or through the analysts and experts they choose to present, "
       "each a claim that can later be found true or false, with its subject, one of "
       (str/join ", " retrospective/subjects) ", the day, YYYY-MM-DD, by which it should be known, who "
       "makes it, and the report that carries it. The outlet is judged by these, so take only its own "
       "calls and those of the analysts and experts it presents: an actor's statement of what it will "
       "do is a position, a party to the story forecasting how it goes is pressing its interests, and a "
       "timetable or a routine next step is no forecast.\n\n"
       "Answer with JSON only, in this shape:\n"
       "{\"dossiers\": [{\"story\": \"G1\", \"summary\": \"...\", \"status\": \"escalating\", "
       "\"domain\": \"economics\", \"label\": \"...\", \"facts\": [{\"fact\": \"...\", \"cites\": [3, 5]}], "
       "\"actors\": [{\"name\": \"...\", \"position\": \"...\", \"interests\": \"...\"}], "
       "\"disputes\": [{\"issue\": \"...\", \"claims\": [{\"claim\": \"...\", \"by\": \"...\", \"cites\": [4]}]}], "
       "\"framing\": [{\"outlet\": \"...\", \"angle\": \"...\"}], "
       "\"gaps\": [{\"gap\": \"...\", \"query\": \"...\"}], \"forces\": [\"...\"], \"significance\": \"...\", "
       "\"expectations\": [{\"claim\": \"...\", \"subject\": \"economy\", \"due\": \"YYYY-MM-DD\", "
       "\"by\": \"...\", \"cites\": [3]}]}]}\n\n"
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

(defn- expectation
  "An outlet's expectation from the answer's `e`, credited to the outlet of
  the first of `sources` it cites, or nil when it cites none of them."
  [e by-n cites]
  (let [claim (text (get e "claim") 400)
        cs (cites (get e "cites"))
        s (some by-n cs)]
    (when (and claim s)
      {:claim claim
       :by (text (get e "by") 100)
       :subject (retrospective/subject-of (get e "subject"))
       :due (text (get e "due") 20)
       :cites cs
       :source (:source s)
       :outlet (news/origin s)
       :url (:url s)})))

(defn- read-dossier
  "A dossier from the answer's `d`, its citations kept to `sources`, its
  story's. A fact that cites none of them is dropped."
  [d sources]
  (let [by-n (into {} (map (juxt :n identity)) sources)
        cites #(filterv by-n (numbers %))]
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
     :significance (text (get d "significance") 500)
     :expectations (vec (take max-expectations (keep #(expectation % by-n cites) (maps (get d "expectations")))))}))

(defn parse-dossiers
  "The dossiers of the desk's `answer` on `stories`, {key dossier}. A
  dossier with no summary and no facts is no dossier."
  [answer stories]
  (let [by-key (into {} (map (juxt :key identity)) stories)]
    (into {}
          (keep (fn [d]
                  (let [k (str/trim (str (get d "story")))]
                    (when-let [story (by-key k)]
                      (let [dossier (read-dossier d (:sources story))]
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

(def effects ["strengthens" "weakens"])

(def lags ["days" "weeks" "months" "years"])

(def channels
  "The channels a link of the map runs through."
  ["trade" "prices" "energy" "capital" "supply chains" "technology" "security" "alliances"
   "domestic politics" "policy" "labour" "demand"])

(def likelihoods ["unlikely" "possible" "likely" "very likely"])

(defn- one-of [options v]
  (some #{(some-> v str str/trim str/lower-case)} options))

(defn- source-numbers [story] (mapv :n (:sources story)))

(defn- cite-list [ns] (str "[" (str/join ", " ns) "]"))

(defn- map-story-block [{:keys [key title] :as story} dossier]
  (let [{:keys [label status summary forces facts significance actors]} dossier]
    (str "### " key ": " (or label title) (when status (str " (" status ")")) "\n"
         (when summary (str summary "\n"))
         (when (seq forces) (str "Forces: " (str/join "; " forces) "\n"))
         (when significance (str "Why it matters: " significance "\n"))
         (when (seq actors)
           (str "Actors: " (str/join "; " (for [{:keys [name interests]} (take 4 actors)]
                                            (str name (when interests (str ", who stands to " interests)))))
                "\n"))
         (str/join "\n" (for [{:keys [fact]} (take 5 facts)] (str "- " fact))))))

(defn- recent-trend-line [long-date {:keys [thread name direction summary day]}]
  (str "- " thread ": " name " (last seen " (long-date day)
       (when direction (str ", " direction)) ")"
       (when summary (str ". " summary))))

(defn connection-prompt
  "The prompt that has the desk map how the day's `stories`, written up in
  `dossiers`, move the world: the trends they push on, the structural
  forces those feed, and the links of cause and effect between all three,
  with the risks and the outlook the map shows. `recent` are the forces
  and trends of the days before, the latest of each thread, which today's
  can continue."
  [day stories dossiers recent long-date]
  (let [{forces "force" trends "trend"} (group-by #(or (:level %) "trend") recent)]
    (str "You map how the day's news moves the world, for a daily briefing read by people who act on it, "
         "investors and traders among them. Today is " day ".\n\n"
         "Below are the dossiers on the day's main stories. Don't connect stories to one another because they "
         "share a country, an actor or a topic. Build a causal map in three levels, from the bottom up:\n\n"
         "1. Stories: today's events, the dossiers below, by their keys.\n"
         "2. Trends: the pressures that run for weeks or months and that today's stories push on, like rerouted "
         "shipping raising freight costs, a chip export ban, a central bank holding rates high, a government "
         "losing its majority. A trend is a process that changes some quantity, capacity, incentive or balance, "
         "named so the reader can tell which way it runs. A trend is wider than any one story: one that only "
         "restates a story, like \"Spain's snap election\", is that story, so link the story straight to what it "
         "moves instead. The best trends gather several stories, or follow from them. Then follow each chain past "
         "what the reports say, to the consequences its mechanisms make likely: oil, freight and insurance prices "
         "after a chokepoint is attacked, inflation expectations and the rate path after energy costs rise, a "
         "currency and bond spreads after a fiscal turn, a supplier's market share after an export ban. These "
         "implied trends are what the reader has to act on before the news reports them, so the map needs several "
         "of them. An implied trend has no stories of its own: it is reached through the trends that cause it, "
         "and it is marked implied.\n"
         "3. Forces: the few structural forces the trends feed, which play out over years, like trade splitting "
         "into blocs, energy security overriding cost, the technology stacks of the US and China splitting apart, "
         "debt piling up while rates stay high, or open models eroding the margins of closed ones. Name two to "
         "four, each fed by more than one trend or story. A worry that only one story raises is no structural "
         "force.\n\n"
         "A force or trend that continues one of the recent days' gives its id in \"continues\", so it can be "
         "followed from day to day, and keeps its name unless it has changed.\n\n"
         "Then the links, each a step of cause and effect:\n"
         "- story to trend, or to a force directly: what the event does to the pressure.\n"
         "- trend to trend: one pressure driving another, which is where the chains are, like export controls "
         "pushing China to build its own chips, which splits the tech stacks; tariffs rerouting trade, which "
         "raises input prices, which keeps inflation up; open models matching closed ones, which squeezes what "
         "the closed labs can charge, which reprices the companies betting on them.\n"
         "- trend to force: how the pressure moves the structural force.\n"
         "- force to trend, sparingly: a force that bears back on a pressure, closing a loop.\n\n"
         "Every link must name the channel it runs through and the mechanism, what changes in the target and "
         "why: a price, a supply, a cost, a capability, an incentive, a constraint, a balance of power, a "
         "coalition. \"Both involve Iran\" or \"adds to tensions\" is no mechanism. If you can't say what "
         "changes, leave the link out. Say whether the link strengthens or weakens its target, how soon it bites, "
         "and how sure you are: high when the reports show the effect, medium when the mechanism is well "
         "established and its conditions hold, low when it is a possibility to watch. Cross-domain links, a war "
         "moving a price, a price moving a vote, a technology moving the balance between states, matter most.\n\n"
         "Follow the main chains at least two steps past the news, from the event to the pressure it adds, to "
         "what that pressure does to prices, supply, budgets, votes or capabilities, and on to the force. Every "
         "story you use must feed a trend or a force, and every trend must reach a force through the links. Leave "
         "out stories that move nothing beyond themselves. Keep the map readable: about eight to sixteen trends "
         "and forces together.\n\n"
         "Then the risks: the three to five ways the map could break against the reader in the coming weeks, "
         "each with what could happen, how likely it is (" (str/join ", " likelihoods) "), when, what it would "
         "hit, markets, sectors, prices, currencies or governments, with the direction, and the signpost that "
         "would show it is happening, as concrete as a level, a vote or a date. And the outlook: the base case in "
         "two sentences, what would confirm it and what would overturn it.\n\n"
         "Answer with JSON only, in this shape:\n"
         "{\"forces\": [{\"id\": \"F1\", \"name\": \"six words or fewer\", \"continues\": \"an id from the recent forces, or null\", "
         "\"direction\": \"" (str/join "|" directions) "\", \"summary\": \"what the force is and how today moved "
         "it, two sentences\"}],\n"
         " \"trends\": [{\"id\": \"T1\", \"name\": \"six words or fewer\", \"continues\": \"an id from the recent trends, or null\", "
         "\"direction\": \"strengthening\", \"stories\": [\"G1\"], \"implied\": false, \"summary\": \"...\"}, "
         "{\"id\": \"T2\", \"name\": \"...\", \"continues\": null, \"direction\": \"strengthening\", \"stories\": [], "
         "\"implied\": true, \"summary\": \"...\"}],\n"
         " \"links\": [{\"from\": \"G1\", \"to\": \"T1\", \"effect\": \"" (str/join "|" effects) "\", "
         "\"channel\": \"" (str/join "|" channels) "\", \"mechanism\": \"five words or fewer\", "
         "\"explanation\": \"one sentence: A changes X, so B ...\", \"lag\": \"" (str/join "|" lags) "\", "
         "\"confidence\": \"" (str/join "|" confidences) "\"}],\n"
         " \"risks\": [{\"risk\": \"...\", \"likelihood\": \"possible\", \"horizon\": \"weeks\", \"exposed\": \"...\", "
         "\"signpost\": \"...\", \"nodes\": [\"T2\"]}],\n"
         " \"outlook\": {\"base\": \"...\", \"confirm\": \"...\", \"overturn\": \"...\"}}\n\n"
         (when (seq forces)
           (str "Recent forces:\n\n" (str/join "\n" (map #(recent-trend-line long-date %) forces)) "\n\n"))
         (when (seq trends)
           (str "Recent trends:\n\n" (str/join "\n" (map #(recent-trend-line long-date %) trends)) "\n\n"))
         "Stories:\n\n"
         (str/join "\n\n" (for [s stories :let [d (get dossiers (:key s))] :when d]
                            (map-story-block s d))))))

(defn- reached
  "The stories whose chains of `links` reach each node, {id [key]}, from
  the stories each node shows itself, `own`, in the order they are met."
  [own links]
  (loop [m own]
    (let [m' (reduce (fn [m {:keys [from to]}]
                       (let [have (get m to [])
                             more (remove (set have) (get m from))]
                         (if (seq more) (assoc m to (into have more)) m)))
                     m links)]
      (if (= m m') m (recur m')))))

(defn parse-connections
  "The map of the desk's `answer` over the stories keyed `keys`: {:forces
  [{:id :thread :level :name :direction :summary :stories}] :trends [{:id
  :thread :level :name :direction :stories :implied :summary}] :links
  [{:from :to :effect :channel :mechanism :explanation :lag :confidence}]
  :risks [{:risk :likelihood :horizon :exposed :signpost :nodes}]
  :outlook {:base :confirm :overturn}}.

  A force or trend continues the thread it names when that is one of
  `threads`, else starts its own, named by the day and its id. A trend
  needs a story of the day, or to be implied and reached by a link. A
  link needs a mechanism and two different nodes, stories, trends or
  forces. The :stories of an implied trend and of a force are the stories
  whose chains reach it. A map in the older shape, trends over stories
  and links between stories, reads the same way."
  [answer keys threads day]
  (let [m (json-object answer)
        keys (set keys)
        threads (set threads)
        story-keys #(->> (if (sequential? %) % [%]) (map (fn [k] (str/trim (str k)))) (filter keys) distinct vec)
        id-of (fn [x prefix i] (or (some-> (get x "id") str str/trim not-empty) (str prefix (inc i))))
        thread-of (fn [x id] (let [c (some-> (get x "continues") str str/trim)]
                               (if (contains? threads c) c (str day "/" id))))
        forces (vec (keep-indexed
                     (fn [i f]
                       (when-let [name (text (get f "name") 120)]
                         (let [id (id-of f "F" i)]
                           {:id id :thread (thread-of f id) :level "force" :name name
                            :direction (direction-of (get f "direction"))
                            :summary (text (get f "summary") 600)})))
                     (maps (get m "forces"))))
        trends (vec (keep-indexed
                     (fn [i t]
                       (when-let [name (text (get t "name") 120)]
                         (let [id (id-of t "T" i)]
                           {:id id :thread (thread-of t id) :level "trend" :name name
                            :direction (direction-of (get t "direction"))
                            :stories (story-keys (get t "stories"))
                            :implied (true? (get t "implied"))
                            :summary (text (get t "summary") 600)})))
                     (maps (get m "trends"))))
        ;; a trend with stories stands; an implied one only once a link reaches it
        standing (set (concat keys (map :id forces) (map :id (filter (comp seq :stories) trends))))
        candidates (into standing (map :id trends))
        links (vec (for [l (maps (get m "links"))
                         :let [from (str/trim (str (get l "from")))
                               to (str/trim (str (get l "to")))
                               mechanism (text (get l "mechanism") 60)]
                         :when (and (candidates from) (candidates to) (not= from to) mechanism)]
                     {:from from :to to :mechanism mechanism
                      :effect (or (one-of effects (get l "effect")) "strengthens")
                      :channel (text (get l "channel") 30)
                      :explanation (text (get l "explanation") 400)
                      :lag (one-of lags (get l "lag"))
                      :confidence (or (one-of confidences (get l "confidence")) "medium")}))
        ;; the nodes reached from what stands, following the links
        live (loop [live standing]
               (let [more (into live (keep (fn [{:keys [from to]}] (when (live from) to))) links)]
                 (if (= more live) live (recur more))))
        trends (filterv (fn [{:keys [id stories implied]}] (and (live id) (or (seq stories) implied))) trends)
        nodes (into keys (concat (map :id forces) (map :id trends)))
        links (filterv (fn [{:keys [from to]}] (and (nodes from) (nodes to))) links)
        reach (reached (merge (into {} (map (fn [k] [k [k]])) keys)
                              (into {} (map (juxt :id :stories)) trends))
                       links)
        with-reach (fn [x] (assoc x :stories (vec (filter keys (get reach (:id x) (:stories x))))))]
    {:forces (mapv with-reach forces)
     :trends (mapv #(if (:implied %) (with-reach %) %) trends)
     :links links
     :risks (vec (for [r (maps (get m "risks"))
                       :let [risk (text (get r "risk") 300)]
                       :when risk]
                   {:risk risk
                    :likelihood (one-of likelihoods (get r "likelihood"))
                    :horizon (text (get r "horizon") 40)
                    :exposed (text (get r "exposed") 300)
                    :signpost (text (get r "signpost") 300)
                    :nodes (vec (filter nodes (map #(str/trim (str %)) (if (sequential? (get r "nodes")) (get r "nodes") []))))}))
     :outlook (let [o (get m "outlook")]
                (when (map? o)
                  (let [o {:base (text (get o "base") 600) :confirm (text (get o "confirm") 400)
                           :overturn (text (get o "overturn") 400)}]
                    (when (:base o) o))))}))

;; --- the graph ---------------------------------------------------------------------

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

(defn- arrow
  "The mermaid arrow for a link: bold where the reports show the effect,
  dotted where it is a possibility, ending in a cross where it weakens."
  [{:keys [confidence effect]}]
  (let [x? (= "weakens" effect)]
    (case confidence
      "high" (if x? "==x" "==>")
      "low" (if x? "-.-x" "-.->")
      (if x? "--x" "-->"))))

(def ^:private legend
  "How to read the graph, shown under it."
  (str "Read it from the bottom up: today's news in grey, the pressures it pushes on, and the structural "
       "forces on top. A dashed box is a consequence the reports don't show yet. A bold arrow is an effect "
       "the reports show, a dotted one a possibility, and one ending in a cross weakens what it points to."))

(def ^:private graph-stories
  "The most stories the graph draws, the main ones: more and the chains
  above them are lost in the lines."
  10)

(defn graph
  "The day's map drawn as a mermaid flowchart from the bottom up: the main
  stories, the trends they push on, and the structural forces those feed
  on top. Only what a link touches is drawn. The levels are told apart by
  their classes, for the page to style, an implied trend, one the reports
  don't show yet, by its own: subgraphs for them would route every line
  around the boxes. The links between trends and forces carry their
  mechanism, and a story's link is drawn bare, since the reasoning is in
  the chains above it. nil when there are no links."
  [stories dossiers {:keys [forces trends links]}]
  (let [shown (set (take graph-stories (keep :key stories)))
        story? (set (keep :key stories))
        links (filterv (fn [{:keys [from to]}]
                         (and (or (not (story? from)) (shown from)) (or (not (story? to)) (shown to))))
                       links)]
    (when (seq links)
      (let [linked (set (mapcat (juxt :from :to) links))
            node (fn [id label] {:key id :id (str/lower-case id) :label (label-text label 60)})
            story-nodes (vec (for [{:keys [key title]} stories
                                   :when (contains? linked key)
                                   :let [d (get dossiers key)]]
                               (node key (or (:label d) title))))
            trend-nodes (vec (for [t trends :when (contains? linked (:id t))]
                               (assoc (node (:id t) (:name t)) :implied (:implied t))))
            force-nodes (vec (for [f forces :when (contains? linked (:id f))] (node (:id f) (:name f))))
            id-of (into {} (map (juxt :key :id)) (concat story-nodes trend-nodes force-nodes))
            nodes (fn [ns] (str/join "" (for [n ns] (str "  " (:id n) "[\"" (:label n) "\"]\n"))))
            classed (fn [class ns] (when (seq ns) (str "  class " (str/join "," (map :id ns)) " " class "\n")))]
        (str "flowchart BT\n"
             "  %% caption: " legend "\n"
             (nodes force-nodes)
             (nodes trend-nodes)
             (nodes story-nodes)
             (str/join "" (for [{:keys [from to mechanism] :as l} links
                                :when (and (id-of from) (id-of to))]
                            (str "  " (id-of from) " " (arrow l)
                                 (when-not (story? from)
                                   (str "|\"" (words (label-text mechanism 200) 6) "\"|"))
                                 " " (id-of to) "\n")))
             (classed "force" force-nodes)
             (classed "trend" (remove :implied trend-nodes))
             (classed "implied" (filter :implied trend-nodes))
             (classed "news" story-nodes))))))

(defn with-graph
  "The briefing `answer` with the `graph` drawn at the start of its
  analysis, or of its section on how things connect, else before its last
  section, else at its end.
  An answer that drew a graph of its own keeps it, and no graph leaves the
  answer as it is."
  [answer graph]
  (if (or (str/blank? graph) (str/includes? answer "```mermaid"))
    answer
    (let [block (str "```mermaid\n" graph "```")
          lines (str/split-lines answer)
          headings (keep-indexed (fn [i l] (when (re-find #"^##\s" l) i)) lines)
          connect (first (keep-indexed (fn [i l] (when (re-find #"(?i)^##\s.*(analysis|connect)" l) i)) lines))
          splice (fn [at middle] (str/join "\n" (concat (take at lines) middle (drop at lines))))]
      (cond
        connect (splice (inc connect) ["" block])
        (> (count headings) 1) (splice (last headings) [block ""])
        :else (str answer "\n\n" block)))))

;; --- what the analyst is given -----------------------------------------------------

(defn- track-record
  "An outlet's record on a subject, as the analyst reads it next to what
  the outlet expects."
  [subject {:keys [judged held partly closeness]}]
  (if judged
    (str "Its record on " subject ": " held " of " judged " held"
         (when (pos? partly) (str ", " partly " partly")) ", closeness " closeness ".")
    (str "No record on " subject " yet.")))

(defn- dossier-text [{:keys [key title] :as story} {:keys [label status summary facts actors disputes
                                                          framing gaps forces significance expectations]}
                     found records]
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
       (when (seq expectations)
         (str "\nExpected:\n"
              (str/join "\n" (for [{:keys [claim by cites subject source outlet]} expectations]
                               (str "- " source (when by (str " (" by ")")) ": " claim " " (cite-list cites)
                                    " " (track-record subject (get records [outlet subject])))))
              "\n"))
       (when (seq forces) (str "\nForces: " (str/join "; " forces) "\n"))
       (when significance (str "\nWhy it matters: " significance "\n"))))

(defn- lag-text [lag] (when lag (str " within " lag)))

(defn analysis-block
  "What the analyst is told of the desk's work: the map, its structural
  forces, the trends, the chains of cause and effect between them and the
  stories, by their source numbers, and the risks and outlook it shows,
  then the dossiers. `found` are the sources the gap searches found, each
  with its :key and :gap, and `records` each outlet's record on a subject,
  {[outlet subject] record}, shown with what it expects. nil when there
  are no dossiers."
  ([stories dossiers the-map found graph?] (analysis-block stories dossiers the-map found graph? {}))
  ([stories dossiers {:keys [forces trends links risks outlook]} found graph? records]
   (when (seq dossiers)
     (let [by-key (into {} (map (juxt :key identity)) stories)
           story-name (fn [k] (let [s (by-key k) d (get dossiers k)]
                                (str (or (:label d) (:title s)) " " (cite-list (source-numbers s)))))
           names (into {} (map (juxt :id :name)) (concat forces trends))
           name-of (fn [k] (or (names k) (story-name k)))
           found-by (group-by :key found)]
       (str "## The desk's analysis\n\n"
            "Before you write, the desk sorted the day's reports into stories, read the main stories' reports "
            "and wrote a dossier on each from them alone, then mapped how they move the world: the trends they "
            "push on, the structural forces those feed, and the chains of cause and effect that run from one to "
            "the next. The dossiers' facts cite the numbered sources below, and so should you. Take what "
            "happened from the dossiers, weigh the disputed accounts and say which way the evidence leans, and "
            "build the analysis on the map's chains, checking each link against the facts. The implied trends "
            "are where the chains lead before the news reports it, so reason them through and say how sure "
            "they are. Where an outlet expects something, weigh it by the outlet's record on that subject."
            (when graph? " The graph of the map is drawn for you, so don't draw one.")
            "\n\n"
            (when (seq forces)
              (str "### Structural forces\n\n"
                   (str/join "\n" (for [{:keys [name direction summary]} forces]
                                    (str "- **" name "**" (when direction (str " (" direction ")"))
                                         (when summary (str ": " summary)))))
                   "\n\n"))
            (when (seq trends)
              (str "### Trends\n\n"
                   (str/join "\n" (for [{:keys [name direction stories summary implied]} trends]
                                    (str "- **" name "**"
                                         (if implied
                                           " (implied, not yet in the reports)"
                                           (str (when direction (str " (" direction ")"))
                                                ", in " (str/join ", " (map story-name stories))))
                                         (when summary (str ": " summary)))))
                   "\n\n"))
            (when (seq links)
              (str "### How they drive one another\n\n"
                   (str/join "\n" (for [{:keys [from to mechanism explanation confidence effect lag]} links]
                                    (str "- " (name-of from) " → " (name-of to) ": " mechanism
                                         ", " (or effect "strengthens") " it" (lag-text lag) "."
                                         (when explanation (str " " explanation))
                                         " (" confidence " confidence)")))
                   "\n\n"))
            (when (seq risks)
              (str "### Risks the desk sees\n\n"
                   (str/join "\n" (for [{:keys [risk likelihood horizon exposed signpost]} risks]
                                    (str "- " risk
                                         (when-let [tags (seq (remove nil? [likelihood horizon]))]
                                           (str " (" (str/join ", " tags) ")"))
                                         "."
                                         (when exposed (str " Exposed: " exposed "."))
                                         (when signpost (str " Signpost: " signpost ".")))))
                   "\n\n"))
            (when-let [{:keys [base confirm overturn]} outlook]
              (str "### The desk's outlook\n\n" base
                   (when confirm (str " Would confirm it: " confirm))
                   (when overturn (str " Would overturn it: " overturn))
                   "\n\n"))
            "### Dossiers\n\n"
            (str/join "\n" (for [s stories :let [d (get dossiers (:key s))] :when d]
                             (dossier-text s d (found-by (:key s)) records))))))))

(defn trends-block
  "The day's map as the researcher looking for precedents is told it: the
  structural forces and the trends, with their stories by source number.
  nil when there are neither."
  [stories {:keys [forces trends]}]
  (let [by-key (into {} (map (juxt :key identity)) stories)
        lines (fn [xs] (str/join "\n" (for [{:keys [name direction stories summary]} xs]
                                        (str "- " name (when direction (str " (" direction ")"))
                                             ", in stories " (cite-list (mapcat #(source-numbers (by-key %)) stories))
                                             (when summary (str ": " summary))))))]
    (when (or (seq forces) (seq trends))
      (str/join "\n\n"
                (remove nil?
                        [(when (seq forces)
                           (str "The structural forces the desk found the day feeding:\n\n" (lines forces)))
                         (when (seq trends)
                           (str "The trends the desk found running through the day:\n\n" (lines trends)))])))))

(defn dossier-notes
  "The dossiers by storyline, as the notes are updated from them: each
  story's facts, with the reports they cite. {story [{:fact :cites}]}"
  [stories dossiers]
  (into {} (for [{:keys [key story]} stories
                 :let [d (get dossiers key)]
                 :when (and story (seq (:facts d)))]
             [story (:facts d)])))
