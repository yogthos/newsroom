(ns newsroom.sources
  "Source adapters. A source in config.edn is a map with a :type; the
  `fetch-items` method for that type fetches what the source has for a day
  and returns items ({:title :url :source :summary :published}, see
  newsroom.feed). It is blocking: the pipeline runs it on a blocking
  executor under a timeout, so an adapter needs no timeout logic of its own.

  An adapter reports what it is doing with `emit!`, which the page shows
  live while a run goes.

  :rss, :scrape and :web-search are built in. A plugin adds a type with
  newsroom.plugin/defsource."
  (:require [jolt.time]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.xml :as xml]
            [jolt.http-client :as http]
            [jolt.xml]
            [newsroom.feed :as feed]))

(defmulti fetch-items
  "The items `source` has, for the context {:day \"YYYY-MM-DD\" :config ...}."
  (fn [source _ctx] (:type source)))

(defn emit!
  "Tell whoever watches the run what the adapter is doing: `text` is shown on
  the page as it happens, with a link when `extra` has a :url."
  ([ctx text] (emit! ctx text nil))
  ([ctx text extra]
   (when-let [f (:emit ctx)]
     (f (merge {:text text} extra)))
   nil))

(defmethod fetch-items :default [source _]
  (throw (ex-info (str "no adapter for source type " (pr-str (:type source))
                       "; known: " (str/join ", " (map pr-str (remove #{:default} (keys (methods fetch-items))))))
                  {:source source})))

(defn source-name [source]
  (or (:name source) (:url source) (some-> (:type source) name)))

(def default-user-agent "newsroom/0.1 (+https://github.com/yogthos/newsroom)")

(defn fetch-text
  "The body of `url` as text; throws on a status other than 2xx."
  [url {:keys [timeout-ms user-agent]}]
  (let [timeout-ms (or timeout-ms 30000)
        resp (http/get url {:headers {"User-Agent" (or user-agent default-user-agent)
                                      "Accept" "application/rss+xml, application/atom+xml, application/xml, text/xml, text/html, */*"}
                            :socket-timeout timeout-ms
                            :conn-timeout (min timeout-ms 15000)
                            :throw-exceptions false})]
    (if (<= 200 (:status resp) 299)
      (str (:body resp))
      (throw (ex-info (str "HTTP " (:status resp) " from " url) {:status (:status resp)})))))

;; --- rss ---------------------------------------------------------------------------

(defmethod fetch-items :rss [source {:keys [config] :as ctx}]
  (emit! ctx (str "Reading " (source-name source)) {:url (:url source)})
  (let [body (fetch-text (:url source) {:timeout-ms (:source-timeout-ms config 30000)
                                       :user-agent (:user-agent source)})
        ;; libxml2 refuses a document with anything before the prolog
        body (subs body (or (str/index-of body "<") 0))]
    (feed/feed-items (xml/parse body) (source-name source))))

;; --- scraping -----------------------------------------------------------------------
;; A page with no feed: the links on it whose URL matches :link-pattern are its
;; stories, titled by their link text. With :summaries, each story's own page
;; is read too for its description and date, one after another, which costs a
;; request per story.

(defn- with-meta-from-page [ctx opts item]
  (try
    (let [{:keys [description published]} (feed/page-meta (fetch-text (:url item) opts))]
      (assoc item :summary (feed/clip (or description "") feed/summary-chars) :published published))
    (catch Exception e
      (when (instance? InterruptedException e) (throw e))
      (emit! ctx (str "couldn't read " (:url item) ": " (ex-message e)) {:level :error :url (:url item)})
      item)))

(defmethod fetch-items :scrape [source {:keys [config] :as ctx}]
  (let [opts {:timeout-ms (:source-timeout-ms config 30000) :user-agent (:user-agent source)}
        _ (emit! ctx (str "Reading " (source-name source)) {:url (:url source)})
        links (take (:limit source 15)
                    (feed/page-links (fetch-text (:url source) opts) (:url source)
                                     (re-pattern (:link-pattern source ".")) (source-name source)))]
    (if (:summaries source)
      (mapv #(with-meta-from-page ctx opts %) links)
      (vec links))))

;; --- web search --------------------------------------------------------------------
;; Exa's hosted MCP, as samizdat.agent.websearch calls it: a plain JSON-RPC
;; POST that needs no key; EXA_API_KEY only raises the rate limit.

(def ^:private exa-endpoint "https://mcp.exa.ai/mcp")

(defn- exa-envelope [query n]
  {"jsonrpc" "2.0"
   "id" 1
   "method" "tools/call"
   "params" {"name" "web_search_exa"
             "arguments" {"query" (str query)
                          "type" "auto"
                          "numResults" (max 1 (min 10 n))
                          "livecrawl" "fallback"}}})

(defn- mcp-text
  "The first text in an MCP result, from a plain JSON body or from the data
  line of an event stream; nil when neither parses."
  [body]
  (let [parse (fn [s] (try (json/read-str s) (catch Throwable _ nil)))
        text-of (fn [parsed]
                  (when (map? parsed)
                    (some->> (get-in parsed ["result" "content"])
                             (filter map?)
                             (keep #(get % "text"))
                             (remove str/blank?)
                             first)))
        trimmed (str/trim (str body))]
    (or (when (str/starts-with? trimmed "{") (text-of (parse trimmed)))
        (some (fn [line]
                (when (str/starts-with? line "data: ")
                  (text-of (parse (str/trim (subs line 6))))))
              (str/split-lines (str body))))))

(defn exa-search
  "The items Exa finds for `query`."
  [query n {:keys [timeout-ms source]}]
  (let [key (jolt.host/getenv "EXA_API_KEY")
        url (if (str/blank? key) exa-endpoint (str exa-endpoint "?exaApiKey=" key))
        resp (http/post url {:headers {"Content-Type" "application/json"
                                       "Accept" "application/json, text/event-stream"}
                             :body (json/write-str (exa-envelope query n))
                             :socket-timeout timeout-ms
                             :conn-timeout (min timeout-ms 15000)
                             :throw-exceptions false})]
    (when-not (= 200 (:status resp))
      (throw (ex-info (str "web search answered " (:status resp)) {:status (:status resp)})))
    (if-let [text (mcp-text (:body resp))]
      (feed/search-items text source)
      (throw (ex-info "web search answer had no results text" {})))))

(def ^:private month-names
  ["January" "February" "March" "April" "May" "June" "July" "August"
   "September" "October" "November" "December"])

(defn long-date
  "2026-09-30 as 30 September 2026, the way a search query reads."
  [day]
  (let [[y m d] (str/split day #"-")]
    (str (parse-long d) " " (month-names (dec (parse-long m))) " " y)))

(def retry-wait-ms
  "How long a rate-limited search waits before its one retry."
  2000)

(defn- search-once
  "One query's items: tried again once after a rate limit, and empty when it
  fails, which is reported so the query costs only its own results."
  [ctx query n opts]
  (let [attempt #(try {:items (exa-search query n opts)}
                      (catch Exception e
                        ;; a cancelled run interrupts this thread: stop, don't carry on
                        (if (instance? InterruptedException e) (throw e) {:error e})))
        {:keys [items error]} (let [r (attempt)]
                                (if (= 429 (:status (ex-data (:error r))))
                                  (do (emit! ctx (str "Rate limited searching “" query "”, trying again"))
                                      (Thread/sleep retry-wait-ms)
                                      (attempt))
                                  r))]
    (if error
      (do (emit! ctx (str "“" query "” failed: " (ex-message error)) {:level :error})
          nil)
      (do (emit! ctx (str (count items) " results for “" query "”"))
          items))))

(defmethod fetch-items :web-search [source {:keys [day config] :as ctx}]
  ;; a query is a string, or {:query :name} to credit its results to an
  ;; outlet rather than to the search
  (let [n (:results source 6)
        timeout (:source-timeout-ms config 30000)
        results (mapv (fn [q]
                        (let [{:keys [query name]} (if (map? q) q {:query q})
                              query (str/replace query "{{date}}" (long-date day))]
                          (emit! ctx (str "Searching the web for “" query "”"))
                          (search-once ctx query n {:timeout-ms timeout
                                                    :source (or name (source-name source))})))
                      (:queries source ["top world news {{date}}"]))]
    (if (and (seq results) (every? nil? results))
      (throw (ex-info "every search failed" {}))
      (vec (apply concat results)))))
