(ns newsroom.world
  "The world model: bayesian-news-model (bnm), a hierarchy of connected
  self-exciting processes, from pairs of a country's actors, its provinces
  and pairs of countries through the countries, the UN subregions and the
  regions to the world, each in six channels, verbal and material
  cooperation, verbal conflict, protest, coercion and violence. It learns
  online from events and forecasts by Monte Carlo.

  There is one model for every reader: they watch the same world. It starts
  from bnm's pretrained snapshot, :world-seed, which is loaded into the
  database the first time, and is kept there behind bnm's ModelStore (see
  newsroom.store); in memory, it is loaded once and saved as it learns,
  which writes only what changed.

  After a day is filed, the articles among its sources that no run has
  coded yet are coded into events by the :coder role, a batch of them a
  call (see bnm.extract), and the events are kept with the article and the
  coder that read it, and learned from. An article is coded once: a past
  day is never coded again with a newer model, which would remember what
  happened next and leak the future into any later backtest.

  Once a week it has learned, its outlook for the next quarter is
  simulated and kept. On the eve of each quarter the outlook is registered
  with the model, which scores it once the quarter is over, as the backtest
  scores a forecast: the outcomes recalibrate its probabilities and teach
  it the climatology of escalation, and the quarter joins each place's
  history. Its record of those scores goes to the analyst with its outlook,
  and its likeliest escalations are recorded as its projections, for the
  retrospective to judge like an outlet's."
  (:require [bnm.core :as bnm]
            [bnm.extract :as extract]
            [bnm.geo :as geo]
            [bnm.store :as bnm-store]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.http-client :as http]
            [newsroom.analysis :as analysis]
            [newsroom.config :as config]
            [newsroom.feed :as feed]
            [newsroom.store :as store]))

;; --- coding the news ----------------------------------------------------------------

(def batch
  "How many sources one coding call reads."
  10)

(def ^:private text-chars
  "How much of a source's text the coder reads."
  3000)

(def source-name
  "The source newsroom's events are to the model, with an exposure of its
  own, so that its volume, far smaller than the event datasets', isn't
  read as a fall in activity."
  "newsroom")

(defn coder
  "What coded the events, as they are stored with: the model and the
  version of bnm's prompt."
  [llm-config]
  (str (:model llm-config) "/" extract/prompt-version))

(defn- compact [day] (str/replace day "-" ""))

(defn articles
  "The day's `sources` as the coder reads them, {:n :url :title :text
  :published}, the text of each its article's, from `texts` {url text},
  where it was read in full, else its summary. The precedents are history
  and the analysis argues about the news rather than reporting it, so
  neither is coded."
  [sources texts day]
  (vec (for [{:keys [n title url summary published] :as s} sources
             :when (not (or (:precedent s) (analysis/analysis? s)))]
         {:n n :url url :title title
          :text (feed/clip (str/trim (str (or (get texts url) summary))) text-chars)
          :published (or published day)})))

