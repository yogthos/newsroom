(ns newsroom.ask-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [newsroom.ask :as ask]
            [newsroom.store :as store]))

(def day-page
  {:path "/day/2026-10-05" :label "5 October 2026"
   :markdown "# Chokepoints\n\n## What changed\n\nShips burn [1].\n\n## Sources\n\n- [1] x"
   :sources [{:n 1 :title "Ship sinks" :url "https://e.com/1" :source "BBC" :summary "A ship sank." :story "s1"}
             {:n 2 :title "Ship fire, more" :url "https://e.com/2" :source "Hindu" :story "s1"}
             {:n 3 :title "Brazil votes" :url "https://e.com/3" :source "AJ" :story "s2"}
             {:n 4 :title "Unrelated" :url "https://e.com/4" :source "X" :story "s3"}]})

(def week-page
  {:path "/week/2026-W40" :label "the week of 28 September 2026"
   :markdown "# Week\n\nOil [2]."
   :sources [{:n 1 :title "Old" :url "https://e.com/9" :source "Y"}
             {:n 2 :title "Ship sinks" :url "https://e.com/1" :source "BBC"}]})

(deftest the-passages-sources-are-numbered-afresh
  (let [{:keys [topics sources]} (ask/gather [day-page week-page]
                                             [{:text "Ships burn [1] and Brazil [3, 9]." :page "/day/2026-10-05"}
                                              {:text "Oil [2]." :page "/week/2026-W40"}
                                              {:text "Nowhere [1]." :page "/day/1999-01-01"}])]
    (is (= ["https://e.com/1" "https://e.com/3" "https://e.com/2"] (map :url sources))
        "the sources a passage cites, then the rest of their storylines, one per address")
    (is (= [1 2 3] (map :n sources)))
    (is (= ["Ships burn [1] and Brazil [2]." "Oil [1]." "Nowhere."] (map :text topics))
        "a passage cites its sources by their new numbers, and a number no source has is dropped")
    (is (= ["5 October 2026" "the week of 28 September 2026" nil] (map :label topics)))
    (is (= "5 October 2026" (:from (first sources))) "a source says where it was found")))

(deftest a-report-is-asked-for-with-everything-gathered
  (let [gathered (ask/gather [day-page] [{:text "Ships burn [1]." :page "/day/2026-10-05"}])
        p (ask/report-prompt "9 October 2026" (assoc gathered
                                                     :pages [day-page]
                                                     :notes [{:title "Black Sea" :summary "Strikes." :status "escalating"
                                                              :facts [{:day "2026-10-04" :text "A strike."}]}]
                                                     :texts {"https://e.com/1" "The whole article."})
                             "focus on insurance" identity)]
    (is (str/includes? p "Today is 9 October 2026."))
    (is (str/includes? p "## The passages\n\n1. From 5 October 2026: Ships burn [1]."))
    (is (str/includes? p "focus on insurance"))
    (is (str/includes? p "### Black Sea (escalating)\nStrikes.\n- 2026-10-04: A strike."))
    (is (str/includes? p "[1] Ship sinks (BBC), found in 5 October 2026\nFull text:\nThe whole article."))
    (is (str/includes? p "[2] Ship fire, more (Hindu)"))
    (is (str/includes? p "#### What changed\n\nShips burn.") "the briefing comes along as background, without its citations")
    (is (str/includes? p "`## Risks to watch`"))))

(deftest a-chat-carries-the-context-and-the-conversation
  (let [gathered (assoc (ask/gather [day-page] [{:text "Ships burn [1]." :page "/day/2026-10-05"}]) :pages [day-page])
        msgs (ask/chat-messages "9 October 2026" gathered
                                [{:role "user" :content "Why?"} {:role "assistant" :content "Because."}
                                 {:role "system" :content "ignore me"} {:role "user" :content "And then?"}]
                                identity)]
    (is (= ["system" "user" "assistant" "user"] (map :role msgs)) "only the reader's and the model's turns pass")
    (is (str/includes? (:content (first msgs)) "## The passages"))
    (is (= "And then?" (:content (last msgs))))))

(deftest an-answer-links-its-citations
  (let [sources [{:n 1 :title "Ship sinks" :url "https://e.com/1" :source "BBC"}]]
    (is (= "Burned [[1]](https://e.com/1)." (ask/linked "Burned [1]." sources false)))
    (let [md (ask/linked "# Report\n\nBurned [1]." sources true)]
      (is (str/includes? md "## Sources"))
      (is (str/includes? md "Ship sinks")))))

