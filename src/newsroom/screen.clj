(ns newsroom.screen
  "Screening the day's reports before the desk sorts them, with TinyDecide
  run in-process through lev: a 6 MB ELECTRA-small answering one question
  of each report, whether it is serious news about world affairs or the
  economy, or entertainment, lifestyle, sport or shopping. A report it
  gives less than :screen-threshold of the first is left out, so the desk
  isn't paid to sort it. It reads the headline and the start of the
  summary, about 40 ms a report on OpenBLAS.

  On a day of real feeds it told the two apart with an AUC of 0.9: at 0.3
  it left out a fifth of the soft reports and none of the hard news, at
  0.4 half of them and one in forty of the news. A report continuing a
  storyline already followed is never left out.

  lev needs its kernels (`jolt lev` builds them) and a BLAS, OpenBLAS on
  linux, Accelerate on macOS; without them, as on Windows, `available?` is
  false and every report goes to the desk. The model's two files come from
  Hugging Face the first time, at the revision pinned in `downloads`, into
  the config directory's screen/, or NEWSROOM_SCREEN_DIR."
  (:require [clojure.java.io :as io]
            [jolt.http-client :as http]
            [lev.tensors :as tensors]
            [lev.tinydecide :as td]
            [newsroom.config :as config]))

(def question
  "What is asked of each report. The options' wording was chosen on a day
  of labelled reports: the plain topic question, or one asking for a
  number, told them apart far worse."
  [{:type "choice" :text "Is this serious news?"
    :options ["yes, serious news about world affairs or the economy"
              "no, entertainment, lifestyle, sport or shopping"]}])

(def ^:private repo
  "https://huggingface.co/TheREZOR/TinyDecide/resolve/aa745b73a2db9a43bce731f0e8e5c90a6899fe2c/")

(def downloads
  "What is fetched, by the file it is saved as, with its size."
  {"meta.json" {:url (str repo "meta.json") :size 187362}
   "model.bin" {:url (str repo "model.bin") :size 6181984}})

(defn home
  "Where the model's files are kept."
  []
  (or (not-empty (System/getenv "NEWSROOM_SCREEN_DIR")) (config/path "screen")))

(defn available?
  "Whether the model can run here: lev's kernels and a BLAS are loaded."
  []
  (tensors/available?))

(defn- download!
  "Fetch `name` into the model's directory unless it is there whole,
  through a .part file."
  [dir name {:keys [url size]}]
  (let [dest (io/file dir name)
        part (io/file dir (str name ".part"))]
    (when-not (and (.exists dest) (= size (.length dest)))
      (let [{:keys [status body]} (http/get url {:as :stream :timeout 120000})]
        (when-not (= 200 status)
          (throw (ex-info (str "could not download " url ": HTTP " status) {:status status})))
        (with-open [in body] (io/copy in part))
        (when-not (= size (.length part))
          (.delete part)
          (throw (ex-info (str "the download of the screening model's " name " was cut short") {:name name})))
        (.renameTo part dest)))))

(defonce ^:private model (atom nil))

(defn- model!
  "The loaded model, fetching its files the first time."
  []
  (or @model
      (locking model
        (or @model
            (let [dir (io/file (home))]
              (.mkdirs dir)
              (doseq [[name spec] downloads] (download! dir name spec))
              (reset! model (td/load-release (.getPath dir))))))))

(defn text
  "What the model reads of an item: its headline, then its summary. It
  reads only the first 127 tokens, which the headline and the summary's
  start fill."
  [{:keys [title summary]}]
  (str title ". " summary))

(defn serious
  "The chance the model gives `item` of being serious news. Blocks."
  [item]
  (-> (td/answer (model!) (text item) question) first :probs first))

(defn screen
  "`items` split into {:kept :dropped} by `threshold`: dropped are those
  `score` (serious by default) gives less than it, in order, and never one
  continuing a storyline (:story). Blocks."
  ([items threshold] (screen items threshold serious))
  ([items threshold score]
   (let [drop? (fn [item] (and (nil? (:story item)) (< (score item) threshold)))
         {kept false dropped true} (group-by (comp boolean drop?) items)]
     {:kept (vec kept) :dropped (vec dropped)})))

(defn status-line
  "What startup says of the screening `config` asks for."
  [config]
  (let [threshold (:screen-threshold config)]
    (str "screening: "
         (cond (nil? threshold) "off"
               (available?) (str "TinyDecide, leaving out reports under " threshold)
               :else "on, but TinyDecide can't run here, with no BLAS for it"))))
