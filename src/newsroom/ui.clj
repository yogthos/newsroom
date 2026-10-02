(ns newsroom.ui
  "The pages. A day is a wiki-style article: its briefing, the sources it
  cites, and everything gathered for it. The sidebar walks the history and
  jumps to a date, and shows the run in progress.

  `fragment` is what the SSE stream re-renders: it reads the pipeline's
  ratoms, so a page updates as a run progresses and when a day is stored."
  (:require [clojure.string :as str]
            [hiccup2.core :as h]
            [jolt.datastar.core :as ds]
            [newsroom.markdown :as md]
            [newsroom.news :as news]
            [newsroom.pipeline :as pipeline]
            [newsroom.sources :as sources]
            [newsroom.store :as store]))

;; --- sidebar -----------------------------------------------------------------------

(defn- month-of [{:keys [day]}] (subs day 0 7))

(defn- month-label [ym]
  (let [[_ date] (str/split (sources/long-date (str ym "-01")) #" " 2)]
    date))

(defn- history [days current]
  [:nav.history
   [:h2 "Archive"]
   (if (empty? days)
     [:p.muted "No briefings yet."]
     (for [ds (partition-by month-of days)
           :let [ym (month-of (first ds))]]
       [:section
        [:h3 (month-label ym)]
        [:ul
         (for [{:keys [day tldr]} ds]
           [:li [:a {:href (str "/day/" day) :class (when (= day current) "current")}
                 (sources/long-date day)]
            (when-not (str/blank? tldr)
              [:p.tldr tldr])])]]))])

(defn- source-state [{:keys [state count error]}]
  (case state
    :ok [:span.ok (str count " items")]
    :failed [:span.bad {:title error} "failed"]
    [:span.muted "…"]))

(defn- clock [ms]
  (let [t (.toLocalTime (.atZone (java.time.Instant/ofEpochMilli ms) (java.time.ZoneId/systemDefault)))]
    (format "%02d:%02d:%02d" (.getHour t) (.getMinute t) (.getSecond t))))

(defn- wire
  "The run's events, newest first."
  [events]
  (when (seq events)
    [:ol.wire
     (for [{:keys [at text url level]} (take 14 (reverse events))]
       [:li {:class (some-> level name)}
        [:time (clock at)]
        (if url [:a {:href url :target "_blank" :rel "noopener"} text] [:span text])])]))

(defn- writing [{:keys [words reasoning-words section tail]}]
  [:div.writing
   (if (pos? (or words 0))
     [:p [:strong words " words"] " written"
      (when section [:span " · " [:em section]])]
     [:p [:strong "Thinking"] (when (pos? (or reasoning-words 0))
                                (str " · " reasoning-words " words of reasoning"))])
   (when-not (str/blank? tail) [:blockquote tail])])

(defn- run-panel [{:keys [state day error items cited provider model sources events] :as st} today]
  (let [busy? (contains? #{:starting :gathering :analysing} state)]
    [:section.run
     [:h2 "Desk"]
     (case state
       :starting [:p "Starting the run for " (sources/long-date day) "…"]
       :gathering [:p "Gathering the news for " (sources/long-date day) "…"]
       :analysing [:p "Analysing " items " items with " provider
                   (when model [:span.muted " (" model ")"]) "…"]
       :done [:p "Filed " [:a {:href (str "/day/" day)} (sources/long-date day)]
              ": " items " sources, " cited " cited."]
       :failed [:p.bad "The run for " (sources/long-date day) " failed: " error]
       :cancelled [:p.muted "The run for " (sources/long-date day) " was cancelled."]
       [:p.muted "Idle."])
     (when (and (= :analysing state) (:writing st))
       (writing (:writing st)))
     (when (and busy? (seq sources))
       [:ul.sources-progress
        (for [[name s] (sort-by key sources)]
          [:li [:span name] (source-state s)])])
     (wire events)
     (if busy?
       [:button {"data-on:click" "@post('/cancel')"} "Cancel run"]
       [:button {"data-on:click" (str "@post('/run?day=" today "')")}
        "Gather today’s news"])]))

(defn- feed-health
  "The run desk's long view of which feeds have been failing: each source's
  last success and how many times in a row it has failed since."
  [health]
  (when (seq health)
    [:details.health
     [:summary "Feed health (" (count health) ")"]
     [:ul
      (for [{:keys [name last-ok consecutive-failures last-error]} health]
        [:li
         (if (zero? consecutive-failures)
           [:span.ok name]
           [:span.bad {:title last-error}
            name " · " consecutive-failures " failed in a row"])
         (when last-ok
           [:span.muted " · last ok " (subs last-ok 0 (min 10 (count last-ok)))])])]]))

(defn- sidebar
  "What goes inside the sidebar."
  [archive current health]
  (list
   [:header.masthead
    [:a {:href "/"} [:h1 "The Newsroom"]]
    [:p.tagline "Daily briefing & analysis"]]
   [:label.jump
    [:span "Go to date"]
    [:input {:type "date" :value current
             "data-on:change" "evt.target.value && (window.location = '/day/' + evt.target.value)"}]]
   (let [[prev next] (news/adjacent-days (map :day archive) current)]
     [:div.stepper
      (if prev [:a {:href (str "/day/" prev)} "← Earlier"] [:span.muted "← Earlier"])
      (if next [:a {:href (str "/day/" next)} "Later →"] [:span.muted "Later →"])])
   (run-panel @pipeline/status (pipeline/today))
   (feed-health health)
   (history archive current)))

;; --- the day -----------------------------------------------------------------------

(defn- gathered [sources]
  [:details.gathered
   [:summary "Everything gathered for the day (" (count sources) ")"]
   [:ol
    (for [{:keys [n title url source summary also]} sources]
      [:li {:id (str "source-" n) :value n}
       [:a {:href url :rel "noopener" :target "_blank"} title]
       (when-not (str/blank? source) [:span.muted " — " source])
       (when (seq also) [:span.muted " · also " (str/join ", " also)])
       (when-not (str/blank? summary) [:p.summary summary])])]])

(defn- article [st day]
  (if-let [{:keys [markdown sources model provider created-at]} (store/day st day)]
    [:article.briefing
     [:p.dateline (sources/long-date day)]
     [:div.prose (h/raw (md/html markdown))]
     [:footer.meta
      "Written by " provider (when model (str " / " model))
      (when created-at (str " at " (subs created-at 0 (min 16 (count created-at)))))
      " · " [:a {:href (str "/day/" day ".md")} "markdown"]]
     (gathered sources)]
    [:article.briefing.empty
     [:p.dateline (sources/long-date day)]
     [:h1 "No briefing for this day"]
     (if (= day (pipeline/today))
       [:p "Today’s news hasn’t been gathered yet. Use the button in the sidebar to gather and analyse it now."]
       [:p "Nothing was gathered on this day."])]))

(defn fragment
  "The content of one live part of a day's page, by the selector its stream
  patches: \"#sidebar\" or \"#article\".

  The two stream apart because each re-renders only when a ratom it read
  changes. The sidebar reads the run's status, which changes several times a
  second during a run; the article reads only `stored`, so it, and the
  diagram in it, stays still until a day is written."
  [st day selector]
  ;; stored changes when a day is written, so both re-read the store
  @pipeline/stored
  (if (= "#article" selector)
    (article st day)
    (sidebar (store/archive st) day (store/source-health st))))

(defn page
  "The whole document for `day`."
  [st day]
  (str "<!DOCTYPE html>"
       (h/html
        [:html {:lang "en"}
         [:head
          [:meta {:charset "utf-8"}]
          [:meta {:name "viewport" :content "width=device-width,initial-scale=1"}]
          [:title (str (sources/long-date day) " · The Newsroom")]
          [:script {:type "module" :src "/js/datastar.js"}]
          [:link {:rel "stylesheet" :href "/css/style.css"}]]
         [:body
          [:div.layout
           [:aside#sidebar.sidebar (ds/init-opts {:selector "#sidebar"})
            (fragment st day "#sidebar")]
           [:main#article (dissoc (ds/init-opts {:selector "#article"}) :data-signals)
            (fragment st day "#article")]]
          [:script {:type "module" :src "/js/diagrams.js"}]]])))
