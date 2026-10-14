(ns newsroom.jobs-test
  "What a restart finds: the jobs the desk and the podcast worker record,
  the steps a stored day still needs, and resume!, which picks them up."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [newsroom.narration :as narration]
            [newsroom.pipeline :as pipeline]
            [newsroom.sources :as sources]
            [newsroom.store :as store]
            [newsroom.tts :as tts]
            [newsroom.ui :as ui]))

(defn- wait-for [pred]
  (loop [i 0]
    (when-not (or (pred) (> i 500))
      (Thread/sleep 20)
      (recur (inc i)))))

(defn- idle! [] (wait-for #(not (pipeline/running?))))

(defmacro ^:private keeping-status
  "Run body, putting the local reader's desk and podcast status back after."
  [& body]
  `(let [desk# @pipeline/status
         podcast# @narration/status]
     (try ~@body
          (finally (reset! pipeline/status desk#)
                   (reset! narration/status podcast#)))))

(deftest jobs-are-kept-per-reader
  (let [st (store/open "sqlite::memory:")
        ann (store/for-user st 7)
        id (store/add-job! st {:kind :run :args {:day "2026-09-30"} :origin :hand :state :running
                               :status {:state :gathering :day "2026-09-30"}})]
    (is (= [[:run :running {:day "2026-09-30"}]] (map (juxt :kind :state :args) (store/open-jobs st))))
    (is (empty? (store/open-jobs ann)) "another reader's are their own")
    (store/update-job! st id {:state :done :status {:state :done :day "2026-09-30" :items 3}})
    (is (empty? (store/open-jobs st)))
    (let [job (store/latest-job st [:run :digest])]
      (is (= :done (:state job)))
      (is (= {:state :done :day "2026-09-30" :items 3} (:status job)))
      (is (some? (:finished-at job))))
    (is (nil? (store/latest-job st [:podcast])))
    (testing "only a reader's newest jobs are kept"
      (dotimes [i 105] (store/add-job! ann {:kind :podcast :args {:day (str i)} :origin :hand :state :queued}))
      (is (= 100 (count (store/open-jobs ann))))
      (is (= "5" (:day (:args (first (store/open-jobs ann)))))))))

(deftest a-day-is-stored-with-what-it-still-needs
  (let [st (store/open "sqlite::memory:")
        steps [[:mentions []]
               [:notes {:numbered [{:n 1 :title "A" :url "https://e.com/1" :also [{:source "B"}]}]
                        :cited [1] :noted ["2026-09-29/1"] :max-facts 5 :dossier-facts {"k" ["fact"]}}]
               [:podcast nil]]]
    (store/save-day! st {:day "2026-09-30" :sources [] :cited [] :markdown "# Day" :model "m" :provider "p"
                         :followups steps})
    (is (= [{:kind "day" :ref "2026-09-30" :steps steps}] (store/followups st)) "kept exactly, in order")
    (is (empty? (store/followups (store/for-user st 7))))
    (store/set-followups! st "day" "2026-09-30" (rest steps))
    (is (= [:notes :podcast] (map first (:steps (first (store/followups st))))))
    (store/set-followups! st "day" "2026-09-30" nil)
    (is (empty? (store/followups st)))
    (testing "storing the day again replaces what it needed"
      (store/save-day! st {:day "2026-09-30" :sources [] :cited [] :markdown "# Day" :model "m" :provider "p"
                           :followups steps})
      (store/save-day! st {:day "2026-09-30" :sources [] :cited [] :markdown "# Day" :model "m" :provider "p"})
      (is (empty? (store/followups st))))))

(defmethod sources/fetch-items ::fixture [_ {:keys [day]}]
  [{:title "Story 1" :url "https://e.com/1" :source "Fixture" :summary "About 1"
    :published (str day "T08:00:00Z")}])

(defmethod sources/fetch-items ::slow [_ _]
  (Thread/sleep 5000)
  [])

(defn- ctx [sources]
  {:config {:sources sources :source-timeout-ms 500 :lookback-days 1 :max-items 10 :dupe-threshold nil
            :providers {} :roles {:analyst :local}}
   :store (store/open "sqlite::memory:")
   :template "Brief {{date}}.\n\n{{sources}}"
   :chat (fn [_ _] {:content "# Day\n\nStory one [1]."})})

(deftest a-run-is-recorded-and-leaves-nothing-undone
  (keeping-status
   (let [c (ctx [{:type ::fixture :name "A"}])
         st (:store c)]
     (is (pipeline/start-run! c "2026-09-30"))
     (idle!)
     (is (some? (store/day st "2026-09-30")))
     (let [job (store/latest-job st [:run])]
       (is (= [:done :hand {:day "2026-09-30" :reread? nil :fresh? nil}] [(:state job) (:origin job) (:args job)]))
       (is (= :done (get-in job [:status :state])))
       (is (seq (get-in job [:status :events])) "the run's log is kept with it")
       (is (nil? (get-in job [:status :run])) "but not its token"))
     (is (empty? (store/followups st)) "the steps after filing were done and struck off"))))

(deftest a-stop-interrupts-a-run-and-a-cancel-cancels-it
  (keeping-status
   (testing "the server stopping"
     (let [c (ctx [{:type ::slow :name "Slow"}])]
       (is (pipeline/start-run! c "2026-09-30"))
       (is (pipeline/cancel-run! nil :stopping? true))
       (idle!)
       (is (= :interrupted (:state (store/latest-job (:store c) [:run]))))
       (is (= :interrupted (:state @pipeline/status)))))
   (testing "the reader cancelling"
     (let [c (ctx [{:type ::slow :name "Slow"}])]
       (is (pipeline/start-run! c "2026-09-30"))
       (is (pipeline/cancel-run!))
       (idle!)
       (is (= :cancelled (:state (store/latest-job (:store c) [:run]))))))))

(deftest resume-picks-up-what-a-restart-cut-short
  (keeping-status
   (let [c (ctx [])
         st (:store c)
         queued (atom [])
         trend {:thread "t1" :level "trend" :name "Tariffs" :direction "rising" :summary "Up." :stories ["s1"]}]
     ;; a day stored before its trends were, a run cut short, a podcast
     ;; being made and one waiting
     (store/save-day! st {:day "2026-09-30" :sources [] :cited [] :markdown "# Day" :model "m" :provider "p"
                          :followups [[:mentions []] [:trends [trend]]]})
     (store/add-job! st {:kind :run :args {:day "2026-10-01"} :origin :hand :state :running
                         :status {:state :analysing :day "2026-10-01" :events [{:text "Gathered 3 items"}]}})
     (store/add-job! st {:kind :podcast :args {:day "2026-09-29"} :origin :hand :state :running
                         :status {:state :speaking :day "2026-09-29"}})
     (store/add-job! st {:kind :podcast :args {:day "2026-09-30"} :origin :hand :state :queued})
     (with-redefs [narration/enqueue! (fn [_ day] (swap! queued conj day) true)]
       (pipeline/resume! c)
       (idle!))
     (testing "the steps the day still needed are done"
       (is (empty? (store/followups st)))
       (is (= ["Tariffs"] (map :name (store/trends-between st "2026-09-30" "2026-09-30")))))
     (testing "the podcasts are queued again, oldest first"
       (is (= ["2026-09-29" "2026-09-30"] @queued)))
     (testing "the jobs the restart cut short are marked so"
       (is (empty? (store/open-jobs st)))
       (is (= :interrupted (:state (store/latest-job st [:run])))))
     (testing "the desk says what it finished"
       (is (= :done (:state @pipeline/status)))
       (is (= [{:kind "day" :ref "2026-09-30"}] (:finishing @pipeline/status)))))))

(deftest an-interrupted-run-is-offered-again
  (keeping-status
   (let [c (ctx [])
         st (:store c)]
     (store/add-job! st {:kind :run :args {:day "2026-10-01"} :origin :hand :state :running
                         :status {:state :analysing :day "2026-10-01"}})
     (pipeline/resume! c)
     (is (= :interrupted (:state @pipeline/status)))
     (is (= "2026-10-01" (:day @pipeline/status)) "the run's status is shown as it was")
     (is (not (pipeline/running?)) "and nothing is run unasked")
     (let [page (ui/page st "2026-09-30")]
       (is (str/includes? page "cut short when the server restarted"))
       (is (str/includes? page "@post(&apos;/run?day=2026-10-01&apos;)"))))))

(deftest a-podcast-is-recorded-and-a-stop-leaves-it-for-the-next-start
  (keeping-status
   (let [st (store/open "sqlite::memory:")
         release (promise)]
     (store/save-day! st {:day "2026-09-30" :sources [] :cited [] :markdown "# Day\n\nA [1]." :model "m" :provider "p"})
     (with-redefs [tts/available? (constantly true)]
       (try
         (narration/enqueue! {:config {:podcast true :podcast-segments 2 :providers {} :roles {:analyst :local}
                                       :podcast-hosts [{:name "Hugo" :voice "Hugo" :backstory "An economist." :personality "Dry."}
                                                       {:name "Kiki" :voice "Kiki" :backstory "A reporter." :personality "Quick."}]}
                              :store st
                              :chat (fn [_ _] @release (throw (ex-info "no model here" {})))}
                             "2026-09-30")
         (wait-for #(= :running (:state (store/latest-job st [:podcast]))))
         (is (= :running (:state (store/latest-job st [:podcast]))))
         (narration/stop!)
         (deliver release true)
         (wait-for #(not (narration/busy?)))
         (is (= [:podcast] (map :kind (store/open-jobs st))) "still open, for the next start")
         (finally (narration/start!)))))))
