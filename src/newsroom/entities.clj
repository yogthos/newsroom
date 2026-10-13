(ns newsroom.entities
  "The knowledge graph of the news: who and what each story is about.

  When the desk sorts the day's reports into stories it names each story's
  entities, the people, organisations, countries, places and markets it is
  about. Each day's are kept as mentions, an entity in a story, with the
  story's importance and whether the briefing cited it, and two entities
  in one story are linked. From them:

  - the day's web: the entities in more than one of the day's stories,
    with the stories they tie together, for the analyst to read the day by;
  - what changed since the last briefing: the entities new to the news,
    those in more of it, those gone quiet, and the links made for the
    first time;
  - a period's entities, for a digest: the most prominent of a week or a
    month, how long they ran and how they moved against the period before."
  (:require [clojure.string :as str]))

(defn entity-key
  "What an entity is known by, however its name is written: lower case,
  accents and extra spaces dropped."
  [name]
  (-> (java.text.Normalizer/normalize (str name) java.text.Normalizer$Form/NFD)
      (->> (remove #(= Character/NON_SPACING_MARK (Character/getType %))) (apply str))
      str/lower-case
      (str/replace #"\s+" " ")
      str/trim))

(defn union
  "The entities of `a` and then those of `b` it doesn't have."
  [a b]
  (first (reduce (fn [[out seen] e]
                   (let [k (entity-key (:name e))]
                     (if (contains? seen k) [out seen] [(conj out e) (conj seen k)])))
                 [[] #{}]
                 (concat a b))))

;; --- a day -------------------------------------------------------------------------

(defn day-stories
  "The day's stories from its `ranked` reports, sorted into stories by
  their :group, every one of them, the analyst given or not: {:story :title
  :importance :entities :ns}. A story the analyst was given, in the
  `numbered` sources, is named by its storyline and cited by their
  numbers; one cut for space by the storyline it continues, else by the
  day and its group."
  [day ranked numbered]
  (let [by-url (group-by :url numbered)
        groups (group-by #(or (:group %) (:url %)) ranked)]
    (vec (for [g (distinct (map #(or (:group %) (:url %)) ranked))
               :let [members (get groups g)
                     lead (first members)
                     given (mapcat #(get by-url (:url %)) members)]]
           {:story (or (:story (first given)) (:story lead) (str day "/" g))
            :title (or (:group-title lead) (:title lead))
            :importance (:importance lead)
            :entities (vec (:entities lead))
            :ns (vec (sort (distinct (keep :n given))))}))))

(defn mentions
  "The day's `stories` as mentions, an entity in a story each: {:day
  :entity :name :kind :story :title :importance :cited}, :cited when the
  briefing cited any of the story's numbers, `cited`."
  [day stories cited]
  (let [cited (set cited)]
    (vec (for [{:keys [story title importance entities ns]} stories
               :let [told (boolean (some cited ns))]
               {:keys [name kind]} (union entities [])]
           {:day day :entity (entity-key name) :name name :kind kind :story story :title title
            :importance (or importance 5) :cited told}))))

(defn- prominence
  "The `mentions` by entity: {key {:name :kind :weight :stories :days}},
  :weight the importance of every story it's in on each day, a story
  counted once a day."
  [mentions]
  (reduce (fn [m {:keys [entity name kind story importance day]}]
            (let [seen? (contains? (get-in m [entity :seen]) [story day])]
              (-> m
                  (update entity (fn [e] (merge {:name name :kind kind :weight 0 :stories #{} :days #{} :seen #{}} e)))
                  (update-in [entity :weight] + (if seen? 0 importance))
                  (update-in [entity :seen] conj [story day])
                  (update-in [entity :stories] conj story)
                  (update-in [entity :days] conj day))))
          {}
          mentions))

(def ^:private web-size
  "The most entities the day's web shows."
  12)

(def ^:private hub-stories
  "In more of the day's stories than this, or than one in thirty of them
  on a busy day, an entity, a big country most often, is in so much of the
  news that it ties nothing in particular together: it's named with its
  count, not its stories."
  8)

(defn day-web
  "The entities that tie more than one of the day's `stories` the analyst
  can cite together: {:name :kind :weight :stories [{:title :ns}]},
  :weight from all the day's `mentions`. They come most telling first:
  the importance of the stories one ties over how many of the day's
  stories it's in, so a company that links a market's fall to a selloff
  comes before a country that's in everything. One in more than its share
  of the day (see hub-stories) is a hub, {:hub true :count}, its stories
  not listed, and doesn't count against the web-size the others fill."
  [mentions stories]
  (let [weights (prominence mentions)
        hub-over (max hub-stories (quot (count stories) 30))
        citable (filter #(seq (:ns %)) stories)
        in (fn [k] (filter (fn [s] (some #(= k (entity-key (:name %))) (:entities s))) citable))
        tied (keep (fn [[k {:keys [name kind weight] all :stories}]]
                     (let [ss (in k)]
                       (cond
                         (< hub-over (count all))
                         {:name name :kind kind :weight weight :hub true :count (count all) :stories []}
                         (< 1 (count ss))
                         {:name name :kind kind :weight weight
                          :telling (/ (reduce + (map #(or (:importance %) 5) ss)) (double (count all)))
                          :stories (mapv #(select-keys % [:title :ns]) ss)})))
                   weights)]
    (vec (concat (sort-by (juxt (comp - :count) :name) (filter :hub tied))
                 (->> (remove :hub tied)
                      (sort-by (juxt (comp - :telling) (comp - :weight) :name))
                      (take web-size)
                      (map #(dissoc % :telling)))))))

;; --- what changed ------------------------------------------------------------------

(def ^:private prominent
  "How much importance an entity's stories add up to on a day for it to be
  among the day's own: enough for one story of note."
  6)

(def ^:private changes-shown
  "The most entities each list of what changed shows."
  8)

(defn- links
  "The pairs of entities `mentions` puts in one story, each as [a b]
  sorted by name, with the story."
  [mentions]
  (for [[_ ms] (group-by :story mentions)
        :let [ms (vec (vals (into {} (map (juxt :entity identity)) ms)))]
        i (range (count ms)) j (range (inc i) (count ms))
        :let [[a b] (sort-by :name [(ms i) (ms j)])]]
    {:a a :b b}))

(defn changes
  "How the day's `today` mentions differ from the last briefing's,
  `previous`, and from every day before it in the `window`: {:new :rising
  :fading :links}. New is a prominent entity none of the window had;
  rising one whose stories weigh twice what they did; fading one prominent
  in the last briefing whose stories weigh a third of that or less now;
  links two of the day's prominent entities in one story for the first
  time in the window. Each {:name :kind :was :now}, links {:a :b :story},
  the heaviest first."
  [today previous window]
  (let [now (prominence today)
        was (prominence previous)
        before (prominence window)
        weight #(get-in %1 [%2 :weight] 0)
        entry (fn [k] {:name (or (get-in now [k :name]) (get-in was [k :name]))
                       :kind (or (get-in now [k :kind]) (get-in was [k :kind]))
                       :was (weight was k) :now (weight now k)})
        heaviest (fn [f es] (->> es (sort-by (juxt (comp - f) :name)) (take changes-shown) vec))
        linked-before (set (map (fn [{:keys [a b]}] [(:entity a) (:entity b)]) (links window)))]
    {:new (heaviest :now (for [k (keys now)
                               :when (and (>= (weight now k) prominent) (not (contains? before k)))]
                           (entry k)))
     :rising (heaviest :now (for [k (keys now)
                                  :let [w (weight was k)]
                                  :when (and (pos? w) (>= (weight now k) (max prominent (* 2 w))))]
                              (entry k)))
     :fading (heaviest :was (for [k (keys was)
                                  :when (and (>= (weight was k) prominent)
                                             (<= (* 3 (weight now k)) (weight was k)))]
                              (entry k)))
     :links (->> (links today)
                 (filter (fn [{:keys [a b]}]
                           (and (>= (weight now (:entity a)) prominent) (>= (weight now (:entity b)) prominent)
                                (not (contains? linked-before [(:entity a) (:entity b)])))))
                 (map (fn [{:keys [a b]}] {:a (:name a) :b (:name b) :story (:title a)
                                           :weight (+ (weight now (:entity a)) (weight now (:entity b)))}))
                 (reduce (fn [m l] (if (contains? m [(:a l) (:b l)]) m (assoc m [(:a l) (:b l)] l))) {})
                 vals
                 (sort-by (juxt (comp - :weight) :a :b))
                 (take changes-shown)
                 vec)}))

;; --- for the analyst ---------------------------------------------------------------

(defn- kind-text [kind] (if kind (str " (" kind ")") ""))

(defn- cites [ns] (str "[" (str/join ", " ns) "]"))

(defn- names [es] (str/join ", " (map :name es)))

(defn web-block
  "What the analyst is told of the day's `web` and of the `changes` since
  the last briefing, on `previous-day`, as markdown; nil when there is
  nothing to say."
  [web {:keys [new rising fading links]} previous-day]
  (let [hubs (filter :hub web)
        ties (remove :hub web)
        web-part (when (seq web)
                   (str/join "\n\n"
                             (remove nil?
                                     [(when (seq ties)
                                        (str "The people, organisations, places and markets in more than one of today's "
                                             "stories, with the stories they're in, to see what the day's news gathers "
                                             "around. Sharing an entity alone doesn't link two stories; what one does to "
                                             "the other does:\n\n"
                                             (str/join "\n" (for [{:keys [name kind stories]} ties]
                                                              (str "- **" name "**" (kind-text kind) ": "
                                                                   (str/join "; " (map #(str (:title %) " " (cites (:ns %)))
                                                                                       stories)))))))
                                      (when (seq hubs)
                                        (str "In much of the day's news: "
                                             (str/join ", " (map #(str (:name %) " (" (:count %) " stories)") hubs))))])))
        lines (remove nil? [(when (seq new) (str "New to the news: " (names new)))
                            (when (seq rising)
                              (str "In more of the news: "
                                   (str/join ", " (map #(str (:name %) " (" (:was %) " to " (:now %) ")") rising))))
                            (when (seq fading) (str "Gone quiet: " (names fading)))
                            (when (seq links)
                              (str "Newly linked: "
                                   (str/join "; " (map #(str (:a %) " and " (:b %) ", in " (:story %)) links))))])
        change-part (when (and previous-day (seq lines))
                      (str "### Since " previous-day "\n\n"
                           "Weighed by the importance of the stories each is in:\n\n"
                           (str/join "\n" (map #(str "- " %) lines))))]
    (when (or web-part change-part)
      (str "## Who and what ties the day together\n\n"
           (str/join "\n\n" (remove nil? [web-part change-part]))))))

;; --- a period ----------------------------------------------------------------------

(def ^:private period-size
  "The most entities a digest is told of."
  15)

(defn- days-text [n] (str n (if (= 1 n) " day" " days")))

(defn period-block
  "What a digest is told of its period's entities, from its `period`
  mentions and those of the period `before` it, as markdown: the most
  prominent, how many days each was in the news, how it moved against the
  period before (new, rising, steady or fading) and its stories' latest
  titles; and the prominent ones of the period before gone quiet. nil when
  the period has none."
  [period before]
  (let [now (prominence period)
        was (prominence before)
        latest (into {} (map (juxt :story :title)) (sort-by :day period))
        movement (fn [k w]
                   (let [b (get-in was [k :weight] 0)]
                     (cond (zero? b) "new"
                           (>= w (* 2 b)) "rising"
                           (<= (* 2 w) b) "fading"
                           :else "steady")))
        top (->> now (sort-by (fn [[k e]] [(- (:weight e)) (:name e)])) (take period-size))
        quiet (->> was
                   (filter (fn [[k e]] (and (>= (:weight e) prominent) (not (contains? now k)))))
                   (sort-by (fn [[_ e]] [(- (:weight e)) (:name e)]))
                   (take changes-shown)
                   (map (comp :name val)))]
    (when (seq top)
      (str "## Who and what the period was about\n\n"
           "Its most prominent people, organisations, places and markets, weighed by the importance of "
           "the stories each was in, with the days each was in the news and how it moved against the "
           "period before:\n\n"
           (str/join "\n" (for [[k {:keys [name kind weight stories days]}] top]
                            (str "- **" name "**" (kind-text kind) ": " (days-text (count days)) ", "
                                 (movement k weight) "; "
                                 (str/join "; " (distinct (keep latest (sort stories)))))))
           (when (seq quiet)
             (str "\n\nGone quiet since the period before: " (str/join ", " quiet)))))))
