(ns newsroom.settings
  "The settings: everything about newsroom that can change while it runs,
  kept in the database and edited on the config page. config.edn keeps only
  the static keys (see newsroom.config).

  Each setting is described by a field, as a source's are (see
  newsroom.sources/shape): its :key, :type, what it does in :doc, and
  whether it may be nil, :nullable?, which turns a feature off. A setting
  left blank on the page is left out, and so takes its default.

  The page is a plain form. A field's name is the path to its value, its
  parts joined by dots, so sources.3.subreddit.0 is the first subreddit of
  the fourth source. A list's indices only order its entries, which lets
  the page add one under any fresh number. `from-form` reads the form back
  into settings, every value its field's type, and says what is wrong with
  any that isn't.

  Settings can also be exported as EDN, in config.edn's form, and imported
  from it. An import is checked against `schema`, a malli schema made from
  the same fields the page shows, then read through the form, so it is
  checked as a save is too."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [newsroom.config :as config]
            [newsroom.llm.providers :as providers]
            [newsroom.plugin :as plugin]
            [newsroom.sources :as sources]
            [newsroom.store :as store]
            [newsroom.template :as template]))

;; --- what there is to set ----------------------------------------------------------

(def sections
  "The settings, in the order the page shows them, under their headings.
  A section with a :kind instead of :fields is one of the structured ones:
  :sources, :models or :plugins."
  [{:title "Schedule"
    :fields [{:key :run-at :type :string :nullable? true :pattern #"([01]?\d|2[0-3]):[0-5]\d" :default "07:00"
              :doc (str "The local time, as HH:MM, the briefing is gathered at while the server is up. "
                        "Blank turns the schedule off, leaving the button in the sidebar.")}
             {:key :run-every-hours :type :number :min 0.01 :default 24
              :doc (str "How many hours apart the runs are after the first. A run re-gathers the day only "
                        "when its briefing is that old, so 24 gives one a day and 6 refreshes it through the day.")}]}
   {:title "Gathering"
    :fields [{:key :lookback-days :type :int :min 0 :default 1
              :doc (str "Items dated more than this many days before the briefing's day are skipped. "
                        "Undated items are always kept, and so are items dated the day after.")}
             {:key :max-items-per-source :type :int :min 1 :default 12
              :doc (str "The most items taken from one outlet in a source. A source that reads many "
                        "outlets, like a feed reader, gives each its own share.")}
             {:key :max-items :type :int :min 1 :default 180
              :doc (str "The most items in all. The most widely carried stories are kept, and among "
                        "equals outlets are taken in turn.")}
             {:key :seen-days :type :int :min 0 :default 3
              :doc (str "A story told in any of this many past briefings is left out of today's, so one "
                        "dropped for space yesterday is not retold today.")}
             {:key :source-timeout-ms :type :int :min 1000 :default 30000
              :doc (str "How long one source may take, in milliseconds, retries and all, before the run goes "
                        "on without it. A source, or its type, can set its own.")}
             {:key :source-retries :type :int :min 0 :default 1
              :doc (str "How many times a request that failed for a passing reason, like a rate limit, an "
                        "overloaded server or a dropped connection, is tried again. A source, or its type, "
                        "can set its own.")}
             {:key :source-retry-wait-ms :type :int :min 0 :default 2000
              :doc (str "How long to wait before trying again, in milliseconds, doubling each time, when the "
                        "server doesn't say how long it wants. A wait that wouldn't fit in the source's "
                        "time isn't waited out.")}]}
   {:title "Storylines"
    :fields [{:key :dupe-threshold :type :number :nullable? true :min 0 :max 1 :default 0.62
              :doc (str "Stories from different outlets whose titles and summaries embed this close (cosine "
                        "similarity) are collapsed into one source, the other outlets noted on it. "
                        "Blank turns collapsing off.")}
             {:key :story-threshold :type :number :nullable? true :min 0 :max 1 :default 0.6
              :doc (str "An item this close to the sources of a story from the last trend days continues "
                        "that story, and stories are ranked by how widely and how long they've run. "
                        "Blank stops the following.")}
             {:key :trend-days :type :int :min 1 :default 7
              :doc "How many days back a story is followed."}
             {:key :half-life-days :type :number :min 0.01 :default 2
              :doc "An earlier day's coverage counts half as much every this many days."}
             {:key :story-notes :type :int :nullable? true :min 1 :default 12
              :doc (str "The most dated facts kept in the running note on each storyline a briefing cites. "
                        "The notes cost a model call a day; blank turns them off.")}
             {:key :precedent-searches :type :int :nullable? true :min 1 :default 12
              :doc (str "Before the briefing is written, a researcher searches the web for earlier events "
                        "like the day's stories, in a few rounds, reading what each round finds before the "
                        "next, and picks the ones that show how today's may develop. They join the sources "
                        "as precedents, for the analyst to ground its projections in and cite. This is the "
                        "most searches it may run; blank turns the research off.")}]}
   {:title "Analysis"
    :fields [{:key :group-stories :type :boolean :default true
              :doc (str "Before the briefing, the model sorts the day's reports into stories, the reports on one "
                        "event from different sides together, and gives each an importance and a status. A "
                        "story's reports share its storyline and are ranked together, its coverage summed and "
                        "scaled by its importance, and reports that aren't news are left out. One model call.")}
             {:key :dossier-stories :type :int :nullable? true :min 1 :default 20
              :doc (str "How many of the day's top stories get a dossier, written from their reports alone: "
                        "the salient facts with their citations, the actors' positions and interests, where "
                        "accounts disagree, how outlets frame it, what's missing, the forces it shows and why "
                        "it matters. The briefing is written from the dossiers. A call for every four stories; "
                        "blank turns the dossiers off, and the map and the gap searches with them.")}
             {:key :read-articles :type :int :min 0 :default 3
              :doc (str "How many reports of each dossier's story are read in full from the outlet's page, "
                        "one an outlet, rather than from the feed's summary. A page behind a paywall keeps "
                        "its summary. 0 reads none.")}
             {:key :connect-stories :type :boolean :default true
              :doc (str "From the dossiers, the model maps the trends running through the day, following each "
                        "from the days before, and the links of cause and effect between the stories. The "
                        "briefing builds on the map and its graph is drawn from the links, and the trends "
                        "guide the precedent research and the digests. One model call.")}
             {:key :gap-searches :type :int :nullable? true :min 1 :default 6
              :doc (str "The dossiers note what the reports leave out, with a search that could find it. This "
                        "is how many of those searches are run; what they find joins the sources. Blank "
                        "turns them off.")}
             {:key :critique-briefing :type :boolean :default true
              :doc (str "Once the briefing is written, a critic reads it against the sources and the material "
                        "facts, and lists the claims that don't hold up: plans an actor has no means to carry "
                        "out, interested claims taken at face value, facts the sources don't carry and "
                        "reasoning that doesn't follow. When it finds any, the analyst rewrites the briefing "
                        "with them fixed. One model call, two when there is something to fix.")}]}
   {:title "Digests"
    :fields [{:key :digests :type :keywords :options [:week :month] :default [:week :month]
              :doc (str "The digests written once a week (Monday to Sunday) or a month is over, ranking "
                        "its storylines and saying which are emerging, persistent or fading.")}
             {:key :digest-stories :type :int :min 1 :default 15
              :doc "How many storylines a digest is given."}]}
   {:title "Archive"
    :fields [{:key :keep-days :type :int :min -1 :default 100
              :doc (str "How many days of briefings to keep; older ones go with their sources and markdown "
                        "files. -1 keeps them all. What the digests need is kept four months longer.")}]}
   {:title "Sources" :kind :sources}
   {:title "Models" :kind :models}
   {:title "Plugins" :kind :plugins}
   {:title "Prompts"
    :fields [{:key :prompt :type :text :rows 18 :template? true
              :doc (str "What the model is told to write the briefing, a Selmer template: {{date}} is the day, "
                        "{{sources}} the numbered sources, {{previous}} the last briefing, {{analysis}} the "
                        "desk's dossiers and map, {{graph}} true when the graph is drawn for the analyst, and "
                        "{% if previous %}...{% endif %} shows text only when there is one. Without a place for "
                        "them, the sources are appended and the last briefing and the analysis go just before them. "
                        "Blank is the default.")}
             {:key :digest-prompt :type :text :rows 12 :template? true
              :doc (str "The weekly and monthly digests' prompt, a Selmer template: {{period}}, {{days}}, "
                        "{{stories}}, {{previous}}, the last digest's overview, {{briefings}}, the week's daily "
                        "briefings or the month's weekly digests, and {{trends}}, the trends the desk "
                        "followed through the period, are filled in. "
                        "Blank is the default.")}
             {:key :precedent-prompt :type :text :rows 12 :template? true
              :doc (str "What the researcher is told when it looks for precedents, a Selmer template: {{date}} "
                        "is the day, {{stories}} the day's numbered stories, {{trends}} the trends the desk found in "
                        "them, {{searches}} and {{rounds}} what "
                        "it may spend. Its answers are read as JSON, {\"queries\": [...]} to search and "
                        "{\"precedents\": [...]} to pick, so keep the default's shapes in it. "
                        "Blank is the default.")}
             {:key :critic-prompt :type :text :rows 12 :template? true
              :doc (str "What the critic is told when it checks the briefing, a Selmer template: {{date}} is "
                        "the day, {{briefing}} the analyst's draft, {{sources}} the numbered sources and "
                        "{{analysis}} the desk's dossiers and map. Its answer is read as JSON, "
                        "{\"issues\": [{\"quote\", \"kind\", \"problem\", \"fix\"}]}, so keep the "
                        "default's shape in it. Blank is the default.")}]}])

