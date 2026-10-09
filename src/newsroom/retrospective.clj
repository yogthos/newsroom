(ns newsroom.retrospective
  "The pure core of the retrospective: no IO, no clock, no storage.

  The briefings and digests project where things go, and the outlets put
  forward their own expectations in their reports. Each is recorded as a
  projection when it is made: a claim, a subject, the storyline it is about
  and the day by which it should be known whether it held. Newsroom's own
  are read from each briefing and digest once it is written, the outlets'
  from the dossiers, credited to the outlet of the report that carries
  them.

  Once a week or a month is over and its digest written, the projections
  that have fallen due are judged against what happened since: held,
  partly, failed, or still open, with how close the outcome came, from 0 to
  1, and why. An open one is given another period, and one open for far
  too long is given up on. From the verdicts a running list of lessons is
  kept, each a rule for the next projection tied to a subject.

  The verdicts make the standings: within each subject, every outlet, and
  newsroom itself, scored by the closeness of its calls. An outlet that
  reads technology well may read geopolitics badly, so a record is only
  ever one outlet's on one subject. The briefings and digests are told the
  lessons and the standings, and the analyst sees each outlet's record on
  the subject of what it expects."
  (:require [clojure.data.json :as json]
            [clojure.math :as m]
            [clojure.string :as str]))

;; --- subjects --------------------------------------------------------------------

(def subjects
  "What a projection can be about. A record is kept per outlet per subject."
  ["geopolitics" "conflict" "domestic politics" "economy" "markets" "energy" "technology" "science"])

