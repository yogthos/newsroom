(ns newsroom.podcast-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [newsroom.config :as config]
            [newsroom.podcast :as podcast]
            [newsroom.template :as template]))

(def ^:private hosts
  [{:name "Hugo" :voice "Hugo" :backstory "A former trade economist." :personality "Dry, curious."}
   {:name "Kiki" :voice "Kiki" :backstory "A science reporter." :personality "Quick, playful."}])

(def ^:private voices ["Bella" "Hugo" "Kiki" "Leo"])

(def ^:private outline
  [{:name "Hello" :description "The hook." :size "short"}
   {:name "Tariffs" :description "Why prices moved." :size "long"}
   {:name "Goodbye" :description "What to watch." :size "medium"}])

(deftest the-default-prompts-are-templates
  (is (nil? (template/error (config/default-text "podcast-outline.md"))))
  (is (nil? (template/error (config/default-text "podcast-transcript.md")))))

(deftest the-hosts-are-the-first-two-with-a-name-and-a-voice
  (is (= hosts (podcast/hosts hosts voices)))
  (testing "a voice is matched without regard to case, and one the engine lacks rules a host out"
    (is (= [{:name "Ann" :voice "Bella"} {:name "Bo" :voice "Leo"}]
           (podcast/hosts [{:name " Ann " :voice "bella"} {:name "Cy" :voice "Nobody"} {:voice "Kiki"}
                           {:name "Bo" :voice "Leo"} {:name "Di" :voice "Kiki"}]
                          voices))))
  (is (= 1 (count (podcast/hosts [{:name "Solo" :voice "Hugo"}] voices)))))

(deftest the-segments-are-held-within-bounds
  (is (= 5 (podcast/segment-count nil)))
  (is (= 2 (podcast/segment-count 1)))
  (is (= 12 (podcast/segment-count 40)))
  (is (= 7 (podcast/segment-count 7))))

(deftest the-outline-prompt-has-the-briefing-and-the-hosts
  (let [p (podcast/render-outline (config/default-text "podcast-outline.md") "5 October 2026" "Tariffs rose." hosts 4)]
    (is (str/includes? p "the episode for 5 October 2026"))
    (is (str/includes? p "<briefing>\nTariffs rose.\n</briefing>"))
    (is (str/includes? p "- Hugo: A former trade economist.\n  Personality: Dry, curious.\n- Kiki: A science reporter."))
    (is (str/includes? p "Plan the episode as 4 segments."))
    (is (not (str/includes? p "{{")))))

(deftest the-outline-is-read-from-the-answer
  (is (= [{:name "Hello" :description "The hook." :size "short"}
          {:name "Tariffs" :description "Why prices moved." :size "long"}
          {:name "Untitled size" :description "" :size "medium"}]
         (podcast/parse-outline
          (str "<think>Let me {plan} it.</think>\n```json\n"
               "{\"segments\": [{\"name\": \" Hello \", \"description\": \"The hook.\", \"size\": \"Short\"},"
               " {\"name\": \"Tariffs\", \"description\": \"Why prices moved.\", \"size\": \"long\"},"
               " {\"description\": \"no name\"}, \"not a segment\","
               " {\"name\": \"Untitled size\", \"size\": \"huge\"}]}\n```")
          5)))
  (testing "no more segments than were asked for"
    (is (= 2 (count (podcast/parse-outline
                     "{\"segments\": [{\"name\": \"a\"}, {\"name\": \"b\"}, {\"name\": \"c\"}]}" 2)))))
  (testing "an answer with no segments is nil"
    (is (nil? (podcast/parse-outline "I can't." 5)))
    (is (nil? (podcast/parse-outline "{\"segments\": []}" 5)))))

