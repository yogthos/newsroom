#!/usr/bin/env jolt
;; Package potion-mxbai-128d-v2 into newsroom resources.
;;
;; Downloads the model (a static embedding table: WordPiece vocab, per-token
;; weights, and a 29525x128 int8 matrix) and writes three files under
;; resources/embed/:
;;
;;   vocab.txt      one token per line, line number = token id
;;   weights.edn    EDN vector of per-token weights (floats)
;;   embeddings.txt the int8 matrix, one char per byte with +256 added to the
;;                  code point (256-511, so no control chars or newline
;;                  translation can mangle it), row-major; embedding i for
;;                  token id t starts at char t*128
;;
;; The char-per-byte file is UTF-8 on disk (~6.5MB) but needs no parsing:
;; slurp it and index by char position. Run once from the repo root; the
;; outputs are committed.
;;
;;   jolt dev/make-embed-model.clj
(require '[clojure.data.json :as json]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[jolt.http-client :as http])

(def base "https://huggingface.co/blobbybob/potion-mxbai-128d-v2/resolve/main")
(def out "resources/embed")
(def dim 128)

(defn- die [& msg]
  (binding [*out* *err*] (println (apply str msg)))
  (System/exit 1))

(defn- fetch
  "The file's bytes from the model repo."
  [name]
  (println "downloading" name)
  (let [{:keys [status body]} (http/get (str base "/" name) {:as :byte-array})]
    (when-not (= 200 status) (die name ": HTTP " status))
    body))

(defn- u8 [bs i] (bit-and (aget bs i) 0xff))

(defn- le-long
  "The `n`-byte little-endian unsigned integer at `off`."
  [bs off n]
  (reduce (fn [acc i] (+ (* acc 256) (u8 bs (+ off i)))) 0 (range (dec n) -1 -1)))

(defn- f32
  "The little-endian IEEE 754 single at `off`. Jolt has no
  Float/intBitsToFloat, so it is assembled from the bits."
  [bs off]
  (let [bits (le-long bs off 4)
        sign (if (bit-test bits 31) -1.0 1.0)
        e (bit-and (bit-shift-right bits 23) 0xff)
        m (bit-and bits 0x7fffff)]
    (cond
      (= e 255) (if (zero? m) (* sign Double/POSITIVE_INFINITY) Double/NaN)
      (zero? e) (* sign (Math/pow 2 -126) (/ m 8388608.0))
      :else (* sign (Math/pow 2 (- e 127)) (+ 1.0 (/ m 8388608.0))))))

(defn- safetensors
  "The tensors in a safetensors file, by name: {:dtype :shape :bytes},
  where :bytes is [start end) into `bs`. The file is an 8-byte header
  length, the JSON header, then the raw tensor data."
  [bs]
  (let [n (le-long bs 0 8)
        header (json/read-str (String. (byte-array (map #(aget bs %) (range 8 (+ 8 n)))) "UTF-8"))
        data (+ 8 n)]
    (into {}
          (keep (fn [[k {:strs [dtype shape data_offsets]}]]
                  (when data_offsets
                    (let [[a b] data_offsets]
                      [k {:dtype dtype :shape shape :bytes [(+ data a) (+ data b)]}]))))
          header)))

(defn- floats
  "A tensor's values as doubles: F32 read from its bytes, or I64/I32 as
  integers."
  [bs {:keys [dtype] [a b] :bytes}]
  (case dtype
    "F32" (mapv #(f32 bs %) (range a b 4))
    "I64" (mapv #(le-long bs % 8) (range a b 8))
    "I32" (mapv #(le-long bs % 4) (range a b 4))
    (die "unexpected dtype " dtype)))

(defn -main []
  (let [_ (fetch "config.json")
        model (fetch "model.safetensors")
        tokenizer (json/read-str (String. (fetch "tokenizer.json") "UTF-8"))
        tensors (safetensors model)
        missing (remove tensors ["embeddings" "weights" "mapping"])
        _ (when (seq missing)
            (die "model.safetensors is missing " (vec missing) "; not a model2vec checkpoint?"))
        {emb-dtype :dtype [rows cols] :shape [emb-start emb-end] :bytes} (tensors "embeddings")
        _ (when-not (and (= "I8" emb-dtype) (= dim cols))
            (die "unexpected embeddings " emb-dtype " " [rows cols]))
        weights (floats model (tensors "weights"))
        mapping (floats model (tensors "mapping"))
        _ (when-not (= mapping (range (count mapping)))
            (die "mapping is not identity; the loader would need it"))
        ;; the vocab is a token -> id object, and a parsed map has no order,
        ;; so the lines are laid out by id
        vocab (get-in tokenizer ["model" "vocab"])
        by-id (mapv first (sort-by val vocab))
        _ (when-not (and (= (count vocab) rows) (= (range rows) (sort (vals vocab))))
            (die "vocab " (count vocab) " doesn't number the " rows " matrix rows"))
        matrix (let [sb (StringBuilder.)]
                 ;; each byte as one char in 256-511: raw 0-255 would put \r
                 ;; and \n inside the data, and a text-mode reader would
                 ;; translate them
                 (doseq [i (range emb-start emb-end)]
                   (.append sb (char (+ 256 (u8 model i)))))
                 (str sb))]
    (.mkdirs (io/file out))
    (spit (io/file out "vocab.txt") (str (str/join "\n" by-id) "\n"))
    (spit (io/file out "weights.edn") (str "[" (str/join " " (map #(format "%.6f" %) weights)) "]"))
    (spit (io/file out "embeddings.txt") matrix)
    (println (str "wrote vocab.txt (" (count by-id) " tokens), weights.edn, embeddings.txt to " out))))

(-main)
