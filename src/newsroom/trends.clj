(ns newsroom.trends
  "The pure core of ranking and of the digests: no IO, no clock, no storage.

  A story is followed from day to day by its embedding. Each stored source
  belongs to a storyline, named by the day and number of the source that
  started it. A new day's item continues the storyline whose recent sources
  it sits closest to, within :story-threshold, or starts its own.

  An item's weight is how widely and how long its story has run: the outlets
  carrying it today plus the outlets that carried it on each earlier day,
  each earlier day counting for half as much every :half-life-days. A story
  many outlets pick up, or one that keeps coming back, outranks a one-off,
  so it survives the cap on items and comes first in the prompt.

  A digest looks over a week or a month of storylines, as their coverage
  and the notes kept on them (newsroom.notes) have them. Each is ranked by
  its coverage in the period and labelled by how that coverage moved: emerging
  when it far outruns the storyline's rate in the periods before, fading
  when the later part of the period carried much less of it than the
  earlier part, persistent when it ran on most days of the period, steady
  otherwise."
  (:require [clojure.math :as m]
            [clojure.string :as str]
            [newsroom.embed :as embed]
            [newsroom.news :as news]
            [newsroom.template :as template]))

;; --- days ------------------------------------------------------------------------

(defn- date [day] (java.time.LocalDate/parse day))

(defn days-between
  "Whole days from `a` to `b`, both YYYY-MM-DD; negative when b is earlier."
  [a b]
  (.between java.time.temporal.ChronoUnit/DAYS (date a) (date b)))

(defn plus-days [day n] (str (.plusDays (date day) n)))

;; --- storylines ------------------------------------------------------------------

(defn storylines
  "The stored sources grouped by the storyline they belong to, each
  {:story :centroid :sources}. A storyline is known by its centroid, the
  mean of its sources' vectors, so one whose sources have no vectors can't
  be continued and is left out. A gap search's result is left out too:
  it was found for a story, not reported on it, so it doesn't count as
  coverage."
  [history]
  (->> (filter :story history)
       (remove :gap)
       (group-by :story)
       (keep (fn [[id ss]]
               (when-let [c (embed/centroid (keep :vector ss))]
                 {:story id :centroid c :sources ss})))
       vec))

(defn link-stories
  "Each item tagged with the storyline it continues, as :story: the one
  whose centroid it is most similar to, when that is within `threshold`.
  An item with no vector, or close to none, starts a storyline of its own
  and is left untagged."
  [items lines threshold]
  (if (or (nil? threshold) (empty? lines))
    items
    (mapv (fn [item]
            (let [v (:vector item)
                  [sim line] (when v
                               (apply max-key first
                                      (map (fn [l] [(embed/cosine v (:centroid l)) l]) lines)))]
              (if (and sim (>= sim threshold))
                (assoc item :story (:story line))
                item)))
          items)))

(defn follow-day
  "A day's stored sources, each with the storyline it belongs to as
  :story, for a day stored before storylines were kept. A source joins the
  storyline of the days before it is closest to, or one an earlier source
  of the same day started, when within `threshold`; else it starts its own,
  named by its day and number. Copies of a story from several outlets were
  separate sources then, so they join one storyline this way. A source that
  already has a storyline keeps it."
  [day sources lines threshold]
  (first
   (reduce (fn [[out today] {:keys [n story] v :vector :as s}]
             (let [candidates (concat (map (juxt :story :centroid) lines)
                                      (map (fn [[id vs]] [id (embed/centroid vs)]) today))
                   [sim id] (when (and v (nil? story))
                              (some->> (seq candidates)
                                       (map (fn [[id c]] [(embed/cosine v c) id]))
                                       (apply max-key first)))
                   story (or story (if (and sim (>= sim threshold)) id (str day "/" n)))]
               [(conj out (assoc s :story story))
                (if v (update today story (fnil conj []) v) today)]))
           [[] {}]
           sources)))

(defn- decay [age half-life]
  (m/pow 2.0 (- (/ (double age) half-life))))

(defn- past-weight
  "The weight of a storyline's earlier days: the outlets of each of its
  `past` sources, each counting half as much every `half-life` days."
  [past day half-life]
  (reduce + (map (fn [s] (* (news/outlets s) (decay (days-between (:day s) day) half-life)))
                 past)))

(defn- round2 [x] (/ (m/round (* 100 x)) 100.0))

