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

  The weekly and monthly digests are runs too: they read the stored days of
  a period instead of gathering, rank its storylines (newsroom.trends) and
  have the analyst write them up. The schedule writes each one once its
  period is over.

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
            [newsroom.notes :as notes]
            [newsroom.precedents :as precedents]
            [newsroom.sources :as sources]
            [newsroom.store :as store]
            [newsroom.trends :as trends]))

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

(defn- interrupted? [e]
  ;; a cancel that lands in a blocking call surfaces as that thread's
  ;; interrupt rather than as ebb's Cancelled
  (or (m/cancelled? e) (instance? InterruptedException e)))

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
  it. Only a cancel of the run itself propagates. The source is read under
  its own policy, its :timeout-ms bounding it and its retries (see
  newsroom.sources/read-source)."
  [source {:keys [config] :as ctx}]
  (let [name (sources/source-name source)
        timeout-ms (:timeout-ms (sources/policy source config))]
    (m/sp
      (let [outcome (m/? (m/timeout (m/attempt (m/via m/blk (sources/read-source source ctx)))
                                    timeout-ms ::timed-out))
            result (if (= ::timed-out outcome)
                     {:error (str "timed out after " (quot timeout-ms 1000) "s")}
                     (try {:items (vec (outcome))}
                          (catch Throwable e
                            ;; the run's cancel can reach the source's thread as
                            ;; an interrupt before it reaches this task
                            (if (interrupted? e)
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

(defn- by-outlet
  "`items` split by the outlet each is credited to, in the order the outlets
  first appear."
  [items]
  (let [groups (group-by :source items)]
    (map groups (distinct (map :source items)))))

(defn day-items
  "The items to analyse from each source's result: those published from
  the lookback to the day after (an outlet ahead of the local timezone has
  already dated today's news tomorrow), at most :max-items-per-source from
  each outlet in it, interleaved. A source that reads many outlets, like a
  feed reader or a search credited to outlets, gives each its own share."
  [results config day]
  (let [from (minus-days day (:lookback-days config 1))
        to (plus-days day 1)
        cap (:max-items-per-source config 12)]
    (round-robin
     (for [{:keys [items]} results
           outlet (by-outlet (feed/recent items from to))]
       (take cap outlet)))))

;; --- the run -----------------------------------------------------------------------

(defn- write-markdown!
  "The briefing as `name`.md, the day for a day's and the period for a
  digest's, which can't collide."
  [dir name doc]
  (let [f (io/file dir (str name ".md"))]
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

(defn- write-up
  "Task: the analyst's answer to `prompt`, streamed to the status as it is
  written: {:answer :reply}. Fails when the answer is empty."
  [{:keys [run-id] :as ctx} llm-config prompt what]
  (m/sp
    (log! run-id {:text (str "Asking " (name (:alias llm-config)) " (" (:model llm-config)
                             ") to write " what)})
    (let [chat (or (:chat ctx) llm/chat)
          watcher (progress-watcher run-id)
          reply (m/? (m/via m/blk (chat llm-config {:messages [{:role "user" :content prompt}]
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
  "The items with the :vector of their embed-text."
  [items]
  (let [model @embed-model]
    (mapv (fn [i] (assoc i :vector (embed/embed model (embed-text i)))) items)))

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
  [{:keys [config run-id] :as ctx} day stories notes]
  (m/sp
    (let [searches (:precedent-searches config)]
      (when (pos? (or searches 0))
        (let [llm-config (research-llm config)
              _ (update-status! run-id assoc :state :researching :items (count stories))
              _ (log! run-id {:text (str "Asking " (name (:alias llm-config)) " (" (:model llm-config)
                                         ") to look for precedents for the day's stories")})
              prompt (precedents/render-prompt (config/precedent-template config)
                                               (str (sources/long-date day) " (" day ")")
                                               stories notes searches sources/long-date)
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

(defn- follow-stories
  "The items linked to the storylines of the last :trend-days (7 by
  default) before `day`, then ranked by how widely and how long their
  stories have run, heaviest first."
  [store config day items]
  (let [threshold (:story-threshold config)
        lines (when threshold
                (trends/storylines (store/sources-between store (minus-days day (:trend-days config 7))
                                                          (minus-days day 1))))]
    (trends/rank (trends/link-stories items lines threshold) lines day (:half-life-days config 2))))

(defn- notes-llm
  "The model that keeps the notes: the :notes role's when the config gives
  it one, else the analyst's."
  [config]
  (providers/role-llm config (if (get-in config [:roles :notes]) :notes :analyst)))

(defn- update-notes
  "Task: the notes on the day's storylines updated from its reports by the
  analyst, and stored. A failure is logged rather than thrown, since the
  day's briefing is already filed; only a cancel propagates."
  [{:keys [config store run-id] :as ctx} day numbered cited noted max-facts]
  (m/sp
    (let [candidates (notes/candidates numbered cited noted)]
      (when (seq candidates)
        (let [old (store/notes store (map first candidates))
              {:keys [prompt ids]} (notes/compaction-prompt day candidates old max-facts)
              llm-config (notes-llm config)
              _ (log! run-id {:text (str "Updating the notes on " (count candidates) " storylines")})
              chat (or (:chat ctx) llm/chat)
              result (m/? (m/attempt (m/via m/blk (chat llm-config {:messages [{:role "user" :content prompt}]
                                                                    :on-delta (fn [_])}))))]
          (try
            (let [updated (notes/apply-answer day candidates old ids (:content (result)) max-facts)]
              (if (seq updated)
                (do (store/save-notes! store updated)
                    (log! run-id {:text (str "Updated the notes on " (count updated) " storylines") :level :ok}))
                (log! run-id {:text "The notes weren't updated: the answer held no notes" :level :error})))
            (catch Throwable e
              (when (interrupted? e) (throw e))
              (log! run-id {:text (str "The notes weren't updated: " (or (ex-message e) (str e)))
                            :level :error}))))))))

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
            fresh (news/unseen-items gathered (news/told (mapcat :sources seen)))
            _ (when (< (count fresh) (count gathered))
                (log! run-id {:text (str "Left out " (- (count gathered) (count fresh))
                                         " stories already in the briefings up to "
                                         (sources/long-date (:day earlier)))}))
            _ (when (:story-threshold config)
                (m/? (follow-old-days ctx (minus-days day (:trend-days config 7)) (minus-days day 1))))
            threshold (:dupe-threshold config)
            embedded-items (if (or threshold (:story-threshold config))
                             (embed-items fresh)
                             fresh)
            collapsed (news/collapse-similar embedded-items threshold)
            _ (when (< (count collapsed) (count embedded-items))
                (log! run-id {:text (str "Collapsed " (- (count embedded-items) (count collapsed))
                                         " near-duplicate stories across outlets")}))
            ranked (follow-stories store config day collapsed)
            _ (when-let [followed (seq (filter #(> (:days %) 1) ranked))]
                (log! run-id {:text (str "Followed " (count followed) " stories from earlier days, the longest"
                                         " in the news for " (apply max (map :days followed)) " days")}))
            ;; a story that starts today is named by its own day and number
            ranked (->> ranked (take (:max-items config 80)) news/cite
                        (mapv (fn [s] (assoc s :story (or (:story s) (str day "/" (:n s)))))))
            _ (when (empty? ranked)
                (throw (ex-info (if (seq gathered)
                                  (str "every story gathered was already in the briefings up to "
                                       (sources/long-date (:day earlier)))
                                  "no items were gathered from any source")
                                {})))
            _ (log! run-id {:text (str (count ranked) " stories to analyse after dropping"
                                       " duplicates and older items")})
            ;; the second round of research: historical precedents for the
            ;; day's stories, numbered after them, cited like any other
            max-facts (:story-notes config)
            kept (when max-facts (store/notes store (map :story ranked)))
            history (m/? (precedents-task ctx day ranked kept))
            numbered (news/cite (into ranked history))
            llm-config (providers/role-llm config :analyst)
            established (some-> (:markdown earlier) news/overview)
            _ (when established
                (log! run-id {:text (str "Building on the briefing for " (sources/long-date (:day earlier)))}))
            running (notes/background kept numbered sources/long-date)
            _ (when running
                (log! run-id {:text "Giving the model the notes on the stories still running"}))
            prompt (news/render-prompt (or (:template ctx) (config/prompt-template config))
                                       (str (sources/long-date day) " (" day ")")
                                       numbered
                                       (some->> [(some->> established
                                                          (previous-context (:day earlier)))
                                                 running]
                                                (remove nil?)
                                                seq
                                                (str/join "\n\n")))
            _ (update-status! run-id assoc :state :analysing :items (count numbered)
                              :provider (name (:alias llm-config)) :model (:model llm-config))
            {:keys [answer reply]} (m/? (write-up ctx llm-config prompt "the briefing"))
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
        (when max-facts
          (m/? (update-notes ctx day numbered cited (keys kept) max-facts)))
        (when-let [gone (seq (prune-days! ctx))]
          (log! run-id {:text (str "Dropped " (count gone) " old "
                                   (if (= 1 (count gone)) "day" "days")
                                   " past the limit of " (:keep-days config default-keep-days))}))
        (swap! stored inc)
        {:day day :digest nil :items (count numbered) :cited (count cited)}))))

;; --- digests -----------------------------------------------------------------------

(defn period-label
  "A digest's period the way a reader says it: the week of 28 September
  2026, or September 2026."
  [kind period]
  (let [[from _] (trends/period-range kind period)]
    (case kind
      :week (str "the week of " (sources/long-date from))
      :month (second (str/split (sources/long-date from) #" " 2)))))

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
                     own (get stored story)
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
        run-id (or (:run-id ctx) (Object.))
        ctx (assoc ctx :run-id run-id)
        label (period-label kind period)
        job {:kind kind :period period}]
    (m/sp
      (when own? (reset! status {:run run-id :state :starting :digest job :started (now)}))
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
            prompt (trends/render-digest-prompt
                    (or (:digest-template ctx) (config/digest-template config))
                    label
                    (trends/days-block days sources/long-date)
                    (trends/digest-block lines sources/long-date)
                    (some->> established (previous-digest-context kind last-period)))
            llm-config (providers/role-llm config :analyst)
            _ (update-status! run-id assoc :state :analysing :items (count lines)
                              :provider (name (:alias llm-config)) :model (:model llm-config))
            {:keys [answer reply]} (m/? (write-up ctx llm-config prompt (str "the digest for " label)))
            doc (news/briefing answer numbered)
            known (set (map :n numbered))
            cited (filterv known (news/citations answer))]
        (store/save-digest! store {:kind kind
                                   :period period
                                   :sources numbered
                                   :cited cited
                                   :markdown doc
                                   :tldr (news/tldr doc)
                                   :model (or (:model reply) (:model llm-config))
                                   :provider (name (:alias llm-config))})
        (write-markdown! (or (:markdown-dir ctx) (config/path "briefings")) period doc)
        (log! run-id {:text (str "Filed the digest for " label ": " (count cited) " of "
                                 (count numbered) " sources cited") :level :ok})
        (swap! stored inc)
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

(defn running? [] (some? @current))

(defn- start-job!
  "Start the task `(make-task token)` unless a run is in flight, with the
  status starting from `init`. Returns true when started."
  [init make-task]
  (let [token (Object.)]
    (when (compare-and-set! current nil {:token token})
      (reset! status (merge {:run token :state :starting :started (now) :sources {}} init))
      (let [finish! (fn [m]
                      (update-status! token merge m {:finished (now)})
                      (swap! current #(when-not (= token (:token %)) %)))
            cancel ((make-task token)
                    (fn [summary] (finish! (assoc summary :state :done)))
                    (fn [e] (finish! (cond
                                       (interrupted? e) {:state :cancelled}
                                       ;; a period with nothing worth a digest isn't a failure
                                       (::nothing-to-digest (ex-data e)) {:state :done :nothing (ex-message e)}
                                       :else {:state :failed :error (or (ex-message e) (str e))}))))]
        (swap! current #(if (= token (:token %)) (assoc % :cancel cancel) %))
        true))))

(defn start-run!
  "Start a run for `day` unless one is in flight. Returns true when started."
  [ctx day]
  (start-job! {:day day} #(run-task (assoc ctx :run-id %) day)))

(defn start-digest!
  "Start writing the digest of `kind` for `period` unless a run is in
  flight. Returns true when started."
  [ctx kind period]
  (start-job! {:digest {:kind kind :period period}}
              #(digest-task (assoc ctx :run-id %) kind period)))

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
      (start-job! (if daily? {:day day} {:digest (zipmap [:kind :period] (first digests))})
                  #(scheduled-task (assoc ctx :run-id %) day daily? digests)))))

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
