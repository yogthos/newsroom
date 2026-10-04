(ns newsroom.precedents
  "The pure core of the historical round: no IO, no clock, no storage.

  Before the analyst writes the briefing, a researcher (the analyst's
  model, or the :research role's) looks for the past that says how the
  day's situation may develop: above all a period when a similar
  combination of the day's trends came together, and how it resolved,
  then earlier events like single stories. Their course is the ground the
  briefing's projections stand on. To see the trends, it is shown how long
  and how widely each story has run and the note on its storyline. It works in
  rounds. It is shown the day's stories and the prompt, precedents.md by
  default, and answers with search queries; it is shown their results,
  each with an id, and searches again or picks. A pick names a result, the
  day's stories it informs and a note on what it teaches: what happened,
  how long it took, what it led to.

  The picks become sources of their own, marked :precedent true, numbered
  after the day's, and cited like any other. A precedent takes no part in
  the storylines: it is background, not a story that started today or an
  earlier day of one that did."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [newsroom.news :as news]
            [newsroom.template :as template]))

(def max-stories
  "How many of the day's stories the researcher is shown: the heaviest,
  which are the ones the briefing will lead with, and enough of them to
  see the trends that run through the day."
  30)

(def max-rounds
  "The most rounds of searching, each a model call, before the researcher
  has to pick from what it has found."
  4)

(def results-per-query
  "How many results each query brings back."
  5)

(defn- clip [s n]
  (let [s (str s)]
    (if (> (count s) n) (str (subs s 0 n) "…") s)))

(defn- story-line
  "A story as the researcher sees it: how widely and how long it has run,
  which says whether it is part of a trend, and its storyline's `note`
  when it has one, which says what the trend is."
  [s note long-date]
  (let [outlets (news/outlets s)
        days (or (:days s) 1)]
    (str "[" (:n s) "] " (:title s)
         (when-not (str/blank? (:source s)) (str " (" (:source s) ")"))
         (when (or (> outlets 1) (> days 1))
           (str "\nCoverage: " (str/join ", " (cond-> []
                                                (> outlets 1) (conj (str outlets " outlets today"))
                                                (> days 1) (conj (str "in the news on " days " days"))))))
         (when-not (str/blank? (:summary s)) (str "\n" (clip (:summary s) 280)))
         (when-not (str/blank? (:summary note))
           (str "\nThe storyline so far"
                (when-let [d (:first-day note)] (str ", since " (long-date d)))
                (when-let [st (:status note)] (str ", " st))
                ": " (clip (:summary note) 400))))))

