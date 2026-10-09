(ns newsroom.ask
  "The reader's own topics: passages picked from the briefings, digests
  and storylines, put to the analyst for a full report on them, or talked
  over with it. The pure part, no IO: what a request holds, which sources
  the passages bring along, and the prompts.

  A passage cites the sources of the page it came from by that page's
  numbers, and passages from different pages would clash, so the sources
  are numbered afresh: first the ones the passages cite, then the other
  reports on the same storylines, and each passage is given its sources'
  new numbers."
  (:require [clojure.string :as str]
            [newsroom.feed :as feed]
            [newsroom.llm.client :as llm]
            [newsroom.llm.providers :as providers]
            [newsroom.markdown :as md]
            [newsroom.news :as news]
            [newsroom.pipeline :as pipeline]
            [newsroom.sources :as sources]
            [newsroom.store :as store]
            [newsroom.trends :as trends]))

;; --- the request -------------------------------------------------------------------

(def max-topics
  "The most passages one request carries."
  30)

(def ^:private max-messages 40)

(def ^:private max-chars
  "The longest a passage or a message is taken."
  6000)

(defn- clip [s n]
  (let [s (str/trim (str s))]
    (if (> (count s) n) (subs s 0 n) s)))

(defn read-request
  "The request's JSON, read with string keys, as {:mode :focus :topics
  :messages}: :mode :report or :chat, :topics [{:text :page}], :messages
  [{:role :content}] of the reader's and the model's turns. Anything else
  in it is left out. nil when it isn't a JSON object."
  [m]
  (when (map? m)
    (let [maps (fn [v] (filter map? (if (sequential? v) v [])))]
      {:mode (if (= "report" (get m "mode")) :report :chat)
       :focus (let [f (clip (get m "focus") 1000)] (when-not (str/blank? f) f))
       :topics (->> (maps (get m "topics"))
                    (keep (fn [t] (let [text (clip (get t "text") max-chars)]
                                    (when-not (str/blank? text)
                                      {:text text :page (clip (get t "page") 200)}))))
                    (take max-topics)
                    vec)
       :messages (->> (maps (get m "messages"))
                      (keep (fn [x] (let [role (get x "role") content (get x "content")]
                                      (when (and (#{"user" "assistant"} role) (string? content)
                                                 (not (str/blank? content)))
                                        {:role role :content (clip content max-chars)}))))
                      (take-last max-messages)
                      vec)})))

