(ns newsroom.llm-stream-test
  "A provider reply streamed: the chunks folded back into the completion the
  adapters already parse, and each delta handed on as it arrives. Ported from
  samizdat.llm-stream-test."
  (:require [jolt.time]
            [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [ring-chez.adapter :as adapter]
            [ring-chez.sse :as rsse]
            [newsroom.llm.client :as client]
            [newsroom.llm.stream :as stream]))

(defn- chunk [delta & [extra]]
  (merge {:id "c1" :model "m" :choices [{:index 0 :delta delta :finish_reason nil}]} extra))

(def ^:private chunks
  [(chunk {:role "assistant" :reasoning_content "think"})
   (chunk {:reasoning_content "ing"})
   (chunk {:content "Hel"})
   (chunk {:content "lo"})
   (merge (chunk {}) {:choices [{:index 0 :delta {} :finish_reason "stop"}]})
   {:id "c1" :model "m" :choices [] :usage {:prompt_tokens 10 :completion_tokens 3 :total_tokens 13}}])

(deftest chunks-fold-into-the-completion
  (let [c (stream/completion (reduce stream/accumulate nil chunks))
        m (get-in c [:choices 0 :message])]
    (is (= "Hello" (:content m)))
    (is (= "thinking" (:reasoning_content m)))
    (is (= "assistant" (:role m)))
    (is (= "stop" (get-in c [:choices 0 :finish_reason])))
    (is (= 13 (get-in c [:usage :total_tokens])))
    (is (= "m" (:model c)))))

(deftest a-delta-is-the-text-and-the-reasoning-it-adds
  (is (= {:text "Hel"} (stream/delta (chunk {:content "Hel"}))))
  (is (= {:reasoning "think"} (stream/delta (chunk {:reasoning_content "think"}))))
  (is (= {:reasoning "r"} (stream/delta (chunk {:reasoning "r"}))))
  (is (nil? (stream/delta (chunk {:role "assistant"})))))

(deftest an-error-frame-mid-stream-is-the-reply
  (let [acc (reduce stream/accumulate nil
                    [(chunk {:content "Hel"})
                     {:error {:code "1302" :message "请求过快"}}])]
    (is (= {:error {:code "1302" :message "请求过快"}}
           (select-keys (stream/completion acc) [:error])))))

(defn- free-port [] (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s)))

(defn- with-server [handler f]
  (let [port (free-port)
        server (adapter/run-server handler {:port port})]
    (try (f port) (finally (adapter/stop-server server)))))

(defn- streaming-handler [requests]
  (fn [req]
    (swap! requests conj (assoc (json/read-str (slurp (:body req)) :key-fn keyword)
                                ::user-agent (get-in req [:headers "user-agent"])))
    (let [ch (async/chan 64)]
      (future
        (doseq [c chunks]
          (rsse/send! ch {:data (json/write-str c)})
          (Thread/sleep 5))
        (rsse/send! ch {:data "[DONE]"})
        (async/close! ch))
      {:status 200 :headers {"Content-Type" "text/event-stream"} :body ch})))

(deftest a-streamed-post-hands-on-each-delta-and-returns-the-whole
  (let [seen (atom [])]
    (with-server (streaming-handler (atom []))
      (fn [port]
        (let [r (stream/post (str "http://127.0.0.1:" port "/v1/chat/completions")
                             {:headers {"Content-Type" "application/json"} :body "{}"
                              :socket-timeout 5000 :conn-timeout 2000}
                             #(swap! seen conj %))]
          (is (= 200 (:status r)))
          (is (= "Hello" (get-in (json/read-str (:body r) :key-fn keyword)
                                 [:choices 0 :message :content])))
          (is (= [{:reasoning "think"} {:reasoning "ing"} {:text "Hel"} {:text "lo"}] @seen)
              "each delta, in order, as it came"))))))

(deftest an-error-status-comes-back-with-its-body
  (with-server (fn [_] {:status 400 :headers {"Content-Type" "application/json"}
                        :body "{\"error\":{\"message\":\"bad request\"}}"})
    (fn [port]
      (let [r (stream/post (str "http://127.0.0.1:" port "/x")
                           {:headers {} :body "{}" :socket-timeout 5000 :conn-timeout 2000}
                           (fn [_] (throw (ex-info "no deltas on an error" {}))))]
        (is (= 400 (:status r)))
        (is (= "{\"error\":{\"message\":\"bad request\"}}" (:body r)))))))

(deftest the-client-streams-when-asked-and-the-provider-can
  (let [requests (atom [])
        seen (atom [])]
    (with-server (streaming-handler requests)
      (fn [port]
        (let [llm {:type :local :alias :test :base-url (str "http://127.0.0.1:" port "/v1")
                   :model "m" :features #{:stream} :timeout-ms 5000 :conn-timeout-ms 2000}
              reply (client/chat llm {:messages [{:role "user" :content "hi"}]
                                      :on-delta #(swap! seen conj %)})]
          (is (= "Hello" (:content reply)))
          (is (= "thinking" (:reasoning reply)))
          (is (true? (:stream (first @requests))))
          (is (= client/user-agent (::user-agent (first @requests))) "a streamed call names itself too")
          (is (= 4 (count @seen))))))))

(deftest a-cancelled-call-stops-and-is-not-retried
  ;; a cancel interrupts the thread making the call; the client used to take
  ;; the interrupt for a transport failure and send the request again
  (let [requests (atom 0)
        handler (fn [_]
                  (swap! requests inc)
                  (let [ch (async/chan 64)]
                    (future
                      (rsse/send! ch {:data (json/write-str (chunk {:content "slow"}))})
                      (Thread/sleep 4000)
                      (async/close! ch))
                    {:status 200 :headers {"Content-Type" "text/event-stream"} :body ch}))]
    (with-server handler
      (fn [port]
        (let [llm {:type :local :alias :test :base-url (str "http://127.0.0.1:" port "/v1")
                   :model "m" :features #{:stream} :timeout-ms 10000 :conn-timeout-ms 2000}
              outcome (atom nil)
              t (Thread. (fn []
                           (reset! outcome
                                   (try (client/chat llm {:messages [] :on-delta (fn [_])})
                                        (catch Throwable e e)))))
              started (System/currentTimeMillis)]
          (.start t)
          (Thread/sleep 500)
          (.interrupt t)
          (.join t 3000)
          (is (< (- (System/currentTimeMillis) started) 2000) "the call gives up within a read slice")
          (is (instance? Throwable @outcome))
          (is (re-find #"(?i)interrupt" (str (ex-message @outcome) (type @outcome))))
          (is (= 1 @requests) "and is not sent again"))))))
