(ns newsroom.config
  "Where newsroom keeps its settings: ~/.config/newsroom, or $NEWSROOM_HOME.

    config.edn        where the page is served and the database kept,
                      :host :port :db; read once, at startup
    plugins/          loaded at startup; a plugin adds source types, see
                      newsroom.plugin
    newsroom.sqlite3  the gathered items and the briefings, by day, and the
                      settings: sources, schedule, providers, plugin
                      settings and the prompts, edited on the config page
                      (see newsroom.settings)

  The first run writes config.edn, so there is always a file to edit. The
  config newsroom runs with is the settings over their defaults, with
  config.edn's keys over both."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- env [k]
  (let [v (jolt.host/getenv k)]
    (when-not (str/blank? v) v)))

(defn home
  "$NEWSROOM_HOME, else .config/newsroom in the user's home directory, which
  Windows names in USERPROFILE rather than HOME."
  []
  (or (env "NEWSROOM_HOME")
      (str (or (env "HOME") (env "USERPROFILE")) "/.config/newsroom")))

(defn path [& parts] (str/join "/" (cons (home) parts)))

(def static-keys
  "What config.edn says: what has to be known before the database is open,
  or can't change while the server runs."
  #{:host :port :db})

;; --- secrets -------------------------------------------------------------------------
;; secrets.edn holds credentials as {"NAME" "value"}, so a key need not be
;; exported in the shell that starts newsroom. Settings name a key as before,
;; ${NAME} or :api-key-env, and `secret` finds it in the environment, else
;; here. The file is read once, at startup, and only by its owner.

(defonce ^:private secrets (atom {}))

(defn- open-to-others
  "The permissions on `f` that let a user other than its owner at it."
  [f]
  (->> (java.nio.file.Files/getPosixFilePermissions
        (java.nio.file.Paths/get (str f) (into-array String []))
        (into-array java.nio.file.LinkOption []))
       (map str)
       (remove #(str/starts-with? % "OWNER_"))))

(defn read-secrets
  "secrets.edn at `f` as a map of name to value, {} when there is no file.
  Throws when a user other than the owner can get at it, or when it is not
  a map of strings to strings. The error names the file and never holds a
  value from it."
  [f]
  (let [f (io/file f)
        refuse (fn [why] (throw (ex-info (str f " " why) {:file (str f)})))]
    (cond
      (not (.exists f)) {}
      (seq (open-to-others f)) (refuse "is open to other users; run chmod 600 on it")
      :else
      (let [m (try (edn/read-string (slurp f))
                   (catch Throwable _ (refuse "is not valid EDN")))]
        (if (and (map? m) (every? (fn [[k v]] (and (string? k) (string? v))) m))
          m
          (refuse "has to be one map of string names to string values"))))))

(defn load-secrets!
  "Read secrets.edn in the config directory, or `f`, for `secret` to serve.
  nil forgets them."
  ([] (load-secrets! (path "secrets.edn")))
  ([f] (reset! secrets (if f (read-secrets f) {}))))

(defn secret
  "The value of `name`: a non-blank environment variable, else the entry in
  secrets.edn. nil when neither has one."
  [name]
  (when name
    (or (env name)
        (let [v (get @secrets name)]
          (when-not (str/blank? v) v)))))

(defn default-text [name]
  (slurp (io/resource (str "defaults/" name))))

(def prompts
  "The prompts, by setting, with the file in resources/defaults each one's
  default is."
  {:prompt "prompt.md"
   :digest-prompt "digest.md"
   :precedent-prompt "precedents.md"})

(defn default-prompts
  "Each prompt's default text, by setting."
  []
  (into {} (for [[k f] prompts] [k (default-text f)])))

(defn default-settings
  "The settings before any are saved: resources/defaults/settings.edn, with
  the default prompts."
  []
  (merge (edn/read-string (default-text "settings.edn")) (default-prompts)))

(defn defaults
  "The whole default config, the static part and the settings."
  []
  (merge (default-settings) (edn/read-string (default-text "config.edn"))))

(defn ensure-home!
  "Create the config directory with the default config.edn, leaving one
  that is already there alone."
  []
  (.mkdirs (io/file (path "plugins")))
  (let [f (io/file (path "config.edn"))]
    (when-not (.exists f)
      (spit f (default-text "config.edn")))))

(defn read-file
  "config.edn as it is, every key it has; {} when there is none."
  []
  (let [f (io/file (path "config.edn"))]
    (or (when (.exists f) (edn/read-string (slurp f))) {})))

(defn effective
  "The config newsroom runs with: `settings` over the default settings, and
  the static keys of `file` over both."
  [file settings]
  (merge (defaults) settings (select-keys file static-keys)))

(defn- template [config k]
  (let [p (get config k)]
    (if (str/blank? p) (default-text (prompts k)) p)))

(defn prompt-template
  "The analysis prompt: the config's :prompt, the default when blank."
  [config]
  (template config :prompt))

(defn digest-template
  "The digest prompt: the config's :digest-prompt, the default when blank."
  [config]
  (template config :digest-prompt))

(defn precedent-template
  "The researcher's prompt: the config's :precedent-prompt, the default
  when blank."
  [config]
  (template config :precedent-prompt))

(defn db-file [config]
  (or (:db config) (path "newsroom.sqlite3")))
