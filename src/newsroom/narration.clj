(ns newsroom.narration
  "The podcast of a day's briefing, written and recorded once the briefing
  is filed. The model writes the script (see newsroom.podcast), the :podcast
  role's or the analyst's, and the speech engine speaks it (newsroom.tts);
  the episode is stored with the day.

  Recording takes minutes, so it doesn't hold the run: it is a worker of
  its own, on a thread of its own. One podcast is made at a time, since
  the speech engine takes gigabytes, and for each reader at most one more
  waits for it, their newer request taking the place of the one waiting;
  readers' requests wait in turn. A podcast that fails says why in its
  reader's status and is never thrown into the run that asked for it.

  Progress is published to `status`, a glimmer ratom, for the sidebar to
  show live. `phase` is the part of it that changes only when the work
  moves to its next stage, for the day's page, which shouldn't redraw at
  every line. Those are the local reader's; another reader's are in their
  `lane`."
  (:require [clojure.string :as str]
            [glimmer.ratom :as ratom]
            [newsroom.config :as config]
            [newsroom.llm.client :as llm]
            [newsroom.llm.providers :as providers]
            [newsroom.podcast :as podcast]
            [newsroom.sources :as sources]
            [newsroom.store :as store]
            [newsroom.tts :as tts]))

(defonce ^{:doc "The podcast being made or made last: {:state :day :done
  :total :error :pending}, :state :idle, :writing, :speaking, :done,
  :failed or :cancelled. While :writing, :done and :total count the
  script's segments, and while :speaking, its lines; :note, before the
  first line, is what the engine says it is doing, the first time it
  fetches and converts its model, and :device what it records on.
  :pending is the day waiting to be made next."}
  status (ratom/atom {:state :idle}))

