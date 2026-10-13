(ns newsroom.llm.provider
  "The providers newsroom can talk to, each one spec in one registry.

  A spec says everything that differs from one provider to the next:

    {:label     \"OpenRouter\"            ; for the page and error messages
     :wire      :openai                   ; :openai chat completions (the default) or :ollama
     :base-url  \"https://openrouter.ai/api/v1\"
     :key-env   \"OPENROUTER_API_KEY\"    ; where its key is read from, nil for none
     :model     \"deepseek/deepseek-v4-flash\"
     :features  #{:reasoning-effort :stream}
     :headers   {\"X-Title\" \"newsroom\"} ; sent with every request
     :reasoning-key :reasoning            ; the reply's field carrying the reasoning
     :max-tokens-key :max_tokens          ; the request's field for the token cap
     :thinking  f                         ; a thinking level, like \"low\", as request fields
     :detect    4}                        ; its place among those found by their key

  or an :adapter of its own in place of :wire, for an endpoint that speaks
  something else. The built-ins (newsroom.llm.builtin) are registered the
  same way a plugin adds one, with newsroom.plugin/defprovider, and the
  resolver (newsroom.llm.providers), the client and the config page read
  only from here."
  (:require [clojure.string :as str]
            [newsroom.llm.adapter.ollama :as ollama]
            [newsroom.llm.adapter.openai :as openai]))

(defonce ^:private registry (atom {}))

(def ^:private preset-keys
  "What of a spec is an llm config's defaults: what an entry in :providers
  overrides."
  [:base-url :key-env :model :features :headers])

(defn- build [id {:keys [wire adapter] :as spec}]
  (or adapter
      (case (or wire :openai)
        :openai (openai/openai-family (assoc spec :id id))
        :ollama ollama/ollama)))

(defn register!
  "Add the provider `id` (a keyword) as `spec` says, replacing one of the
  same id. Returns the id."
  [id spec]
  (swap! registry assoc id (assoc spec :id id ::adapter (build id spec)))
  id)

(defn unregister!
  "Drop the provider `id`."
  [id]
  (swap! registry dissoc id)
  nil)

(defn ids
  "Every provider's id, in order."
  []
  (sort (keys @registry)))

(defn spec
  "The provider `id`'s spec, or nil."
  [id]
  (get @registry id))

(defn preset
  "The llm config defaults of the provider `id`: {:base-url :key-env
  :model :features :headers}, or nil when there is no such provider."
  [id]
  (some-> (spec id) (select-keys preset-keys)))

(defn adapter
  "The adapter that talks to the provider `id`. Throws naming what there is
  rather than returning nil, since a nil adapter fails much later and less
  clearly."
  [id]
  (or (::adapter (spec id))
      (throw (ex-info (str "No adapter for provider " id ". Known: " (str/join ", " (map name (ids))))
                      {:provider id :known (ids)}))))

(defn detect-order
  "The providers found by their key when nothing names one, in the order
  they're tried."
  []
  (->> (vals @registry) (filter :detect) (sort-by :detect) (mapv :id)))
