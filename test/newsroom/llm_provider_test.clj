(ns newsroom.llm-provider-test
  "The providers: each a spec in one registry, which the resolver, the
  client and the config page all read, and which a plugin adds to the
  same way the built-ins do."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.http-client :as http]
            [newsroom.config :as config]
            [newsroom.llm.adapter :as adapter]
            [newsroom.llm.client :as client]
            [newsroom.llm.provider :as provider]
            [newsroom.llm.providers :as providers]
            [newsroom.plugin :as plugin]
            [newsroom.settings :as settings]))

(defn- keys-only
  "secret-for answering only for the variables in `present`."
  [present]
  (fn [_ k] (get present k)))

(defn- body-for
  "The request body `type`'s adapter sends for `request`."
  [type request]
  (adapter/chat-body (provider/adapter type) (provider/preset type)
                     (merge {:messages [{:role "user" :content "hi"}]} request)))

(deftest every-built-in-is-a-provider
  (is (every? (set (provider/ids)) [:deepseek :glm :zai :zai-coding :openrouter :openai :local :ollama]))
  (is (= "OpenRouter" (adapter/display-name (provider/adapter :openrouter)))))

(deftest openrouter-reaches-any-model-with-one-key
  (with-redefs [config/secret-for (keys-only {"OPENROUTER_API_KEY" "sk-or"})]
    (let [llm (providers/resolve-provider {} :openrouter)]
      (is (= "https://openrouter.ai/api/v1" (:base-url llm)))
      (is (= "deepseek/deepseek-v4-flash" (:model llm)))
      (is (= "sk-or" (:api-key llm)))
      (is (= "newsroom" (get (:headers llm) "X-Title")) "it says which app is calling")
      (is (= "https://github.com/yogthos/newsroom" (get (:headers llm) "HTTP-Referer"))))
    (testing "any model it routes to, by its id there"
      (is (= "z-ai/glm-5.3" (:model (providers/resolve-provider {:providers {:or {:type :openrouter :model "z-ai/glm-5.3"}}} :or)))))
    (testing "found when it's the only key there is"
      (is (= :openrouter (:alias (providers/role-llm {} :analyst)))))))

(deftest each-provider-turns-its-thinking-down-its-own-way
  (is (= {:type "disabled"} (:thinking (body-for :deepseek {:reasoning-effort "none"}))))
  (is (= "low" (:reasoning_effort (body-for :deepseek {:reasoning-effort "low"}))))
  (is (= {:type "disabled"} (:thinking (body-for :glm {:reasoning-effort "none"})))
      "GLM turns its thinking off like DeepSeek")
  (is (= {:type "disabled"} (:thinking (body-for :zai {:reasoning-effort "none"}))))
  (is (= "low" (:reasoning_effort (body-for :glm {:reasoning-effort "low"}))))
  (is (= {:enabled false} (:reasoning (body-for :openrouter {:reasoning-effort "none"}))))
  (is (= {:effort "low"} (:reasoning (body-for :openrouter {:reasoning-effort "low"}))))
  (is (= "low" (:reasoning_effort (body-for :openai {:reasoning-effort "low"}))))
  (testing "llama.cpp's template toggle goes to a local server only"
    (is (= {:enable_thinking false} (:chat_template_kwargs (body-for :local {}))))
    (is (not (contains? (body-for :deepseek {}) :chat_template_kwargs)))))

(deftest openrouters-reasoning-is-read-from-its-own-field
  (is (= {:content "Hi" :reasoning "thought"}
         (select-keys (adapter/parse-chat (provider/adapter :openrouter)
                                          {:choices [{:message {:content "Hi" :reasoning "thought"} :finish_reason "stop"}]})
                      [:content :reasoning]))))

(deftest a-call-goes-out-as-its-provider-says
  (let [sent (atom [])]
    (with-redefs [config/secret-for (keys-only {"OPENROUTER_API_KEY" "sk-or"})
                  http/post (fn [url opts]
                              (swap! sent conj [url opts])
                              (if (= 1 (count @sent))
                                {:status 402 :body (json/write-str {:error {:message "Insufficient credits" :code 402}})}
                                {:status 200 :body (json/write-str {:choices [{:message {:content "ok"} :finish_reason "stop"}]})}))]
      (let [llm (providers/resolve-provider {} :openrouter)
            e (try (client/chat llm {:messages [{:role "user" :content "hi"}] :reasoning-effort "low"}) nil
                   (catch Exception e e))
            [url {:keys [headers body]}] (first @sent)]
        (is (= "https://openrouter.ai/api/v1/chat/completions" url))
        (is (= "Bearer sk-or" (get headers "Authorization")))
        (is (= "newsroom" (get headers "X-Title")))
        (is (= {:effort "low"} (:reasoning (json/read-str body :key-fn keyword))))
        (is (str/includes? (ex-message e) "Insufficient credits"))
        (is (= 1 (count @sent)) "out of credit isn't tried again")))))

(deftest a-call-names-itself
  (let [sent (atom [])]
    (with-redefs [config/secret-for (keys-only {"OPENROUTER_API_KEY" "sk-or"})
                  http/post (fn [url opts]
                              (swap! sent conj opts)
                              {:status 200 :body (json/write-str {:choices [{:message {:content "ok"} :finish_reason "stop"}]})})]
      (let [call (fn [cfg] (client/chat (providers/resolve-provider cfg :openrouter)
                                        {:messages [{:role "user" :content "hi"}]})
                   (get-in (last @sent) [:headers "User-Agent"]))]
        (is (= client/user-agent (call {})) "as newsroom, when its provider doesn't say")
        (is (= "acme/1.0" (call {:providers {:openrouter {:user-agent " acme/1.0 "}}})) "or as its provider says")
        (is (= "acme/2.0" (call {:providers {:openrouter {:user-agent "acme/2.0" :headers {"user-agent" "x"}}}}))
            ":user-agent wins over a header of any case")
        (is (= "hdr/1" (call {:providers {:openrouter {:headers {"user-agent" "hdr/1"}}}}))
            "and a User-Agent header is kept")
        (is (= 1 (count (filter #(= "user-agent" (str/lower-case %)) (keys (:headers (last @sent)))))))))
    (is (some #{:user-agent} (map :key (settings/provider-fields))) "the config page has the field")))

(deftest a-plugin-adds-a-provider-the-way-the-built-ins-are
  (try
    (plugin/defprovider :acme {:label "Acme" :base-url "https://llm.acme.test/v1" :key-env "ACME_KEY"
                               :model "acme-1" :features #{:reasoning-effort}})
    (with-redefs [config/secret-for (keys-only {"ACME_KEY" "sk-acme"})]
      (let [llm (providers/resolve-provider {:roles {:analyst :acme}} :acme)]
        (is (= ["https://llm.acme.test/v1" "acme-1" "sk-acme"] ((juxt :base-url :model :api-key) llm)))
        (is (= "Acme" (adapter/display-name (provider/adapter :acme))))
        (is (= "low" (:reasoning_effort (body-for :acme {:reasoning-effort "low"}))) "an OpenAI-style endpoint by default")))
    (is (some #{:acme} (:options (first (filter #(= :type (:key %)) (settings/provider-fields)))))
        "and the config page offers it")
    (finally (provider/unregister! :acme))))