(defn- story-heads
  "The sources the researcher is shown, the first `max-stories` stories:
  of a story told in several reports, its first, under the story's title."
  [sources]
  (->> sources
       (reduce (fn [[seen out] s]
                 (if (and (:group s) (contains? seen (:group s)))
                   [seen out]
                   [(cond-> seen (:group s) (conj (:group s)))
                    (conj out (cond-> s (:group-title s) (assoc :title (:group-title s))))]))
               [#{} []])
       second
       (take max-stories)))

(defn render-prompt
  "The researcher's first prompt: the Selmer `template` with {{date}},
  {{stories}}, the day's numbered `sources` with the `notes` on their
  storylines, {story note}, {{trends}}, the trends the desk found running
  through the day, and {{searches}} and {{rounds}}, the budget, filled in.
  A template with no place for the stories gets them after it, and one
  with no place for the trends gets them just before the stories."
  ([template date sources notes searches long-date]
   (render-prompt template date sources notes searches long-date nil))
  ([template date sources notes searches long-date trends]
   (template/fill (news/place-vars template (if trends ["trends" "stories"] ["stories"]) "stories")
                  {:date date
                   :stories (str/join "\n\n" (map #(story-line % (get notes (:story %)) long-date)
                                                  (story-heads sources)))
                   :trends trends
                   :searches searches
                   :rounds max-rounds})))

(defn- json-object
  "The JSON object in `answer`, read with string keys, or nil. A fence or
  prose around it is ignored."
  [answer]
  (let [s (str answer)
        from (str/index-of s "{")
        to (str/last-index-of s "}")]
    (when (and from to (< from to))
      (let [parsed (try (json/read-str (subs s from (inc to))) (catch Throwable _ nil))]
        (when (map? parsed) parsed)))))

(defn- story-numbers [v]
  (->> (if (sequential? v) v [v])
       (keep #(cond (integer? %) (long %)
                    (string? %) (parse-long (str/trim (str/replace % #"[\[\]]" "")))))
       distinct
       vec))

(defn parse-answer
  "What the researcher's `answer` asks for: {:picks [{:id :stories :note}]}
  when it has picked, which ends the research, {:queries [...]} when it
  wants to search, nil when it says neither. Ids are upper-cased, so r4 is
  R4."
  [answer]
  (let [m (json-object answer)
        picks (get m "precedents")
        queries (get m "queries")]
    (cond
      (sequential? picks)
      {:picks (vec (for [p picks
                         :when (map? p)
                         :let [id (str/upper-case (str/trim (str (get p "id"))))]
                         :when (not (str/blank? id))]
                     {:id id
                      :stories (story-numbers (or (get p "stories") (get p "story")))
                      :note (str/trim (str (get p "note")))}))}

      (sequential? queries)
      {:queries (->> queries
                     (map #(str/trim (str %)))
                     (remove str/blank?)
                     distinct
                     vec)})))

(defn search-source
  "A :web-search source that runs `queries`."
  [queries]
  {:type :web-search :name "Precedent search"
   :results results-per-query :queries queries})

(defn new-results
  "The `items` a search found that the researcher hasn't seen: none that is
  one of the day's `stories` or a result shown in an earlier round, and
  each story once. Each gets an :id, R1 on from `shown`'s count, which
  the researcher picks it by."
  [items stories shown]
  (->> (news/unseen-items (news/dedupe-items items) (concat stories shown))
       (map-indexed (fn [i item] (assoc (dissoc item :vector :story :n)
                                        :id (str "R" (+ 1 i (count shown))))))
       vec))

(defn- result-entry [{:keys [id title source published url summary]}]
  (str id ". " title
       (when-not (str/blank? source) (str " (" source ")"))
       (when published (str ", " published))
       "\n" url
       (when-not (str/blank? summary) (str "\n" (clip summary 600)))))

(defn results-message
  "What the researcher is told after a round: the `results` it brought
  back, then what it has left to search with, `searches` over `rounds`
  rounds. With none left of either, it is told to pick."
  [results searches rounds]
  (str (if (seq results)
         (str "Results:\n\n" (str/join "\n\n" (map result-entry results)))
         "The searches found nothing new.")
       "\n\n"
       (if (and (pos? searches) (pos? rounds))
         (str "You have " searches (if (= 1 searches) " search" " searches") " left over "
              rounds (if (= 1 rounds) " round" " rounds")
              ". Search again, as {\"queries\": [...]}, or pick, as {\"precedents\": [...]}.")
         "That was the last round. Pick now, as {\"precedents\": [...]}.")))

(def pick-now
  "What the researcher is told when it searched with nothing left to
  search with."
  "There are no searches left. Pick from the results you have, as {\"precedents\": [...]}.")

(defn picked
  "The precedents the researcher `picks` from the `results` it was shown,
  as sources: each a result marked :precedent true, with the :informs
  numbers of the day's stories, those of `day-ns`, and the researcher's
  :note. A pick of no result shown is dropped, and so is a second pick of
  one."
  [picks results day-ns]
  (let [by-id (into {} (map (juxt :id identity)) results)
        day-ns (set day-ns)]
    (->> picks
         (keep (fn [{:keys [id stories note]}]
                 (when-let [r (by-id id)]
                   (cond-> (assoc (dissoc r :id) :precedent true)
                     (seq (filter day-ns stories)) (assoc :informs (vec (filter day-ns stories)))
                     (not (str/blank? note)) (assoc :note note)))))
         (news/dedupe-items)
         vec)))
