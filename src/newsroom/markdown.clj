(ns newsroom.markdown
  "Markdown to HTML, for the briefings: headings, paragraphs, lists,
  blockquotes, rules, fenced code, and inline emphasis, code and links. The
  briefing is written by a model, so every piece of text is escaped and a
  link only keeps an http(s) or relative target."
  (:require [clojure.string :as str]))

(defn escape [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

;; --- inline ------------------------------------------------------------------------

(def ^:private code-re #"`([^`]+)`")
(def ^:private link-re
  #"\[((?:\[[^\]]*\]|[^\[\]])*)\]\(((?:[^()\s]|\([^()\s]*\))+)\)")

(defn- segments
  "s split by the first of `re`'s matches, repeatedly: [:text s] and
  [:match groups] in order."
  [re s]
  (loop [s s, out []]
    (if-let [m (re-find re s)]
      (let [whole (if (string? m) m (first m))
            i (str/index-of s whole)]
        (recur (subs s (+ i (count whole)))
               (cond-> out
                 (pos? i) (conj [:text (subs s 0 i)])
                 true (conj [:match m]))))
      (cond-> out (seq s) (conj [:text s])))))

(defn- emphasis [escaped]
  (-> escaped
      (str/replace #"\*\*([^*]+)\*\*" "<strong>$1</strong>")
      (str/replace #"__([^_]+)__" "<strong>$1</strong>")
      (str/replace #"(^|[^\w*])\*([^*\s](?:[^*]*[^*\s])?)\*" "$1<em>$2</em>")
      (str/replace #"(^|[^\w_])_([^_\s](?:[^_]*[^_\s])?)_" "$1<em>$2</em>")))

(defn- safe-href? [url]
  (boolean (re-find #"^(https?://|/|#)" url)))

(declare inline)

(defn- links [s]
  (apply str
         (for [[kind v] (segments link-re s)]
           (if (= :text kind)
             (emphasis (escape v))
             (let [[_ text url] v]
               (if (safe-href? url)
                 (str "<a href=\"" (escape url) "\">" (inline text) "</a>")
                 (inline text)))))))

(defn inline
  "One run of text as HTML."
  [s]
  (apply str
         (for [[kind v] (segments code-re s)]
           (if (= :text kind)
             (links v)
             (str "<code>" (escape (second v)) "</code>")))))

;; --- blocks ------------------------------------------------------------------------

(defn- kind [line]
  (cond
    (str/blank? line) :blank
    (re-find #"^```" line) :fence
    (re-find #"^#{1,6}\s" line) :heading
    (re-find #"^\s{0,3}([-*_])(\s*\1){2,}\s*$" line) :rule
    (re-find #"^\s*>" line) :quote
    (re-find #"^\s*[-*+]\s+" line) :ul
    (re-find #"^\s*\d+[.)]\s+" line) :ol
    :else :para))

(declare html)

(def ^:private diagram-tools
  (str "<div class=\"diagram-tools\">"
       "<button type=\"button\" data-diagram=\"in\" title=\"Zoom in\">+</button>"
       "<button type=\"button\" data-diagram=\"out\" title=\"Zoom out\">−</button>"
       "<button type=\"button\" data-diagram=\"reset\" title=\"Fit\">Fit</button>"
       "<button type=\"button\" data-diagram=\"expand\" title=\"Full screen\">⤢</button>"
       "<span class=\"diagram-hint\">drag to pan, ctrl + scroll to zoom</span>"
       "</div>"))

(defn- diagram
  "A mermaid diagram with its zoom and pan controls. The page's script draws
  it (mermaid reads the text) and drives the controls; they are rendered here
  rather than added by the script so a live re-render keeps them. A
  `%% caption:` comment in it is shown under it, as how to read it."
  [code]
  (let [lines (str/split-lines code)
        caption? #(re-find #"^\s*%% caption: " %)
        captions (keep #(second (re-find #"^\s*%% caption: (.*)$" %)) lines)]
    (str "<figure class=\"diagram\">" diagram-tools
         "<div class=\"diagram-view\"><pre class=\"mermaid\">" (str/join "\n" (remove caption? lines)) "</pre></div>"
         (when (seq captions) (str "<figcaption>" (str/join " " captions) "</figcaption>"))
         "</figure>")))

(defn- heading [line]
  (let [[_ hashes text] (re-find #"^(#{1,6})\s+(.*?)\s*#*\s*$" line)
        n (count hashes)]
    (str "<h" n ">" (inline text) "</h" n ">")))

(defn- take-while-kind [ks lines]
  (split-with #(contains? ks (kind %)) lines))

(defn- blocks [lines]
  (loop [lines lines, out []]
    (if-let [line (first lines)]
      (case (kind line)
        :blank (recur (rest lines) out)
        :heading (recur (rest lines) (conj out (heading line)))
        :rule (recur (rest lines) (conj out "<hr>"))
        :fence (let [[body more] (split-with #(not (re-find #"^```" %)) (rest lines))
                     code (escape (str/join "\n" body))
                     lang (str/lower-case (str/trim (subs line 3)))]
                 (recur (rest more)
                        (conj out (if (= "mermaid" lang)
                                    (diagram code)
                                    (str "<pre><code>" code "</code></pre>")))))
        :quote (let [[qs more] (take-while-kind #{:quote} lines)]
                 (recur more
                        (conj out (str "<blockquote>"
                                       (html (str/join "\n" (map #(str/replace % #"^\s*>\s?" "") qs)))
                                       "</blockquote>"))))
        (:ul :ol) (let [k (kind line)
                        tag (name k)
                        marker (if (= :ul k) #"^\s*[-*+]\s+" #"^\s*\d+[.)]\s+")
                        [items more] (take-while-kind #{k} lines)]
                    (recur more
                           (conj out (str "<" tag ">"
                                          (apply str (map #(str "<li>" (inline (str/replace % marker "")) "</li>")
                                                          items))
                                          "</" tag ">"))))
        :para (let [[ps more] (take-while-kind #{:para} lines)]
                (recur more
                       (conj out (str "<p>" (inline (str/join "\n" (map str/trim ps))) "</p>")))))
      out)))

(defn html
  "The markdown as an HTML string."
  [markdown]
  (apply str (blocks (str/split-lines (str markdown)))))
