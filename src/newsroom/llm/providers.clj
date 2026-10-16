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
  exactly \"${VAR}\", and so is a :headers value. :user-agent is what its
  calls name themselves as, newsroom's own when it's left out. Anything else on an entry is
  an llm knob (:model, :max-tokens, :temperature, :timeout-ms, :thinking?,
  :reasoning-effort, :features).

  With no :roles, the first built-in whose key is in the environment serves
  (DeepSeek, GLM, Z.ai, OpenRouter, OpenAI), and a local endpoint when none
  is. The built-ins, and any a plugin adds, are in newsroom.llm.provider.

  With several readers, the settings are a reader's own, so a key read from
  the environment or secrets.edn has to be one config.edn shares, and it
  is sent only where its built-in sends it: a :type :deepseek entry may use
  DEEPSEEK_API_KEY at DeepSeek's own endpoint, and nowhere else. A key
  typed into the settings is the reader's to send where they like."
  (:require [clojure.string :as str]
            [newsroom.config :as config]
            [newsroom.llm.builtin]
            [newsroom.llm.provider :as provider]))

(def default-llm
  "What every provider runs with unless its entry says otherwise. A briefing
  is one long answer, and a thinking model spends part of :max-tokens on its
  reasoning before it writes a word, so both limits are generous."
  {:temperature 0.4
   :max-tokens 32768
   :timeout-ms 600000
   :conn-timeout-ms 15000})

(defn- env [config k]
  (config/secret-for config k))

(defn as-key
  "The provider `x` names, as aliases are matched: case aside."
  [x]
  (some-> x name str/lower-case not-empty keyword))

(defn- shared-key-error
  "Why the shared secret `v` can't be sent to the endpoint `llm` resolves
  to, or nil when it can: with several readers, only to the built-in whose
  key it is, at that built-in's own endpoint."
  [config where v llm]
  (when-not (config/single-user? config)
    (let [preset (provider/preset (:type llm))]
      (when-not (and (= v (:key-env preset)) (= (:base-url llm) (:base-url preset)))
        (ex-info (str where " can't use ${" v "}: a key the server shares is only sent to its own provider's "
                      "endpoint, so type the key in to use it elsewhere")
                 {:var v :shared true})))))

(defn- expand-var
  "`s` with an exact ${VAR} replaced from the environment or secrets.edn,
  checked by `allowed` first, which returns an error to throw or nil. An
  unset variable is an error naming it, and so is one that isn't shared
  with readers."
  [config where s allowed]
  (if-let [[_ v] (and (string? s) (re-matches #"\$\{([A-Za-z_][A-Za-z0-9_]*)\}" s))]
    (let [value (env config v)]
      (cond
        value (if-let [e (allowed v)] (throw e) value)
        (config/secret v) (throw (ex-info (str where " names ${" v "}, which isn't shared with readers")
                                          {:var v :shared false}))
        :else (throw (ex-info (str where " names ${" v "}, which is not set") {:var v}))))
    s))

(defn- declared-entry [config alias]
  (some (fn [[k v]] (when (= (as-key k) alias) v)) (:providers config)))

(defn provider-names
  "Every provider `config` can select: the built-ins and its declared aliases."
  [config]
  (distinct (concat (provider/ids) (keep as-key (keys (:providers config))))))

(defn- detect-provider [config]
  (or (first (for [p (provider/detect-order)
                   :when (env config (:key-env (provider/preset p)))]
               p))
      :local))

(defn resolve-provider
  "The llm config `alias` names: its preset with the declaration's knobs over
  it and the key resolved. Throws naming the alias when nothing declares it
  or its type has no preset."
  [config alias]
  (let [alias (as-key alias)
        entry (declared-entry config alias)
        type (as-key (or (:type entry) (when (provider/preset alias) alias)))
        known (map name (provider/ids))
        where (str "provider " (some-> alias name))]
    (cond
      (and (nil? entry) (nil? (provider/preset alias)))
      (throw (ex-info (str "unknown provider " (some-> alias name)
                           "; built-in: " (str/join ", " known))
                      {:provider alias :known (provider-names config)}))

      (nil? type)
      (throw (ex-info (str where " needs a :type, one of " (str/join ", " known))
                      {:provider alias}))

      (nil? (provider/preset type))
      (throw (ex-info (str where " has :type " (name type)
                           ", which no adapter serves; one of " (str/join ", " known))
                      {:provider alias :type type}))

      :else
      (let [preset (provider/preset type)
            key-env (or (:api-key-env entry) (:key-env preset))
            llm (-> (merge default-llm
                           (dissoc preset :key-env)
                           (dissoc (or entry {}) :type :api-key-env :headers :user-agent))
                    (assoc :type type :alias alias))
            allowed #(shared-key-error config where % llm)
            env-key (when (and key-env (not (:api-key entry)) (env config key-env))
                      (when-let [e (allowed key-env)] (throw e))
                      (env config key-env))]
        (cond-> llm
          (and key-env (not (:api-key entry))) (assoc :api-key env-key)
          (:api-key entry) (update :api-key #(expand-var config where % allowed))
          (:headers entry) (update :headers merge
                                   (into {} (for [[k v] (:headers entry)] [k (expand-var config where v allowed)])))
          (not (str/blank? (:user-agent entry)))
          (update :headers #(assoc (into {} (remove (fn [[k _]] (= "user-agent" (str/lower-case (str k))))) %)
                                   "User-Agent" (str/trim (:user-agent entry)))))))))

(defn role-llm
  "The llm config serving `role`: the alias :roles assigns it, else the
  :default role's, else whichever built-in has a key in the environment."
  [config role]
  (let [roles (:roles config)
        alias (or (get roles role) (get roles :default) (detect-provider config))]
    (resolve-provider config alias)))

(defn redacted
  "`llm` safe to log or show: the key reduced to whether there is one."
  [llm]
  (cond-> (dissoc llm :headers)
    (contains? llm :api-key) (assoc :api-key (if (:api-key llm) "<set>" "<none>"))))
