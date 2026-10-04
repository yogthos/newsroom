(ns newsroom.news
  "The pure core of a day's briefing: no IO, no clock, no storage.

  Items gathered from every source are deduplicated by their canonical URL,
  those the last briefings already had are left out, and the rest are
  numbered as sources; near-duplicates across outlets are collapsed into one
  source that credits the others; the numbered sources are written into the
  analysis prompt; the model's markdown answer cites them as [n], and
  `briefing` links those citations and appends the sources the answer cited.
  The contract is test/newsroom/news_spec.clj."
  (:require [clojure.string :as str]
            [newsroom.embed :as embed]
            [newsroom.template :as template]))

;; --- urls ------------------------------------------------------------------------

(def tracking-params
  "Query parameters that say how a reader arrived, not what they read."
  #{"fbclid" "gclid" "dclid" "mc_cid" "mc_eid" "igshid" "cmpid"
    "at_medium" "at_campaign" "traffic_source" "maca" "ocid" "smid"})

(defn- tracking? [param]
  (let [k (first (str/split param #"=" 2))]
    (or (str/starts-with? k "utm_") (contains? tracking-params k))))

(defn canonical-url
  "The address a story is known by: trimmed, with no fragment, no tracking
  parameters and no trailing slash."
  [url]
  (let [u (first (str/split (str/trim url) #"#" 2))
        q (str/index-of u "?")
        base (str/replace (if q (subs u 0 q) u) #"/+$" "")
        params (remove tracking? (remove str/blank? (str/split (if q (subs u (inc q)) "") #"&")))]
    (if (seq params)
      (str base "?" (str/join "&" params))
      base)))

;; --- items -----------------------------------------------------------------------

(defn- usable-item? [item]
  (not (or (str/blank? (:title item)) (str/blank? (:url item)))))

(defn dedupe-items
  "The first copy of each story, in the order they came; an item with no
  title or no URL is dropped."
  [items]
  (first (reduce (fn [[kept seen] item]
                   (let [k (canonical-url (:url item))]
                     (if (or (not (usable-item? item)) (contains? seen k))
                       [kept seen]
                       [(conj kept item) (conj seen k)])))
                 [[] #{}]
                 items)))

(def ^:private min-title-chars
  "How long a headline has to be, normalised, to identify a story by itself:
  a short one like \"Live updates\" is used for different stories every day."
  24)

(defn- title-key
  "A headline as it identifies a story across outlets and URLs: lower case,
  punctuation and spacing evened out. nil when it is too short to rely on."
  [title]
  (let [k (str/trim (str/replace (str/lower-case (str title)) #"[^\p{L}\p{N}]+" " "))]
    (when (>= (count k) min-title-chars) k)))

(defn unseen-items
  "The items that weren't among `earlier`, the sources of the briefings
  before this one, in the order they came. A story counts as seen when its
  canonical URL or its headline matches one of them, since the same wire
  story turns up at a new address the next day."
  [items earlier]
  (let [urls (set (map (comp canonical-url :url) earlier))
        titles (set (keep (comp title-key :title) earlier))]
    (filterv (fn [item]
               (not (or (contains? urls (canonical-url (:url item)))
                        (contains? titles (title-key (:title item))))))
             items)))

;; --- who carried a story --------------------------------------------------------

(def ^:private second-levels
  "Second-level labels under a country's domain that a publisher registers
  beneath, as in bbc.co.uk or chinadaily.com.cn."
  #{"co" "com" "org" "net" "ac" "gov" "edu" "ne" "or"})

(defn- publisher
  "The registrable domain of a URL's host, which names its publisher across
  all of its feeds: news.bbc.co.uk and www.bbc.co.uk are both bbc.co.uk.
  nil for a URL with no host."
  [url]
  (when-let [[_ host] (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#:]+)" (str url))]
    (let [labels (str/split (str/lower-case host) #"\.")
          n (count labels)
          keep (if (and (> n 2) (= 2 (count (last labels))) (contains? second-levels (nth labels (- n 2)))) 3 2)]
      (str/join "." (take-last keep labels)))))

(def ^:private agencies
  "The wire agencies, by how a dateline names them and by their own domains."
  {"AP" "AP", "Associated Press" "AP", "Reuters" "Reuters", "AFP" "AFP",
   "Agence France-Presse" "AFP", "Xinhua" "Xinhua", "dpa" "dpa", "PTI" "PTI", "ANI" "ANI",
   "IANS" "IANS", "UPI" "UPI", "Kyodo" "Kyodo", "Yonhap" "Yonhap", "TASS" "TASS", "EFE" "EFE",
   "ANSA" "ANSA", "Bloomberg" "Bloomberg"
   "apnews.com" "AP", "reuters.com" "Reuters", "afp.com" "AFP", "xinhuanet.com" "Xinhua",
   "news.cn" "Xinhua", "bloomberg.com" "Bloomberg", "tass.com" "TASS", "yna.co.kr" "Yonhap"})

(def ^:private dateline
  "A wire dateline opening a story, as in WASHINGTON (AP) — or (Reuters) -."
  #"^[^(]{0,60}\((AP|Associated Press|Reuters|AFP|Agence France-Presse|Xinhua|dpa|PTI|ANI|IANS|UPI|Kyodo|Yonhap|TASS|EFE|ANSA|Bloomberg)\)\s*[-–—:]")

(defn- origin
  "Where an item's story came from, which is what counts as one outlet: the
  wire agency its dateline names, since a reprint is the agency's story
  wherever it runs, else its publisher, else its source."
  [{:keys [url summary source origin]}]
  (or origin
      (some->> (re-find dateline (str summary)) second (get agencies))
      (let [p (publisher url)]
        (or (get agencies p) p))
      source))

;; --- near-duplicates ----------------------------------------------------------------

(def ^:private verbatim
  "How similar two items from one publisher's different feeds, or two
  reprints of one wire story, have to be to count as the same text."
  0.9)

(defn collapse-similar
  "Near-duplicate items collapsed into one, as greedy centroid clusters: an
  item joins the most similar cluster whose centroid is within `threshold`
  cosine similarity, else it starts its own. Items from one feed never
  merge, and items with one origin (a publisher's several feeds, reprints of
  one wire story) only when they are near verbatim, since one outlet's two
  similar stories are usually two stories. The surviving item, the first of
  its cluster, lists the others in :also as {:source :url :title :origin},
  so the copies count as told, and carries the cluster's centroid as its
  :vector."
  [items threshold]
  (if (or (nil? threshold) (empty? items))
    items
    (let [joins? (fn [c item v]
                   (and (:sum c)
                        (not (contains? (set (map :source (:items c))) (:source item)))
                        (let [kin (filter #(= (origin item) (origin %)) (:items c))]
                          (every? #(>= (embed/cosine v (:vector %)) verbatim) kin))))
          clusters (reduce
                    (fn [clusters item]
                      (let [v (:vector item)
                            ;; a cluster keeps the sum of its vectors, which
                            ;; points the same way as their mean
                            best (when v
                                   (some->> (map-indexed vector clusters)
                                            (keep (fn [[i c]]
                                                    (when (joins? c item v)
                                                      [(embed/cosine v (:sum c)) i])))
                                            seq
                                            (apply max-key first)))]
                        (if (and best (>= (first best) threshold))
                          (update clusters (second best)
                                  (fn [c] (-> c
                                              (update :items conj item)
                                              (update :sum #(mapv + % v)))))
                          (conj clusters {:items [item] :sum v}))))
                    []
                    items)]
      (mapv (fn [{:keys [sum] [head & others] :items}]
              (let [also (vec (keep #(when (:source %)
                                       (assoc (select-keys % [:source :url :title]) :origin (origin %)))
                                    others))]
                (cond-> head
                  (seq also) (assoc :also also :vector (embed/unit sum)))))
            clusters))))

(defn told
  "The stories `sources` told: each source and the copies collapsed into it,
  which are what unseen-items checks a new day's items against."
  [sources]
  (mapcat (fn [s]
            (cons s (for [{:keys [url] :as copy} (:also s) :when url]
                      (merge {:title "" :source "" :summary "" :published nil}
                             (select-keys copy [:title :url :source])))))
          sources))

(defn outlets
  "How many outlets carried a source: the distinct origins of it and of the
  copies collapsed into it, so a publisher's several feeds count once and a
  wire story counts once however many outlets reprint it."
  [{:keys [also] :as s}]
  (max 1 (count (distinct (remove str/blank? (map origin (cons s also)))))))

(defn cite
  "The items as sources, numbered from 1 in order: the numbers the analysis
  cites them by."
  [items]
  (vec (map-indexed (fn [i item] (assoc item :n (inc i))) items)))

;; --- the prompt ------------------------------------------------------------------

(defn- coverage
  "How widely and how long a source's story has run, for the model to weigh
  it by: nil for a story one outlet carried on one day."
  [s]
  (let [n (outlets s)
        days (or (:days s) 1)]
    (when (or (> n 1) (> days 1))
      (str "Coverage: "
           (str/join ", " (cond-> []
                            (> n 1) (conj (str n " outlets today"))
                            (> days 1) (conj (str "in the news on " days " days"))))))))

(defn- source-line [s]
  (str "[" (:n s) "] " (:title s)
       (when-not (str/blank? (:source s)) (str " (" (:source s) ")"))
       (when-let [p (:published s)] (str ", " p))
       "\n" (:url s)
       (when-let [c (coverage s)] (str "\n" c))
       (when-not (str/blank? (:summary s)) (str "\n" (:summary s)))))

(defn- source-block [sources]
  (let [today (remove :precedent sources)
        precedents (filterv :precedent sources)]
    (str/join "\n\n" (concat (map source-line today)
                             (when (seq precedents)
                               [(str "Historical precedents: the sources below this line are earlier "
                                     "events of the same kind as today's stories, found by web search. "
                                     "Use them to ground the analysis — what happened last time, how long "
                                     "it took, what it led to — and cite them as sources like any other.")])
                             (map source-line precedents)))))

(def ^:private overview-chars
  "About how much of the last briefing's overview the model gets: a few
  paragraphs, cut at a paragraph."
  4000)

(defn- strip-citations
  "Text with its citations gone, linked ([[3]](url)) or not ([3, 7]): the
  numbers belong to that day's sources and mean nothing in another prompt."
  [text]
  (-> text
      (str/replace #"\s*\[\[\d+\]\]\([^)\s]*\)(,\s*\[\[\d+\]\]\([^)\s]*\))*" "")
      (str/replace #"\s*\[\d+(,\s*\d+)*\]" "")))

(defn overview
  "The first section of a briefing, its overview, without citations and cut
  to a few paragraphs: what the next day's briefing can take as established.
  nil when the briefing has no sections."
  [markdown]
  (let [body (->> (str/split-lines (str markdown))
                  (drop-while #(not (re-find #"^## " %)))
                  rest
                  (take-while #(not (re-find #"^#{1,2} " %))))
        paragraphs (remove str/blank? (map str/trim (str/split (strip-citations (str/join "\n" body))
                                                              #"\n\s*\n")))
        kept (reduce (fn [acc p]
                       (if (and (seq acc) (> (+ (count (str/join "\n\n" acc)) (count p)) overview-chars))
                         (reduced acc)
                         (conj acc p)))
                     [] paragraphs)]
    (when (seq kept) (str/join "\n\n" kept))))

(defn tldr
  "The standfirst of a briefing: the first blockquote before the first
  section heading, its lines joined, with the > marks and any citations
  stripped, since it is shown on its own in the archive. nil when there is
  none."
  [markdown]
  (let [quoted? #(str/starts-with? % ">")
        text (->> (str/split-lines (str markdown))
                  (take-while #(not (re-find #"^## " %)))
                  (drop-while (complement quoted?))
                  (take-while quoted?)
                  (map #(subs % 1))
                  (str/join " ")
                  strip-citations)
        text (str/trim (str/replace text #"\s+" " "))]
    (when-not (str/blank? text) text)))

(defn- tag-re
  "A Selmer variable tag for `var`, filters and all: {{ sources }},
  {{sources|upper}}."
  [var]
  (re-pattern (str "\\{\\{\\s*" var "\\s*(?:\\|[^}]*)?\\}\\}")))

(defn has-var?
  "Whether `template` shows the variable `var` somewhere."
  [template var]
  (boolean (re-find (tag-re var) template)))

(defn place-vars
  "`template` with a place for each of `vars` it has none for: the first
  just before the place of `before`, or at the end when it has none either,
  so that what the model has to see is always in the prompt. `vars` are
  placed in order, `before` last."
  [template vars before]
  (reduce (fn [t var]
            (cond
              (has-var? t var) t
              (and (not= var before) (has-var? t before))
              (str/replace-first t (tag-re before) (fn [m] (str "{{" var "}}\n\n" m)))
              :else (str t "\n\n{{" var "}}")))
          template
          vars))

(defn render-prompt
  "The briefing's prompt: the Selmer `template` with {{date}}, {{sources}}
  and {{previous}}, text about the last briefing, filled in. A template
  with no place for the sources gets them after it, so the model always
  sees what it may cite, and one with no place for `previous` gets it just
  before the sources."
  [template day sources previous]
  (template/fill (place-vars template (if previous ["previous" "sources"] ["sources"]) "sources")
        {:date day :sources (source-block sources) :previous previous}))

;; --- citations -------------------------------------------------------------------

(def ^:private citation-re
  "A fenced code block, which is left alone, or a citation: [n] or [n, m]."
  #"(?s)(```.*?(?:```|$))|\[(\d+(?:\s*,\s*\d+)*)\]")

(defn- numbers-in [group]
  (keep #(parse-long (str/trim %)) (str/split group #",")))

(defn citations
  "The source numbers the markdown cites, as [n] or [n, m], ascending.
  Fenced code blocks, a diagram's source among them, cite nothing."
  [markdown]
  (vec (sort (distinct (mapcat (fn [[_ fence group]] (when-not fence (numbers-in group)))
                               (re-seq citation-re markdown))))))

(defn link-citations
  "The markdown with every citation of a known source turned into a link to
  it: [2] becomes [[2]](url). A number no source has, and anything inside a
  fenced code block, is left as it was."
  [markdown sources]
  (let [urls (into {} (map (fn [s] [(:n s) (:url s)]) sources))]
    (str/replace markdown citation-re
                 (fn [[whole fence group]]
                   (let [ns (when-not fence (numbers-in group))]
                     (if (some #(contains? urls %) ns)
                       (str/join ", " (map (fn [n] (if (contains? urls n)
                                                     (str "[[" n "]](" (get urls n) ")")
                                                     (str "[" n "]")))
                                           ns))
                       whole))))))

(defn- source-entry [s]
  (str "- [" (:n s) "] [" (str/replace (:title s) #"[\[\]]" "") "](" (:url s) ")"
       (when-not (str/blank? (:source s)) (str " — " (:source s)))))

(defn briefing
  "The day's briefing: the answer with its citations linked, then a Sources
  section listing the sources it cited."
  [markdown sources]
  (let [cited (set (citations markdown))
        used (filter #(contains? cited (:n %)) sources)]
    (str (str/trimr (link-citations markdown sources))
         "\n\n## Sources\n\n"
         (if (seq used)
           (str/join "\n" (map source-entry used))
           "_No sources were cited._")
         "\n")))

;; --- days ------------------------------------------------------------------------

(defn- leap? [y]
  (and (zero? (mod y 4)) (or (pos? (mod y 100)) (zero? (mod y 400)))))

(defn- days-in-month [y m]
  (case m
    2 (if (leap? y) 29 28)
    (4 6 9 11) 30
    31))

(defn valid-day?
  "Whether s is a calendar day written YYYY-MM-DD."
  [s]
  (let [[_ y m d] (re-matches #"(\d{4})-(\d{2})-(\d{2})" s)]
    (boolean
     (and y
          (<= 1 (parse-long m) 12)
          (<= 1 (parse-long d) (days-in-month (parse-long y) (parse-long m)))))))

(defn adjacent-days
  "The nearest day before `day` and after it among `days`, or nil where
  there is none."
  [days day]
  [(last (sort (filter #(neg? (compare % day)) days)))
   (first (sort (filter #(pos? (compare % day)) days)))])
