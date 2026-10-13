(ns newsroom.llm.stream
  "A chat completion streamed from an OpenAI-compatible endpoint, ported from
  samizdat.llm.stream: each delta handed on as it arrives, and the chunks
  folded back into the one completion body the adapters already parse, so
  status handling, parsing and retries are the same whether a call streamed
  or not.

  jolt.http-client reads a response whole, so this speaks HTTP/1.1 over the
  socket itself: plain TCP through jolt.http.net, TLS through jolt.http.tls."
  (:require [jolt.time]
            [newsroom.json :as json]
            [clojure.string :as str]
            [jolt.http.net :as net]
            [jolt.http.tls :as tls]
            [newsroom.llm.sse :as sse]))

;; --- the chunks --------------------------------------------------------------

(defn accumulate
  "Fold one chunk into `acc`. Every string a delta carries is appended to the
  same field of the message, content and whichever field the endpoint puts
  its reasoning in, so no provider's field needs naming here."
  [acc chunk]
  (let [{:keys [delta finish_reason]} (first (:choices chunk))]
    (cond-> (merge acc (select-keys chunk [:id :model :created]))
      (:usage chunk) (assoc :usage (:usage chunk))
      ;; an error frame mid-stream: GLM sends its business codes this way
      (:error chunk) (assoc :error (:error chunk))
      finish_reason (assoc :finish-reason finish_reason)
      delta
      (update :message
              (fn [m]
                (reduce-kv (fn [m k v]
                             (cond
                               (string? v) (update m k str v)
                               (some? v) (assoc m k v)
                               :else m))
                           (or m {}) delta))))))

(defn completion
  "`acc` as the completion a non-streamed call returns."
  [{:keys [message usage finish-reason] :as acc}]
  (cond-> {:id (:id acc) :object "chat.completion" :model (:model acc)
           :choices [{:index 0
                      :message (update message :role #(or % "assistant"))
                      :finish_reason finish-reason}]}
    usage (assoc :usage usage)
    (:error acc) (assoc :error (:error acc))))

(defn delta
  "What a chunk adds that a reader sees: {:text} and/or {:reasoning}, or nil."
  [chunk]
  (let [d (:delta (first (:choices chunk)))
        text (:content d)
        reasoning (or (:reasoning_content d) (:reasoning d))]
    (not-empty (cond-> {}
                 (not-empty (str text)) (assoc :text text)
                 (not-empty (str reasoning)) (assoc :reasoning reasoning)))))

;; --- the socket --------------------------------------------------------------

(defn- open
  "A connection as {:send! :recv! :close!}. `recv!` answers a chunk of bytes,
  nil at end of stream, or throws a read timeout."
  [{:keys [tls? host port]} read-ms conn-ms]
  (if tls?
    (let [st (tls/tls-connect host port false read-ms conn-ms)]
      {:send! #((jolt.host/ref-get st :write) st %)
       :recv! #((jolt.host/ref-get st :read) st nil)
       :close! #((jolt.host/ref-get st :close))})
    (let [fd (net/connect host port conn-ms)]
      (net/set-read-timeout! fd read-ms)
      {:send! #(net/send-bytes fd %)
       :recv! #(net/recv-bytes fd)
       :close! #(net/close fd)})))

(defn- request-bytes [{:keys [host port path]} headers ^String body]
  (let [b (.getBytes body "UTF-8")]
    (byte-array
     (concat
      (.getBytes
       (str "POST " path " HTTP/1.1\r\n"
            "Host: " host ":" port "\r\n"
            (apply str (for [[k v] headers] (str k ": " v "\r\n")))
            "Accept: text/event-stream\r\n"
            "Content-Length: " (alength b) "\r\n"
            "Connection: close\r\n\r\n")
        "UTF-8")
      b))))

(defn post
  "POST `body` to `url` asking for a stream, calling `on-delta` with each
  delta (see `delta`) as it arrives. Returns {:status :headers :body} like
  jolt.http-client's post: the body is the folded completion as JSON, or
  whatever the endpoint sent instead, an error or a whole reply to a request
  it did not stream.

  `:socket-timeout` bounds each read. `on-delta` throwing does not fail the
  call: a watcher's trouble is not the model's."
  [url {:keys [headers body socket-timeout conn-timeout]} on-delta]
  (let [t (sse/parse-url url)
        conn (open t socket-timeout conn-timeout)
        hand-on (fn [d] (try (on-delta d) (catch Throwable _ nil)))]
    (try
      ((:send! conn) (request-bytes t headers (str body)))
      (loop [st (sse/reader {:keep-body? true}) acc nil]
        (let [got ((:recv! conn))
              {:keys [state events]} (if got (sse/feed st got) {:state st :events []})
              [acc done?] (reduce (fn [[acc done?] {:keys [data]}]
                                    (if (= "[DONE]" (str/trim (str data)))
                                      [acc true]
                                      (let [c (try (json/read-str (str data) :key-fn keyword)
                                                   (catch Throwable _ nil))]
                                        (if (map? c)
                                          (do (some-> (delta c) hand-on)
                                              [(accumulate acc c) done?])
                                          [acc done?]))))
                                  [acc false] events)]
          (cond
            (and (nil? got) (nil? (:status state)))
            (throw (jolt.host/throwable "java.net.SocketException"
                                        "Connection closed before a response"))

            (or (nil? got) done? (:done? state))
            {:status (:status state)
             :headers (:headers state)
             :body (if (:raw? state)
                     (sse/body-text state)
                     (json/write-str (completion acc)))}

            :else (recur state acc))))
      (finally ((:close! conn))))))