(defn rank
  "The items by weight, heaviest first, each with its :weight and :days,
  the days its story has been in the news counting today. Items of equal
  weight keep their order."
  [items lines day half-life]
  (let [by-story (into {} (map (juxt :story identity)) lines)
        weigh (fn [item]
                (let [past (:sources (by-story (:story item)))]
                  (assoc item
                         :weight (round2 (+ (news/outlets item) (past-weight past day half-life)))
                         :days (inc (count (distinct (map :day past)))))))]
    (->> (map-indexed (fn [i item] [i (weigh item)]) items)
         (sort-by (fn [[i item]] [(- (:weight item)) i]))
         (mapv second))))

(defn group-outlets
  "How many outlets carried a story told in several of the day's reports,
  `members`: the distinct origins of them all and of their copies."
  [[lead & others :as members]]
  (news/outlets (assoc lead :also (vec (concat others (mapcat :also members))))))

(defn importance-factor
  "How much a story's importance, 1 to 10, scales its weight: 5 leaves it
  as its coverage has it, 10 doubles it, 1 cuts it to a fifth. A story
  with no importance is left as it is."
  [importance]
  (if importance (/ importance 5.0) 1.0))

(defn rank-groups
  "The items, sorted into stories by their :group, ranked a story at a
  time: by the story's weight, the outlets that carried any of its reports
  today plus its storyline's earlier days, scaled by its :importance (see
  importance-factor). Heaviest story first, the reports of one story
  together, the most widely carried first. Each item gets its story's
  :weight, :days and :group-outlets. Stories of equal score keep their
  order."
  [items lines day half-life]
  (let [by-story (into {} (map (juxt :story identity)) lines)
        groups (group-by :group items)]
    (->> (distinct (map :group items))
         (map-indexed
          (fn [i g]
            (let [members (->> (get groups g)
                               (map-indexed vector)
                               (sort-by (fn [[j s]] [(- (news/outlets s)) j]))
                               (mapv second))
                  lead (first members)
                  past (:sources (by-story (:story lead)))
                  outlets (group-outlets members)
                  weight (+ outlets (past-weight past day half-life))
                  days (inc (count (distinct (map :day past))))]
              {:i i
               :score (* weight (importance-factor (:importance lead)))
               :members (mapv #(assoc % :weight (round2 weight) :days days :group-outlets outlets)
                              members)})))
         (sort-by (fn [{:keys [i score]}] [(- score) i]))
         (mapcat :members)
         vec)))

;; --- periods ---------------------------------------------------------------------

