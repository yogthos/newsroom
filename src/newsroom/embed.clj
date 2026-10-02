(ns newsroom.embed
  "In-process embeddings on the CPU, with no model server and no FFI.

  The model is potion-mxbai-128d-v2, a static embedding model: there is no
  transformer, just a WordPiece vocabulary (29,525 tokens) where every token
  has one learned 128-dim vector. A sentence's vector is the weighted mean
  of its token vectors, L2-normalized, which matches model2vec's own
  inference to 1e-6 (test/newsroom/embed_test.clj checks golden vectors).

  That makes the whole model 6MB of data, packaged as resources/embed by
  dev/make-embed-model.clj. The int8 matrix is stored one byte per char,
  offset by 256 so no control character can be mangled by a reader; a
  token's vector is a char-range read straight off the slurped string, no
  parsing at all."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def dim 128)

(defrecord Model [vocab weights embeddings])

;; --- loading ------------------------------------------------------------------

(defn load-model
  "The packaged model from resources/embed. Reads ~6MB and builds a
  29,525-entry token lookup, so hold the result and reuse it."
  []
  (let [vocab (into {} (map-indexed (fn [i tok] [tok i]))
                    (str/split-lines (slurp (io/resource "embed/vocab.txt"))))
        weights (vec (read-string (slurp (io/resource "embed/weights.edn"))))
        embeddings (slurp (io/resource "embed/embeddings.txt"))]
    (when (or (nil? (vocab "[UNK]")) (not= (count weights) (count vocab)))
      (throw (ex-info "embed resources don't line up" {})))
    (->Model vocab weights embeddings)))

;; --- tokenizing ---------------------------------------------------------------

(defn- strip-accents
  "NFD normalization with the combining marks dropped, like BERT's
  default accent stripping."
  [s]
  (->> (java.text.Normalizer/normalize s java.text.Normalizer$Form/NFD)
       (remove #(= Character/NON_SPACING_MARK (Character/getType %)))
       (apply str)))

(defn- wordpiece
  "Greedy longest-match WordPiece, the standard BERT subword split: the
  longest vocabulary entry at the head of the word, the rest as ##pieces
  until the word is used up. A word with an unresolvable piece is dropped,
  which nets out the same as BERT's [UNK] plus model2vec dropping it."
  [vocab word]
  (loop [start 0, out []]
    (if (>= start (count word))
      out
      (let [cand (fn [end] (str (when (pos? start) "##") (subs word start end)))
            hit (loop [end (count word)]
                  (cond
                    (< end (inc start)) nil
                    (contains? vocab (cand end)) end
                    :else (recur (dec end))))]
        (if hit
          (recur hit (conj out (cand hit)))
          out)))))

(defn tokenize
  "A text as vocabulary tokens, lowercased and accent-stripped, split into
  words of letters and digits with every other non-space character a word
  of its own, then greedy longest-match WordPiece inside each word."
  [model text]
  (let [norm (-> text str/lower-case strip-accents)
        {:keys [vocab]} model]
    (into []
          (mapcat (partial wordpiece vocab))
          (re-seq #"[^\W_]+|\S" norm))))

(defn- token-ids
  "Vocabulary ids for the tokens, dropping anything unknown."
  [model text]
  (keep (:vocab model) (tokenize model text)))

;; --- embedding ----------------------------------------------------------------

(defn- token-vector
  "The int8 row for a token id, dequantized: each char of the matrix string
  is the byte's unsigned value + 256, so flip anything >= 128 negative."
  [model id]
  (mapv (fn [j] (let [u (- (int (nth (:embeddings model) (+ (* id dim) j))) 256)]
                  (if (< u 128) u (- u 256))))
        (range dim)))

(defn embed
  "The 128-dim unit vector for a text, or nil when nothing in it is in the
  vocabulary. The weighted mean of the token vectors, L2-normalized."
  [model text]
  (let [ids (token-ids model text)]
    (when (seq ids)
      (let [{:keys [weights]} model
            n (count ids)
            sum (reduce (fn [acc id]
                          (let [v (token-vector model id)
                                w (nth weights id)]
                            (mapv + acc (mapv #(* % w) v))))
                        (vec (repeat dim 0.0))
                        ids)
            mean (mapv #(/ % n) sum)
            norm (Math/sqrt (reduce + (map * mean mean)))]
        (when (pos? norm)
          (mapv #(/ % norm) mean))))))

(defn cosine
  "Cosine similarity of two vectors; 0 when either is empty or nil."
  [a b]
  (if (or (nil? a) (nil? b) (empty? a) (empty? b))
    0.0
    (let [dot (reduce + (map * a b))
          na (Math/sqrt (reduce + (map * a a)))
          nb (Math/sqrt (reduce + (map * b b)))]
      (if (or (zero? na) (zero? nb))
        0.0
        (/ dot (* na nb))))))

(defn unit
  "The vector scaled to length 1, or nil when it has none."
  [v]
  (let [n (when (seq v) (Math/sqrt (reduce + (map * v v))))]
    (when (and n (pos? n)) (mapv #(/ % n) v))))

(defn centroid
  "The direction of the vectors' mean, as a unit vector; nils don't count
  toward it, and it is nil when there are none."
  [vs]
  (let [vs (remove nil? vs)]
    (when (seq vs)
      (unit (reduce (fn [acc v] (mapv + acc v)) vs)))))

;; --- storing ------------------------------------------------------------------

(def ^:private hex "0123456789abcdef")

(def ^:private nibble (zipmap hex (range)))

(defn encode
  "A vector as 2 hex digits a dimension, quantized to a signed byte scaled
  by its largest component: 256 characters for the model's 128 dims. The
  scale is dropped, since cosine similarity doesn't see it."
  [v]
  (when (seq v)
    (let [top (reduce max (map #(Math/abs (double %)) v))]
      (apply str (mapcat (fn [x]
                           (let [b (+ 128 (if (zero? top) 0 (Math/round (* 127.0 (/ x top)))))]
                             [(nth hex (quot b 16)) (nth hex (rem b 16))]))
                         v)))))

(defn decode
  "An encoded vector back as a unit vector, or nil for a blank or malformed one."
  [s]
  (when (and (string? s) (pos? (count s)) (even? (count s)) (every? nibble s))
    (let [v (mapv (fn [[a b]] (- (+ (* 16 (nibble a)) (nibble b)) 128.0))
                  (partition 2 s))
          norm (Math/sqrt (reduce + (map * v v)))]
      (when (pos? norm) (mapv #(/ % norm) v)))))