(deftest the-transcript-prompt-has-the-segment-and-what-came-before
  (let [t (config/default-text "podcast-transcript.md")
        first-p (podcast/render-transcript t "5 October 2026" "Tariffs rose." hosts outline [] 0)
        last-p (podcast/render-transcript t "5 October 2026" "Tariffs rose." hosts outline
                                          [{:speaker "Hugo" :text "Hello there."} {:speaker "Kiki" :text "Hi!"}] 2)]
    (is (str/includes? first-p "<segment>\nHello: The hook.\n</segment>"))
    (is (str/includes? first-p "1. Hello (short): The hook.\n2. Tariffs (long): Why prices moved."))
    (is (str/includes? first-p "at least 6 turns") "a short segment's turns")
    (is (str/includes? first-p "Use the hosts' names, Hugo, Kiki, for the speakers"))
    (is (str/includes? first-p "{\"speaker\": \"Hugo\", \"dialogue\""))
    (is (str/includes? first-p "{\"speaker\": \"Kiki\", \"dialogue\""))
    (is (str/includes? first-p "Open the episode"))
    (is (not (str/includes? first-p "<transcript>")))
    (is (not (str/includes? first-p "final segment")))
    (is (not (str/includes? first-p "{{")))
    (testing "the last segment is told it is the last, and hears the conversation so far"
      (is (str/includes? last-p "This is the final segment"))
      (is (str/includes? last-p "<transcript>\nHugo: Hello there.\nKiki: Hi!\n</transcript>"))
      (is (str/includes? last-p "at least 10 turns"))
      (is (str/includes? last-p "Carry on from the conversation so far")))))

(deftest the-transcript-is-read-from-the-answer
  (is (= [{:speaker "Hugo" :text "Good morning."}
          {:speaker "Kiki" :text "[excited] Big day, <laugh> really."}
          {:speaker "Hugo" :text "It (((really))) was."}]
         (podcast/parse-transcript
          (str "Here: {\"transcript\": ["
               "{\"speaker\": \"hugo\", \"dialogue\": \"Good morning.\"},"
               "{\"speaker\": \"Narrator\", \"dialogue\": \"Not a host.\"},"
               "{\"speaker\": \" KIKI \", \"dialogue\": \"[Excited] Big day, <laugh> really [3].\"},"
               "{\"speaker\": \"Kiki\", \"dialogue\": \"  \"},"
               "{\"speaker\": \"Kiki\", \"dialogue\": \"[1]\"},"
               "\"stray\","
               "{\"speaker\": \"**Hugo**\", \"dialogue\": \"It (((really))) was.\"}]}")
          hosts)))
  (is (nil? (podcast/parse-transcript "{\"lines\": []}" hosts)))
  (is (nil? (podcast/parse-transcript "nothing" hosts))))

(deftest a-line-is-cleaned-for-speech
  (testing "markdown, citations and addresses go"
    (is (= "The bank said rates would hold, as the report put it."
           (podcast/speakable "The **bank** said [rates](https://e.com/x) would _hold_ [1], as the `report` put it [2, 3].")))
    (is (= "See it online." (podcast/speakable "See it online https://e.com/a?b=1 [[4]](https://e.com/4).")))
    (is (= "Prices rose." (podcast/speakable "## Prices rose."))))
  (testing "the engine's own tags are kept, anything else in brackets goes"
    (is (= "[surprised] Wait, <gasp> they did (((what)))?"
           (podcast/speakable "[Surprised] Wait, <GASP> they did (((what)))? [music swells] <break time=\"1s\"/>")))
    (is (= "Well, here we are." (podcast/speakable "Well, [excited] here we are.")) "an emotion only leads a line")
    (is (= "Fine." (podcast/speakable "[narrator] Fine. <whisper>"))))
  (testing "stage directions become the engine's vocal events"
    (is (= "<laugh> That's rich. <sigh> Fine."
           (podcast/speakable "(laughs) That's rich. *sighs* Fine."))))
  (testing "placeholders and stray ellipses go"
    (is (= "And then it ended." (podcast/speakable "And then … it ended. [...]")))
    (is (= "" (podcast/speakable "...")))
    (is (= "Well… maybe." (podcast/speakable "Well… maybe.")) "an ellipsis in a word's place stays")))

(deftest the-transcript-becomes-the-engines-lines
  (is (= [{:voice "Hugo" :text "Hello."} {:voice "Kiki" :text "Hi."}]
         (podcast/tts-lines [{:speaker "Hugo" :text "Hello."} {:speaker "Kiki" :text "Hi."}
                             {:speaker "Gone" :text "Dropped."}]
                            hosts)))
  (is (= "Hugo: Hello.\nKiki: Hi." (podcast/transcript-text [{:speaker "Hugo" :text "Hello."}
                                                            {:speaker "Kiki" :text "Hi."}]))))

(deftest a-line-is-read-without-the-engines-tags
  (is (= "Wait, they did what?" (podcast/readable "[surprised] Wait, <gasp> they did (((what)))?")))
  (is (= "Plain." (podcast/readable "Plain."))))
