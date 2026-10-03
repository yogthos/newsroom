(ns reddit.core
  "A subreddit as a source, read for the stories its posts link to rather
  than for the posts: each item is the linked article, titled and summed up
  from the article's own page. Posts that link back to reddit (text posts,
  images, videos) are left out.

  Copy this folder to plugins/reddit in the config directory, and add a
  source of type reddit on the config page. As data, a source is

    {:type :reddit :subreddit [\"technology\" \"worldnews\"]}
    ;; or any reddit feed, like a day's top posts
    {:type :reddit :name \"Top in r/worldnews\"
     :url \"https://www.reddit.com/r/worldnews/top.rss?t=day\"}

  Reddit lets a client read about one feed a minute without logging in, so
  more than one reddit source in a run gets turned away. Subreddits listed
  in one source are read together, in a single request, which is the way
  to follow several.

  :limit caps the stories read from a feed, 10 by default. Each story is
  credited to its subreddit, or to the source's :name when it has one. The
  article pages are read in parallel, each allowed :page-timeout-ms
  (10000), which has to fit in the run's :source-timeout-ms.

  Nothing is needed in the plugin's settings, though reddit turns some user
  agents away, so one can be set there:

    :plugins {:reddit {:user-agent \"newsroom/0.4 (by /u/you)\"}}"
  (:require [clojure.string :as str]
            [newsroom.plugin :as plugin]))

(defn- tag [el] (some-> (:tag el) name (str/replace #"^.*:" "")))

(defn- children [el tag-name] (filter #(and (map? %) (= tag-name (tag %))) (:content el)))

(defn- text-of [el tag-name]
  (some->> (children el tag-name) first :content (filter string?) (apply str) str/trim not-empty))

(defn- linked-url
  "The URL a post's [link] points at. The content is the post's HTML, so
  the href still has its &amp; escapes."
  [content]
  (some-> (re-find #"<a href=\"([^\"]+)\">\s*\[link\]\s*</a>" (str content))
          second
          (str/replace "&amp;" "&")))

(defn- on-reddit? [url]
  (boolean (re-find #"^https?://([^/]+\.)?(reddit\.com|redd\.it)(/|$)" url)))

(defn posts
  "The posts in a parsed reddit feed that link off reddit, in feed order,
  each {:title :url :published :subreddit}, once per link."
  [doc]
  (->> (tree-seq map? :content doc)
       (filter #(= "entry" (tag %)))
       (keep (fn [e]
               (when-let [url (linked-url (text-of e "content"))]
                 (when-not (on-reddit? url)
                   {:title (text-of e "title")
                    :url url
                    :subreddit (some-> (children e "category") first :attrs :label)
                    :published (or (text-of e "published") (text-of e "updated"))}))))
       (reduce (fn [[seen out] p]
                 (if (seen (:url p)) [seen out] [(conj seen (:url p)) (conj out p)]))
               [#{} []])
       second))

(defn- story
  "A post as an item, from the page it links to when that can be read, and
  from the post alone when it can't."
  [source ctx opts post]
  (let [meta (try
               (plugin/page-meta (plugin/fetch-text (:url post) opts))
               (catch Exception e
                 (when (instance? InterruptedException e) (throw e))
                 (plugin/emit! ctx (str "couldn't read " (:url post) ": " (ex-message e))
                               {:level :error :url (:url post)})
                 nil))]
    (plugin/item source {:title (or (:title meta) (:title post))
                         :url (:url post)
                         :source (when-not (:name source) (:subreddit post))
                         :summary (:description meta)
                         :published (or (:published meta) (:published post))})))

(defn- subreddits
  "A source's :subreddit, one name or several, as reddit writes a
  combination of them: technology+worldnews."
  [source]
  (let [s (:subreddit source)]
    (if (coll? s) (str/join "+" s) s)))

(defn- read-feed [url opts]
  (try
    (plugin/fetch-text url opts)
    (catch Exception e
      (if (= 429 (:status (ex-data e)))
        (throw (ex-info (str "HTTP 429 from " url ": reddit allows about one feed a minute, so"
                             " put the subreddits in one source, as :subreddit [\"a\" \"b\"]")
                        (ex-data e)))
        (throw e)))))

(plugin/defsettings
  {:doc "Nothing is needed here."
   :fields [{:key :user-agent :type :string
             :doc "The User-Agent sent to reddit, which turns some away, like newsroom/0.4 (by /u/you)."}]})

(plugin/defname :reddit [source]
  (if (:subreddit source) (str "r/" (subreddits source)) (:url source)))

(plugin/defsource :reddit
  {:doc (str "Subreddits, read for the stories their posts link to. Reddit lets a client read about "
             "one feed a minute, so list every subreddit in one source.")
   :fields [{:key :subreddit :type :strings :label "Subreddits"
             :doc "The subreddits to read, together, in one request."}
            {:key :url :type :string
             :doc "Any reddit feed instead, like https://www.reddit.com/r/worldnews/top.rss?t=day."}
            {:key :limit :type :int :default 10 :doc "The most stories read from the feed."}
            {:key :page-timeout-ms :type :int :default 10000
             :doc "How long each article page may take, which has to fit in the source timeout."}]}
  [source ctx]
  (let [{:keys [user-agent]} (plugin/config :reddit)
        url (or (:url source) (str "https://www.reddit.com/r/" (subreddits source) ".rss"))
        opts {:timeout-ms (:page-timeout-ms source 10000) :user-agent user-agent}]
    (plugin/emit! ctx (str "Reading " (plugin/source-name source)) {:url url})
    (let [found (take (:limit source 10) (posts (plugin/parse-xml (read-feed url opts))))]
      (plugin/emit! ctx (str "Following " (count found) " links from " (plugin/source-name source)))
      (->> found
           (mapv #(future (story source ctx opts %)))
           (mapv deref)))))
