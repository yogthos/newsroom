(ns newsroom.entities-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [newsroom.entities :as entities]))

(defn- e [& names] (mapv (fn [n] {:name n :kind "organisation"}) names))

(defn- report [n group & {:as more}]
  (merge {:title (str "Report " n) :url (str "https://e.com/" n) :source "Wire" :group group
          :group-title (str "Story " group) :importance 5}
         more))

(deftest an-entity-is-known-by-its-name-however-written
  (is (= "samsung electronics" (entities/entity-key " Samsung  Electronics ")))
  (is (= "turkiye" (entities/entity-key "Türkiye")))
  (is (= "s&p 500" (entities/entity-key "S&P 500"))))

(deftest the-days-stories-with-their-citations
  (let [ranked [(report 1 "G1" :entities (e "Samsung")) (report 2 "G1" :entities (e "Samsung"))
                (report 3 "G2" :entities (e "Bank of Korea") :story "2026-10-01/4")]
        numbered [(assoc (report 1 "G1") :n 1 :story "2026-10-13/1")]
        stories (entities/day-stories "2026-10-13" ranked numbered)]
    (is (= [{:story "2026-10-13/1" :title "Story G1" :importance 5 :entities (e "Samsung") :ns [1]}
            {:story "2026-10-01/4" :title "Story G2" :importance 5 :entities (e "Bank of Korea") :ns []}]
           stories)
        "a story the briefing was given is named by its storyline and cited by its numbers; one cut for space keeps its own")))

(def ^:private day-stories
  [{:story "a" :title "Kospi plunges" :importance 9 :entities (e "Kospi" "Samsung") :ns [1 2]}
   {:story "b" :title "Chip shares sell off" :importance 8 :entities (e "Samsung" "SK Hynix") :ns [3]}
   {:story "c" :title "Samsung phone launch" :importance 2 :entities (e "Samsung") :ns []}
   {:story "d" :title "Fed holds" :importance 7 :entities (e "Federal Reserve") :ns [4]}])

(deftest a-day-is-stored-as-who-was-in-which-story
  (let [rows (entities/mentions "2026-10-13" day-stories #{1 4})]
    (is (= 6 (count rows)))
    (is (= {:day "2026-10-13" :entity "samsung" :name "Samsung" :kind "organisation" :story "a"
            :title "Kospi plunges" :importance 9 :cited true}
           (first (filter #(and (= "samsung" (:entity %)) (= "a" (:story %))) rows))))
    (is (false? (:cited (first (filter #(= "b" (:story %)) rows)))) "a story none of whose reports was cited")))

(deftest the-web-of-the-day-ties-stories-together-through-who-is-in-them
  (let [web (entities/day-web (entities/mentions "2026-10-13" day-stories #{}) day-stories)]
    (is (= ["Samsung"] (map :name web)) "an entity in only one story ties nothing together")
    (is (= [{:title "Kospi plunges" :ns [1 2]} {:title "Chip shares sell off" :ns [3]}]
           (:stories (first web)))
        "only the stories the analyst can cite")
    (is (= 19 (:weight (first web))) "weighed by the importance of every story it's in")))

(defn- day-of [day stories] (entities/mentions day stories #{}))

(deftest the-day-is-compared-with-the-last
  (let [yesterday (day-of "2026-10-12" [{:story "y1" :title "Fed meets" :importance 8 :entities (e "Federal Reserve" "Powell")}
                                        {:story "y2" :title "Samsung earnings" :importance 3 :entities (e "Samsung")}
                                        {:story "y3" :title "Tariff talks" :importance 9 :entities (e "European Union" "China")}])
        earlier (day-of "2026-10-10" [{:story "x1" :title "Kospi record" :importance 4 :entities (e "Kospi")}])
        today (day-of "2026-10-13" day-stories)
        {:keys [new rising fading links]} (entities/changes today yesterday (concat earlier yesterday))]
    (is (= ["SK Hynix"] (map :name new)) "in none of the days before")
    (is (= [{:name "Samsung" :kind "organisation" :was 3 :now 19}] (map #(select-keys % [:name :kind :was :now]) rising)))
    (is (= #{"European Union" "China" "Powell"} (set (map :name fading))) "prominent yesterday, gone today")
    (is (not (some #{"Federal Reserve"} (map :name fading))) "still in the news")
    (is (= [["Kospi" "Samsung"] ["SK Hynix" "Samsung"]] (map (juxt :a :b) links))
        "two prominent entities in one story for the first time")))

(deftest the-analyst-reads-the-web-and-what-changed
  (let [today (day-of "2026-10-13" day-stories)
        yesterday (day-of "2026-10-12" [{:story "y3" :title "Tariff talks" :importance 9 :entities (e "China")}])
        block (entities/web-block (entities/day-web today day-stories) (entities/changes today yesterday yesterday)
                                  "12 October 2026")]
    (is (str/includes? block "- **Samsung** (organisation): Kospi plunges [1, 2]; Chip shares sell off [3]"))
    (is (str/includes? block "Since 12 October 2026"))
    (is (str/includes? block "Gone quiet: China"))
    (is (str/includes? block "Newly linked: Kospi and Samsung")))
  (is (nil? (entities/web-block [] {} "12 October 2026")) "nothing to say, no block"))

(deftest a-period-is-read-through-its-entities
  (let [period (concat (day-of "2026-10-12" [{:story "s1" :title "Kospi falls" :importance 9 :entities (e "Kospi" "Samsung")}])
                       (day-of "2026-10-13" [{:story "s1" :title "Kospi falls further" :importance 8 :entities (e "Kospi")}
                                             {:story "s2" :title "Fed holds" :importance 6 :entities (e "Federal Reserve")}]))
        before (day-of "2026-10-05" [{:story "s0" :title "Fed meets" :importance 7 :entities (e "Federal Reserve")}
                                     {:story "s9" :title "Oil spikes" :importance 9 :entities (e "OPEC")}])
        block (entities/period-block period before)]
    (is (str/includes? block "- **Kospi** (organisation): 2 days, new; Kospi falls further"))
    (is (str/includes? block "- **Federal Reserve** (organisation): 1 day, steady; Fed holds"))
    (is (str/includes? block "Gone quiet since the period before: OPEC"))))
