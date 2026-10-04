(ns newsroom.settings-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [newsroom.config :as config]
            [hiccup2.core :as h]
            [newsroom.config-page :as config-page]
            [newsroom.core :as core]
            [newsroom.news :as news]
            [newsroom.plugin :as plugin]
            [newsroom.settings :as settings]
            [newsroom.sources :as sources]
            [newsroom.store :as store]
            [newsroom.template :as template]
            [newsroom.ui :as ui]))

(def ^:private flat settings/form-params)

(defn- defaults [] (select-keys (config/defaults) settings/setting-keys))

(deftest the-defaults-survive-the-form
  (plugin/load-all! "plugins" {})
  (let [d (defaults)
        {:keys [settings errors]} (settings/from-form (flat (settings/to-form d)))]
    (is (= {} errors))
    (is (= (dissoc d :plugins) (dissoc settings :plugins)) "a lone query stays a string")
    (is (= {} (:plugins settings)))))

(deftest the-form-is-read-by-the-fields-types
  (let [{:keys [settings errors]}
        (settings/from-form {"max-items" " 90 " "dupe-threshold" "0.7" "run-at" ""
                             "digests.__" "1" "digests.month" "true"
                             "sources.7.type" "rss" "sources.7.name" "Feed" "sources.7.url" "https://e.com/rss"
                             "sources.2.type" "scrape" "sources.2.name" "Page" "sources.2.url" "https://e.com"
                             "sources.2.link-pattern" "/a/" "sources.2.limit" "5" "sources.2.summaries" "true"
                             "sources.2._extra" "{:custom 1}"
                             "sources.9.type" "web-search" "sources.9.queries.4.query" "b"
                             "sources.9.queries.1.query" "a" "sources.9.queries.1.name" "Outlet"
                             "sources.9.queries.3.query" ""
                             "providers.0._alias" "llama" "providers.0.type" "local" "providers.0.thinking?" "true"
                             "roles.analyst" "llama"})]
    (is (= {} errors))
    (is (= 90 (:max-items settings)))
    (is (= 0.7 (:dupe-threshold settings)))
    (is (contains? settings :run-at) "a blank nullable setting is turned off")
    (is (nil? (:run-at settings)))
    (is (not (contains? settings :keep-days)) "a blank one takes its default")
    (is (= [:month] (:digests settings)))
    (testing "a list is in the order of its indices, blank entries dropped"
      (is (= [{:type :scrape :name "Page" :url "https://e.com" :link-pattern "/a/" :limit 5 :summaries true
               :custom 1}
              {:type :rss :name "Feed" :url "https://e.com/rss"}
              {:type :web-search :queries [{:query "a" :name "Outlet"} "b"]}]
             (:sources settings))))
    (is (= {:llama {:type :local :thinking? true}} (:providers settings)))
    (is (= {:analyst :llama} (:roles settings)))))

(deftest what-is-wrong-is-said-by-field
  (let [{:keys [errors]}
        (settings/from-form {"max-items" "lots" "run-at" "7am" "dupe-threshold" "2"
                             "sources.0.type" "rss" "sources.0.name" "Same"
                             "sources.1.type" "rss" "sources.1.name" "Same" "sources.1.url" "https://e.com"
                             "sources.2.type" "scrape" "sources.2.url" "https://e.com" "sources.2.link-pattern" "("
                             "sources.3.type" "rss" "sources.3.url" "https://f.com" "sources.3._extra" "[1]"
                             "providers.0._alias" "" "providers.0.model" "m"
                             "providers.1._alias" "mystery"
                             "roles.analyst" "nobody"})]
    (is (= "has to be a whole number" (errors "max-items")))
    (is (= "isn't in the right form" (errors "run-at")))
    (is (= "has to be at most 1" (errors "dupe-threshold")))
    (is (= "is needed" (errors "sources.0.url")))
    (is (str/includes? (errors "sources.1.name") "another source") "names are one source's")
    (is (str/starts-with? (errors "sources.2.link-pattern") "isn't a regex"))
    (is (= "has to be a map, {:key value ...}" (errors "sources.3._extra")))
    (is (= "is needed" (errors "providers.0._alias")))
    (is (str/includes? (errors "providers.1.type") "needs a :type"))
    (is (= "names nobody, which no provider is" (errors "roles.analyst")))))

