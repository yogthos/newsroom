(ns newsroom.search
  "Searching what newsroom has kept: the briefings, the digests, every
  source gathered for a day, and the storylines' notes and facts.

  The store indexes them with sqlite's FTS5 (see newsroom.store), which
  matches a word by its stem, so tariff finds tariffs, and ranks by bm25.
  A word is also matched as the start of a longer one. On top of that a
  word the index has never seen is taken for a misspelling and matched by
  the words it has that are a letter or two off, so tarrifs finds tariffs:
  the near words come from a second index of the words as written, not
  stemmed, and are worked out here, by edit distance.

  The query is plain text: words, which must all match, and \"quoted
  phrases\", which must match in order. Nothing in it is read as the
  index's own syntax."
  (:require [clojure.string :as str]
            [newsroom.store :as store]))

;; --- the query -------------------------------------------------------------------

(defn- tokens
  "The words in `s`, lowercased, split as the index splits them: on
  anything that isn't a letter or a digit."
  [s]
  (vec (re-seq #"[\p{L}\p{N}]+" (str/lower-case s))))

(defn query-terms
  "The terms of the query `q`: {:word w} for a word, {:phrase [w ...]} for
  a quoted phrase, or for a word with punctuation inside it, like 4.25 or
  U.S., which the index keeps as words in a row."
  [q]
  (vec (for [chunk (re-seq #"\"[^\"]*\"?|[^\s\"]+" (str q))
             :let [ws (tokens chunk)]
             :when (seq ws)]
         (if (or (str/starts-with? chunk "\"") (next ws))
           (if (next ws) {:phrase ws} {:word (first ws)})
           {:word (first ws)}))))

(defn distance
  "The edit distance between `a` and `b`: how many letters must be
  inserted, deleted, changed, or swapped with the next, to make one the
  other."
  [a b]
  (let [a (vec a) b (vec b) n (count a) m (count b)]
    (loop [i 1
           prev2 nil
           prev (vec (range (inc m)))]
      (if (> i n)
        (peek prev)
        (let [row (reduce (fn [row j]
                            (let [cost (if (= (a (dec i)) (b (dec j))) 0 1)
                                  d (min (inc (row (dec j)))
                                         (inc (prev j))
                                         (+ (prev (dec j)) cost))
                                  d (if (and prev2 (> i 1) (> j 1)
                                             (= (a (dec i)) (b (- j 2)))
                                             (= (a (- i 2)) (b (dec j))))
                                      (min d (inc (prev2 (- j 2))))
                                      d)]
                              (conj row d)))
                          [i]
                          (range 1 (inc m)))]
          (recur (inc i) prev row))))))

(defn- allowed
  "How many letters a word of `n` letters may be off: none for the short
  ones, where one letter makes another word, two for the long ones."
  [n]
  (cond (< n 4) 0 (< n 7) 1 :else 2))

(def ^:private max-near 4)

(def ^:private rarity
  "How many times rarer than the commonest guess a guess may be: a word
  that turns up in a document or two beside one in hundreds is more
  likely a coincidence of spelling than what was meant."
  10)

(defn near
  "The words of `vocab`, {word documents}, that `word` may have been meant
  as: the ones a letter off, or two off and starting with the same letter,
  since a misspelling rarely starts wrong, and not far rarer than the
  commonest of them; closest first, then the ones of its length, then the
  commonest. None when the index has the word, or when the word is the
  start of them, which its prefix already matches."
  [word vocab]
  (let [k (allowed (count word))
        initial (first word)]
    (if (or (zero? k) (contains? vocab word))
      []
      (let [found (keep (fn [[w docs]]
                          (let [dl (abs (- (count w) (count word)))]
                            (when (and (<= dl k) (not (str/starts-with? w word)))
                              (let [d (distance word w)]
                                (when (or (= d 1) (and (= d 2) (<= d k) (= initial (first w))))
                                  [d dl (- docs) w])))))
                        vocab)
            most (reduce max 0 (map #(- (nth % 2)) found))]
        (->> found
             (filter #(>= (* rarity (- (nth % 2))) most))
             sort
             (take max-near)
             (mapv peek))))))

(defn- quoted [w] (str "\"" (str/replace w "\"" "\"\"") "\""))

(defn match-expr
  "The FTS5 expression for `terms`: every term must match, a word or any
  of its `corrections`, {word [near words]}, as written or, from three
  letters, as the start of a longer word; a phrase, its words in order."
  [terms corrections]
  (str/join " AND "
            (for [{:keys [word phrase]} terms]
              (str "("
                   (if phrase
                     (str/join " + " (map quoted phrase))
                     (str/join " OR " (cons (str (quoted word) (when (>= (count word) 3) "*"))
                                            (map quoted (get corrections word)))))
                   ")"))))

;; --- searching -------------------------------------------------------------------

(def kinds
  "What can be searched, in the order the results page offers them."
  [:day :digest :source :story :fact])

(defn- segments
  "A snippet, its matches between \u0002 and \u0003 as the store marks
  them, as [text matched?] pieces."
  [s]
  (vec (for [[_ marked plain] (re-seq #"\u0002([^\u0003]*)\u0003?|([^\u0002]+)" (str s))
             :let [text (or marked plain)]
             :when (seq text)]
         [text (some? marked)])))

(defn search
  "Search the store for `q`: {:results :counts :corrections}. Each result
  is {:kind :title :href :day :url :cited? :snippet}, its snippet as
  [text matched?] pieces; :counts is how many of each kind match, whatever
  `opts` narrows to; :corrections is {word [near words]} for the words the
  index didn't have. `opts`: :kind, one of `kinds`; :limit and :offset;
  :sort :newest for the latest first rather than the best matches."
  [st q {:keys [kind limit offset sort] :or {limit 30 offset 0}}]
  (let [terms (query-terms q)]
    (if (empty? terms)
      {:results [] :counts {} :corrections {}}
      (let [vocab (store/vocabulary st)
            corrections (into {}
                              (keep (fn [{:keys [word]}]
                                      (when word
                                        (when-let [ws (seq (near word vocab))] [word (vec ws)]))))
                              terms)
            expr (match-expr terms corrections)]
        {:results (mapv #(update % :snippet segments)
                        (store/search st expr {:kind kind :limit limit :offset offset :sort sort}))
         :counts (store/search-counts st expr)
         :corrections corrections}))))