(defn- article-number [a]
  (cond (number? a) (long a)
        (string? a) (some-> (re-find #"\d+" a) parse-long)))

(defn coded
  "The events in the coder's `answer` to the prompt for `articles`, as bnm
  takes them, each with the :url of the article it was read from. One whose
  article the answer doesn't name, or names wrongly, is credited to the
  batch's first. Undated ones take `day`."
  [answer articles day]
  (let [urls (into {} (map (juxt :n :url)) articles)]
    (mapv (fn [e]
            (-> e
                (dissoc :article)
                (assoc :url (or (urls (article-number (:article e))) (:url (first articles))))))
          (extract/events (or (analysis/json-object answer) {}) (compact day)))))

;; --- the model ----------------------------------------------------------------------

(def horizon
  "How far the outlook looks ahead, in weeks: a quarter, which is also the
  period the model scores its outlooks over."
  13)

(def all-levels
  "Every level of the hierarchy, the leaves (pairs of countries and of
  actors) among them."
  #{:leaf :local :country :subregion :region :world})

(defonce ^:private models
  (atom {}))

(def ^:private lock
  "The model's lock: its arrays change as it learns, and an outlook reads
  them for minutes."
  (Object.))

(defn- model-key [store] (:conn store))

(defn- model-of [store]
  (or (get @models (model-key store))
      (let [m (or (bnm/load (store/world-model-store store)) (bnm/create))]
        (swap! models assoc (model-key store) m)
        m)))

(defn model
  "The world model, loaded the first time."
  [store]
  (locking lock (model-of store)))

(defn- changed!
  "Keep `(f model)` as the model and save it, which writes only what
  changed; a model that couldn't be saved is dropped, to be loaded again as
  it was stored."
  [store f]
  (try
    (let [m (bnm/save! (store/world-model-store store) (f (model-of store)))]
      (swap! models assoc (model-key store) m)
      m)
    (catch Throwable e
      (swap! models dissoc (model-key store))
      (throw e))))

(defn- fetch-seed!
  "The local path of the snapshot at `seed`, a path or an http(s) URL, the
  latter downloaded once into the config directory."
  [seed report]
  (if-not (re-find #"^https?://" seed)
    seed
    (let [dest (io/file (config/home) "world" (last (str/split seed #"/")))
          part (io/file (str dest ".part"))]
      (when-not (.exists dest)
        (io/make-parents dest)
        (report "Downloading the world model's pretrained snapshot")
        (let [{:keys [status body]} (http/get seed {:as :stream :timeout 1800000})]
          (when-not (= 200 status)
            (throw (ex-info (str "could not download " seed ": HTTP " status) {:status status})))
          (with-open [in body]
            (io/copy in part))
          (.renameTo part dest)))
      (str dest))))

(defn seed!
  "Load bnm's pretrained snapshot at `seed` (a path or a URL; see
  :world-seed) into the database, when there is no model yet. The snapshot
  is a bnm file store, its parts and node rows copied as they are."
  [store seed report]
  (locking lock
    (when (and seed (store/world-model-empty? store))
      (let [from (bnm-store/file-store (fetch-seed! seed report))
            parts (into {} (keep (fn [p] (when-some [v (bnm-store/read-part from p)] [p v])))
                        [:header :volumes :pending :track])]
        (when-not (:header parts)
          (throw (ex-info (str seed " holds no model") {:seed seed})))
        (bnm-store/write! (store/world-model-store store) parts (bnm-store/read-nodes from nil) [])
        (swap! models dissoc (model-key store))
        (report "Loaded the world model's pretrained snapshot")))))

(defn learn!
  "Learn from `events`, newsroom's, and save the model. The model learns
  in weekly bins, each once a later week's events arrive, so a day's
  events wait with the model until then. Blocks; learning a week takes a
  second or so."
  [store events]
  (locking lock
    (changed! store #(bnm/add-events % source-name events))))

(defn outlook!
  "The model's outlook for the next `horizon` weeks, simulated `sims`
  times over every level, or nil before it has learned a week. On a
  quarter's eve it is registered with the model, to be scored once the
  quarter is over. Blocks, for minutes at 200 simulations."
  [store sims]
  (locking lock
    (let [m (model-of store)]
      (when (:bin m)
        (let [rows (bnm/outlook m {:horizon horizon :sims sims :levels all-levels})]
          (when (bnm/period-eve? m)
            (changed! store #(bnm/register % rows)))
          rows)))))

(defn learned-bin
  "The last week the model learned, as its bin, or nil."
  [store]
  (:bin (model store)))

(defn record
  "How the model's quarterly outlooks have scored, by node kind and
  channel (see bnm.core/record)."
  [store]
  (bnm/record (model store)))

(defn drivers
  "What drives `node`'s `channel` most, by the model: {:source :kind
  :scale :channel :share}, the share of the node's usual rate each
  accounts for. Read from the database, without the model: it doesn't
  wait for an outlook being simulated."
  [store node channel n]
  (try (bnm/drivers (store/world-model-store store) node channel :n n)
       (catch Exception _ nil)))

;; --- reading it -------------------------------------------------------------------

(def escalation-channels
  "The channels whose escalation the outlook ranks."
  [:violence :coercion :protest :verbal-conflict])

(def groups
  "How the outlook is read, by the kinds of node: between countries, within
  them, the countries themselves, and the regions."
  [{:key :between :title "Between countries" :kinds #{:dyad}}
   {:key :within :title "Within countries" :kinds #{:actor :pair :province}}
   {:key :countries :title "Countries" :kinds #{:country}}
   {:key :regions :title "Regions" :kinds #{:subregion :region :world}}])

(defn channel-name [c]
  (get {:verbal-coop "verbal cooperation" :material-coop "material cooperation"
        :verbal-conflict "verbal conflict"}
       c (name c)))

(defn label
  "A node as a reader knows it: Ukraine, Russia – Ukraine, Ukraine:
  opposition, Ukraine: Kyiv City, Eastern europe, the world."
  [id]
  (cond
    (= geo/world id) "the world"
    (geo/rest-leaf? id) (str (label (subs id 0 (- (count id) 2))) " (the rest)")
    (or (geo/dyad? id) (geo/pair? id)) (str/join " – " (map label (str/split id #"\|")))
    (or (geo/region? id) (geo/subregion? id)) (str/capitalize (str/replace (subs id 2) "-" " "))
    :else (bnm/label id)))

(defn place
  "The node of a country named `s`, an ISO code or a name, or nil."
  [s]
  (when (string? s)
    (or (geo/resolve-code (str/upper-case (str/trim s))) (geo/resolve-name s))))

(defn- kind-of [row] (or (:kind row) (geo/kind (:node row))))

(defn risks
  "The `n` likeliest escalations in `rows`, an outlook as stored, among
  the nodes of `kinds`, by calibrated probability."
  [rows kinds n]
  (->> rows
       (filter #(and (kinds (kind-of %)) (some #{(:channel %)} escalation-channels)))
       (sort-by #(- (or (:p-cal %) (:p-escalate %) 0.0)))
       (take n)
       vec))

(defn- ratio [x base] (when (and x base (pos? base)) (/ x base)))

(defn- times [x] (when x (format "%.1f×" (double x))))

(defn baseline-name
  "What an outlook row's escalation is measured from."
  [{:keys [last recent]}]
  (if (and last (not= last recent)) "last quarter" "its usual quarter"))

(defn risk-line [{:keys [node channel p-cal p-escalate expected low high recent] :as r}]
  (let [base (or (:last r) recent)]
    (str (label node) ": " (channel-name channel)
         ", P(at least 1.5× " (baseline-name r) ") = " (format "%.2f" (double (or p-cal p-escalate)))
         (when-let [e (ratio expected base)]
           (str "; expected " (times e) ", 80% range " (times (ratio low base)) "–" (times (ratio high base)))))))

(defn driver-line [{:keys [source kind scale channel share]}]
  (str (if (= :self kind) "its own" (str (label source) "'s"))
       " " (channel-name channel) " (" (name scale) ", " (Math/round (* 100.0 (double share))) "%)"))

(defn skill
  "How much better than the climatology the model's calibrated
  probabilities scored, as a share of the climatology's Brier score; nil
  without both."
  [{:keys [brier-cal brier-clim]}]
  (when (and brier-cal brier-clim (pos? brier-clim))
    (- 1.0 (/ brier-cal brier-clim))))

(defn record-lines
  "The model's record for the countries, a line a channel: how its
  calibrated probabilities scored against the climatology's."
  [record]
  (for [c escalation-channels
        :let [r (get record [:country c])
              sk (skill r)]
        :when sk]
    (str (str/capitalize (channel-name c)) ": " (:n r) " forecasts over " (:periods r) " quarters, "
         (:positives r) " escalations; " (Math/round (* 100.0 (Math/abs (double sk)))) "% "
         (if (neg? sk) "worse" "better") " than the base rate")))

(def ^:private risks-per-group
  "How many of each group's likeliest escalations the analyst is told."
  4)

(defn outlook-block
  "What the analyst is told of the world model's outlook made on `made`:
  the likeliest escalations in `rows`, by group, its `record`, and for
  `places` {[node channel] [drivers]}, what drives them. nil when there is
  nothing to tell."
  [made rows record places long-date]
  (let [by-group (for [{:keys [title kinds]} groups
                       :let [top (risks rows kinds risks-per-group)]
                       :when (seq top)]
                   [title top])
        rec (record-lines record)]
    (when (or (seq by-group) (seq places))
      (str "## The world model's outlook\n\n"
           "A statistical model of the world's events, learned from decades of coded events and from the "
           "events coded from the news since, gives its view of the coming quarter, as of " (long-date made)
           ". It counts who did what to whom, in six channels from cooperation to violence, and learns how "
           "each place's activity drives the others'; it knows nothing of the reasons. Its probabilities are "
           "calibrated by how its past quarters turned out. Weigh it against the reporting rather than "
           "repeat it: where the reporting and the model agree, say so with more confidence, and where they "
           "differ, say which you believe and why. It is not a source: don't cite it by number."
           (str/join (for [[title top] by-group]
                       (str "\n\n" title ", the likeliest escalations:\n"
                            (str/join "\n" (map #(str "- " (risk-line %)) top)))))
           (when (seq rec)
             (str "\n\nHow its outlooks for the countries have held, once their quarter was over:\n"
                  (str/join "\n" (map #(str "- " %) rec))))
           (when (seq places)
             (str "\n\nWhat drives the places in today's stories, by the share of their usual activity:\n"
                  (str/join "\n" (for [[[node channel] ds] places]
                                   (str "- " (label node) "'s " (channel-name channel) ": "
                                        (str/join "; " (map driver-line ds)))))))))))

(defn story-places
  "The places the day's stories are about, most mentioned first: the
  countries among `mentions` ({:story :name :kind}), and the pairs of them
  a story is about together."
  [mentions]
  (let [by-story (for [[_ ms] (group-by :story mentions)]
                   (vec (distinct (keep #(when (= "country" (:kind %)) (place (:name %))) ms))))
        countries (frequencies (mapcat identity by-story))
        dyads (frequencies (for [cs by-story, a cs, b cs :when (neg? (compare a b))] (geo/dyad a b)))]
    (->> (concat countries dyads)
         (sort-by (comp - val))
         (map key))))

(def ^:private subject-of-channel
  {:violence "conflict" :coercion "conflict" :protest "domestic politics" :verbal-conflict "geopolitics"})

(def outlet
  "Who the world model's projections are credited to."
  "World model")

(defn projections
  "The world model's likeliest escalations in `rows`, its outlook made on
  `made`, between and of countries, as projections credited to it: those it
  gives at least even odds, at most `n`, each due once its quarter is over."
  [rows made n plus-days]
  (vec (for [{:keys [node channel p-cal horizon] :as r} (risks rows #{:dyad :country} n)
             :when (>= (or p-cal 0.0) 0.5)]
         {:made-on made
          :claim (str (label node) "'s " (channel-name channel) " rises to at least 1.5× " (baseline-name r)
                      "'s level over the " horizon " weeks from " made
                      " (the model gives it " (format "%.2f" (double p-cal)) ")")
          :subject (subject-of-channel channel "geopolitics")
          :source outlet :outlet outlet
          :due (plus-days made (* 7 horizon))})))
