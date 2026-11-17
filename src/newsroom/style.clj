(ns newsroom.style
  "The style check on a written briefing: the sentences that carry a tell
  of machine writing, found by pattern rather than by a model. No IO.

  The analyst's prompt asks it to avoid these habits, and a draft still
  slips some through: a dash, a not-X-but-Y contrast, a stock word like
  pivotal. The ones a pattern finds reliably are listed here as problems in
  the critic's shape, {:quote :kind :problem :fix} with :kind \"style\", so
  they go back to the analyst with the critic's own in one revision. Code,
  the map's fragments and link targets aren't prose and aren't checked."
  (:require [clojure.string :as str]))

(def max-issues
  "How many sentences one check sends back at most, so a draft full of
  tells gets a revision request of a readable size."
  12)

(defn- words-re [ws]
  (re-pattern (str "(?i)\\b(" (str/join "|" ws) ")\\b")))

(def ^:private tells
  "Each tell: [:kind regex what], where `what` names a match in the problem
  line. A regex with a group names the group's text instead."
  [[:punctuation #"[—–]|\s--\s" "a dash"]
   [:punctuation #";" "a semicolon"]
   [:contrast #"(?i)\bnot (?:just|only|merely|simply)\b[^.!?]*\bbut\b" "a not-X-but-Y contrast"]
   [:contrast #"(?i)\b(?:it|this|that)(?:['’]s| is) not\b[^.!?]*?,\s*(?:it|this|that)(?:['’]s| is)\b"
    "an it's-not-X-it's-Y contrast"]
   [:contrast #"(?i)\b(?:it|this|that) isn['’]t\b[^.!?]*?,\s*(?:it|this|that)(?:['’]s| is)\b"
    "an it's-not-X-it's-Y contrast"]
   [:contrast #"(?i)\b(?:does not|doesn['’]t|is not|isn['’]t) (?:mean|about)\b[^.!?]*[.!?]\s+(?:it|this|that)(?:['’]s| is| means)\b"
    "a contrast split over two sentences"]
   [:word (words-re ["moreover" "furthermore" "additionally" "notably" "in essence" "ultimately"]) nil]
   [:word (words-re ["delve[sd]?" "pivotal" "crucial" "tapestry" "testament" "underscor(?:e[sd]?|ing)"
                     "highlight(?:s|ed|ing)?" "showcas(?:e[sd]?|ing)" "bolster(?:s|ed|ing)?"
                     "garner(?:s|ed|ing)?" "intricate" "interplay" "seismic" "landscape"]) nil]
   [:staging (words-re ["here['’]s the thing" "here['’]s what" "let['’]s look at" "let['’]s be clear"
                        "it['’]s worth noting" "make no mistake" "to be clear" "the real question is"
                        "at its core" "the heart of the matter" "what really matters"]) nil]
   [:tail #"(?i),\s+(signal(?:l)?ing|reflecting|cementing|fueling|fuelling)\b" nil]])

(def ^:private fixes
  {:punctuation "use a period or a comma in place of the dash or semicolon"
   :contrast "state the point directly, without the contrast"
   :word "use a plain word, or drop it"
   :staging "start with the point itself"
   :tail "give the consequence a clause of its own with its cause, or cut it"})

(defn- prose-lines
  "The prose of markdown `md`, one string per paragraph, list item, heading
  or quote, with code, fenced blocks, link targets and URLs taken out and
  the markdown markers stripped."
  [md]
  (let [text (-> (str md)
                 (str/replace #"(?ms)^```.*?^```[^\n]*$" "")
                 (str/replace #"`[^`\n]*`" "")
                 (str/replace #"\]\([^)\s]*\)" "]")
                 (str/replace #"https?://\S+" "")
                 (str/replace "**" ""))
        starts? #(re-find #"^\s*(?:#+\s|>|[-*+]\s|\d+\.\s)" %)
        strip #(-> % (str/replace #"^\s*(?:#+\s+|>\s*|[-*+]\s+|\d+\.\s+)" "") str/trim)]
    (->> (str/split-lines text)
         (reduce (fn [acc line]
                   (cond
                     (str/blank? line) (conj acc "")
                     (or (starts? line) (str/blank? (peek acc))) (conj acc (strip line))
                     :else (conj (pop acc) (str (peek acc) " " (str/trim line)))))
                 [""])
         (remove str/blank?))))

(defn- sentences
  "The sentences of a paragraph `p`, with a sentence that denies a meaning
  joined to the next one when that one states it, so a contrast split over
  the two reads as one."
  [p]
  (let [ss (vec (re-seq #"\S.*?(?:[.!?]+[\"'”’)\]]*(?=\s+[A-Z\"“'(\[*])|$)" p))
        denies? #(re-find #"(?i)\b(?:does not|doesn['’]t|is not|isn['’]t) (?:mean|about)\b" %)
        states? #(re-find #"(?i)^(?:it|this|that)(?:['’]s| is| means)\b" %)]
    (loop [i 0 out []]
      (cond
        (>= i (count ss)) out
        (and (denies? (ss i)) (< (inc i) (count ss)) (states? (ss (inc i))))
        (recur (+ i 2) (conj out (str (ss i) " " (ss (inc i)))))
        :else (recur (inc i) (conj out (ss i)))))))

(defn- found
  "The tells in sentence `s`: [[kind what] ...], each once, in order."
  [s]
  (distinct
   (for [[kind re what] tells
         m (re-seq re s)
         :let [hit (if (string? m) m (second m))]]
     [kind (or what (str "\"" (str/lower-case hit) "\""))])))

(defn- issue [s hits]
  (let [whats (map second hits)]
    {:quote s
     :kind "style"
     :problem (str "Reads as machine-written: "
                   (if (next whats)
                     (str (str/join ", " (butlast whats)) " and " (last whats))
                     (first whats))
                   ".")
     :fix (str/join " " (for [f (distinct (map (comp fixes first) hits))]
                         (str (str/upper-case (subs f 0 1)) (subs f 1) ".")))}))

(defn issues
  "The sentences of the briefing `md` that carry a tell, as problems in the
  critic's shape, at most `max-issues` of them, [] when there are none."
  [md]
  (->> (prose-lines md)
       (mapcat sentences)
       distinct
       (keep (fn [s] (let [hits (found s)] (when (seq hits) (issue s hits)))))
       (take max-issues)
       vec))
