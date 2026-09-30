(ns newsroom.plugin-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [jolt.http-client :as http]
            [newsroom.plugin :as plugin]
            [newsroom.sources :as sources]))

(defn- write! [root rel text]
  (let [f (io/file root rel)]
    (.mkdirs (.getParentFile f))
    (spit f text)))

(defn- plugins-dir
  "A fresh plugins directory holding `files`, {relative-path text}."
  [files]
  (let [root (str (fs/create-temp-dir))]
    (doseq [[rel text] files] (write! root rel text))
    root))

(defn- by-name [report] (into {} (map (juxt :plugin identity)) report))

(deftest every-namespace-in-a-plugin-folder-is-loaded
  (let [dir (plugins-dir
             {"alpha/core.clj" "(ns alpha.core (:require [alpha.util :as u]))\n(def answer (u/x))\n"
              "alpha/util.clj" "(ns alpha.util)\n(defn x [] 42)\n"
              "alpha/deep/more.clj" "(ns alpha.deep.more)\n(def here true)\n"})
        report (by-name (plugin/load-all! dir {}))]
    (is (:ok (report "alpha")))
    (is (= '[alpha.core alpha.deep.more alpha.util] (:namespaces (report "alpha"))))
    (is (= 42 @(resolve 'alpha.core/answer)))
    (is @(resolve 'alpha.deep.more/here))))

(deftest a-broken-plugin-does-not-stop-the-others
  (let [dir (plugins-dir
             {"broken/core.clj" "(ns broken.core)\n(throw (ex-info \"boom\" {}))\n"
              "fine/core.clj" "(ns fine.core)\n(def ok 1)\n"})
        report (by-name (plugin/load-all! dir {}))]
    (is (false? (:ok (report "broken"))))
    (is (str/includes? (:error (report "broken")) "boom"))
    (is (:ok (report "fine")))
    (is (= 1 @(resolve 'fine.core/ok)))))

(deftest a-plugin-reads-its-own-config
  (let [dir (plugins-dir
             {"gamma/core.clj" (str "(ns gamma.core (:require [newsroom.plugin :as plugin]))\n"
                                    "(def at-load (plugin/config))\n")})
        cfg {:plugins {:gamma {:token "t" :home "${HOME}"} :other {:x 1}}}]
    (plugin/load-all! dir cfg)
    (testing "while loading, under the key named after its folder"
      (is (= "t" (:token @(resolve 'gamma.core/at-load)))))
    (testing "${VAR} is read from the environment"
      (is (= (jolt.host/getenv "HOME") (:home (plugin/config :gamma)))))
    (testing "by name, any time after"
      (is (= {:x 1} (plugin/config "other")))
      (is (nil? (plugin/config :missing))))))

(deftest an-unset-variable-is-named
  (plugin/load-all! (plugins-dir {}) {:plugins {:delta {:token "${NEWSROOM_TEST_UNSET_VAR}"}}})
  (let [e (try (plugin/config :delta) nil (catch Exception e e))]
    (is (str/includes? (ex-message e) "NEWSROOM_TEST_UNSET_VAR"))
    (is (str/includes? (ex-message e) "delta"))))

(deftest a-single-file-is-a-plugin-too
  (let [dir (plugins-dir {"solo.clj" "(ns solo)\n(def here true)\n"})
        report (by-name (plugin/load-all! dir {}))]
    (is (:ok (report "solo")))
    (is @(resolve 'solo/here))))

(deftest defsource-adds-a-source-type
  (let [dir (plugins-dir
             {"eps/core.clj"
              (str "(ns eps.core (:require [newsroom.plugin :as plugin]))\n"
                   "(plugin/defsource :eps-test [source ctx]\n"
                   "  (plugin/emit! ctx \"reading\")\n"
                   "  [(plugin/item source {:title \" <b>Hi</b> \" :url \"https://e.com/1\"\n"
                   "                        :summary (apply str (repeat 700 \"a \"))})])\n")})]
    (plugin/load-all! dir {})
    (let [events (atom [])
          [item] (sources/fetch-items {:type :eps-test :name "Eps"}
                                      {:day "2026-09-30" :config {}
                                       :emit #(swap! events conj (:text %))})]
      (is (= ["reading"] @events))
      (is (= {:title "Hi" :url "https://e.com/1" :source "Eps" :published nil}
             (dissoc item :summary)))
      (is (<= (count (:summary item)) 600)))))

(deftest get-json-reads-an-answer-and-throws-on-a-failure
  (let [seen (atom nil)]
    (with-redefs [http/request (fn [req]
                                 (reset! seen req)
                                 (if (str/ends-with? (:url req) "/bad")
                                   {:status 403 :body "{\"error\":\"nope\"}"}
                                   {:status 200 :body "{\"ok\":true,\"n\":[1,2]}"}))]
      (is (= {:ok true :n [1 2]}
             (plugin/get-json "https://api.e.com/x" {:query-params {"a" "b"}
                                                      :headers {"Authorization" "Bearer k"}})))
      (is (= :get (:request-method @seen)))
      (is (= {"a" "b"} (:query-params @seen)))
      (is (= "Bearer k" (get-in @seen [:headers "Authorization"])))
      (is (= {:x 1} (json/read-str (:body (do (plugin/post-json "https://api.e.com/y" {:x 1}) @seen))
                                   :key-fn keyword)))
      (let [e (try (plugin/get-json "https://api.e.com/bad") nil (catch Exception e e))]
        (is (= 403 (:status (ex-data e))))))))

;; --- the example plugin ------------------------------------------------------------

(deftest the-example-slack-plugin-reads-a-channel
  (let [report (by-name (plugin/load-all! "examples/plugins"
                                          {:plugins {:slack {:token "xoxb-1" :workspace "acme"}}}))
        seen (atom nil)]
    (is (:ok (report "slack")) (:error (report "slack")))
    (with-redefs [http/request
                  (fn [req]
                    (reset! seen req)
                    {:status 200
                     :body (json/write-str
                            {:ok true
                             :messages [{:type "message" :ts "1790726400.000100"
                                         :text "Tariffs up again\nDetails in the thread <https://e.com|here>"}
                                        {:type "message" :subtype "channel_join" :ts "1790726300.000100"
                                         :text "<@U1> has joined the channel"}]})})]
      (let [items (sources/fetch-items {:type :slack :name "Slack #news" :channel "C123"}
                                       {:day "2026-09-30" :config {}})]
        (is (= "Bearer xoxb-1" (get-in @seen [:headers "Authorization"])))
        (is (= "C123" (get-in @seen [:query-params "channel"])))
        (is (= [{:title "Tariffs up again"
                 :url "https://acme.slack.com/archives/C123/p1790726400000100"
                 :source "Slack #news"
                 :summary "Tariffs up again Details in the thread here"
                 :published "2026-09-30T00:00:00Z"}]
               items))))))
