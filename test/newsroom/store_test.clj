(ns newsroom.store-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jdbc.core :as jdbc]
            [newsroom.store :as store]))

(defn- fresh [] (store/open "sqlite::memory:"))

(def sources
  [{:n 1 :title "A" :url "https://e.com/a" :source "Wire" :summary "sa" :published "2026-09-30"}
   {:n 2 :title "B" :url "https://e.com/b" :source "Web" :summary "" :published nil}
   {:n 3 :title "C" :url "https://e.com/c" :source "Wire" :summary "sc" :published nil}])

(deftest a-day-round-trips
  (let [db (fresh)]
    (try
      (store/save-day! db {:day "2026-09-30" :sources sources :cited [1 3]
                           :markdown "# Brief\n\n[1] and [3]" :model "m" :provider "p"})
      (let [day (store/day db "2026-09-30")]
        (is (= "# Brief\n\n[1] and [3]" (:markdown day)))
        (is (= "m" (:model day)))
        (is (= "p" (:provider day)))
        (is (string? (:created-at day)))
        (is (= sources (:sources day)) "every gathered source, in order")
        (is (= [1 3] (map :n (:cited day))) "the sources the briefing cites"))
      (finally (store/close db)))))

(deftest an-analysis-source-is-kept-as-one-and-isnt-coverage
  (let [db (fresh)
        piece {:n 4 :title "Why the ceasefire won't hold" :url "https://youtube.com/watch?v=x" :source "Daniel Davis"
               :summary "An argument." :published nil :kind :analysis :story "s1"}]
    (try
      (store/save-day! db {:day "2026-09-30" :sources (conj (mapv #(assoc % :story "s1") sources) piece) :cited [4]
                           :markdown "x [4]" :model "m" :provider "p"})
      (is (= :analysis (:kind (last (:sources (store/day db "2026-09-30"))))))
      (is (nil? (:kind (first (:sources (store/day db "2026-09-30"))))) "news is the default, and not written out")
      (is (= [nil nil nil :analysis] (map :kind (store/sources-between db "2026-09-30" "2026-09-30")))
          "what storylines and digests read keeps it too")
      (is (= [1 2 3] (map :n (store/coverage-between db "2026-09-30" "2026-09-30")))
          "a commentator carrying a story isn't an outlet reporting it")
      (finally (store/close db)))))

(deftest a-day-with-a-tldr-and-corroborated-source-round-trips
  (let [db (fresh)]
    (try
      (store/save-day! db {:day "2026-09-30"
                           :sources [(assoc (first sources) :also [{:source "AP" :url "https://ap.example.com/1" :title "AP copy"}
                                                                   {:source "Reuters" :url "https://r.example.com/1" :title "Reuters copy"}])]
                           :cited [1] :tldr "Rates up, tech down"
                           :markdown "# The day\n\n> Rates up, tech down" :model "m" :provider "p"})
      (let [day (store/day db "2026-09-30")]
        (is (= "Rates up, tech down" (:tldr day)))
        (is (= ["AP" "Reuters"] (map :source (:also (first (:sources day))))))
        (is (= "https://ap.example.com/1" (:url (first (:also (first (:sources day))))))))
      (is (= [{:day "2026-09-30" :tldr "Rates up, tech down"}] (store/archive db)))
      (finally (store/close db)))))

(deftest source-health-rolls-up-over-runs
  (let [db (fresh)]
    (try
      (store/record-source-health! db [{:source "BBC World" :error nil}
                                       {:source "Dead feed" :error "timed out after 30s"}])
      (let [h (into {} (map (juxt :name identity)) (store/source-health db))]
        (is (= 0 (:consecutive-failures (h "BBC World"))))
        (is (string? (:last-ok (h "BBC World"))))
        (is (= 1 (:consecutive-failures (h "Dead feed"))))
        (is (= "timed out after 30s" (:last-error (h "Dead feed"))))
        (is (nil? (:last-ok (h "Dead feed")))))
      (testing "failures accumulate until a success resets them"
        (store/record-source-health! db [{:source "Dead feed" :error "connection refused"}])
        (is (= 2 (:consecutive-failures (get (into {} (map (juxt :name identity))
                                                          (store/source-health db))
                                             "Dead feed")))
            "the second failure bumps the count rather than restarting it")
        (store/record-source-health! db [{:source "Dead feed" :error nil}])
        (let [h (into {} (map (juxt :name identity)) (store/source-health db))]
          (is (= 0 (:consecutive-failures (h "Dead feed"))))
          (is (nil? (:last-error (h "Dead feed"))))
          (is (nil? (h "BBC World")) "a source the run no longer fetches is forgotten")))
      (finally (store/close db)))))

(deftest a-source-keeps-its-storyline-for-later-days
  (let [db (fresh)
        v (mapv #(/ % (Math/sqrt 2)) [1.0 1.0])]
    (try
      (store/save-day! db {:day "2026-09-29" :sources [(assoc (first sources) :vector v :story "2026-09-29/1" :weight 2.5)]
                           :cited [1] :markdown "x" :model "m" :provider "p"})
      (store/save-day! db {:day "2026-09-30" :sources [(second sources)] :cited [] :markdown "y" :model "m" :provider "p"})
      (store/save-day! db {:day "2026-10-01" :sources [(nth sources 2)] :cited [] :markdown "z" :model "m" :provider "p"})
      (let [[a b :as between] (store/sources-between db "2026-09-29" "2026-09-30")]
        (is (= ["2026-09-29" "2026-09-30"] (map :day between)) "both ends included, nothing past them")
        (is (= "2026-09-29/1" (:story a)))
        (is (= 2.5 (:weight a)))
        (is (true? (:cited? a)))
        (is (< (Math/abs (- 1.0 (reduce + (map * v (:vector a))))) 1e-4) "the vector comes back")
        (is (nil? (:vector b)))
        (is (false? (:cited? b))))
      (is (nil? (:vector (first (:sources (store/day db "2026-09-29"))))) "a day's page doesn't decode vectors")
      (finally (store/close db)))))

(deftest coverage-outlives-the-days
  (let [db (fresh)
        day (fn [d] {:day d :sources [(assoc (first sources) :story "s1" :url "https://one.org/a"
                                             :also [{:source "Two" :url "https://two.org/a"}])]
                     :cited [1] :markdown d :model "m" :provider "p"})]
    (try
      (doseq [d ["2026-09-28" "2026-09-29" "2026-09-30"]] (store/save-day! db (day d)))
      (is (= [{:day "2026-09-28" :n 1 :story "s1" :outlets 2 :cited? true}]
             (store/coverage-between db "2026-09-28" "2026-09-28")))
      (store/prune! db 1)
      (is (= ["2026-09-30"] (store/days db)) "the days go")
      (is (= 3 (count (store/coverage-between db "2026-01-01" "2026-12-31"))) "their coverage stays")
      (finally (store/close db)))))

(deftest notes-and-standfirsts-outlive-the-days
  (let [db (fresh)
        note {:title "The Fed's pause" :summary "Held." :first-day "2026-09-28" :last-day "2026-09-28"
              :facts [{:day "2026-09-28" :text "Held." :url "https://e.com/a" :source "Wire" :headline "A"}]}]
    (try
      (doseq [d ["2026-09-28" "2026-09-29"]]
        (store/save-day! db {:day d :sources [(assoc (first sources) :story "fed")] :cited []
                             :tldr (str "Day " d) :markdown d :model "m" :provider "p"}))
      (store/save-notes! db {"fed" note})
      (is (= {"fed" (assoc note :story "fed")} (store/notes db ["fed" "other"])))
      (is (= {} (store/notes db [])))
      (store/prune! db 1)
      (is (= ["2026-09-29"] (store/days db)))
      (is (= ["Day 2026-09-28" "Day 2026-09-29"] (map :tldr (store/standfirsts-between db "2026-09-01" "2026-09-30")))
          "the standfirsts stay with the coverage")
      (is (some? (get (store/notes db ["fed"]) "fed")) "and so do the notes")
      (finally (store/close db)))))

(deftest coverage-is-filled-in-for-an-older-database
  (let [f (str (System/getProperty "java.io.tmpdir") "/newsroom-coverage-" (System/currentTimeMillis) ".sqlite3")]
    (try
      (let [db (store/open f)]
        (store/save-day! db {:day "2026-09-30" :sources sources :cited [3] :markdown "x" :model "m" :provider "p"})
        ;; as a database from before coverage was kept
        (jdbc/execute! (:conn db) "delete from coverage")
        (store/close db))
      (let [db (store/open f)]
        (try
          (is (= [[1 false] [2 false] [3 true]]
                 (map (juxt :n :cited?) (store/coverage-between db "2026-09-30" "2026-09-30"))))
          (finally (store/close db))))
      (finally (io/delete-file f true)))))

(deftest a-digest-round-trips
  (let [db (fresh)]
    (try
      (store/save-digest! db {:kind :week :period "2026-W40"
                              :sources [{:n 1 :day "2026-09-29" :title "A" :url "https://e.com/a" :source "Wire"}
                                        {:n 2 :day "2026-09-30" :title "B" :url "https://e.com/b" :source "Web"}]
                              :cited [2] :markdown "# Week" :tldr "A week." :model "m" :provider "p"})
      (store/save-digest! db {:kind :month :period "2026-09" :sources [] :cited []
                              :markdown "# Month" :model "m" :provider "p"})
      (let [d (store/digest db :week "2026-W40")]
        (is (= "# Week" (:markdown d)))
        (is (= "A week." (:tldr d)))
        (is (= [false true] (map :cited? (:sources d))))
        (is (= "2026-09-30" (:day (second (:sources d))))))
      (is (nil? (store/digest db :week "2026-W39")))
      (is (= #{{:kind :week :period "2026-W40" :tldr "A week."} {:kind :month :period "2026-09" :tldr nil}}
             (set (store/digests db))))
      (testing "pruning the days leaves the digests"
        (store/prune! db 0)
        (is (some? (store/digest db :week "2026-W40"))))
      (finally (store/close db)))))

(deftest saving-a-day-again-replaces-it
  (let [db (fresh)]
    (try
      (store/save-day! db {:day "2026-09-30" :sources sources :cited [1 2 3]
                           :markdown "first" :model "m" :provider "p"})
      (store/save-day! db {:day "2026-09-30" :sources (take 1 sources) :cited [1]
                           :markdown "second" :model "m" :provider "p"})
      (let [day (store/day db "2026-09-30")]
        (is (= "second" (:markdown day)))
        (is (= 1 (count (:sources day))))
        (is (= [1] (map :n (:cited day)))))
      (finally (store/close db)))))

(deftest days-are-listed-newest-first
  (let [db (fresh)]
    (try
      (doseq [d ["2026-09-28" "2026-09-30" "2026-09-29"]]
        (store/save-day! db {:day d :sources [] :cited [] :markdown d :model "m" :provider "p"}))
      (is (= ["2026-09-30" "2026-09-29" "2026-09-28"] (store/days db)))
      (is (nil? (store/day db "2026-01-01")))
      (finally (store/close db)))))

(deftest pruning-keeps-the-newest-days
  (let [db (fresh)
        days ["2026-09-26" "2026-09-27" "2026-09-28" "2026-09-29" "2026-09-30"]]
    (try
      (doseq [d days]
        (store/save-day! db {:day d :sources sources :cited [1] :markdown d :model "m" :provider "p"}))
      (testing "unlimited keeps everything"
        (is (= [] (store/prune! db -1)))
        (is (= 5 (count (store/days db)))))
      (testing "a limit drops the oldest days, with their sources"
        (is (= ["2026-09-27" "2026-09-26"] (store/prune! db 3)))
        (is (= ["2026-09-30" "2026-09-29" "2026-09-28"] (store/days db)))
        (is (nil? (store/day db "2026-09-26")))
        (is (= 3 (count (:sources (store/day db "2026-09-28"))))))
      (testing "nothing more to drop"
        (is (= [] (store/prune! db 3))))
      (finally (store/close db)))))

(deftest the-entities-of-each-day-are-kept-for-each-reader
  (let [db (fresh)
        row (fn [day entity story & {:as more}]
              (merge {:day day :entity entity :name (str/capitalize entity) :kind "organisation" :story story
                      :title (str "Story " story) :importance 7 :cited true}
                     more))]
    (try
      (store/save-mentions! db "2026-09-29" [(row "2026-09-29" "samsung" "a") (row "2026-09-29" "kospi" "a")])
      (store/save-mentions! db "2026-09-30" [(row "2026-09-30" "samsung" "b" :cited false :kind nil)])
      (is (= [(row "2026-09-29" "kospi" "a") (row "2026-09-29" "samsung" "a")
              (row "2026-09-30" "samsung" "b" :cited false :kind nil)]
             (store/mentions-between db "2026-09-01" "2026-09-30")))
      (store/save-mentions! db "2026-09-29" [(row "2026-09-29" "fed" "c")])
      (is (= ["fed"] (map :entity (store/mentions-between db "2026-09-29" "2026-09-29"))) "a rerun replaces the day's")
      (let [theirs (store/for-user db (store/ensure-user! db {:issuer "i" :subject "t"}))]
        (is (= [] (store/mentions-between theirs "2026-09-01" "2026-09-30")) "another reader's are their own"))
      (finally (store/close db)))))

(deftest the-desks-trends-are-kept-with-the-coverage
  (let [db (fresh)]
    (try
      (store/save-trends! db "2026-09-29" [{:thread "2026-09-29/T1" :name "Energy squeeze" :direction "holding"
                                            :summary "Oil up." :stories ["fed" "oil"]}])
      (store/save-trends! db "2026-09-30" [{:thread "2026-09-29/T1" :name "Energy squeeze" :direction "strengthening"
                                            :summary "Oil higher." :stories ["oil"]}
                                           {:thread "2026-09-30/T2" :name "Chip blocs" :stories []}
                                           {:thread "2026-09-30/F1" :level "force" :name "Tech stacks split" :stories ["oil"]}])
      (is (= [{:day "2026-09-29" :thread "2026-09-29/T1" :level "trend" :name "Energy squeeze" :direction "holding"
               :summary "Oil up." :stories ["fed" "oil"]}]
             (store/trends-between db "2026-09-29" "2026-09-29")))
      (is (= ["trend" "trend" "force"] (map :level (store/trends-between db "2026-09-30" "2026-09-30")))
          "a structural force is kept beside the trends, by its level")
      (is (= ["2026-09-29/T1" "2026-09-29/T1" "2026-09-30/T2" "2026-09-30/F1"]
             (map :thread (store/trends-between db "2026-09-01" "2026-09-30"))))
      (store/save-trends! db "2026-09-30" [])
      (is (= 1 (count (store/trends-between db "2026-09-01" "2026-09-30"))) "saving a day again replaces its trends")
      (store/save-notes! db {"fed" {:title "T" :summary "S" :status "escalating" :facts [] :first-day "2026-09-29"
                                    :last-day "2026-09-30"}})
      (is (= "escalating" (:status (get (store/notes db ["fed"]) "fed"))) "a note keeps its status")
      (finally (store/close db)))))

(deftest a-gap-searchs-result-is-kept-but-isnt-coverage
  (let [db (fresh)]
    (try
      (store/save-day! db {:day "2026-09-30"
                           :sources [(assoc (first sources) :story "fed")
                                     (assoc (second sources) :story "fed" :gap "The vote")]
                           :cited [1 2] :markdown "m" :model "m" :provider "p"})
      (is (= "The vote" (:gap (second (:sources (store/day db "2026-09-30"))))))
      (is (= [1] (map :n (store/coverage-between db "2026-09-30" "2026-09-30")))
          "a result found for a story isn't counted as an outlet carrying it")
      (is (= "The vote" (:gap (second (store/sources-between db "2026-09-30" "2026-09-30")))))
      (finally (store/close db)))))

;; --- the retrospective -------------------------------------------------------------

(def ^:private projections
  [{:made-on "2026-09-28" :claim "The Fed cuts by October." :subject "economy" :due "2026-10-01"
    :story "fed"}
   {:made-on "2026-09-28" :claim "Chip exports fall." :subject "technology" :due "2026-11-30"
    :story "chips" :source "Tech" :outlet "tech.org" :url "https://tech.org/1" :by "Tech's analysts"}])

(deftest projections-are-kept-until-they-fall-due
  (let [db (fresh)]
    (try
      (store/save-projections! db "day" "2026-09-28" projections)
      (store/save-projections! db "week" "2026-W39" [{:made-on "2026-09-27" :claim "Oil climbs." :subject "energy"
                                                      :due "2026-10-03"}])
      (testing "what falls due by a period's end, oldest first"
        (let [due (store/projections-due db "2026-10-04" :week "2026-W40")]
          (is (= ["Oil climbs." "The Fed cuts by October."] (map :claim due)))
          (is (every? :id due))
          (is (= {:made-on "2026-09-28" :claim "The Fed cuts by October." :subject "economy" :due "2026-10-01"
                  :story "fed" :status "open" :origin "day" :ref "2026-09-28"}
                 (select-keys (second due) [:made-on :claim :subject :due :story :status :origin :ref :source])))))
      (testing "saving a day's projections again replaces them"
        (store/save-projections! db "day" "2026-09-28" [(second projections)])
        (is (= ["Oil climbs."] (map :claim (store/projections-due db "2026-10-04" :week "2026-W40")))))
      (testing "a verdict settles one, and the retrospective that gave it sees it again on a rerun"
        (let [[oil] (store/projections-due db "2026-10-04" :week "2026-W40")]
          (store/save-verdicts! db [{:id (:id oil) :status "held" :closeness 0.8 :reason "It climbed."
                                     :due (:due oil) :checked-kind "week" :checked-period "2026-W40"}])
          (is (= [{:claim "Oil climbs." :status "held" :closeness 0.8 :reason "It climbed."
                   :checked-kind "week" :checked-period "2026-W40"}]
                 (map #(select-keys % [:claim :status :closeness :reason :checked-kind :checked-period])
                      (store/projections-due db "2026-10-04" :week "2026-W40"))))
          (is (empty? (store/projections-due db "2026-10-04" :month "2026-09")) "but no other does")
          (is (= ["Oil climbs."] (map :claim (store/projections-checked db :week "2026-W40"))))
          (is (= ["Oil climbs."] (map :claim (store/judged-projections db))))))
      (testing "the outlet's are kept with who made them"
        (is (= {:source "Tech" :outlet "tech.org" :url "https://tech.org/1" :by "Tech's analysts"}
               (select-keys (first (store/projections-due db "2026-12-01" :month "2026-11"))
                            [:source :outlet :url :by]))))
      (testing "they outlive the days"
        (store/save-day! db {:day "2026-09-28" :sources [] :cited [] :markdown "m" :model "m" :provider "p"})
        (store/save-day! db {:day "2026-09-29" :sources [] :cited [] :markdown "m" :model "m" :provider "p"})
        (store/prune! db 1)
        (is (= 1 (count (store/judged-projections db))))
        (is (= 1 (count (store/projections-due db "2026-12-01" :month "2026-11")))))
      (finally (store/close db)))))

(deftest the-forecasts-still-open-are-newsrooms-own-made-before-today
  (let [db (fresh)]
    (try
      (store/save-projections! db "day" "2026-09-28" projections)
      (store/save-projections! db "day" "2026-09-30" [{:made-on "2026-09-30" :claim "Today's own." :subject "energy"
                                                       :due "2026-10-30"}])
      (store/save-projections! db "week" "2026-W39" [{:made-on "2026-09-27" :claim "Oil climbs." :subject "energy"
                                                      :due "2026-10-03"}])
      (let [[oil] (store/projections-due db "2026-10-04" :week "2026-W40")]
        (store/save-verdicts! db [{:id (:id oil) :status "held" :closeness 0.8 :reason "It climbed."
                                   :due (:due oil) :checked-kind "week" :checked-period "2026-W40"}]))
      (is (= ["The Fed cuts by October."] (map :claim (store/open-projections db "2026-09-30")))
          "not the outlets', not the settled, not today's")
      (finally (store/close db)))))

(deftest a-retrospective-round-trips
  (let [db (fresh)]
    (try
      (is (nil? (store/latest-retrospective db)))
      (store/save-retrospective! db {:kind :week :period "2026-W40" :summary "Two of three held."
                                     :lessons [{:subject "economy" :lesson "Cuts come late."}]
                                     :model "m" :provider "p"})
      (store/save-retrospective! db {:kind :month :period "2026-09" :summary "A month."
                                     :lessons [{:subject "energy" :lesson "Oil lags."}] :model "m" :provider "p"})
      (is (= {:kind :week :period "2026-W40" :summary "Two of three held."
              :lessons [{:subject "economy" :lesson "Cuts come late."}] :model "m" :provider "p"}
             (dissoc (store/retrospective db :week "2026-W40") :created-at)))
      (is (= "2026-09" (:period (store/latest-retrospective db))) "the latest written")
      (store/save-retrospective! db {:kind :week :period "2026-W40" :summary "Rewritten." :lessons [] :model "m" :provider "p"})
      (is (= "Rewritten." (:summary (store/retrospective db :week "2026-W40"))) "writing it again replaces it")
      (is (= "2026-W40" (:period (store/latest-retrospective db))))
      (finally (store/close db)))))

(deftest a-narration-round-trips
  (let [db (fresh)
        mp3 (byte-array [73 68 51 0 -1 0 65])
        transcript [{:speaker "Hugo" :text "Hello."} {:speaker "Kiki" :text "[excited] Hi!"}]]
    (try
      (store/save-day! db {:day "2026-09-30" :sources sources :cited [1] :markdown "# Brief" :model "m" :provider "p"})
      (is (nil? (store/narration db "2026-09-30")))
      (is (nil? (store/narration-audio db "2026-09-30")))
      (store/save-narration! db {:day "2026-09-30" :mp3 mp3 :transcript transcript :seconds 61.5
                                 :model "m" :provider "p"})
      (let [n (store/narration db "2026-09-30")]
        (is (= {:day "2026-09-30" :transcript transcript :seconds 61.5 :model "m" :provider "p"}
               (dissoc n :created-at)))
        (is (string? (:created-at n)))
        (is (not (contains? n :mp3)) "the audio is read on its own"))
      (is (= (vec mp3) (vec (store/narration-audio db "2026-09-30"))) "the bytes come back as they went in")
      (testing "recording it again replaces it"
        (store/save-narration! db {:day "2026-09-30" :mp3 (byte-array [1 2]) :transcript [] :seconds 1.0
                                   :model "m2" :provider "p"})
        (is (= [1 2] (vec (store/narration-audio db "2026-09-30"))))
        (is (= "m2" (:model (store/narration db "2026-09-30")))))
      (testing "a rerun of the day drops the narration of the briefing it replaces"
        (store/save-day! db {:day "2026-09-30" :sources sources :cited [1] :markdown "# Again" :model "m" :provider "p"})
        (is (nil? (store/narration db "2026-09-30"))))
      (finally (store/close db)))))

(deftest narrations-go-with-their-days
  (let [db (fresh)]
    (try
      (doseq [d ["2026-09-28" "2026-09-29" "2026-09-30"]]
        (store/save-day! db {:day d :sources [] :cited [] :markdown d :model "m" :provider "p"})
        (store/save-narration! db {:day d :mp3 (byte-array [1]) :transcript [] :seconds 1.0}))
      (is (= ["2026-09-28"] (store/prune! db 2)))
      (is (nil? (store/narration db "2026-09-28")) "the oldest goes with its day")
      (is (some? (store/narration db "2026-09-29")))
      (is (some? (store/narration-audio db "2026-09-30")))
      (finally (store/close db)))))

;; --- readers ---------------------------------------------------------------------

(deftest the-local-reader-is-there-from-the-start
  (let [db (fresh)]
    (try
      (is (= store/local-user-id (store/user-id db)) "a store opened plain is the local reader's")
      (is (= "local" (:subject (store/user db store/local-user-id))))
      (finally (store/close db)))))

(deftest a-reader-is-found-by-issuer-and-subject
  (let [db (fresh)]
    (try
      (let [a (store/ensure-user! db {:issuer "https://auth.example" :subject "abc" :name "Ann" :email "a@x"})
            again (store/ensure-user! db {:issuer "https://auth.example" :subject "abc" :name "Ann B" :email "a@x"})
            other (store/ensure-user! db {:issuer "https://other.example" :subject "abc"})]
        (is (= a again) "the same reader signing in again")
        (is (not= a other) "the same subject at another issuer is someone else")
        (is (not= store/local-user-id a))
        (is (= "Ann B" (:name (store/user db a))) "the name is kept up to date")
        (is (= #{store/local-user-id a other} (set (map :id (store/users db))))))
      (finally (store/close db)))))

(deftest each-reader-keeps-their-own-newsroom
  (let [db (fresh)
        uid (store/ensure-user! db {:issuer "https://auth.example" :subject "bob"})
        mine (store/for-user db store/local-user-id)
        theirs (store/for-user db uid)]
    (try
      (store/save-day! mine {:day "2026-09-30" :sources sources :cited [1] :markdown "# Mine\n\nwalrus [1]"
                             :model "m" :provider "p" :tldr "mine"})
      (store/save-settings! mine {:run-at "07:00"})
      (store/save-notes! mine {"s1" {:title "Note" :summary "walrus" :facts [] :last-day "2026-09-30"}})
      (store/save-digest! mine {:kind :week :period "2026-W40" :sources [] :cited [] :markdown "# Week" :model "m" :provider "p"})
      (store/save-projections! mine "day" "2026-09-30" [{:made-on "2026-09-30" :claim "c" :subject "s" :due "2026-10-01"}])
      (store/record-source-health! mine [{:source "Wire" :error nil}])
      (store/save-narration! mine {:day "2026-09-30" :mp3 (byte-array [1]) :transcript [] :seconds 1.0})
      (testing "another reader sees none of it"
        (is (nil? (store/day theirs "2026-09-30")))
        (is (empty? (store/days theirs)))
        (is (empty? (store/archive theirs)))
        (is (= {} (store/settings theirs)))
        (is (empty? (store/all-notes theirs)))
        (is (empty? (store/digests theirs)))
        (is (empty? (store/open-projections theirs "2026-10-02")))
        (is (empty? (store/source-health theirs)))
        (is (nil? (store/narration theirs "2026-09-30")))
        (is (empty? (store/coverage-between theirs "2026-09-01" "2026-10-30")))
        (is (empty? (store/search theirs "walrus" {}))))
      (testing "and keeps their own day under the same date"
        (store/save-day! theirs {:day "2026-09-30" :sources [] :cited [] :markdown "# Theirs" :model "m" :provider "p"})
        (is (= "# Theirs" (:markdown (store/day theirs "2026-09-30"))))
        (is (= "# Mine\n\nwalrus [1]" (:markdown (store/day mine "2026-09-30"))))
        (is (some? (store/narration mine "2026-09-30")) "a day saved by one reader leaves the other's podcast"))
      (testing "the reader's own search finds their own"
        (is (= #{:day :story} (set (map :kind (store/search mine "walrus" {}))))))
      (testing "pruning one reader's days leaves the other's"
        (store/save-day! theirs {:day "2026-09-29" :sources [] :cited [] :markdown "# Earlier" :model "m" :provider "p"})
        (is (= ["2026-09-29"] (store/prune! theirs 1)))
        (is (some? (store/day mine "2026-09-30"))))
      (finally (store/close db)))))

(def ^:private legacy-schema
  "The tables of a database from before there were readers, as v0.12 made
  them."
  ["create table briefings (day text primary key, markdown text not null, model text, provider text,
      tldr text, created_at text not null)"
   "create table sources (day text not null, n integer not null, title text not null, url text not null,
      source text, summary text, published text, also text, vector text, story text, weight real,
      precedent integer not null default 0, note text, informs text, gap text,
      cited integer not null default 0, primary key (day, n))"
   "create table settings (key text primary key, value text not null)"
   "create table story_notes (story text primary key, title text, summary text, facts text not null,
      first_day text, last_day text not null, updated_at text not null, status text)"
   "create table projections (id integer primary key, origin text not null, ref text not null,
      made_on text not null, claim text not null, subject text not null, story text, source text,
      outlet text, url text, by_whom text, due text not null, status text not null default 'open',
      closeness real, reason text, checked_kind text, checked_period text, checked_at text)"
   "create index projections_due on projections (status, due)"
   "create table search_docs (id integer primary key, kind text not null, ref text not null, day text,
      href text not null, url text, cited integer not null default 0, title text, body text)"
   "insert into briefings values ('2026-09-30', '# Old day\n\nwalrus', 'm', 'p', 'old', '2026-09-30T07:00:00Z')"
   "insert into sources (day, n, title, url, source, summary, cited) values
      ('2026-09-30', 1, 'Old report', 'https://e.com/old', 'Wire', 'walrus', 1)"
   "insert into settings values ('run-at', '\"06:00\"')"
   "insert into projections (origin, ref, made_on, claim, subject, due) values
      ('day', '2026-09-30', '2026-09-30', 'Rates hold', 'economy', '2026-10-30')"])

(deftest a-database-from-before-readers-is-the-local-readers
  (let [f (str (System/getProperty "java.io.tmpdir") "/newsroom-legacy-" (System/currentTimeMillis) ".sqlite3")]
    (try
      (let [conn (jdbc/connection (str "sqlite:" f))]
        (doseq [stmt legacy-schema] (jdbc/execute! conn stmt))
        (.close conn))
      (let [db (store/open f)
            other (store/for-user db (store/ensure-user! db {:issuer "https://auth.example" :subject "new"}))]
        (try
          (is (= "# Old day\n\nwalrus" (:markdown (store/day db "2026-09-30"))))
          (is (= ["Old report"] (map :title (:sources (store/day db "2026-09-30")))))
          (is (= {:run-at "06:00"} (store/settings db)))
          (is (= ["Rates hold"] (map :claim (store/open-projections db "2026-10-01"))))
          (is (seq (store/search db "walrus" {})) "the old day is indexed again")
          (is (nil? (store/day other "2026-09-30")) "a new reader starts with nothing")
          (is (empty? (store/search other "walrus" {})))
          (finally (store/close db))))
      (testing "and opening it again changes nothing"
        (let [db (store/open f)]
          (try
            (is (= ["2026-09-30"] (store/days db)))
            (is (= 1 (count (store/search db "walrus" {:kind :day}))))
            (finally (store/close db)))))
      (finally (io/delete-file f true)))))

(deftest a-session-is-kept-until-it-expires
  (let [db (fresh)
        uid (store/ensure-user! db {:issuer "https://auth.example" :subject "s"})
        now (System/currentTimeMillis)]
    (try
      (store/save-session! db {:id "h1" :user-id uid :id-token "tok" :expires-at (+ now 60000)})
      (store/save-session! db {:id "h2" :user-id uid :expires-at (- now 1)})
      (is (= {:id "h1" :user-id uid :id-token "tok"} (select-keys (store/session db "h1") [:id :user-id :id-token])))
      (is (nil? (store/session db "h2")) "an expired session is no session")
      (is (nil? (store/session db "nope")))
      (store/delete-session! db "h1")
      (is (nil? (store/session db "h1")))
      (finally (store/close db)))))

(deftest what-one-reader-fetched-is-shared-for-a-while
  (let [db (fresh)
        items [{:title "A" :url "https://e.com/a" :source "Wire" :summary "s" :published nil}]
        theirs (store/for-user db (store/ensure-user! db {:issuer "i" :subject "t"}))]
    (try
      (is (nil? (store/shared-fetch db "k" 60000)))
      (store/save-shared-fetch! db "k" "Wire" items)
      (is (= {:name "Wire" :items items} (select-keys (store/shared-fetch theirs "k" 60000) [:name :items]))
          "any reader's store reads it")
      (is (integer? (:fetched-at (store/shared-fetch db "k" 60000))))
      (is (nil? (store/shared-fetch db "k" -1)) "too old to use")
      (testing "articles and embeddings are shared the same way"
        (store/save-shared-article! db "https://e.com/a" "The text")
        (is (= "The text" (store/shared-article theirs "https://e.com/a" 60000)))
        (is (nil? (store/shared-article db "https://e.com/b" 60000)))
        (store/save-shared-vectors! db {"h1" [0.5 0.25]})
        (is (= {"h1" [0.5 0.25]} (update-vals (store/shared-vectors theirs ["h1" "h2"]) vec))))
      (testing "and dropped once old"
        (store/prune-shared! db -1)
        (is (nil? (store/shared-fetch db "k" 60000)))
        (is (nil? (store/shared-article db "https://e.com/a" 60000))))
      (finally (store/close db)))))
