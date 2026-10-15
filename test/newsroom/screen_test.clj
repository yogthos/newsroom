(ns newsroom.screen-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [newsroom.screen :as screen]))

(deftest screen-splits-by-the-threshold
  (let [score {"war" 0.9 "rates" 0.35 "recipe" 0.1 "goal" 0.2}
        items (mapv (fn [t] {:title t}) ["war" "recipe" "rates" "goal"])
        {:keys [kept dropped]} (screen/screen items 0.3 (comp score :title))]
    (testing "a report under the threshold is left out, the rest kept in order"
      (is (= ["war" "rates"] (map :title kept)))
      (is (= ["recipe" "goal"] (map :title dropped))))
    (testing "a report following a storyline is kept whatever it scores"
      (is (= ["war" "recipe" "rates"]
             (map :title (:kept (screen/screen (assoc-in items [1 :story] "2026-10-14-3") 0.3 (comp score :title)))))))
    (testing "a threshold of 0 keeps everything"
      (is (= items (:kept (screen/screen items 0 (comp score :title))))))))

(deftest text-is-the-headline-then-the-summary
  (is (= "Rates held. The bank kept them at 4%." (screen/text {:title "Rates held" :summary "The bank kept them at 4%."}))))

(deftest status-line-says-whether-it-runs
  (is (= "screening: off" (screen/status-line {})))
  (is (re-find #"^screening: " (screen/status-line {:screen-threshold 0.3}))))

;; With the model's files in NEWSROOM_SCREEN_DIR or the config directory's
;; screen/, and lev's kernels and a BLAS loaded, the model itself is asked.
(deftest the-model-tells-hard-news-from-soft
  (if-not (and (screen/available?) (.exists (io/file (screen/home) "model.bin")))
    (println "skipping the screening model: no kernels, BLAS or model files")
    (let [hard (screen/serious {:title "Albania protests turn violent as protesters demand PM Rama's resignation"
                                :summary "Thousands rallied in Tirana on Sunday, clashing with police outside parliament."})
          soft (screen/serious {:title "The 12 best air fryers of the year, tested"
                                :summary "From budget picks to splurges, these are the ones worth buying."})]
      (is (< soft 0.5 hard)))))
