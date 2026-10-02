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
