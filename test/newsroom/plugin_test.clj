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

(deftest a-folder-with-no-clojure-is-not-a-plugin
  (testing "like plugins/speech/, which holds the speech engine's libraries"
    (let [dir (plugins-dir {"speech/libnewsroom_tts_mini.so" "not clojure"
                            "speech/espeak-ng-data/phontab" "data"
                            "fine/core.clj" "(ns fine.core)\n(def ok 1)\n"})
          report (by-name (plugin/load-all! dir {}))]
      (is (= #{"fine"} (set (keys report)))))))

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
  (let [report (by-name (plugin/load-all! "plugins"
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

;; --- the reddit plugin -------------------------------------------------------------

(defn- entry [id title link published & [sub]]
  (str "<entry><id>" id "</id><title>" title "</title>"
       "<category term=\"" (or sub "technology") "\" label=\"r/" (or sub "technology") "\"/>"
       "<content type=\"html\">"
       (-> (str "<table><tr><td> submitted by <a href=\"https://www.reddit.com/user/x\"> /u/x </a><br/>"
                "<span><a href=\"" link "\">[link]</a></span> "
                "<span><a href=\"https://www.reddit.com/r/technology/comments/" id "/\">[comments]</a></span>"
                "</td></tr></table>")
           (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;") (str/replace "\"" "&quot;"))
       "</content>"
       "<link href=\"https://www.reddit.com/r/technology/comments/" id "/\" />"
       "<published>" published "</published></entry>"))

(def ^:private reddit-feed
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?><feed xmlns=\"http://www.w3.org/2005/Atom\">"
       "<title>/r/Technology</title>"
       (entry "t3_a" "Reddit's own words about it" "https://news.e.com/story?a=1&amp;b=2" "2026-09-30T13:12:35+00:00")
       (entry "t3_b" "Ask r/technology: what now?" "https://www.reddit.com/r/technology/comments/t3_b/" "2026-09-30T12:00:00+00:00")
       (entry "t3_c" "A paywalled one" "https://paywall.e.com/x" "2026-09-30T11:00:00+00:00")
       (entry "t3_d" "The first story again" "https://news.e.com/story?a=1&amp;b=2" "2026-09-30T10:00:00+00:00")
       "</feed>"))

(def ^:private article
  (str "<html><head><meta property=\"og:title\" content=\"The story's own headline\">"
       "<meta property=\"og:description\" content=\"What happened, in the outlet's words.\">"
       "<meta property=\"article:published_time\" content=\"2026-09-30T09:00:00Z\"></head></html>"))

(deftest the-reddit-plugin-follows-links-to-the-stories
  (let [report (by-name (plugin/load-all! "plugins" {}))
        fetched (atom [])]
    (is (:ok (report "reddit")) (:error (report "reddit")))
    (with-redefs [http/get (fn [url _]
                             (swap! fetched conj url)
                             (cond
                               (= url "https://www.reddit.com/r/technology.rss") {:status 200 :body reddit-feed}
                               (str/starts-with? url "https://news.e.com/") {:status 200 :body article}
                               :else {:status 403 :body "no"}))]
      (let [events (atom [])
            items (sources/fetch-items {:type :reddit :subreddit "technology"}
                                       {:day "2026-09-30" :config {}
                                        :emit #(swap! events conj %)})]
        (testing "the story's page, not the reddit thread"
          (is (= {:title "The story's own headline"
                  :url "https://news.e.com/story?a=1&b=2"
                  :source "r/technology"
                  :summary "What happened, in the outlet's words."
                  :published "2026-09-30T09:00:00Z"}
                 (first items))))
        (testing "a page that can't be read keeps the post's title and date"
          (is (= {:title "A paywalled one"
                  :url "https://paywall.e.com/x"
                  :source "r/technology"
                  :summary ""
                  :published "2026-09-30T11:00:00+00:00"}
                 (second items))))
        (testing "self posts and repeated links are left out"
          (is (= 2 (count items)))
          (is (= 1 (count (filter #(str/starts-with? % "https://news.e.com/") @fetched)))))))))

(deftest a-reddit-source-is-named-after-its-subreddit
  (plugin/load-all! "plugins" {})
  (is (= "r/technology" (sources/source-name {:type :reddit :subreddit "technology"})))
  (is (= "Mine" (sources/source-name {:type :reddit :subreddit "technology" :name "Mine"})))
  (is (= "https://www.reddit.com/r/a+b.rss"
         (sources/source-name {:type :reddit :url "https://www.reddit.com/r/a+b.rss"}))))

(deftest the-reddit-plugin-reads-a-feed-url-too
  (let [_ (plugin/load-all! "plugins" {:plugins {:reddit {:user-agent "my-agent"}}})
        seen (atom [])]
    (with-redefs [http/get (fn [url req]
                             (swap! seen conj [url (get-in req [:headers "User-Agent"])])
                             {:status 200 :body "<feed xmlns=\"http://www.w3.org/2005/Atom\"></feed>"})]
      (is (= [] (sources/fetch-items {:type :reddit :name "Top tech"
                                      :url "https://www.reddit.com/r/technology/top.rss?t=day"}
                                     {:day "2026-09-30" :config {}})))
      (is (= [["https://www.reddit.com/r/technology/top.rss?t=day" "my-agent"]] @seen)))))

(deftest several-subreddits-are-read-in-one-request
  (plugin/load-all! "plugins" {})
  (let [seen (atom [])
        feed (str "<feed xmlns=\"http://www.w3.org/2005/Atom\">"
                  (entry "t3_w" "World" "https://w.e.com/1" "2026-09-30T10:00:00+00:00" "worldnews")
                  (entry "t3_t" "Tech" "https://t.e.com/1" "2026-09-30T10:00:00+00:00" "technology")
                  "</feed>")
        source {:type :reddit :subreddit ["worldnews" "technology"]}]
    (with-redefs [http/get (fn [url _]
                             (swap! seen conj url)
                             (if (str/includes? url "reddit.com")
                               {:status 200 :body feed}
                               {:status 404 :body ""}))]
      (is (= "r/worldnews+technology" (sources/source-name source)))
      (is (= [["World" "r/worldnews"] ["Tech" "r/technology"]]
             (map (juxt :title :source) (sources/fetch-items source {:day "2026-09-30" :config {}})))
          "each story is credited to its own subreddit")
      (is (= 1 (count (filter #(str/includes? % "reddit.com") @seen))))
      (is (= "https://www.reddit.com/r/worldnews+technology.rss" (first @seen))))
    (testing "a source with a :name credits its stories to that"
      (with-redefs [http/get (fn [url _] {:status (if (str/includes? url "reddit.com") 200 404) :body feed})]
        (is (= #{"Mine"} (set (map :source (sources/fetch-items (assoc source :name "Mine")
                                                                {:day "2026-09-30" :config {}})))))))))

(deftest a-rate-limited-reddit-feed-says-what-to-do
  (plugin/load-all! "plugins" {})
  (let [fail (fn [source headers]
               (with-redefs [http/get (fn [_ _] {:status 429 :body "" :headers headers})]
                 (try (sources/fetch-items source {:day "2026-09-30" :config {}})
                      nil (catch Exception e (ex-message e)))))]
    (testing "when to try again, from what reddit says"
      (let [msg (fail {:type :reddit :subreddit ["technology" "worldnews"]} {"x-ratelimit-reset" "22"})]
        (is (str/includes? msg "429"))
        (is (str/includes? msg "try again in 22s"))
        (is (not (str/includes? msg "one source")) "its subreddits are in one source already")))
    (testing "a source of one subreddit is told several go in one"
      (let [msg (fail {:type :reddit :subreddit "technology"} {})]
        (is (str/includes? msg "try again in a minute"))
        (is (str/includes? msg "one source"))))))

(deftest a-rate-limited-reddit-feed-is-read-once-reddit-lets-it
  (plugin/load-all! "plugins" {})
  (is (= {:timeout-ms 90000 :retries 1 :retry-wait-ms 2000} (sources/policy {:type :reddit} {}))
      "time enough by default to sit out reddit's reset")
  (let [calls (atom [])
        feed (str "<feed xmlns=\"http://www.w3.org/2005/Atom\">"
                  (entry "t3_w" "World" "https://w.e.com/1" "2026-09-30T10:00:00+00:00" "worldnews")
                  "</feed>")]
    (with-redefs [http/get (fn [url _]
                             (swap! calls conj url)
                             (cond
                               (not (str/includes? url "reddit.com")) {:status 404 :body ""}
                               (= 1 (count (filter #(str/includes? % "reddit.com") @calls)))
                               {:status 429 :headers {"x-ratelimit-reset" "0"} :body ""}
                               :else {:status 200 :body feed}))]
      (is (= ["World"] (map :title (sources/read-source {:type :reddit :subreddit ["worldnews"]}
                                                        {:day "2026-09-30" :config {}}))))
      (is (= 2 (count (filter #(str/includes? % "reddit.com") @calls)))))))

;; --- the youtube plugin ------------------------------------------------------------

(defn- yt-entry [id title published & [short?]]
  (str "<entry><id>yt:video:" id "</id><yt:videoId>" id "</yt:videoId>"
       "<title>" title "</title>"
       "<link rel=\"alternate\" href=\"https://www.youtube.com/" (if short? "shorts/" "watch?v=") id "\"/>"
       "<author><name>Chan</name></author>"
       "<published>" published "</published>"
       "<media:group><media:description>What it's about.\n\nSponsored by https://e.com #ad</media:description></media:group>"
       "</entry>"))

(def ^:private yt-feed
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
       "<feed xmlns:yt=\"http://www.youtube.com/xml/schemas/2015\" xmlns:media=\"http://search.yahoo.com/mrss/\""
       " xmlns=\"http://www.w3.org/2005/Atom\"><title>Chan</title><author><name>Chan</name></author>"
       (yt-entry "short1" "A short" "2026-09-30T12:00:00+00:00" true)
       (yt-entry "new1" "The latest" "2026-09-29T12:00:00+00:00")
       (yt-entry "new2" "The one before" "2026-09-28T12:00:00+00:00")
       (yt-entry "old1" "Last week's" "2026-09-20T12:00:00+00:00")
       "</feed>"))

(def ^:private timedtext
  (str "<?xml version=\"1.0\" encoding=\"utf-8\" ?><transcript>"
       "<text start=\"0.1\" dur=\"2\">Today we&amp;#39;re looking</text>"
       "<text start=\"2\" dur=\"2\">at &amp;quot;tariffs&amp;quot;.</text></transcript>"))

(defn- youtube-http
  "http/get and http/request answering as YouTube does, recording the
  requests in `seen`."
  [seen & [{:keys [tracks] :or {tracks [{:languageCode "de" :baseUrl "https://yt.e/de"}
                                         {:languageCode "en" :kind "asr" :baseUrl "https://yt.e/asr&fmt=srv3"}
                                         {:languageCode "en-GB" :baseUrl "https://yt.e/en&fmt=srv3"}]}}]]
  {:get (fn [url _]
          (swap! seen conj url)
          (cond
            (= url "https://www.youtube.com/@chan")
            {:status 200 :body "<link rel=\"canonical\" href=\"https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa\">"}
            (str/includes? url "channel_id=UCaaaaaaaaaaaaaaaaaaaaaa") {:status 200 :body yt-feed}
            (= url "https://yt.e/en") {:status 200 :body timedtext}
            :else {:status 404 :body ""}))
   :request (fn [req]
              (swap! seen conj (json/read-str (:body req) :key-fn keyword))
              {:status 200 :body (json/write-str {:captions {:playerCaptionsTracklistRenderer {:captionTracks tracks}}})})})

(deftest the-youtube-plugin-reads-the-latest-videos-transcripts
  (let [report (by-name (plugin/load-all! "plugins" {}))
        seen (atom [])
        {:keys [get request]} (youtube-http seen)]
    (is (:ok (report "youtube")) (:error (report "youtube")))
    (is (= 3 (:lookback-days (sources/shape :youtube))))
    (with-redefs [http/get get http/request request]
      (let [items (sources/fetch-items {:type :youtube :channels ["@chan"] :videos 2}
                                       {:day "2026-09-30" :config {}})]
        (testing "the latest videos of the last days, Shorts and older ones left out"
          (is (= ["The latest" "The one before"] (map :title items))))
        (testing "a video is credited to its channel, with its transcript as its text"
          (is (= {:title "The latest"
                  :url "https://www.youtube.com/watch?v=new1"
                  :source "Chan"
                  :summary "What it's about. Today we're looking at \"tariffs\"."
                  :published "2026-09-29T12:00:00+00:00"
                  :text "Transcript of the video on Chan:\nToday we're looking at \"tariffs\"."}
                 (first items))))
        (testing "the captions in the language asked for, people's over YouTube's, without srv3"
          (is (some #{"https://yt.e/en"} @seen))
          (is (not-any? #{"https://yt.e/asr" "https://yt.e/de"} @seen)))
        (testing "the channel's handle is looked up once"
          (sources/fetch-items {:type :youtube :channels ["@chan"]} {:day "2026-09-30" :config {}})
          (is (= 1 (count (filter #{"https://www.youtube.com/@chan"} @seen)))))))))

(deftest a-youtube-video-without-captions-is-still-an-item
  (plugin/load-all! "plugins" {:plugins {:youtube {:client-version "99.0"}}})
  (let [seen (atom [])
        {:keys [get request]} (youtube-http seen {:tracks []})]
    (with-redefs [http/get get http/request request]
      (let [[item :as items] (sources/fetch-items {:type :youtube :name "Mine" :channels ["UCaaaaaaaaaaaaaaaaaaaaaa"]}
                                                  {:day "2026-09-30" :config {}})]
        (is (= 1 (count items)))
        (is (= {:title "The latest" :source "Mine" :summary "What it's about."}
               (select-keys item [:title :source :summary :text])))
        (is (= "99.0" (get-in (first (filter map? @seen)) [:context :client :clientVersion]))
            "the app version from the settings")
        (is (not-any? #{"https://www.youtube.com/@chan"} @seen) "an ID needs no lookup")))))

(deftest a-youtube-channel-that-fails-costs-only-itself
  (plugin/load-all! "plugins" {})
  (let [{:keys [get request]} (youtube-http (atom []))
        events (atom [])]
    (with-redefs [http/get get http/request request]
      (is (= ["The latest"]
             (map :title (sources/fetch-items {:type :youtube :channels ["@gone" "@chan"]}
                                              {:day "2026-09-30" :config {} :emit #(swap! events conj (:text %))}))))
      (is (some #(str/includes? % "couldn't read the channel @gone") @events))
      (is (thrown-with-msg? Exception #"404"
                            (sources/fetch-items {:type :youtube :channels ["@gone"]} {:day "2026-09-30" :config {}}))
          "and fails the source when it's the only one"))))

(deftest the-youtube-plugin-picks-captions-and-reads-pages
  (plugin/load-all! "plugins" {})
  (let [pick @(resolve 'youtube.core/pick-track)
        page-id @(resolve 'youtube.core/page-channel-id)]
    (is (= "fr" (:languageCode (pick [{:languageCode "fr" :kind "asr"} {:languageCode "de" :kind "asr"}] ["fr" "en"]))))
    (is (= "de" (:languageCode (pick [{:languageCode "fr" :kind "asr"} {:languageCode "de"}] ["en"])))
        "with none in the languages asked for, people's captions before YouTube's")
    (is (nil? (pick [] ["en"])))
    (is (= "UCbbbbbbbbbbbbbbbbbbbbbb" (page-id "x\"externalId\":\"UCbbbbbbbbbbbbbbbbbbbbbb\"y")))
    (is (nil? (page-id "<html>consent</html>")))))