(deftest a-plugin-declares-its-shapes
  (plugin/load-all! "plugins" {})
  (is (= [:subreddit :url :limit :page-timeout-ms] (map :key (:fields (sources/shape :reddit)))))
  (is (= :strings (:type (first (:fields (sources/shape :reddit))))))
  (is (= [:user-agent] (map :key (:fields (plugin/settings-shape "reddit")))))
  (is (= [:token :workspace] (map :key (:fields (plugin/settings-shape :slack)))))
  (testing "a source of a plugin's type, and the plugin's settings, are read by their shapes"
    (let [{:keys [settings errors]}
          (settings/from-form {"sources.0.type" "reddit" "sources.0.subreddit.0" "technology"
                               "sources.0.subreddit.1" "worldnews" "sources.0.limit" "5"
                               "plugins.reddit.user-agent" "me"
                               "plugins.slack.workspace" "acme"})]
      (is (= [{:type :reddit :subreddit ["technology" "worldnews"] :limit 5}] (:sources settings)))
      (is (= {:user-agent "me"} (get-in settings [:plugins :reddit])))
      (is (= "is needed" (errors "plugins.slack.token")))))
  (testing "a shape is optional, and a plugin without one still loads"
    (let [dir (str (fs/create-temp-dir))]
      (io/make-parents (io/file dir "plain/core.clj"))
      (spit (io/file dir "plain/core.clj")
            (str "(ns plain.core (:require [newsroom.plugin :as plugin]))\n"
                 "(plugin/defsource :plain-test [source ctx] [])\n"))
      (plugin/load-all! dir {})
      (is (nil? (sources/shape :plain-test)))
      (is (= [] (sources/fetch-items {:type :plain-test} {:day "2026-09-30" :config {}}))))))