(defonce ^{:doc "The day being made, how far and what waits: `status`
  without the counts, which change only as the work moves on."}
  phase (ratom/reaction (select-keys @status [:state :day :pending])))

(defonce ^:private lanes (atom {}))

(defn lane
  "The reader `uid`'s podcasts: {:status :phase}, as `status` and `phase`
  are the local reader's, which they are for `uid` nil."
  [uid]
  (if (or (nil? uid) (= store/local-user-id uid))
    {:status status :phase phase}
    (or (get @lanes uid)
        (get (swap! lanes (fn [m]
                            (if (contains? m uid)
                              m
                              (let [st (ratom/atom {:state :idle})]
                                (assoc m uid {:status st
                                              :phase (ratom/reaction (select-keys @st [:state :day :pending]))})))))
             uid))))

(defn- uid-of [ctx] (some-> (:store ctx) store/user-id))

(defn- status-of [job] (:status (lane (:uid job))))

(defonce ^{:doc "The podcast being made and those waiting: {:current job
  :queue [job]}, a job {:ctx :day :uid :cancelled}, at most one waiting
  for each reader."}
  ^:private jobs (atom {}))

(defn busy?
  "Whether a podcast is being made or waits to be."
  []
  (some? (:current @jobs)))

(defn- cancelled-error []
  (ex-info "the podcast was cancelled" {::cancelled true}))

(defn- waiting-on
  "What `f` returns, run on a thread of its own and waited on in short
  steps, so that a cancel doesn't wait for a slow model to answer. Throws
  what `f` throws, and when `cancelled?` turns true."
  [cancelled? f]
  (let [result (future (try {:value (f)} (catch Throwable e {:error e})))]
    (loop []
      (let [r (deref result 100 ::waiting)]
        (cond
          (not= ::waiting r) (if-let [e (:error r)] (throw e) (:value r))
          (cancelled?) (do (future-cancel result) (throw (cancelled-error)))
          :else (recur))))))

(defn- podcast-llm
  "The model that writes the script: the :podcast role's when the config
  gives it one, else the analyst's."
  [config]
  (providers/role-llm config (if (get-in config [:roles :podcast]) :podcast :analyst)))

(defn- dated [day] (str (sources/long-date day) " (" day ")"))

(defn- record!
  "Write and record the podcast of `day` and store it, reporting to
  `status` as it goes. Blocks; throws when it can't be made, and when
  `cancelled?` turns true."
  [{:keys [config store on-saved] :as ctx} status day cancelled?]
  (let [chat (or (:chat ctx) llm/chat)
        llm-config (podcast-llm config)
        hosts (podcast/hosts (:podcast-hosts config) (tts/voices))
        markdown (:markdown (store/day store day))
        _ (when-not markdown
            (throw (ex-info (str "there is no briefing for " (sources/long-date day)) {})))
        _ (when (< (count hosts) 2)
            (throw (ex-info (str "the podcast needs two hosts, each with a name and one of the speech "
                                 "engine's voices")
                            {})))
        briefing (podcast/briefing-text markdown)
        ask (fn [prompt]
              (when (cancelled?) (throw (cancelled-error)))
              (waiting-on cancelled?
                          #(chat llm-config {:messages [{:role "user" :content prompt}] :on-delta (fn [_])})))
        n (podcast/segment-count (:podcast-segments config))
        reply (ask (podcast/render-outline (config/podcast-outline-template config) (dated day) briefing hosts n))
        outline (podcast/parse-outline (:content reply) n)
        _ (when-not outline
            (throw (ex-info "the model's answer held no outline of the podcast's segments" {})))
        _ (swap! status assoc :done 0 :total (count outline))
        [transcript reply]
        (reduce (fn [[transcript _] i]
                  (let [reply (ask (podcast/render-transcript (config/podcast-transcript-template config)
                                                              (dated day) briefing hosts outline transcript i))]
                    (swap! status assoc :done (inc i))
                    ;; a segment that came back with no dialogue leaves a
                    ;; gap rather than costing the episode
                    [(into transcript (podcast/parse-transcript (:content reply) hosts)) reply]))
                [[] reply]
                (range (count outline)))
        lines (podcast/tts-lines transcript hosts)
        _ (when (empty? lines)
            (throw (ex-info "the model wrote no dialogue for the podcast" {})))
        _ (swap! status assoc :state :speaking :done 0 :total (count lines))
        {:keys [mp3 seconds]} (tts/synthesize lines {:on-progress (fn [done total]
                                                                      (swap! status assoc :done done :total total
                                                                             :note nil))
                                                     ;; the first time, the engine fetches its model
                                                     :on-status #(swap! status assoc :note %)
                                                     :on-device #(swap! status assoc :device %)
                                                     :engine (:podcast-engine config :kitten-2)
                                                     :device (:podcast-device config :auto)
                                                     :cancelled? cancelled?})]
    (when (cancelled?) (throw (cancelled-error)))
    (store/save-narration! store {:day day :mp3 mp3 :transcript transcript :seconds seconds
                                  :model (or (:model reply) (:model llm-config))
                                  :provider (some-> (:alias llm-config) name)})
    (when on-saved (on-saved))
    seconds))

(defn- run-job!
  "Make the podcast of `job`, catching whatever stops it into its
  reader's status."
  [{:keys [ctx day cancelled] :as job}]
  (let [status (status-of job)
        cancelled? #(deref cancelled)]
    (swap! status merge {:state :writing :day day :done 0 :total nil :error nil :seconds nil :note nil
                         :device nil})
    (try
      (let [seconds (record! ctx status day cancelled?)]
        (swap! status merge {:state :done :seconds seconds}))
      (catch Throwable e
        (swap! status merge (if (or (cancelled?) (::cancelled (ex-data e)))
                              {:state :cancelled}
                              {:state :failed :error (or (ex-message e) (str e))}))))))

(declare launch!)

(defn- next!
  "Once `job` is over, start the first one waiting, if any."
  [job]
  (let [[old new] (swap-vals! jobs (fn [js]
                                     (if (identical? job (:current js))
                                       {:current (first (:queue js)) :queue (vec (rest (:queue js)))}
                                       js)))]
    (when-let [next (:current new)]
      (when-not (identical? next (:current old))
        (swap! (status-of next) assoc :pending nil)
        (launch! next)))))

(defn- launch! [job]
  (future
    (try (run-job! job)
         (finally (next! job)))))

(defn enqueue!
  "Make the podcast of the stored `day`: now, when no other is being
  made, else once the ones before it are done, in place of any of its
  reader's already waiting. `ctx` is {:config :store}, and optionally
  :chat (the model call, llm/chat by default) and :on-saved, called once
  the episode is stored. Returns true."
  [ctx day]
  (let [uid (uid-of ctx)
        job {:ctx ctx :day day :uid uid :cancelled (atom false)}
        theirs? #(= uid (:uid %))
        [_ new] (swap-vals! jobs (fn [{:keys [current queue] :as js}]
                                   (cond
                                     (nil? current) {:current job :queue []}
                                     (some theirs? queue) (assoc js :queue (mapv #(if (theirs? %) job %) queue))
                                     :else (assoc js :queue (conj (vec queue) job)))))]
    (if (identical? job (:current new))
      (launch! job)
      (swap! (status-of job) assoc :pending day))
    true))

(defn cancel!
  "Stop the podcast the reader `uid`, the local one by default, has being
  made and forget the one they have waiting. Returns true when there was
  one to stop."
  ([] (cancel! nil))
  ([uid]
   (let [uid (or uid store/local-user-id)
         theirs? #(= uid (or (:uid %) store/local-user-id))
         [old _] (swap-vals! jobs update :queue #(vec (remove theirs? %)))]
     (swap! (:status (lane uid)) assoc :pending nil)
     (let [current (:current old)
           waiting (some theirs? (:queue old))]
       (when (and current (theirs? current))
         (reset! (:cancelled current) true))
       (boolean (or (and current (theirs? current)) waiting))))))