(defn subject-of
  "`v` as one of `subjects`, by its words when it names none outright;
  geopolitics when nothing fits."
  [v]
  (let [s (some-> v str str/trim str/lower-case)]
    (cond
      (nil? s) "geopolitics"
      (some #{s} subjects) s
      (re-find #"war\b|military|conflict|securit|defen[cs]e|missile|troops|ceasefire" s) "conflict"
      (re-find #"market|stock|bond|yield|currenc|equit|commodit|price" s) "markets"
      (re-find #"energy|oil|gas\b|power|electric|coal|nuclear|renewable" s) "energy"
      (re-find #"tech|\bai\b|chip|semiconductor|software|comput|cyber" s) "technology"
      (re-find #"scien|health|climate|space|medic|research" s) "science"
      (re-find #"econom|trade|tariff|inflation|fiscal|monetar|rates|growth|debt|labou?r|jobs" s) "economy"
      (re-find #"domestic|election|parliament|party|vote|congress|government" s) "domestic politics"
      :else "geopolitics")))

;; --- when a projection falls due -------------------------------------------------

(def ^:private min-days
  "The soonest a projection is checked after it is made."
  3)

(def ^:private default-days
  "When a projection that gives no time is checked."
  30)

(def ^:private max-days
  "The latest a projection is checked: about as long as the storylines'
  notes are kept, which it is judged by."
  180)

(def ^:private give-up-days
  "How long after it was made an open projection is given up on."
  240)

(defn- date [day] (java.time.LocalDate/parse day))

(defn- plus-days [day n] (str (.plusDays (date day) n)))

(defn- days-between [a b] (.between java.time.temporal.ChronoUnit/DAYS (date a) (date b)))

(defn- day-of [v]
  (let [s (str/trim (str v))]
    (when (re-matches #"\d{4}-\d{2}-\d{2}" s)
      (try (str (date s)) (catch Throwable _ nil)))))

(defn due-of
  "The day a projection made on `made-on` is checked, from `v`, the day it
  says it will be known by: no sooner than a few days on, no later than
  half a year, and a month on when `v` is no day."
  [made-on v]
  (let [d (day-of v)
        days (if d (days-between made-on d) default-days)]
    (plus-days made-on (max min-days (min max-days days)))))

;; --- answers ---------------------------------------------------------------------

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

(defn- clip [s n]
  (let [s (str/trim (str s))]
    (if (> (count s) n) (str (subs s 0 (dec n)) "…") s)))

(defn- text [v n]
  (when (string? v)
    (let [s (clip v n)]
      (when-not (str/blank? s) s))))

(defn- maps [v] (filterv map? (if (sequential? v) v [])))

(defn- numbers
  "The numbers `v` names, 3, \"3\", \"[3]\" or a list of them."
  [v]
  (->> (if (sequential? v) v [v])
       (mapcat #(cond (integer? %) [%]
                      (string? %) (keep (fn [d] (when (<= (count d) 9) (parse-long d))) (re-seq #"\d+" %))))
       distinct
       vec))

(defn- round2 [x] (/ (m/round (* 100 (double x))) 100.0))

;; --- newsroom's own projections --------------------------------------------------

(def max-own
  "The most projections read from one briefing or digest."
  6)

(defn extraction-prompt
  "The prompt that has the model list the projections `markdown`, `what`
  (the briefing for a day, the digest for a week), makes, as of `day`."
  [what day markdown]
  (str "You list the projections a piece of news analysis makes, so they can be checked once their time "
       "comes. Below is " what ", written on " day ".\n\n"
       "A projection is a claim about what will happen after " day " that can later be found true or "
       "false: an event that will or won't happen, a figure that will move one way, a decision an actor "
       "will take, by some time. Take the ones the text commits to, in its analysis or outlook and wherever else it "
       "says which way things are heading. Where it lays out both branches, take the one it says the "
       "evidence points to, and leave out a branch it doesn't commit to. Leave out what nothing could "
       "disprove, what is only a risk it names, and what has already happened. Take at most " max-own
       ", the ones that matter most.\n\n"
       "For each one give:\n"
       "- claim: the projection in one sentence that stands on its own, naming the actors and what is "
       "expected, with no reference back to the text.\n"
       "- subject: one of " (str/join ", " subjects) ".\n"
       "- by: the day, YYYY-MM-DD, by which it should be known whether it held: the text's own horizon "
       "where it gives one, else when it would show.\n"
       "- cites: the numbers of the sources the text cites for it, the [n] next to it or the story it is "
       "about.\n\n"
       "Answer with JSON only, in this shape:\n"
       "{\"projections\": [{\"claim\": \"...\", \"subject\": \"economy\", \"by\": \"YYYY-MM-DD\", \"cites\": [3]}]}\n"
       "An empty list when it makes none.\n\n"
       "The text:\n\n" markdown))

(defn- storyline-of
  "The storyline of the first of `cites` among the `sources` that has one,
  the precedents passed over."
  [cites by-n]
  (some #(let [s (by-n %)] (when-not (:precedent s) (:story s))) cites))

(defn parse-projections
  "The projections in the model's `answer`, made on `made-on`, each
  {:made-on :claim :subject :due :story}: the storyline of the first of
  `sources` it cites. [] when it lists none, nil when the answer holds no
  list."
  [answer made-on sources]
  (let [ps (get (json-object answer) "projections")
        by-n (into {} (map (juxt :n identity)) sources)]
    (when (sequential? ps)
      (->> (maps ps)
           (keep (fn [p]
                   (when-let [claim (text (get p "claim") 400)]
                     {:made-on made-on
                      :claim claim
                      :subject (subject-of (get p "subject"))
                      :due (due-of made-on (get p "by"))
                      :story (storyline-of (numbers (get p "cites")) by-n)})))
           (take max-own)
           vec))))

;; --- what the outlets expect -----------------------------------------------------

(def max-outlet
  "The most of the outlets' expectations recorded a day, the main stories'
  first, so the retrospective can keep up with them."
  20)

(defn outlet-projections
  "The expectations the dossiers on `stories` found the outlets putting
  forward, as projections made on `day` by the outlet credited with each:
  at most `max-outlet`, the first stories' first."
  [stories dossiers day]
  (->> (for [{:keys [key story]} stories
             e (:expectations (get dossiers key))]
         (merge {:made-on day :story story}
                (select-keys e [:claim :subject :by :source :outlet :url])
                {:due (due-of day (:due e))}))
       (take max-outlet)
       vec))

;; --- judging ---------------------------------------------------------------------

(defn number-projections
  "The projections with an id each, P1, P2, …, as the judge names them."
  [rows]
  (mapv (fn [r i] (assoc r :pid (str "P" (inc i)))) rows (range)))

(def ^:private facts-shown
  "The most facts of a storyline the judge is shown for a projection."
  10)

(defn- maker [{:keys [source by]}]
  (if source
    (str source (when by (str " (" by ")")))
    "newsroom"))

(defn- projection-block [{:keys [pid made-on due subject claim story] :as p} notes long-date]
  (let [note (get notes story)
        since (->> (:facts note)
                   (filter #(pos? (compare (:day %) made-on)))
                   (take-last facts-shown))]
    (str "### " pid "\n"
         pid ", made " (long-date made-on) " by " (maker p) ", due " (long-date due) " (" subject "): " claim
         (when note
           (str "\nSince then on its storyline, " (:title note) ":\n"
                (if (seq since)
                  (str/join "\n" (for [{:keys [day text]} since] (str "- " (long-date day) ": " text)))
                  "Nothing recorded."))))))

(defn judge-prompt
  "The prompt that has the model judge the numbered `projections` as of
  `today`, each with the facts recorded on its storyline since it was made,
  from `notes`, {story note}, and `general`, what the period's digest and
  days said."
  [today projections notes general long-date]
  (str "You check how projections turned out. Today is " today ".\n\n"
       "Each projection below was made on a day, by newsroom's analysis or by an outlet in its reporting, "
       "and was due to be known by now. Under each is what has been recorded on its storyline since it was "
       "made, and after them all is what the latest digest and the daily briefings say happened. Judge "
       "each one on that evidence alone, not on what you know from elsewhere:\n"
       "- held: it happened as claimed.\n"
       "- partly: the direction was right but the scale, the timing or the form was off, or only part of "
       "it happened.\n"
       "- failed: it didn't happen, or the opposite did.\n"
       "- open: the evidence doesn't settle it yet.\n"
       "Give closeness, from 0 to 1: how near the outcome came to the claim, 1 when it came true as stated "
       "and 0 when the opposite happened. Give the reason in a sentence or two: what actually happened, "
       "and why the projection held or missed, what it read right or wrong about the forces at work, the "
       "actors' interests and their means, or the timing.\n\n"
       "Answer with JSON only, in this shape:\n"
       "{\"verdicts\": [{\"id\": \"P1\", \"verdict\": \"partly\", \"closeness\": 0.5, \"reason\": \"...\"}]}\n\n"
       "Projections:\n\n"
       (str/join "\n\n" (map #(projection-block % notes long-date) projections))
       (when-not (str/blank? general)
         (str "\n\n## What happened\n\n" general))))

(def verdicts
  "What a judged projection's status can be."
  ["held" "partly" "failed" "open"])

(def ^:private settled #{"held" "partly" "failed"})

(def ^:private verdict-closeness
  {"held" 1.0 "partly" 0.5 "failed" 0.0})

(defn- closeness-of [v verdict]
  (let [n (cond (number? v) (double v)
                (string? v) (some-> (re-find #"\d+(?:\.\d+)?" v) parse-double))]
    (cond
      (not (settled verdict)) nil
      (nil? n) (verdict-closeness verdict)
      (> n 1.0) (round2 (min 1.0 (/ n 100.0)))
      :else (round2 (max 0.0 n)))))

(defn parse-verdicts
  "The verdicts of the judge's `answer` on the projections with the ids
  `pids`, {pid {:verdict :closeness :reason}}: an unknown id or verdict is
  dropped, the first answer for an id stands. nil when the answer holds no
  list."
  [answer pids]
  (let [vs (get (json-object answer) "verdicts")]
    (when (sequential? vs)
      (reduce (fn [out v]
                (let [pid (str/upper-case (str/trim (str (get v "id"))))
                      verdict (some #{(some-> (get v "verdict") str str/trim str/lower-case)} verdicts)]
                  (if (and verdict (contains? pids pid) (not (contains? out pid)))
                    (assoc out pid {:verdict verdict
                                    :closeness (closeness-of (get v "closeness") verdict)
                                    :reason (text (get v "reason") 600)})
                    out)))
              {}
              (maps vs)))))

(def ^:private period-days {:week 7 :month 30})

(defn apply-verdicts
  "The `projections` the `verdicts` say something about, updated: a
  settled one with its status, closeness and reason, an open one due again
  a period after `to`, or given up on as unresolved when it was made long
  ago. Each is marked as checked by the retrospective of `kind` and
  `period`."
  [projections verdicts {:keys [kind period to]}]
  (vec (for [{:keys [pid made-on] :as p} projections
             :let [{:keys [verdict closeness reason]} (get verdicts pid)]
             :when verdict]
         (-> (dissoc p :pid)
             (assoc :reason reason :checked-kind (name kind) :checked-period period)
             (merge (cond
                      (settled verdict) {:status verdict :closeness closeness}
                      (> (days-between made-on to) give-up-days) {:status "unresolved" :closeness nil}
                      :else {:status "open" :closeness nil
                             :due (plus-days to (period-days kind 7))}))))))

;; --- the lessons -----------------------------------------------------------------

(def max-lessons
  "The most lessons kept."
  10)

(defn- verdict-line [{:keys [claim subject status closeness reason source]}]
  (str "- (" subject ", " (or source "newsroom") ") " status
       (when closeness (str ", closeness " closeness))
       ": " claim (when reason (str " " reason))))

(defn lessons-prompt
  "The prompt that has the model rewrite the `lessons` kept so far from
  the projections `judged` in the retrospective of `label`."
  [label judged lessons]
  (str "You keep the track record of a daily news analysis: how its projections, and the expectations "
       "the outlets it reads put forward, have turned out, so that the next ones are better.\n\n"
       "Below are the projections judged in the retrospective of " label ", each with its subject, who "
       "made it, the verdict, how close it came and why, and the lessons kept so far. Rewrite the "
       "lessons. A lesson is a pattern in what went right or wrong and why, as a rule for the next "
       "projection: a force that was overweighted or missed, actors whose stated plans outran their "
       "means, things that ran slower or faster than expected, an outlet whose expectations on a subject "
       "lean one way. Tie each to a subject, one of " (str/join ", " subjects) ". Keep at most "
       max-lessons ", merge the ones that repeat, and drop a lesson the record no longer bears out. Also "
       "write a summary of two or three sentences on how the period's projections held.\n\n"
       "Answer with JSON only, in this shape:\n"
       "{\"summary\": \"...\", \"lessons\": [{\"subject\": \"economy\", \"lesson\": \"...\"}]}\n\n"
       "Judged:\n"
       (str/join "\n" (map verdict-line judged))
       "\n\nThe lessons so far:\n"
       (if (seq lessons)
         (str/join "\n" (for [{:keys [subject lesson]} lessons] (str "- (" subject ") " lesson)))
         "None yet.")))

(defn parse-lessons
  "The summary and lessons of the model's `answer`, {:summary :lessons},
  or nil when it holds neither."
  [answer]
  (let [m (json-object answer)
        summary (text (get m "summary") 800)
        lessons (vec (for [l (maps (get m "lessons"))
                           :let [t (text (get l "lesson") 400)]
                           :when t]
                       {:subject (subject-of (get l "subject")) :lesson t}))]
    (when (or summary (seq lessons))
      {:summary summary :lessons (vec (take max-lessons lessons))})))

;; --- the standings ---------------------------------------------------------------

(def ^:private prior
  "The closeness a record is held to until it is long: a record of n calls
  scores as if it had `prior-weight` more calls at `prior`."
  0.5)

(def ^:private prior-weight 2)

(def own-name "Newsroom")

(defn- record
  "The record of one outlet's `rows` on one subject. `shared` are the
  source names that several outlets came through, like a web search's,
  which name none of them."
  [rows shared]
  (let [n (count rows)
        total (reduce + (map :closeness rows))
        names (frequencies (remove shared (keep :source rows)))
        outlet (:outlet (first rows))
        status (frequencies (map :status rows))]
    {:outlet outlet
     :name (cond
             (nil? outlet) own-name
             ;; an agency, or an outlet with no web address, goes by its own name
             (not (str/includes? outlet ".")) outlet
             (empty? names) outlet
             :else (key (apply max-key val (sort-by key names))))
     :judged n
     :held (get status "held" 0)
     :partly (get status "partly" 0)
     :failed (get status "failed" 0)
     :closeness (round2 (/ total n))
     :score (round2 (/ (+ total (* prior prior-weight)) (+ n prior-weight)))}))

(defn standings
  "The settled projections `rows` as standings, {subject [record]}: within
  each subject a record for every outlet, newsroom's own under no outlet,
  best first. A record is {:outlet :name :judged :held :partly :failed
  :closeness :score}, :closeness the mean and :score the mean held toward
  the middle until the record is long, so a single lucky call doesn't top
  the table."
  [rows]
  (let [rows (filter #(and (settled (:status %)) (:closeness %)) rows)
        shared (->> (filter :source rows)
                    (group-by :source)
                    (keep (fn [[source rs]] (when (next (distinct (map :outlet rs))) source)))
                    set)]
    (->> (group-by :subject rows)
         (into {} (map (fn [[subject rs]]
                         [subject (->> (group-by :outlet rs)
                                       vals
                                       (map #(record % shared))
                                       (sort-by (juxt (comp - :score) (comp - :judged) :name))
                                       vec)]))))))

(defn record-of
  "The standings as a lookup, {[outlet subject] record}."
  [standings]
  (into {} (for [[subject recs] standings, r recs] [[(:outlet r) subject] r])))

(defn record-text
  "A record in a few words: 4 judged, 2 held, 1 partly, 1 failed,
  closeness 0.6."
  [{:keys [judged held partly failed closeness]}]
  (str judged " judged, " held " held, " partly " partly, " failed " failed, closeness " closeness))

(def ^:private outlets-shown
  "The most outlets the record block lists for a subject."
  6)

(def open-shown
  "The most forecasts still open the analyst is shown."
  12)

(defn forecasts-shown
  "The forecasts still `open` the analyst is shown, at most `n`: those on
  `storylines` running today first, then the soonest due."
  ([open storylines] (forecasts-shown open storylines open-shown))
  ([open storylines n]
   (->> open
        (sort-by (juxt #(if (contains? storylines (:story %)) 0 1) :due))
        (take n)
        vec)))

(defn record-block
  "What the analyst is told of the track record: the lessons of the latest
  `retrospective`, newsroom's own record by subject, the records of
  `outlets` (all of them when nil) by subject, and the forecasts still
  `open`. nil when there is nothing to tell."
  ([retrospective standings outlets] (record-block retrospective standings outlets nil))
  ([retrospective standings outlets open]
   (let [lessons (:lessons retrospective)
         own (for [[subject recs] (sort-by key standings)
                   r recs :when (nil? (:outlet r))]
               [subject r])
         others (for [[subject recs] (sort-by key standings)
                      :let [rs (->> recs
                                    (filter :outlet)
                                    (filter #(or (nil? outlets) (contains? outlets (:outlet %))))
                                    (take outlets-shown))]
                      :when (seq rs)]
                  [subject rs])]
     (when (or (seq lessons) (seq own) (seq others) (seq open))
       (str "## The track record\n\n"
            "Newsroom checks its projections, and the expectations the outlets put forward, once their time "
            "comes. Use the record to calibrate the projections you make now: lean on what has held, correct "
            "for the patterns that have missed, and weigh an outlet's expectation by its record on that "
            "subject, since an outlet that reads one subject well may read another badly. Closeness runs "
            "from 0, the opposite happened, to 1, it came true as stated. Don't write about the scores or "
            "the lessons themselves."
            (when (seq lessons)
              (str "\n\nLessons"
                   (when-let [l (:label retrospective)] (str ", as of the retrospective of " l))
                   ":\n"
                   (str/join "\n" (for [{:keys [subject lesson]} lessons] (str "- (" subject ") " lesson)))))
            (when (seq own)
              (str "\n\nHow newsroom's own projections have held, by subject:\n"
                   (str/join "\n" (for [[subject r] own] (str "- " subject ": " (record-text r))))))
            (when (seq others)
              (str "\n\nHow the outlets have called things, by subject, best first:\n"
                   (str/join "\n" (for [[subject rs] others]
                                    (str "- " subject ": "
                                         (str/join "; " (for [r rs] (str (:name r) ": " (record-text r)))))))))
            (when (seq open)
              (str "\n\nThe forecasts still open, made in earlier briefings and not yet settled. Where today's "
                   "news bears on one, say how today moves it, toward it or away from it, and why, and revise "
                   "it where the evidence calls for it. Leave out the ones today doesn't touch:\n"
                   (str/join "\n" (for [{:keys [subject made-on due claim]} open]
                                     (str "- (" subject ", made " made-on ", due " due ") " claim))))))))))
