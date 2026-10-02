(ns newsroom.pipeline
  "A day's run, on ebb: every source fetched at once on the blocking pool,
  each under its own timeout, so a slow or broken feed costs only its own
  items; then the pure core (newsroom.news) dedupes and numbers them, the
  analyst model writes the briefing, and the day is stored.

  A run is one cancellable task. Cancelling it interrupts whatever fetch or
  model call is in flight and stores nothing. Only one run goes at a time.

  Progress is published to `status`, a glimmer ratom, so every open page
  shows it live."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [ebb.core :as m]
            [glimmer.ratom :as ratom]
            [jolt.time]
            [newsroom.config :as config]
            [newsroom.embed :as embed]
            [newsroom.feed :as feed]
            [newsroom.llm.client :as llm]
            [newsroom.llm.providers :as providers]
            [newsroom.news :as news]
            [newsroom.sources :as sources]
            [newsroom.store :as store]))

(defonce ^{:doc "The current or last run, for the page to show."}
  status (ratom/atom {:state :idle}))

(defonce ^{:doc "The run in flight: {:token :cancel}, or nil."}
  current (atom nil))

(defonce ^{:doc "Bumped whenever a day is stored, so pages showing it refresh."}
  stored (ratom/atom 0))

(def ^:private embed-model
  "The embedding model, loaded once on first use: 6MB of resources that
  reads in well under a second."
  (delay (embed/load-model)))

(defn- now [] (System/currentTimeMillis))

(defn today [] (str (java.time.LocalDate/now)))

(defn- minus-days [day n]
  (str (.minusDays (java.time.LocalDate/parse day) n)))

(defn- plus-days [day n]
  (str (.plusDays (java.time.LocalDate/parse day) n)))

(defn- update-status!
  "Apply f to the status when it belongs to run `run-id`. A run's writes are
  tagged so a cancelled run whose fiber is still winding down cannot write
  over the status of the run that replaced it. Sources report from several
  threads at once, which relies on swap! being atomic (glimmer v0.1.4)."
  [run-id f & args]
  (swap! status (fn [st] (if (identical? run-id (:run st)) (apply f st args) st))))

(def ^:private max-events 60)

(defn- log!
  "Add an event, {:text} with an optional :url and :level, to the run's log."
  [run-id event]
  (update-status! run-id update :events
                  (fn [es] (vec (take-last max-events (conj (or es []) (assoc event :at (now))))))))

;; --- watching the model write ------------------------------------------------------