(def source-name-field
  {:key :name :type :string
   :doc "What its items are credited to, and its name in the sidebar. Two sources need different names."})

(def provider-fields
  "What a provider entry takes."
  [{:key :type :type :keyword :options (vec (sort (keys providers/presets)))
    :doc "The built-in it works like. Needed unless the alias names a built-in."}
   {:key :model :type :string :doc "The model to ask for."}
   {:key :base-url :type :string :doc "The endpoint, when not the built-in's."}
   {:key :api-key-env :type :string
    :doc "The name the key is under, in the environment or secrets.edn, when not the built-in's usual one."}
   {:key :api-key :type :string :doc "The key itself, or ${VAR} to read it from the environment or secrets.edn."}
   {:key :max-tokens :type :int :min 1 :default 32768
    :doc "The reply's budget; a thinking model spends part of it reasoning before it writes."}
   {:key :temperature :type :number :min 0 :default 0.4 :doc "How freely the model writes."}
   {:key :timeout-ms :type :int :min 1000 :default 600000 :doc "How long a read may wait on the model."}
   {:key :thinking? :type :boolean :doc "Let a reasoning model think; off by default for local servers."}
   {:key :reasoning-effort :type :string :doc "low, high and so on, where the model has it."}])

(def role-fields
  "The jobs a provider is given."
  [{:key :analyst :type :keyword :doc "Writes the briefing and the digests."}
   {:key :notes :type :keyword
    :doc "Keeps the story notes, routine extraction a cheaper model does well; the analyst when blank."}
   {:key :research :type :keyword
    :doc "Searches for the precedents and picks them; the analyst when blank."}
   {:key :desk :type :keyword
    :doc (str "Sorts the day's reports into stories, writes the dossiers and maps the trends and links "
              "between them; the analyst when blank.")}
   {:key :critic :type :keyword
    :doc (str "Checks the written briefing for claims that don't hold up; the analyst when blank. A "
              "different model from the analyst's sees past its blind spots.")}
   {:key :default :type :keyword :doc "Any role that names no provider of its own."}])