(def kinds
  "The digests there are, by the span of news each covers."
  #{:week :month})

(defn- iso-week
  "The ISO year and week of a day: weeks run Monday to Sunday, and the first
  week of a year is the one with its Thursday."
  [day]
  (let [d (date day)
        thursday (.plusDays d (- 4 (.getValue (.getDayOfWeek d))))]
    [(.getYear thursday) (inc (quot (dec (.getDayOfYear thursday)) 7))]))

(defn period-of
  "The period of `kind` that `day` falls in: 2026-W40 for a week, 2026-09
  for a month."
  [kind day]
  (case kind
    :week (let [[y w] (iso-week day)] (format "%d-W%02d" y w))
    :month (subs day 0 7)))

(defn period-range
  "The first and last day of a period, or nil when it isn't one."
  [kind period]
  (case kind
    :week (when-let [[_ y w] (re-matches #"(\d{4})-W(\d{2})" (str period))]
            (let [jan4 (java.time.LocalDate/of (parse-long y) 1 4)
                  monday (.plusDays jan4 (+ (- 1 (.getValue (.getDayOfWeek jan4)))
                                            (* 7 (dec (parse-long w)))))]
              ;; week 53 exists only in years that have one
              (when (and (<= 1 (parse-long w) 53) (= period (period-of :week (str monday))))
                [(str monday) (str (.plusDays monday 6))])))
    :month (when-let [[_ y mo] (re-matches #"(\d{4})-(\d{2})" (str period))]
             (when (<= 1 (parse-long mo) 12)
               (let [first-day (java.time.LocalDate/of (parse-long y) (parse-long mo) 1)]
                 [(str first-day) (str (.plusDays first-day (dec (.lengthOfMonth first-day))))])))
    nil))

(defn previous-period
  "The last whole period of `kind` before the one `day` is in."
  [kind day]
  (let [[from _] (period-range kind (period-of kind day))]
    (period-of kind (plus-days from -1))))

(def baseline-periods
  "How many periods before a digest's its storylines are compared against,
  to tell a story that is new from one that has been running."
  4)

(defn baseline-range
  "The days a digest's period is compared against: the
  `baseline-periods` periods before it."
  [kind period]
  (let [[from _] (period-range kind period)
        start (nth (iterate #(first (period-range kind (period-of kind (plus-days % -1)))) from)
                   baseline-periods)]
    [start (plus-days from -1)]))

;; --- digests ---------------------------------------------------------------------

(defn story-of
  "The storyline a stored source belongs to; one stored before storylines
  were tracked is its own, named by its day and number as a new one is."
  [s]
  (or (:story s) (str (:day s) "/" (:n s))))

(defn- reach
  "The outlets that carried a source: counted when it was stored, or from
  its copies."
  [s]
  (or (:outlets s) (news/outlets s)))

(defn- line-trend
  [{:keys [growth early late days]} active-days]
  (cond
    (and (>= early 2) (< (* 2 late) early)) :fading
    (and growth (>= growth 1.0)) :emerging
    (>= (* 2 days) (max 2 active-days)) :persistent
    :else :steady))

(defn digest-storylines
  "The storylines of a period's sources, ranked, each {:story :title :days
  :outlets :cited :baseline :growth :trend :score :sources}: the days it
  ran, the outlets that carried it summed over them, how many of its
  sources the daily briefings cited, and its outlets in the baseline before
  the period. :growth is the log ratio of its coverage to what the baseline
  rate would give a period this long, nil when there is no baseline.

  `window` is stored sources with their :day, `baseline` stored sources or
  their coverage, and
  `active-days` and `baseline-days` how many days each had a briefing.
  A storyline only one outlet carried on one day is left out."
  [window baseline active-days baseline-days]
  (let [base (reduce (fn [acc s] (update acc (story-of s) (fnil + 0) (reach s)))
                     {} baseline)
        days (sort (distinct (map :day window)))
        middle (when (seq days) (nth days (quot (count days) 2)))]
    (->> (group-by story-of window)
         (map (fn [[id ss]]
                (let [outlets (reduce + (map reach ss))
                      b (get base id 0)
                      n-days (count (distinct (map :day ss)))
                      cited (count (filter :cited? ss))
                      ;; an odd number of days puts the middle one in the
                      ;; later half
                      early (reduce + (map reach (filter #(neg? (compare (:day %) middle)) ss)))
                      growth (when (pos? baseline-days)
                               (m/log (/ (+ outlets 1.0)
                                         (+ 1.0 (* b (/ (double active-days) baseline-days))))))
                      lead (first (sort-by (fn [s] [(- (news/outlets s)) (if (:cited? s) 0 1) (:day s)]) ss))
                      line {:story id
                            :title (:title lead)
                            :days n-days
                            :outlets outlets
                            :cited cited
                            :baseline b
                            :growth (some-> growth (* 100) m/round (/ 100.0))
                            :early early
                            :late (- outlets early)
                            :score (* (+ outlets cited) (+ 1.0 (/ (double n-days) (max 1 active-days))))
                            :sources (vec (sort-by (juxt :day :n) ss))}]
                  (assoc line :trend (line-trend line active-days)))))
         (filter #(or (> (:outlets %) 1) (> (:days %) 1)))
         (sort-by (juxt (comp - :score) :story))
         vec)))

(defn digest-sources
  "The stored `sources` a digest cites for a storyline with no notes: at
  most `n`, the most telling of each day (cited by the day's briefing, then
  the most widely carried), latest days first when there are more days than
  room, in the order they ran."
  [sources n]
  (->> (group-by :day sources)
       (map (fn [[_ ss]] (first (sort-by (fn [s] [(if (:cited? s) 0 1) (- (news/outlets s)) (:n s)]) ss))))
       (sort-by :day)
       (take-last n)
       vec))

(defn cite-storylines
  "The storylines, each with :cites, its :candidates numbered across all of
  them from 1, as the digest cites them."
  [lines]
  (first (reduce (fn [[out n] line]
                   (let [cites (mapv (fn [s i] (assoc s :n (+ n i))) (:candidates line) (range))]
                     [(conj out (assoc line :cites cites)) (+ n (count cites))]))
                 [[] 1]
                 lines)))

(def ^:private trend-words
  {:emerging "emerging, far more coverage than in the weeks before"
   :fading "fading, much less coverage at the end of the period than at the start"
   :persistent "persistent, in the news on most days"
   :steady "steady"})

(defn- digest-line [{:keys [title trend days outlets baseline summary]}]
  (str "### " title "\n"
       "Trend: " (trend-words trend) ". " outlets " outlet reports over " days
       (if (= 1 days) " day" " days")
       (when (pos? baseline) (str ", " baseline " in the periods before"))
       "."
       (when-not (str/blank? summary) (str "\nWhere it stands: " summary))))

(defn- digest-source
  "A numbered source for the digest: a fact from the storyline's notes with
  the report it came from, or a stored report with its summary."
  [long-date {:keys [n day title source url summary fact]}]
  (if fact
    (str "[" n "] " (long-date day) ": " fact
         "\n" title (when-not (str/blank? source) (str " (" source ")")) ", " url)
    (str "[" n "] " (long-date day) ": " title
         (when-not (str/blank? source) (str " (" source ")"))
         "\n" url
         (when-not (str/blank? summary)
           (str "\n" (if (> (count summary) 300) (str (subs summary 0 300) "…") summary))))))

(defn digest-block
  "The storylines for the digest prompt, each with its trend and its
  numbered sources, which the digest cites by number."
  [lines long-date]
  (str/join "\n\n"
            (map (fn [{:keys [cites] :as line}]
                   (str (digest-line line) "\n\n"
                        (str/join "\n\n" (map #(digest-source long-date %) cites))))
                 lines)))

(defn days-block
  "The standfirst of each day's briefing in the period, as a list."
  [archive long-date]
  (str/join "\n" (for [{:keys [day tldr]} (sort-by :day archive)
                       :when (not (str/blank? tldr))]
                   (str "- " (long-date day) ": " tldr))))

(defn render-digest-prompt
  "The digest's prompt: the Selmer `template` with {{period}}, {{days}},
  {{stories}}, {{previous}}, about the last digest, {{briefings}}, the
  briefings the period's digest is built on, {{trends}}, the trends the
  desk followed through the period, and {{record}}, how earlier
  projections turned out, filled in. A template with no place for the
  stories gets them after it, so the model always sees what it may cite,
  and one with no place for `previous`, `briefings`, `trends` or `record`
  gets them just before them."
  ([template period days stories previous] (render-digest-prompt template period days stories previous nil nil))
  ([template period days stories previous trends] (render-digest-prompt template period days stories previous trends nil))
  ([template period days stories previous trends briefings]
   (render-digest-prompt template period days stories previous trends briefings nil))
  ([template period days stories previous trends briefings record]
   (template/fill (news/place-vars template (cond-> []
                                              previous (conj "previous")
                                              briefings (conj "briefings")
                                              trends (conj "trends")
                                              record (conj "record")
                                              true (conj "stories"))
                                   "stories")
                  {:period period :days days :stories stories :previous previous :trends trends
                   :briefings briefings :record record})))

;; --- the desk's trends -------------------------------------------------------------

(defn trend-threads
  "The desk's daily trends, `rows` as the store keeps them, followed as
  threads: each {:thread :name :summary :direction :days :first-day
  :last-day :directions}, its name and summary its latest, :directions the
  direction of each day it was seen. The longest running first, then the
  latest seen."
  [rows]
  (->> (group-by :thread rows)
       (map (fn [[thread rs]]
              (let [rs (sort-by :day rs)
                    latest (last rs)]
                {:thread thread
                 :name (:name latest)
                 :summary (:summary latest)
                 :direction (:direction latest)
                 :days (count (distinct (map :day rs)))
                 :first-day (:day (first rs))
                 :last-day (:day latest)
                 :day (:day latest)
                 :directions (vec (keep (fn [r] (when (:direction r) [(:day r) (:direction r)])) rs))})))
       (sort (fn [a b] (compare [(:days b) (:last-day b) (:thread a)]
                                [(:days a) (:last-day a) (:thread b)])))
       vec))

(defn- turns
  "The [day direction] pairs where the direction changed, the first one
  included."
  [directions]
  (map first (partition-by second directions)))

(defn trends-block
  "The trend threads of a period for the digest prompt: how many days
  each ran, how its direction moved, and where it stood last. nil when
  there are none."
  [threads long-date]
  (when (seq threads)
    (str/join "\n\n"
              (for [{:keys [name summary days first-day last-day directions]} threads]
                (str "### " name "\n"
                     "Seen on " days (if (= 1 days) " day" " days")
                     (when (not= first-day last-day)
                       (str ", from " (long-date first-day) " to " (long-date last-day)))
                     "."
                     (when (seq directions)
                       (str " Direction: "
                            (str/join ", " (for [[d dir] (turns directions)]
                                             (str dir " (" (long-date d) ")")))
                            "."))
                     (when summary (str "\nWhere it stood last: " summary)))))))

