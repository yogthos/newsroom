(ns newsroom.feed-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.xml :as xml]
            [jolt.xml]
            [newsroom.feed :as feed]))

(def rss
  "<?xml version=\"1.0\"?>
<rss version=\"2.0\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">
<channel><title>Wire</title>
 <item>
  <title>Talks resume &amp; stall</title>
  <link>https://example.com/a?utm_source=rss</link>
  <description><![CDATA[<p>Delegates <b>met</b> in Geneva.</p>]]></description>
  <pubDate>Tue, 29 Sep 2026 22:10:00 GMT</pubDate>
 </item>
 <item>
  <title>  </title>
  <link>https://example.com/b</link>
 </item>
 <item>
  <title>Rates held</title>
  <guid isPermaLink=\"true\">https://example.com/c</guid>
  <dc:date>2026-09-30T08:00:00Z</dc:date>
 </item>
</channel></rss>")

(def atom
  "<?xml version=\"1.0\" encoding=\"utf-8\"?>
<feed xmlns=\"http://www.w3.org/2005/Atom\">
 <title>Atom wire</title>
 <entry>
  <title type=\"html\">Grain deal &lt;em&gt;extended&lt;/em&gt;</title>
  <link rel=\"replies\" href=\"https://example.com/g#comments\"/>
  <link rel=\"alternate\" href=\"https://example.com/g\"/>
  <summary>Ports reopen.</summary>
  <updated>2026-09-30T06:00:00+02:00</updated>
 </entry>
</feed>")

(def rdf
  "<?xml version=\"1.0\"?>
<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" xmlns=\"http://purl.org/rss/1.0/\">
 <channel><title>RDF wire</title></channel>
 <item><title>Strike ends</title><link>https://example.com/s</link>
  <description>Workers return.</description></item>
</rdf:RDF>")

(deftest plain-text
  (is (= "Delegates met in Geneva." (feed/plain-text "<p>Delegates <b>met</b>\n in Geneva.</p>")))
  (is (= "a & b < c \"d\" 'e' é" (feed/plain-text "a &amp; b &lt; c &quot;d&quot; &#39;e&#39; &#233;")))
  (is (= "" (feed/plain-text nil)))
  (is (= 5 (count (feed/clip "abcdefghij" 5)))))

(deftest rss-items
  (let [items (feed/feed-items (xml/parse rss) "Wire")]
    (is (= ["Talks resume & stall" "" "Rates held"] (map :title items)))
    (is (= "https://example.com/a?utm_source=rss" (:url (first items))))
    (is (= "Delegates met in Geneva." (:summary (first items))))
    (is (= "Tue, 29 Sep 2026 22:10:00 GMT" (:published (first items))))
    (testing "a permalink guid stands in for a missing link"
      (is (= "https://example.com/c" (:url (nth items 2)))))
    (testing "dc:date is a publication date"
      (is (= "2026-09-30T08:00:00Z" (:published (nth items 2)))))
    (is (every? #(= "Wire" (:source %)) items))))

(deftest atom-items
  (let [[item] (feed/feed-items (xml/parse atom) "Atom")]
    (is (= "Grain deal extended" (:title item)))
    (is (= "https://example.com/g" (:url item)) "the alternate link, not replies")
    (is (= "Ports reopen." (:summary item)))
    (is (= "2026-09-30T06:00:00+02:00" (:published item)))))

(deftest rdf-items
  (is (= [{:title "Strike ends" :url "https://example.com/s" :source "RDF"
           :summary "Workers return." :published nil}]
         (feed/feed-items (xml/parse rdf) "RDF"))))

(deftest published-day
  (is (= "2026-09-29" (feed/published-day "Tue, 29 Sep 2026 22:10:00 GMT")))
  (is (= "2026-09-05" (feed/published-day "Sat, 5 Sep 2026 01:00:00 +0000")))
  (is (= "2026-09-30" (feed/published-day "2026-09-30T06:00:00+02:00")))
  (is (= "2026-09-30" (feed/published-day "2026-09-30 21:15:11")) "ECNS")
  (is (= "2026-09-30" (feed/published-day "2026 Sep 30   02:09:05 PDT")) "Sixth Tone")
  (is (= "2026-08-31" (feed/published-day "Aug 31, 2026")) "Sixth Tone")
  (is (= "2026-09-30" (feed/published-day "16:08, September 30, 2026")))
  (is (nil? (feed/published-day "N/A")))
  (is (nil? (feed/published-day "yesterday")))
  (is (nil? (feed/published-day nil))))

(deftest recent
  (let [items [{:title "old" :published "Mon, 21 Sep 2026 10:00:00 GMT"}
               {:title "eve" :published "2026-09-29T23:00:00Z"}
               {:title "today" :published "2026-09-30T08:00:00Z"}
               {:title "undated" :published nil}
               {:title "future" :published "2026-10-02T08:00:00Z"}]]
    (is (= ["eve" "today" "undated"]
           (map :title (feed/recent items "2026-09-29" "2026-09-30"))))))

(def exa-text
  (str "Title: Morning Briefing: Sept. 30, 2026\n"
       "URL: https://www.aa.com.tr/en/world/morning-briefing\n"
       "Published: 2026-09-30T07:15:44.321Z\n"
       "Author: Someone\n"
       "Highlights:\n# Morning Briefing\n\nA rundown of the news.\n...\n- item one\n"
       "\n---\n\n"
       "Title: US forces exit Iraq | Reuters\n"
       "URL: https://www.reuters.com/world/us-forces-exit-iraq/\n"
       "Published: 2026-09-30T00:01:55.000Z\n"
       "Author: Ahmed Rasheed\n"
       "Highlights:\nUS forces exit Iraq, emboldening proxies.\n"
       "\n---\n\n"
       "Title: No link here\n"
       "\n---\n\n"
       "Title: CCTV.com English - News, Video\nURL: https://english.cctv.com/\nPublished: N/A\n"
       "\n---\n\n"
       "Title: Latest news\nURL: https://english.news.cn/list/latestnews.htm\nPublished: N/A\n"
       "\n---\n\n"
       "Title: Deep-sea drilling_英语频道_央视网(cctv.com)\n"
       "URL: https://english.cctv.com/2026/09/30/VIDEnJc.shtml\nPublished: N/A\n"))

(deftest search-items
  (let [items (feed/search-items exa-text "Web search")]
    (is (= 3 (count items)) "a block with no URL, a homepage or a listing page is not an item")
    (is (= "Deep-sea drilling" (:title (last items))) "a site's name suffix is dropped")
    (is (nil? (:published (last items))) "N/A is no date")
    (is (= "Morning Briefing: Sept. 30, 2026" (:title (first items))))
    (is (= "https://www.aa.com.tr/en/world/morning-briefing" (:url (first items))))
    (is (= "2026-09-30T07:15:44.321Z" (:published (first items))))
    (is (str/includes? (:summary (first items)) "A rundown of the news."))
    (is (not (str/includes? (:summary (first items)) "#")))
    (is (= "Web search" (:source (second items))))))

(def page
  "<html><head><title>World news</title></head><body>
   <nav><a href=\"/\">Home</a> <a href=\"/world\">World</a></nav>
   <a class=\"story\" href=\"/article/talks-resume-1a2b\"><h3>Talks <b>resume</b> in Geneva</h3></a>
   <a href=\"https://news.example.com/article/rates-held-3c4d?utm_source=home\">Rates held &amp; markets calm</a>
   <a href='/article/talks-resume-1a2b'>Talks resume in Geneva</a>
   <a href=\"/article/x9\">Go</a>
   <a href=\"https://elsewhere.com/article/other\">Another site's story</a>
   <a href=\"/video/clip-5\">A video clip</a>
   <a href=\"javascript:void(0)\">Menu</a>
   </body></html>")

(deftest page-links
  (let [items (feed/page-links page "https://news.example.com/world" #"/article/" "Example")]
    (is (= ["https://news.example.com/article/talks-resume-1a2b"
            "https://news.example.com/article/rates-held-3c4d?utm_source=home"
            "https://elsewhere.com/article/other"]
           (map :url items))
        "matching links, resolved against the page, each story once, in page order")
    (is (= ["Talks resume in Geneva" "Rates held & markets calm" "Another site's story"]
           (map :title items)))
    (is (every? #(= "Example" (:source %)) items))
    (testing "a link whose text is too short to be a headline is skipped"
      (is (not-any? #(= "Go" (:title %)) items))))
  (testing "dot segments are resolved"
    (is (= ["https://e.cn/20260930/abc/c.html" "https://e.cn/world/asia/x.html"]
           (map :url (feed/page-links (str "<a href=\"../20260930/abc/c.html\">A story from the day before</a>"
                                           "<a href=\"./asia/x.html\">A story from the region here</a>")
                                      "https://e.cn/world/index.htm" #"html" "E")))))
  (testing "a relative link without a leading slash resolves against the page's directory"
    (is (= ["https://e.com/news/story-one"]
           (map :url (feed/page-links "<a href=\"story-one\">The first story of the day</a>"
                                      "https://e.com/news/index.html" #"story" "E"))))))

(deftest page-meta
  (let [html "<head><meta property=\"og:title\" content=\"Talks resume\">
              <meta name=\"description\" content=\"Delegates met &amp; talked.\">
              <meta property=\"og:description\" content=\"Delegates met in Geneva.\">
              <meta property=\"article:published_time\" content=\"2026-09-30T08:00:00Z\"></head>"]
    (is (= {:title "Talks resume" :description "Delegates met in Geneva." :published "2026-09-30T08:00:00Z"}
           (feed/page-meta html))
        "og:description wins over the plain description"))
  (is (= {:title nil :description "Plain one." :published nil}
         (feed/page-meta "<meta content=\"Plain one.\" name=\"description\">"))
      "attribute order doesn't matter")
  (is (= {:title nil :description nil :published nil} (feed/page-meta "<p>nothing</p>"))))
