(ns newsroom.pipeline
  "A day's run, on ebb: every source fetched at once on the blocking pool,
  each under its own timeout, so a slow or broken feed costs only its own
  items; then the pure core (newsroom.news) dedupes and numbers them, the
  analyst model writes the briefing, and the day is stored.

  A run is one cancellable task. Cancelling it interrupts whatever fetch or
  model call is in flight and stores nothing. Only one run goes at a time.

  Before the analyst writes, a researcher can ground the day in history:
  it searches the web for earlier events like the day's stories, over a
  few rounds, and the ones it picks join the day's sources as precedents
  (see newsroom.precedents), to be cited like any other and remembered
  with the day.

  Once the analyst has written, a critic checks the briefing against the
  sources and the material facts, and when it finds claims that don't hold
  up, the analyst rewrites it with them fixed (see newsroom.critique).

  The weekly and monthly digests are runs too: they read the stored days of
  a period instead of gathering, rank its storylines (newsroom.trends) and
  have the analyst write them up. The schedule writes each one once its
  period is over.

  The projections a briefing or digest makes, and the expectations the
  outlets put forward in the dossiers, are recorded once it is filed. Each
  digest is followed by a retrospective that judges the ones fallen due
  and keeps the lessons, and the briefings and digests are told how the
  projections have gone (see newsroom.retrospective).

  Once a day is filed, its podcast is written and recorded, when the
  speech engine is there, by a worker of its own that doesn't hold the run
  (see newsroom.narration).

  Progress is published to `status`, a glimmer ratom, so every open page
  shows it live.

  With several readers, each runs on their own: a reader's `lane` is the
  status their pages show, the run they have in flight and the count their
  pages refresh by, so one reader's run neither waits for another's nor
  shows on their pages. The local reader's lane is `status`, `current` and
  `stored`.

  What a source gives doesn't depend on who reads it, so a reader's run
  takes what another's read of the same source for the same day, when it
  is no more than :shared-fetch-minutes old, and when the other is reading
  it right then, waits for that read rather than making its own. The text
  of an article read in full and the embedding of an item are shared the
  same way. With one reader there is no one to share with, and every run
  reads afresh unless config.edn says otherwise."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [ebb.core :as m]
            [glimmer.ratom :as ratom]
            [jolt.time]
            [newsroom.analysis :as analysis]
            [newsroom.config :as config]
            [newsroom.critique :as critique]
            [newsroom.embed :as embed]
            [newsroom.entities :as entities]
            [newsroom.feed :as feed]
            [newsroom.llm.cache :as cache]
            [newsroom.llm.client :as llm]
            [newsroom.llm.providers :as providers]
            [newsroom.narration :as narration]
            [newsroom.news :as news]
            [newsroom.notes :as notes]
            [newsroom.plugin :as plugin]
            [newsroom.precedents :as precedents]
            [newsroom.progress :as progress]
            [newsroom.retrospective :as retrospective]
            [newsroom.sources :as sources]
            [newsroom.store :as store]
            [newsroom.trends :as trends]
            [newsroom.tts :as tts]))

(defonce ^{:doc "The current or last run, for the page to show."}
  status (ratom/atom {:state :idle}))

(defonce ^{:doc "The run in flight: {:token :cancel}, or nil."}
  current (atom nil))

(defonce ^{:doc "Bumped whenever a day is stored, so pages showing it refresh."}
  stored (ratom/atom 0))

(defonce ^:private lanes (atom {}))

(defn lane
  "The reader `uid`'s runs: {:status :current :stored}, as `status`,
  `current` and `stored` are the local reader's, which they are for
  `uid` nil."
  [uid]
  (if (or (nil? uid) (= store/local-user-id uid))
    {:status status :current current :stored stored}
    (or (get @lanes uid)
        (get (swap! lanes (fn [m]
                            (if (contains? m uid)
                              m
                              (assoc m uid {:status (ratom/atom {:state :idle}) :current (atom nil)
                                            :stored (ratom/atom 0)}))))
             uid))))

(defn- lane-of [ctx] (lane (some-> (:store ctx) store/user-id)))

(defn- by-reader?
  "Whether the reader cancelled the run in `ctx`'s lane, rather than the
  server stopping."
  [ctx]
  (boolean (:by-reader @(:current (lane-of ctx)))))

(deftype ^:private Run [status job store])

(defn- status-of
  "The status run `run-id` reports to: its reader's, or the local reader's
  for a run-id that isn't a Run, as a test's may not be."
  [run-id]
  (if (instance? Run run-id) (.-status ^Run run-id) status))

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

(defn- snapshot
  "A status as it is kept with its job: without the run's token."
  [st]
  (dissoc st :run))

