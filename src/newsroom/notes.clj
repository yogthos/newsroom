(ns newsroom.notes
  "The pure core of the story notes: no IO, no clock, no storage.

  A storyline the briefings follow keeps a note: a stable title, a short
  summary of where it stands, and its salient facts, each dated and tied to
  the report it came from. After a day's briefing, the analyst compacts the
  day's reports on the storylines that mattered into their notes, merging
  what repeats and dropping what a later fact supersedes, so a note holds
  the course of a story rather than every report on it.

  The notes outlive the sources they were made from, so the digests are
  written from them, and the daily briefing gets the notes of the stories
  still running as background."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]))

(def statuses
  "Where a storyline stands."
  ["developing" "escalating" "de-escalating" "concluding" "static"])

(defn status-of
  "`v` as one of `statuses`, or nil."
  [v]
  (let [s (some-> v str str/trim str/lower-case)]
    (some #{s} statuses)))

(def max-candidates
  "The most storylines one day's compaction updates."
  20)

(defn- clip [s n]
  (let [s (str/trim (str s))]
    (if (> (count s) n) (str (subs s 0 (dec n)) "…") s)))

(defn candidates
  "The storylines of the day's numbered `sources` worth a note, heaviest
  first: those the briefing cited, since the analyst has judged them to
  matter, and those that already have one, which are kept up to date. A
  story the briefing passed over doesn't start a note, however many outlets
  carried it. {story [sources]}, as an ordered list of pairs."
  [sources cited noted]
  (let [cited (set cited)
        noted (set noted)]
    (->> (filter :story sources)
         ;; what a gap search found is background, not the day's reporting
         (remove :gap)
         (group-by :story)
         (filter (fn [[story ss]] (or (contains? noted story) (some #(contains? cited (:n %)) ss))))
         (sort-by (fn [[story ss]] [(- (reduce max (map #(or (:weight %) 1) ss))) story]))
         (take max-candidates)
         vec)))

;; --- the compaction prompt -------------------------------------------------------

(defn- numbered-facts
  "Every fact of the notes with an id E1, E2, … across all of them, so the
  answer can keep a fact by naming it: {story [[id fact]]}."
  [notes stories]
  (first (reduce (fn [[out i] story]
                   (let [facts (:facts (get notes story))]
                     [(assoc out story (mapv (fn [f j] [(str "E" (+ i j)) f]) facts (range)))
                      (+ i (count facts))]))
                 [{} 1]
                 stories)))

(defn- note-block [note ids]
  (if note
    (str "Notes so far:\nTitle: " (:title note) "\nSummary: " (:summary note) "\n"
         (when (:status note) (str "Status: " (:status note) "\n"))
         (str/join "\n" (for [[id f] ids]
                          (str id " " (:day f) ": " (:text f)
                               (when-not (str/blank? (:source f)) (str " (" (:source f) ")"))))))
    "No notes yet: this is a new storyline."))

(defn- report [{:keys [n title source summary]}]
  (str "[" n "] " title (when-not (str/blank? source) (str " (" source ")"))
       (when-not (str/blank? summary) (str "\n" summary))))

(defn- dossier-block
  "The facts the desk's dossier found in the day's reports on a storyline,
  each with the reports it cites."
  [facts]
  (when (seq facts)
    (str "\nThe desk's facts from today's reports:\n"
         (str/join "\n" (for [{:keys [fact cites]} facts]
                          (str "- " fact " [" (str/join ", " cites) "]"))))))

(defn compaction-prompt
  "The prompt that has the analyst update the notes of `candidates` with
  the day's reports, and the fact ids it names, {:prompt :ids}.
  `dossiers` are the facts the desk drew from the day's reports, {story
  [{:fact :cites}]}, which the update can take with their citations."
  ([day candidates notes max-facts] (compaction-prompt day candidates notes max-facts nil))
  ([day candidates notes max-facts dossiers]
  (let [per-story (numbered-facts notes (map first candidates))]
    {:ids (into {} (mapcat val per-story))
     :prompt
     (str "You keep the running notes on the news stories a daily briefing follows. Today is " day ".\n\n"
          "For each storyline below you get its notes so far, if it has any, and today's reports on it. "
          "Update the notes so they hold what a reader needs to follow the story over weeks: the events "
          "that moved it, the figures and decisions that matter, and who did what. Merge facts that "
          "repeat, drop a detail a later fact supersedes unless the change itself matters, and leave "
          "out colour, quotes and speculation. Every fact must come from the notes or from today's "
          "reports. Keep at most " max-facts " facts for each storyline, the ones that matter most "
          "for its course, in the order they happened. A fact taken from the desk's facts cites the "
          "first report they name. Say where the story stands now: " (str/join ", " statuses) ".\n\n"
          "Answer with JSON only, in this shape:\n"
          "{\"storylines\": [{\"id\": \"the storyline's id\", \"title\": \"a short name for the story, "
          "kept from the notes unless the story has changed\", \"summary\": \"two or three sentences on "
          "what the story is and where it stands now\", \"status\": \"escalating\", \"facts\": [{\"day\": \"YYYY-MM-DD\", \"fact\": "
          "\"one sentence\", \"cite\": \"E3 to keep a fact from the notes, or the number of today's "
          "report it comes from\"}]}]}\n\n"
          "Storylines:\n\n"
          (str/join "\n\n"
                    (for [[story sources] candidates]
                      (str "### id: " story "\n"
                           (note-block (get notes story) (get per-story story)) "\n"
                           "Today's reports:\n"
                           (str/join "\n" (map report sources))
                           (dossier-block (get dossiers story))))))})))

;; --- the answer ------------------------------------------------------------------

(defn parse-answer
  "The storylines of the analyst's JSON answer, or nil when it isn't any.
  A fence or prose around the JSON is ignored."
  [answer]
  (let [s (str answer)
        from (str/index-of s "{")
        to (str/last-index-of s "}")]
    (when (and from to (< from to))
      (let [parsed (try (json/read-str (subs s from (inc to))) (catch Throwable _ nil))
            lines (get parsed "storylines")]
        (when (sequential? lines)
          (filterv map? lines))))))

(defn- cite-of
  "What a fact's cite names: [:kept id] or [:report n], or nil."
  [cite]
  (let [c (str/trim (str cite))]
    (cond
      (re-matches #"E\d+" c) [:kept c]
      :else (some->> (re-find #"\d+" c) parse-long (vector :report)))))

(defn apply-answer
  "The updated notes, {story note}, for the storylines the answer covers
  among `candidates`. A fact keeps the report it came from, so a digest can
  cite it: one kept from the notes keeps its old day and report, one from
  today's reports takes that report's. A fact whose cite names neither is
  dropped, and a storyline the answer gave no facts keeps its old note."
  [day candidates notes ids answer max-facts]
  (let [wanted (into {} candidates)
        by-n (into {} (map (juxt :n identity)) (mapcat second candidates))]
    (into {}
          (keep (fn [line]
                  (let [story (str (get line "id"))
                        old (get notes story)]
                    (when (contains? wanted story)
                      (let [facts (->> (get line "facts")
                                       (filter map?)
                                       (keep (fn [f]
                                               (let [text (clip (get f "fact") 300)
                                                     [kind ref] (cite-of (get f "cite"))]
                                                 (when-not (str/blank? text)
                                                   (case kind
                                                     :kept (when-let [k (get ids ref)] (assoc k :text text))
                                                     :report (when-let [s (get by-n ref)]
                                                               {:day day :text text :url (:url s)
                                                                :source (:source s) :headline (:title s)})
                                                     nil)))))
                                       (sort-by :day)
                                       (take-last max-facts)
                                       vec)]
                        (when (seq facts)
                          [story {:story story
                                  :title (clip (or (not-empty (get line "title")) (:title old)
                                                   (:title (first (wanted story))))
                                               120)
                                  :summary (clip (or (get line "summary") (:summary old)) 600)
                                  :status (or (status-of (get line "status")) (:status old))
                                  :facts facts
                                  :first-day (or (:first-day old) day)
                                  :last-day day}]))))))
          (parse-answer answer))))

;; --- using the notes -------------------------------------------------------------

(defn background
  "What the daily briefing is told about the stories still running, from
  their notes: each one's summary and latest facts. nil when none of
  today's stories has a note."
  [notes sources long-date]
  (let [running (->> sources
                     (filter #(> (or (:days %) 1) 1))
                     (keep #(get notes (:story %)))
                     distinct
                     (take 8))]
    (when (seq running)
      (str "## The stories still running\n\n"
           "These are the notes kept on the stories of today's sources that earlier briefings followed. "
           "Take them as background the reader already has, and say what today's reports add or "
           "change. Their facts are not today's sources, so cite only the numbered sources below.\n\n"
           (str/join "\n\n"
                     (for [{:keys [title summary status facts]} running]
                       (str "### " title (when status (str " (" status ")")) "\n" summary
                            (when (seq facts)
                              (str "\n" (str/join "\n" (for [f (take-last 4 facts)]
                                                         (str "- " (long-date (:day f)) ": " (:text f)))))))))))))

(defn facts-between
  "A note's facts dated from `from` to `to`, as sources a digest can number
  and cite: the report's headline as the title and the fact itself."
  [note from to]
  (vec (for [{:keys [day text url source headline]} (:facts note)
             :when (and url (<= (compare from day) 0 (compare to day)))]
         {:day day :title (or headline text) :url url :source source :fact text})))
