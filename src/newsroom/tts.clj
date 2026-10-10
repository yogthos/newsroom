(ns newsroom.tts
  "Speech, from KittenTTS 2 run in-process: its speech language model
  through llama.cpp and its S3 decoder ported to ggml, both behind
  native/newsroom_tts.cpp, and the MP3 through LAME, all reached through
  jolt.ffi. `jolt tts` builds the native library.

  The model's files come from Hugging Face the first time speech is made,
  about 2 GB, at the revisions pinned in `downloads`, and are converted
  once into the forms the engine loads: the speech model's ternary GGUF to
  Q4_0, which holds the same weights exactly and runs on any CPU, the
  decoder's safetensors to a GGUF, and the voices to one. They are kept in
  the config directory's tts/, or NEWSROOM_TTS_DIR.

  A podcast is a list of lines, each {:voice :text}; `synthesize` speaks
  them in turn, in one loaded model, and returns the episode as MP3. A line
  is written out the way it is spoken first (`spoken`) and split at its
  sentences into chunks the model reads well (`chunks`), as the upstream
  kittenml package does; the model's own markup, a leading [emotion] and
  (((emphasis))), passes through. Its vocal events, <laugh> and the like,
  are dropped: this checkpoint says them as words."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.ffi :as ffi]
            [jolt.http-client :as http]
            [newsroom.config :as config]))

;; --- the native library --------------------------------------------------------
;; :blocking calls may run while the collector does, so they take no :string
;; arguments: every string goes in as an arena's pointer.
;;
;; Two engines can be there. The one linked into newsroom runs on the CPU,
;; and on macOS on Metal. A GPU plugin, libnewsroom_tts_gpu, built for CUDA,
;; HIP or Vulkan (native/build_tts.sh), is the same engine with its entry
;; points named nrttsg_; it is loaded when it's beside the binary, in
;; native/, or at NEWSROOM_TTS_GPU_LIB, and its own libraries, the GPU's
;; driver among them, are there to load it. Each binding calls the plugin's
;; function when it loaded, else the linked engine's.

