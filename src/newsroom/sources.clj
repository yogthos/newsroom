(ns newsroom.sources
  "Source adapters. A source in config.edn is a map with a :type; the
  `fetch-items` method for that type fetches what the source has for a day
  and returns items ({:title :url :source :summary :published}, see
  newsroom.feed). It is blocking: the pipeline runs it on a blocking
  executor under a timeout, so an adapter needs no timeout logic of its own.

  An adapter reports what it is doing with `emit!`, which the page shows
  live while a run goes.

  A source is read with `read-source`, under its policy: how long it may
  take, how many times a request that failed for a passing reason is tried
  again, and how long to wait before that. The source's own :timeout-ms,
  :retries and :retry-wait-ms come first, then its type's shape's :policy,
  then the config's :source-timeout-ms, :source-retries and
  :source-retry-wait-ms. `fetch-text`, and the plugins' get-json and
  post-json, retry under it on their own, so an adapter needs no retry
  logic either.

  :rss, :scrape and :web-search are built in. A plugin adds a type with
  newsroom.plugin/defsource.

  `shape` says what a source of a type looks like, for the config page: a
  doc string and its fields beyond :type and :name, each

    {:key :url :type :string :doc \"...\" :required? true :default ...}

  where :type is :string, :text, :int, :number, :boolean, :strings (a list
  of strings), :keyword or :keywords (from :options), or :records (a list
  of maps, each with the :fields given; with :shorthand?, an entry with
  only its first field is that field's value). :default is only shown; a field
  left blank is left out of the source. A shape's :policy, {:timeout-ms :retries
  :retry-wait-ms}, is how its sources are read when they don't say,
  before the config's settings for every source. A :string can also say it is
  a :regex?, or match a :pattern; an :int or :number can have a :min and
  a :max. :label names it on the page, where the key does by default."
  (:require [jolt.time]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.xml :as xml]
            [jolt.http-client :as http]
            [jolt.xml]
            [newsroom.config :as config]
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

(defmulti default-name
  "What a source of this type is called when it has no :name of its own."
  :type)

(defmethod default-name :default [source]
  (or (:url source) (some-> (:type source) name)))

(defmulti shape
  "What a source of this type looks like, {:doc :fields}, or nil when its
  type says nothing about its keys."
  identity)

(defmethod shape :default [_] nil)

(defn source-name
  "What a source is called on the page and in its items. Two sources of a
  run need different names, since its status is kept by name."
  [source]
  (or (:name source) (default-name source)))

(def default-user-agent "newsroom/0.6 (+https://github.com/yogthos/newsroom)")

;; --- the policy --------------------------------------------------------------------

(def default-policy
  "How a source is read when nothing says otherwise."
  {:timeout-ms 30000 :retries 1 :retry-wait-ms 2000})

(def ^:private global-keys
  "The config's setting for each part of the policy, for every source."
  {:timeout-ms :source-timeout-ms :retries :source-retries :retry-wait-ms :source-retry-wait-ms})

(defn policy
  "How `source` is read, {:timeout-ms :retries :retry-wait-ms}: its own
  keys, else its type's shape's :policy, else the config's, else
  `default-policy`."
  [source config]
  (let [typed (:policy (shape (:type source)))]
    (into {} (for [[k global] global-keys]
               [k (some #(when (some? %) %) [(get source k) (get typed k) (get config global) (default-policy k)])]))))

(def ^:dynamic *policy*
  "The policy of the source being read, with its :deadline, the epoch ms
  by which it has to be done, and the run's :emit; bound by `read-source`.
  Outside one, a request is tried once."
  nil)

(defn- now [] (System/currentTimeMillis))

(def ^:private passing-statuses
  "The statuses a request may well not get the next time: too many requests,
  a timeout, an overloaded or restarting server."
  #{408 425 429 500 502 503 504})

(defn- passing?
  "Whether `e` is a failure that trying again may get past: a status from
  `passing-statuses`, or a failure to reach the server at all. A failure
  that is an answer, like a 404 or a page that doesn't parse, is not."
  [e]
  (if-let [status (:status (ex-data e))]
    (contains? passing-statuses status)
    (not (instance? clojure.lang.ExceptionInfo e))))

(defn- header [headers k]
  (some (fn [[h v]] (when (= k (str/lower-case (name h))) (str v))) headers))

(defn asked-wait-ms
  "How long the server asked to be left alone, from its headers: a
  Retry-After in seconds, or the x-ratelimit-reset reddit and others send.
  nil when it didn't say."
  [headers]
  (some-> (or (header headers "retry-after") (header headers "x-ratelimit-reset"))
          str/trim parse-double (* 1000) long (max 0)))

(defn- seconds [ms] (long (Math/ceil (/ ms 1000.0))))

(defn retrying
  "What `(attempt timeout-ms)` returns, tried again under `*policy*` when it
  fails for a passing reason: after the wait the server asked for and a
  second more, else
  :retry-wait-ms doubling with each try, and only while the wait leaves
  time before the source's deadline. Each try is given `timeout-ms`, or what
  is left before the deadline when that is less. A failure that can't be
  tried again is thrown as it was."
  [attempt timeout-ms]
  (let [{:keys [retries retry-wait-ms deadline emit]} *policy*
        left #(if deadline (- deadline (now)) Long/MAX_VALUE)]
    (loop [n 0]
      (let [r (try {:ok (attempt (max 1000 (min (or timeout-ms 30000) (left))))}
                   (catch Exception e
                     ;; a cancelled run interrupts this thread: stop, don't carry on
                     (if (instance? InterruptedException e) (throw e) {:error e})))]
        (if-not (contains? r :error)
          (:ok r)
          (let [e (:error r)
                ;; a server's reset is counted in whole seconds, rounded down,
                ;; so a second more is what gets past it
                wait (or (some-> (asked-wait-ms (:headers (ex-data e))) (+ 1000))
                         (* (or retry-wait-ms (:retry-wait-ms default-policy)) (bit-shift-left 1 n)))]
            (if (and (< n (or retries 0)) (passing? e) (< (+ wait 1000) (left)))
              (do (when emit
                    (emit {:text (str (ex-message e) ", trying again in " (seconds wait) "s")}))
                  (Thread/sleep wait)
                  (recur (inc n)))
              (throw e))))))))

(defn read-source
  "The items `source` has, as `fetch-items` gives them, read under its
  policy: its requests are retried within its :timeout-ms. Blocking; the
  caller still bounds it by the same :timeout-ms, which this only spends."
  [source ctx]
  (let [{:keys [timeout-ms] :as p} (policy source (:config ctx))]
    (binding [*policy* (assoc p :deadline (+ (now) timeout-ms) :emit (:emit ctx))]
      (vec (fetch-items source ctx)))))

(defn- get-once [url timeout-ms user-agent]
  (let [resp (http/get url {:headers {"User-Agent" (or user-agent default-user-agent)
                                      "Accept" "application/rss+xml, application/atom+xml, application/xml, text/xml, text/html, */*"}
                            :socket-timeout timeout-ms
                            :conn-timeout (min timeout-ms 15000)
                            :throw-exceptions false})]
    (if (<= 200 (:status resp) 299)
      (str (:body resp))
      (throw (ex-info (str "HTTP " (:status resp) " from " url)
                      {:status (:status resp) :headers (:headers resp)})))))

(defn fetch-text
  "The body of `url` as text, tried again under the source's policy when it
  fails for a passing reason; throws on a status other than 2xx, with the
  response's :status and :headers in the ex-data. :timeout-ms caps each
  try, which the source's deadline caps too."
  [url {:keys [timeout-ms user-agent]}]
  (retrying #(get-once url % user-agent) timeout-ms))

;; --- rss ---------------------------------------------------------------------------

(defn parse-xml
  "An XML document's text as a clojure.xml tree."
  [text]
  ;; libxml2 refuses a document with anything before the prolog
  (xml/parse (subs text (or (str/index-of text "<") 0))))

(def ^:private user-agent-field
  {:key :user-agent :type :string
   :doc "The User-Agent sent for this source, for a server that refuses clients it doesn't recognize."})

(defmethod shape :rss [_]
  {:doc "An RSS 2.0, RSS 1.0 (RDF) or Atom feed."
   :fields [{:key :url :type :string :required? true :doc "The feed's address."}
            user-agent-field]})

(defmethod fetch-items :rss [source ctx]
  (emit! ctx (str "Reading " (source-name source)) {:url (:url source)})
  (let [body (fetch-text (:url source) {:user-agent (:user-agent source)})
        doc (parse-xml body)]
    (feed/feed-items doc (source-name source))))

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

(defmethod shape :scrape [_]
  {:doc (str "A page with no feed: every link on it whose full URL matches the link pattern "
             "becomes a story, titled by its link text.")
   :fields [{:key :url :type :string :required? true :doc "The page to read the story links from."}
            {:key :link-pattern :type :string :required? true :regex? true
             :doc (str "A Java-style regex the full URL of a story link matches. Relative links are "
                       "resolved against the page first.")}
            {:key :limit :type :int :default 15 :doc "The most stories taken from the page."}
            {:key :summaries :type :boolean :default false
             :doc (str "Read each story's own page too, for the description and date in its meta tags. "
                       "That's one request per story, so keep the limit small.")}
            user-agent-field]})

(defmethod fetch-items :scrape [source ctx]
  (let [opts {:user-agent (:user-agent source)}
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
  (let [key (config/secret "EXA_API_KEY")
        url (if (str/blank? key) exa-endpoint (str exa-endpoint "?exaApiKey=" key))
        resp (http/post url {:headers {"Content-Type" "application/json"
                                       "Accept" "application/json, text/event-stream"}
                             :body (json/write-str (exa-envelope query n))
                             :socket-timeout timeout-ms
                             :conn-timeout (min timeout-ms 15000)
                             :throw-exceptions false})]
    (when-not (= 200 (:status resp))
      (throw (ex-info (str "web search answered " (:status resp))
                      {:status (:status resp) :headers (:headers resp)})))
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

(defn- search-once
  "One query's items, tried again under the source's policy, and empty when
  it fails, which is reported so the query costs only its own results."
  [ctx query n opts]
  (let [{:keys [items error]} (try {:items (retrying #(exa-search query n (assoc opts :timeout-ms %)) nil)}
                                   (catch Exception e
                                     (if (instance? InterruptedException e) (throw e) {:error e})))]
    (if error
      (do (emit! ctx (str "“" query "” failed: " (ex-message error)) {:level :error})
          nil)
      (do (emit! ctx (str (count items) " results for “" query "”"))
          items))))

(defmethod shape :web-search [_]
  {:doc (str "Searches through Exa. No key is needed, and EXA_API_KEY raises the rate limit. "
             "{{date}} in a query is the briefing's day written out, like 30 September 2026.")
   :fields [{:key :results :type :int :default 6 :doc "Results per query, at most 10."}
            {:key :queries :type :records :shorthand? true :default ["top world news {{date}}"]
             :doc "What to search for."
             :fields [{:key :query :type :string :required? true
                       :doc "The query; site: keeps it to one outlet."}
                      {:key :name :type :string
                       :doc "The outlet its results are credited to, rather than the search."}]}]})

(defmethod fetch-items :web-search [source {:keys [day] :as ctx}]
  ;; a query is a string, or {:query :name} to credit its results to an
  ;; outlet rather than to the search
  (let [n (:results source 6)
        results (mapv (fn [q]
                        (let [{:keys [query name]} (if (map? q) q {:query q})
                              query (str/replace query "{{date}}" (long-date day))]
                          (emit! ctx (str "Searching the web for “" query "”"))
                          (search-once ctx query n {:source (or name (source-name source))})))
                      (:queries source ["top world news {{date}}"]))]
    (if (and (seq results) (every? nil? results))
      (throw (ex-info "every search failed" {}))
      (vec (apply concat results)))))
