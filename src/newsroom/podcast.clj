(ns newsroom.podcast
  "The podcast of a day's briefing, its script: no IO, no clock, no
  storage.

  Two hosts talk the briefing through. The script is written in two
  stages, as open-notebook writes its podcasts: an outline of the episode's
  segments, each sized short, medium or long, then the dialogue of each
  segment in turn, written with the outline and the conversation so far.
  The dialogue goes straight to the speech engine (see newsroom.tts), so
  every line is cleaned down to the words said aloud and the few
  expression tags the engine understands, and spoken in its host's voice."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [newsroom.analysis :as analysis]
            [newsroom.news :as news]
            [newsroom.template :as template]))

(def turns
  "How many turns of dialogue a segment of each size is asked for, at
  least."
  {"short" 6 "medium" 10 "long" 14})

(def emotions
  "The emotion tags the speech engine reads at the start of a line."
  #{"angry" "contemplative" "excited" "joyful" "mundane" "nervous" "sad" "stern" "surprised" "tender"})

(def default-segments 5)

(defn segment-count
  "How many segments an episode has: `n`, held between 2 and 12, or 5."
  [n]
  (if (number? n) (max 2 (min 12 (long n))) default-segments))

(defn hosts
  "The hosts who can speak, from the configured `hosts`: the first two with
  a name and a voice among the engine's `voices`, matched without regard to
  case and given as the engine names it."
  [hosts voices]
  (let [by-name (into {} (map (juxt str/lower-case identity)) voices)]
    (->> hosts
         (keep (fn [{:keys [name voice] :as h}]
                 (let [name (some-> name str str/trim)
                       voice (get by-name (some-> voice str str/trim str/lower-case))]
                   (when (and (not (str/blank? name)) voice)
                     (assoc h :name name :voice voice)))))
         (take 2)
         vec)))

(def ^:private briefing-chars
  "About how much of the briefing the script is written from: all of a
  full one."
  24000)

(defn briefing-text
  "The briefing as the script is written from it: its text without
  citations, diagrams or the Sources list."
  [markdown]
  (news/body markdown briefing-chars 2))

;; --- the outline -------------------------------------------------------------------

(defn render-outline
  "The prompt for the episode's outline: the Selmer `template` with
  {{date}}, {{briefing}}, the briefing's text, {{hosts}}, each with its
  :name, :backstory and :personality, and {{segments}}, how many to plan,
  filled in."
  [template date briefing hosts segments]
  (template/fill template {:date date :briefing briefing :hosts hosts :segments segments}))

(defn- answer-json
  "The JSON object in a model's answer, any reasoning in <think> tags
  before it left out."
  [answer]
  (analysis/json-object (str/replace (str answer) #"(?s)<think>.*?</think>" "")))

(defn- text [v]
  (when (string? v)
    (let [s (str/trim v)]
      (when-not (str/blank? s) s))))

(defn parse-outline
  "The segments in the model's `answer`, at most `n` of them, each {:name
  :description :size}, a size it doesn't know being medium. nil when the
  answer holds none. A segment needs a name."
  [answer n]
  (let [segments (get (answer-json answer) "segments")]
    (when (sequential? segments)
      (not-empty
       (vec (take n (for [s segments
                          :when (map? s)
                          :let [name (text (get s "name"))]
                          :when name]
                      {:name name
                       :description (or (text (get s "description")) "")
                       :size (let [size (some-> (text (get s "size")) str/lower-case)]
                               (if (contains? turns size) size "medium"))})))))))

;; --- the dialogue ------------------------------------------------------------------

(defn- outline-text [outline]
  (str/join "\n" (map-indexed (fn [i {:keys [name size description]}]
                                (str (inc i) ". " name " (" size "): " description))
                              outline)))

(defn transcript-text
  "The conversation so far as a prompt reads it, a line a turn."
  [transcript]
  (str/join "\n" (for [{:keys [speaker text]} transcript] (str speaker ": " text))))

(defn render-transcript
  "The prompt for the dialogue of segment `i` of `outline`: the Selmer
  `template` with {{date}}, {{briefing}}, {{hosts}}, {{names}}, the hosts'
  names joined, {{first}} and {{second}}, their names as JSON strings,
  {{outline}}, {{transcript}}, the conversation so far, {{segment}},
  {{final}}, true for the last segment, and {{turns}}, how many turns the
  segment's size asks for, filled in."
  [template date briefing hosts outline transcript i]
  (let [{:keys [name description size]} (nth outline i)
        names (map :name hosts)]
    (template/fill template {:date date
                             :briefing briefing
                             :hosts hosts
                             :names (str/join ", " names)
                             :first (json/write-str (first names))
                             :second (json/write-str (or (second names) (first names)))
                             :outline (outline-text outline)
                             :transcript (when (seq transcript) (transcript-text transcript))
                             :segment (str name ": " description)
                             :final (= i (dec (count outline)))
                             :turns (turns size 10)})))

(defn- directions [s]
  ;; (laughs), *laughs* or [laughs], but not the (((emphasis))) around a word:
  ;; the engine says a vocal event as a word, so there is none to make of it
  (str/replace s #"(?i)(^|[^(])[(*\[]\s*(?:laugh|laughs|laughing|chuckles?|chuckling|giggles?|giggling|sighs?|sighing|gasps?|gulps?|scoffs?|sobs?|pauses?|growls?)\s*[)*\]]"
               "$1"))

(defn- leading-emotion
  "[the emotion tag a line starts with, the rest], the tag nil when the
  line starts with none the engine knows."
  [s]
  (if-let [[_ tag rest] (re-matches #"(?s)\s*\[\s*([A-Za-z]+)\s*\](.*)" s)]
    (if (contains? emotions (str/lower-case tag)) [(str "[" (str/lower-case tag) "]") rest] [nil s])
    [nil s]))

(defn speakable
  "A line of dialogue as the speech engine is to say it: no markdown, no
  citations, no addresses, no stage directions, no tag but a leading
  emotion and (((emphasis))), and no ellipsis standing in for words. The
  engine says a vocal event like <laugh> as a word, so those go too."
  [line]
  (let [[emotion rest] (-> (str line)
                           (str/replace #"\[\[\d+\]\]\([^)]*\)" "")
                           (str/replace #"!?\[([^\]]*)\]\((?:https?://|www\.|/)[^)]*\)" "$1")
                           (str/replace #"\[\s*\d+(?:\s*[,–-]\s*\d+)*\s*\]" "")
                           (str/replace #"(?:https?://|www\.)[^\s)\]]*[^\s)\].,;:!?'\"]" "")
                           directions
                           leading-emotion)
        body (-> rest
                 (str/replace #"\[[^\]]*\]" "")
                 (str/replace #"</?\s*[A-Za-z]+[^>]*>" "")
                 (str/replace #"(?m)^\s*(?:#{1,6}|>|[-+•]|\d+\.)\s+" "")
                 (str/replace #"[*_`#]" "")
                 (str/replace #"(^|\s)(?:\.\.\.|…)(?=\s|$)" "$1")
                 (str/replace #"\s+" " ")
                 (str/replace #"\s+([,.;:!?])" "$1")
                 str/trim)]
    (cond
      (str/blank? body) ""
      emotion (str emotion " " body)
      :else body)))

(defn- words? [s]
  ;; a line of tags alone says nothing
  (re-find #"\w" (-> s
                     (str/replace #"^\[[a-z]+\]" "")
                     (str/replace #"<[a-z]+>" ""))))

(defn parse-transcript
  "The dialogue in the model's `answer`, [{:speaker :text}], each speaker
  one of `hosts`, named as the host is, and each text cleaned for speech.
  A turn by anyone else, or with nothing left to say, is dropped. nil when
  the answer holds no transcript."
  [answer hosts]
  (let [entries (get (answer-json answer) "transcript")
        by-name (into {} (map (fn [h] [(str/lower-case (:name h)) (:name h)])) hosts)]
    (when (sequential? entries)
      (vec (for [e entries
                 :when (map? e)
                 :let [speaker (some->> (get e "speaker") str (re-find #"[^*_\s][^*_]*") str/trim str/lower-case
                                        (get by-name))
                       line (when (string? (get e "dialogue")) (speakable (get e "dialogue")))]
                 :when (and speaker (not (str/blank? line)) (words? line))]
             {:speaker speaker :text line})))))

(defn tts-lines
  "The `transcript` as the speech engine's lines, [{:voice :text}], each
  in its host's voice; a turn by no host is left out."
  [transcript hosts]
  (let [voice (into {} (map (juxt :name :voice)) hosts)]
    (vec (for [{:keys [speaker text]} transcript
               :let [v (voice speaker)]
               :when v]
           {:voice v :text text}))))

(defn readable
  "A line of dialogue as a reader sees it in the transcript, without the
  speech engine's tags: the emotion dropped, the emphasis left as plain
  words."
  [line]
  (-> (str line)
      (str/replace #"^\s*\[[a-z]+\]\s*" "")
      (str/replace #"\s*<[a-z]+>" "")
      (str/replace #"\(\(\((.*?)\)\)\)" "$1")
      (str/replace #"\s+" " ")
      str/trim))
