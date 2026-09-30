(ns newsroom.test-runner
  "Runs every test namespace, and checks the core against its writ spec."
  (:require [clojure.test :as t]
            [newsroom.config-test]
            [newsroom.feed-test]
            [newsroom.llm-stream-test]
            [newsroom.markdown-test]
            [newsroom.news-spec]
            [newsroom.pipeline-test]
            [newsroom.plugin-test]
            [newsroom.sources-test]
            [newsroom.store-test]
            [newsroom.ui-test]))

(def namespaces
  '[newsroom.news-spec
    newsroom.config-test
    newsroom.feed-test
    newsroom.llm-stream-test
    newsroom.markdown-test
    newsroom.store-test
    newsroom.pipeline-test
    newsroom.plugin-test
    newsroom.sources-test
    newsroom.ui-test])

(defn -main [& _]
  (let [{:keys [fail error]} (apply t/run-tests namespaces)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
