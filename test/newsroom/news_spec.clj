(ns newsroom.news-spec
  "The contract for newsroom.news, the pure core of a day's briefing.

  A day runs through it in one direction: the items every source gathered
  are cleaned of duplicates, numbered as citable sources, and written into
  the analysis prompt; the model's answer comes back as markdown citing
  those numbers, and is turned into the briefing, its citations linked and
  a list of the sources it cited appended. Only cited sources are listed:
  everything gathered for the day is kept in the store, but the briefing
  names what its claims rest on. The history sidebar asks which
  days come before and after the one on screen.

  Items reach the laws through `story`, which builds one from generated
  values, so duplicates are common: a story is keyed by a small number, and
  the same story comes back with a fragment or tracking parameters on it."
  (:require [clojure.string :as str]
            [newsroom.news :refer [canonical-url dedupe-items unseen-items overview add-previous cite render-prompt
                                   citations link-citations briefing collapse-similar told outlets tldr
                                   valid-day? adjacent-days]]
            [writ.spec :refer [spec ann refine graph flow law calls assume]]))

(spec newsroom.news)

(assume str/trim [String -> String])
(assume str/blank? [String -> Bool])
(assume str/includes? [String String -> Bool])

;; --- the data --------------------------------------------------------------------

;; what a source adapter hands over: an RSS entry, a search hit
(refine Item [i {:title String, :url String, :source String, :summary String,
                 :published (Opt String)}]
  true)

;; an item with the number the analysis cites it by
(refine Source [s {:n Nat, :title String, :url String, :source String,
                   :summary String, :published (Opt String)}]
  true)

(ann canonical-url  [String -> String])
(ann dedupe-items   [(List Item) -> (List Item)])
(ann unseen-items   [(List Item) (List Item) -> (List Item)])
(ann overview       [String -> (Opt String)])
(ann add-previous   [String (Opt String) -> String])
(ann cite           [(List Item) -> (List Source)])
(ann render-prompt  [String String (List Source) -> String])
(ann citations      [String -> (Vec Nat)])
(ann link-citations [String (List Source) -> String])
(ann briefing       [String (List Source) -> String])
(ann collapse-similar [(List Item) (Opt Float) -> (List Item)])
(ann told           [(List Item) -> (List Item)])
(ann outlets        [Item -> Nat])
(ann tldr           [String -> (Opt String)])
(ann valid-day?     [String -> Bool])
(ann adjacent-days  [(List String) String -> (Tuple (Opt String) (Opt String))])

;; --- vocabulary --------------------------------------------------------------------

