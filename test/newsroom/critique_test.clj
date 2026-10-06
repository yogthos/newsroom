(ns newsroom.critique-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [newsroom.config :as config]
            [newsroom.critique :as critique]))

(def ^:private sources
  [{:n 1 :title "Kyiv unveils plan for its own satellite network" :source "Outlet" :url "https://e.com/1"
    :summary "Officials said the country would build a Starlink rival."}])

(deftest the-critic-reads-the-draft-and-its-sources
  (let [p (critique/render-prompt (config/default-text "critic.md") "5 October 2026 (2026-10-05)"
                                  "# Today\n\nKyiv will launch its own Starlink [1]." sources nil)]
    (is (str/includes? p "Today is 5 October 2026 (2026-10-05)."))
    (is (str/includes? p "## The briefing\n\n# Today\n\nKyiv will launch its own Starlink [1]."))
    (is (str/includes? p "[1] Kyiv unveils plan for its own satellite network (Outlet)"))
    (is (str/includes? p "Officials said the country would build a Starlink rival."))
    (is (not (str/includes? p "desk's analysis")) "no analysis, no place for it"))
  (testing "the desk's analysis goes in when there is one"
    (is (str/includes? (critique/render-prompt (config/default-text "critic.md") "today" "Draft" sources
                                               "## The desk's analysis\n\nDossiers.")
                       "## The desk's analysis\n\nDossiers.\n\n## Sources")))
  (testing "a template with no place for the draft or the sources gets them at the end"
    (let [p (critique/render-prompt "Check this." "today" "The draft." sources nil)]
      (is (str/starts-with? p "Check this.\n\nThe draft.\n\n[1] Kyiv")))))

(deftest the-critics-answer-is-read-as-a-list-of-problems
  (is (= [{:quote "Kyiv will launch its own Starlink"
           :kind "implausible"
           :problem "Ukraine has no launch capability."
           :fix "Say what the announcement is for."}
          {:quote nil :kind "face-value" :problem "An interested figure." :fix nil}
          {:quote nil :kind "repeated" :problem "Retells the overview." :fix nil}
          {:quote nil :kind "reasoning" :problem "Doesn't follow." :fix nil}]
         (critique/parse-issues
          (str "Here it is:\n```json\n"
               "{\"issues\": [{\"quote\": \" Kyiv will launch its own Starlink \", \"kind\": \"Implausible\","
               " \"problem\": \"Ukraine has no launch capability.\", \"fix\": \"Say what the announcement is for.\"},"
               " {\"kind\": \"face value\", \"problem\": \"An interested figure.\"},"
               " {\"kind\": \"Repeated\", \"problem\": \"Retells the overview.\"},"
               " {\"kind\": \"something else\", \"problem\": \"Doesn't follow.\"},"
               " {\"quote\": \"no problem given\"}, \"not an issue\"]}\n```"))))
  (testing "no problems is an empty list, and an answer with no list is nil"
    (is (= [] (critique/parse-issues "{\"issues\": []}")))
    (is (nil? (critique/parse-issues "The briefing looks fine to me.")))
    (is (nil? (critique/parse-issues "{\"problems\": []}")))))

(deftest the-analyst-is-told-what-to-fix
  (let [m (critique/revision-request [{:quote "Kyiv will launch its own Starlink" :kind "implausible"
                                       :problem "No launch capability." :fix "Drop it."}
                                      {:kind "reasoning" :problem "Doesn't follow."}])]
    (is (str/includes? m "1. \"Kyiv will launch its own Starlink\"\n   (implausible) No launch capability.\n   Fix: Drop it."))
    (is (str/includes? m "2. (reasoning) Doesn't follow."))
    (is (str/includes? m "Answer with the whole briefing"))))

(deftest a-revision-has-to-be-a-whole-briefing
  (let [draft (str "# Today\n\n" (str/join " " (repeat 40 "word")))]
    (is (critique/usable-revision? draft (str "# Today, revised\n\n" (str/join " " (repeat 30 "word")))))
    (is (not (critique/usable-revision? draft "")))
    (is (not (critique/usable-revision? draft "I fixed the claim about Starlink.")) "a note, not a briefing")
    (is (not (critique/usable-revision? draft (str "Today\n\n" (str/join " " (repeat 40 "word"))))) "lost its title")
    (is (critique/usable-revision? "No title here." "Nor here."))))
