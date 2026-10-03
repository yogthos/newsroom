(ns newsroom.sources-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.http-client :as http]
            [newsroom.sources :as sources]))

(defn- search-stub
  "exa-search that answers each query from `answers`: a seq of results, or
  an exception to throw, taken in turn."
  [answers calls]
  (fn [query _ {:keys [source]}]
    (swap! calls conj query)
    (let [a (get @answers query)]
      (swap! answers update query rest)
      (let [r (first a)]
        (if (instance? Throwable r)
          (throw r)
          (mapv #(hash-map :title % :url (str "https://e.com/" %) :source source) r))))))

(def ^:private rate-limited (ex-info "web search answered 429" {:status 429}))
(def ^:private down (ex-info "web search answered 500" {:status 500}))

(defn- run [source answers]
  (let [calls (atom []) events (atom [])]
    (with-redefs [sources/exa-search (search-stub (atom answers) calls)]
      (let [r (try (sources/read-source source {:day "2026-09-30" :config {:source-retry-wait-ms 1}
                                                :emit #(swap! events conj (:text %))})
                   (catch Exception e e))]
        {:result r :calls @calls :events @events}))))

(deftest a-rate-limited-query-is-tried-again
  (let [{:keys [result calls]} (run {:type :web-search :queries ["a"]}
                                    {"a" [rate-limited ["x"]]})]
    (is (= ["x"] (map :title result)))
    (is (= ["a" "a"] calls))))

(deftest an-overloaded-search-is-tried-again
  (let [{:keys [result calls]} (run {:type :web-search :queries ["a"]} {"a" [down ["x"]]})]
    (is (= ["x"] (map :title result)))
    (is (= ["a" "a"] calls))))

(deftest a-failed-query-costs-only-its-own-results
  (let [{:keys [result events]} (run {:type :web-search :name "Web" :queries ["a" "b" "c"]}
                                     {"a" [["x"]] "b" [down down] "c" [["y"]]})]
    (is (= ["x" "y"] (map :title result)))
    (is (some #(str/includes? % "“b” failed: web search answered 500") events))))

(deftest a-source-whose-every-query-fails-fails
  (let [{:keys [result]} (run {:type :web-search :queries ["a" "b"]}
                              {"a" [down down] "b" [rate-limited rate-limited]})]
    (is (instance? Exception result))
    (is (str/includes? (ex-message result) "every search failed"))))

(def ^:private front
  "<a href=\"/article/one-story-here\">The first story of the day</a>
   <a href=\"/article/two-story-here\">The second story of the day</a>
   <a href=\"/article/three-story-here\">The third story of the day</a>
   <a href=\"/about\">About this newspaper</a>")

(defn- article [desc day]
  (str "<meta property=\"og:description\" content=\"" desc "\">"
       "<meta property=\"article:published_time\" content=\"" day "T08:00:00Z\">"))

(deftest a-scraped-page-gives-its-story-links
  (let [fetched (atom [])]
    (with-redefs [sources/fetch-text (fn [url _] (swap! fetched conj url) front)]
      (let [items (sources/fetch-items {:type :scrape :name "Paper" :url "https://paper.test/world"
                                        :link-pattern "/article/" :limit 2}
                                       {:day "2026-09-30" :config {}})]
        (is (= ["https://paper.test/article/one-story-here" "https://paper.test/article/two-story-here"]
               (map :url items)))
        (is (every? #(= "Paper" (:source %)) items))
        (is (= ["https://paper.test/world"] @fetched) "only the page, without :summaries")))))

(defn- throw-later [] (fn [] (throw (ex-info "HTTP 403" {}))))

(deftest summaries-come-from-each-story-page
  (let [pages {"https://paper.test/world" front
               "https://paper.test/article/one-story-here" (article "One happened." "2026-09-30")
               "https://paper.test/article/two-story-here" (throw-later)}
        events (atom [])]
    (with-redefs [sources/fetch-text (fn [url _] (let [p (pages url)] (if (fn? p) (p) p)))]
      (let [items (sources/fetch-items {:type :scrape :name "Paper" :url "https://paper.test/world"
                                        :link-pattern "/article/" :limit 2 :summaries true}
                                       {:day "2026-09-30" :config {}
                                        :emit #(swap! events conj (:text %))})]
        (is (= ["One happened." ""] (map :summary items)))
        (is (= ["2026-09-30T08:00:00Z" nil] (map :published items)))
        (is (some #(str/includes? % "couldn't read") @events)
            "a story page that fails keeps its link and says so")))))

;; --- the policy --------------------------------------------------------------------

(defmethod sources/shape ::typed [_] {:policy {:timeout-ms 90000 :retries 3}})

(deftest a-source-is-read-by-its-own-policy-first
  (let [config {:source-timeout-ms 20000 :source-retries 2 :source-retry-wait-ms 500}]
    (is (= {:timeout-ms 30000 :retries 1 :retry-wait-ms 2000} (sources/policy {:type :rss} {}))
        "the defaults")
    (is (= {:timeout-ms 20000 :retries 2 :retry-wait-ms 500} (sources/policy {:type :rss} config))
        "the config's, for every source")
    (is (= {:timeout-ms 90000 :retries 3 :retry-wait-ms 500} (sources/policy {:type ::typed} config))
        "its type's over the config's")
    (is (= {:timeout-ms 5000 :retries 0 :retry-wait-ms 500}
           (sources/policy {:type ::typed :timeout-ms 5000 :retries 0} config))
        "its own over its type's, 0 included")))

(defn- answering
  "An http/get answering each request with the next of `responses`, a map
  or an exception to throw, counting them in `calls`."
  [responses calls]
  (let [left (atom responses)]
    (fn [_ _]
      (swap! calls inc)
      (let [r (first @left)]
        (swap! left rest)
        (if (instance? Throwable r) (throw r) r)))))

(defn- read-rss [policy responses]
  (let [calls (atom 0) events (atom [])]
    (with-redefs [http/get (answering responses calls)]
      {:result (try (sources/read-source (merge {:type :rss :name "Feed" :url "https://e.com/rss"} policy)
                                         {:day "2026-09-30" :config {}
                                          :emit #(swap! events conj (:text %))})
                    (catch Exception e (ex-message e)))
       :calls @calls
       :events @events})))

(def ^:private feed "<rss><channel><item><title>One</title><link>https://e.com/1</link></item></channel></rss>")

(deftest a-request-that-fails-for-a-passing-reason-is-tried-again
  (testing "after the wait the server asked for"
    (let [{:keys [result calls events]} (read-rss {:retries 1}
                                                  [{:status 429 :headers {"Retry-After" "0"} :body ""}
                                                   {:status 200 :body feed}])]
      (is (= ["One"] (map :title result)))
      (is (= 2 calls))
      (is (some #(str/includes? % "HTTP 429 from https://e.com/rss, trying again in 1s") events))))
  (testing "after its own wait when the server doesn't say, and when the server can't be reached"
    (let [{:keys [result calls]} (read-rss {:retries 2 :retry-wait-ms 1}
                                           [(java.io.IOException. "connection reset")
                                            {:status 503 :body ""}
                                            {:status 200 :body feed}])]
      (is (= ["One"] (map :title result)))
      (is (= 3 calls))))
  (testing "no more than its retries"
    (let [{:keys [result calls]} (read-rss {:retries 1 :retry-wait-ms 1}
                                           [{:status 502 :body ""} {:status 502 :body ""} {:status 200 :body feed}])]
      (is (= "HTTP 502 from https://e.com/rss" result))
      (is (= 2 calls)))))

(deftest a-request-is-not-tried-again-when-it-would-not-help
  (testing "an answer that won't change"
    (let [{:keys [result calls]} (read-rss {:retries 3 :retry-wait-ms 1} [{:status 404 :body ""}])]
      (is (= "HTTP 404 from https://e.com/rss" result))
      (is (= 1 calls))))
  (testing "a wait longer than the source has left"
    (let [{:keys [result calls]} (read-rss {:retries 3 :timeout-ms 5000}
                                           [{:status 429 :headers {"x-ratelimit-reset" "44"} :body ""}])]
      (is (= "HTTP 429 from https://e.com/rss" result))
      (is (= 1 calls))))
  (testing "a source that asks for none"
    (let [{:keys [calls]} (read-rss {:retries 0} [{:status 503 :body ""}])]
      (is (= 1 calls))))
  (testing "a request outside of a source's read is tried once"
    (let [calls (atom 0)]
      (with-redefs [http/get (answering [{:status 503 :body ""} {:status 200 :body feed}] calls)]
        (is (thrown? Exception (sources/fetch-text "https://e.com/rss" {}))))
      (is (= 1 @calls)))))
