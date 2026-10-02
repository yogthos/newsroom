(ns newsroom.news
  "The pure core of a day's briefing: no IO, no clock, no storage.

  Items gathered from every source are deduplicated by their canonical URL,
  those the last briefings already had are left out, and the rest are
  numbered as sources; near-duplicates across outlets are collapsed into one
  source that credits the others; the numbered sources are written into the
  analysis prompt; the model's markdown answer cites them as [n], and
  `briefing` links those citations and appends the sources the answer cited.
  The contract is test/newsroom/news_spec.clj."
  (:require [clojure.math :as m]
            [clojure.string :as str]))

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

;; --- near-duplicates ----------------------------------------------------------------

(defn- cosine
  "Cosine similarity of two vectors, 0 when either is missing or empty."
  [a b]
  (if (or (nil? a) (nil? b) (empty? a) (empty? b))
    0.0
    (let [dot (reduce + (map * a b))
          na (m/sqrt (reduce + (map * a a)))
          nb (m/sqrt (reduce + (map * b b)))]
      (if (or (zero? na) (zero? nb)) 0.0 (/ dot (* na nb))))))

(defn- centroid
  "The mean of the vectors, which nils don't count toward."
  [vs]
  (let [vs (remove nil? vs)]
    (when (seq vs)
      (let [n (count vs)]
        (mapv #(/ % n) (reduce (fn [acc v] (mapv + acc v)) (repeat (count (first vs)) 0.0) vs))))))

(defn collapse-similar
  "Near-duplicate items collapsed into one, as greedy centroid clusters: an
  item joins the most similar cluster whose centroid is within `threshold`
  cosine similarity, else it starts its own. Only items from different
  outlets merge, since one outlet's two similar stories are usually two
  stories; the surviving item names the others in :also, the outlets that
  also carry it. Embedding vectors don't survive the collapse."
  [items threshold]
  (if (or (nil? threshold) (empty? items))
    items
    (let [clusters (reduce
                    (fn [clusters item]
                      (let [v (:vector item)
                            candidates (when v
                                         (->> clusters
                                              (remove #(contains? (set (map :source %)) (:source item)))
                                              (map (fn [c] [(cosine v (centroid (keep :vector c))) c]))
                                              seq))
                            best (when candidates (apply max-key first candidates))]
                        (if (and best (>= (first best) threshold))
                          (mapv #(if (identical? (second best) %) (conj % item) %) clusters)
                          (conj clusters [item]))))
                    []
                    items)]
      (into []
            (mapcat (fn [c]
                      [(let [[head & others] c
                             also (vec (distinct (keep :source others)))]
                         (cond-> (dissoc head :vector)
                           (seq also) (assoc :also also)))]))
            clusters))))

(defn cite
  "The items as sources, numbered from 1 in order: the numbers the analysis
  cites them by."
  [items]
  (vec (map-indexed (fn [i item] (assoc item :n (inc i))) items)))

;; --- the prompt ------------------------------------------------------------------

(defn- source-line [s]
  (str "[" (:n s) "] " (:title s)
       (when-not (str/blank? (:source s)) (str " (" (:source s) ")"))
       (when-let [p (:published s)] (str ", " p))
       "\n" (:url s)
       (when-not (str/blank? (:summary s)) (str "\n" (:summary s)))))

(defn- source-block [sources]
  (str/join "\n\n" (map source-line sources)))

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
  section heading, its > and spaces stripped. nil when there is none."
  [markdown]
  (let [lines (str/split-lines (str markdown))
        before-section (first (split-with #(not (re-find #"^## " %)) lines))
        quote (some #(when-let [[_ text] (re-matches #">\s?(.*)" %)] text)
                    before-section)]
    (when (and quote (not (str/blank? quote))) quote)))

(defn add-previous
  "The template with `previous`, text about the last briefing, at its
  {{previous}}, or just before {{sources}} when it has no place for it, or at
  the end when it has neither. Without `previous` the template is unchanged
  but for an empty {{previous}}."
  [template previous]
  (cond
    (nil? previous) (str/replace template "{{previous}}" "")
    (str/includes? template "{{previous}}") (str/replace template "{{previous}}" previous)
    (str/includes? template "{{sources}}") (str/replace template "{{sources}}"
                                                        (str previous "\n\n{{sources}}"))
    :else (str template "\n\n" previous)))

(defn render-prompt
  "The template with {{date}} and {{sources}} filled in. A template with no
  {{sources}} gets them after it, so the model always sees what it may cite."
  [template day sources]
  (let [block (source-block sources)
        filled (str/replace template "{{date}}" day)]
    (if (str/includes? filled "{{sources}}")
      (str/replace filled "{{sources}}" block)
      (str filled "\n\n" block))))

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
