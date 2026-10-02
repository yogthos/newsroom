(ns newsroom.trends-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [newsroom.trends :as trends]))

(defn- unit [v] (let [n (Math/sqrt (reduce + (map * v v)))] (mapv #(/ % n) v)))

(def fed (unit [8 1 0]))
(def fed-again (unit [7.8 1.2 0.1]))
(def space (unit [0 1 8]))

(defn- src
  "A stored source: `n` on `day`, of `story`, carried by `outlets` outlets."
  [day n story v & {:keys [outlets cited? title] :or {outlets 1}}]
  {:day day :n n :story story :vector v :cited? cited?
   :title (or title (str "Story " story)) :url (str "https://e.com/" day "/" n) :source "Wire"
   :also (vec (for [i (range (dec outlets))] {:source (str "Outlet " i) :url (str "https://o" i ".org/x")}))})

;; --- periods ---------------------------------------------------------------------

(deftest weeks-are-iso-weeks
  (is (= "2026-W40" (trends/period-of :week "2026-10-02")))
  (is (= ["2026-09-28" "2026-10-04"] (trends/period-range :week "2026-W40")) "Monday to Sunday")
  (testing "the first week of a year is the one with its Thursday"
    (is (= "2026-W53" (trends/period-of :week "2027-01-01")))
    (is (= "2020-W53" (trends/period-of :week "2021-01-03")))
    (is (= "2026-W01" (trends/period-of :week "2025-12-29"))))
  (testing "a week a year doesn't have is no period"
    (is (nil? (trends/period-range :week "2025-W53")))
    (is (nil? (trends/period-range :week "2026-W00")))
    (is (nil? (trends/period-range :week "nonsense")))))

(deftest months
  (is (= "2026-09" (trends/period-of :month "2026-09-30")))
  (is (= ["2028-02-01" "2028-02-29"] (trends/period-range :month "2028-02")))
  (is (nil? (trends/period-range :month "2026-13")))
  (is (nil? (trends/period-range :year "2026"))))

(deftest the-last-whole-period
  (is (= "2026-W40" (trends/previous-period :week "2026-10-05")) "on the Monday after")
  (is (= "2026-W39" (trends/previous-period :week "2026-10-04")) "the Sunday is still this week")
  (is (= "2026-09" (trends/previous-period :month "2026-10-01")))
  (is (= "2025-12" (trends/previous-period :month "2026-01-15"))))

(deftest the-baseline-is-the-four-periods-before
  (is (= ["2026-08-31" "2026-09-27"] (trends/baseline-range :week "2026-W40")))
  (is (= ["2026-05-01" "2026-08-31"] (trends/baseline-range :month "2026-09"))))

;; --- ranking ---------------------------------------------------------------------

(def history
  [(src "2026-09-28" 1 "2026-09-28/1" fed)
   (src "2026-09-29" 4 "2026-09-28/1" fed-again)
   (src "2026-09-29" 2 "2026-09-29/2" space)])

(deftest items-continue-the-storyline-they-sit-closest-to
  (let [lines (trends/storylines history)
        linked (trends/link-stories [{:title "Fed again" :vector (unit [8 1.1 0])}
                                     {:title "Something new" :vector (unit [0 8 -1])}
                                     {:title "No vector"}]
                                    lines 0.9)]
    (is (= 2 (count lines)))
    (is (= ["2026-09-28/1" nil nil] (mapv :story linked)))
    (testing "no threshold, no following"
      (is (every? nil? (map :story (trends/link-stories [{:vector fed}] lines nil)))))))

(deftest a-story-that-keeps-running-outweighs-a-one-off
  (let [lines (trends/storylines history)
        items [{:title "One-off, two outlets" :source "A" :also [{:source "B"}]}
               {:title "Fed, third day" :source "A" :story "2026-09-28/1"}
               {:title "Plain"  :source "C"}]
        ranked (trends/rank items lines "2026-09-30" 2)]
    (is (= ["Fed, third day" "One-off, two outlets" "Plain"] (mapv :title ranked)))
    (is (= [3 1 1] (mapv :days ranked)) "days in the news, today included")
    (testing "a day counts half as much every half-life"
      ;; today's outlet, plus yesterday at 2^-1/2 and the day before at 1/2
      (is (= 2.21 (:weight (first ranked)))))
    (testing "equal weights keep their order"
      (is (= ["b" "a"] (mapv :title (trends/rank [{:title "b"} {:title "a"}] [] "2026-09-30" 2)))))))

;; --- digests ---------------------------------------------------------------------

(def window
  [;; ran all week, and in the weeks before
   (src "2026-09-28" 1 "war" fed :outlets 3 :cited? true :title "War grinds on")
   (src "2026-09-29" 1 "war" fed :outlets 2)
   (src "2026-09-30" 1 "war" fed :outlets 2)
   (src "2026-10-01" 1 "war" fed :outlets 2)
   ;; new at the end of the week
   (src "2026-10-01" 2 "chips" space :outlets 3 :title "Chip controls")
   (src "2026-10-02" 1 "chips" space :outlets 4)
   ;; big early, gone late
   (src "2026-09-28" 2 "summit" space :outlets 4 :title "Summit opens")
   (src "2026-09-29" 2 "summit" space :outlets 1)
   ;; one outlet, one day
   (src "2026-09-30" 2 "minor" space)])

(def baseline
  "The war at about the rate it runs now, on six of the fourteen days
  before, as the coverage kept for those days has it."
  (vec (for [d ["2026-09-14" "2026-09-15" "2026-09-17" "2026-09-20" "2026-09-22" "2026-09-25"]]
         {:day d :n 1 :story "war" :outlets 3 :cited? false})))

(deftest a-periods-storylines-are-ranked-and-labelled
  (let [lines (trends/digest-storylines window baseline 5 14)
        by-story (into {} (map (juxt :story identity)) lines)]
    (is (= #{"war" "chips" "summit"} (set (keys by-story))) "a one-outlet one-off is left out")
    (is (= "war" (:story (first lines))) "the longest and widest runs first")
    (is (= :persistent (:trend (by-story "war"))))
    (is (= :emerging (:trend (by-story "chips"))))
    (is (= :fading (:trend (by-story "summit"))))
    (is (= "War grinds on" (:title (by-story "war"))) "titled by its most widely carried source")
    (is (= [4 9 18] ((juxt :days :outlets :baseline) (by-story "war"))))
    (testing "with no baseline there is no growth to call a story emerging by"
      (is (nil? (:growth (first (trends/digest-storylines window [] 5 0))))))))

(deftest a-digest-cites-each-storyline-by-its-days
  (let [lines (trends/cite-storylines
               (map #(assoc % :candidates (trends/digest-sources (:sources %) 3))
                    (trends/digest-storylines window baseline 5 14)))]
    (is (= [[1 2 3] [4 5] [6 7]] (mapv #(mapv :n (:cites %)) lines)) "numbered across storylines")
    (is (= ["2026-09-29" "2026-09-30" "2026-10-01"] (mapv :day (:cites (first lines))))
        "the latest days when there are more than room for")
    (let [prompt (trends/render-digest-prompt "Digest of {{period}}.\n\n{{days}}\n\n{{stories}}"
                                              "the week of 28 September 2026"
                                              (trends/days-block [{:day "2026-09-29" :tldr "Quiet."}
                                                                  {:day "2026-09-28" :tldr "Busy."}
                                                                  {:day "2026-09-30" :tldr nil}]
                                                                 identity)
                                              (trends/digest-block lines identity))]
      (is (str/starts-with? prompt "Digest of the week of 28 September 2026."))
      (is (str/includes? prompt "- 2026-09-28: Busy.\n- 2026-09-29: Quiet.") "standfirsts in order")
      (is (str/includes? prompt "### War grinds on\nTrend: persistent"))
      (is (str/includes? prompt "[4] 2026-10-01: Chip controls (Wire)"))
      (testing "a fact from the notes is cited with the report it came from"
        (is (str/includes? (trends/digest-block
                            (trends/cite-storylines [{:title "Chips" :trend :emerging :days 2 :outlets 5 :baseline 0
                                                      :summary "Curbs keep widening."
                                                      :candidates [{:day "2026-10-01" :fact "Curbs announced."
                                                                    :title "Chip curbs" :source "Wire"
                                                                    :url "https://e.com/c"}]}])
                            identity)
                           "Where it stands: Curbs keep widening.\n\n[1] 2026-10-01: Curbs announced.\nChip curbs (Wire), https://e.com/c")))
      (testing "a template without {{stories}} gets them at the end"
        (is (str/ends-with? (trends/render-digest-prompt "Digest." "p" "" "STORIES") "\n\nSTORIES"))))))
