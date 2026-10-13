(ns newsroom.news-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [newsroom.news :as news]))

(defn- item [n source & {:keys [title url summary emb]}]
  (cond-> {:n n
           :title (or title (str "Story " n))
           :url (or url (str "https://" (str/lower-case source) ".com/" n))
           :source source
           :summary (or summary "")
           :published nil}
    emb (assoc :vector emb)))

(defn- unit [v] (let [n (Math/sqrt (reduce + (map * v v)))] (mapv #(/ % n) v)))

(def fed (unit [8 1 0]))
(def fed-again (unit [7.8 1.2 0.1]))
(def space (unit [0 1 8]))

(deftest collapse-similar-merges-near-duplicates-across-outlets
  (let [items [(item 1 "Reuters" :emb fed)
               (item 2 "BBC" :emb space)
               (item 3 "AP" :emb fed-again)]
        out (news/collapse-similar items 0.55)]
    (testing "the first copy survives with the others credited"
      (is (= 2 (count out)))
      (is (= 1 (:n (first out))))
      (is (= [{:source "AP" :url "https://ap.com/3" :title "Story 3" :origin "ap.com"}] (:also (first out)))
          "the copies that also carry it, so they count as told")
      (is (= 2 (news/outlets (first out))))
      (is (nil? (:also (second out)))))
    (testing "no vectors, no merging"
      (is (= 3 (count (news/collapse-similar (mapv #(dissoc % :vector) items) 0.55)))))
    (testing "a threshold past the similarity keeps both"
      (is (= 3 (count (news/collapse-similar items 0.9999)))
          "fed and fed-again sit at 0.9995 similarity"))
    (testing "nothing to collapse"
      (is (= [] (news/collapse-similar [] 0.55))))))

(deftest collapse-similar-never-merges-within-one-outlet
  (let [out (news/collapse-similar [(item 1 "Reuters" :emb fed)
                                    (item 2 "Reuters" :emb fed-again)
                                    (item 3 "BBC" :emb fed)]
                                   0.55)]
    ;; the two Reuters stories stay apart; BBC's copy joins the first
    (is (= [["BBC"] nil] (mapv #(some->> (:also %) (mapv :source)) out)))
    (is (= 2 (count out)) "the second Reuters story is its own source")))

(deftest collapse-similar-clusters-by-centroid
  (let [a (unit [10 0 0]) b (unit [9 1 0]) c (unit [0 10 0]) d (unit [1 9 0])
        out (news/collapse-similar [(item 1 "A" :emb a)
                                    (item 2 "B" :emb b)
                                    (item 3 "C" :emb c)
                                    (item 4 "D" :emb d)]
                                   0.9)]
    (is (= 2 (count out)) "two clusters of two")))

(deftest outlets-are-counted-by-origin
  (let [out (news/collapse-similar [(item 1 "CGTN World" :url "https://www.cgtn.com/a" :emb fed)
                                    (item 2 "CGTN Business" :url "https://news.cgtn.com/a" :emb fed-again)
                                    (item 3 "CGTN China" :url "https://www.cgtn.com/c" :emb (unit [6 5 0]))
                                    (item 4 "BBC" :url "https://www.bbc.co.uk/a" :emb fed)]
                                   0.55)]
    (testing "a publisher's feeds carrying one text merge, and count once"
      (is (= ["CGTN Business" "BBC"] (map :source (:also (first out)))))
      (is (= 2 (news/outlets (first out)))))
    (testing "the same publisher's merely similar story stays its own"
      (is (= 2 (count out)))
      (is (= "CGTN China" (:source (second out))))))
  (testing "reprints of a wire story count as the agency's"
    (let [[one] (news/collapse-similar
                 [(item 1 "Yahoo" :url "https://news.yahoo.com/x" :summary "WASHINGTON (AP) — The Senate voted." :emb fed)
                  (item 2 "ABC" :url "https://abcnews.go.com/x" :summary "WASHINGTON (AP) — The Senate voted." :emb fed)
                  (item 3 "AP" :url "https://apnews.com/x" :emb fed)
                  (item 4 "Guardian" :url "https://www.theguardian.com/x" :emb fed-again)]
                 0.55)]
      (is (= ["AP" "AP" "theguardian.com"] (map :origin (:also one))))
      (is (= 2 (news/outlets one)) "AP and the Guardian"))))

(deftest tldr-reads-the-standfirst
  (is (= "Rates up, tech down"
         (news/tldr "# The day\n\n> Rates up, tech down\n\n## Overview\n\nText.")))
  (is (= "Only line"
         (news/tldr "> Only line\n\n## Overview\n\nText.")))
  (testing "a blockquote after the first section is not the tldr"
    (is (nil? (news/tldr "# The day\n\n## Overview\n\n> quoted in a section\n\nText."))))
  (testing "no blockquote, no tldr"
    (is (nil? (news/tldr "# The day\n\n## Overview\n\nText."))))
  (is (nil? (news/tldr "")))
  (testing "a standfirst that cites its sources is shown without the citations"
    (is (= "Rates up, tech down."
           (news/tldr "# The day\n\n> Rates up [[1]](https://e.com/1), tech down [2].\n\n## Overview")))))

(deftest a-briefing-is-read-back-whole-without-what-belongs-to-its-day
  (let [doc (str "# The day\n\n> Rates up [1].\n\n## Overview\n\nRates rose [[1]](https://e.com/1).\n\n"
                 "## How it all connects\n\n```mermaid\nflowchart LR\n```\n\nThe links [2, 3].\n\n"
                 "## Sources\n\n- [1] [Rates](https://e.com/1)\n")
        body (news/body doc 10000 2)]
    (is (str/includes? body "### The day"))
    (is (str/includes? body "#### How it all connects\n\nThe links."))
    (is (str/includes? body "Rates rose."))
    (is (not (str/includes? body "mermaid")) "the diagram is the day's")
    (is (not (str/includes? body "e.com")) "and so are its citations and sources")
    (is (= "### The day" (news/body doc 5 2)) "cut at a paragraph")))

(deftest a-story-the-last-briefing-told-is-marked
  (let [src (fn [n & kvs] (apply assoc {:n n :title (str "T" n) :url (str "https://e.com/" n) :source "S"
                                        :summary "" :published nil} kvs))
        p (news/render-prompt "{{sources}}" "d"
                              [(src 1 :covered true) (src 2)
                               (src 3 :group "G1" :group-title "Talks" :covered true) (src 4 :group "G1")]
                              nil)]
    (is (str/includes? p "[1] T1 (S)\nhttps://e.com/1\nAlready in the last briefing"))
    (is (not (str/includes? p "[2] T2 (S)\nhttps://e.com/2\nAlready")))
    (is (str/includes? p "Story: Talks\nAlready in the last briefing"))
    (is (= 2 (count (re-seq #"Already in the last briefing" p))) "a story's reports share its header's mark")))

(deftest the-stories-a-briefing-must-cover
  (let [s (fn [n group imp] (assoc (item n "Wire") :group group :group-title (str "Story " group) :importance imp))
        sources [(s 1 "G1" 9) (s 2 "G1" 9) (s 3 "G2" 8) (s 4 "G3" 5) (assoc (s 5 "G4" 9) :precedent true)]]
    (is (= [{:title "Story G2" :ns [3]}] (news/missed-stories sources "Told [2]." 8))
        "a story counts as covered when any of its reports is cited")
    (is (= [] (news/missed-stories sources "Told [2], [3]." 8)))
    (is (= [] (news/missed-stories sources "Nothing." nil)) "no threshold, nothing is a must")
    (let [missed [{:title "Kospi plunges" :ns [3 4]}]]
      (is (str/includes? (news/coverage-request missed) "- Kospi plunges [3, 4]"))
      (is (= "# Day\n\nText [1].\n\n## Also today\n\n- Kospi plunges [3, 4]"
             (news/also-today "# Day\n\nText [1].\n" missed))))))

(deftest analysis-is-labelled-for-the-analyst-and-the-critic
  (let [news-src (assoc (item 1 "Wire") :group "G1" :group-title "Ceasefire")
        piece (assoc (item 2 "Daniel Davis" :summary "It won't hold.") :group "G1" :group-title "Ceasefire"
                     :kind :analysis)
        lone (assoc (item 3 "Glenn Diesen") :kind :analysis :days 3)
        block (news/source-block [news-src piece lone])]
    (is (str/includes? block "[2] Story 2 (Daniel Davis, analysis)"))
    (is (str/includes? block "Story: Ceasefire") "under the story it argues about")
    (is (str/includes? block "Sources marked analysis are commentary"))
    (is (re-find #"\[1\][^\[]*Coverage" (news/source-block [(assoc (item 1 "Wire") :days 3)]))
        "a report running for days shows its coverage")
    (is (not (re-find #"\[3\][^\[]*Coverage" block)) "an analysis is no coverage")
    (is (not (str/includes? (news/source-block [news-src]) "marked analysis")) "no analysis, no note")))