(defn alnum [s] (str/replace (str s) #"[^0-9a-zA-Z]" ""))

(defn web-url
  "A story's address: a path of letters and digits under a fixed host."
  [n]
  (str "https://example.com/story/" n))

(defn story
  "An item for story `n`, as a feed might hand it over: with a fragment or
  tracking parameters when `junk` says so."
  [n title junk]
  {:title (str "t" (alnum title))
   :url (case (mod junk 4)
          0 (web-url n)
          1 (str (web-url n) "#comments")
          2 (str (web-url n) "?utm_source=rss&utm_medium=" (alnum title))
          3 (str (web-url n) "/"))
   :source "wire"
   :summary (str title)
   :published nil})

(defn stories [ns titles]
  (vec (map-indexed (fn [i n] (story n (nth titles (mod i (max 1 (count titles))) "")
                                     (+ i n)))
                    ns)))

(defn usable? [item]
  (not (or (str/blank? (:title item)) (str/blank? (:url item)))))

(defn unique-urls? [items]
  (= (count items) (count (distinct (map #(canonical-url (:url %)) items)))))

(defn numbered? [sources]
  (= (map :n sources) (range 1 (inc (count sources)))))

(defn first-per-url
  "The model of deduplication: each usable item whose story has not been
  seen yet, in the order they came."
  [items]
  (first (reduce (fn [[kept seen] item]
                   (let [k (canonical-url (:url item))]
                     (if (or (not (usable? item)) (contains? seen k))
                       [kept seen]
                       [(conj kept item) (conj seen k)])))
                 [[] #{}]
                 items)))

(defn source-of [n title]
  {:n n :title (str "t" (alnum title)) :url (web-url n) :source "wire"
   :summary "" :published nil})

(defn sources-of [ns]
  (vec (map-indexed (fn [i n] (source-of (inc i) (str n))) ns)))

(defn cited-in [n] (str "A claim [" n "]."))

(defn sources-section [doc]
  (let [i (str/index-of doc "## Sources")]
    (if i (subs doc i) "")))

;; --- the state graph ---------------------------------------------------------------

(refine Unique [xs (List Item)] (unique-urls? xs))
(refine Cited [xs (List Source)] (numbered? xs))
(refine Prompt [s String] (not (str/includes? s "{{sources}}")))
(refine Briefing [s String] (str/includes? s "## Sources"))
(refine Link [s String] (str/starts-with? s "http"))
(refine Canonical [s String] (not (str/includes? s "#")))

(graph day
  {:states {:gathered (List Item), :unique Unique, :cited Cited, :prompt Prompt,
            :answer String, :briefing Briefing, :numbers (Vec Nat),
            :link Link, :url Canonical}
   :edges  {:link     {[canonical-url] #{:url}}
            :gathered {[dedupe-items] #{:unique}}
            :unique   {[cite] #{:cited}}
            :cited    {[render-prompt String String _] #{:prompt}}
            :answer   {[briefing (List Source)] #{:briefing}
                       [citations] #{:numbers}}}})

;; --- the wiring --------------------------------------------------------------------

;; the briefing is the answer with its citations linked, then the sources it
;; cited: the numbers come from the answer, the links from the sources
(flow briefing [markdown sources]
  [markdown link-citations :result]
  [markdown citations :result]
  [sources link-citations])

(calls briefing {:through [link-citations citations]})
(calls dedupe-items {:through [canonical-url]})

;; --- urls ------------------------------------------------------------------------

(law a-canonical-url-is-canonical
  (forall [n Nat, t String, j Nat]
    (= (canonical-url (canonical-url (:url (story n t j))))
       (canonical-url (:url (story n t j))))))

(law fragments-and-tracking-do-not-make-a-new-story
  (forall [n Nat, t String, j Nat]
    (= (canonical-url (:url (story n t j))) (web-url n))))

(law other-parameters-are-kept
  (forall [n Nat, t String]
    (= (canonical-url (str (web-url n) "?id=" (alnum t) "&utm_campaign=x#top"))
       (str (web-url n) "?id=" (alnum t)))))

(law different-stories-keep-different-urls
  (forall [a Nat, b Nat]
    (=> (not= a b) (not= (canonical-url (web-url a)) (canonical-url (web-url b))))))

;; --- deduplication ---------------------------------------------------------------

(law the-first-copy-of-each-story-is-kept-in-order
  (forall [ns (List Nat), ts (List String)]
    (= (dedupe-items (stories ns ts)) (first-per-url (stories ns ts)))))

(law an-item-without-a-title-or-url-is-dropped
  (forall [n Nat, t String]
    (and (= [] (dedupe-items [(assoc (story n t 0) :title "  ")]))
         (= [] (dedupe-items [(assoc (story n t 0) :url "")])))))

(law nothing-gathered-nothing-kept (= [] (dedupe-items [])))

;; --- what the last briefing already had --------------------------------------------

(defn yesterdays
  "Stories that can't collide with `stories` by URL or by title."
  [ms]
  (mapv (fn [n] (assoc (story (+ 1000000 n) "x" 0) :title (str "Yesterday's story number " n)))
        ms))

(law with-no-earlier-briefing-everything-is-new
  (forall [ns (List Nat), ts (List String)]
    (= (stories ns ts) (unseen-items (stories ns ts) []))))

(law a-story-in-the-last-briefing-is-left-out
  (forall [ns (List Nat), ts (List String)]
    (= [] (unseen-items (stories ns ts) (stories ns ts)))))

(law other-stories-are-kept-in-order
  (forall [ns (List Nat), ts (List String), ms (List Nat)]
    (= (stories ns ts) (unseen-items (stories ns ts) (yesterdays ms)))))

(law the-same-headline-at-another-address-is-the-same-story
  (forall [n Nat, m Nat]
    (= [] (unseen-items [(assoc (story n "a" 0) :title "Tariffs rise again on steel imports")]
                        [(assoc (story (+ 1000000 m) "b" 0) :title "Tariffs Rise Again on Steel Imports!")]))))

(law a-short-headline-is-not-enough-to-match
  (forall [n Nat, m Nat]
    (= 1 (count (unseen-items [(assoc (story n "a" 0) :title "Live updates")]
                              [(assoc (story (+ 1000000 m) "b" 0) :title "Live updates")])))))

;; --- building on the last briefing ----------------------------------------------

(defn briefing-doc [title overview-text rest-text]
  (str "# " title "\n\n## Overview\n\n" overview-text "\n\n## Politics\n\n" rest-text
       "\n\n## Sources\n\n1. [x](https://e.com/1)\n"))

(law the-overview-is-the-first-section
  (forall [a String, b String]
    (= (str "Opening " (alnum a) ".")
       (overview (briefing-doc "Day" (str "Opening " (alnum a) ".") (str "Later " (alnum b) "."))))))

(law the-overview-loses-its-citations
  (forall [n Nat, m Nat]
    (= "A claim. Another one."
       (overview (briefing-doc "Day" (str "A claim [[" n "]](https://e.com/" n "), [[" m "]](https://e.com/x)."
                                          " Another one [" n ", " m "].")
                               "x")))))

(law a-briefing-with-no-sections-has-no-overview
  (forall [a String]
    (nil? (overview (str "# Just a title " (alnum a))))))

(law the-last-briefing-goes-where-the-template-says
  (forall [a String, b String, c String]
    (= (str (alnum a) " " (alnum c) " " (alnum b) " {{sources}}")
       (add-previous (str (alnum a) " {{previous}} " (alnum b) " {{sources}}") (alnum c)))))

(law without-a-place-it-goes-before-the-sources
  (forall [a String, b String, c String]
    (= (str (alnum a) " " (alnum c) "\n\n{{sources}} " (alnum b))
       (add-previous (str (alnum a) " {{sources}} " (alnum b)) (alnum c)))))

(law with-neither-place-it-goes-at-the-end
  (forall [a String, c String]
    (= (str (alnum a) "\n\n" (alnum c))
       (add-previous (alnum a) (alnum c)))))

(law without-a-last-briefing-only-the-place-is-dropped
  (forall [a String, b String]
    (and (= (str (alnum a) "  " (alnum b) " {{sources}}")
            (add-previous (str (alnum a) " {{previous}} " (alnum b) " {{sources}}") nil))
         (= (str (alnum a) " {{sources}}")
            (add-previous (str (alnum a) " {{sources}}") nil)))))

;; --- citing ------------------------------------------------------------------------

(law sources-are-numbered-from-one-in-order
  (forall [ns (List Nat), ts (List String)]
    (and (numbered? (cite (stories ns ts)))
         (= (stories ns ts) (map #(dissoc % :n) (cite (stories ns ts)))))))

;; --- the prompt --------------------------------------------------------------------

(law every-source-is-in-the-prompt
  (forall [ns (List Nat), tmpl String, day String]
    (every? (fn [s] (and (str/includes? (render-prompt tmpl day (sources-of ns))
                                        (str "[" (:n s) "]"))
                         (str/includes? (render-prompt tmpl day (sources-of ns)) (:url s))
                         (str/includes? (render-prompt tmpl day (sources-of ns)) (:title s))))
            (sources-of ns))))

(law the-placeholders-are-filled
  (forall [ns (List Nat), a String, b String]
    (and (str/includes? (render-prompt (str (alnum a) " {{date}} " (alnum b) " {{sources}}")
                                       "2026-09-30" (sources-of ns))
                        (str (alnum a) " 2026-09-30 " (alnum b) " "))
         (not (str/includes? (render-prompt "{{date}} {{sources}}" "2026-09-30" (sources-of ns))
                             "{{")))))

(law a-template-without-sources-still-gets-them-after-it
  (forall [ns (List Nat), a String]
    (str/starts-with? (render-prompt (str "Analyse " (alnum a)) "2026-09-30" (sources-of ns))
                      (str "Analyse " (alnum a)))))

(law the-sources-end-the-prompt-where-they-are-placed
  (forall [n Nat, ns (List Nat), a String]
    (and (str/ends-with? (render-prompt (str (alnum a) " {{sources}}") "2026-09-30" (sources-of (cons n ns)))
                         (:url (last (sources-of (cons n ns)))))
         (= (render-prompt (str "On {{date}}, " (alnum a) ": {{sources}}") "2026-09-30" [])
            (str "On 2026-09-30, " (alnum a) ": ")))))

;; --- citations ---------------------------------------------------------------------

(law the-numbers-cited-in-order
  (forall [ns (List Nat)]
    (= (citations (str/join " " (map cited-in ns))) (vec (sort (distinct ns))))))

(law a-grouped-citation-cites-each
  (forall [a Nat, b Nat]
    (= (citations (str "Both [" a ", " b "] and [" b "][" a "].")) (vec (sort (distinct [a b]))))))

(law links-and-words-in-brackets-are-not-citations
  (forall [t String, n Nat]
    (= [] (citations (str "[" (alnum t) "x](https://example.com/" n ") [n" n "] [" n "a]")))))

(defn fenced [body] (str "```mermaid\n" body "\n```"))

(law code-blocks-are-not-cited-or-linked
  (forall [ns (List Nat), cs (List Nat), n Nat]
    (and (= (citations (str (fenced (str "A[" n "] --> B[" n ", " n "]")) "\n\n"
                            (str/join " " (map cited-in cs))))
            (citations (str/join " " (map cited-in cs))))
         (str/starts-with? (link-citations (str (fenced (str "A[1] --> B[" n "]")) "\n" (cited-in 1))
                                           (sources-of (cons n ns)))
                           (fenced (str "A[1] --> B[" n "]"))))))

(law linking-keeps-the-citations
  (forall [ns (List Nat), cs (List Nat)]
    (= (citations (link-citations (str/join " " (map cited-in cs)) (sources-of ns)))
       (citations (str/join " " (map cited-in cs))))))

(law a-citation-links-to-its-source
  (forall [n Nat, ns (List Nat), i Nat]
    (str/includes? (link-citations (cited-in (inc (mod i (inc (count ns))))) (sources-of (cons n ns)))
                   (str "[[" (inc (mod i (inc (count ns)))) "]]("
                        (web-url (inc (mod i (inc (count ns))))) ")"))))

(law an-unknown-citation-is-left-alone
  (forall [ns (List Nat)]
    (= (cited-in (+ 100 (count ns)))
       (link-citations (cited-in (+ 100 (count ns))) (sources-of ns)))))

;; --- the briefing ------------------------------------------------------------------

(law the-sources-section-lists-what-was-cited
  (forall [ns (List Nat), cs (List Nat)]
    (every? (fn [s] (= (boolean (some #{(:n s)} cs))
                       (str/includes? (sources-section
                                       (briefing (str/join " " (map cited-in cs)) (sources-of ns)))
                                      (str "(" (:url s) ")"))))
            (sources-of ns))))

(law the-answer-is-kept-above-the-sources
  (forall [t String]
    (str/starts-with? (briefing (str "# Today\n\n" (alnum t)) [])
                      (str "# Today\n\n" (alnum t)))))

(law an-answer-citing-nothing-lists-no-sources
  (forall [ns (List Nat), t String]
    (every? (fn [s] (not (str/includes? (briefing (str "No claims " (alnum t)) (sources-of ns))
                                        (str "(" (:url s) ")"))))
            (sources-of ns))))

(law each-cited-source-is-a-linked-entry
  (forall [n Nat, ns (List Nat), i Nat]
    (let [k (inc (mod i (inc (count ns))))
          s (nth (sources-of (cons n ns)) (dec k))]
      (str/ends-with? (briefing (cited-in k) (sources-of (cons n ns)))
                      (str "\n- [" k "] [" (:title s) "](" (:url s) ") — " (:source s) "\n")))))

;; --- near-duplicates ----------------------------------------------------------------

(defn embedded
  "A story from outlet `s` carrying one of a fixed set of embedding vectors,
  as the pipeline hands items to collapse-similar."
  [n s vec]
  (assoc (story n (str s n) 0) :source s :vector vec))

(def dup-vectors [[1.0 0.0] [0.99 0.02] [0.0 1.0]])

(law a-nil-threshold-collapses-nothing
  (= (stories [1 2 3] ["a"])
     (collapse-similar (stories [1 2 3] ["a"]) nil)))

(law a-verbatim-copy-merges-at-any-threshold-below-one
  (forall [t Float]
    (let [clamp (max 0.0 (min 0.99 t))]
      (= 1 (count (collapse-similar [(assoc (story 1 "t" 0) :source "wire" :vector [1 0])
                                     (assoc (story 2 "u" 0) :source "other" :vector [1 0])]
                                    clamp))))))

(law orthogonal-stories-stay-apart-at-any-live-threshold
  (forall [t Float]
    (let [clamp (max 0.1 t)]
      (= 2 (count (collapse-similar [(assoc (story 1 "t" 0) :source "wire" :vector [1 0])
                                     (assoc (story 2 "u" 0) :source "other" :vector [0 1])]
                                    clamp))))))

(law a-verbatim-copy-is-one-story-with-both-outlets
  (= [(assoc (story 1 "t" 0) :also [{:source "other"
                                     :url (:url (story 1 "t" 1))
                                     :title (:title (story 1 "t" 1))
                                     :origin "example.com"}])]
     (mapv #(dissoc % :vector)
           (collapse-similar [(assoc (story 1 "t" 0) :source "wire" :vector [1 0])
                              (assoc (story 1 "t" 1) :source "other" :vector [1 0])]
                             0.5))))

(law a-collapsed-copy-counts-as-told
  (forall [n Nat, a String]
    (let [head (assoc (story n a 0) :source "wire" :vector [1 0])
          copy (assoc (story n a 1) :url (str "https://elsewhere.org/" n) :source "other"
                      :title (str "elsewhere " (alnum a)) :vector [1 0])
          told-today (collapse-similar [head copy] 0.5)]
      (and (= 2 (outlets (first told-today)))
           (empty? (unseen-items [(dissoc copy :vector)] (told told-today)))))))

(law one-publishers-feeds-count-as-one-outlet
  (forall [n Nat]
    (= 1 (outlets (assoc (story n "a" 0) :source "World feed"
                         :also [{:source "Business feed" :url (str "https://www.example.com/business/" n)}])))))

(law different-publishers-are-different-outlets
  (= 2 (outlets (assoc (story 1 "a" 0) :url "https://one.org/a"
                       :also [{:source "Two" :url "https://two.co.uk/b"}]))))

(law a-wire-story-counts-once-however-many-reprint-it
  (= 1 (outlets (assoc (story 1 "a" 0) :url "https://one.org/a" :summary "LONDON (Reuters) - Oil rose."
                       :also [{:source "Two" :url "https://two.org/b" :origin "Reuters"}]))))

(law one-publishers-similar-stories-stay-apart
  (= 2 (count (collapse-similar [(assoc (story 1 "a" 0) :source "World" :vector [1 0])
                                 (assoc (story 2 "b" 0) :source "Business" :vector [0.8 0.6])]
                                0.5))))

(law told-lists-each-source-then-its-copies-in-order
  (forall [n Nat]
    (let [s (assoc (story n "a" 0) :also [{:source "other" :url "https://o.example.com/x" :title "copy"}
                                          {:source "named only"}])
          s2 (story (inc n) "b" 0)]
      (= [(:url s) "https://o.example.com/x" (:url s2)] (map :url (told [s s2]))))))

(law the-standfirst-is-the-first-blockquote-before-the-sections
  (forall [a String]
    (= (str "t" (alnum a))
       (tldr (str "# Day\n\n> t" (alnum a) "\n\n## Overview\n\nBody.")))))

(law the-standfirst-drops-its-citations-and-joins-its-lines
  (= "Rates rose and yields followed."
     (tldr "# Day\n\n> Rates rose [[1]](https://e.com/1)\n> and yields followed [2, 3].\n\n## Overview")))

(law a-briefing-without-a-standfirst-has-none
  (nil? (tldr "# Day\n\n## Overview\n\nBody.")))

;; --- days --------------------------------------------------------------------------

(graph calendar
  {:states {:days (List String), :neighbours (Tuple (Opt String) (Opt String)),
            :text String, :verdict Bool}
   :edges  {:days {[adjacent-days String] #{:neighbours}}
            :text {[valid-day?] #{:verdict}}}})

(defn day-of [y m d] (format "%04d-%02d-%02d" (+ 2000 y) (inc (mod m 12)) (inc (mod d 28))))

(law a-calendar-day-is-valid
  (forall [y Nat, m Nat, d Nat] (valid-day? (day-of y m d))))

(law other-strings-are-not-days
  (forall [s String]
    (and (not (valid-day? (str "x" s)))
         (not (valid-day? "2026-13-01"))
         (not (valid-day? "2026-00-10"))
         (not (valid-day? "2026-02-32"))
         (not (valid-day? "2026-9-30")))))

(defn before [days day] (last (sort (filter #(neg? (compare % day)) days))))
(defn after  [days day] (first (sort (filter #(pos? (compare % day)) days))))

(law the-neighbours-are-the-nearest-days-with-a-briefing
  (forall [ds (List Nat), d Nat]
    (= (adjacent-days (map #(day-of 0 0 %) ds) (day-of 0 0 d))
       [(before (map #(day-of 0 0 %) ds) (day-of 0 0 d))
        (after (map #(day-of 0 0 %) ds) (day-of 0 0 d))])))
