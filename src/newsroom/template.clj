(ns newsroom.template
  "The prompts are Selmer templates. Filling one is kept apart from
  newsroom.news, whose code is pure: Selmer's switch for escaping is a
  dynamic binding."
  (:require [selmer.parser :as selmer]
            [selmer.util :as selmer-util]))

(defn fill
  "`template`, Selmer's, filled from `values`. A prompt is not HTML, so
  nothing is escaped, and what a value holds is never read as a template,
  so a source that writes {{ or {% comes through as it is."
  [template values]
  (selmer-util/without-escaping (selmer/render template values)))

(defn error
  "Why `template` can't be filled, nil when it can."
  [template]
  (try (fill template {}) nil
       (catch Exception e (or (ex-message e) (str e)))))