(defn page-ref
  "What a page's path names: {:day}, {:kind :period} for a digest, or
  {:story}. nil for any other path."
  [path]
  (let [path (str path)]
    (or (when-let [[_ day] (re-matches #"/day/(\d{4}-\d{2}-\d{2})" path)]
          (when (news/valid-day? day) {:day day}))
        (when-let [[_ kind period] (re-matches #"/(week|month)/([0-9W-]+)" path)]
          (let [kind (keyword kind)]
            (when (trends/period-range kind period) {:kind kind :period period})))
        (when-let [[_ story] (re-matches #"/story/(\d{4}-\d{2}-\d{2}/\d+)" path)]
          {:story story}))))

;; --- the sources -------------------------------------------------------------------

(def ^:private max-sources
  "The most sources a request is given."
  40)

(def ^:private related-per-passage
  "How many other reports on a passage's storylines come along with it."
  4)

(defn- renumber
  "`text` with each citation of a source of `mapping`, {old new}, given its
  new number, and the rest dropped."
  [text mapping]
  (-> (str/replace text #"\s*\[(\d+(?:\s*,\s*\d+)*)\]"
                   (fn [[whole group]]
                     (let [ns (distinct (keep #(mapping (parse-long (str/trim %))) (str/split group #",")))]
                       (if (seq ns)
                         (str (re-find #"^\s*" whole) "[" (str/join ", " ns) "]")
                         ""))))
      str/trim))

(defn gather
  "The `topics`, passages {:text :page}, with the sources they bring from
  `pages`, each {:path :label :markdown :sources}: {:topics [{:text :page
  :label}] :sources [source]}, the sources numbered afresh, each with
  :from, the label of the page it was found on, and the passages citing
  them by their new numbers."
  [pages topics]
  (let [by-path (into {} (map (juxt :path identity)) pages)
        cited (for [{:keys [text page]} topics
                    :let [p (by-path page) by-n (into {} (map (juxt :n identity)) (:sources p))]
                    n (news/citations text)
                    :let [s (by-n n)]
                    :when s]
                [page n (assoc s :from (:label p))])
        related (for [{:keys [page]} (distinct (map #(select-keys % [:page]) topics))
                      :let [p (by-path page)
                            stories (set (keep (fn [[pg _ s]] (when (= pg page) (:story s))) cited))]
                      :when (seq stories)
                      s (take related-per-passage (filter #(contains? stories (:story %)) (:sources p)))]
                  [page (:n s) (assoc s :from (:label p))])
        ;; one source an address, numbered in the order met
        [sources numbers] (reduce (fn [[out numbers] [page n s]]
                                    (let [at (some #(when (= (:url s) (:url (second %))) (first %))
                                                   (map-indexed vector out))]
                                      (cond
                                        at [out (assoc numbers [page n] (inc at))]
                                        (>= (count out) max-sources) [out numbers]
                                        :else [(conj out s) (assoc numbers [page n] (inc (count out)))])))
                                  [[] {}]
                                  (concat cited related))]
    {:topics (mapv (fn [{:keys [text page]}]
                     {:text (renumber text (fn [n] (numbers [page n])))
                      :page page
                      :label (:label (by-path page))})
                   topics)
     :sources (vec (map-indexed (fn [i s] (assoc (dissoc s :vector) :n (inc i))) sources))}))

;; --- the prompts -------------------------------------------------------------------

(def ^:private text-chars
  "How much of a source's full text the analyst is given."
  6000)

(def ^:private page-chars
  "How much of each page the passages come from is given as background."
  12000)

(defn- source-block [texts {:keys [n title source from summary url]}]
  (let [text (get texts url)]
    (str "[" n "] " title (when-not (str/blank? source) (str " (" source ")"))
         (when from (str ", found in " from))
         (cond
           (not (str/blank? text)) (str "\nFull text:\n" (clip text text-chars))
           (not (str/blank? summary)) (str "\nSummary: " summary)))))

(defn- note-block [long-date {:keys [title summary status facts]}]
  (str "### " title (when status (str " (" status ")")) "\n" summary
       (when (seq facts)
         (str "\n" (str/join "\n" (for [f (take-last 8 facts)]
                                    (str "- " (long-date (:day f)) ": " (:text f))))))))

(defn- context
  "What the analyst is given to work from: the passages, the pages they
  come from, the storylines' notes and the numbered sources."
  [{:keys [topics sources pages notes texts]} long-date]
  (str "## The passages\n\n"
       (str/join "\n\n" (map-indexed (fn [i {:keys [text label]}]
                                       (str (inc i) ". " (when label (str "From " label ": ")) text))
                                     topics))
       "\n\n"
       (when-let [bodies (seq (for [{:keys [label markdown]} pages
                                    :let [b (news/body markdown page-chars 2)]
                                    :when b]
                                (str "### " label "\n\n" b)))]
         (str "## The briefings the passages come from\n\n"
              "Background: what the reader has already read. Its citations are left out, so cite the "
              "numbered sources below instead.\n\n"
              (str/join "\n\n" bodies) "\n\n"))
       (when (seq notes)
         (str "## The storylines\n\n"
              "The running notes kept on the stories the passages touch, as background.\n\n"
              (str/join "\n\n" (map #(note-block long-date %) notes)) "\n\n"))
       "## Sources\n\n"
       (if (seq sources)
         (str/join "\n\n" (map #(source-block texts %) sources))
         "None.")))

(def ^:private grounding
  (str "Ground every factual claim in the numbered sources, citing them in square brackets, such as [3] or "
       "[2, 7], right after the claim. Don't cite a number that isn't in the list, and don't invent facts. "
       "When you go beyond the sources, with history, mechanisms you know or projections, say so plainly. "
       "Weigh claims made by interested parties as claims, and say how sure each step of a projection is."))

(def ^:private style
  (str "Write plainly and directly, as someone explaining it to a smart reader who has to act on it. Keep "
       "the sentences varied, the transitions smooth, and the reasoning concrete: name what changes, through "
       "which channel, and what follows."))

(defn report-prompt
  "The prompt that has the analyst write a full report on the passages of
  `gathered` ({:topics :sources :pages :notes :texts}), with the reader's
  `focus` when they gave one."
  [date gathered focus long-date]
  (str "You are the analyst of a daily news briefing. Today is " date ". The reader picked the passages "
       "below from the briefings as the topics they want to understand in full, and asked for a report on "
       "them. Write it in markdown.\n\n"
       (when focus (str "The reader asks you to: " focus "\n\n"))
       "Start with a `#` title and a `>` blockquote of one or two sentences with the bottom line. Then:\n\n"
       "- `## Summary`: a few short paragraphs on where things stand and why it matters, for a reader who "
       "may stop there.\n"
       "- A `##` section for each topic, or for each group of passages that are really one topic: the "
       "background a reader needs, what is happening and why, the actors with what they want and what "
       "they stand to gain or lose, where accounts disagree and which way the evidence leans, and what it "
       "drives downstream, through prices, supply, budgets, votes, capabilities or alliances.\n"
       "- `## How they connect`: the chains of cause and effect between the topics and the wider forces "
       "they feed, when there are any. Don't connect topics that only share a country or a subject.\n"
       "- `## Risks to watch`: a bullet for each, saying what could happen, how likely "
       "(unlikely, possible, likely, very likely) and when, what it would hit and which way, such as "
       "markets, sectors, prices, currencies or governments, and the signpost that would show it starting.\n"
       "- `## Outlook`: the base case for the coming weeks and over the longer run, what would confirm it "
       "and what would overturn it.\n\n"
       "Go deeper than the briefing did: the passages are where the reader wants more, so use the full "
       "texts and the storylines' history, and don't just restate the briefing. Don't write a sources list "
       "at the end, it is added for you.\n\n"
       grounding "\n\n" style "\n\n"
       (context gathered long-date)))

(defn chat-messages
  "The messages for the analyst to answer the reader's last turn in
  `messages`, about the passages of `gathered`: the context first, as the
  system's, then the conversation, the reader's and the model's turns
  only."
  [date gathered messages long-date]
  (into [{:role "system"
          :content (str "You are the analyst of a daily news briefing. Today is " date ". The reader picked "
                        "the passages below from the briefings and wants to talk them over with you. Answer "
                        "what they ask directly and concretely, in markdown, as long as the question needs "
                        "and no longer. When they ask what something means for them, say what it would hit, "
                        "which way, how likely and when, and what would show it happening.\n\n"
                        grounding "\n\n" style "\n\n"
                        (context gathered long-date))}]
        (filter #(#{"user" "assistant"} (:role %)) messages)))

(defn linked
  "The model's `answer` with its citations of `sources` linked, and for a
  report the list of the sources it cites after it."
  [answer sources report?]
  (if report?
    (news/briefing answer sources)
    (news/link-citations answer sources)))

;; --- answering ---------------------------------------------------------------------

(defn- load-page
  "The page at `path` as the passages draw on it, {:path :label :markdown
  :sources :story}, or nil when there's no such page."
  [st path]
  (when-let [{:keys [day kind period story]} (page-ref path)]
    (cond
      day (when-let [d (store/day st day)]
            {:path path :label (sources/long-date day) :markdown (:markdown d) :sources (:sources d)})
      kind (when-let [d (store/digest st kind period)]
             {:path path :label (pipeline/period-label kind period) :markdown (:markdown d) :sources (:sources d)})
      story (when-let [note (get (store/notes st [story]) story)]
              ;; a storyline's page has no numbered sources, so its facts are
              {:path path :label (:title note) :story story
               :sources (vec (map-indexed (fn [i {:keys [text url source headline]}]
                                            {:n (inc i) :title (or headline text) :url url :source source
                                             :summary text :story story})
                                          (filter :url (:facts note))))}))))

(def ^:private articles-read
  "How many of a report's sources are read in full from the outlets' pages."
  6)

(def ^:private article-timeout-ms 20000)

(defonce ^:private read-cache (atom {}))

(defn- read-article
  "The text of the article at `url`, or nil when it can't be read. The
  last ones read are kept, so a conversation doesn't read them again.
  Blocks."
  [url]
  (if (contains? @read-cache url)
    (get @read-cache url)
    (let [text (try (feed/article-text (sources/fetch-text url {:timeout-ms article-timeout-ms}))
                    (catch Throwable _ nil))]
      (swap! read-cache (fn [c] (assoc (if (> (count c) 200) {} c) url text)))
      text)))

(defn- read-texts
  "The full text of the first `n` of `sources`, {url text}, read at once,
  each given its time."
  [read sources n]
  (let [wanted (take n (filter :url sources))
        reads (mapv (fn [s] [(:url s) (future (read (:url s)))]) wanted)]
    (into {} (keep (fn [[url f]] (when-let [t (deref f (+ article-timeout-ms 5000) nil)] [url t]))) reads)))

(defn answer!
  "Answer the reader's `request` (see read-request): gather the passages'
  pages, their sources and the notes on their storylines, read the main
  sources in full for a report, and ask the analyst's model. `emit` is
  called with each event for the reader, [event data]: \"status\" with
  what is happening, \"delta\" with each piece of the answer as it
  streams, then \"done\" with {:markdown :html}, or \"error\" with what
  went wrong. A false from `emit` means the reader has gone, and the
  answer is given up.

  `ctx` is {:store :config}, and optionally :chat (llm/chat by default)
  and :read (how an article's text is read)."
  [{:keys [store config] :as ctx} {:keys [mode focus topics messages]} emit]
  (let [gone (ex-info "the reader has gone" {::gone true})
        say! (fn [event data] (when (false? (emit event data)) (throw gone)))]
    (try
      (when (empty? topics) (throw (ex-info "Add a passage to the topics first." {})))
      (when (and (= :chat mode) (not= "user" (:role (last messages))))
        (throw (ex-info "Ask a question first." {})))
      (let [pages (vec (keep #(load-page store %) (distinct (map :page topics))))
            gathered (gather pages topics)
            story-pages (keep :story pages)
            stories (distinct (concat story-pages (keep :story (:sources gathered))))
            notes (vec (take 8 (keep (store/notes store stories) stories)))
            report? (= :report mode)
            _ (when report? (say! "status" (str "Reading " (min articles-read (count (:sources gathered)))
                                                " of the sources in full")))
            texts (if report? (read-texts (or (:read ctx) read-article) (:sources gathered) articles-read) {})
            gathered (assoc gathered :pages (filterv :markdown pages) :notes notes :texts texts)
            today (sources/long-date (pipeline/today))
            llm-config (providers/role-llm config :analyst)
            request (if report?
                      [{:role "user" :content (report-prompt today gathered focus sources/long-date)}]
                      (chat-messages today gathered messages sources/long-date))
            _ (say! "status" (str (if report? "Writing the report" "Thinking") " with "
                                  (name (:alias llm-config)) " (" (:model llm-config) ")"))
            thinking (atom false)
            reply ((or (:chat ctx) llm/chat)
                   llm-config
                   {:messages request
                    :on-delta (fn [{:keys [text reasoning]}]
                                (cond
                                  (seq text) (say! "delta" text)
                                  (and (seq reasoning) (not @thinking))
                                  (do (reset! thinking true) (say! "status" "Reasoning it through"))))})
            answer (str (:content reply))
            markdown (linked answer (:sources gathered) report?)]
        (say! "done" {:markdown markdown :html (md/html markdown)}))
      (catch Throwable e
        (when-not (::gone (ex-data e))
          (emit "error" (or (ex-message e) (str e))))))))