(defonce ^{:private true
           :doc "The runs whose status changed since it was last written to their job."}
  unsaved (atom #{}))

(defn- save-status!
  "Write run `r`'s status to its job, while the status is still its."
  [^Run r]
  (let [st @(.-status r)]
    (when (identical? r (:run st))
      (store/update-job! (.-store r) (.-job r) {:status (snapshot st)}))))

(def ^:private save-every-ms
  "How often a running job's status is written: a restart loses at most
  this much of its log."
  1000)

(defonce ^{:private true
           :doc "The thread writing the statuses that changed, every save-every-ms,
  so a run that reports many times a second costs a write a second."}
  saver
  (delay
    (doto (Thread. (fn []
                     (loop []
                       (Thread/sleep save-every-ms)
                       (let [[rs _] (swap-vals! unsaved (constantly #{}))]
                         (doseq [r rs]
                           (try (save-status! r)
                                (catch Throwable e
                                  (binding [*out* *err*]
                                    (println "couldn't save a run's status:" (ex-message e)))))))
                       (recur))))
      (.setDaemon true)
      (.start))))

(defn- update-status!
  "Apply f to the status when it belongs to run `run-id`. A run's writes are
  tagged so a cancelled run whose fiber is still winding down cannot write
  over the status of the run that replaced it. Sources report from several
  threads at once, which relies on swap! being atomic (glimmer v0.1.4). A
  run with a job has its status written to it soon after (see saver)."
  [run-id f & args]
  (swap! (status-of run-id) (fn [st] (if (identical? run-id (:run st)) (apply f st args) st)))
  (when (and (instance? Run run-id) (.-job ^Run run-id))
    (swap! unsaved conj run-id)
    @saver))

(def ^:private max-events 60)

(defn- add-event
  "The status `st` with `event` added to its log."
  [st event]
  (update st :events (fn [es] (vec (take-last max-events (conj (or es []) (assoc event :at (now))))))))

(defn- log!
  "Add an event, {:text} with an optional :url and :level, to the run's log."
  [run-id event]
  (update-status! run-id add-event event))

(defn- interrupted? [e]
  ;; a cancel that lands in a blocking call surfaces as that thread's
  ;; interrupt rather than as ebb's Cancelled
  (or (m/cancelled? e) (instance? InterruptedException e)))

;; --- watching the model write ------------------------------------------------------

(defn- progress-watcher
  "An :on-delta for the model call: it logs when the model starts thinking
  and starts writing, and puts how far it has got on the status as it
  streams, plus once at the end (`flush!`)."
  [run-id]
  (progress/watcher {:on-progress #(update-status! run-id assoc :writing %)
                     :on-thinking #(log! run-id {:text "The model is thinking"})
                     :on-writing #(log! run-id {:text "The model is writing the briefing"})}))

;; --- gathering ---------------------------------------------------------------------

(def ^:private minute-ms 60000)

(defn shared-minutes
  "How long what a run reads serves another's: :shared-fetch-minutes, 30
  by default with several readers and 0, nothing kept, with one."
  [config]
  (or (:shared-fetch-minutes config) (if (config/single-user? config) 0 30)))

(defn- sorted-deep
  "`x` with every map in it sorted by its keys' printed form, so two
  sources that ask for the same thing print the same however their maps
  were built."
  [x]
  (walk/postwalk
   (fn [v] (if (map? v) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2))) v) v))
   x))

(defn share-key
  "What `source` asks for on `day`, as the key what it gives is shared by:
  the source without what only names it or says how long to wait for it,
  and, for a source a plugin reads, the plugin's settings in `config`,
  since they may hold the credentials it reads with."
  [source config day]
  (config/sha-256
   (pr-str (sorted-deep [(dissoc source :name :timeout-ms :retries :retry-wait-ms :lookback-days)
                         (when-let [p (plugin/owner (:type source))]
                           (or (get-in config [:plugins (keyword p)]) (get-in config [:plugins p])))
                         day]))))

(defonce ^{:private true :doc "The reads going on now, by share-key, each a promise of {:items} or {:error}."}
  in-flight (atom {}))

(defn- once-in-flight
  "What (f) returns, {:name :items}, unless a read for `key` is already
  going on, when it is that read's, waited on for at most `wait-ms`. When
  that read fails or isn't done in time, (f) is tried here instead."
  [key wait-ms f]
  (let [p (promise)
        [old _] (swap-vals! in-flight #(if (contains? % key) % (assoc % key p)))
        other (get old key)]
    (if other
      (let [r (deref other wait-ms nil)]
        (if (contains? r :items) r (f)))
      (try (let [r (f)]
             (deliver p r)
             r)
           (catch Throwable e
             (deliver p {:error e})
             (throw e))
           (finally
             (swap! in-flight #(if (identical? p (get % key)) (dissoc % key) %)))))))

(defn- recredit
  "`items` read for a source called `from`, credited to `to` instead: a
  reader's own name for the source, where the outlet isn't one of its
  own."
  [items from to]
  (if (= from to)
    items
    (mapv #(if (= from (:source %)) (assoc % :source to) %) items)))

(defn- minutes-ago [ms]
  (let [m (quot (- (System/currentTimeMillis) ms) minute-ms)]
    (cond (< m 1) "just now" (= 1 m) "a minute ago" :else (str m " minutes ago"))))

(defn- kept-ms
  "How long what a run reads is kept for one that analyses the day again:
  as long as the models' answers are, :reply-cache-hours."
  [config]
  (* (or (:reply-cache-hours config) 0) 60 minute-ms))

(defn read-shared
  "The items `source` has for the context's day, as read-source reads
  them, unless another run read the same within :shared-fetch-minutes or
  is reading it now: then that run's, credited to this source's name. A
  run that analyses the day again, :reread?, takes what the last run read
  within :reply-cache-hours, and reads the source only when nothing is
  kept. What's read is kept for as long as either asks. Blocks."
  [source {:keys [config store day reread?] :as ctx}]
  (let [shared-ms (* (shared-minutes config) minute-ms)
        kept (kept-ms config)
        reuse-ms (if reread? (max shared-ms kept) shared-ms)
        name (sources/source-name source)]
    (if-not (and store (pos? (max shared-ms kept)))
      (sources/read-source source ctx)
      (let [key (share-key source config day)]
        (if-let [{old :name :keys [items fetched-at]} (when (pos? reuse-ms)
                                                        (store/shared-fetch store key reuse-ms))]
          (do (sources/emit! ctx (str name ": " (count items) " items, as read by a run " (minutes-ago fetched-at)))
              (recredit items old name))
          (let [{old :name :keys [items]}
                (once-in-flight key (:timeout-ms (sources/policy source config))
                                (fn []
                                  (let [items (sources/read-source source ctx)]
                                    (store/save-shared-fetch! store key name items)
                                    {:name name :items items})))]
            (recredit items old name)))))))

(defn- gather-one
  "Task: one source's items for the day, never failing. A failure or a
  timeout is recorded as that source's :error, so the run goes on without
  it. Only a cancel of the run itself propagates. The source is read under
  its own policy, its :timeout-ms bounding it and its retries (see
  newsroom.sources/read-source), or shared with another reader's run (see
  read-shared)."
  [source {:keys [config] :as ctx}]
  (let [name (sources/source-name source)
        timeout-ms (:timeout-ms (sources/policy source config))]
    (m/sp
      (let [outcome (m/? (m/timeout (m/attempt (m/via m/blk (read-shared source ctx)))
                                    timeout-ms ::timed-out))
            result (if (= ::timed-out outcome)
                     {:error (str "timed out after " (quot timeout-ms 1000) "s")}
                     (try {:items (cond->> (vec (outcome))
                                    ;; commentary frames the day's news; it isn't reporting
                                    (= :analysis (sources/kind source)) (mapv #(assoc % :kind :analysis)))}
                          (catch Throwable e
                            ;; the run's cancel can reach the source's thread as
                            ;; an interrupt before it reaches this task
                            (if (interrupted? e)
                              (throw e)
                              {:error (or (ex-message e) (str e))}))))]
        ;; the source's state and its line in the log in one write, so no
        ;; frame shows one without the other
        (update-status! (:run-id ctx)
                        (fn [st]
                          (-> st
                              (update-in [:sources name] merge
                                         (if (:error result)
                                           {:state :failed :error (:error result)}
                                           {:state :ok :count (count (:items result))}))
                              (add-event (cond
                                           (= ::timed-out outcome) {:text (str name " timed out") :level :error}
                                           (:error result) {:text (str name " failed: " (:error result)) :level :error}
                                           :else {:text (str name ": " (count (:items result)) " items")
                                                  :level :ok})))))
        (assoc result :source name :lookback-days (sources/lookback-days source config))))))

(defn- round-robin
  "The first of each list, then the second of each, and so on, so that a cap
  on the total trims every list evenly rather than dropping the last ones."
  [lists]
  (loop [lists (remove empty? lists), out []]
    (if (seq lists)
      (recur (remove empty? (map rest lists)) (into out (map first lists)))
      out)))

(defn- by-outlet
  "`items` split by the outlet each is credited to, in the order the outlets
  first appear."
  [items]
  (let [groups (group-by :source items)]
    (map groups (distinct (map :source items)))))

(defn day-items
  "The items to analyse from each source's result: every one published
  from the lookback, the result's :lookback-days or the config's, to the
  day after (an outlet ahead of the local timezone has already dated
  today's news tomorrow), the outlets taken in turn. None is cut by its
  place in its feed: the day is cut a story at a time once every report is
  in its story (see trends/select-stories)."
  [results config day]
  (let [to (plus-days day 1)]
    (round-robin
     (for [{:keys [items lookback-days]} results
           :let [from (minus-days day (or lookback-days (:lookback-days config 1)))]
           outlet (by-outlet (feed/recent items from to))]
       outlet))))

;; --- the run -----------------------------------------------------------------------

(defn import-markdown!
  "Bring the briefings and digests older newsrooms wrote out as markdown
  files in `dir`, briefings/ by default, into the store, where they are
  kept and searched now: each file named for a day, a week or a month the
  store doesn't have, which keeps what it has. The folder is then moved
  aside, kept as .bak. Returns the names of the files brought in."
  ([store] (import-markdown! store (config/path "briefings")))
  ([store dir]
   (let [dir (io/file dir)]
     (if-not (.isDirectory dir)
       []
       (let [imported
             (vec (for [f (sort-by #(.getName %) (.listFiles dir))
                        :let [[_ name] (re-matches #"(.+)\.md" (.getName f))
                              kind (cond (nil? name) nil
                                         (news/valid-day? name) :day
                                         (re-matches #"\d{4}-W\d{2}" name) :week
                                         (re-matches #"\d{4}-\d{2}" name) :month)
                              doc (when (and kind (or (= :day kind) (trends/period-range kind name)))
                                    (slurp f))]
                        :when (and doc
                                   (if (= :day kind)
                                     (nil? (store/day store name))
                                     (nil? (store/digest store kind name))))]
                    (do (if (= :day kind)
                          (store/save-day! store {:day name :sources [] :cited [] :markdown doc :tldr (news/tldr doc)})
                          (store/save-digest! store {:kind kind :period name :sources [] :cited [] :markdown doc
                                                     :tldr (news/tldr doc)}))
                        name)))
             bak (io/file (str (.getPath dir) ".bak"))]
         (when-not (.exists bak) (.renameTo dir bak))
         imported)))))

(def default-keep-days 100)

(def ^:private shared-days-ms
  "How long what readers share is kept at most: a week, an embedding a
  week from when it was last used."
  (* 7 24 60 60 1000))

(defn prune-days!
  "Drop the days past :keep-days (100 by default, -1 for no limit) from the
  store. Returns the days dropped."
  [{:keys [config store]}]
  (store/prune! store (:keep-days config default-keep-days)))

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

(def ^:private previous-chars
  "About how much of the last briefing the next day's model reads back:
  all of a full one, cut at a paragraph past that."
  24000)

(defn- previous-context
  "What the model is told about the last briefing: the briefing itself, as
  what the reader already knows, so today's says what is new rather than
  telling it again."
  [day briefing]
  (str "## Already established\n\n"
       "This is the last briefing, for " (sources/long-date day) ". The reader has read it, so take "
       "everything in it as known and write today's about what is genuinely new since then. A lot of "
       "today's reports carry on stories it already told, and many of them only repeat what was known, "
       "so weigh each one by what it adds. Where a story moved, say in a sentence where it stood and "
       "then what changed, and don't restate its background, its mechanics or the projections already "
       "made unless today's news changes them. Where a story didn't move, leave it out, however many "
       "reports it has today. Check what it expected against what happened, and say plainly when a "
       "projection held, failed or is still open. A new development deserves more room than a familiar "
       "one with more coverage. Its citations belong to that day's sources, so cite only today's "
       "numbered sources below.\n\n"
       briefing))

(defn- write-up
  "Task: the analyst's answer to `prompt`, or to the conversation when it
  is a vector of messages, streamed to the status as it is written:
  {:answer :reply}. Fails when the answer is empty."
  [{:keys [run-id] :as ctx} llm-config prompt what]
  (m/sp
    (log! run-id {:text (str "Asking " (name (:alias llm-config)) " (" (:model llm-config)
                             ") to write " what)})
    (let [chat (or (:chat ctx) llm/chat)
          watcher (progress-watcher run-id)
          messages (if (string? prompt) [{:role "user" :content prompt}] prompt)
          reply (m/? (m/via m/blk (chat llm-config {:messages messages
                                                    :on-delta (:on-delta watcher)})))
          _ ((:flush! watcher))
          answer (str/trim (str (:content reply)))]
      (when (str/blank? answer)
        (throw (ex-info (if (= "length" (:finish-reason reply))
                          (str "the model used its whole token budget ("
                               (:max-tokens llm-config) ") before writing " what ";"
                               " raise :max-tokens for the provider in config.edn")
                          (str "the model returned an empty answer for " what " (finish reason: "
                               (:finish-reason reply) ")"))
                        {:finish-reason (:finish-reason reply)})))
      {:answer answer :reply reply})))

(defn embed-text
  "What an item is embedded by: its headline twice, then its summary. The
  embedding is a mean over the words, so the doubled headline weighs as
  much as a summary several times its length, and on a day of real feeds
  this split same-story pairs from same-topic ones better than the
  headline alone, the headline with the summary's first sentence, or the
  headline once with the whole summary."
  [{:keys [title summary]}]
  (str title ". " title ". " summary))

(defn- embed-items
  "The items with the :vector of their embed-text, those another run
  embedded already taken from `store`'s shared vectors, when it has
  them."
  [store items]
  (let [hashes (mapv #(config/sha-256 (embed-text %)) items)
        known (if store (store/shared-vectors store hashes) {})
        model (delay @embed-model)
        fresh (into {} (for [[h i] (map vector hashes items)
                             :when (not (contains? known h))]
                         [h (embed/embed @model (embed-text i))]))]
    (when (and store (seq fresh))
      (store/save-shared-vectors! store fresh))
    (mapv (fn [h i] (assoc i :vector (or (get known h) (get fresh h)))) hashes items)))

(defn- follow-old-days!
  "Storylines for the days from `from` to `to` that were stored before
  they were kept: oldest first, each day's sources are embedded and linked
  to the storylines of the :trend-days before, as a run would have linked
  them, so the days count toward the ranking and the digests. Returns how
  many days were followed; none when :story-threshold is nil. Blocks."
  [store config from to]
  (let [threshold (get config :story-threshold 0.6)
        days (when threshold (store/days-without-storylines store from to))]
    (doseq [d days]
      (let [own (mapv #(cond-> % (nil? (:vector %)) (assoc :vector (embed/embed @embed-model (embed-text %))))
                      (store/sources-between store d d))
            lines (trends/storylines (store/sources-between store (minus-days d (:trend-days config 7))
                                                            (minus-days d 1)))]
        (store/set-storylines! store d (trends/follow-day d own lines threshold))))
    (count days)))

(defn- follow-old-days
  "Task: follow-old-days! on the blocking pool, saying so when there are
  days to follow."
  [{:keys [config store run-id]} from to]
  (m/sp
    (let [n (m/? (m/via m/blk (follow-old-days! store config from to)))]
      (when (pos? n)
        (log! run-id {:text (str "Followed the stories through " n
                                 (if (= 1 n) " day" " days") " stored before storylines were kept")}))
      n)))

(defn- research-llm
  "The model that looks for the precedents: the :research role's when the
  config gives it one, else the analyst's."
  [config]
  (providers/role-llm config (if (get-in config [:roles :research]) :research :analyst)))

(defn- search-round
  "Task: the items `queries` find, read as gather-one reads any source,
  through `ctx`'s :search when it has one (a test's). Fails when the
  search does; a cancel propagates."
  [ctx queries]
  (m/sp
    (let [search (or (:search ctx) gather-one)
          result (m/? (search (precedents/search-source queries) ctx))]
      (if (:error result)
        (throw (ex-info (:error result) {}))
        (vec (:items result))))))

(defn- research
  "Task: the researcher's rounds over the day's `stories`, from the first
  `prompt`: {:picks :shown}, what it picked and every result it was shown.
  Each round its queries are searched, up to the `searches` it has left,
  and it is shown what they found. It stops when it picks, when it answers
  with neither queries nor picks, or, once it is out of searches or
  rounds, when it is told to pick and doesn't. Fails when a call or a
  search does."
  [{:keys [run-id] :as ctx} llm-config stories prompt searches]
  (let [chat (or (:chat ctx) llm/chat)]
    (m/sp
      (loop [messages [{:role "user" :content prompt}]
             shown []
             left searches
             rounds precedents/max-rounds
             told-to-pick? false]
        (let [reply (m/? (m/via m/blk (chat llm-config {:messages messages :on-delta (fn [_])})))
              content (str/trim (str (:content reply)))
              {:keys [picks queries]} (precedents/parse-answer content)
              messages (conj messages {:role "assistant" :content content})]
          (cond
            picks {:picks picks :shown shown}
            (empty? queries) {:picks [] :shown shown}
            (or (zero? left) (zero? rounds))
            (if told-to-pick?
              {:picks [] :shown shown}
              (recur (conj messages {:role "user" :content precedents/pick-now}) shown left rounds true))
            :else
            (let [queries (vec (take left queries))
                  _ (log! run-id {:text (str "Searching for precedents: "
                                             (str/join "; " (map #(str "“" % "”") queries)))})
                  fresh (precedents/new-results (m/? (search-round ctx queries)) stories shown)
                  left (- left (count queries))
                  rounds (dec rounds)]
              (log! run-id {:text (str (count fresh) " new results for the researcher to read")})
              (recur (conj messages {:role "user" :content (precedents/results-message fresh left rounds)})
                     (into shown fresh) left rounds false))))))))

(defn- precedents-task
  "Task: the research before the briefing. A researcher, the :research
  role's model or the analyst's, is shown the day's numbered `stories`,
  with the `notes` on their storylines, and searches the web over a few
  rounds for past periods when today's trends came together and for
  earlier events like single stories,
  then picks the results that show how today's may develop, with a note
  on each. Its picks come back as precedent sources. A failed call or
  search is logged and the day is analysed without precedents rather than
  lost; only a cancel propagates. nil when the research is off or keeps
  nothing."
  [{:keys [config run-id] :as ctx} day stories notes trends]
  (m/sp
    (let [searches (:precedent-searches config)]
      (when (pos? (or searches 0))
        (let [llm-config (research-llm config)
              _ (update-status! run-id assoc :state :researching :items (count stories))
              _ (log! run-id {:text (str "Asking " (name (:alias llm-config)) " (" (:model llm-config)
                                         ") to look for precedents for the day's stories")})
              prompt (precedents/render-prompt (config/precedent-template config)
                                               (str (sources/long-date day) " (" day ")")
                                               stories notes searches sources/long-date trends)
              outcome (m/? (m/attempt (research ctx llm-config stories prompt searches)))
              {:keys [picks shown]} (try (outcome)
                                         (catch Throwable e
                                           (when (interrupted? e) (throw e))
                                           (log! run-id {:text (str "The research for precedents failed: "
                                                                    (or (ex-message e) (str e)))
                                                         :level :error})
                                           nil))
              found (precedents/picked picks shown (map :n stories))]
          (if (empty? found)
            (do (when picks
                  (log! run-id {:text (if (seq shown)
                                        (str "No precedents kept from the " (count shown) " results read")
                                        "The researcher found no past worth searching for")}))
                nil)
            (do (log! run-id {:text (str "Kept " (count found) (if (= 1 (count found)) " precedent" " precedents")
                                         " of the " (count shown) " results read") :level :ok})
                found)))))))

(defn- recent-storylines
  "The storylines of the last :trend-days (7 by default) before `day`,
  none when :story-threshold is nil."
  [store config day]
  (when (:story-threshold config)
    (trends/storylines (store/sources-between store (minus-days day (:trend-days config 7))
                                              (minus-days day 1)))))

;; --- the desk ----------------------------------------------------------------------
;; Before the analyst writes, the desk sorts the day's reports into stories,
;; reads the main ones in full, writes a dossier on each and maps the trends
;; and links between them (see newsroom.analysis). Each stage is a model call
;; or a few, and one that fails is logged and the run goes on without it.

(defn- softly
  "Task: what `task` completes with, or nil when it fails, which is logged
  as `what` failing. Only a cancel propagates."
  [run-id what task]
  (m/sp
    (let [outcome (m/? (m/attempt task))]
      (try (outcome)
           (catch Throwable e
             (when (interrupted? e) (throw e))
             (log! run-id {:text (str what " failed: " (or (ex-message e) (str e))) :level :error})
             nil)))))

(defn- effort
  "The request opts that keep `llm-config`'s thinking to `level`, where its
  provider lets it be set."
  [llm-config level]
  (if (contains? (:features llm-config) :reasoning-effort) {:reasoning-effort level} {}))

(defn- cut-off? [reply]
  (or (= "length" (:finish-reason reply)) (str/blank? (:content reply))))

(defn- ask-reply
  "Task: the model's reply to `prompt`, not streamed, `opts` merged into
  the request. A reply cut off before its answer was done, which is a
  model thinking its whole token budget away, is asked for once more with
  less thinking, where the provider lets it be turned down."
  [{:keys [run-id] :as ctx} llm-config prompt opts]
  (m/sp
    (let [chat (or (:chat ctx) llm/chat)
          call #(m/? (m/via m/blk (chat llm-config (merge {:messages [{:role "user" :content prompt}]
                                                           :on-delta (fn [_])}
                                                          %))))
          reply (call opts)
          less (effort llm-config "low")]
      (if (and (cut-off? reply) (seq less) (not= (:reasoning-effort opts) (:reasoning-effort less)))
        (do (log! run-id {:text (str "An answer from " (name (:alias llm-config)) " came back cut off, "
                                     "so it was asked again with less thinking")})
            (call (merge opts less)))
        reply))))

(defn- ask
  "Task: the model's answer to `prompt` (see ask-reply)."
  ([ctx llm-config prompt] (ask ctx llm-config prompt {}))
  ([ctx llm-config prompt opts]
   (m/sp (str (:content (m/? (ask-reply ctx llm-config prompt opts)))))))

(defn- desk-llm
  "The model the desk works with: the :desk role's when the config gives
  it one, else the analyst's."
  [config]
  (providers/role-llm config (if (get-in config [:roles :desk]) :desk :analyst)))

(defn- counted [n one many] (str n " " (if (= 1 n) one many)))

(defn- dated [day] (str (sources/long-date day) " (" day ")"))



(def ^:private sorting-wave
  "How many sorting calls go at once."
  10)

(defn- sort-once
  "Task: the desk's sorting of `items`, parsed, asked once more when the
  answer is cut off or can't be read, with less thinking, which is what
  runs a model out of tokens; nil when it still can't be."
  [{:keys [run-id] :as ctx} llm-config day items notes]
  (m/sp
    (let [prompt (analysis/grouping-prompt (dated day) items notes)
          try! #(m/? (softly run-id "Sorting the reports into stories" (ask ctx llm-config prompt %)))
          parsed (some-> (try! {}) (analysis/parse-grouping (count items)))]
      (or parsed
          (do (log! run-id {:text (str "The sorting of " (counted (count items) "report" "reports")
                                       " came back cut off or unreadable, so it was asked again")})
              (some-> (try! (effort llm-config "low")) (analysis/parse-grouping (count items))))))))

(defn- sort-batch
  "Task: the desk's sorting of one batch of `items`, {:groups :junk} by
  their numbers in it, every report accounted for: those the answer left
  out are sorted again on their own, and any still left out become
  stories of their own. nil when the batch couldn't be sorted at all."
  [{:keys [run-id] :as ctx} llm-config day items notes]
  (m/sp
    (when-let [parsed (m/? (sort-once ctx llm-config day items notes))]
      (let [missing (analysis/unplaced parsed (count items))]
        (if (empty? missing)
          parsed
          (let [_ (log! run-id {:text (str "The sorting left out " (counted (count missing) "report" "reports")
                                           ", so they were sorted again on their own")})
                again (m/? (sort-once ctx llm-config day (mapv #(nth items (dec %)) missing) notes))
                back #(nth missing (dec %))]
            (cond-> parsed
              again (-> (update :groups into (map #(update % :members (partial mapv back)) (:groups again)))
                        (update :junk into (map back (:junk again)))))))))))

(def ^:private review-pool
  "How many of the day's heaviest stories the editor looks over: well over
  what :max-items has room for, so a story underrated by its batch can
  still climb into the briefing, and few enough for one call to weigh
  together with a little thinking. An answer that can't be read is asked
  for again over the heaviest half."
  150)

(defn- review-task
  "Task: the `ranked` items, sorted into stories, once the editor has
  looked over the heaviest review-pool of them together, merging the ones
  two batches each started on one event and rating again the importances
  that don't hold up against the rest of the day; as they were when the
  call fails or can't be read."
  [{:keys [config run-id] :as ctx} day ranked]
  (m/sp
    (let [llm-config (desk-llm config)
          heaviest (distinct (map :group ranked))
          review! (fn [n]
                    (let [keys (take n heaviest)
                          pool (filterv (comp (set keys) :group) ranked)
                          _ (log! run-id {:text (str "Asking the editor to look over the day's " (count keys)
                                                     " heaviest stories together")})
                          answer (m/? (softly run-id "The editor's look at the day"
                                              (ask ctx llm-config (analysis/review-prompt (dated day) pool)
                                                   (effort llm-config "low"))))]
                      [answer (some-> answer (analysis/parse-review keys))]))
          n (min review-pool (count heaviest))
          [answer review] (review! n)
          [answer review] (if (or review (< n 2))
                            [answer review]
                            (do (log! run-id {:text "The editor's answer couldn't be read, so it was asked again over half as many"})
                                (review! (quot n 2))))
          items ranked]
      (if-not review
        (do (when answer
              (log! run-id {:text "The editor's answer couldn't be read, so the stories stay as sorted"
                            :level :error}))
            items)
        (let [merged (reduce + (map (comp dec count) (:same review)))
              was (into {} (map (juxt :group :importance)) (reverse items))
              rerated (count (remove (fn [[k v]] (= v (was k))) (:importance review)))]
          (log! run-id {:text (str "The editor "
                                   (str/join " and " (remove nil? [(when (pos? merged)
                                                                     (str "merged " (counted merged "story" "stories")
                                                                          " into others"))
                                                                   (when (pos? rerated)
                                                                     (str "re-rated " (counted rerated "story" "stories")))]))
                                   (when (and (zero? merged) (zero? rerated)) "changed nothing"))
                        :level :ok})
          (analysis/apply-review items review))))))

(def ^:private analysis-per-story
  "The most pieces of analysis one story is given."
  2)

(def ^:private analysis-candidates
  "The most pieces of analysis the desk is asked about, the latest first."
  60)

(defn- analysis-task
  "Task: the day's `selected` reports with the `commentary` among them, at
  most :max-analysis (12) pieces: each under the story the desk says it
  argues about most, the heaviest stories' first, and those about none of
  them after every story while there is room (see analysis/place-analysis).
  A failed call leaves every piece about none."
  [{:keys [config run-id] :as ctx} day selected commentary]
  (m/sp
    (let [budget (or (:max-analysis config) 12)]
      (if (or (empty? commentary) (not (pos? budget)))
        selected
        (let [pieces (vec (take analysis-candidates (sort-by #(str (feed/published-day (:published %))) #(compare %2 %1) commentary)))
              titles (into {} (map (juxt :group :group-title)) (reverse selected))
              stories (vec (for [g (distinct (keep :group selected))] {:key g :title (titles g)}))
              llm-config (desk-llm config)
              attached (when (seq stories)
                         (log! run-id {:text (str "Asking which stories " (counted (count pieces) "piece" "pieces")
                                                  " of analysis argue about")})
                         (some-> (m/? (softly run-id "Placing the analysis"
                                              (ask ctx llm-config (analysis/attach-prompt (dated day) stories pieces))))
                                 (analysis/parse-attachments (count pieces) (map :key stories))))
              placed (analysis/place-analysis selected pieces (or attached {}) budget analysis-per-story)
              in (filter analysis/analysis? placed)]
          (log! run-id {:text (str "Gave the analyst " (counted (count in) "piece" "pieces") " of analysis"
                                   (let [under (count (filter :group in))]
                                     (when (pos? under) (str ", " under " under the stories they argue about"))))
                        :level :ok})
          placed)))))

(defn- group-task
  "Task: the day's `items` sorted into stories by the desk (see
  analysis/apply-grouping), or nil when :group-stories is off, or the
  sorting fails or says nothing. The items go in batches of
  analysis/sorting-batch, the reports on one subject together, a few calls
  at once, and a batch that can't be sorted costs only its own sorting."
  [{:keys [config store run-id] :as ctx} day items]
  (m/sp
    (when (and (:group-stories config) (seq items))
      (let [llm-config (desk-llm config)
            batches (analysis/sorting-batches items analysis/sorting-batch)
            _ (update-status! run-id assoc :state :sorting :items (count items))
            _ (log! run-id {:text (str "Asking " (name (:alias llm-config)) " (" (:model llm-config)
                                       ") to sort " (count items) " reports into stories"
                                       (when (< 1 (count batches)) (str ", in " (count batches) " calls")))})
            notes (store/notes-before store (keep :story items) day)
            sorted (loop [waves (partition-all sorting-wave batches) out []]
                     (if-let [wave (first waves)]
                       (recur (rest waves)
                              (into out (m/? (apply m/join vector
                                                    (map #(sort-batch ctx llm-config day % notes) wave)))))
                       out))
            failed (count (remove some? sorted))
            _ (when (pos? failed)
                (log! run-id {:text (str (counted failed "batch" "batches") " of reports couldn't be sorted, "
                                         "so each of their reports is a story of its own")
                              :level :error}))
            joined (analysis/join-groupings (map (fn [b p] [(count b) p]) batches sorted))
            grouped (when (seq (:groups joined))
                      (analysis/apply-grouping (into [] cat batches) joined))]
        (if (seq grouped)
          (let [junk (- (count items) (count grouped))]
            (log! run-id {:text (str "Sorted " (count grouped) " reports into "
                                     (count (distinct (map :group grouped))) " stories"
                                     (when (pos? junk) (str ", leaving out " junk " that weren't news")))
                          :level :ok})
            grouped)
          (do (log! run-id {:text "The sorting held no stories, so the reports stay as they are"
                            :level :error})
              nil))))))

(def ^:private article-timeout-ms 20000)

(defn- read-article
  "The text of the article at `url`, or nil when it can't be read. Blocks."
  [url]
  (try (feed/article-text (sources/fetch-text url {:timeout-ms article-timeout-ms}))
       (catch Throwable e
         (when (interrupted? e) (throw e))
         nil)))

(def ^:private article-days
  "How long the text of an article read in full serves another reader's
  run."
  1)

(defn- read-article-shared
  "The text of the article at `url`, read by another run within a day or
  read now and kept, or nil when it can't be read. Blocks."
  [store url]
  (or (some-> store (store/shared-article url (* article-days 24 60 minute-ms)))
      (when-let [text (read-article url)]
        (when store (store/save-shared-article! store url text))
        text)))

(defn- read-articles
  "Task: `stories` with the text of their main reports, :read-articles of
  each (none when it is 0), read from the outlets' pages, all at once. A
  page that can't be read, or holds too little to be the article, leaves
  its report with its summary. A report whose source handed over its
  text, like a video's transcript, keeps that text and isn't read."
  [{:keys [config store run-id]} stories]
  (m/sp
    (let [wanted (vec (remove :text (mapcat #(analysis/readers % (:read-articles config 0)) stories)))]
      (if (empty? wanted)
        stories
        (let [_ (log! run-id {:text (str "Reading " (count wanted) " articles in full for the dossiers")})
              texts (m/? (apply m/join vector
                                (map (fn [s] (m/timeout (m/via m/blk (read-article-shared store (:url s)))
                                                        (+ article-timeout-ms 5000) nil))
                                     wanted)))
              by-url (into {} (keep (fn [[s t]] (when t [(:url s) t]))) (map vector wanted texts))]
          (log! run-id {:text (str "Read " (count by-url) " of " (count wanted) " articles in full;"
                                   " the rest stay as their summaries")
                        :level :ok})
          (mapv (fn [st] (update st :sources (fn [ss] (mapv #(if-let [t (by-url (:url %))] (assoc % :text t) %) ss))))
                stories))))))

(defn- dossiers-task
  "Task: the desk's dossiers on `stories`, {key dossier}, written a few
  stories a call, the calls at once. A call that fails costs only its
  stories' dossiers."
  [{:keys [config run-id] :as ctx} day stories notes]
  (m/sp
    (let [llm-config (desk-llm config)
          batches (vec (partition-all analysis/dossier-batch stories))
          _ (log! run-id {:text (str "Asking " (name (:alias llm-config)) " (" (:model llm-config)
                                     ") to write dossiers on " (count stories) " stories")})
          answers (m/? (apply m/join vector
                              (map (fn [b]
                                     (softly run-id "A call for dossiers"
                                             (ask ctx llm-config (analysis/dossier-prompt (dated day) b notes
                                                                                          sources/long-date))))
                                   batches)))
          dossiers (apply merge {} (map (fn [b a] (when a (analysis/parse-dossiers a b))) batches answers))]
      (log! run-id {:text (str "Wrote dossiers on " (count dossiers) " of " (count stories) " stories")
                    :level (if (seq dossiers) :ok :error)})
      dossiers)))

(defn- connect-task
  "Task: the desk's map of the trends and links among `stories`, from
  their `dossiers`, the trends of the days before given to continue: see
  analysis/parse-connections. nil when the call fails."
  [{:keys [config store run-id] :as ctx} day stories dossiers]
  (m/sp
    (let [llm-config (desk-llm config)
          threads (trends/trend-threads (store/trends-between store (minus-days day (:trend-days config 7))
                                                              (minus-days day 1)))
          ;; the forces are few and change slowly, so all of them come along
          recent (into (vec (take 6 (filter #(= "force" (:level %)) threads)))
                       (take 15 (remove #(= "force" (:level %)) threads)))
          _ (log! run-id {:text "Mapping the trends running through the day and how the stories connect"})
          answer (m/? (softly run-id "Mapping the day"
                              (ask ctx llm-config (analysis/connection-prompt (dated day) stories dossiers recent
                                                                              sources/long-date))))]
      (when answer
        (let [{:keys [forces trends links] :as found} (analysis/parse-connections answer (keys dossiers)
                                                                                   (map :thread recent) day)]
          (log! run-id {:text (str "Mapped " (counted (count forces) "structural force" "structural forces")
                                   ", " (counted (count trends) "trend" "trends")
                                   " and " (counted (count links) "link" "links")
                                   (let [n (count (filter #(contains? (set (map :thread recent)) (:thread %))
                                                          (concat forces trends)))]
                                     (when (pos? n) (str ", " n " of them running from the days before"))))
                        :level :ok})
          found)))))

(def ^:private results-per-gap
  "How many results of a gap's search join the sources."
  2)

(defn- gap-task
  "Task: what the searches for the dossiers' gaps found, at most
  :gap-searches of them, each result a source to be numbered after the
  day's, with the :key of its story, the :gap it was searched for and
  :gap-story, its story's sources as the analyst cites them. A result
  that is one of the day's sources is left out."
  [{:keys [config] :as ctx} stories dossiers seen]
  (m/sp
    (let [queries (analysis/gap-queries stories dossiers (or (:gap-searches config) 0))]
      (when (seq queries)
        (log! (:run-id ctx) {:text (str "Searching for " (count queries)
                                        (if (= 1 (count queries)) " gap" " gaps") " in the reporting")})
        (let [by-key (into {} (map (juxt :key identity)) stories)
              results (m/? (apply m/join vector
                                  (map-indexed (fn [i {:keys [query]}]
                                                 (gather-one {:type :web-search :name (str "Gap search " (inc i))
                                                              :results 3 :queries [query]}
                                                             ctx))
                                               queries)))]
          (first
           (reduce (fn [[out seen] [{:keys [key gap]} {:keys [items]}]]
                     (let [fresh (take results-per-gap (news/unseen-items (news/dedupe-items items) seen))
                           cites (str "[" (str/join ", " (map :n (:sources (by-key key)))) "]")]
                       [(into out (map #(assoc (dissoc % :vector) :key key :gap gap :gap-story cites
                                                   :story (:story (by-key key)))
                                          fresh))
                        (into seen fresh)]))
                   [[] (vec seen)]
                   (map vector queries results))))))))

(defn- desk-task
  "Task: the desk's work on the day's `stories` before the briefing:
  {:stories :dossiers :map :found}, the main :dossier-stories of them read
  and written up, their trends and links mapped when :connect-stories is
  on, and the gaps in their reporting searched for. nil when the dossiers
  are off or none could be written."
  [{:keys [config run-id] :as ctx} day stories notes seen]
  (m/sp
    (let [n (:dossier-stories config)]
      (when (pos? (or n 0))
        (let [main (vec (take n (filter (comp seq :sources) stories)))
              _ (update-status! run-id assoc :state :preparing :items (count main))
              main (or (m/? (softly run-id "Reading the articles" (read-articles ctx main))) main)
              dossiers (m/? (dossiers-task ctx day main notes))]
          (when (seq dossiers)
            (let [the-map (when (:connect-stories config)
                            (m/? (softly run-id "Mapping the day" (connect-task ctx day main dossiers))))
                  found (m/? (softly run-id "Searching for the gaps" (gap-task ctx main dossiers seen)))]
              {:stories main :dossiers dossiers :map the-map :found (vec found)})))))))

;; --- the critic --------------------------------------------------------------------
;; Once the analyst has written the briefing, a critic reads it against the
;; sources and the material facts and lists the claims that don't hold up,
;; and the analyst rewrites it with them fixed (see newsroom.critique). A
;; critic that fails, or a revision that fails, leaves the draft as it was.

(defn- critic-llm
  "The model that checks the briefing: the :critic role's when the config
  gives it one, else the analyst's."
  [config]
  (providers/role-llm config (if (get-in config [:roles :critic]) :critic :analyst)))

(def ^:private issue-chars
  "How much of a problem the critic found the run's log shows."
  220)

(defn- critique-task
  "Task: the briefing `draft` once the critic has checked it, {:answer
  :reply :issues}: the analyst's revision, written in the conversation
  that wrote the draft from `prompt`, when the critic found problems, else
  the draft with `reply`. The draft stands when :critique-briefing is off,
  or when the critic or the revision fails or gives nothing usable; only a
  cancel propagates."
  [{:keys [config run-id] :as ctx} day numbered analysed llm-config prompt {:keys [answer reply]}]
  (m/sp
    (let [draft {:answer answer :reply reply :issues []}]
      (if-not (:critique-briefing config)
        draft
        (let [critic (critic-llm config)
              _ (update-status! run-id assoc :state :reviewing :writing nil
                                :provider (name (:alias critic)) :model (:model critic))
              _ (log! run-id {:text (str "Asking " (name (:alias critic)) " (" (:model critic)
                                         ") to check the briefing for claims that don't hold up")})
              found (m/? (softly run-id "The critic's check"
                                 (ask ctx critic (critique/render-prompt (config/critic-template config)
                                                                         (dated day) answer numbered analysed))))
              issues (some-> found critique/parse-issues)]
          (cond
            (nil? found) draft
            (nil? issues)
            (do (log! run-id {:text "The critic's answer held no list of problems, so the briefing stands"
                              :level :error})
                draft)
            (empty? issues)
            (do (log! run-id {:text "The critic found nothing that doesn't hold up" :level :ok})
                draft)
            :else
            (let [_ (log! run-id {:text (str "The critic found " (count issues)
                                             (if (= 1 (count issues)) " problem" " problems"))})
                  _ (doseq [{:keys [kind problem]} issues]
                      (log! run-id {:text (str "Critic, " kind ": " (critique/clip problem issue-chars))}))
                  _ (update-status! run-id assoc :state :revising
                                    :provider (name (:alias llm-config)) :model (:model llm-config))
                  revised (m/? (softly run-id "The revision"
                                       (write-up ctx llm-config
                                                 [{:role "user" :content prompt}
                                                  {:role "assistant" :content answer}
                                                  {:role "user" :content (critique/revision-request issues)}]
                                                 "the briefing again with the critic's problems fixed")))]
              (cond
                (nil? revised) (assoc draft :issues issues)
                (not (critique/usable-revision? answer (:answer revised)))
                (do (log! run-id {:text "The revision wasn't a whole briefing, so the draft stands"
                                  :level :error})
                    (assoc draft :issues issues))
                :else
                (do (log! run-id {:text "Revised the briefing" :level :ok})
                    (assoc revised :issues issues))))))))))

;; --- the coverage check -----------------------------------------------------------
;; A story the desk rated :must-cover-importance or more is never cut for
;; space, and a briefing that cites none of its reports is asked once to
;; cover it; what is still left out is listed under the briefing.

(defn- coverage-task
  "Task: {:answer :reply}, the briefing `answer` with every story rated
  :must-cover-importance or more in it: the analyst asked to work in those
  it left out, in the conversation that wrote it from `prompt`, and any
  still left out listed under it, Also today."
  [{:keys [config run-id] :as ctx} numbered llm-config prompt answer reply]
  (m/sp
    (let [threshold (:must-cover-importance config)
          missed (news/missed-stories numbered answer threshold)]
      (if (empty? missed)
        {:answer answer :reply reply}
        (let [_ (log! run-id {:text (str "The briefing left out " (counted (count missed) "story" "stories")
                                         " rated " threshold " or more, so the analyst was asked to cover "
                                         (if (= 1 (count missed)) "it" "them"))})
              _ (update-status! run-id assoc :state :revising
                                :provider (name (:alias llm-config)) :model (:model llm-config))
              revised (m/? (softly run-id "Covering the stories left out"
                                   (write-up ctx llm-config
                                             [{:role "user" :content prompt}
                                              {:role "assistant" :content answer}
                                              {:role "user" :content (news/coverage-request missed)}]
                                             "the briefing again with the stories it left out")))
              [answer reply] (if (and revised (critique/usable-revision? answer (:answer revised)))
                               [(:answer revised) (:reply revised)]
                               [answer reply])
              still (news/missed-stories numbered answer threshold)]
          (if (empty? still)
            (do (log! run-id {:text "Every story rated that important is in the briefing now" :level :ok})
                {:answer answer :reply reply})
            (do (log! run-id {:text (str "Listed " (counted (count still) "story" "stories")
                                         " still left out under the briefing, Also today")
                              :level :error})
                {:answer (news/also-today answer still) :reply reply})))))))

(defn- notes-llm
  "The model that keeps the notes: the :notes role's when the config gives
  it one, else the analyst's."
  [config]
  (providers/role-llm config (if (get-in config [:roles :notes]) :notes :analyst)))

(defn- update-notes
  "Task: the notes on the day's storylines updated from its reports by the
  analyst, and stored. A failure is logged rather than thrown, since the
  day's briefing is already filed; only a cancel propagates."
  [{:keys [config store run-id] :as ctx} day numbered cited noted max-facts dossier-facts]
  (m/sp
    (let [candidates (notes/candidates numbered cited noted)]
      (when (seq candidates)
        (let [old (store/notes-before store (map first candidates) day)
              {:keys [prompt ids]} (notes/compaction-prompt day candidates old max-facts dossier-facts)
              llm-config (notes-llm config)
              _ (log! run-id {:text (str "Updating the notes on " (count candidates) " storylines")})
              result (m/? (m/attempt (ask-reply ctx llm-config prompt {})))]
          (try
            (let [reply (result)
                  _ (when (cut-off? reply)
                      (throw (ex-info (str "the answer was cut off (finish reason: " (:finish-reason reply) ")") {})))
                  updated (notes/apply-answer day candidates old ids (:content reply) max-facts)]
              (if (seq updated)
                (do (store/save-notes! store updated)
                    (log! run-id {:text (str "Updated the notes on " (count updated) " storylines") :level :ok}))
                (log! run-id {:text "The notes stay as they were: the answer held nothing new"})))
            (catch Throwable e
              (when (interrupted? e) (throw e))
              (log! run-id {:text (str "The notes weren't updated: " (or (ex-message e) (str e)))
                            :level :error}))))))))

(defn period-label
  "A digest's period the way a reader says it: the week of 28 September
  2026, or September 2026."
  [kind period]
  (let [[from _] (trends/period-range kind period)]
    (case kind
      :week (str "the week of " (sources/long-date from))
      :month (second (str/split (sources/long-date from) #" " 2)))))

;; --- the retrospective ------------------------------------------------------------
;; The projections a briefing or digest makes, and those the outlets put
;; forward, are recorded once it is filed. After each digest, the ones fallen
;; due are judged against what happened and the lessons are rewritten (see
;; newsroom.retrospective). A step that fails is logged and the rest goes on.

(defn- retro-llm
  "The model that records and judges the projections: the :retrospective
  role's when the config gives it one, else the analyst's."
  [config]
  (providers/role-llm config (if (get-in config [:roles :retrospective]) :retrospective :analyst)))

(defn- labelled
  "A retrospective with its period's :label."
  [{:keys [kind period] :as r}]
  (when r (assoc r :label (period-label kind period))))

(defn- track-record
  "What the track record holds for a prompt written on `day`: {:block
  :records}, the block telling the analyst how the projections went, with
  the records of `outlets` (all when nil) and the forecasts made before
  `day` still open, those on `storylines` first, and every outlet's record
  by subject. nil when the retrospective is off."
  [{:keys [config store]} day outlets storylines]
  (when (:retrospective config)
    (let [standings (retrospective/standings (store/judged-projections store))
          open (retrospective/forecasts-shown (store/open-projections store day) storylines)]
      {:block (retrospective/record-block (labelled (store/latest-retrospective store)) standings outlets open)
       :records (retrospective/record-of standings)})))

(defn- record-projections
  "Task: the projections `markdown` makes, read by the model as of
  `made-on`, with the `outlets'` expectations, stored as what `origin`
  `ref` projected. The citations of `markdown` name `sources`, which tie a
  projection to its storyline. A failed call is logged, and what the
  outlets expect is stored all the same."
  [{:keys [config store run-id] :as ctx} origin ref what made-on markdown sources outlets]
  (m/sp
    (let [llm-config (retro-llm config)
          answer (m/? (softly run-id "Recording the projections"
                              (ask ctx llm-config (retrospective/extraction-prompt what made-on markdown))))
          own (when answer (retrospective/parse-projections answer made-on sources))]
      (when (and answer (nil? own))
        (log! run-id {:text "The answer listing the projections held no list of them" :level :error}))
      (store/save-projections! store origin ref (concat own outlets))
      (log! run-id {:text (str "Recorded " (counted (count own) "projection" "projections")
                               (when (seq outlets)
                                 (str " and " (counted (count outlets) "expectation" "expectations")
                                      " the outlets put forward")))
                    :level :ok}))))

(def ^:private judge-batch
  "How many projections one call judges."
  15)

(def ^:private judge-wave
  "How many judging calls go at once."
  4)

(def ^:private max-judged
  "The most projections one retrospective judges, the oldest first; the
  rest wait for the next. Well over what a week of briefings records, so
  the weekly retrospective keeps up."
  300)

(def ^:private evidence-chars
  "About how much of the digest the judge reads as what happened."
  6000)

(def ^:private standfirsts-shown
  "The most of the days' standfirsts the judge reads."
  45)

(defn- verdict-counts [rows]
  (let [n (frequencies (map :status rows))]
    (str/join ", " (for [s ["held" "partly" "failed" "open" "unresolved"] :when (pos? (get n s 0))]
                     (str (get n s) " " (if (= s "open") "still open" s))))))

(defn- retrospective-task
  "Task: the retrospective of `kind` and `period`, which ends on `to`:
  the projections fallen due by then judged against the storylines' facts
  since they were made, the digest just written, `markdown`, and the days'
  standfirsts, and the lessons rewritten from the verdicts. Stored unless
  nothing could be judged; a failure is logged, since the digest is filed
  already."
  [{:keys [config store run-id] :as ctx} kind period to markdown]
  (m/sp
    (let [label (period-label kind period)
          due (vec (take max-judged (store/projections-due store to kind period)))]
      (if (empty? due)
        (log! run-id {:text (str "No projections fell due by the end of " label)})
        (let [llm-config (retro-llm config)
              _ (update-status! run-id assoc :state :checking :items (count due) :writing nil
                                :provider (name (:alias llm-config)) :model (:model llm-config))
              _ (log! run-id {:text (str "Asking " (name (:alias llm-config)) " (" (:model llm-config)
                                         ") to check how " (counted (count due) "projection" "projections")
                                         " turned out")})
              numbered (retrospective/number-projections due)
              notes (store/notes store (keep :story due))
              general (str (news/body markdown evidence-chars 2)
                           (let [from (reduce #(if (neg? (compare %2 %1)) %2 %1) (map :made-on due))
                                 days (take-last standfirsts-shown (store/standfirsts-between store from to))]
                             (when (seq days)
                               (str "\n\n### The days' standfirsts\n\n"
                                    (trends/days-block days sources/long-date)))))
              batches (vec (partition-all judge-batch numbered))
              judge (fn [b]
                      (softly run-id "Judging the projections"
                              (ask ctx llm-config (retrospective/judge-prompt (sources/long-date to) b notes
                                                                             general sources/long-date))))
              ;; a few calls at a time, so a long backlog doesn't hit the
              ;; provider's rate limit all at once
              answers (loop [waves (partition-all judge-wave batches) out []]
                        (if-let [wave (first waves)]
                          (recur (rest waves) (into out (m/? (apply m/join vector (map judge wave)))))
                          out))
              verdicts (apply merge {} (map (fn [b a]
                                              (when a (retrospective/parse-verdicts a (set (map :pid b)))))
                                            batches answers))
              judged (retrospective/apply-verdicts numbered verdicts {:kind kind :period period :to to})]
          (if (empty? judged)
            (log! run-id {:text (str "None of the " (count due) " projections due could be judged")
                          :level :error})
            (let [_ (store/save-verdicts! store judged)
                  _ (log! run-id {:text (str "Judged " (count judged) " projections: " (verdict-counts judged))
                                  :level :ok})
                  previous (store/latest-retrospective store)
                  settled (filterv :closeness judged)
                  answer (when (seq settled)
                           (m/? (softly run-id "Drawing the lessons"
                                        (ask ctx llm-config (retrospective/lessons-prompt
                                                             label settled (:lessons previous))))))
                  drawn (some-> answer retrospective/parse-lessons)]
              (when (and answer (nil? drawn))
                (log! run-id {:text "The lessons' answer held none, so the last ones are kept" :level :error}))
              (store/save-retrospective! store {:kind kind :period period
                                                :summary (:summary drawn)
                                                :lessons (if drawn (:lessons drawn) (:lessons previous))
                                                :model (:model llm-config)
                                                :provider (name (:alias llm-config))})
              (when drawn
                (log! run-id {:text (str "Kept " (counted (count (:lessons drawn)) "lesson" "lessons")
                                         " from how the projections went")
                              :level :ok})))))))))

(defn narrate!
  "Have the podcast of the stored `day` written and recorded, on its own
  worker rather than as a run (see newsroom.narration), the pages showing
  the day refreshed once it is stored."
  [ctx day]
  (let [{:keys [stored]} (lane-of ctx)]
    (narration/enqueue! (assoc (select-keys ctx [:config :store :chat]) :on-saved #(swap! stored inc)) day)))

;; --- what a stored day or digest still needs ------------------------------------
;; A day is stored before the steps that follow from it: its mentions and
;; trends, the notes, the projections, the podcast; a digest before its
;; projections and retrospective. The steps are stored with it, in the same
;; transaction, each with what it needs, and struck off as each is done, so
;; a restart between the two finishes what is left (resume!) rather than
;; leaving the day without its notes.

(defn- bare
  "Sources as a step after filing keeps them: without their embeddings,
  which none of the steps reads."
  [sources]
  (mapv #(dissoc % :vector) sources))

(defn- followup-step
  "Task: one step still to do for the day or digest `ref`."
  [{:keys [store] :as ctx} ref [step args]]
  (case step
    :mentions (m/sp (store/save-mentions! store ref args))
    :trends (m/sp (store/save-trends! store ref args))
    :notes (let [{:keys [numbered cited noted max-facts dossier-facts]} args]
             (update-notes ctx ref numbered cited noted max-facts dossier-facts))
    :projections (let [{:keys [origin what made-on markdown sources outlets]} args]
                   (record-projections ctx origin ref what made-on markdown sources outlets))
    :retrospective (let [{:keys [kind period to markdown]} args]
                     (retrospective-task ctx kind period to markdown))
    :podcast (m/sp (narrate! ctx ref))))

(defn- followups-task
  "Task: `steps`, the steps still to do for `kind` (\"day\", \"week\" or
  \"month\") `ref`, in order, each struck off once done. A step that fails
  is logged and struck off too, since it would fail again; one the reader
  cancels drops the rest with it, and one the server's stopping cuts short
  is left for the next start."
  [{:keys [store run-id] :as ctx} kind ref steps]
  (m/sp
    (loop [[step & more] steps]
      (when step
        (let [r (m/? (m/attempt (followup-step ctx ref step)))]
          (try (r)
               (catch Throwable e
                 (when (interrupted? e)
                   (when (by-reader? ctx) (store/set-followups! store kind ref nil))
                   (throw e))
                 (log! run-id {:text (str "After filing, " (name (first step)) " failed: "
                                          (or (ex-message e) (str e)))
                               :level :error})))
          (store/set-followups! store kind ref more)
          (recur more))))))

(defn- web-block
  "What the analyst is told of the entities that tie the day's `stories`
  together, and of what changed since the briefing of `previous-day`, from
  the mentions kept for the :trend-days before; nil when there's nothing."
  [store config day stories previous-day]
  (let [today (entities/mentions day stories #{})
        window (store/mentions-between store (minus-days day (:trend-days config 7)) (minus-days day 1))
        previous (when previous-day (filterv #(= previous-day (:day %)) window))]
    (entities/web-block (entities/day-web today stories)
                        (if (seq previous) (entities/changes today previous window) {})
                        (when (seq previous) (sources/long-date previous-day)))))

(defn- with-saved-answers
  "`ctx` with its model call answering what an earlier run asked from the
  answer it got, for :reply-cache-hours (see newsroom.llm.cache), unless
  the run is to ask afresh, :fresh?. Each answer reused counts in `hits`."
  [{:keys [config store fresh?] :as ctx} hits]
  (assoc ctx :chat (cache/cached (or (:chat ctx) llm/chat) store
                                 {:max-age-ms (some-> (:reply-cache-hours config) (* 60 60 1000))
                                  :fresh? fresh?
                                  :on-hit (fn [_] (swap! hits inc))})))

(defn run-task
  "Task: gather, analyse and store `day`. Completes with the stored day's
  summary; fails when nothing was gathered or the model call fails.

  `ctx` is {:config :store}, and optionally :chat (the model call, llm/chat
  by default), :template (the prompt, prompt.md by default), :run-id
  (the status the run reports to; start-run! sets it, and a run without
  one takes over the status), :reread?, to analyse the news the day's last
  run read rather than read the sources again (see read-shared), and
  :fresh?, to ask the models afresh rather than reuse the answers an
  earlier run got to the same questions."
  [{:keys [config store] :as ctx} day]
  (let [own? (nil? (:run-id ctx))
        run-id (or (:run-id ctx) (Run. (:status (lane-of ctx)) nil nil))
        reused (atom 0)
        ctx (-> (assoc ctx :run-id run-id :day day :emit #(log! run-id %))
                (with-saved-answers reused))]
    (m/sp
      (let [srcs (:sources config)
            _ (when own? (reset! (status-of run-id) {:run run-id :state :starting :day day :started (now)}))
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
            fresh (news/unseen-items gathered (news/told (mapcat :sources seen)))
            _ (when (< (count fresh) (count gathered))
                (log! run-id {:text (str "Left out " (- (count gathered) (count fresh))
                                         " stories already in the briefings up to "
                                         (sources/long-date (:day earlier)))}))
            ;; analysis argues about the day's stories rather than reporting
            ;; them, so it isn't sorted, weighed or cut with the reports
            commentary (filterv analysis/analysis? fresh)
            fresh (filterv (complement analysis/analysis?) fresh)
            _ (when (seq commentary)
                (log! run-id {:text (str "Set aside " (counted (count commentary) "piece" "pieces")
                                         " of analysis to frame the stories they argue about")}))
            _ (when (:story-threshold config)
                (m/? (follow-old-days ctx (minus-days day (:trend-days config 7)) (minus-days day 1))))
            threshold (:dupe-threshold config)
            embedded-items (if (or threshold (:story-threshold config))
                             (embed-items store fresh)
                             fresh)
            collapsed (news/collapse-similar embedded-items threshold)
            _ (when (< (count collapsed) (count embedded-items))
                (log! run-id {:text (str "Collapsed " (- (count embedded-items) (count collapsed))
                                         " near-duplicate stories across outlets")}))
            lines (recent-storylines store config day)
            linked (trends/link-stories collapsed lines (:story-threshold config))
            ;; the desk sorts the reports into stories, which are ranked whole
            grouped (m/? (softly run-id "Sorting the reports into stories" (group-task ctx day linked)))
            half-life (:half-life-days config 2)
            ranked (if grouped
                     (trends/rank-groups grouped lines day half-life)
                     (trends/rank linked lines day half-life))
            ;; each sorting call saw only its own reports, so the editor
            ;; looks over the heaviest stories together, and they are
            ;; ranked again
            ranked (if (and grouped (< analysis/sorting-batch (count grouped)))
                     (trends/rank-groups (m/? (review-task ctx day ranked)) lines day half-life)
                     ranked)
            ;; every story of the day, the ones cut for space too, for the graph
            whole-day ranked
            _ (when-let [followed (seq (filter #(> (:days %) 1) ranked))]
                (log! run-id {:text (str "Followed " (count followed) " stories from earlier days, the longest"
                                         " in the news for " (apply max (map :days followed)) " days")}))
            ;; a story that starts today is named by the day and number of
            ;; its first source
            ranked (-> (m/? (analysis-task ctx day (trends/select-stories ranked config) commentary))
                       news/cite
                       (analysis/story-ids day))
            ;; a story the last briefing cited is flagged, so today's says
            ;; only what is new in it
            covered (set (keep :story (:cited earlier)))
            ranked (cond->> ranked
                     (seq covered) (mapv #(cond-> % (contains? covered (:story %)) (assoc :covered true))))
            _ (when (empty? ranked)
                (throw (ex-info (if (seq gathered)
                                  (str "every story gathered was already in the briefings up to "
                                       (sources/long-date (:day earlier)))
                                  "no items were gathered from any source")
                                {})))
            _ (log! run-id {:text (str (count ranked) " reports"
                                       (when grouped
                                         (str " in " (count (distinct (map :group ranked))) " stories"))
                                       " to analyse after dropping duplicates and older items")})
            max-facts (:story-notes config)
            kept (when max-facts (store/notes-before store (map :story ranked) day))
            stories (analysis/stories ranked)
            day-stories (entities/day-stories day whole-day ranked)
            web (web-block store config day day-stories (:day earlier))
            desk (when (seq stories)
                   (m/? (softly run-id "The desk's work" (desk-task ctx day stories kept ranked))))
            {:keys [dossiers found] the-map :map} desk
            ;; a report a dossier was written from is briefed by it
            briefed (set (for [st (:stories desk)
                               :when (contains? dossiers (:key st))
                               s (analysis/dossier-sources st)]
                           (:n s)))
            ranked (cond->> ranked
                     (seq briefed) (mapv #(cond-> % (contains? briefed (:n %)) (assoc :briefed true))))
            ;; what the gap searches found is numbered after the day's sources
            day-sources (analysis/story-ids (news/cite (into ranked found)) day)
            found (vec (drop (count ranked) day-sources))
            ;; the second round of research: historical precedents for the
            ;; day's stories, numbered after them, cited like any other
            history (m/? (precedents-task ctx day ranked kept
                                          (analysis/trends-block stories the-map)))
            numbered (news/cite (into day-sources history))
            graph (when (:map desk)
                    (analysis/graph (:stories desk) dossiers the-map))
            record (track-record ctx day (set (keep news/origin (remove :precedent numbered)))
                                (set (keep :story numbered)))
            analysed (when desk
                       (analysis/analysis-block (:stories desk) dossiers the-map found (some? graph)
                                                (:records record)))
            llm-config (providers/role-llm config :analyst)
            established (some-> (:markdown earlier) (news/body previous-chars 2))
            _ (when established
                (log! run-id {:text (str "Building on the briefing for " (sources/long-date (:day earlier))
                                         (when-let [n (seq (filter :covered ranked))]
                                           (str ", " (count n) " of today's reports on stories it covered")))}))
            running (notes/background kept numbered sources/long-date)
            _ (when running
                (log! run-id {:text "Giving the model the notes on the stories still running"}))
            _ (when (:block record)
                (log! run-id {:text "Telling the model how the earlier projections turned out"}))
            prompt (news/render-desk-prompt (or (:template ctx) (config/prompt-template config))
                                       (dated day)
                                       numbered
                                       (some->> [(some->> established
                                                          (previous-context (:day earlier)))
                                                 running]
                                                (remove nil?)
                                                seq
                                                (str/join "\n\n"))
                                       {:analysis analysed :graph? (some? graph) :record (:block record)
                                        :web web})
            _ (update-status! run-id assoc :state :analysing :items (count numbered)
                              :provider (name (:alias llm-config)) :model (:model llm-config))
            {:keys [answer reply]} (m/? (critique-task ctx day numbered analysed llm-config prompt
                                                       (m/? (write-up ctx llm-config prompt "the briefing"))))
            {:keys [answer reply]} (m/? (coverage-task ctx numbered llm-config prompt answer reply))
            ;; the projections are read from the briefing as written, without the graph
            written answer
            answer (analysis/with-graph answer graph)
            doc (news/briefing answer numbered)
            known (set (map :n numbered))
            cited (filterv known (news/citations answer))]
        (let [by-key (into {} (map (juxt :key identity)) stories)
              podcast? (and (:podcast config) (:podcast-auto config)
                            (tts/available? (:podcast-engine config :kitten-2)))
              steps (cond-> [[:mentions (entities/mentions day day-stories cited)]
                             ;; in place of a rerun's, even when this run found none
                             [:trends (mapv (fn [t] (update t :stories #(vec (distinct (keep (comp :story by-key) %)))))
                                            (concat (:forces the-map) (:trends the-map)))]]
                      max-facts (conj [:notes {:numbered (bare numbered) :cited cited :noted (vec (keys kept))
                                               :max-facts max-facts
                                               :dossier-facts (analysis/dossier-notes (:stories desk) dossiers)}])
                      (:retrospective config)
                      (conj [:projections {:origin "day" :what (str "the briefing for " (sources/long-date day))
                                           :made-on day :markdown written :sources (bare numbered)
                                           :outlets (retrospective/outlet-projections (:stories desk) dossiers day)}])
                      ;; the podcast takes minutes, so it is recorded once the run is over
                      podcast? (conj [:podcast nil]))]
          (store/save-day! store {:day day
                                  :sources numbered
                                  :cited cited
                                  :markdown doc
                                  :tldr (news/tldr doc)
                                  :model (or (:model reply) (:model llm-config))
                                  :provider (name (:alias llm-config))
                                  :followups steps})
          (log! run-id {:text (str "Filed the briefing: " (count cited) " of " (count numbered)
                                   " sources cited") :level :ok})
          (when podcast?
            (log! run-id {:text "The podcast of the briefing will be written and recorded next"}))
          (m/? (followups-task ctx "day" day steps)))
        (when-let [gone (seq (prune-days! ctx))]
          (log! run-id {:text (str "Dropped " (count gone) " old "
                                   (if (= 1 (count gone)) "day" "days")
                                   " past the limit of " (:keep-days config default-keep-days))}))
        (store/prune-shared! store shared-days-ms)
        (when (pos? @reused)
          (log! run-id {:text (str "Reused " (counted @reused "answer" "answers") " saved from an earlier run")}))
        (swap! (:stored (lane-of ctx)) inc)
        {:day day :digest nil :items (count numbered) :cited (count cited)}))))

;; --- digests -----------------------------------------------------------------------

(def ^:private sources-per-storyline
  "How many of a storyline's stored sources a digest is given to cite, when
  it has no notes."
  5)

(def ^:private facts-per-storyline
  "How many facts from a storyline's notes a digest is given to cite."
  8)

(defn- with-candidates
  "The storylines with what the digest cites for each, as :candidates: the
  facts from its notes dated in the period, else its stored sources there,
  when :keep-days hasn't dropped them. A storyline with neither is left
  out, and one with notes takes their title and summary."
  [lines kept stored from to]
  (vec (keep (fn [{:keys [story] :as line}]
               (let [note (get kept story)
                     facts (some-> note (notes/facts-between from to))
                     own (remove analysis/analysis? (get stored story))
                     candidates (if (seq facts)
                                  (vec (take-last facts-per-storyline facts))
                                  (trends/digest-sources own sources-per-storyline))]
                 (when (seq candidates)
                   (assoc line
                          :title (or (:title note)
                                     (:title (first (sort-by #(- (news/outlets %)) own))))
                          :summary (:summary note)
                          :candidates candidates))))
             lines)))

(defn- previous-digest-context
  [kind period overview]
  (str "## The last digest\n\n"
       "This is the overview of the digest for " (period-label kind period) ". Take it as what "
       "the reader already knows. Don't retell it. Say what this period changed, confirmed or "
       "overturned. Its citations belong to that digest's sources, so cite only the numbered "
       "sources below.\n\n"
       overview))

(def ^:private day-chars
  "About how much of each day's briefing a weekly digest reads."
  8000)

(def ^:private week-chars
  "About how much of each weekly digest a monthly one reads."
  12000)

(defn- weeks-over
  "The weeks that overlap the days from `from` to `to`, in order."
  [from to]
  (->> (iterate #(trends/period-of :week (trends/plus-days (second (trends/period-range :week %)) 1))
                (trends/period-of :week from))
       (take-while #(<= (compare (first (trends/period-range :week %)) to) 0))))

(defn- briefings-context
  "What a digest is built on, nil when none of it is kept: a week's daily
  briefings, or a month's weekly digests, with the overviews of the
  month's days that no weekly digest covers."
  [store kind from to]
  (let [entry (fn [heading text] (when text (str "### " heading "\n\n" text)))
        joined #(some->> % (remove nil?) seq (str/join "\n\n"))
        days (store/briefings-between store from to)]
    (case kind
      :week
      (some->> (joined (map #(entry (sources/long-date (:day %)) (news/body (:markdown %) day-chars 3)) days))
               (str "## The week's briefings\n\n"
                    "These are the daily briefings of the week, in order. The reader has read them day by "
                    "day, so don't retell them or go through the week one day at a time. Read them for how "
                    "things evolved across the week: where each story stood at the start, the turns it took, "
                    "what each day really added and what only repeated what was known, which threads came "
                    "together and which went nowhere, and where things ended up. Their citations belong to "
                    "those days' sources, so cite only the numbered sources below.\n\n"))
      :month
      (let [weeks (keep (fn [w] (when-let [d (store/digest store :week w)] [w d])) (weeks-over from to))
            covered (fn [day] (some (fn [[w _]] (let [[f t] (trends/period-range :week w)]
                                                  (<= (compare f day) 0 (compare t day))))
                                    weeks))
            weekly (map (fn [[w d]] (entry (let [l (period-label :week w)] (str (str/upper-case (subs l 0 1)) (subs l 1))) (news/body (:markdown d) week-chars 3)))
                        weeks)
            daily (map #(entry (sources/long-date (:day %)) (news/overview (:markdown %)))
                       (remove (comp covered :day) days))]
        (some->> (joined (concat weekly daily))
                 (str "## The month's weekly digests\n\n"
                      "These are the digests of the weeks of the month, in order, and the overviews of the "
                      "briefings of any days they don't cover. The reader has read them, so don't retell "
                      "them or go through the month one week at a time. Read them for how things developed "
                      "across the weeks: which movements built from one week to the next, where they turned, "
                      "what the weeks' readings got right or wrong in hindsight, and what the month amounts "
                      "to that no single week shows. The first and last weeks may run past the month's "
                      "edges, so keep to what happened within it. Their citations belong to those digests' "
                      "sources, so cite only the numbered sources below.\n\n"))))))

(defn- period-web
  "What a digest is told of its period's entities, from `from` to `to`,
  against the period before it."
  [store kind from to]
  (let [[before-from before-to] (trends/period-range kind (trends/previous-period kind from))]
    (entities/period-block (store/mentions-between store from to)
                           (store/mentions-between store before-from before-to))))

(defn digest-task
  "Task: write and store the digest of `kind` (:week or :month) for
  `period` from what is kept of its days: their coverage, which ranks the
  storylines, the notes kept on those storylines, which the digest cites,
  and the days' standfirsts. A storyline with no notes falls back to its
  stored sources. Completes with the digest's summary; fails when the
  period has no briefings or nothing ran for more than one outlet on one
  day.

  `ctx` is as for run-task, plus an optional :digest-template."
  [{:keys [config store] :as ctx} kind period]
  (let [own? (nil? (:run-id ctx))
        run-id (or (:run-id ctx) (Run. (:status (lane-of ctx)) nil nil))
        ctx (with-saved-answers (assoc ctx :run-id run-id) (atom 0))
        label (period-label kind period)
        job {:kind kind :period period}]
    (m/sp
      (when own? (reset! (status-of run-id) {:run run-id :state :starting :digest job :started (now)}))
      (update-status! run-id assoc :state :starting :digest job :sources {} :writing nil)
      (log! run-id {:text (str "Reading the briefings for " label)})
      (let [[from to] (trends/period-range kind period)
            [base-from base-to] (trends/baseline-range kind period)
            _ (m/? (follow-old-days ctx base-from to))
            window (store/coverage-between store from to)
            _ (when (empty? window)
                (throw (ex-info (str "there are no briefings for " label) {})))
            baseline (store/coverage-between store base-from base-to)
            active-days (count (distinct (map :day window)))
            ranked (trends/digest-storylines window baseline active-days
                                             (count (distinct (map :day baseline))))
            kept (store/notes store (map :story ranked))
            lines (->> (with-candidates ranked kept
                                        ;; by the same name the ranking gave them, which a source
                                        ;; stored before storylines were kept only has by its day
                                        (group-by trends/story-of (store/sources-between store from to {:vectors? false}))
                                        from to)
                       (take (:digest-stories config 15))
                       vec)
            _ (when (empty? lines)
                (throw (ex-info (str "no story in " label " ran for more than a day or one outlet")
                                {::nothing-to-digest true})))
            _ (log! run-id {:text (str (count (filter :summary lines)) " of " (count lines)
                                       " storylines have notes")})
            lines (trends/cite-storylines lines)
            numbered (vec (mapcat :cites lines))
            _ (log! run-id {:text (str (count lines) " storylines from " active-days " days, "
                                       (count (filter #(= :emerging (:trend %)) lines)) " emerging")})
            days (store/standfirsts-between store from to)
            last-period (trends/period-of kind (trends/plus-days from -1))
            established (some-> (store/digest store kind last-period) :markdown news/overview)
            threads (trends/trend-threads (store/trends-between store from to))
            _ (when (seq threads)
                (log! run-id {:text (str "Following " (count threads) " trends the desk found through the period")}))
            built-on (briefings-context store kind from to)
            record (track-record ctx to nil (set (keep :story lines)))
            _ (when (:block record)
                (log! run-id {:text "Telling the model how the earlier projections turned out"}))
            _ (when built-on
                (log! run-id {:text (str "Building on the " (if (= kind :month) "weekly digests" "daily briefings")
                                         " of " label)}))
            prompt (trends/render-digest-prompt
                    (or (:digest-template ctx) (config/digest-template config))
                    label
                    (trends/days-block days sources/long-date)
                    (trends/digest-block lines sources/long-date)
                    (some->> established (previous-digest-context kind last-period))
                    (trends/trends-block threads sources/long-date)
                    built-on
                    (:block record)
                    (period-web store kind from to))
            llm-config (providers/role-llm config :analyst)
            _ (update-status! run-id assoc :state :analysing :items (count lines)
                              :provider (name (:alias llm-config)) :model (:model llm-config))
            {:keys [answer reply]} (m/? (write-up ctx llm-config prompt (str "the digest for " label)))
            doc (news/briefing answer numbered)
            known (set (map :n numbered))
            cited (filterv known (news/citations answer))]
        (let [steps (when (:retrospective config)
                      [[:projections {:origin (name kind) :what (str "the digest for " label) :made-on to
                                      :markdown answer
                                      ;; a digest's citations tie a projection to the storyline they're under
                                      :sources (bare (mapcat (fn [l] (map #(assoc % :story (:story l)) (:cites l)))
                                                             lines))
                                      :outlets nil}]
                       [:retrospective {:kind kind :period period :to to :markdown doc}]])]
          (store/save-digest! store {:kind kind
                                     :period period
                                     :sources numbered
                                     :cited cited
                                     :markdown doc
                                     :tldr (news/tldr doc)
                                     :model (or (:model reply) (:model llm-config))
                                     :provider (name (:alias llm-config))
                                     :followups steps})
          (log! run-id {:text (str "Filed the digest for " label ": " (count cited) " of "
                                   (count numbered) " sources cited") :level :ok})
          (m/? (followups-task ctx (name kind) period steps)))
        (swap! (:stored (lane-of ctx)) inc)
        {:digest job :items (count numbered) :cited (count cited)}))))

(defn due-digests
  "The digests to write on `day`, as [kind period]: for each kind in
  :digests, the last whole period before day's, when it has briefings and
  no digest yet."
  [store config day]
  (let [days (store/days store)]
    (vec (for [kind (map keyword (:digests config [:week :month]))
               :when (contains? trends/kinds kind)
               :let [period (trends/previous-period kind day)
                     [from to] (trends/period-range kind period)]
               :when (and (some #(<= (compare from %) 0 (compare to %)) days)
                          (nil? (store/digest store kind period)))]
           [kind period]))))

(defn running?
  "Whether the reader `uid`, the local one by default, has a run in
  flight."
  ([] (running? nil))
  ([uid] (some? @(:current (lane uid)))))

(defn- start-job!
  "Start the task `(make-task run-id)` in `ctx`'s reader's lane unless
  they have a run in flight, with the status starting from `init`. `job`
  is {:kind :args :origin}, what is recorded of it (see store/add-job!),
  with its status as it goes. Returns true when started."
  [{:keys [store] :as ctx} job init make-task]
  (let [{:keys [current] lane-status :status} (lane-of ctx)
        claim (Object.)]
    (when (compare-and-set! current nil {:token claim})
      (let [st (merge {:state :starting :started (now) :sources {}} init)
            id (store/add-job! store (assoc job :state :running :status st))
            token (Run. lane-status id store)
            _ (reset! current {:token token})
            _ (reset! lane-status (assoc st :run token))
            finish! (fn [m]
                      (update-status! token merge m {:finished (now)})
                      (swap! unsaved disj token)
                      (try (store/update-job! store id {:state (:state m) :status (snapshot @lane-status)})
                           (catch Throwable e
                             (binding [*out* *err*] (println "couldn't save a run's end:" (ex-message e)))))
                      (swap! current #(when-not (= token (:token %)) %)))
            cancel ((make-task token)
                    (fn [summary] (finish! (assoc summary :state :done)))
                    (fn [e] (finish! (cond
                                       ;; one the server's stopping cut short is picked up or
                                       ;; offered again when it starts (resume!)
                                       (interrupted? e) {:state (if (by-reader? ctx) :cancelled :interrupted)}
                                       ;; a period with nothing worth a digest isn't a failure
                                       (::nothing-to-digest (ex-data e)) {:state :done :nothing (ex-message e)}
                                       :else {:state :failed :error (or (ex-message e) (str e))}))))]
        (swap! current #(if (= token (:token %)) (assoc % :cancel cancel) %))
        true))))

(defn start-run!
  "Start a run for `day` unless the reader has one in flight. `opts` may
  say :reread?, to analyse the news the day's last run read again, and
  :fresh?, to ask the models afresh. Returns true when started."
  ([ctx day] (start-run! ctx day {}))
  ([ctx day {:keys [reread? fresh?]}]
   (start-job! ctx {:kind :run :args {:day day :reread? reread? :fresh? fresh?} :origin :hand}
               {:day day} #(run-task (assoc ctx :run-id % :reread? reread? :fresh? fresh?) day))))

(defn start-digest!
  "Start writing the digest of `kind` for `period` unless the reader has a
  run in flight. Returns true when started."
  [ctx kind period]
  (start-job! ctx {:kind :digest :args {:kind kind :period period} :origin :hand}
              {:digest {:kind kind :period period}}
              #(digest-task (assoc ctx :run-id %) kind period)))

(defn resume!
  "After a restart, pick up what it cut short for the reader `ctx` is for:
  their desk and podcast show how their last job went, a job the restart
  interrupted is marked so, the steps a stored day or digest still needs
  are finished, and the podcasts being made or waiting are queued again.
  A run or digest the restart interrupted before it was stored is not run
  again: the desk offers to (see ui/run-panel). Call it once per reader
  at start, before their schedule; a reader signing in or out has no part
  in it."
  [{:keys [store] :as ctx}]
  (let [uid (store/user-id store)
        open (store/open-jobs store)]
    (doseq [{:keys [id]} open]
      (store/update-job! store id {:state :interrupted}))
    (when-let [{:keys [state status]} (store/latest-job store [:run :digest :followups])]
      (reset! (:status (lane-of ctx)) (cond-> (or status {:state :idle})
                                        (= :interrupted state) (assoc :state :interrupted))))
    (when-let [{:keys [state status]} (store/latest-job store [:podcast])]
      (reset! (:status (narration/lane uid)) (cond-> (or status {:state :idle})
                                               (= :interrupted state) (assoc :state :interrupted
                                                                             :pending nil))))
    (when-let [pending (seq (store/followups store))]
      (start-job! ctx {:kind :followups :args {:followups (mapv #(select-keys % [:kind :ref]) pending)}
                       :origin :resume}
                  {:finishing (mapv #(select-keys % [:kind :ref]) pending)}
                  (fn [run-id]
                    (let [ctx (assoc ctx :run-id run-id)]
                      (m/sp
                        (update-status! run-id assoc :state :finishing)
                        (loop [[{:keys [kind ref steps]} & more] pending]
                          (when kind
                            (log! run-id {:text (str "Finishing what the restart left undone for "
                                                     (if (= "day" kind)
                                                       (str "the briefing for " (sources/long-date ref))
                                                       (str "the digest for " (period-label (keyword kind) ref))))})
                            (m/? (followups-task ctx kind ref steps))
                            (recur more)))
                        (swap! (:stored (lane-of ctx)) inc)
                        {})))))
    ;; oldest first: the newest waiting one takes the place of any before it
    (doseq [{:keys [kind args]} open :when (= :podcast kind)]
      (narrate! ctx (:day args)))
    nil))

(defn- scheduled-task
  "Task: the day's run when `daily?`, then each of `digests`. A failed
  digest is logged and the next one tried; the job ends as the day's run
  did, or as the last digest did when there was no run."
  [{:keys [run-id] :as ctx} day daily? digests]
  (m/sp
    (let [daily (when daily? (m/? (m/attempt (run-task ctx day))))
          _ (when daily
              (try (daily) (catch Throwable e (when (interrupted? e) (throw e)))))
          last-digest (loop [[[kind period] & more] digests, result nil]
                        (if-not kind
                          result
                          (let [r (m/? (m/attempt (digest-task ctx kind period)))]
                            (try (r)
                                 (catch Throwable e
                                   (when (interrupted? e) (throw e))
                                   ;; a period with nothing worth a digest isn't a failure
                                   (log! run-id (if (::nothing-to-digest (ex-data e))
                                                  {:text (str "Nothing to digest for " (period-label kind period)
                                                              ": " (ex-message e))}
                                                  {:text (str "The digest for " (period-label kind period)
                                                              " failed: " (or (ex-message e) (str e)))
                                                   :level :error}))))
                            (recur more r))))]
      (if daily
        (do (update-status! run-id assoc :digest nil :day day)
            (daily))
        (last-digest)))))

(defn cancel-run!
  "Cancel the run the reader `uid`, the local one by default, has in
  flight, if any, as the reader asked: its job ends cancelled and what it
  left undone is dropped. With `:stopping? true` the server is stopping
  instead, and the job is interrupted, for the next start to pick up."
  ([] (cancel-run! nil))
  ([uid & {:keys [stopping?]}]
   (let [{:keys [current]} (lane uid)]
     (when-let [cancel (:cancel (swap! current #(cond-> % (and % (not stopping?)) (assoc :by-reader true))))]
       (cancel)
       true))))

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

(defn- run-if-due!
  "Start today's run when it is due, followed by any digest whose period
  has ended without one. The config is the one :current-config returns when
  `ctx` has it, so a run follows the settings as they are now."
  [ctx interval]
  (let [{:keys [store config] :as ctx} (if-let [current (:current-config ctx)]
                                         (assoc ctx :config (current))
                                         ctx)
        day (today)
        created (some-> (store/day store day) :created-at java.time.Instant/parse .toEpochMilli)
        daily? (due? (some->> created (- (now))) interval)
        digests (due-digests store config day)]
    (when (or daily? (seq digests))
      (start-job! ctx {:kind (if daily? :run :digest) :args {:day day :daily? daily? :digests digests}
                       :origin :schedule}
                  (if daily? {:day day} {:digest (zipmap [:kind :period] (first digests))})
                  #(scheduled-task (assoc ctx :run-id %) day daily? digests)))))

(defn schedule-task
  "Task: run today's briefing at :run-at and every :run-every-hours after,
  and once at start when a run was due today and hasn't happened. Runs until
  cancelled."
  [{:keys [config] :as ctx}]
  (let [run-at (:run-at config)
        interval (interval-ms config)]
    (m/sp
      ;; at start, what a restart cut short may be being finished first
      ;; (resume!): a run due now waits for it rather than being skipped
      (loop []
        (when (some? @(:current (lane-of ctx)))
          (m/? (m/sleep 1000))
          (recur)))
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
