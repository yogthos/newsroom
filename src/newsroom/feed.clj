(ns newsroom.feed
  "What a source hands over, turned into items: RSS 2.0, RSS 1.0 (RDF) and
  Atom documents as clojure.xml trees, web search results as Exa's text
  blocks, and the story links on a web page. Pure: the fetching is
  newsroom.sources'.

  An item is {:title :url :source :summary :published}, every value a string
  but :published, which is the feed's own date text or nil."
  (:require [clojure.string :as str]))

;; --- text --------------------------------------------------------------------------

(def ^:private entities
  {"amp" "&" "lt" "<" "gt" ">" "quot" "\"" "apos" "'" "nbsp" " "
   "ndash" "–" "mdash" "—" "hellip" "…" "rsquo" "’" "lsquo" "‘"
   "rdquo" "”" "ldquo" "“"})

(defn- decode-entity [[whole body]]
  (cond
    (str/starts-with? body "#x") (str (char (Long/parseLong (subs body 2) 16)))
    (str/starts-with? body "#") (or (some-> (parse-long (subs body 1)) int char str) whole)
    :else (get entities body whole)))

(defn plain-text
  "Markup reduced to what a reader sees: tags dropped, entities decoded,
  whitespace collapsed."
  [s]
  (-> (str s)
      (str/replace #"(?s)<!--.*?-->" " ")
      (str/replace #"<[^>]*>" " ")
      (str/replace #"&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);" decode-entity)
      (str/replace #"\s+" " ")
      str/trim))

(defn clip
  "At most n characters of s, cut at a word where one is near."
  [s n]
  (let [s (str s)]
    (if (<= (count s) n)
      s
      (let [cut (subs s 0 n)
            space (str/last-index-of cut " ")]
        (if (and space (> space (* 0.7 n))) (subs cut 0 space) cut)))))

(def summary-chars 600)

;; --- xml ---------------------------------------------------------------------------

(defn- local-name
  "An element's tag without its namespace prefix: dc:date is date."
  [el]
  (let [t (name (:tag el))
        i (str/last-index-of t ":")]
    (if i (subs t (inc i)) t)))

(defn- children [el] (filter map? (:content el)))

(defn- child [el tag] (first (filter #(= tag (local-name %)) (children el))))

(defn- text-of
  "Every piece of text under an element, in order."
  [el]
  (cond
    (string? el) el
    (map? el) (apply str (map text-of (:content el)))
    :else ""))

(defn- child-text [el & tags]
  (some (fn [tag] (some-> (child el tag) text-of str/trim not-empty)) tags))

(defn- atom-link
  "An Atom entry's link to the story: the alternate one, or one with no rel."
  [entry]
  (let [links (filter #(= "link" (local-name %)) (children entry))
        alternate (first (filter #(contains? #{nil "alternate"} (get-in % [:attrs :rel])) links))]
    (some-> (or alternate (first links)) :attrs :href str/trim)))

(defn- rss-link [item]
  (or (child-text item "link")
      (let [guid (child item "guid")]
        (when (and guid (not= "false" (get-in guid [:attrs :isPermaLink])))
          (let [g (str/trim (text-of guid))]
            (when (str/starts-with? g "http") g))))))

(defn- entry->item [el source]
  (let [atom? (= "entry" (local-name el))]
    {:title (plain-text (child-text el "title"))
     :url (str (if atom? (atom-link el) (rss-link el)))
     :source source
     :summary (clip (plain-text (child-text el "description" "summary" "content" "encoded"))
                    summary-chars)
     :published (child-text el "pubDate" "published" "updated" "date")}))

(defn feed-items
  "The items of a parsed feed, in document order, titled by `source`."
  [doc source]
  (->> (tree-seq map? children doc)
       (filter #(contains? #{"item" "entry"} (local-name %)))
       (mapv #(entry->item % source))))

;; --- dates -------------------------------------------------------------------------

(def ^:private months
  {"jan" "01" "feb" "02" "mar" "03" "apr" "04" "may" "05" "jun" "06"
   "jul" "07" "aug" "08" "sep" "09" "oct" "10" "nov" "11" "dec" "12"})

(defn published-day
  "The day a feed date names, as YYYY-MM-DD, from RFC 822 (RSS) or ISO 8601
  (Atom, Dublin Core) text; nil when it is neither. The day is the one the
  date is written in, not converted to another zone."
  [s]
  (let [s (str/trim (str s))]
    (if-let [[_ day] (re-find #"^(\d{4}-\d{2}-\d{2})" s)]
      day
      (let [[y m d] (or (when-let [[_ d m y] (re-find #"(\d{1,2})\s+([A-Za-z]{3})[a-z]*\.?\s+(\d{4})" s)]
                          [y m d])
                        (when-let [[_ m d y] (re-find #"([A-Za-z]{3})[a-z]*\.?\s+(\d{1,2}),?\s+(\d{4})" s)]
                          [y m d])
                        (when-let [[_ y m d] (re-find #"(\d{4})\s+([A-Za-z]{3})[a-z]*\.?\s+(\d{1,2})" s)]
                          [y m d]))]
        (when-let [mm (and m (months (str/lower-case m)))]
          (str y "-" mm "-" (if (= 1 (count d)) (str "0" d) d)))))))

(defn recent
  "The items published from day `from` to day `to`, both included, and
  those whose date cannot be read."
  [items from to]
  (filterv (fn [item]
             (let [d (published-day (:published item))]
               (or (nil? d) (<= (compare from d) 0 (compare to d)))))
           items))

;; --- web search --------------------------------------------------------------------

(defn- field [block label]
  (let [v (some-> (re-find (re-pattern (str "(?m)^" label ":[ \\t]*(.*)$")) block) second str/trim not-empty)]
    (when-not (= "N/A" v) v)))

(def ^:private title-suffixes
  "What some sites put after every title, which says nothing about the story."
  [#"_英语频道_央视网\(cctv\.com\)$"])

(defn- clean-title [t]
  (reduce #(str/trim (str/replace %1 %2 "")) t title-suffixes))

(defn- index-page?
  "Whether a search result is a site's front page or a list of stories
  rather than a story."
  [url]
  (let [path (str/replace url #"^[a-z]+://[^/]+" "")]
    (boolean (or (re-matches #"/?(\?.*)?" path)
                 (re-find #"(?i)/(list|lists|index|latest|category|tag|topics?)(/|\.|$)" path)))))

(defn- highlights [block]
  (when-let [i (str/index-of block "Highlights:")]
    (-> (subs block (+ i (count "Highlights:")))
        (str/replace #"(?m)^#+\s*" "")
        (str/replace #"(?m)^\.\.\.$" "")
        plain-text)))

(defn search-items
  "The stories in an Exa search answer: each block with a URL that is not a
  front page or a list of stories."
  [text source]
  (->> (str/split (str text) #"\n-{3,}\n")
       (keep (fn [block]
               (let [url (field block "URL")]
                 (when (and url (not (index-page? url)))
                   {:title (clean-title (plain-text (or (field block "Title") url)))
                    :url url
                    :source source
                    :summary (clip (or (highlights block) "") summary-chars)
                    :published (field block "Published")}))))
       vec))

;; --- web pages -----------------------------------------------------------------------

(defn- unquote-attr [v]
  (let [v (str/trim (str v))]
    (if (and (>= (count v) 2) (contains? #{\" \'} (first v)))
      (subs v 1 (dec (count v)))
      v)))

(defn- remove-dot-segments
  "`url` with its path's . and .. segments resolved, as a browser would."
  [url]
  (if-let [[_ origin path tail] (re-matches #"(?s)(https?://[^/?#]+)(/[^?#]*)?(.*)" url)]
    (let [segments (str/split (or path "/") #"/" -1)
          resolved (reduce (fn [acc seg]
                             (case seg
                               "." acc
                               ".." (if (> (count acc) 1) (pop acc) acc)
                               (conj acc seg)))
                           [] segments)
          trailing? (contains? #{"." ".."} (last segments))]
      (str origin (str/join "/" resolved) (when trailing? "/") tail))
    url))

(defn- resolve-url
  "`href` as an absolute URL against the page at `base`, or nil for a link
  that goes nowhere a story could be (a fragment, a script, an email)."
  [base href]
  (let [href (str/replace (str/trim href) "&amp;" "&")
        [_ origin] (re-find #"^(https?://[^/?#]+)" base)
        dir (str/replace (first (str/split base #"[?#]" 2)) #"/[^/]*$" "/")]
    (cond
      (re-find #"^(?i)https?://" href) href
      (re-find #"^(?i)(#|javascript:|mailto:|tel:|data:)" href) nil
      (str/blank? href) nil
      (str/starts-with? href "//") (str (first (str/split base #"//" 2)) href)
      (str/starts-with? href "/") (when origin (remove-dot-segments (str origin href)))
      :else (remove-dot-segments (str dir href)))))

(def min-headline-chars
  "Link text shorter than this is navigation (\"More\", \"Go\"), not a headline."
  12)

(defn page-links
  "The stories a web page links to: every link whose absolute URL matches
  `pattern`, titled by the link's text, each URL once, in page order."
  [html base pattern source]
  (->> (re-seq #"(?is)<a\s[^>]*?href\s*=\s*(\"[^\"]*\"|'[^']*'|[^\s>]+)[^>]*>(.*?)</a>" (str html))
       (keep (fn [[_ href inner]]
               (let [url (resolve-url base (unquote-attr href))
                     title (plain-text inner)]
                 (when (and url (re-find pattern url) (>= (count title) min-headline-chars))
                   {:title title :url url :source source :summary "" :published nil}))))
       (reduce (fn [[seen out] item]
                 (if (contains? seen (:url item))
                   [seen out]
                   [(conj seen (:url item)) (conj out item)]))
               [#{} []])
       second))

(defn- meta-tags
  "Each <meta> tag's name or property with its content."
  [html]
  (into {}
        (keep (fn [tag]
                (let [attr (fn [k] (some-> (re-find (re-pattern (str "(?i)\\b" k "\\s*=\\s*(\"[^\"]*\"|'[^']*')")) tag)
                                           second unquote-attr))
                      k (or (attr "property") (attr "name"))
                      v (attr "content")]
                  (when (and k v) [(str/lower-case k) (plain-text v)]))))
        (re-seq #"(?is)<meta\s[^>]*>" (str html))))

(defn page-meta
  "What an article page says about itself in its meta tags: a title, a
  description and when it was published, each nil when the page doesn't say."
  [html]
  (let [m (meta-tags html)]
    {:title (or (get m "og:title") (get m "twitter:title"))
     :description (or (get m "og:description") (get m "description") (get m "twitter:description"))
     :published (or (get m "article:published_time") (get m "og:published_time")
                    (get m "datepublished") (get m "pubdate"))}))
