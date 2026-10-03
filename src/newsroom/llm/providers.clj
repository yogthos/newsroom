(ns newsroom.llm.providers
  "Which endpoint answers, declared in config.edn. Ported from samizdat.config.

  config.edn declares endpoints under :providers, by alias, and says which
  alias serves each role under :roles:

    {:providers {:deepseek {:model \"deepseek-v4-pro\"}      ; alias = built-in
                 :bonsai   {:type :local :base-url \"http://127.0.0.1:8080/v1\"
                            :thinking? true}
                 :vllm     {:type :openai :base-url \"https://gpu:8000/v1\"
                            :api-key \"${VLLM_KEY}\"}}
     :roles {:analyst :deepseek}}

  :type picks the adapter and the built-in preset that fills whatever the
  entry leaves out; it defaults to the alias when the alias names a built-in.
  :api-key-env names the variable holding the key; :api-key is a literal or
  exactly \"${VAR}\", and so is a :headers value. Anything else on an entry is
  an llm knob (:model, :max-tokens, :temperature, :timeout-ms, :thinking?,
  :reasoning-effort, :features).

  With no :roles, the first built-in whose key is in the environment serves
  (DeepSeek, GLM, OpenAI), and a local endpoint when none is."
  (:require [clojure.string :as str]
            [newsroom.config :as config]))

(def presets
  {:deepseek {:base-url "https://api.deepseek.com/v1"
              :features #{:thinking-toggle :reasoning-effort :stream}
              :key-env  "DEEPSEEK_API_KEY"
              :model    "deepseek-v4-flash"}
   ;; the coding endpoint, the one a GLM coding plan key is good for
   :glm      {:base-url "https://open.bigmodel.cn/api/coding/paas/v4"
              :features #{:reasoning-effort :stream}
              :key-env  "ZHIPU_API_KEY"
              :model    "glm-5.3"}
   :openai   {:base-url "https://api.openai.com/v1"
              :features #{:reasoning-effort :stream}
              :key-env  "OPENAI_API_KEY"
              :model    "gpt-4o"}
   ;; llama-server / vLLM / LM Studio
   :local    {:base-url "http://127.0.0.1:8080/v1"
              :key-env  nil
              :model    "local-model"
              :features #{:thinking-toggle :stream}}
   ;; Ollama's native api, so no /v1 suffix
   :ollama   {:base-url "http://127.0.0.1:11434"
              :key-env  nil
              :model    "qwen3"
              :features #{}}})

(def default-llm
  "What every provider runs with unless its entry says otherwise. A briefing
  is one long answer, and a thinking model spends part of :max-tokens on its
  reasoning before it writes a word, so both limits are generous."
  {:temperature 0.4
   :max-tokens 32768
   :timeout-ms 600000
   :conn-timeout-ms 15000})

(defn- env [k]
  (config/secret k))

(defn- as-key [x]
  (some-> x name str/lower-case not-empty keyword))

(defn- expand-var
  "`s` with an exact ${VAR} replaced from the environment or secrets.edn. An
  unset variable is an error naming it."
  [where s]
  (if-let [[_ v] (and (string? s) (re-matches #"\$\{([A-Za-z_][A-Za-z0-9_]*)\}" s))]
    (or (env v)
        (throw (ex-info (str where " names ${" v "}, which is not set") {:var v})))
    s))

(defn- declared-entry [config alias]
  (some (fn [[k v]] (when (= (as-key k) alias) v)) (:providers config)))

(defn provider-names
  "Every provider `config` can select: the built-ins and its declared aliases."
  [config]
  (distinct (concat (keys presets) (keep as-key (keys (:providers config))))))

(defn- detect-provider []
  (or (first (for [p [:deepseek :glm :openai]
                   :when (env (:key-env (presets p)))]
               p))
      :local))

(defn resolve-provider
  "The llm config `alias` names: its preset with the declaration's knobs over
  it and the key resolved. Throws naming the alias when nothing declares it
  or its type has no preset."
  [config alias]
  (let [alias (as-key alias)
        entry (declared-entry config alias)
        type (as-key (or (:type entry) (when (presets alias) alias)))
        known (sort (map name (keys presets)))
        where (str "provider " (some-> alias name))]
    (cond
      (and (nil? entry) (nil? (presets alias)))
      (throw (ex-info (str "unknown provider " (some-> alias name)
                           "; built-in: " (str/join ", " known))
                      {:provider alias :known (provider-names config)}))

      (nil? type)
      (throw (ex-info (str where " needs a :type, one of " (str/join ", " known))
                      {:provider alias}))

      (nil? (presets type))
      (throw (ex-info (str where " has :type " (name type)
                           ", which no adapter serves; one of " (str/join ", " known))
                      {:provider alias :type type}))

      :else
      (let [preset (presets type)
            key-env (or (:api-key-env entry) (:key-env preset))]
        (cond-> (merge default-llm
                       (dissoc preset :key-env)
                       (dissoc (or entry {}) :type :api-key-env))
          true (assoc :type type :alias alias)
          (and key-env (not (:api-key entry))) (assoc :api-key (env key-env))
          (:api-key entry) (update :api-key #(expand-var where %))
          (:headers entry) (update :headers
                                   #(into {} (for [[k v] %] [k (expand-var where v)]))))))))

(defn role-llm
  "The llm config serving `role`: the alias :roles assigns it, else the
  :default role's, else whichever built-in has a key in the environment."
  [config role]
  (let [roles (:roles config)
        alias (or (get roles role) (get roles :default) (detect-provider))]
    (resolve-provider config alias)))

(defn redacted
  "`llm` safe to log or show: the key reduced to whether there is one."
  [llm]
  (cond-> (dissoc llm :headers)
    (contains? llm :api-key) (assoc :api-key (if (:api-key llm) "<set>" "<none>"))))
