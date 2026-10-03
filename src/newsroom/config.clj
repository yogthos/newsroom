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

(defn default-text [name]
  (slurp (io/resource (str "defaults/" name))))

(defn default-settings
  "The settings before any are saved: resources/defaults/settings.edn, with
  the default prompts as :prompt and :digest-prompt."
  []
  (assoc (edn/read-string (default-text "settings.edn"))
         :prompt (default-text "prompt.md")
         :digest-prompt (default-text "digest.md")))

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

(defn prompt-template
  "The analysis prompt: the config's :prompt, the default when blank."
  [config]
  (let [p (:prompt config)]
    (if (str/blank? p) (default-text "prompt.md") p)))

(defn digest-template
  "The digest prompt: the config's :digest-prompt, the default when blank."
  [config]
  (let [p (:digest-prompt config)]
    (if (str/blank? p) (default-text "digest.md") p)))

(defn db-file [config]
  (or (:db config) (path "newsroom.sqlite3")))
