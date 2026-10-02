(ns newsroom.news-test
  (:require [clojure.test :refer [deftest is testing]]
            [newsroom.news :as news]))

(defn- item [n source & {:keys [title url summary emb]}]
  (cond-> {:n n
           :title (or title (str "Story " n))
           :url (or url (str "https://e.com/" n))
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
      (is (= ["AP"] (:also (first out))) "the outlets that also carry it")
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
    (is (= [["BBC"] nil] (mapv :also out)))
    (is (= 2 (count out)) "the second Reuters story is its own source")))

(deftest collapse-similar-clusters-by-centroid
  (let [a (unit [10 0 0]) b (unit [9 1 0]) c (unit [0 10 0]) d (unit [1 9 0])
        out (news/collapse-similar [(item 1 "A" :emb a)
                                    (item 2 "B" :emb b)
                                    (item 3 "C" :emb c)
                                    (item 4 "D" :emb d)]
                                   0.9)]
    (is (= 2 (count out)) "two clusters of two")))

(deftest tldr-reads-the-standfirst
  (is (= "Rates up, tech down"
         (news/tldr "# The day\n\n> Rates up, tech down\n\n## Overview\n\nText.")))
  (is (= "Only line"
         (news/tldr "> Only line\n\n## Overview\n\nText.")))
  (testing "a blockquote after the first section is not the tldr"
    (is (nil? (news/tldr "# The day\n\n## Overview\n\n> quoted in a section\n\nText."))))
  (testing "no blockquote, no tldr"
    (is (nil? (news/tldr "# The day\n\n## Overview\n\nText."))))
  (is (nil? (news/tldr ""))))
