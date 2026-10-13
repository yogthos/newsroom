(ns newsroom.llm.client
  "One chat completion against a configured provider, with the retry
  discipline from samizdat.llm.client: a 429 that is a usage cap, and any
  error we do not recognize, is not retried; a rate limit, an overload or a
  transport failure is, with backoff and whatever wait the provider asked
  for. Blocking: run it on a blocking executor (ebb's m/blk)."
  (:require [jolt.time]
            [newsroom.json :as json]
            [clojure.string :as str]
            [jolt.http-client :as http]
            [newsroom.llm.adapter :as adapter]
            [newsroom.llm.builtin]
            [newsroom.llm.provider :as provider]
            [newsroom.llm.stream :as stream]))

(def max-backoff-ms 60000)
(def max-attempts 4)

(defn retry-after-ms
  "How long the provider asked us to wait, from the response headers, or nil."
  [headers]
  (let [h (fn [k] (get headers k))
        secs (some-> (or (h "retry-after") (h "Retry-After")) str/trim parse-long)
        reset (some-> (or (h "x-ratelimit-reset-requests")
                          (h "x-ratelimit-reset-tokens"))
                      str/trim
                      (str/replace #"[sm]$" "")
                      parse-long)]
    (when-let [s (or secs reset)]
      (* 1000 (max 0 s)))))

(defn classify
  "What to do about a failed response: :retry when it is transient, :fatal
  when the answer will not change."
  [adapter status body]
  (cond
    (and (= 429 status) (adapter/usage-cap? adapter status body)) :fatal
    (= 429 status) :retry
    (>= status 500) :retry
    (= 408 status) :retry
    :else :fatal))

(defn- backoff-ms
  "2s, 8s, 32s with up to +25% jitter, or what the provider asked for, never
  past a minute."
  [attempt headers]
  (min max-backoff-ms
       (or (retry-after-ms headers)
           (long (* (+ 1.0 (rand 0.25)) (* 2000 (Math/pow 4 attempt)))))))

(defn- interrupted?
  "Whether `e` is a cancel reaching the calling thread rather than a failure
  of the call: ebb cancels a blocking task by interrupting its thread."
  [e]
  (instance? InterruptedException e))

(defn- decode [body]
  (try (json/read-str (str body) :key-fn keyword) (catch Throwable _ nil)))

(defn- post-once [adapter llm request]
  (try
    (let [streamed? (boolean (and (:on-delta request)
                                  (contains? (:features llm) :stream)))
          body (cond-> (adapter/chat-body adapter llm request)
                 streamed? (assoc :stream true :stream_options {:include_usage true}))
          opts {:headers (merge (adapter/auth-headers adapter llm)
                                (:headers llm)
                                {"Content-Type" "application/json"})
                :body (json/write-str body)
                :socket-timeout (:timeout-ms llm)
                :conn-timeout (:conn-timeout-ms llm)}
          resp (if streamed?
                 (stream/post (adapter/chat-url adapter llm) opts (:on-delta request))
                 (http/post (adapter/chat-url adapter llm) (assoc opts :throw-exceptions false)))
          status (:status resp)
          decoded (decode (:body resp))]
      (if (<= 200 status 299)
        (if-let [reply (and decoded (adapter/parse-chat adapter decoded))]
          {:outcome :ok :reply reply}
          {:outcome :fatal :error (str (adapter/display-name adapter)
                                       " answered with no completion")})
        {:outcome (classify adapter status decoded)
         :headers (:headers resp)
         :error (str (adapter/display-name adapter) " " status ": "
                     (or (and decoded (adapter/error-message adapter decoded))
                         (let [b (str (:body resp))] (subs b 0 (min 300 (count b))))))}))
    (catch Throwable e
      ;; a cancel is not a transport failure: retrying it would send the
      ;; request again after the caller has given up on it
      (if (interrupted? e)
        (throw e)
        {:outcome :retry :error (str "transport: " (or (ex-message e) (str e)))}))))

(defn chat
  "Send `request` ({:messages [{:role :content}] :max-tokens :temperature
  :reasoning-effort :on-delta}) to the provider `llm` describes, as resolved
  by newsroom.llm.providers. With :on-delta, a provider whose features say
  :stream streams its reply, calling it with {:text} and {:reasoning} deltas
  as they arrive; the others answer whole. Returns {:content :reasoning :finish-reason :model
  :usage}; throws when the call fails for good."
  [llm request]
  (let [adapter (provider/adapter (:type llm))
        request (merge {:max-tokens (:max-tokens llm)
                        :temperature (:temperature llm)}
                       request)]
    (loop [attempt 0]
      (let [{:keys [outcome reply error headers]} (post-once adapter llm request)]
        (cond
          (= :ok outcome) reply

          (and (= :retry outcome) (< (inc attempt) max-attempts))
          (do (when (Thread/interrupted)
                (throw (InterruptedException. "cancelled before a retry")))
              (Thread/sleep (backoff-ms attempt headers))
              (recur (inc attempt)))

          :else
          (throw (ex-info error {:provider (:alias llm) :attempts (inc attempt)})))))))
