(ns newsroom.llm.builtin
  "The providers newsroom comes with, registered as any plugin's would be
  (see newsroom.llm.provider)."
  (:require [newsroom.llm.adapter.openai :as openai]
            [newsroom.llm.provider :as provider]))

(provider/register! :deepseek
  {:label "DeepSeek"
   :base-url "https://api.deepseek.com/v1"
   :key-env "DEEPSEEK_API_KEY"
   :model "deepseek-v4-flash"
   :features #{:reasoning-effort :stream}
   :reasoning-key :reasoning_content
   :thinking openai/thinking-switch
   :detect 1})

;; Zhipu's own endpoint in China, the coding one, which is what a GLM coding
;; plan key is good for
(provider/register! :glm
  {:label "GLM"
   :base-url "https://open.bigmodel.cn/api/coding/paas/v4"
   :key-env "ZHIPU_API_KEY"
   :model "glm-5.3"
   :features #{:reasoning-effort :stream}
   :reasoning-key :reasoning_content
   :thinking openai/thinking-switch
   :detect 2})

;; GLM through Z.ai, Zhipu's international endpoint, and its coding plan's
(provider/register! :zai
  {:label "Z.ai"
   :base-url "https://api.z.ai/api/paas/v4"
   :key-env "ZAI_API_KEY"
   :model "glm-5.3"
   :features #{:reasoning-effort :stream}
   :reasoning-key :reasoning_content
   :thinking openai/thinking-switch
   :detect 3})

(provider/register! :zai-coding
  {:label "Z.ai coding plan"
   :base-url "https://api.z.ai/api/coding/paas/v4"
   :key-env "ZAI_API_KEY"
   :model "glm-5.3"
   :features #{:reasoning-effort :stream}
   :reasoning-key :reasoning_content
   :thinking openai/thinking-switch})

;; any model of any provider through one key, each by its id there, like
;; z-ai/glm-5.3; the headers say which app is calling
(provider/register! :openrouter
  {:label "OpenRouter"
   :base-url "https://openrouter.ai/api/v1"
   :key-env "OPENROUTER_API_KEY"
   :model "deepseek/deepseek-v4-flash"
   :features #{:reasoning-effort :stream}
   :headers {"HTTP-Referer" "https://github.com/yogthos/newsroom" "X-Title" "newsroom"}
   :reasoning-key :reasoning
   :thinking openai/reasoning-object
   :detect 4})

(provider/register! :openai
  {:label "OpenAI"
   :base-url "https://api.openai.com/v1"
   :key-env "OPENAI_API_KEY"
   :model "gpt-4o"
   :features #{:reasoning-effort :stream}
   :detect 5})

;; llama-server, vLLM, LM Studio; llama-server hands back reasoning_content
;; for a reasoning model exactly as DeepSeek does
(provider/register! :local
  {:label "local"
   :base-url "http://127.0.0.1:8080/v1"
   :key-env nil
   :model "local-model"
   :features #{:thinking-toggle :stream}
   :reasoning-key :reasoning_content})

;; Ollama's native api, so no /v1 suffix
(provider/register! :ollama
  {:label "Ollama"
   :wire :ollama
   :base-url "http://127.0.0.1:11434"
   :key-env nil
   :model "qwen3"
   :features #{}})
