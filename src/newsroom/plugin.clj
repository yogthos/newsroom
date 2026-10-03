(ns newsroom.plugin
  "Plugins, and what one is written against.

  A plugin lives in plugins/ in the config directory (see newsroom.config),
  as a folder of namespaces named after it:

    plugins/slack/core.clj     (ns slack.core ...)
    plugins/slack/api.clj      (ns slack.api ...)

  plugins/ is put on the load path and every namespace in the folder is
  required at startup, so the files can require each other the usual way. A
  lone plugins/NAME.clj is a plugin of one file.

  Its settings are the entry named after the folder under :plugins in the
  settings, edited on the config page, and a string there written as
  \"${VAR}\" is read from the environment:

    :plugins {:slack {:token \"${SLACK_BOT_TOKEN}\" :workspace \"acme\"}}

  `defsettings` declares what those settings are, so the config page can
  show a field for each.

  A plugin adds source types with `defsource`, and can name their sources
  with `defname`. The whole source map from :sources is handed over too, so
  settings that differ per source, like which channel to read, go there.
  The map after the type is the source's shape (see newsroom.sources/shape),
  which the config page builds a source's fields from:

    (ns slack.core
      (:require [newsroom.plugin :as plugin]))

    (plugin/defsettings
      {:fields [{:key :token :type :string :required? true
                 :doc \"A bot token, or ${SLACK_BOT_TOKEN}.\"}]})

    (plugin/defsource :slack
      {:doc \"A Slack channel's recent messages.\"
       :fields [{:key :channel :type :string :required? true
                 :doc \"The channel's ID.\"}]}
      [source ctx]
      (let [{:keys [token]} (plugin/config :slack)]
        (plugin/emit! ctx (str \"Reading \" (:channel source)))
        (for [m (plugin/get-json \"https://slack.com/api/...\" {...})]
          (plugin/item source {:title ... :url ...}))))

  jolt.http-client, clojure.data.json and the rest of what newsroom is built
  from can be required as they are; `get-json` and `post-json` cover the
  common case of a JSON API."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [jolt.http-client :as http]
            [newsroom.feed :as feed]
            [newsroom.sources :as sources]))

;; --- the API -----------------------------------------------------------------------

(def ^:dynamic *plugin*
  "The plugin being loaded, {:name :dir}, while its namespaces load."
  nil)

(defonce ^:private app-config (atom {}))

(defn set-config!
  "Make `cfg` the config the plugins' settings come from, as when the
  config page saves."
  [cfg]
  (reset! app-config cfg))

(defonce ^:private settings-shapes (atom {}))

(defn settings-shape
  "What a plugin's settings look like, {:doc :fields}, as it declared them
  with `defsettings`; nil when it didn't."
  [plugin]
  (get @settings-shapes (name plugin)))

(defn declared-settings
  "The names of the plugins that declared their settings."
  []
  (keys @settings-shapes))

(defn declare-settings!
  "Say what the settings of `plugin` look like; see `defsettings`."
  [plugin shape]
  (swap! settings-shapes assoc (name plugin) shape)
  nil)

(defmacro defsettings
  "Declare the plugin's settings, {:doc :fields}, its fields as a source
  shape's are (see newsroom.sources/shape), so the config page can show
  them. With no name, the plugin being loaded's."
  ([shape]
   `(declare-settings! (or (:name *plugin*)
                           (throw (ex-info "defsettings names no plugin outside of loading one" {})))
                       ~shape))
  ([plugin shape]
   `(declare-settings! ~plugin ~shape)))

(defn- expand-vars
  "`v` with every string that is exactly ${VAR} read from the environment. An
  unset variable is an error naming it and `plugin`."
  [plugin v]
  (walk/postwalk
   (fn [x]
     (if-let [[_ var] (and (string? x) (re-matches #"\$\{([A-Za-z_][A-Za-z0-9_]*)\}" x))]
       (let [value (jolt.host/getenv var)]
         (if (str/blank? value)
           (throw (ex-info (str "plugin " plugin " names ${" var "}, which is not set")
                           {:plugin plugin :var var}))
           value))
       x))
   v))

(defn config
  "A plugin's entry under :plugins in config.edn, nil when it has none. With
  no argument, that of the plugin being loaded."
  ([]
   (if *plugin*
     (config (:name *plugin*))
     (throw (ex-info "(config) names no plugin outside of loading one" {}))))
  ([plugin]
   (let [plugins (:plugins @app-config)
         entry (or (get plugins (keyword (name plugin))) (get plugins (name plugin)))]
     (expand-vars (name plugin) entry))))

(defmacro defsource
  "Add a source type: `body` returns the items a source of `type` has, for
  the context {:day \"YYYY-MM-DD\" :config ...}. An optional map before the
  arguments is the type's shape, {:doc :fields}, which the config page
  shows a source's fields from; see newsroom.sources/shape."
  [type & more]
  (let [[shape [source ctx] & body] (if (vector? (first more)) (cons nil more) more)]
    `(do
       ~@(when shape [`(defmethod sources/shape ~type [~'_] ~shape)])
       (defmethod sources/fetch-items ~type [~source ~ctx] ~@body))))

(defmacro defname
  "Say what a source of `type` is called when it has no :name, so that two
  sources of the type don't share a name. Without it, a source goes by its
  :url, else its type."
  [type [source] & body]
  `(defmethod sources/default-name ~type [~source] ~@body))

(def emit!
  "(emit! ctx text) or (emit! ctx text {:url ... :level :error}): show what the
  source is doing on the page while a run goes."
  sources/emit!)

(def fetch-text
  "The body of a URL as text, (fetch-text url {:timeout-ms :user-agent});
  throws on a status other than 2xx."
  sources/fetch-text)

(def parse-xml
  "An XML document's text, an RSS or Atom feed say, as a clojure.xml tree."
  sources/parse-xml)

(def page-meta
  "What an article page's HTML says about itself in its meta tags:
  {:title :description :published}, each nil when the page doesn't say."
  feed/page-meta)

(def source-name
  "The name a source's items are credited to."
  sources/source-name)

(defn item
  "An item from `m`, {:title :url :summary :published}: markup dropped from
  the title and summary, the summary clipped, and credited to `source`
  unless `m` has a :source of its own. :published is a date string in RSS
  or ISO 8601 form, or nil when unknown."
  [source m]
  {:title (feed/plain-text (:title m))
   :url (str (:url m))
   :source (or (:source m) (source-name source))
   :summary (feed/clip (feed/plain-text (:summary m)) feed/summary-chars)
   :published (:published m)})

(defn request-json
  "Send `req` (as jolt.http-client takes it: :url, :request-method,
  :headers, :query-params, :body) and read the JSON answer, keys as
  keywords, tried again under the source's policy when it fails for a
  passing reason (see newsroom.sources/retrying). Throws on a status other
  than 2xx, with :status, :body and :headers in the ex-data."
  [req]
  (sources/retrying
   (fn [timeout]
     (let [resp (http/request (-> req
                                  (dissoc :timeout-ms)
                                  (update :headers #(merge {"User-Agent" sources/default-user-agent
                                                            "Accept" "application/json"}
                                                           %))
                                  (assoc :socket-timeout timeout
                                         :conn-timeout (min timeout 15000)
                                         :throw-exceptions false)))]
       (if (<= 200 (:status resp) 299)
         (json/read-str (str (:body resp)) :key-fn keyword)
         (throw (ex-info (str "HTTP " (:status resp) " from " (:url req))
                         {:status (:status resp) :body (:body resp) :headers (:headers resp)})))))
   (:timeout-ms req)))

(defn get-json
  "GET `url` and read the JSON answer; `opts` takes :headers,
  :query-params and :timeout-ms."
  ([url] (get-json url {}))
  ([url opts] (request-json (assoc opts :url url :request-method :get))))

(defn post-json
  "POST `body` to `url` as JSON and read the JSON answer; `opts` as for
  get-json."
  ([url body] (post-json url body {}))
  ([url body opts]
   (request-json (-> opts
                     (assoc :url url :request-method :post :body (json/write-str body))
                     (update :headers merge {"Content-Type" "application/json"})))))

;; --- loading -----------------------------------------------------------------------

(defn- clj-file? [f]
  (and (.isFile f) (some #(str/ends-with? (.getName f) %) [".clj" ".cljc"])))

(defn- ns-of
  "The namespace a file under `root` holds, by the usual path convention."
  [root f]
  (let [rel (subs (.getPath f) (inc (count (.getPath (io/file root)))))]
    (-> rel
        (str/replace #"\.cljc?$" "")
        (str/replace #"[/\\]" ".")
        (str/replace "_" "-")
        symbol)))

(defn- namespaces [root dir]
  (->> (file-seq dir)
       (filter clj-file?)
       (map #(ns-of root %))
       sort
       vec))

(defn- load-one [root f]
  (let [name (str/replace (.getName f) #"\.cljc?$" "")]
    (try
      (binding [*plugin* {:name name :dir (.getPath f)}]
        (if (.isDirectory f)
          (let [nss (namespaces root f)]
            (doseq [n nss] (require n))
            {:plugin name :ok true :namespaces nss})
          ;; a lone file keeps being loaded as a file, whatever its ns
          (do (load-file (.getPath f))
              {:plugin name :ok true})))
      (catch Throwable e
        (binding [*out* *err*]
          (println "plugin" name "failed to load:" (ex-message e)))
        {:plugin name :ok false :error (or (ex-message e) (str e))}))))

(defn load-all!
  "Load every plugin in the directory `root`, in name order, with `cfg` the
  config their settings come from. A plugin that fails to load is reported
  and skipped rather than keeping the server down. Returns a report per
  plugin: {:plugin :ok :namespaces :error}."
  [root cfg]
  (set-config! cfg)
  (let [dir (io/file root)
        entries (when (.isDirectory dir)
                  (->> (.listFiles dir)
                       (filter #(or (.isDirectory %) (clj-file? %)))
                       (remove #(str/starts-with? (.getName %) "."))
                       (sort-by #(.getName %))))]
    (when (seq entries)
      (let [path (.getPath dir)]
        (jolt.host/set-source-roots!
         (vec (distinct (cons path (jolt.host/source-roots)))))))
    (mapv #(load-one (.getPath dir) %) entries)))
