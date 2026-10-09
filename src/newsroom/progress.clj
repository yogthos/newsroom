(ns newsroom.progress
  "How far a model has got with an answer it streams: the words it has
  thought and written so far, the section it is in and its last lines.
  The desk shows it while a briefing or a digest is written, and the
  topics pane while a report or a reply is."
  (:require [clojure.string :as str]))

(defn word-count [text] (count (re-seq #"[A-Za-z][A-Za-z'’-]*" text)))

(defn- last-heading [text]
  (some-> (last (re-seq #"(?m)^#{2,3}\s+(.+)$" text)) second str/trim))

(defn- tail [text n]
  (let [t (str/trim text)]
    (if (<= (count t) n)
      t
      (let [cut (subs t (- (count t) n))
            space (str/index-of cut " ")]
        (str "…" (if space (subs cut (inc space)) cut))))))

(def ^:private every-ms 300)

(defn watcher
  "An :on-delta for a model call, {:on-delta :flush!}. It collects the
  stream and calls `on-progress` with {:words :reasoning-words :section
  :tail} at most every few hundred milliseconds, and once more on
  `flush!`. `on-thinking` and `on-writing` are called once each, when
  the model starts to think and to write."
  [{:keys [on-progress on-thinking on-writing now]
    :or {on-thinking (fn []) on-writing (fn []) now #(System/currentTimeMillis)}}]
  (let [buf (StringBuilder.)
        thought (atom 0)
        began (atom #{})
        flushed (atom nil)
        once! (fn [k f] (when-not (@began k) (swap! began conj k) (f)))
        flush! (fn []
                 (reset! flushed (now))
                 (let [t (str buf)]
                   (on-progress {:words (word-count t)
                                 :reasoning-words @thought
                                 :section (last-heading t)
                                 :tail (tail t 280)})))]
    {:on-delta (fn [{:keys [text reasoning]}]
                 (locking buf
                   (when (seq reasoning)
                     (once! :thinking on-thinking)
                     (swap! thought + (word-count reasoning)))
                   (when (seq text)
                     (once! :writing on-writing)
                     (.append buf text))
                   (when (or (nil? @flushed) (> (- (now) @flushed) every-ms))
                     (flush!))))
     :flush! (fn [] (locking buf (flush!)))}))

(defn- grouped
  "`n` with its thousands set off by commas."
  [n]
  (str/replace (str n) #"\B(?=(\d{3})+$)" ","))

(defn- counted [n] (str (grouped n) (if (= 1 n) " word" " words")))

(defn summary
  "Progress in a line: the reasoning while the model thinks, then what it
  has written and the section it is in."
  [{:keys [words reasoning-words section]}]
  (if (pos? (or words 0))
    (str (counted words) " written" (when section (str " · " section)))
    (str "Thinking" (when (pos? (or reasoning-words 0))
                      (str " · " (counted reasoning-words) " of reasoning")))))