(def ^:private plugin-name
  (if (re-find #"(?i)mac|darwin" (System/getProperty "os.name")) "libnewsroom_tts_gpu.dylib" "libnewsroom_tts_gpu.so"))

(defn- executable-dir
  "The directory the running binary is in, where it can be told."
  []
  (try (some-> (io/file "/proc/self/exe") .getCanonicalFile .getParentFile .getPath)
       (catch Throwable _ nil)))

(def ^:private plugin
  "Whether the GPU plugin loaded, tried once."
  (delay
    (let [candidates (distinct (remove nil? [(not-empty (System/getenv "NEWSROOM_TTS_GPU_LIB"))
                                             (some-> (executable-dir) (io/file plugin-name) .getPath)
                                             (.getPath (io/file "native" plugin-name))]))
          present (filter #(.exists (io/file %)) candidates)]
      (when (seq present)
        (try (ffi/load-library (vec present))
             (some? (ffi/find-symbol "nrttsg_open"))
             (catch Throwable e
               ;; most often the GPU's own libraries aren't installed
               (binding [*out* *err*]
                 (println "newsroom: the speech engine's GPU plugin didn't load, so it runs on the CPU:"
                          (ex-message e)))
               false))))))

(defmacro ^:private defengine
  "A binding of the engine's `sym` that calls the plugin's when it loaded,
  else the linked engine's."
  [name sym args ret & opts]
  (let [cpu (symbol (str name "-cpu")) gpu (symbol (str name "-gpu"))]
    `(do (ffi/defcfn ~cpu ~(str "nrtts_" sym) ~args ~ret ~@opts)
         (ffi/defcfn ~gpu ~(str "nrttsg_" sym) ~args ~ret ~@opts)
         (defn- ~name [& xs#] (apply (if @plugin ~gpu ~cpu) xs#)))))

(defengine convert-lm* "convert_lm" [:pointer :pointer :pointer :int] :int :blocking)
(defengine convert-decoder* "convert_decoder" [:pointer :pointer :pointer :int] :int :blocking)
(defengine convert-voices* "convert_voices" [:pointer :pointer :pointer :pointer :int] :int :blocking)
(defengine open* "open" [:pointer :pointer :pointer :int :int] :pointer :blocking)
(defengine ok* "ok" [:pointer] :int)
(defengine error* "error" [:pointer] :string)
(defengine device* "device" [:pointer] :string)
(defengine free* "free" [:pointer] :void)
(defengine speak* "speak"
  [:pointer :pointer :pointer :int :float :int :float :float :uint32 :int :pointer] :int :blocking)
(defengine mp3-open* "mp3_open" [:int :int] :pointer)
(defengine mp3-add-speech* "mp3_add_speech" [:pointer :pointer :int] :int :blocking)
(defengine mp3-finish* "mp3_finish" [:pointer] :int :blocking)
(defengine mp3-bytes* "mp3_bytes" [:pointer] :pointer)
(defengine mp3-seconds* "mp3_seconds" [:pointer] :double)
(defengine mp3-close* "mp3_close" [:pointer] :void)

(defn native?
  "Whether the speech engine is there: the GPU plugin, or the engine
  linked into newsroom."
  []
  (or @plugin (some? (ffi/find-symbol "nrtts_open"))))

;; --- the model's files ---------------------------------------------------------

(def ^:private kitten "https://huggingface.co/KittenML/kitten-tts-2/resolve/f2eee74f4467855ad58fe16f1fb7b7df473263fd/")
(def ^:private chatterbox
  "https://huggingface.co/ResembleAI/chatterbox-turbo/resolve/749d1c1a46eb10492095d68fbcf55691ccf137cd/")

(def downloads
  "What is fetched, by the file it is saved as, with its size."
  {"model-tq2_1.gguf" {:url (str kitten "cpp/model-tq2_1.gguf") :size 1029076832}
   "voices.json" {:url (str kitten "cpp/default/voices.json") :size 39235846}
   "config.json" {:url (str kitten "config.json")}
   "s3gen_meanflow.safetensors" {:url (str chatterbox "s3gen_meanflow.safetensors") :size 1064875036}})

(def ^:private engine-files
  "What the engine loads, each converted from the downloads named."
  {:lm {:file "model-q4_0.gguf" :from ["model-tq2_1.gguf"]}
   :decoder {:file "s3gen.gguf" :from ["s3gen_meanflow.safetensors"]}
   :voices {:file "voices.gguf" :from ["voices.json" "config.json"]}})

(defn home
  "Where the model's files are kept."
  []
  (or (not-empty (System/getenv "NEWSROOM_TTS_DIR")) (config/path "tts")))

(defn- file-of [k] (io/file (home) (get-in engine-files [k :file])))

(defn prepared?
  "Whether the converted files the engine loads are all in place."
  []
  (every? #(.exists (file-of %)) (keys engine-files)))

(defn available?
  "Whether speech can be made: the native library is loaded. The model's
  files are fetched the first time it is."
  []
  (native?))

(defn- download!
  "Fetch `name` into the model's directory unless it is there, through a
  .part file, telling `report` how far it has got."
  [name report]
  (let [{:keys [url size]} (get downloads name)
        dest (io/file (home) name)
        part (io/file (home) (str name ".part"))]
    (when-not (and (.exists dest) (or (nil? size) (= size (.length dest))))
      (report (str "Downloading the speech model's " name))
      (let [{:keys [status body]} (http/get url {:as :stream :timeout 600000})]
        (when-not (= 200 status)
          (throw (ex-info (str "could not download " url ": HTTP " status) {:status status})))
        (with-open [in body
                    out (io/output-stream part)]
          (let [buf (byte-array (* 1024 1024))]
            (loop [total 0, shown 0]
              (let [n (.read in buf)]
                (when (pos? n)
                  (.write out buf 0 n)
                  (let [total (+ total n)
                        mb (quot total (* 100 1024 1024))]
                    (when (and size (> mb shown))
                      (report (str "Downloading the speech model's " name ", "
                                   (quot (* 100 total) size) "%")))
                    (recur total (max shown mb))))))))
        (when (and size (not= size (.length part)))
          (.delete part)
          (throw (ex-info (str "the download of " name " was cut short") {:name name})))
        (.renameTo part dest)))))

(defn- converted!
  "Convert `k`'s downloads with `convert`, unless its file is there, and
  delete them after."
  [k convert report]
  (let [{:keys [file from]} (get engine-files k)
        dest (io/file (home) file)]
    (when-not (.exists dest)
      (doseq [f from] (download! f report))
      (report (str "Converting the speech model's " (first from)))
      (ffi/with-arena [a]
        (let [err (ffi/alloc a 1024)
              paths (map #(ffi/string->ptr a (.getPath (io/file (home) %))) from)
              rc (apply convert (concat paths [(ffi/string->ptr a (.getPath dest)) err (int 1024)]))]
          (when-not (zero? rc)
            (throw (ex-info (str "could not convert " (first from) ": " (ffi/ptr->string err)) {:file file})))))
      (doseq [f from] (.delete (io/file (home) f))))))

(defn prepare!
  "Fetch and convert the model's files that aren't in place yet, telling
  `report` what it is doing. Throws when the native library isn't built."
  ([] (prepare! (fn [_])))
  ([report]
   (when-not (native?)
     (throw (ex-info "the speech engine isn't built: run jolt tts" {})))
   (.mkdirs (io/file (home)))
   (converted! :lm convert-lm* report)
   (converted! :decoder convert-decoder* report)
   (converted! :voices convert-voices* report)))

;; --- text, as it is spoken -----------------------------------------------------

(def ^:private ones
  ["zero" "one" "two" "three" "four" "five" "six" "seven" "eight" "nine" "ten" "eleven" "twelve"
   "thirteen" "fourteen" "fifteen" "sixteen" "seventeen" "eighteen" "nineteen"])

(def ^:private tens ["" "" "twenty" "thirty" "forty" "fifty" "sixty" "seventy" "eighty" "ninety"])

(defn- under-thousand [n]
  (let [h (quot n 100), r (rem n 100)
        rest (cond (zero? r) nil
                   (< r 20) (ones r)
                   :else (str (tens (quot r 10)) (when (pos? (rem r 10)) (str "-" (ones (rem r 10))))))]
    (cond
      (zero? h) rest
      rest (str (ones h) " hundred and " rest)
      :else (str (ones h) " hundred"))))

(defn number-words
  "A whole number in words: 3200 is three thousand two hundred."
  [n]
  (cond
    (neg? n) (str "minus " (number-words (- n)))
    (zero? n) "zero"
    :else (->> [[1000000000000 "trillion"] [1000000000 "billion"] [1000000 "million"] [1000 "thousand"] [1 nil]]
               (reduce (fn [[n words] [unit name]]
                         (let [k (quot n unit)]
                           (if (pos? k)
                             [(rem n unit) (conj words (str (under-thousand k) (when name (str " " name))))]
                             [n words])))
                       [n []])
               second
               (str/join " "))))

(defn- year-words
  "A year the way it is said: nineteen ninety-nine, two thousand and eight,
  twenty twenty-six."
  [y]
  (let [hi (quot y 100), lo (rem y 100)]
    (cond
      (and (= 20 hi) (zero? lo)) "two thousand"
      (and (= 20 hi) (< lo 10)) (str "two thousand and " (number-words lo))
      (zero? lo) (str (number-words hi) " hundred")
      (< lo 10) (str (number-words hi) " oh " (number-words lo))
      :else (str (number-words hi) " " (number-words lo)))))

(defn- digits-words [s] (str/join " " (map #(ones (- (int %) 48)) s)))

(defn- read-number
  "A number as written, with its commas, as words."
  [s]
  (let [[whole frac] (str/split (str/replace s "," "") #"\." 2)
        w (number-words (parse-long whole))]
    (if (str/blank? frac) w (str w " point " (digits-words frac)))))

(def ^:private tag-re
  #"\[(?:mundane|nervous|tender|angry|excited|stern|sad|contemplative|surprised|joyful)\]|\(\(\([^()\n]{1,80}\)\)\)")

(def ^:private vocal-event-re
  #"(?i)\s*<(?:pause|sigh|gasp|laugh|giggle|sob|scoff|growl|um|gulp)>")

(defn expression?
  "Whether `text` uses the model's expression markup, which turns on its
  emotion conditioning."
  [text]
  (boolean (re-find tag-re (str text))))

(defn- protect
  "`text` with each of the model's tags swapped for a placeholder, a
  private-use character the rewriting leaves alone, and the tags."
  [text]
  (let [tags (atom [])]
    [(str/replace text tag-re (fn [m] (swap! tags conj m) (str (char (+ 0xE100 (dec (count @tags)))))))
     @tags]))

(defn- restore [text tags]
  (str/replace text #"[\uE100-\uE8FF]" (fn [c] (nth tags (- (int (first c)) 0xE100)))))

(defn- capitalize-and-end
  "The first letter capitalized and a closing mark at the end."
  [s]
  (let [s (str/trim s)
        i (some (fn [[i c]] (when (Character/isLetter c) i)) (map-indexed vector s))
        s (if i (str (subs s 0 i) (str/upper-case (subs s i (inc i))) (subs s (inc i))) s)]
    (cond
      (str/blank? s) s
      (re-find #"[.!?\"'’”)\]\uE100-\uE8FF]$" s) s
      :else (str (str/replace s #"[,;:\-–—]+$" "") "."))))

(defn spoken
  "`text` written out the way it is spoken, for the model: numbers, money,
  percentages and years in words, dashes and ellipses as commas, a bracket
  that isn't one of the model's tags as an aside, the first letter
  capitalized and a closing mark at the end. A vocal event like <laugh>
  goes, since the model would say it."
  [text]
  (let [[s tags] (protect (str/replace (str text) vocal-event-re ""))
        s (-> s
              (str/replace #"\s*\n+\s*" " ")
              (str/replace #"(?:\.\s+){2,}\.|…|\.\.\." ", ")
              (str/replace #"\[([^\[\]\n]{1,400})\]|<([^<>\n]{1,60})>|\(([^()\n]{1,400})\)"
                           (fn [[_ a b c]] (str ", " (str/trim (or a b c)) ",")))
              (str/replace #"\s*(?:—|–|--)\s*" ", ")
              (str/replace #"\$(\d[\d,]*(?:\.\d+)?)\s*(million|billion|trillion)?"
                           (fn [[_ n scale]] (str (read-number n) (when scale (str " " scale)) " dollars")))
              (str/replace #"(\d[\d,]*(?:\.\d+)?)\s*%" (fn [[_ n]] (str (read-number n) " percent")))
              (str/replace #"\b(1[1-9]\d\d|20\d\d)\b(?![,.]\d)" (fn [[_ y]] (year-words (parse-long y))))
              (str/replace #"\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+(?:\.\d+)?" read-number)
              (str/replace #",\s*([,.!?;:])" "$1")
              (str/replace #",\s*,+" ", ")
              (str/replace #"\s+([,.!?;:])" "$1")
              (str/replace #"^\s*,\s*" "")
              (str/replace #"[ \t]{2,}" " ")
              capitalize-and-end)]
    (restore s tags)))

(def ^:private chunk-chars 380)
(def ^:private chunk-min-chars 130)

(defn- merge-short
  "Fold chunks shorter than the minimum into a neighbour, the smaller
  result first, letting a chunk run to 1.4 times the budget."
  [parts]
  (let [limit (long (* chunk-chars 1.4))]
    (loop [out (vec parts)]
      (let [i (first (keep-indexed (fn [i c]
                                     (when (and (< (count c) chunk-min-chars)
                                                (or (and (pos? i) (<= (+ (count (out (dec i))) 1 (count c)) limit))
                                                    (and (< i (dec (count out)))
                                                         (<= (+ (count c) 1 (count (out (inc i)))) limit))))
                                       i))
                                   out))]
        (if (or (nil? i) (<= (count out) 1))
          out
          (let [c (out i)
                prev-ok (and (pos? i) (<= (+ (count (out (dec i))) 1 (count c)) limit))
                next-ok (and (< i (dec (count out))) (<= (+ (count c) 1 (count (out (inc i)))) limit))]
            (recur (if (and prev-ok (or (not next-ok) (<= (count (out (dec i))) (count (out (inc i))))))
                     (into (conj (subvec out 0 (dec i)) (str (out (dec i)) " " c)) (subvec out (inc i)))
                     (into (conj (subvec out 0 i) (str c " " (out (inc i)))) (subvec out (+ i 2)))))))))))

(defn chunks
  "`text` split at its sentences into chunks of at most 380 characters, as
  kittenml's split_for_synthesis does: the model compresses its delivery in
  a longer one and is unsteady on a short one. Every chunk ends with a mark."
  [text]
  (let [s (str/trim (str text))]
    (cond
      (str/blank? s) []
      (<= (count s) chunk-chars) [s]
      :else
      (let [parts (loop [[sentence & more] (remove str/blank? (str/split s #"(?<=[.!?])[\"’”')\]]*\s+"))
                         current ""
                         out []]
                    (if-not sentence
                      (cond-> out (seq current) (conj current))
                      (let [[heads sentence] (loop [sentence (str/trim sentence), heads []]
                                               (if (<= (count sentence) chunk-chars)
                                                 [heads sentence]
                                                 (let [cut (max (or (str/last-index-of sentence ", " chunk-chars) -1)
                                                                (or (str/last-index-of sentence "; " chunk-chars) -1))
                                                       cut (if (> cut (quot chunk-chars 3)) (inc cut) chunk-chars)]
                                                   (recur (str/trim (subs sentence cut))
                                                          (conj heads (str/trim (subs sentence 0 cut)))))))
                            out (if (seq heads) (cond-> out (seq current) (conj current)) out)
                            current (if (seq heads) "" current)
                            out (into out heads)]
                        (cond
                          (str/blank? current) (recur more sentence out)
                          (<= (+ (count current) 1 (count sentence)) chunk-chars) (recur more (str current " " sentence) out)
                          :else (recur more sentence (conj out current))))))]
        (mapv (fn [c] (if (re-find #"[.!?,;:\"'’”)\]]$" c) c (str c ",")))
              (merge-short parts))))))

;; --- speaking ------------------------------------------------------------------

(def sample-rate 24000)

(defn device-name
  "What the engine says it runs on, as a reader says it: the GPU, or the CPU,
  or both, when one of its two models didn't match its CPU copy on the GPU
  and runs on the CPU instead."
  [d]
  (let [d (str d)
        gpu (cond (re-find #"^MTL" d) "the GPU, through Metal"
                  (re-find #"(?i)^vulkan" d) "the GPU, through Vulkan"
                  (re-find #"(?i)^cuda" d) "the GPU, through CUDA"
                  (re-find #"(?i)^rocm|^hip" d) "the GPU, through ROCm")]
    (cond (= "CPU" d) "the CPU"
          (and gpu (str/includes? d "on the CPU)")) (str gpu " and the CPU")
          gpu gpu
          :else d)))

(def ^:private settings
  "The model's stable preset, and the bitrate of the episode."
  {:temperature 0.8 :top-k 50 :top-p 0.8 :min-p 0.0 :kbps 64})

(def ^:private chunk-gap-ms 160)
(def ^:private line-gap-ms 350)

(defn- token-budget
  "How many codec tokens a chunk may take: what its length needs at the
  model's delivery, with room to spare, as kittenml sizes it."
  [chunk]
  (max 200 (min 1000 (long (* (/ (count chunk) 20.0) 25 1.8)))))

(defn voices
  "The names of the built-in voices, in the model's order."
  []
  ["Bella" "Jasper" "Luna" "Bruno" "Rosie" "Hugo" "Kiki" "Leo" "Matthew" "Elliot" "Willow" "Dolores"
   "Victor" "Dante" "Alfred" "Saoirse" "Claire" "Raven" "Marcus" "Herbert" "Diana" "Laurence" "Maeve"
   "Walter" "Edith" "Miles" "Grace" "Reginald" "Iris" "Frank" "Serena" "Julian" "Eleanor" "Otis"
   "Vincent" "Martha" "Sable" "Victoria"])

(defn synthesize
  "The MP3 of `lines`, [{:voice :text}], spoken in order with a short
  pause between them: {:mp3 bytes :seconds audio-length}. `opts`:
  :on-progress, called with the lines done and the total as each is
  spoken, :on-status, told what the engine is doing while it fetches and
  converts its files, :on-device, told what it runs on once it has
  loaded (see `device-name`), :cancelled?, a
  function checked as the model writes, which stops the work with an
  exception when true, :device, :auto (the GPU when one comes up, else the
  CPU) or :cpu, :threads and :seed. Throws when speech isn't available."
  [lines {:keys [on-progress on-status on-device cancelled? device threads seed] :or {seed 1234}}]
  (prepare! (or on-status (fn [_])))
  (let [h (ffi/with-arena [a]
            (open* (ffi/string->ptr a (.getPath (file-of :lm)))
                   (ffi/string->ptr a (.getPath (file-of :decoder)))
                   (ffi/string->ptr a (.getPath (file-of :voices)))
                   (int (or threads 0))
                   (int (if (= :cpu device) 1 0))))]
    (when (zero? (ffi/address h)) (throw (ex-info "out of memory loading the speech engine" {})))
    (try
      (when (zero? (ok* h))
        (throw (ex-info (str "could not load the speech engine: " (error* h)) {})))
      (when on-device (on-device (device* h)))
      (let [{:keys [temperature top-k top-p min-p kbps]} settings
            e (mp3-open* (int sample-rate) (int kbps))
            total (count lines)]
        (when (zero? (ffi/address e)) (throw (ex-info "could not start the MP3 encoder" {})))
        (try
          (ffi/with-arena [a]
            ;; the engine reads `cancel` between tokens; `watch` sets it, and
            ;; is waited on before the arena that holds it goes
            (let [cancel (ffi/alloc a :int)
                  stop (atom false)
                  watch (when cancelled?
                          (future (loop []
                                    (when-not @stop
                                      (when (cancelled?) (ffi/write cancel :int 1))
                                      (Thread/sleep 100)
                                      (recur)))))]
              (try
                (doseq [[i {:keys [voice text]}] (map-indexed vector lines)
                        :let [say (spoken text)
                              parts (chunks say)]]
                  (doseq [[j part] (map-indexed vector parts)]
                    (when (and cancelled? (cancelled?)) (throw (ex-info "the speech was cancelled" {::cancelled true})))
                    (let [n (speak* h (ffi/string->ptr a (str voice)) (ffi/string->ptr a part)
                                    (int (if (expression? say) 1 0))
                                    ;; a :float takes a double: jolt refuses a
                                    ;; java.lang.Float there
                                    (double temperature) (int top-k) (double top-p) (double min-p)
                                    (int (+ seed (* 1000 i) j)) (int (token-budget part)) cancel)]
                      (cond
                        (= -2 n) (throw (ex-info "the speech was cancelled" {::cancelled true}))
                        (neg? n) (throw (ex-info (str "could not speak a line: " (error* h)) {:line i})))
                      (mp3-add-speech* e h (int (if (= j (dec (count parts))) line-gap-ms chunk-gap-ms)))))
                  (when on-progress (on-progress (inc i) total)))
                (finally
                  (reset! stop true)
                  (when watch @watch)))))
          (let [n (mp3-finish* e)]
            (when (neg? n) (throw (ex-info "could not finish the MP3" {})))
            {:mp3 (ffi/read-array (mp3-bytes* e) n)
             :seconds (mp3-seconds* e)})
          (finally (mp3-close* e))))
      (finally (free* h)))))
