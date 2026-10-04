(ns newsroom.precedents
  "The pure core of the historical round: no IO, no clock, no storage.

  Before the analyst writes the briefing, it is asked which of the day's
  stories have a past worth knowing: an earlier event of the same kind,
  whose course says what today's may lead to. The answer is search
  queries; their results are gathered as sources of their own, marked
  :precedent true, numbered after the day's, and cited like any other.
  A precedent takes no part in the storylines: it is background, not a
  story that started today or an earlier day of one that did."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [newsroom.news :as news]))

(def max-stories
  "How many of the day's stories the queries are chosen from: the heaviest,
  which are the ones the briefing will lead with."
  10)

(defn- story-line [s]
  (str "[" (:n s) "] " (:title s)
       (when-not (str/blank? (:source s)) (str " (" (:source s) ")"))
       (when-not (str/blank? (:summary s))
         (str "\n" (subs (:summary s) 0 (min (count (:summary s)) 280))))))

(defn queries-prompt
  "The prompt that asks for the search queries, given the day's `sources`
  numbered as the prompt shows them."
  [sources max-queries]
  (str "You find the historical background for a daily news briefing, to ground its analysis in precedent.\n\n"
       "Here are the day's stories. For each one where the past has something to teach — an earlier event "
       "of the same kind, a policy tried before, a cycle or standoff that has run before — write a web "
       "search query that would find the telling coverage of that earlier episode. Name the actors and the "
       "measure in it, and the years that matter. Leave out a story whose past is a curiosity rather than "
       "a guide, and don't write a query for the day's news itself: search what came before.\n\n"
       "Answer with JSON only, in this shape:\n"
       "{\"queries\": [\"central bank rate pause cycles history 2015 2019\", \"EU tariffs on Chinese steel 2018 outcome\"]}\n\n"
       "At most " max-queries " queries, the most instructive first. An empty list is a fine answer "
       "when none of the day's stories has a past worth consulting.\n\n"
       "Today's stories:\n\n"
       (str/join "\n\n" (map story-line (take max-stories sources)))))

(defn parse-queries
  "The search queries of the model's JSON answer, or nil when it isn't any.
  A fence or prose around the JSON is ignored."
  [answer]
  (let [s (str answer)
        from (str/index-of s "{")
        to (str/last-index-of s "}")]
    (when (and from to (< from to))
      (let [parsed (try (json/read-str (subs s from (inc to))) (catch Throwable _ nil))
            queries (get parsed "queries")]
        (when (sequential? queries)
          (->> (map #(str/trim (str %)) queries)
               (remove str/blank?)
               (distinct)
               (vec)))))))

(defn search-source
  "A :web-search source that runs the queries the model chose: at most
  `max-queries` of them, `results` results each."
  [queries results]
  {:type :web-search :name "Precedent search"
   :results results :queries queries})

(defn mark
  "The search results as precedent sources: marked :precedent true, credited
  to the search that found them, without their :vector and :story, and with
  copies of one story kept once."
  [items source]
  (mapv #(-> %
             (dissoc :vector :story :n)
             (assoc :precedent true :source source))
        (news/dedupe-items items)))
