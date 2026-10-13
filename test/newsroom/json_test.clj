(ns newsroom.json-test
  "JSON on jolt, whose characters are code points: one past U+FFFF is a
  single character, which clojure.data.json, written for UTF-16, can't
  escape or unescape."
  (:require [clojure.test :refer [deftest is testing]]
            [newsroom.json :as json]))

(deftest an-emoji-goes-out-as-itself
  (is (= "{\"t\":\"Hi 👋 é\"}" (json/write-str {:t "Hi 👋 é"})))
  (is (= "\"a\\\"b\\n\"" (json/write-str "a\"b\n")) "what JSON has to escape still is"))

(deftest an-emoji-comes-back-however-it-was-written
  (is (= "👋" (json/read-str "\"👋\"")))
  (is (= "x👋y" (json/read-str "\"x\\ud83d\\udc4by\"")) "as a surrogate pair")
  (is (= "x\uFFFDy" (json/read-str "\"x\\ud83dy\"")) "a lone surrogate is a replacement character")
  (is (= "\\ud83d\\udc4b" (json/read-str "\"\\\\ud83d\\\\udc4b\"")) "an escaped backslash isn't an escape")
  (is (= {:t "é"} (json/read-str "{\"t\":\"\\u00e9\"}" :key-fn keyword)))
  (testing "a round trip keeps every character"
    (is (= "🚀 中文 👋" (json/read-str (json/write-str "🚀 中文 👋"))))))
