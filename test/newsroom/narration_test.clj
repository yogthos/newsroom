(ns newsroom.narration-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ebb.core :as m]
            [newsroom.core :as core]
            [newsroom.narration :as narration]
            [newsroom.pipeline :as pipeline]
            [newsroom.sources :as sources]
            [newsroom.store :as store]
            [newsroom.tts :as tts]
            [newsroom.ui :as ui]))

(def ^:private hosts
  [{:name "Hugo" :voice "Hugo" :backstory "An economist." :personality "Dry."}
   {:name "Kiki" :voice "Kiki" :backstory "A reporter." :personality "Quick."}])

(defn- config []
  {:podcast true :podcast-segments 2 :podcast-hosts hosts :providers {} :roles {:analyst :local}})

(def ^:private briefing
  "# Big day\n\nTariffs rose [[1]](https://e.com/1).\n\n## Sources\n\n- [1] [Tariffs](https://e.com/1)")

(defn- fresh-store []
  (let [st (store/open "sqlite::memory:")]
    (store/save-day! st {:day "2026-09-30" :sources [] :cited [] :markdown briefing :model "m" :provider "p"})
    st))

(defn- scripted
  "A model that plans two segments and writes two turns for each, keeping
  the prompts it was given."
  [prompts]
  (fn [_ {:keys [messages]}]
    (let [p (:content (first messages))]
      (swap! prompts conj p)
      {:model "scripted"
       :content (if (str/includes? p "Plan the episode")
                  "{\"segments\": [{\"name\": \"Hello\", \"description\": \"The hook.\", \"size\": \"short\"},
                                   {\"name\": \"Bye\", \"description\": \"The end.\", \"size\": \"short\"}]}"
                  (str "{\"transcript\": [{\"speaker\": \"Hugo\", \"dialogue\": \"About tariffs [1].\"},"
                       " {\"speaker\": \"Kiki\", \"dialogue\": \"[excited] Right!\"}]}"))})))

(defn- settled
  "The narration status once the worker is done with everything queued."
  []
  (loop [i 0]
    (let [st @narration/status]
      (if (or (and (#{:done :failed :cancelled :idle} (:state st)) (not (narration/busy?))) (> i 300))
        st
        (do (Thread/sleep 20) (recur (inc i)))))))

(defn- wait-for [pred]
  (loop [i 0]
    (when-not (or (pred) (> i 300))
      (Thread/sleep 20)
      (recur (inc i)))))

(deftest a-podcast-is-written-recorded-and-stored
  (let [st (fresh-store)
        prompts (atom [])
        spoken (atom nil)
        progress (atom [])
        saved (atom 0)]
    (try
      (with-redefs [tts/synthesize (fn [lines {:keys [on-progress]}]
                                     (reset! spoken lines)
                                     (doseq [i (range (count lines))]
                                       (on-progress (inc i) (count lines))
                                       (swap! progress conj (select-keys @narration/status [:state :done :total])))
                                     {:mp3 (byte-array [73 68 51]) :seconds 42.0})]
        (narration/enqueue! {:config (config) :store st :chat (scripted prompts) :on-saved #(swap! saved inc)}
                            "2026-09-30")
        (let [status (settled)]
          (is (= :done (:state status)) (pr-str status))
          (is (= "2026-09-30" (:day status))))
        (testing "an outline, then the dialogue of each segment in turn"
          (is (= 3 (count @prompts)))
          (is (str/includes? (first @prompts) "Plan the episode as 2 segments."))
          (is (str/includes? (first @prompts) "Tariffs rose."))
          (is (not (str/includes? (first @prompts) "https://e.com/1")) "the briefing without its citations")
          (is (str/includes? (second @prompts) "<segment>\nHello: The hook.\n</segment>"))
          (is (not (str/includes? (second @prompts) "<transcript>")))
          (is (str/includes? (last @prompts) "<transcript>\nHugo: About tariffs.\nKiki: [excited] Right!"))
          (is (str/includes? (last @prompts) "This is the final segment")))
        (testing "the speech engine is given each line in its host's voice"
          (is (= [{:voice "Hugo" :text "About tariffs."} {:voice "Kiki" :text "[excited] Right!"}
                  {:voice "Hugo" :text "About tariffs."} {:voice "Kiki" :text "[excited] Right!"}]
                 @spoken))
          (is (= {:state :speaking :done 2 :total 4} (second @progress)) "and its progress shows"))
        (testing "the episode is stored with its script, and the pages told"
          (let [n (store/narration st "2026-09-30")]
            (is (= [{:speaker "Hugo" :text "About tariffs."} {:speaker "Kiki" :text "[excited] Right!"}]
                   (take 2 (:transcript n))))
            (is (= 42.0 (:seconds n)))
            (is (= "scripted" (:model n)))
            (is (= "local" (:provider n))))
          (is (= [73 68 51] (vec (store/narration-audio st "2026-09-30"))))
          (is (= 1 @saved))))
      (finally (store/close st)))))

(deftest a-podcast-that-cant-be-made-says-why
  (let [st (fresh-store)
        run (fn [cfg chat day]
              (with-redefs [tts/synthesize (fn [_ _] {:mp3 (byte-array [1]) :seconds 1.0})]
                (narration/enqueue! {:config cfg :store st :chat chat} day)
                (settled)))]
    (try
      (is (= {:state :failed :day "2026-09-29" :error "there is no briefing for 29 September 2026"}
             (select-keys (run (config) (scripted (atom [])) "2026-09-29") [:state :day :error])))
      (is (str/includes? (:error (run (assoc (config) :podcast-hosts [{:name "Solo" :voice "Hugo"}])
                                      (scripted (atom [])) "2026-09-30"))
                         "two hosts"))
      (is (str/includes? (:error (run (config) (fn [_ _] {:content "I'd rather not."}) "2026-09-30"))
                         "outline"))
      (is (str/includes? (:error (run (config) (fn [_ _] (throw (ex-info "HTTP 401" {}))) "2026-09-30"))
                         "HTTP 401"))
      (testing "when the speech engine fails"
        (with-redefs [tts/synthesize (fn [_ _] (throw (ex-info "speech isn't available yet" {})))]
          (narration/enqueue! {:config (config) :store st :chat (scripted (atom []))} "2026-09-30")
          (is (= "speech isn't available yet" (:error (settled))))))
      (is (nil? (store/narration st "2026-09-30")) "nothing is stored")
      (finally (store/close st)))))

(deftest one-podcast-at-a-time-and-the-newest-request-waits
  (let [st (fresh-store)
        gate (promise)
        spoken (atom [])]
    (doseq [d ["2026-09-28" "2026-09-29"]]
      (store/save-day! st {:day d :sources [] :cited [] :markdown (str "# " d "\n\nNews.") :model "m" :provider "p"}))
    (try
      (with-redefs [tts/synthesize (fn [lines _]
                                     (swap! spoken conj (:text (first lines)))
                                     @gate
                                     {:mp3 (byte-array [1]) :seconds 1.0})]
        (let [ctx {:config (config) :store st :chat (scripted (atom []))}]
          (narration/enqueue! ctx "2026-09-28")
          (wait-for #(= :speaking (:state @narration/status)))
          (narration/enqueue! ctx "2026-09-29")
          (narration/enqueue! ctx "2026-09-30")
          (is (= "2026-09-28" (:day @narration/status)) "the first goes on")
          (is (= "2026-09-30" (:pending @narration/status)) "the newest request replaces the one waiting")
          (deliver gate true)
          (is (= :done (:state (settled))))
          (is (= "2026-09-30" (:day @narration/status)))
          (is (nil? (:pending @narration/status)))
          (is (some? (store/narration st "2026-09-28")))
          (is (nil? (store/narration st "2026-09-29")) "the one replaced never ran")
          (is (some? (store/narration st "2026-09-30")))
          (is (= 2 (count @spoken)))))
      (finally (store/close st)))))

(deftest a-podcast-can-be-cancelled
  (let [st (fresh-store)]
    (try
      (testing "while it is spoken"
        (with-redefs [tts/synthesize (fn [lines {:keys [cancelled?]}]
                                       (loop []
                                         (when (cancelled?) (throw (ex-info "cancelled" {})))
                                         (Thread/sleep 10)
                                         (recur)))]
          (narration/enqueue! {:config (config) :store st :chat (scripted (atom []))} "2026-09-30")
          (wait-for #(= :speaking (:state @narration/status)))
          (is (narration/cancel!))
          (is (= :cancelled (:state (settled))))))
      (testing "while the model writes"
        (let [started (promise)]
          (narration/enqueue! {:config (config) :store st
                               :chat (fn [_ _] (deliver started true) (Thread/sleep 5000) {:content "{}"})}
                              "2026-09-30")
          @started
          (let [t (System/currentTimeMillis)]
            (narration/cancel!)
            (is (= :cancelled (:state (settled))))
            (is (< (- (System/currentTimeMillis) t) 2000) "without waiting on the model"))))
      (is (nil? (store/narration st "2026-09-30")))
      (is (not (narration/cancel!)) "nothing left to cancel")
      (finally (store/close st)))))

(defmethod sources/fetch-items ::fixture [_ {:keys [day]}]
  [{:title "Story" :url "https://e.com/1" :source "Fixture" :summary "About it" :published (str day "T08:00:00Z")}])

(deftest a-filed-briefing-is-narrated-when-speech-is-there
  (let [st (store/open "sqlite::memory:")
        queued (atom [])
        ctx (fn [podcast? & [auto?]]
              {:config {:sources [{:type ::fixture :name "A"}] :source-timeout-ms 500 :lookback-days 1
                        :max-items-per-source 2 :max-items 10 :dupe-threshold nil
                        :providers {} :roles {:analyst :local} :podcast podcast?
                        :podcast-auto (if (some? auto?) auto? true)}
               :store st
               :template "Brief {{date}}.\n\n{{sources}}"
               :chat (fn [_ _] {:content "# Today\n\nOne [1]."})})]
    (try
      (with-redefs [narration/enqueue! (fn [ctx day] (swap! queued conj [day (fn? (:on-saved ctx))]) true)]
        (with-redefs [tts/available? (constantly true)]
          (m/? (pipeline/run-task (ctx true) "2026-09-30"))
          (is (= [["2026-09-30" true]] @queued) "queued once it's filed, with the pages to tell")
          (reset! queued [])
          (m/? (pipeline/run-task (ctx false) "2026-09-30"))
          (is (= [] @queued) ":podcast turns it off")
          (m/? (pipeline/run-task (ctx true false) "2026-09-30"))
          (is (= [] @queued) ":podcast-auto off leaves it to be recorded from the day's page"))
        (with-redefs [tts/available? (constantly false)]
          (m/? (pipeline/run-task (ctx true) "2026-09-30"))
          (is (= [] @queued) "and without the speech engine there's nothing to do")))
      (finally (store/close st)))))

(deftest a-range-is-read-from-the-header
  (is (nil? (core/byte-range nil 100)))
  (is (= [0 99] (core/byte-range "bytes=0-" 100)))
  (is (= [10 19] (core/byte-range "bytes=10-19" 100)))
  (is (= [10 99] (core/byte-range "bytes=10-500" 100)) "an end past the body is its end")
  (is (= [90 99] (core/byte-range "bytes=-10" 100)))
  (is (= [0 99] (core/byte-range "bytes=-500" 100)))
  (is (= :unsatisfiable (core/byte-range "bytes=100-" 100)))
  (is (= :unsatisfiable (core/byte-range "bytes=-0" 100)))
  (is (= :unsatisfiable (core/byte-range "bytes=0-" 0)))
  (testing "one it can't read, or several ranges, is the whole body"
    (is (nil? (core/byte-range "bytes=20-10" 100)))
    (is (nil? (core/byte-range "items=0-1" 100)))
    (is (nil? (core/byte-range "bytes=0-1,5-6" 100)))))

(deftest the-podcast-is-served-and-recorded-over-http
  (let [st (fresh-store)
        get-mp3 (fn [day range]
                  (core/app (cond-> {:uri (str "/day/" day ".mp3") :request-method :get :headers {}}
                              range (assoc-in [:headers "range"] range))))]
    (reset! core/system {:config (config) :store st :file {}})
    (try
      (is (= 404 (:status (get-mp3 "2026-09-30" nil))) "no podcast yet")
      (store/save-narration! st {:day "2026-09-30" :mp3 (byte-array (range 10)) :transcript [] :seconds 1.0})
      (let [whole (get-mp3 "2026-09-30" nil)]
        (is (= 200 (:status whole)))
        (is (= "audio/mpeg" (get-in whole [:headers "Content-Type"])))
        (is (= "bytes" (get-in whole [:headers "Accept-Ranges"])))
        (is (= (range 10) (vec (:body whole)))))
      (let [part (get-mp3 "2026-09-30" "bytes=2-4")]
        (is (= 206 (:status part)))
        (is (= "bytes 2-4/10" (get-in part [:headers "Content-Range"])))
        (is (= [2 3 4] (vec (:body part)))))
      (is (= [7 8 9] (vec (:body (get-mp3 "2026-09-30" "bytes=-3")))))
      (let [none (get-mp3 "2026-09-30" "bytes=10-")]
        (is (= 416 (:status none)))
        (is (= "bytes */10" (get-in none [:headers "Content-Range"]))))
      (is (= 404 (:status (get-mp3 "2026-02-30" nil))))
      (testing "a podcast can be asked for by day"
        (let [asked (atom nil)]
          (with-redefs [narration/enqueue! (fn [_ day] (reset! asked day) true)]
            (is (= 200 (:status (core/app {:uri "/podcast" :query-string "day=2026-09-30" :request-method :post}))))
            (is (= "2026-09-30" @asked))
            (is (= 400 (:status (core/app {:uri "/podcast" :query-string "day=soon" :request-method :post})))))))
      (finally
        (reset! core/system nil)
        (store/close st)))))

(deftest the-days-page-plays-the-podcast
  (let [st (fresh-store)
        article #(ui/page st "2026-09-30")]
    (try
      (testing "with none, a button records one when speech is there"
        (with-redefs [tts/available? (constantly true)]
          (is (str/includes? (article) "@post(&apos;/podcast?day=2026-09-30&apos;)"))
          (is (str/includes? (article) "Record a podcast of this briefing")))
        (with-redefs [tts/available? (constantly false)]
          (is (not (str/includes? (article) "Record a podcast")))))
      (testing "while it's made, the article says so and the desk shows how far it has got"
        (reset! narration/status {:state :speaking :day "2026-09-30" :done 3 :total 40})
        (let [page (article)]
          (is (str/includes? page "The podcast of this briefing is being recorded"))
          (is (str/includes? page "Recording the podcast of 30 September 2026: 3 of 40 lines"))
          (is (not (str/includes? page "Record a podcast"))))
        (reset! narration/status {:state :writing :day "2026-09-30" :done 0 :total 5})
        (is (str/includes? (article) "The podcast of this briefing is being written"))
        (is (str/includes? (article) "Writing the podcast of 30 September 2026, segment 1 of 5")))
      (testing "once it's stored, a player with the transcript"
        (reset! narration/status {:state :done :day "2026-09-30"})
        (store/save-narration! st {:day "2026-09-30" :mp3 (byte-array [1]) :seconds 754.0
                                   :transcript [{:speaker "Hugo" :text "Hello <laugh> there."}
                                                {:speaker "Kiki" :text "Hi."}]})
        (let [page (article)
              created (:created-at (store/narration st "2026-09-30"))]
          (is (re-find #"<audio [^>]*controls" page))
          (is (re-find #"<audio [^>]*id=\"podcast-player\"" page))
          (is (re-find #"<audio [^>]*preload=\"none\"" page))
          (is (str/includes? page (str "src=\"/day/2026-09-30.mp3?v="
                                       (java.net.URLEncoder/encode created "UTF-8") "\"")))
          (is (str/includes? page "12:34"))
          (is (str/includes? page "<strong>Hugo</strong> Hello there.") "read without the engine's tags")
          (is (str/includes? page "Recorded the podcast of"))
          (is (not (str/includes? page "Record a podcast of this briefing")))))
      (finally
        (reset! narration/status {:state :idle})
        (store/close st)))))
