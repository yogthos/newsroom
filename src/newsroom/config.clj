(ns newsroom.config
  "Where newsroom keeps its settings: ~/.config/newsroom, or $NEWSROOM_HOME.

    config.edn        sources, schedule, providers (see resources/defaults)
    prompt.md         the analysis prompt, {{date}} and {{sources}} filled in
    digest.md         the weekly and monthly digest prompt, {{period}},
                      {{days}} and {{stories}} filled in
    plugins/          loaded at startup; a plugin adds source types, see
                      newsroom.plugin
    newsroom.sqlite3  the gathered items and the briefings, by day

  The first run writes the defaults, so there is always a file to edit."
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

(defn- default-text [name]
  (slurp (io/resource (str "defaults/" name))))

(defn defaults []
  (edn/read-string (default-text "config.edn")))

(defn ensure-home!
  "Create the config directory with the default config and prompts, leaving
  any file that is already there alone."
  []
  (.mkdirs (io/file (path "plugins")))
  (doseq [name ["config.edn" "prompt.md" "digest.md"]
          :let [f (io/file (path name))]
          :when (not (.exists f))]
    (spit f (default-text name))))

(defn load-config
  "config.edn over the defaults, key by key."
  []
  (let [f (io/file (path "config.edn"))
        user (when (.exists f) (edn/read-string (slurp f)))]
    (merge (defaults) user)))

(defn prompt-template []
  (let [f (io/file (path "prompt.md"))]
    (if (.exists f) (slurp f) (default-text "prompt.md"))))

(defn digest-template []
  (let [f (io/file (path "digest.md"))]
    (if (.exists f) (slurp f) (default-text "digest.md"))))

(defn db-file [config]
  (or (:db config) (path "newsroom.sqlite3")))