(def scalar-keys
  "Every setting in a section of plain fields."
  (set (for [s sections, f (:fields s)] (:key f))))

(def setting-keys
  "Every key the settings have."
  (into scalar-keys [:sources :providers :roles :plugins]))

;; --- form names --------------------------------------------------------------------

(defn field-name
  "The form name of the value at `path`, its parts joined by dots."
  [& path]
  (str/join "." (map #(if (keyword? %) (name %) (str %)) (remove nil? path))))

(def extra-key
  "The part of a name holding a map's keys that no field shows, as EDN."
  "_extra")

(defn- one-value
  "A form value: the last when a name came more than once."
  [v]
  (if (vector? v) (peek v) v))

(defn form-tree
  "The form's flat {name value} as nested maps, by the dots in each name."
  [params]
  (reduce-kv (fn [m k v] (assoc-in m (str/split k #"\.") (one-value v))) {} params))

(defn indexed
  "A list's entries in the order of their indices."
  [m]
  (when (map? m)
    (->> m
         (keep (fn [[k v]] (when-let [i (parse-long k)] [i k v])))
         (sort-by first)
         (map rest))))

;; --- reading values ----------------------------------------------------------------

(defn- blank? [x] (or (nil? x) (and (string? x) (str/blank? x))))

(defn- in-range [field n]
  (let [{:keys [min max]} field]
    (cond
      (and min (< n min)) (str "has to be at least " min)
      (and max (> n max)) (str "has to be at most " max))))

(defn- regex-error [s]
  (try (re-pattern s) nil
       (catch Exception e (str "isn't a regex: " (ex-message e)))))

(declare read-map)

(defmulti ^:private read-value
  "The value of `field` from the form's `raw`, as {:value v}, nil when it's
  left blank, or {:error \"...\"} when it can't be read. `name` is its form
  name, which errors deeper in are reported under, into `errors`."
  (fn [field _raw _name _errors] (:type field)))

(defmethod read-value :default [_ raw _ _]
  (when-not (blank? raw) {:value (str/trim raw)}))

(defmethod read-value :string [field raw _ _]
  (when-not (blank? raw)
    (let [s (str/trim raw)]
      (cond
        (and (:pattern field) (not (re-matches (:pattern field) s))) {:error "isn't in the right form"}
        (and (:regex? field) (regex-error s)) {:error (regex-error s)}
        :else {:value s}))))

(defmethod read-value :text [field raw _ _]
  (when-not (blank? raw)
    (let [s (str/replace raw "\r\n" "\n")]
      (if-let [e (and (:template? field) (template/error s))]
        {:error (str "isn't a template Selmer can read: " e)}
        {:value s}))))

(defmethod read-value :int [field raw _ _]
  (when-not (blank? raw)
    (if-let [n (parse-long (str/trim raw))]
      (if-let [e (in-range field n)] {:error e} {:value n})
      {:error "has to be a whole number"})))

(defmethod read-value :number [field raw _ _]
  (when-not (blank? raw)
    (let [s (str/trim raw)]
      (if-let [n (or (parse-long s) (parse-double s))]
        (if-let [e (in-range field n)] {:error e} {:value n})
        {:error "has to be a number"}))))

(defmethod read-value :boolean [_ raw _ _]
  (case (some-> raw str/trim)
    "true" {:value true}
    "false" {:value false}
    nil))

(defmethod read-value :keyword [_ raw _ _]
  (when-not (blank? raw)
    {:value (keyword (str/replace (str/trim raw) #"^:" ""))}))

(defmethod read-value :keywords [field raw _ _]
  ;; a hidden marker comes with the checkboxes, so none ticked is []
  (when (map? raw)
    {:value (vec (filter #(= "true" (get raw (name %))) (:options field)))}))

(defmethod read-value :strings [_ raw _ _]
  (let [vs (vec (for [[_ v] (indexed raw) :when (not (blank? v))] (str/trim v)))]
    (when (seq vs) {:value vs})))

(defmethod read-value :records [field raw name errors]
  (let [first-key (:key (first (:fields field)))
        rs (vec (for [[i r] (indexed raw)
                      ;; an entry added and left blank is no entry
                      :when (and (map? r) (not-every? blank? (vals r)))
                      :let [m (read-map (:fields field) r (field-name name i) errors)]
                      :when (seq m)]
                  (if (and (:shorthand? field) (= [first-key] (keys m))) (get m first-key) m)))]
    (when (seq rs) {:value rs})))

(defn- read-extra
  "The keys of a map no field shows, from the EDN under `extra-key`."
  [raw name errors]
  (let [s (get raw extra-key)]
    (when-not (blank? s)
      (let [v (try (edn/read-string s) (catch Exception e {::error (ex-message e)}))]
        (cond
          (::error v) (do (swap! errors assoc (field-name name extra-key) (str "isn't EDN: " (::error v))) nil)
          (map? v) v
          :else (do (swap! errors assoc (field-name name extra-key) "has to be a map, {:key value ...}") nil))))))

(defn- read-map
  "The map `fields` read from `raw`, with the keys no field shows from
  its extra EDN; a required field left blank is an error."
  [fields raw name errors]
  (let [raw (if (map? raw) raw {})]
    (reduce (fn [m {:keys [key required?] :as field}]
              (let [n (field-name name key)
                    {:keys [value error] :as r} (read-value field (get raw (clojure.core/name key)) n errors)]
                (cond
                  error (do (swap! errors assoc n error) m)
                  r (assoc m key value)
                  required? (do (swap! errors assoc n "is needed") m)
                  :else (dissoc m key))))
            (or (read-extra raw name errors) {})
            fields)))

;; --- the structured sections -------------------------------------------------------

(defn source-types
  "Every source type there is an adapter for, built-in or from a plugin."
  []
  (sort (remove #{:default} (keys (methods sources/fetch-items)))))

(defn policy-fields
  "How a source of `type` is read, the same for every type, its type's
  :policy shown as the default."
  [type]
  (let [typed (:policy (sources/shape type))
        default (fn [k] (when-let [v (get typed k)] (str v " for this type")))]
    [{:key :timeout-ms :type :int :min 1000 :default (default :timeout-ms)
      :doc "How long this source may take, retries and all, in milliseconds. Blank: its type's, else the source timeout."}
     {:key :retries :type :int :min 0 :default (default :retries)
      :doc "How many times a request failing for a passing reason is tried again. Blank: its type's, else the setting."}
     {:key :retry-wait-ms :type :int :min 0 :default (default :retry-wait-ms)
      :doc "The wait before trying again when the server doesn't say. Blank: its type's, else the setting."}]))

(defn source-fields
  "The fields a source of `type` shows: its name, its shape's, then how
  it is read."
  [type]
  (-> [source-name-field]
      (into (:fields (sources/shape type)))
      (into (policy-fields type))))

(defn read-source
  "A source as the form holds it, read as well as it can be: what the page
  names it by while it's being edited."
  [raw]
  (let [type (some-> (get raw "type") str/trim (str/replace #"^:" "") not-empty keyword)]
    (assoc (read-map (source-fields type) raw "source" (atom {})) :type type)))

(defn source-at
  "The source the form `params` holds at `prefix`, sources.3 say, read and
  checked as a save reads it: {:source :errors}, :errors by form name."
  [params prefix]
  (let [raw (get-in (form-tree params) (str/split prefix #"\."))
        type (some-> (get raw "type") str/trim (str/replace #"^:" "") not-empty keyword)
        errors (atom {})
        source (when type (assoc (read-map (source-fields type) raw prefix errors) :type type))]
    {:source source
     :errors (if type @errors {(field-name prefix "type") "is needed"})}))

(defn- read-sources [raw errors]
  (let [read (vec (for [[i r] (indexed raw)
                        :let [type (some-> (get r "type") str/trim (str/replace #"^:" "") not-empty keyword)]
                        :when type]
                    [i (assoc (read-map (source-fields type) r (field-name :sources i) errors) :type type)]))]
    ;; the run keeps each source's status by name, so a name is one source's
    (doseq [[i s] (second (reduce (fn [[seen dupes] [i s]]
                                  (let [n (sources/source-name s)]
                                    [(conj seen n) (if (seen n) (conj dupes [i s]) dupes)]))
                                [#{} []]
                                read))]
      (swap! errors assoc (field-name :sources i :name)
             (str "is " (sources/source-name s) ", which another source is called too")))
    (mapv second read)))

(defn- read-providers
  "The providers, by alias; `index` gets the form index of each alias."
  [raw errors index]
  (reduce (fn [m [i r]]
            (let [n (field-name :providers i)
                  alias (some-> (get r "_alias") str/trim (str/replace #"^:" "") not-empty keyword)
                  entry (read-map provider-fields r n errors)]
              (cond
                (and (nil? alias) (empty? entry)) m
                (nil? alias) (do (swap! errors assoc (field-name n "_alias") "is needed") m)
                (contains? m alias) (do (swap! errors assoc (field-name n "_alias") "is given twice") m)
                :else (do (swap! index assoc alias i)
                          (assoc m alias entry)))))
          {}
          (indexed raw)))

(defn plugin-names
  "The plugins the page shows, by name: those found at startup, those that
  declared settings and those with settings already."
  [settings]
  (sort (distinct (concat (map :plugin (:plugins (plugin/loaded)))
                          (plugin/declared-settings)
                          (map name (keys (:plugins settings)))))))

(defn plugin-fields [plugin] (:fields (plugin/settings-shape plugin)))

(defn- read-plugins [raw errors]
  (into {}
        (for [[plugin r] raw
              :let [m (read-map (plugin-fields plugin) r (field-name :plugins plugin) errors)]
              :when (seq m)]
          [(keyword plugin) m])))

(defn- check-models
  "Errors for providers that can't resolve and roles naming no provider;
  `index` is each provider's form index."
  [settings errors index]
  (let [cfg (select-keys settings [:providers :roles])
        names (set (providers/provider-names cfg))]
    (doseq [[alias _] (:providers settings)]
      (try (providers/resolve-provider cfg alias)
           (catch Exception e
             ;; an unset ${VAR} is the environment's business, not the form's
             (when-not (:var (ex-data e))
               (swap! errors assoc (field-name :providers (index alias) :type) (ex-message e))))))
    (doseq [[role alias] (:roles settings)
            :when (not (names alias))]
      (swap! errors assoc (field-name :roles role)
             (str "names " (name alias) ", which no provider is")))))

(defn from-form
  "The settings the config page's form `params`, {name value}, says, and
  what is wrong with it: {:settings :errors}, :errors {name message} for
  the fields that couldn't be read, empty when they all could. A list left
  empty is kept empty rather than taking its default."
  [params]
  (let [tree (form-tree params)
        errors (atom {})
        index (atom {})
        scalars (read-map (for [s sections, f (:fields s)] f) (select-keys tree (map name scalar-keys)) nil errors)
        ;; blank turns a nullable setting off, rather than leaving it to its default
        nulls (into {} (for [s sections, f (:fields s)
                             :when (and (:nullable? f) (not (contains? scalars (:key f))))]
                         [(:key f) nil]))
        settings (merge nulls scalars
                        {:sources (read-sources (get tree "sources") errors)
                         :providers (read-providers (get tree "providers") errors index)
                         :roles (read-map role-fields (get tree "roles") "roles" errors)
                         :plugins (read-plugins (get tree "plugins") errors)})]
    (check-models settings errors @index)
    {:settings settings :errors @errors}))

;; --- writing values back -----------------------------------------------------------
;; The page is drawn from the form's own shape, the nested maps `from-form`
;; reads, so that a form sent back with errors in it is drawn again as it was
;; sent. `to-form` puts settings in that shape.

(declare map-form)

(defn- value-form
  "A value of `field` as the form holds it."
  [field v]
  (case (:type field)
    (:strings) (into {} (map-indexed (fn [i x] [(str i) (str x)])) (if (coll? v) v (when v [v])))
    (:records) (into {} (map-indexed (fn [i x]
                                       [(str i) (map-form (:fields field)
                                                          (if (map? x) x {(:key (first (:fields field))) x}))]))
                     v)
    (:keywords) (into {"__" "1"} (for [k v] [(name k) "true"]))
    (:keyword) (if (keyword? v) (name v) (str v))
    (:boolean) (case v true "true" false "false" "")
    (if (nil? v) "" (str v))))

(defn- map-form
  "A map of `fields` as the form holds it, the keys no field shows as EDN."
  [fields m]
  (let [extra (apply dissoc m :type (map :key fields))]
    (cond-> (into {} (for [{:keys [key] :as f} fields
                           :when (contains? m key)]
                       [(name key) (value-form f (get m key))]))
      (seq extra) (assoc extra-key (pr-str extra)))))

(defn to-form
  "`settings` as the form holds them, the shape `from-form` reads."
  [settings]
  (merge (map-form (for [s sections, f (:fields s)] f) (select-keys settings scalar-keys))
         {"sources" (into {} (map-indexed (fn [i {:keys [type] :as s}]
                                            [(str i) (assoc (map-form (source-fields type) s)
                                                            "type" (some-> type name))]))
                          (:sources settings))
          "providers" (into {} (map-indexed (fn [i [alias entry]]
                                              [(str i) (assoc (map-form provider-fields entry)
                                                              "_alias" (name alias))]))
                            (:providers settings))
          "roles" (map-form role-fields (:roles settings))
          "plugins" (into {} (for [p (plugin-names settings)]
                               [p (map-form (plugin-fields p) (get-in settings [:plugins (keyword p)]))]))}))

;; --- EDN ---------------------------------------------------------------------------

(defn form-params
  "A form tree as the flat {name value} a browser sends."
  ([tree] (form-params tree nil))
  ([tree prefix]
   (into {} (mapcat (fn [[k v]]
                      (let [n (if prefix (str prefix "." k) k)]
                        (if (map? v) (form-params v n) [[n v]])))
                    tree))))

(defn export-edn
  "`settings` as EDN in config.edn's own form, every key sorted, to be
  imported again with `from-edn`."
  [settings]
  (str ";; newsroom's settings, as config.edn writes them. Import this on the\n"
       ";; config page, or put it in config.edn, where it's moved into the\n"
       ";; database at startup.\n"
       (with-out-str
         (pp/pprint (into (sorted-map) (select-keys settings setting-keys))))))

;; --- the schema --------------------------------------------------------------------

(defn- message [text pred] [:fn {:error/message text} pred])

(defn- valid-regex? [s] (nil? (regex-error s)))

(declare map-schema)

(defn- field-schema
  "The malli schema of a field's value, as config.edn writes it."
  [{:keys [type options fields min max pattern regex? template? nullable? shorthand?]}]
  (let [bounded (fn [pred] (cond-> [:and pred]
                             min (conj [:>= min])
                             max (conj [:<= max])))
        base (case type
               :string (cond
                         pattern [:and string? (message "isn't in the right form" #(boolean (re-matches pattern %)))]
                         regex? [:and string? (message "should be a regex" valid-regex?)]
                         :else string?)
               :text (if template?
                       [:and string? (message "should be a Selmer template" #(nil? (template/error %)))]
                       string?)
               :int (bounded int?)
               :number (bounded number?)
               :boolean boolean?
               :keyword (if (seq options) (into [:enum] options) keyword?)
               :keywords [:sequential (into [:enum] options)]
               :strings [:or string? [:sequential string?]]
               :records [:sequential (let [record (map-schema fields)]
                                       (if shorthand?
                                         [:or (field-schema (first fields)) record]
                                         record))]
               any?)]
    (if nullable? [:maybe base] base)))

(defn- map-schema
  "An open map of `fields`: keys no field shows are let through, as on the
  page."
  [fields]
  (into [:map] (for [{:keys [key required?] :as f} fields]
                 [key {:optional (not required?)} (field-schema f)])))

(defn schema
  "The malli schema of the settings as config.edn writes them, made from the
  fields the page shows, so each source type is checked by its shape and
  each plugin's settings by what it declared. config.edn's static keys are
  let through, since a whole config.edn can be imported."
  []
  (let [scalars (for [s sections, f (:fields s)] f)
        source (into [:multi {:dispatch :type}]
                     (concat (for [t (source-types)]
                               [t (map-schema (into [{:key :type :type :keyword :required? true}]
                                                    (source-fields t)))])
                             [[::m/default [:map [:type keyword?]]]]))
        plugins (into [:map] (for [p (plugin/declared-settings)]
                               [(keyword p) {:optional true} (map-schema (plugin-fields p))]))]
    (-> [:map {:closed true}]
        (into (for [f scalars] [(:key f) {:optional true} (field-schema f)]))
        (into [[:sources {:optional true} [:sequential source]]
               [:providers {:optional true} [:map-of keyword? (map-schema provider-fields)]]
               [:roles {:optional true} (map-schema role-fields)]
               [:plugins {:optional true} [:and [:map-of keyword? map?] plugins]]])
        (into (for [k (sort config/static-keys)] [k {:optional true} any?])))))

(defn schema-errors
  "What is wrong with `m` by `schema`, {where message}, where being the path
  to the value as config.edn writes it, like :sources 3 :url. Empty when
  nothing is."
  [m]
  (if-let [explained (m/explain (schema) m)]
    (reduce (fn [errors {:keys [in] :as e}]
              (let [where (if (seq in) (str/join " " (map pr-str in)) "EDN")
                    text (me/error-message e)]
                ;; the first check a value fails says enough
                (update errors where #(or % text))))
            {}
            (:errors explained))
    {}))

(defn from-edn
  "The settings EDN `text` says, in config.edn's form: {:settings :errors},
  :settings only the keys `text` has, :errors {where message}, empty when
  they could all be read. It is checked against `schema` first, then read
  through the form, which finds what a schema doesn't, like two sources of
  one name. A whole config.edn will do: its static keys, which only
  config.edn can change, are passed over."
  [text]
  (let [m (try (edn/read-string (str text)) (catch Exception e {::error (ex-message e)}))]
    (cond
      (::error m) {:settings {} :errors {"EDN" (str "can't be read: " (::error m))}}
      (not (map? m)) {:settings {} :errors {"EDN" "has to be a map of settings, {:key value ...}"}}
      :else
      (let [errors (schema-errors m)]
        (if (seq errors)
          {:settings {} :errors errors}
          (let [known (select-keys m setting-keys)
                {:keys [settings errors]} (from-form (form-params (to-form known)))]
            {:settings (select-keys settings (keys known))
             :errors errors}))))))

;; --- the store ---------------------------------------------------------------------

(defn- move-aside!
  "Keep `f` as f.bak, replacing an older one."
  [f]
  (let [bak (io/file (str (.getPath f) ".bak"))]
    (when (.exists bak) (io/delete-file bak))
    (.renameTo f bak)))

(defn- static-text [static]
  (str ";; newsroom's static configuration: where the page is served and the\n"
       ";; database kept. Everything else is edited on the config page, /config.\n"
       "{"
       (str/join "\n " (for [[k v] (sort-by key static)] (str (pr-str k) " " (pr-str v))))
       "}\n"))

(defn import!
  "Bring what the files still say into the stored settings: config.edn's
  keys beyond the static ones, and prompt.md and digest.md when they are
  there and differ from the defaults. config.edn is rewritten with the
  static keys only and the prompts are moved aside, each kept as .bak.
  Returns the keys imported."
  [store file]
  (let [moved (apply dissoc file config/static-keys)
        prompts (for [[k f default] [[:prompt "prompt.md" "prompt.md"] [:digest-prompt "digest.md" "digest.md"]]
                      :let [f (io/file (config/path f))]
                      :when (.exists f)]
                  [k f (when-not (= (slurp f) (config/default-text default)) (slurp f))])
        imported (merge moved (into {} (for [[k _ text] prompts :when text] [k text])))]
    (when (seq imported)
      (store/save-settings! store (merge (store/settings store) imported)))
    (when (seq moved)
      (let [f (io/file (config/path "config.edn"))]
        (when (.exists f)
          (move-aside! f)
          (spit f (static-text (select-keys file config/static-keys))))))
    (doseq [[_ f] prompts] (move-aside! f))
    (vec (keys imported))))

(defn seed-prompts!
  "Store the default of each prompt the database has none of, so a new
  database, or one from before a prompt was added, holds every prompt to
  edit. One the user has saved is left alone. Returns the keys stored."
  [store]
  (let [stored (store/settings store)
        missing (into {} (remove (fn [[k _]] (contains? stored k))) (config/default-prompts))]
    (when (seq missing)
      (store/save-settings! store (merge stored missing)))
    (vec (keys missing))))

(defn stored
  "The stored settings, those of `setting-keys`."
  [store]
  (select-keys (store/settings store) setting-keys))

(defn save!
  "Store `settings`, replacing what was stored."
  [store settings]
  (store/save-settings! store (select-keys settings setting-keys)))
