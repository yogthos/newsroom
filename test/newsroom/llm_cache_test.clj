(ns newsroom.llm-cache-test
  (:require [clojure.test :refer [deftest is testing]]
            [newsroom.llm.cache :as cache]
            [newsroom.store :as store]))

(def ^:private llm {:type :deepseek :base-url "https://api.deepseek.com/v1" :model "deepseek-v4-flash"
                    :temperature 0.4 :max-tokens 32768 :api-key "sk-1" :timeout-ms 600000 :alias :deepseek})

(defn- ask [content] {:messages [{:role "user" :content content}]})

(defn- counting
  "A chat answering `answer` to everything, and the requests it was sent."
  [answer]
  (let [sent (atom [])]
    [(fn [_ request] (swap! sent conj request) answer) sent]))

(deftest the-same-request-is-answered-from-the-saved-answer
  (let [st (store/open "sqlite::memory:")
        [chat sent] (counting {:content "Sorted." :model "deepseek-v4-flash" :finish-reason "stop"})
        hits (atom 0)
        deltas (atom [])
        cached (cache/cached chat st {:max-age-ms 60000 :on-hit (fn [_] (swap! hits inc))})]
    (try
      (is (= "Sorted." (:content (cached llm (ask "Sort these.")))))
      (is (= "Sorted." (:content ((cache/cached chat st {:max-age-ms 60000 :on-hit (fn [_] (swap! hits inc))})
                                  llm (assoc (ask "Sort these.") :on-delta #(swap! deltas conj %))))))
      (is (= 1 (count @sent)) "the second run's call isn't sent")
      (is (= 1 @hits))
      (is (= [{:text "Sorted."}] @deltas) "a streamed call still hears the answer")
      (testing "a different question, model or setting is asked"
        (cached llm (ask "Sort those."))
        (cached (assoc llm :model "deepseek-v4-pro") (ask "Sort these."))
        (cached (assoc llm :temperature 0.9) (ask "Sort these."))
        (is (= 4 (count @sent))))
      (testing "the key, the alias and the timeouts don't change what's asked"
        (let [[other sent'] (counting {:content "x"})]
          ((cache/cached other st {:max-age-ms 60000})
           (assoc llm :api-key "sk-2" :timeout-ms 1000 :alias :mine) (ask "Sort these."))
          (is (empty? @sent'))))
      (finally (store/close st)))))

(deftest what-isnt-worth-reusing-is-asked-again
  (let [st (store/open "sqlite::memory:")]
    (try
      (testing "a blank or cut-off answer isn't kept"
        (doseq [answer [{:content "  "} {:content "Half" :finish-reason "length"}]]
          (let [[chat sent] (counting answer)
                cached (cache/cached chat st {:max-age-ms 60000})]
            (cached llm (ask (pr-str answer)))
            ((cache/cached chat st {:max-age-ms 60000}) llm (ask (pr-str answer)))
            (is (= 2 (count @sent))))))
      (testing "asking afresh skips the saved answer, and keeps the new one"
        (let [[chat sent] (counting {:content "Old."})]
          ((cache/cached chat st {:max-age-ms 60000}) llm (ask "Again?")))
        (let [[chat sent] (counting {:content "New."})]
          (is (= "New." (:content ((cache/cached chat st {:max-age-ms 60000 :fresh? true}) llm (ask "Again?")))))
          (is (= 1 (count @sent))))
        (let [[chat sent] (counting {:content "Newer."})]
          (is (= "New." (:content ((cache/cached chat st {:max-age-ms 60000}) llm (ask "Again?")))))))
      (testing "the same request twice in one run is asked the second time"
        (let [[chat sent] (counting {:content "Once."})
              cached (cache/cached chat st {:max-age-ms 60000})]
          (cached llm (ask "Twice?"))
          (cached llm (ask "Twice?"))
          (is (= 2 (count @sent)) "a retry wants a new answer")
          (let [cached (cache/cached chat st {:max-age-ms 60000})]
            (cached llm (ask "Twice?"))
            (cached llm (ask "Twice?"))
            (is (= 3 (count @sent)) "a later run reuses it once, then asks"))))
      (testing "an answer older than the age allowed is asked again, and no age turns it off"
        (let [[chat sent] (counting {:content "Aged."})]
          ((cache/cached chat st {:max-age-ms 60000}) llm (ask "Aged?"))
          (Thread/sleep 20)
          ((cache/cached chat st {:max-age-ms 10}) llm (ask "Aged?"))
          ((cache/cached chat st {:max-age-ms 0}) llm (ask "Aged?"))
          (is (= 3 (count @sent)))))
      (finally (store/close st)))))
