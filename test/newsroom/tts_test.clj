(ns newsroom.tts-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [newsroom.config :as config]
            [newsroom.tts :as tts]))

(deftest numbers-are-read-out
  (is (= "zero" (tts/number-words 0)))
  (is (= "seven" (tts/number-words 7)))
  (is (= "forty-two" (tts/number-words 42)))
  (is (= "one hundred and five" (tts/number-words 105)))
  (is (= "three thousand two hundred" (tts/number-words 3200)))
  (is (= "two million five hundred thousand" (tts/number-words 2500000)))
  (is (= "minus four" (tts/number-words -4))))

(deftest text-is-written-as-it-is-spoken
  (testing "numbers, money, percentages and years"
    (is (= "Rates rose by four point five percent." (tts/spoken "Rates rose by 4.5%")))
    (is (= "It cost twelve dollars." (tts/spoken "It cost $12.")))
    (is (= "In twenty twenty-six, prices fell." (tts/spoken "In 2026, prices fell.")))
    (is (= "About one thousand two hundred ships." (tts/spoken "About 1,200 ships.")))
    (is (= "It ran in nineteen ninety-nine and two thousand and eight." (tts/spoken "It ran in 1999 and 2008."))))
  (testing "dashes and ellipses are pauses, and a bracket that isn't a tag is read as an aside"
    (is (= "Tariffs, as expected, went up." (tts/spoken "Tariffs — as expected — went up.")))
    (is (= "Well, maybe." (tts/spoken "Well… maybe")))
    (is (= "The bank, the central one, held." (tts/spoken "The bank (the central one) held."))))
  (testing "the emotion and emphasis tags pass through, and a vocal event, spoken as a word, goes"
    (is (= "[excited] That's (((huge))) right?" (tts/spoken "[excited] that's (((huge))) <laugh> right?")))
    (is (not (tts/expression? "Ha <laugh> ha.")))
    (is (tts/expression? "[joyful] Hello."))
    (is (tts/expression? "It's (((big))) news."))
    (is (not (tts/expression? "A plain line [3] about x < 5.")))))

(deftest long-text-is-chunked-at-sentences
  (let [sentence "The central bank held rates steady for a third month as inflation eased."
        text (str/join " " (repeat 12 sentence))
        chunks (tts/chunks text)]
    (is (< 1 (count chunks)))
    (is (every? #(<= (count %) (* 380 1.4)) chunks))
    (is (every? #(re-find #"[.!?,;:]$" %) chunks) "every chunk closes with a mark")
    (is (= (str/replace text #"\s+" "") (str/replace (str/join "" chunks) #"\s+" "")) "nothing is lost"))
  (testing "text that fits is one chunk"
    (is (= ["Short and sweet."] (tts/chunks "Short and sweet."))))
  (testing "a short tail is folded into its neighbour"
    (let [chunks (tts/chunks (str (str/join " " (repeat 6 "This is a sentence of a fair length to fill a chunk."))
                                  " Then one more."))]
      (is (not-any? #(< (count %) 130) (butlast chunks))))))

(deftest the-device-is-named-as-a-reader-says-it
  (is (= "the CPU" (tts/device-name "CPU")))
  (is (= "the GPU, through Metal" (tts/device-name "MTL0")))
  (is (= "the GPU, through Vulkan" (tts/device-name "Vulkan0")))
  (is (= "the GPU, through CUDA" (tts/device-name "CUDA0")))
  (is (= "the GPU, through ROCm" (tts/device-name "ROCm0")))
  (is (= "the GPU, through Vulkan and the CPU" (tts/device-name "Vulkan0 (decoder on the CPU)")))
  (is (= "the GPU, through Vulkan and the CPU" (tts/device-name "Vulkan0 (speech model on the CPU)"))))

(deftest the-speech-libraries-live-in-the-config-plugins-folder
  (let [places (tts/library-places "libnewsroom_tts_mini.so")]
    (is (= (config/path "plugins" "speech" "libnewsroom_tts_mini.so") (first places)))
    (is (= "native/libnewsroom_tts_mini.so" (last places)) "and a checkout's native/, for development")
    (is (= 2 (count places)) "and nowhere else, like beside the binary")))

(deftest the-model-files-are-named-by-pinned-revisions
  (is (every? #(re-find #"/resolve/[0-9a-f]{40}/" (:url %)) (concat (vals tts/downloads) (vals tts/mini-downloads)))))

;; what startup says, which the release's smoke test reads
(deftest the-engines-there-are-said
  (is (re-find #"^speech engines: " (tts/engines-line)))
  (is (= (tts/available? :kitten-mini) (boolean (re-find #"KittenTTS mini" (tts/engines-line))))))

;; --- the mini engine ------------------------------------------------------------

(deftest the-mini-engine-has-voices-for-every-host
  (is (= ["Bella" "Jasper" "Luna" "Bruno" "Rosie" "Hugo" "Kiki" "Leo"] (tts/voices :kitten-mini)))
  (is (= (tts/voices) (tts/voices :kitten-2)))
  (testing "a voice it has is its own"
    (is (= "Hugo" (tts/engine-voice :kitten-mini "Hugo")))
    (is (= "Kiki" (tts/engine-voice :kitten-mini "Kiki"))))
  (testing "one it doesn't have is its deepest of the same sex"
    (is (= "Luna" (tts/engine-voice :kitten-mini "Martha")))
    (is (= "Bruno" (tts/engine-voice :kitten-mini "Frank"))))
  (is (= "Martha" (tts/engine-voice :kitten-2 "Martha"))))

(deftest the-mini-engine-reads-no-markup
  (is (= "It's huge, right?" (tts/without-markup "[excited] It's (((huge))), right?")))
  (is (= "Plain." (tts/without-markup "Plain."))))

(deftest the-mini-engine-speaks
  (if-not (and (tts/available? :kitten-mini) (tts/prepared? :kitten-mini))
    (println "skipping the mini engine's test: its plugin or its model files are missing")
    (let [progress (atom [])
          {:keys [mp3 seconds]} (tts/synthesize [{:voice "Hugo" :text "Good morning, and welcome to the briefing."}
                                                 {:voice "Martha" :text "[excited] It's a big day!"}]
                                                {:engine :kitten-mini
                                                 :on-progress (fn [done total] (swap! progress conj [done total]))})]
      (is (< 1.5 seconds 15))
      (is (< 4000 (alength mp3)))
      (is (= [[1 2] [2 2]] @progress))
      (is (= 0xff (bit-and (aget mp3 0) 0xff))))))

;; The engine itself, when its native library is built and its files are in
;; place: NEWSROOM_TTS_DIR, else the config directory's tts/.
(deftest the-engine-speaks
  (if-not (and (tts/native?) (tts/prepared?))
    (println "skipping the speech engine's test: the native library or the model files are missing")
    (let [progress (atom [])
          {:keys [mp3 seconds]} (tts/synthesize [{:voice "Bruno" :text "Good morning, and welcome to the briefing."}
                                                 {:voice "Luna" :text "[excited] It's a big day!"}]
                                                {:on-progress (fn [done total] (swap! progress conj [done total]))})]
      (is (< 1.5 seconds 15))
      (is (< 4000 (alength mp3)))
      (is (= [[1 2] [2 2]] @progress))
      (testing "it's an MP3: an MPEG audio frame sync at the start"
        (is (= 0xff (bit-and (aget mp3 0) 0xff)))
        (is (= 0xe0 (bit-and (aget mp3 1) 0xe0)))))))
