(ns newsroom.progress-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [newsroom.progress :as progress]))

(deftest a-watcher-follows-the-stream
  (let [clock (atom 1000)
        seen (atom [])
        began (atom [])
        w (progress/watcher {:on-progress #(swap! seen conj %)
                             :on-thinking #(swap! began conj :thinking)
                             :on-writing #(swap! began conj :writing)
                             :now #(deref clock)})]
    ((:on-delta w) {:reasoning "Weigh the oil"})
    (is (= [{:words 0 :reasoning-words 3 :section nil :tail ""}] @seen) "the first piece is shown at once")
    ((:on-delta w) {:reasoning "and the grain"})
    (is (= 1 (count @seen)) "pieces close together are shown together")
    (swap! clock + 301)
    ((:on-delta w) {:text "## The mechanics\n\nOil rises [1]."})
    ((:on-delta w) {:text " Grain follows."})
    (is (= 2 (count @seen)))
    ((:flush! w))
    (is (= {:words 6 :reasoning-words 6 :section "The mechanics"} (dissoc (last @seen) :tail))
        "words, not markup or citations, and what was thought before")
    (is (str/ends-with? (:tail (last @seen)) "Grain follows."))
    (is (= [:thinking :writing] @began) "each start is told once")))

(deftest progress-in-a-line
  (is (= "Thinking" (progress/summary {:words 0 :reasoning-words 0})))
  (is (= "Thinking · 1 word of reasoning" (progress/summary {:words 0 :reasoning-words 1})))
  (is (= "Thinking · 1,234 words of reasoning" (progress/summary {:words 0 :reasoning-words 1234})))
  (testing "once it writes, what it has written"
    (is (= "1 word written" (progress/summary {:words 1 :reasoning-words 300})))
    (is (= "2,500 words written · Outlook" (progress/summary {:words 2500 :section "Outlook"})))))