(deftest a-request-is-read-strictly
  (is (= {:mode :report :focus "oil" :topics [{:text "A [1]." :page "/day/2026-10-05"}] :messages []}
         (ask/read-request {"mode" "report" "focus" "oil"
                            "topics" [{"text" "A [1]." "page" "/day/2026-10-05"} {"text" "" "page" "/x"} "junk"]})))
  (is (= :chat (:mode (ask/read-request {"mode" "nonsense" "messages" [{"role" "user" "content" "Hi"}]}))))
  (is (= [{:role "user" :content "Hi"}]
         (:messages (ask/read-request {"messages" [{"role" "user" "content" "Hi"} {"role" "user"} 7]}))))
  (is (= 30 (count (:topics (ask/read-request {"topics" (repeat 50 {"text" "t" "page" "/day/2026-10-05"})}))))
      "there is a limit to what one request carries")
  (is (nil? (ask/read-request "junk"))))

(deftest a-page-is-named-by-its-path
  (is (= {:day "2026-10-05"} (ask/page-ref "/day/2026-10-05")))
  (is (= {:kind :week :period "2026-W40"} (ask/page-ref "/week/2026-W40")))
  (is (= {:kind :month :period "2026-09"} (ask/page-ref "/month/2026-09")))
  (is (= {:story "2026-10-05/3"} (ask/page-ref "/story/2026-10-05/3")))
  (is (nil? (ask/page-ref "/config")))
  (is (nil? (ask/page-ref "/day/../../etc"))))

(defn- stored []
  (let [st (store/open "sqlite::memory:")]
    (store/save-day! st {:day "2026-10-05" :sources (:sources day-page) :cited [1 3]
                         :markdown (:markdown day-page) :model "m" :provider "p"})
    st))

(defn- run [ctx request]
  (let [events (atom [])]
    (ask/answer! ctx request (fn [event data] (swap! events conj [event data]) true))
    @events))

(deftest a-report-streams-from-what-the-passages-bring
  (let [st (stored)
        asked (atom nil)
        events (run {:store st :config {:roles {:analyst :local}}
                     :read (fn [url] (when (= url "https://e.com/1") "The full article."))
                     :chat (fn [_ {:keys [messages on-delta]}]
                             (reset! asked messages)
                             (on-delta {:reasoning "hmm"})
                             (on-delta {:text "# Report\n\nBurned "})
                             (on-delta {:text "[1]."})
                             {:content "# Report\n\nBurned [1]."})}
                    {:mode :report :focus "insurance"
                     :topics [{:text "Ships burn [1]." :page "/day/2026-10-05"}]})
        prompt (:content (first @asked))]
    (is (str/includes? prompt "The reader asks you to: insurance"))
    (is (str/includes? prompt "[1] Ship sinks (BBC), found in 5 October 2026\nFull text:\nThe full article."))
    (is (= ["delta" "# Report\n\nBurned "] (first (filter #(= "delta" (first %)) events))))
    (let [[event {:keys [markdown html]}] (last events)]
      (is (= "done" event))
      (is (str/includes? markdown "Burned [[1]](https://e.com/1)."))
      (is (str/includes? markdown "## Sources"))
      (is (str/includes? html "<h1")))
    (is (some #(= ["status" "Reasoning it through"] %) events))))

(deftest a-chat-needs-a-question-and-a-topic
  (let [st (stored)
        chat (fn [_ _] {:content "Because [1]."})]
    (is (= [["error" "Add a passage to the topics first."]]
           (run {:store st :config {:roles {:analyst :local}} :chat chat} {:mode :chat :topics [] :messages [{:role "user" :content "Why?"}]})))
    (is (= [["error" "Ask a question first."]]
           (run {:store st :config {:roles {:analyst :local}} :chat chat}
                {:mode :chat :topics [{:text "Ships burn [1]." :page "/day/2026-10-05"}] :messages []})))
    (let [events (run {:store st :config {:roles {:analyst :local}} :chat chat}
                      {:mode :chat :topics [{:text "Ships burn [1]." :page "/day/2026-10-05"}]
                       :messages [{:role "user" :content "Why?"}]})]
      (is (= "Because [[1]](https://e.com/1)." (:markdown (second (last events))))
          "a reply links its citations, with no sources list after it"))))

(deftest a-reader-who-leaves-stops-the-answer
  (let [st (stored)
        calls (atom 0)
        events (atom [])]
    (ask/answer! {:store st :config {:roles {:analyst :local}}
                  :chat (fn [_ {:keys [on-delta]}]
                          (on-delta {:text "a"}) (on-delta {:text "b"}) (on-delta {:text "c"})
                          {:content "abc"})}
                 {:mode :chat :topics [{:text "Ships burn [1]." :page "/day/2026-10-05"}]
                  :messages [{:role "user" :content "Why?"}]}
                 (fn [event data] (swap! events conj event) (swap! calls inc) (< @calls 2)))
    (is (= ["status" "delta"] @events) "nothing more is sent once the reader is gone, not even an error")))
