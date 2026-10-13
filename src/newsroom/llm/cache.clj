(ns newsroom.llm.cache
  "The models' answers kept by exactly what they were asked, so that running
  a day again reuses every answer whose question came out the same: the
  sorting of a batch nothing new joined, a story's dossier, the briefing
  itself when the run failed after it. An answer can't go stale, since any
  change to the reports, the notes, a prompt, the model or its settings
  changes the question. What a reader is asked is the same for another
  reader only when it holds the same news, so the answers are kept for
  every reader, as the embeddings are."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [newsroom.config :as config]
            [newsroom.store :as store]))

(defn- canonical
  "`x` with its maps' keys and its sets in order, so that the same question
  prints the same however it was built."
  [x]
  (walk/postwalk (fn [v]
                   (cond
                     (map? v) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2))) v)
                     (set? v) (vec (sort-by pr-str v))
                     :else v))
                 x))

(defn request-key
  "The hash of what `request` asks the endpoint `llm` describes: the model,
  where it is and how it's run, and the messages, not the key it's asked
  with, what it's called or how long it's waited on."
  [llm request]
  (config/sha-256 (pr-str (canonical [(dissoc llm :api-key :headers :alias :timeout-ms :conn-timeout-ms)
                                      (dissoc request :on-delta)]))))

(defn- keep? [{:keys [content finish-reason]}]
  (and (not (str/blank? content)) (not= "length" finish-reason)))

(defn cached
  "`chat` answering what was asked before from the answer `store` kept for
  it, when it was kept less than `max-age-ms` ago; 0 or nil asks every
  time. A streamed call hears a kept answer whole. `fresh?` asks every
  time and keeps the new answers. An answer that's blank or was cut off
  isn't kept, and a question asked a second time in a run, as a retry
  does, is asked again. `on-hit` is called with each answer reused."
  [chat store {:keys [max-age-ms fresh? on-hit]}]
  (if-not (and max-age-ms (pos? max-age-ms))
    chat
    (let [asked (atom #{})]
      (fn [llm request]
        (let [k (request-key llm request)
              again? (contains? @asked k)
              _ (swap! asked conj k)
              reply (when-not (or fresh? again?) (store/saved-reply store k max-age-ms))]
          (if reply
            (do (when-let [on-delta (:on-delta request)] (on-delta {:text (:content reply)}))
                (when on-hit (on-hit reply))
                reply)
            (let [reply (chat llm request)]
              (when (keep? reply) (store/save-reply! store k reply))
              reply)))))))
