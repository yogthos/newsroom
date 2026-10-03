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

  Settings can also be exported as EDN and imported from it, which goes
  through the form too, so an import is checked as a save is."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [newsroom.config :as config]
            [newsroom.llm.providers :as providers]
            [newsroom.plugin :as plugin]
            [newsroom.sources :as sources]
            [newsroom.store :as store]))

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
              :doc "The most items taken from one source."}
             {:key :max-items :type :int :min 1 :default 180
              :doc (str "The most items in all. The most widely carried stories are kept, and among "
                        "equals sources are taken in turn.")}
             {:key :seen-days :type :int :min 0 :default 3
              :doc (str "A story told in any of this many past briefings is left out of today's, so one "
                        "dropped for space yesterday is not retold today.")}
             {:key :source-timeout-ms :type :int :min 1000 :default 30000
              :doc "How long one source may take, in milliseconds, before the run goes on without it."}]}
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
                        "The notes cost a model call a day; blank turns them off.")}]}
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
    :fields [{:key :prompt :type :text :rows 18
              :doc (str "What the model is told to write the briefing. {{date}} is the day, {{sources}} the "
                        "numbered sources and {{previous}} the last briefing's overview; without them, the "
                        "sources are appended and the overview goes just before them. Blank is the default.")}
             {:key :digest-prompt :type :text :rows 12
              :doc (str "The weekly and monthly digests' prompt. {{period}}, {{days}} and {{stories}} are "
                        "filled in. Blank is the default.")}]}])

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
    :doc "The environment variable holding the key, when not the built-in's usual one."}
   {:key :api-key :type :string :doc "The key itself, or ${VAR} to read it from the environment."}
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

(defmethod read-value :text [_ raw _ _]
  (when-not (blank? raw)
    {:value (str/replace raw "\r\n" "\n")}))

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

(defn source-fields
  "The fields a source of `type` shows: its name, then its shape's."
  [type]
  (into [source-name-field] (:fields (sources/shape type))))

(defn read-source
  "A source as the form holds it, read as well as it can be: what the page
  names it by while it's being edited."
  [raw]
  (let [type (some-> (get raw "type") str/trim (str/replace #"^:" "") not-empty keyword)]
    (assoc (read-map (source-fields type) raw "source" (atom {})) :type type)))

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
  "The plugins the page has settings for: those that declared them and
  those with settings already, by name."
  [settings]
  (sort (distinct (concat (plugin/declared-settings) (map name (keys (:plugins settings)))))))

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

(defn from-edn
  "The settings EDN `text` says, in config.edn's form, checked as the
  form's are: {:settings :errors}, :settings only the keys `text` has,
  :errors {where message}, empty when they could all be read. A whole
  config.edn will do: its static keys, which only config.edn can change,
  are passed over."
  [text]
  (let [m (try (edn/read-string (str text)) (catch Exception e {::error (ex-message e)}))]
    (cond
      (::error m) {:settings {} :errors {"EDN" (str "can't be read: " (::error m))}}
      (not (map? m)) {:settings {} :errors {"EDN" "has to be a map of settings, {:key value ...}"}}
      :else
      (let [unknown (remove (into setting-keys config/static-keys) (keys m))
            known (select-keys m setting-keys)
            {:keys [settings errors]} (from-form (form-params (to-form known)))]
        {:settings (select-keys settings (keys known))
         :errors (into errors (for [k unknown] [(pr-str k) "isn't a setting"]))}))))

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

(defn stored
  "The stored settings, those of `setting-keys`."
  [store]
  (select-keys (store/settings store) setting-keys))

(defn save!
  "Store `settings`, replacing what was stored."
  [store settings]
  (store/save-settings! store (select-keys settings setting-keys)))
