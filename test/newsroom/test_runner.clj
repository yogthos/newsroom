(ns newsroom.test-runner
  "Runs every test namespace, and checks the core against its writ spec."
  (:require [clojure.test :as t]
            [newsroom.analysis-test]
            [newsroom.config-test]
            [newsroom.critique-test]
            [newsroom.embed-test]
            [newsroom.feed-test]
            [newsroom.llm-stream-test]
            [newsroom.markdown-test]
            [newsroom.news-spec]
            [newsroom.news-test]
            [newsroom.notes-test]
            [newsroom.pipeline-test]
            [newsroom.precedents-test]
            [newsroom.settings-test]
            [newsroom.plugin-test]
            [newsroom.secrets-test]
            [newsroom.sources-test]
            [newsroom.store-test]
            [newsroom.trends-test]
            [newsroom.ui-test]))

(def namespaces
  '[newsroom.news-spec
    newsroom.analysis-test
    newsroom.config-test
    newsroom.critique-test
    newsroom.embed-test
    newsroom.feed-test
    newsroom.llm-stream-test
    newsroom.markdown-test
    newsroom.news-test
    newsroom.notes-test
    newsroom.precedents-test
    newsroom.store-test
    newsroom.trends-test
    newsroom.pipeline-test
    newsroom.plugin-test
    newsroom.secrets-test
    newsroom.settings-test
    newsroom.sources-test
    newsroom.ui-test])

(defn -main [& _]
  (let [{:keys [fail error]} (apply t/run-tests namespaces)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
