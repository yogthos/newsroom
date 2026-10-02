(ns newsroom.embed-test
  "Embeddings against golden vectors from model2vec 0.9.0 running
  blobbybob/potion-mxbai-128d-v2 (dev/make-embed-model.clj packages it).
  Vectors are the first 6 of 128 dims, normalized; Clojure sums in float64
  against python's float32, so 1e-5 is the tolerance."
  (:require [clojure.test :refer [deftest is testing]]
            [newsroom.embed :as embed]))

(def model (delay (embed/load-model)))

(def goldens
  "Text, its WordPiece tokens, first 6 dims of its embedding."
  [["The Federal Reserve raised interest rates."
    ["the" "federal" "reserve" "raised" "interest" "rates" "."]
    [0.139256 0.261225 -0.240683 -0.001192 0.064750 0.102108]]
   ["The central bank lifted its policy rate."
    ["the" "central" "bank" "lifted" "its" "policy" "rate" "."]
    [0.119983 0.189574 -0.122903 -0.079794 0.118920 0.011522]]
   ["Scientists discovered a new exoplanet around Proxima Centauri."
    ["scientists" "discovered" "a" "new" "ex" "##op" "##lane" "##t"
     "around" "pro" "##xi" "##ma" "cent" "##aur" "##i" "."]
    [0.165043 0.055347 0.263839 0.300228 0.077816 0.108577]]
   ["Tariffs on steel imports hit manufacturing jobs."
    ["tariffs" "on" "steel" "imports" "hit" "manufacturing" "jobs" "."]
    [0.240028 0.185892 -0.099877 0.149467 0.261693 -0.002532]]])

(deftest tokenizes-like-model2vec
  (doseq [[text tokens _] goldens]
    (is (= tokens (embed/tokenize @model text)) text)))

(deftest matches-model2vec-vectors
  (doseq [[text _ dims] goldens
          :let [v (embed/embed @model text)]]
    (is (= 128 (count v)) text)
    (is (< (Math/abs (- 1.0 (Math/sqrt (reduce + (map * v v))))) 1e-9)
        (str text " not unit length"))
    (doseq [[i g] (map-indexed vector dims)]
      (is (< (Math/abs (- (nth v i) g)) 1e-5)
          (str text " dim " i ": " (nth v i) " vs " g)))))

(deftest cosine-separates-related-from-unrelated
  (let [[a b c _] (map (fn [[t _ _]] (embed/embed @model t)) goldens)
        fed-bank (embed/cosine a b)
        fed-space (embed/cosine a c)]
    (is (> fed-bank 0.3))
    (is (< fed-space 0.0))
    (is (> fed-bank fed-space))))

(deftest nothing-to-embed
  (testing "punctuation is in the vocabulary, so anything can embed"
    (is (some? (embed/embed @model "?!?")))
    (is (= ["."] (embed/tokenize @model "."))))
  (testing "no tokens at all"
    (is (nil? (embed/embed @model "")))
    (is (nil? (embed/embed @model "         ")))
    (is (= 1.0 (embed/cosine [1 0] [1 0])))
    (is (= 0.0 (embed/cosine nil [1 0])))))

(deftest a-stored-vector-keeps-its-direction
  (let [v (embed/embed @model "The Federal Reserve raised interest rates.")
        s (embed/encode v)]
    (is (= 256 (count s)) "two hex digits a dimension")
    (is (> (embed/cosine v (embed/decode s)) 0.999))
    (is (nil? (embed/decode nil)))
    (is (nil? (embed/decode "zz")))
    (is (nil? (embed/encode nil)))))