(defn- word-count [text] (count (re-seq #"[A-Za-z][A-Za-z'’-]*" text)))

(defn- last-heading [text]
  (some-> (last (re-seq #"(?m)^#{2,3}\s+(.+)$" text)) second str/trim))

(defn- tail [text n]
  (let [t (str/trim text)]
    (if (<= (count t) n)
      t
      (let [cut (subs t (- (count t) n))
            space (str/index-of cut " ")]
        (str "…" (if space (subs cut (inc space)) cut))))))

(def ^:private flush-ms 300)

(defn- progress-watcher
  "An :on-delta for the model call: it collects the stream, logs when the
  model starts thinking and starts writing, and puts what has been written
  so far on the status at most every flush-ms, plus once at the end
  (`flush!`)."
  [run-id]
  (let [buf (StringBuilder.)
        thought (atom 0)
        said (atom #{})
        flushed (atom 0)
        say-once! (fn [k msg] (when-not (@said k) (swap! said conj k) (log! run-id {:text msg})))
        flush! (fn []
                 (reset! flushed (now))
                 (let [t (str buf)]
                   (update-status! run-id assoc :writing
                                   {:words (word-count t)
                                    :reasoning-words @thought
                                    :section (last-heading t)
                                    :tail (tail t 280)})))]
    {:on-delta (fn [{:keys [text reasoning]}]
                 (locking buf
                   (when reasoning
                     (say-once! :thinking "The model is thinking")
                     (swap! thought + (word-count reasoning)))
                   (when text
                     (say-once! :writing "The model is writing the briefing")
                     (.append buf text))
                   (when (> (- (now) @flushed) flush-ms)
                     (flush!))))
     :flush! (fn [] (locking buf (flush!)))}))

;; --- gathering ---------------------------------------------------------------------

(defn- gather-one
  "Task: one source's items for the day, never failing. A failure or a
  timeout is recorded as that source's :error, so the run goes on without
  it. Only a cancel of the run itself propagates."
  [source {:keys [config] :as ctx}]
  (let [name (sources/source-name source)
        timeout-ms (:source-timeout-ms config 30000)]
    (m/sp
      (let [outcome (m/? (m/timeout (m/attempt (m/via m/blk (sources/fetch-items source ctx)))
                                    timeout-ms ::timed-out))
            result (if (= ::timed-out outcome)
                     {:error (str "timed out after " (quot timeout-ms 1000) "s")}
                     (try {:items (vec (outcome))}
                          (catch Throwable e
                            (if (m/cancelled? e)
                              (throw e)
                              {:error (or (ex-message e) (str e))}))))]
        (update-status! (:run-id ctx) update-in [:sources name] merge
                        (if (:error result)
                          {:state :failed :error (:error result)}
                          {:state :ok :count (count (:items result))}))
        (log! (:run-id ctx)
              (cond
                (= ::timed-out outcome) {:text (str name " timed out") :level :error}
                (:error result) {:text (str name " failed: " (:error result)) :level :error}
                :else {:text (str name ": " (count (:items result)) " items") :level :ok}))
        (assoc result :source name)))))

(defn- round-robin
  "The first of each list, then the second of each, and so on, so that a cap
  on the total trims every list evenly rather than dropping the last ones."
  [lists]
  (loop [lists (remove empty? lists), out []]
    (if (seq lists)
      (recur (remove empty? (map rest lists)) (into out (map first lists)))
      out)))

(defn day-items
  "The items to analyse from each source's result: those published from
  the lookback to the day after (an outlet ahead of the local timezone has
  already dated today's news tomorrow), at most :max-items-per-source from
  each, interleaved."
  [results config day]
  (let [from (minus-days day (:lookback-days config 1))
        to (plus-days day 1)]
    (round-robin
     (map (fn [{:keys [items]}]
            (take (:max-items-per-source config 12) (feed/recent items from to)))
          results))))

;; --- the run -----------------------------------------------------------------------

(defn- write-markdown! [dir day doc]
  (let [f (io/file dir (str day ".md"))]
    (.mkdirs (.getParentFile f))
    (spit f doc)))

(def default-keep-days 100)

(defn prune-days!
  "Drop the days past :keep-days (100 by default, -1 for no limit) from the
  store, and their markdown files. Returns the days dropped."
  [{:keys [config store] :as ctx}]
  (let [gone (store/prune! store (:keep-days config default-keep-days))
        dir (or (:markdown-dir ctx) (config/path "briefings"))]
    (doseq [d gone]
      (let [f (io/file dir (str d ".md"))]
        (when (.exists f) (.delete f))))
    gone))

(defn- previous-day
  "The latest briefing stored before `day`, or nil."
  [store day]
  (some->> (store/days store)
           (filter #(neg? (compare % day)))
           first
           (store/day store)))

(defn- seen-briefings
  "The briefings before `day` whose stories count as seen, newest first:
  the last :seen-days of them (3 by default), since a story that ran two
  days ago, was left out of yesterday's for space, and returns today has
  still been told."
  [store day config]
  (let [n (:seen-days config 3)]
    (when (pos? n)
      (->> (store/days store)
           (filter #(neg? (compare % day)))
           (take n)
           (keep #(store/day store %))))))

(defn- previous-context
  "What the model is told about the last briefing: its overview, as what is
  already established and to be built on rather than told again."
  [day overview]
  (str "## Already established\n\n"
       "This is the overview of the last briefing, for " (sources/long-date day) ". Take it as what "
       "the reader already knows. Don't retell it. Build on it, say what today's news changes, "
       "confirms or overturns, and follow up threads it left open. Its citations belong to that "
       "day's sources, so cite only today's numbered sources below.\n\n"
       overview))

(defn run-task
  "Task: gather, analyse and store `day`. Completes with the stored day's
  summary; fails when nothing was gathered or the model call fails.

  `ctx` is {:config :store}, and optionally :chat (the model call, llm/chat
  by default), :template (the prompt, prompt.md by default),
  :markdown-dir (where the day's .md is written, briefings/ by default) and
  :run-id (the status the run reports to; start-run! sets it, and a run
  without one takes over the status)."
  [{:keys [config store] :as ctx} day]
  (let [own? (nil? (:run-id ctx))
        run-id (or (:run-id ctx) (Object.))
        ctx (assoc ctx :run-id run-id :day day :emit #(log! run-id %))]
    (m/sp
      (let [srcs (:sources config)
            _ (when own? (reset! status {:run run-id :state :starting :day day :started (now)}))
            _ (update-status! run-id assoc :state :gathering
                              :sources (into {} (map (fn [s] [(sources/source-name s) {:state :pending}]) srcs)))
            _ (log! run-id {:text (str "Gathering the news for " (sources/long-date day)
                                       " from " (count srcs) " sources")})
            results (m/? (apply m/join vector (map #(gather-one % ctx) srcs)))
            _ (log! run-id {:text (str "Gathered " (reduce + (map (comp count :items) results))
                                       " items from " (count (remove :error results)) " of "
                                       (count srcs) " sources")})
            _ (store/record-source-health!
               store
               (map (fn [r] {:source (:source r) :error (:error r)}) results))
            gathered (news/dedupe-items (day-items results config day))
            seen (seen-briefings store day config)
            earlier (first seen)
            fresh (news/unseen-items gathered (mapcat :sources seen))
            _ (when (< (count fresh) (count gathered))
                (log! run-id {:text (str "Left out " (- (count gathered) (count fresh))
                                         " stories already in the briefings up to "
                                         (sources/long-date (:day earlier)))}))
            threshold (:dupe-threshold config)
            embedded-items (if threshold
                             (let [model @embed-model]
                               (mapv (fn [i] (assoc i :vector (embed/embed model (str (:title i) ". " (:summary i)))))
                                     fresh))
                             fresh)
            collapsed (news/collapse-similar embedded-items threshold)
            _ (when (< (count collapsed) (count embedded-items))
                (log! run-id {:text (str "Collapsed " (- (count embedded-items) (count collapsed))
                                         " near-duplicate stories across outlets")}))
            numbered (->> collapsed (take (:max-items config 80)) news/cite)
            _ (when (empty? numbered)
                (throw (ex-info (if (seq gathered)
                                  (str "every story gathered was already in the briefings up to "
                                       (sources/long-date (:day earlier)))
                                  "no items were gathered from any source")
                                {})))
            _ (log! run-id {:text (str (count numbered) " stories to analyse after dropping"
                                       " duplicates and older items")})
            llm-config (providers/role-llm config :analyst)
            established (some-> (:markdown earlier) news/overview)
            _ (when established
                (log! run-id {:text (str "Building on the briefing for " (sources/long-date (:day earlier)))}))
            prompt (news/render-prompt (news/add-previous (or (:template ctx) (config/prompt-template))
                                                          (some->> established
                                                                   (previous-context (:day earlier))))
                                       (str (sources/long-date day) " (" day ")")
                                       numbered)
            _ (update-status! run-id assoc :state :analysing :items (count numbered)
                              :provider (name (:alias llm-config)) :model (:model llm-config))
            _ (log! run-id {:text (str "Asking " (name (:alias llm-config)) " (" (:model llm-config)
                                       ") to write the briefing")})
            chat (or (:chat ctx) llm/chat)
            watcher (progress-watcher run-id)
            reply (m/? (m/via m/blk (chat llm-config {:messages [{:role "user" :content prompt}]
                                                      :on-delta (:on-delta watcher)})))
            _ ((:flush! watcher))
            answer (str/trim (str (:content reply)))
            _ (when (str/blank? answer)
                (throw (ex-info (if (= "length" (:finish-reason reply))
                                  (str "the model used its whole token budget ("
                                       (:max-tokens llm-config) ") before writing the briefing;"
                                       " raise :max-tokens for the provider in config.edn")
                                  (str "the model returned an empty briefing (finish reason: "
                                       (:finish-reason reply) ")"))
                                {:finish-reason (:finish-reason reply)})))
            doc (news/briefing answer numbered)
            known (set (map :n numbered))
            cited (filterv known (news/citations answer))]
        (store/save-day! store {:day day
                                :sources numbered
                                :cited cited
                                :markdown doc
                                :tldr (news/tldr doc)
                                :model (or (:model reply) (:model llm-config))
                                :provider (name (:alias llm-config))})
        (write-markdown! (or (:markdown-dir ctx) (config/path "briefings")) day doc)
        (log! run-id {:text (str "Filed the briefing: " (count cited) " of " (count numbered)
                                 " sources cited") :level :ok})
        (when-let [gone (seq (prune-days! ctx))]
          (log! run-id {:text (str "Dropped " (count gone) " old "
                                   (if (= 1 (count gone)) "day" "days")
                                   " past the limit of " (:keep-days config default-keep-days))}))
        (swap! stored inc)
        {:day day :items (count numbered) :cited (count cited)}))))

(defn running? [] (some? @current))

(defn start-run!
  "Start a run for `day` unless one is in flight. Returns true when started."
  [ctx day]
  (let [token (Object.)]
    (when (compare-and-set! current nil {:token token})
      (reset! status {:run token :state :starting :day day :started (now) :sources {}})
      (let [finish! (fn [m]
                      (update-status! token merge m {:finished (now)})
                      (swap! current #(when-not (= token (:token %)) %)))
            cancel ((run-task (assoc ctx :run-id token) day)
                    (fn [summary] (finish! (assoc summary :state :done)))
                    ;; a cancel that lands in a blocking call surfaces as
                    ;; that thread's interrupt rather than as ebb's Cancelled
                    (fn [e] (finish! (if (or (m/cancelled? e) (instance? InterruptedException e))
                                       {:state :cancelled}
                                       {:state :failed :error (or (ex-message e) (str e))}))))]
        (swap! current #(if (= token (:token %)) (assoc % :cancel cancel) %))
        true))))

(defn cancel-run!
  "Cancel the run in flight, if any."
  []
  (when-let [cancel (:cancel @current)]
    (cancel)
    true))

;; --- the schedule ------------------------------------------------------------------
;; The first run is at :run-at, local time, and the next ones every
;; :run-every-hours after it (a day by default). Wake times are worked out
;; from the clock rather than by adding up sleeps, so they don't drift, and a
;; wake missed while the machine slept is skipped rather than made up.

(def ^:private hour-ms (* 60 60 1000))

(defn interval-ms
  "How long the schedule sleeps between runs: :run-every-hours, a day by
  default."
  [config]
  (let [h (:run-every-hours config 24)]
    (when-not (and (number? h) (pos? h))
      (throw (ex-info (str ":run-every-hours has to be a positive number of hours, not " (pr-str h))
                      {:run-every-hours h})))
    (long (* h hour-ms))))

(defn next-wake
  "The first wake after `now`: `anchor`, today's :run-at, plus or minus a
  whole number of intervals. Times are epoch milliseconds."
  [anchor interval now]
  (+ anchor (* interval (inc (long (Math/floor (/ (- now anchor) (double interval))))))))

(def ^:private early-ms
  "How early a timer may fire and still find a briefing an interval old."
  60000)

(defn due?
  "Whether a wake should gather: there is no briefing for today, `age` is
  nil, or it is an interval old. One run by hand an hour before a daily wake
  makes that wake unnecessary."
  [age interval]
  (or (nil? age) (>= age (- interval early-ms))))

(defn- anchor-ms
  "Today's :run-at as an instant."
  [hh-mm]
  (-> (java.time.LocalDateTime/of (java.time.LocalDate/now) (java.time.LocalTime/parse hh-mm))
      (.atZone (java.time.ZoneId/systemDefault))
      .toInstant
      .toEpochMilli))

(defn- day-of [ms]
  (str (.toLocalDate (.atZone (java.time.Instant/ofEpochMilli ms) (java.time.ZoneId/systemDefault)))))

(defn- run-if-due! [{:keys [store] :as ctx} interval]
  (let [day (today)
        created (some-> (store/day store day) :created-at java.time.Instant/parse .toEpochMilli)]
    (when (due? (some->> created (- (now))) interval)
      (start-run! ctx day))))

(defn schedule-task
  "Task: run today's briefing at :run-at and every :run-every-hours after,
  and once at start when a run was due today and hasn't happened. Runs until
  cancelled."
  [{:keys [config] :as ctx}]
  (let [run-at (:run-at config)
        interval (interval-ms config)]
    (m/sp
      (let [last-wake (- (next-wake (anchor-ms run-at) interval (now)) interval)]
        (when (= (today) (day-of last-wake))
          (run-if-due! ctx interval)))
      (loop [after (now)]
        (let [at (next-wake (anchor-ms run-at) interval after)]
          (m/? (m/sleep (max 1000 (- at (now)))))
          (run-if-due! ctx interval)
          (recur (max at (now))))))))

(defn start-schedule!
  "Start the schedule; returns its canceller, or nil when :run-at is not
  set."
  [{:keys [config] :as ctx}]
  (when (:run-at config)
    ((schedule-task ctx)
     (fn [_])
     (fn [e]
       (when-not (m/cancelled? e)
         (binding [*out* *err*]
           (println "schedule stopped:" (ex-message e))))))))
