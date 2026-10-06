(ns newsroom.critique
  "The critic's pass over the day's briefing, once the analyst has written
  it: no IO, no clock, no storage.

  The news carries claims that were really reported but can't be true, or
  can't happen the way they're told: a plan a country has no means to carry
  out, a capability an official promises and the industry can't deliver. An
  analyst that cites such a claim faithfully still misleads the reader when
  it treats it as a real prospect. The critic reads the briefing against
  the sources and against the material facts, what each actor actually has
  in industry, resources, money and time, and lists the claims that don't
  hold up, each with what is wrong with it and how to fix it. When it finds
  any, the analyst is shown the list and rewrites the briefing."
  (:require [clojure.string :as str]
            [newsroom.analysis :as analysis]
            [newsroom.news :as news]
            [newsroom.template :as template]))

(def kinds
  "What a problem the critic finds can be."
  ["implausible" "face-value" "unsupported" "reasoning" "overreach"])

(defn render-prompt
  "The critic's prompt: the Selmer `template` with {{date}}, {{briefing}},
  the analyst's draft, {{sources}}, the numbered `sources` it was written
  from, and {{analysis}}, the desk's dossiers and map, filled in. A template
  with no place for the briefing or the sources gets them at its end."
  [template date briefing sources analysis]
  (template/fill (news/place-vars template ["briefing" "sources"] "sources")
                 {:date date
                  :briefing briefing
                  :sources (news/source-block sources)
                  :analysis analysis}))

(defn clip
  "`s` trimmed, and cut to `n` characters with an ellipsis when longer."
  [s n]
  (let [s (str/trim (str s))]
    (if (> (count s) n) (str (subs s 0 (dec n)) "…") s)))

(defn- text [v n]
  (when (string? v)
    (let [s (clip v n)]
      (when-not (str/blank? s) s))))

(defn- kind-of [v]
  (let [s (some-> v str str/trim str/lower-case (str/replace #"[\s_]+" "-"))]
    (or (some #{s} kinds) "reasoning")))

(defn parse-issues
  "The problems in the critic's `answer`, [{:quote :kind :problem :fix}],
  [] when it found none, nil when the answer holds no list of them. An
  issue needs to say what is wrong."
  [answer]
  (let [m (analysis/json-object answer)
        issues (get m "issues")]
    (when (sequential? issues)
      (vec (for [i issues
                 :when (map? i)
                 :let [problem (text (get i "problem") 800)]
                 :when problem]
             {:quote (text (get i "quote") 400)
              :kind (kind-of (get i "kind"))
              :problem problem
              :fix (text (get i "fix") 500)})))))

(defn- issue-text [i {:keys [quote kind problem fix]}]
  (str (inc i) ". " (when quote (str "\"" quote "\"\n   "))
       "(" kind ") " problem
       (when fix (str "\n   Fix: " fix))))

(defn revision-request
  "What the analyst is told when the critic found `issues` in its draft:
  the problems, and to answer with the whole briefing rewritten."
  [issues]
  (str "A critic checked your briefing against the sources and against the material facts on the "
       "ground, and found these problems:\n\n"
       (str/join "\n\n" (map-indexed issue-text issues))
       "\n\nRewrite the briefing with each of them fixed. Where a claim can't happen the way it was "
       "reported, don't present it as a prospect. Either leave it out or say what it really is, an "
       "announcement, a bid for money or attention, a signal to someone, and why it can't work as "
       "stated given what the actor actually has. Where a claim comes from a party with a stake in it, "
       "say so and weigh it against that. Where a claim isn't in the sources, drop it or say plainly "
       "that it is your own reading. Where a chain of reasoning breaks, rebuild it on what the facts "
       "support, and follow through on what the fix changes elsewhere in the briefing, in the way the "
       "stories connect and in the outlook. If you're certain the critic is mistaken about a point, "
       "keep your text there and make its grounds clearer.\n\n"
       "Keep everything else as it was: the structure, the headings, the standfirst, the style rules, "
       "the citations to the numbered sources and any graph. Answer with the whole briefing and "
       "nothing else, no note on what you changed."))

(defn usable-revision?
  "Whether `revised` can stand in for the `draft`: a whole briefing rather
  than a note on the changes or a fragment of one. It keeps the draft's
  title when it had one and is at least half its length."
  [draft revised]
  (let [titled? #(boolean (re-find #"(?m)^#\s+\S" %))]
    (and (not (str/blank? revised))
         (or (not (titled? draft)) (titled? revised))
         (>= (count revised) (quot (count draft) 2)))))