(deftest the-page-shows-every-setting-with-what-it-does
  (plugin/load-all! "plugins" {})
  (let [st (store/open "sqlite::memory:")]
    (try
      (let [page (ui/page st {:config {:tree (settings/to-form (assoc (defaults) :sources
                                                                      [{:type :reddit :subreddit ["technology" "economy"]}
                                                                       {:type :gone :name "Old" :x 1}]))}})]
        (is (str/includes? page "action=\"/config\""))
        (is (re-find #"src=\"/js/config.js\?v=[0-9a-f]+\"" page))
        (is (str/includes? page "How many days of briefings to keep") "a setting's description")
        (testing "a plugin's source has its declared fields"
          (is (str/includes? page "name=\"sources.0.subreddit.1\" type=\"text\" value=\"economy\""))
          (is (str/includes? page "r/technology+economy"))
          (is (str/includes? page "name=\"sources.__0__.subreddit.__1__\"") "a template for a new subreddit"))
        (testing "a new source picks its type in its own form, every type's fields put away until then"
          (is (str/includes? page "data-cfg-type-select=\"\" id=\"sources.__0__.type\" name=\"sources.__0__.type\""))
          (is (not (str/includes? page "aria-label=\"Type of source\"")) "no picker beside the button")
          (doseq [t ["rss" "scrape" "web-search" "reddit" "slack"]]
            (is (str/includes? page (str "<fieldset class=\"type-fields\" data-cfg-type=\"" t "\" disabled"))))
          (is (not (re-find #"<template[^>]*data-cfg-type" page)) "one template, not one a type")
          (is (str/includes? page "<span class=\"title\" data-cfg-default=\"New source\">New source</span>")
              "a title the name typed takes the place of"))
        (testing "a type no adapter reads keeps its keys as EDN"
          (is (str/includes? page "No adapter reads sources of type gone"))
          (is (str/includes? page "{:x 1}</textarea>")))
        (is (str/includes? page "name=\"plugins.slack.token\""))
        (is (str/includes? page "<main id=\"article\"><article") "only the sidebar is live")
        (testing "a form sent back is drawn as it was sent, errors and all"
          (let [page (ui/page st {:config {:tree (settings/form-tree {"max-items" "lots"})
                                           :errors {"max-items" "has to be a whole number"}}})]
            (is (str/includes? page "value=\"lots\""))
            (is (str/includes? page "Max items has to be a whole number"))
            (is (str/includes? page "1 field needs fixing")))))
      (finally (store/close st)))))

(deftest settings-in-files-are-moved-into-the-database
  (let [home (str (fs/create-temp-dir))
        st (store/open "sqlite::memory:")]
    (try
      (with-redefs [config/home (constantly home)]
        (spit (io/file home "config.edn") (pr-str {:port 4000 :max-items 50 :sources [{:type :rss :url "u"}]}))
        (spit (io/file home "prompt.md") "Mine: {{sources}}")
        (spit (io/file home "digest.md") (config/default-text "digest.md"))
        (let [file (config/read-file)
              imported (settings/import! st file)]
          (is (= #{:max-items :sources :prompt} (set imported)))
          (is (= {:max-items 50 :sources [{:type :rss :url "u"}] :prompt "Mine: {{sources}}"} (settings/stored st)))
          (testing "config.edn keeps the static keys, the old files kept aside"
            (is (= {:port 4000} (edn/read-string (slurp (io/file home "config.edn")))))
            (is (= 50 (:max-items (edn/read-string (slurp (io/file home "config.edn.bak"))))))
            (is (not (.exists (io/file home "prompt.md"))))
            (is (.exists (io/file home "prompt.md.bak")))
            (is (not (.exists (io/file home "digest.md")))))
          (testing "the config run with is the settings over the defaults, the static keys over both"
            (let [cfg (config/effective (config/read-file) (settings/stored st))]
              (is (= 4000 (:port cfg)))
              (is (= 50 (:max-items cfg)))
              (is (= 3 (:seen-days cfg)))
              (is (= "Mine: {{sources}}" (config/prompt-template cfg)))
              (is (= (config/default-text "digest.md") (config/digest-template cfg)))))
          (testing "a second start has nothing to move"
            (is (= [] (settings/import! st (config/read-file)))))))
      (finally (store/close st)))))

(deftest the-default-prompts-are-stored-to-edit
  (let [st (store/open "sqlite::memory:")]
    (try
      (store/save-settings! st {:max-items 50 :prompt "Mine: {{sources}}"})
      (is (= #{:digest-prompt :precedent-prompt} (set (settings/seed-prompts! st))))
      (let [stored (settings/stored st)]
        (is (= "Mine: {{sources}}" (:prompt stored)) "a prompt of the user's own is left alone")
        (is (= (config/default-text "digest.md") (:digest-prompt stored)))
        (is (= (config/default-text "precedents.md") (:precedent-prompt stored)))
        (is (= 50 (:max-items stored))))
      (testing "a second start has nothing to store"
        (is (= [] (settings/seed-prompts! st))))
      (finally (store/close st)))))

(deftest saving-the-form-runs-with-it-from-then-on
  (plugin/load-all! "plugins" {})
  (let [st (store/open "sqlite::memory:")
        cfg (config/effective {} {})
        post (fn [params]
               (core/app {:uri "/config" :request-method :post
                          :headers {"content-type" "application/x-www-form-urlencoded"}
                          :body (java.io.ByteArrayInputStream.
                                 (.getBytes (str/join "&" (for [[k v] params]
                                                            (str (java.net.URLEncoder/encode k "UTF-8") "="
                                                                 (java.net.URLEncoder/encode v "UTF-8"))))
                                            "UTF-8"))}))]
    (reset! core/system {:config cfg :store st :file {}})
    (try
      (let [form (assoc (flat (settings/to-form cfg))
                        "max-items" "42" "run-at" ""
                        "sources.99.type" "reddit" "sources.99.subreddit.0" "technology"
                        "plugins.reddit.user-agent" "agent")]
        (testing "a form with an error saves nothing"
          (let [resp (post (assoc form "max-items" "x"))]
            (is (= 422 (:status resp)))
            (is (str/includes? (:body resp) "Nothing was saved"))
            (is (= {} (settings/stored st)))))
        (let [resp (post form)]
          (is (= 303 (:status resp)))
          (is (= "/config?saved=1" (get-in resp [:headers "Location"])))
          (is (= 42 (:max-items (settings/stored st))))
          (is (= 42 (:max-items (:config @core/system))) "the config in memory is the saved one")
          (is (nil? (:run-at (:config @core/system))))
          (is (= {:type :reddit :subreddit ["technology"]} (last (:sources (:config @core/system)))))
          (is (= "agent" (:user-agent (plugin/config :reddit))) "and so are the plugins' settings")
          (is (str/includes? (:body (core/app {:uri "/config" :request-method :get :query-string "saved=1"}))
                             "Saved."))))
      (finally
        (reset! core/system nil)
        (store/close st)))))

(deftest settings-go-out-and-come-back-as-edn
  (plugin/load-all! "plugins" {})
  (testing "an export is config.edn's form, and imports as it was"
    (let [d (defaults)
          text (settings/export-edn d)
          {:keys [settings errors]} (settings/from-edn text)]
      (is (str/starts-with? text ";;"))
      (is (= {} errors))
      (is (= (dissoc d :plugins) (dissoc settings :plugins)) "lone queries stay strings")))
  (testing "an import names only what it changes, and a whole config.edn will do"
    (let [{:keys [settings errors]}
          (settings/from-edn (slurp (io/file "examples/config.edn")))]
      (is (= {} errors))
      (is (not (contains? settings :host)))
      (is (= :deepseek (get-in settings [:roles :analyst])))
      (is (some #(= {:type :web-search :name "Web search" :results 6
                     :queries ["top world news {{date}}" "global economy markets news {{date}}"
                               {:name "CCTV" :query "site:english.cctv.com news {{date}}"}]}
                    %)
                (:sources settings))))
    (is (= {:max-items 5} (:settings (settings/from-edn "{:max-items 5}")))))
  (testing "what is wrong is said, by where it is"
    (is (= {":bogus" "disallowed key" ":max-items" "should be an int"}
           (:errors (settings/from-edn "{:bogus 1 :max-items \"lots\"}"))))
    (is (= "has to be a map of settings, {:key value ...}" (get (:errors (settings/from-edn "[1]")) "EDN")))
    (is (str/starts-with? (get (:errors (settings/from-edn "{:a")) "EDN") "can't be read"))))

(deftest importing-on-the-page-saves-and-runs-with-it
  (plugin/load-all! "plugins" {})
  (let [st (store/open "sqlite::memory:")
        boundary "XyZ"
        upload (fn [text]
                 (core/app {:uri "/config/import" :request-method :post
                            :headers {"content-type" (str "multipart/form-data; boundary=" boundary)}
                            :body (java.io.ByteArrayInputStream.
                                   (.getBytes (str "--" boundary "\r\n"
                                                   "Content-Disposition: form-data; name=\"file\"; filename=\"s.edn\"\r\n"
                                                   "Content-Type: application/edn\r\n\r\n"
                                                   text "\r\n"
                                                   "--" boundary "\r\n"
                                                   "Content-Disposition: form-data; name=\"edn\"\r\n\r\n"
                                                   "\r\n--" boundary "--\r\n")
                                              "UTF-8"))}))]
    (reset! core/system {:config (config/effective {} {}) :store st :file {}})
    (try
      (let [resp (upload "{:max-items \"x\"}")]
        (is (= 422 (:status resp)))
        (is (str/includes? (:body resp) "Nothing was imported"))
        (is (= {} (settings/stored st))))
      (let [resp (upload "{:max-items 33 :port 1}")]
        (is (= 303 (:status resp)))
        (is (= 33 (:max-items (settings/stored st))))
        (is (= 33 (:max-items (:config @core/system))))
        (is (= 3 (:seen-days (settings/stored st))) "the rest are kept")
        (is (nil? (:port (settings/stored st)))))
      (let [resp (core/app {:uri "/config/export" :request-method :get})]
        (is (str/includes? (get-in resp [:headers "Content-Disposition"]) "attachment"))
        (is (= 33 (:max-items (edn/read-string (:body resp))))))
      (finally
        (reset! core/system nil)
        (store/close st)))))

(deftest an-import-is-checked-against-the-schema
  (plugin/load-all! "plugins" {})
  (let [errors #(:errors (settings/from-edn %))]
    (testing "each source by its type's shape"
      (is (= {":sources 0 :url" "missing required key"} (errors "{:sources [{:type :rss}]}")))
      (is (= {":sources 0 :link-pattern" "should be a regex"}
             (errors "{:sources [{:type :scrape :url \"u\" :link-pattern \"(\"}]}")))
      (is (contains? (errors "{:sources [{:type :reddit :subreddit 5}]}") ":sources 0 :subreddit"))
      (is (= {} (errors "{:sources [{:type :reddit :subreddit \"technology\"} {:type :unknown-type :x 1}]}"))
          "one subreddit or several, and a type no plugin declares is let through")
      (is (= {":sources 0 :type" "missing required key"} (errors "{:sources [{:url \"u\"}]}"))))
    (testing "the settings by their fields"
      (is (= {":max-items" "should be at least 1"} (errors "{:max-items 0}")))
      (is (= {":run-at" "isn't in the right form"} (errors "{:run-at \"7am\"}")))
      (is (= {} (errors "{:run-at nil :dupe-threshold nil}")) "a setting that may be off may be nil")
      (is (= {":digests 0" "should be either :week or :month"} (errors "{:digests [:day]}")))
      (is (contains? (errors "{:providers {:x {:type :nope}}}") ":providers :x :type"))
      (is (= {":plugins :slack :token" "missing required key"} (errors "{:plugins {:slack {:workspace \"a\"}}}"))))
    (testing "what the schema can't see, the form still does"
      (is (str/includes? (get (errors "{:sources [{:type :rss :name \"A\" :url \"u\"} {:type :rss :name \"A\" :url \"v\"}]}")
                              "sources.1.name")
                         "another source")))))

(deftest a-prompt-is-a-selmer-template
  (is (= {":prompt" "should be a Selmer template"} (:errors (settings/from-edn "{:prompt \"{% if x %}\"}"))))
  (is (str/starts-with? (get (:errors (settings/from-form {"prompt" "{% bogus %}"})) "prompt")
                        "isn't a template Selmer can read"))
  (testing "the default prompts are templates"
    (is (nil? (template/error (config/default-text "prompt.md"))))
    (is (nil? (template/error (config/default-text "digest.md")))))
  (testing "{% if %} shows a part only when there is something for it"
    (let [t "Today {{date}}.{% if previous %} Before: {{previous}}{% endif %}\n{{sources}}"]
      (is (= "Today d.\n" (news/render-prompt t "d" [] nil)))
      (is (= "Today d. Before: p\n" (news/render-prompt t "d" [] "p"))))))

(plugin/defsource :settings-test-ok
  {:fields [{:key :n :type :int :required? true}]}
  [source ctx]
  (plugin/emit! ctx "no luck with one page" {:level :error})
  (for [i (range (:n source))]
    (plugin/item source {:title (str "Story " i) :url (str "https://e.com/" i)})))

(plugin/defsource :settings-test-broken [_ _]
  (throw (ex-info "HTTP 404 from https://e.com/feed" {})))

(plugin/defsource :settings-test-slow [_ _]
  (Thread/sleep 5000)
  [])

(deftest a-source-can-be-tried-before-it-is-saved
  (let [try-it (fn [params] (core/test-source {:source-timeout-ms 300} params "sources.12"))]
    (testing "its fields are checked first"
      (is (= {:errors {"sources.12.n" "is needed"}} (try-it {"sources.12.type" "settings-test-ok"})))
      (is (= {:errors {"sources.12.type" "is needed"}} (try-it {}))))
    (testing "then it is read as a run would"
      (let [{:keys [items events name]} (try-it {"sources.12.type" "settings-test-ok" "sources.12.n" "3"
                                                  "sources.12.name" "Mine" "sources.4.n" "x"})]
        (is (= ["Story 0" "Story 1" "Story 2"] (map :title items)))
        (is (= "Mine" name))
        (is (= ["no luck with one page"] (map :text events)))
        (let [html (str (h/html (config-page/test-result {:items items :events events :name name} "2026-10-03")))]
          (is (str/includes? html "Read 3 items for 2026-10-03, credited to Mine."))
          (is (str/includes? html "no luck with one page")))))
    (testing "and what stops it is said"
      (is (= "HTTP 404 from https://e.com/feed" (:error (try-it {"sources.12.type" "settings-test-broken"}))))
      (is (str/includes? (:error (try-it {"sources.12.type" "settings-test-slow"})) "longer than")))
    (testing "the page has a test button on every source"
      (let [st (store/open "sqlite::memory:")
            page (try (ui/page st {:config {:tree (settings/to-form {:sources [{:type :rss :url "u"}]})}})
                      (finally (store/close st)))]
        (is (str/includes? page "data-cfg-test=\"sources.0\""))
        (is (str/includes? page "data-cfg-test=\"sources.__0__\""))))))
