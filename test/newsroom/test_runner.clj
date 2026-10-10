(ns newsroom.test-runner
  "Runs every test namespace, and checks the core against its writ spec."
  (:require [clojure.test :as t]
            [newsroom.analysis-test]
            [newsroom.ask-test]
            [newsroom.config-test]
            [newsroom.critique-test]
            [newsroom.embed-test]
            [newsroom.feed-test]
            [newsroom.llm-stream-test]
            [newsroom.markdown-test]
            [newsroom.narration-test]
            [newsroom.news-spec]
            [newsroom.news-test]
            [newsroom.notes-test]
            [newsroom.pipeline-test]
            [newsroom.podcast-test]
            [newsroom.precedents-test]
            [newsroom.progress-test]
            [newsroom.retrospective-test]
            [newsroom.settings-test]
            [newsroom.plugin-test]
            [newsroom.search-test]
            [newsroom.secrets-test]
            [newsroom.sources-test]
            [newsroom.store-test]
            [newsroom.trends-test]
            [newsroom.tts-test]
            [newsroom.ui-test]))

(def namespaces
  '[newsroom.news-spec
    newsroom.analysis-test
    newsroom.ask-test
    newsroom.config-test
    newsroom.critique-test
    newsroom.embed-test
    newsroom.feed-test
    newsroom.llm-stream-test
    newsroom.markdown-test
    newsroom.news-test
    newsroom.notes-test
    newsroom.podcast-test
    newsroom.precedents-test
    newsroom.progress-test
    newsroom.retrospective-test
    newsroom.search-test
    newsroom.store-test
    newsroom.trends-test
    newsroom.tts-test
    newsroom.pipeline-test
    newsroom.narration-test
    newsroom.plugin-test
    newsroom.secrets-test
    newsroom.settings-test
    newsroom.sources-test
    newsroom.ui-test])

(defn -main [& _]
  (let [{:keys [fail error]} (apply t/run-tests namespaces)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
