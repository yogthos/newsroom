(ns newsroom.llm.adapter.openai
  "The OpenAI chat-completions family, ported from samizdat.llm.adapter.openai.

  One adapter covers OpenAI, DeepSeek, GLM, OpenRouter, and any local
  llama-server or vLLM endpoint, because they all speak the same wire
  format. What differs is carried as fields on the record, from the
  provider's spec: where the reasoning comes back, what the max-tokens field
  is called, and how a thinking level is asked for. The tool-call, prefill and grammar
  machinery samizdat needs for an agent loop is left out; a briefing is one
  plain completion."
  (:require [clojure.string :as str]
            [newsroom.llm.adapter :as adapter]))

(def ^:private usage-cap-re
  #"(?i)insufficient|quota|balance|billing|credit|exceeded your current|usage limit|arrearage")

(defn- supports? [config feature]
  (contains? (or (:features config) #{}) feature))

(defn- local-wire
  "llama.cpp's knobs, each behind the feature that says the endpoint has it:
  Qwen-family thinking is turned off through the chat template unless the
  config opts in with :thinking? true."
  [config]
  (cond-> {}
    (and (supports? config :thinking-toggle) (not (:thinking? config)))
    (assoc :chat_template_kwargs {:enable_thinking false})))

(defn effort-field
  "How most endpoints take a thinking level: reasoning_effort, \"none\"
  and all."
  [effort]
  {:reasoning_effort effort})

(defn thinking-switch
  "DeepSeek's and GLM's: thinking turned off outright for \"none\", else
  reasoning_effort."
  [effort]
  (if (= "none" effort) {:thinking {:type "disabled"}} {:reasoning_effort effort}))

(defn reasoning-object
  "OpenRouter's: a reasoning object, which it passes on in each upstream's
  own terms, turned off for \"none\"."
  [effort]
  (if (= "none" effort) {:reasoning {:enabled false}} {:reasoning {:effort effort}}))

(defrecord OpenAIAdapter [provider-id label reasoning-key max-tokens-key thinking]
  adapter/Adapter
  (id [_] provider-id)
  (display-name [_] label)

  (chat-url [_ config] (str (:base-url config) "/chat/completions"))

  (models-url [_ config]
    (str (str/replace (str (:base-url config)) #"/beta/?$" "/v1") "/models"))

  (auth-headers [_ config]
    (if-let [k (:api-key config)]
      {"Authorization" (str "Bearer " k)}
      {}))

  (chat-body [_ config {:keys [messages max-tokens temperature reasoning-effort]}]
    (let [effort (or reasoning-effort (:reasoning-effort config))]
      (cond-> {:model (:model config) :messages messages}
        max-tokens (assoc max-tokens-key max-tokens)
        temperature (assoc :temperature temperature)
        (some? effort) (merge (thinking effort))
        :always (merge (local-wire config)))))

  (parse-chat [_ body]
    (when-let [choice (first (:choices body))]
      (let [msg (:message choice)]
        {:content (:content msg)
         :reasoning (get msg reasoning-key)
         :finish-reason (or (:finish_reason choice) "stop")
         ;; the model that answered, which may not be the one requested
         :model (some-> (:model body) str not-empty)
         :usage (when-let [u (:usage body)]
                  {:prompt-tokens (or (:prompt_tokens u) 0)
                   :completion-tokens (or (:completion_tokens u) 0)
                   :total-tokens (or (:total_tokens u) 0)})})))

  (parse-models [_ body] (mapv :id (:data body)))

  (error-message [_ body]
    (when-let [e (:error body)]
      (if (map? e)
        (str (or (:message e) "unknown error")
             (when-let [c (:code e)] (str " (code " c ")")))
        (str e))))

  (usage-cap? [_ _status body]
    (let [msg (str (get-in body [:error :message])
                   " " (get-in body [:error :type])
                   " " (get-in body [:error :code]))]
      (boolean (re-find usage-cap-re msg)))))

(defn openai-family
  "An adapter for an OpenAI-compatible endpoint, from a provider's spec
  (see newsroom.llm.provider): `reasoning-key` names the reply's field
  carrying the reasoning, `max-tokens-key` the request's for the token cap,
  since newer OpenAI models renamed max_tokens to max_completion_tokens,
  and `thinking` turns a thinking level into request fields."
  [{:keys [id label reasoning-key max-tokens-key thinking]
    :or {max-tokens-key :max_tokens}}]
  (->OpenAIAdapter id (or label (str/capitalize (name id)))
                   (or reasoning-key :__no_reasoning_field__)
                   max-tokens-key
                   (or thinking effort-field)))
