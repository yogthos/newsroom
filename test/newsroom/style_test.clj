(ns newsroom.style-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [newsroom.critique :as critique]
            [newsroom.style :as style]))

(defn- quotes [md] (mapv :quote (style/issues md)))

(deftest plain-prose-has-no-tells
  (is (= [] (style/issues (str "# Rates hold\n\n> The Fed held, so the dollar firmed.\n\n"
                               "## What changed\n\nThe Fed held rates at 4.5% [1], and the dollar rose "
                               "because traders had priced a cut [2, 3].\n\n"
                               "- **Oil**: Brent could climb past $90 if the strait closes."))))
  (is (= [] (style/issues "")))
  (is (= [] (style/issues nil))))

(deftest each-tell-is-found
  (doseq [[why s] [["an em dash" "The vote, held late — and lost — moved bonds [1]."]
                   ["an en dash" "Yields rose – fast [2]."]
                   ["a double hyphen" "Yields rose -- fast [2]."]
                   ["a semicolon" "Yields rose; spreads widened [2]."]
                   ["not just ... but" "The tariff is not just a tax but a signal to Beijing [1]."]
                   ["not only ... but also" "It hits not only steel but also aluminium [1]."]
                   ["it's not X, it's Y" "It's not a pause, it's a retreat [3]."]
                   ["this doesn't mean X. It means Y" "This doesn't mean the war ends. It means the front freezes."]
                   ["a banned connective" "Moreover, the yen fell [4]."]
                   ["an overused word" "The deal marks a pivotal shift for Gulf exporters [2]."]
                   ["a staged run-up" "Here's the thing: the bank has no room left [5]."]
                   ["a deep-sounding saying" "At its core, this is a fight over chips [5]."]
                   ["an -ing tail" "Exports fell 4% [1], underscoring the pressure on Berlin."]
                   ["a figurative landscape" "The energy landscape shifted [1]."]]]
    (testing why
      (is (= [s] (quotes s))))))

(deftest a-sentence-is-quoted-once-with-every-tell-it-has
  (let [[i :as all] (style/issues "Moreover, yields rose — fast; it was pivotal [1].")]
    (is (= 1 (count all)))
    (is (= "style" (:kind i)))
    (is (str/includes? (:problem i) "dash"))
    (is (str/includes? (:problem i) "semicolon"))
    (is (str/includes? (:problem i) "\"moreover\""))
    (is (str/includes? (:problem i) "\"pivotal\""))
    (is (string? (:fix i)))))

(deftest code-diagrams-and-links-are-not-prose
  (is (= [] (style/issues (str "## Step 1: Rates\n\n**The Fed held [1].**\n\n"
                               "```mermaid\nflowchart BT\n  a[\"Rates\"] --> b[\"Debt\"]\n  a -.-> c; c --x b\n```\n\n"
                               "See [the release](https://e.com/a--b;c) and `x -- y; z`.")))))

(deftest an-abbreviation-does-not-end-a-sentence
  (is (= ["U.S. officials said rates rise; bonds fell."]
         (quotes "U.S. officials said rates rise; bonds fell."))))

(deftest each-sentence-is-its-own-quote
  (is (= ["Yields rose — fast [1]." "Moreover, the yen fell."]
         (quotes "Yields rose — fast [1]. The dollar held. Moreover, the yen fell.\n\n- Plain bullet [2]."))))

(deftest the-list-is-capped
  (is (= style/max-issues
         (count (style/issues (str/join " " (for [i (range 40)] (str "Rates rose " i "; bonds fell.")))))))
  (is (< 5 style/max-issues 40)))

(deftest the-critic-knows-a-style-problem
  (is (some #{"style"} critique/kinds))
  (is (str/includes? (critique/revision-request (style/issues "Rates rose; bonds fell."))
                     "(style)")))
