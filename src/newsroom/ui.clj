(ns newsroom.ui
  "The pages. A day is a wiki-style article: its briefing, the sources it
  cites, and everything gathered for it. A digest is the same for a week or
  a month. The sidebar walks the history and the digests, jumps to a date,
  and shows the run in progress.

  `fragment` is what the SSE stream re-renders: it reads the pipeline's
  ratoms, so a page updates as a run progresses and when a day is stored."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [newsroom.config-page :as config-page]
            [jolt.datastar.core :as ds]
            [newsroom.markdown :as md]
            [newsroom.news :as news]
            [newsroom.pipeline :as pipeline]
            [newsroom.sources :as sources]
            [newsroom.store :as store]
            [newsroom.trends :as trends]))

;; --- sidebar -----------------------------------------------------------------------

(def ^:private asset-version
  (memoize
   (fn [path]
     (some-> (io/resource (str "public" path)) slurp hash (Integer/toHexString)))))

(defn asset
  "The address of a file under resources/public, with a version from its
  content, so a browser that cached the last one is sent the new one."
  [path]
  (if-let [v (asset-version path)] (str path "?v=" v) path))

(defn- month-of [{:keys [day]}] (subs day 0 7))

(defn- month-label [ym]
  (let [[_ date] (str/split (sources/long-date (str ym "-01")) #" " 2)]
    date))

(defn digest-href [kind period] (str "/" (name kind) "/" period))

(defn- digest-title
  "The week of 28 September 2026, as a title."
  [kind period]
  (let [label (pipeline/period-label kind period)]
    (str (str/upper-case (subs label 0 1)) (subs label 1))))

(def ^:private listed
  "How many of each kind of digest the sidebar lists, latest first; the
  stepper on a digest's page walks back past them."
  {:month 12 :week 8})

(defn- digest-label
  "A digest's name in the sidebar, under its kind's heading."
  [kind period]
  (let [[from _] (trends/period-range kind period)]
    (case kind
      :week (str "Week of " (sources/long-date from))
      :month (pipeline/period-label kind period))))

(defn- digest-list
  "The latest digests of `kind`, under their own heading."
  [kind heading digests current]
  (when-let [ds (seq (take (listed kind)
                           (sort-by :period #(compare %2 %1) (filter #(= kind (:kind %)) digests))))]
    [:nav.history.digests
     [:h2 heading]
     [:ul
      (for [{:keys [period tldr]} ds]
        [:li [:a {:href (digest-href kind period)
                  :class (when (= current {:kind kind :period period}) "current")}
              (digest-label kind period)]
         (when-not (str/blank? tldr) [:p.tldr tldr])])]]))

(defn- history [days current]
  [:nav.history
   [:h2 "Daily"]
   (if (empty? days)
     [:p.muted "No briefings yet."]
     (for [ds (partition-by month-of days)
           :let [ym (month-of (first ds))]]
       [:section
        [:h3 (month-label ym)]
        [:ul
         (for [{:keys [day tldr]} ds]
           [:li [:a {:href (str "/day/" day) :class (when (= day (:day current)) "current")}
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

(defn- digest-panel
  "What the desk says about a digest being written or just written."
  [{:keys [state error items cited provider model nothing]} {:keys [kind period]}]
  (let [label (pipeline/period-label kind period)]
    (case (if nothing :nothing state)
      :nothing [:p.muted "Nothing to digest for " label ": " nothing "."]
      :starting [:p "Reading the briefings for " label "…"]
      :analysing [:p "Writing the digest for " label " from " items " storylines with " provider
                  (when model [:span.muted " (" model ")"]) "…"]
      :done [:p "Filed the digest for " [:a {:href (digest-href kind period)} label]
             ": " items " sources, " cited " cited."]
      :failed [:p.bad "The digest for " label " failed: " error]
      :cancelled [:p.muted "The digest for " label " was cancelled."]
      [:p.muted "Idle."])))

(defn- run-panel [{:keys [state day error items cited provider model sources events digest] :as st} today]
  (let [busy? (contains? #{:starting :gathering :sorting :preparing :analysing :researching} state)]
    [:section.run
     [:h2 "Desk"]
     (if digest
       (digest-panel st digest)
       (case state
       :starting [:p "Starting the run for " (sources/long-date day) "…"]
       :gathering [:p "Gathering the news for " (sources/long-date day) "…"]
       :sorting [:p "Sorting " items " reports into stories for " (sources/long-date day) "…"]
       :preparing [:p "Reading and writing dossiers on " items " stories for " (sources/long-date day) "…"]
       :researching [:p "Looking for precedents to " items " stories for " (sources/long-date day) "…"]
       :analysing [:p "Analysing " items " items with " provider
                   (when model [:span.muted " (" model ")"]) "…"]
       :done [:p "Filed " [:a {:href (str "/day/" day)} (sources/long-date day)]
              ": " items " sources, " cited " cited."]
       :failed [:p.bad "The run for " (sources/long-date day) " failed: " error]
       :cancelled [:p.muted "The run for " (sources/long-date day) " was cancelled."]
       [:p.muted "Idle."]))
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

(defn story-href [story] (str "/story/" story))

(defn- story-list
  "The storylines with the latest notes, for the sidebar."
  [stories current]
  (when (seq stories)
    [:nav.history.stories
     [:h2 "Storylines"]
     [:ul
      (for [{:keys [story title]} stories]
        [:li [:a {:href (story-href story) :class (when (= story (:story current)) "current")} title]])]
     [:p [:a {:href "/stories" :class (when (:stories current) "current")} "Every storyline →"]]]))

(defn- stepper
  "Links to the day or the digest of the same kind before and after
  `current`; nothing on a page that is neither."
  [archive digests {:keys [day kind period]}]
  (when (or day kind)
    (let [[prev next href] (if day
                             (conj (news/adjacent-days (map :day archive) day) #(str "/day/" %))
                             (conj (news/adjacent-days (map :period (filter #(= kind (:kind %)) digests)) period)
                                   #(digest-href kind %)))]
      [:div.stepper
       (if prev [:a {:href (href prev)} "← Earlier"] [:span.muted "← Earlier"])
       (if next [:a {:href (href next)} "Later →"] [:span.muted "Later →"])])))

(defn- sidebar
  "What goes inside the sidebar. `current` is the page's {:day}, {:kind
  :period}, {:story} or {:stories true}."
  [archive digests stories current health]
  (list
   [:header.masthead
    [:a {:href "/"} [:h1 "The Newsroom"]]
    [:p.tagline "Daily briefing & analysis"]]
   [:p.config-link [:a {:href "/config" :class (when (:config current) "current")} "Config"]]
   [:label.jump
    [:span "Go to date"]
    [:input {:type "date" :value (:day current)
             "data-on:change" "evt.target.value && (window.location = '/day/' + evt.target.value)"}]]
   (stepper archive digests current)
   (run-panel @pipeline/status (pipeline/today))
   (feed-health health)
   (story-list stories current)
   (digest-list :month "Monthly" digests current)
   (digest-list :week "Weekly" digests current)
   (history archive current)))

;; --- the day -----------------------------------------------------------------------

(defn- gathered [sources noted]
  [:details.gathered
   [:summary "Everything gathered for the day (" (count sources) ")"]
   [:ol
    (for [{:keys [n title url source summary also story informs note gap]} sources]
      [:li {:id (str "source-" n) :value n}
       [:a {:href url :rel "noopener" :target "_blank"} title]
       (when-not (str/blank? source) [:span.muted " — " source])
       (when (seq informs)
         [:span.muted " · precedent for "
          (interpose ", " (for [i informs] [:a {:href (str "#source-" i)} (str "[" i "]")]))])
       (when-not (str/blank? note) [:p.summary.note [:strong "What it teaches: "] note])
       (when-not (str/blank? gap) [:p.summary.note [:strong "Found for what the reporting left out: "] gap])
       (when-let [note (get noted story)]
         [:span.muted " · storyline: " [:a {:href (story-href story)} (:title note)]])
       (when (seq also)
         [:span.muted " · also "
          (interpose ", " (for [{:keys [source url]} also]
                            (if url [:a {:href url :rel "noopener" :target "_blank"} source] source)))])
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
     (gathered sources (store/notes st (keep :story sources)))]
    [:article.briefing.empty
     [:p.dateline (sources/long-date day)]
     [:h1 "No briefing for this day"]
     (if (= day (pipeline/today))
       [:p "Today’s news hasn’t been gathered yet. Use the button in the sidebar to gather and analyse it now."]
       [:p "Nothing was gathered on this day."])]))

(defn- digest-article [st kind period]
  (if-let [{:keys [markdown sources model provider created-at]} (store/digest st kind period)]
    [:article.briefing
     [:p.dateline (digest-title kind period)]
     [:div.prose (h/raw (md/html markdown))]
     [:footer.meta
      "Written by " provider (when model (str " / " model))
      (when created-at (str " at " (subs created-at 0 (min 16 (count created-at)))))
      " · " [:a {:href (str (digest-href kind period) ".md")} "markdown"]]
     [:details.gathered
      [:summary "Every source the digest was given (" (count sources) ")"]
      [:ol
       (for [{:keys [n day title url source]} sources]
         [:li {:id (str "source-" n) :value n}
          [:a {:href url :rel "noopener" :target "_blank"} title]
          [:span.muted " — " (when-not (str/blank? source) (str source ", ")) (sources/long-date day)]])]]]
    (let [[from to] (trends/period-range kind period)
          ended? (neg? (compare to (pipeline/today)))]
      [:article.briefing.empty
       [:p.dateline (digest-title kind period)]
       [:h1 "No digest for this " (name kind)]
       (cond
         (not ended?) [:p "The " (name kind) " isn’t over yet. Its digest is written once it is."]
         (empty? (store/coverage-between st from to)) [:p "There are no briefings for this " (name kind) "."]
         :else (list [:p "The digest is written from the briefings of " (pipeline/period-label kind period) "."]
                     [:button {"data-on:click" (str "@post('/digest?kind=" (name kind) "&period=" period "')")}
                      "Write this digest"]))])))

;; --- storylines --------------------------------------------------------------------

(defn- span-of
  "When a note's storyline ran: 3 September 2026, or from one day to another."
  [{:keys [first-day last-day]}]
  (if (or (nil? first-day) (= first-day last-day))
    (sources/long-date last-day)
    (str (sources/long-date first-day) " to " (sources/long-date last-day))))

(defn- stories-article [st]
  (let [all (store/all-notes st)]
    [:article.briefing.storylines
     [:p.dateline "Storylines"]
     [:h1 "The stories the briefings follow"]
     (if (empty? all)
       [:p.muted "No storylines yet. A note is kept on every story a briefing cites, from the next run on."]
       (list
        [:p.muted "Each one keeps a running note: where it stands and the facts that moved it, "
         "compacted from the reports after every briefing. The latest updated come first."]
        [:ul.story-index
         (for [{:keys [story title summary facts] :as note} all]
           [:li
            [:h3 [:a {:href (story-href story)} title]]
            [:p.muted (span-of note) " · " (count facts) (if (= 1 (count facts)) " fact" " facts")]
            (when-not (str/blank? summary) [:p summary])])]))]))

(defn- coverage-chart
  "A storyline's coverage, a bar a day as wide as the outlets carrying it."
  [coverage briefed]
  (let [top (reduce max 1 (map :outlets coverage))]
    [:ol.coverage
     (for [{:keys [day outlets cited?]} coverage]
       [:li
        [:span.day (if (contains? briefed day)
                     [:a {:href (str "/day/" day)} (sources/long-date day)]
                     (sources/long-date day))]
        [:span.bar [:span {:style (str "width:" (long (* 100 (/ outlets top))) "%")}]]
        [:span.count outlets (if (= 1 outlets) " outlet" " outlets") (when cited? " · cited")]])]))

(defn- story-article [st story]
  (if-let [{:keys [title summary facts] :as note} (get (store/notes st [story]) story)]
    (let [coverage (store/story-coverage st story)]
      [:article.briefing.storyline
       [:p.dateline "Storyline · " (span-of note)]
       [:h1 title]
       (when-not (str/blank? summary) [:p.standfirst summary])
       [:h2 "How it went"]
       [:ol.facts
        (for [{:keys [day text url source headline]} facts]
          [:li
           [:span.day (sources/long-date day)]
           [:p text
            (when url
              [:span.muted " — " [:a {:href url :rel "noopener" :target "_blank"} (or headline url)]
               (when-not (str/blank? source) (str ", " source))])]])]
       (when (seq coverage)
         (list [:h2 "Coverage"]
               [:p.muted "The outlets carrying it each day it was in the news."]
               (coverage-chart coverage (set (store/days st)))))])
    [:article.briefing.empty
     [:p.dateline "Storyline"]
     [:h1 "No notes on this storyline"]
     [:p "It may never have been cited by a briefing, or its notes were dropped with its days. "
      [:a {:href "/stories"} "Every storyline"]]]))

(defn fragment
  "The content of one live part of a page, by the selector its stream
  patches: \"#sidebar\" or \"#article\". `current` is the page's {:day},
  {:kind :period} for a digest's, {:story} for a storyline's, or {:stories
  true} for the list of them.

  The two stream apart because each re-renders only when a ratom it read
  changes. The sidebar reads the run's status, which changes several times a
  second during a run; the article reads only `stored`, so it, and the
  diagram in it, stays still until a day is written."
  [st current selector]
  ;; stored changes when a day is written, so both re-read the store
  @pipeline/stored
  (let [current (if (string? current) {:day current} current)]
    (cond
      (not= "#article" selector) (sidebar (store/archive st) (store/digests st) (store/recent-stories st 6)
                                          current (store/source-health st))
      (:day current) (article st (:day current))
      (:story current) (story-article st (:story current))
      (:stories current) (stories-article st)
      :else (digest-article st (:kind current) (:period current)))))

(defn page
  "The whole document for a day, or for any other `current` fragment takes,
  or for the config page, {:config {:tree :errors ...}} (see
  newsroom.config-page). The config page's article isn't live, since
  re-drawing it would lose what is being typed into it."
  [st current]
  (let [current (if (string? current) {:day current} current)
        title (cond
                (:config current) "Config"
                (:day current) (sources/long-date (:day current))
                (:story current) (or (:title (get (store/notes st [(:story current)]) (:story current)))
                                     "Storyline")
                (:stories current) "Storylines"
                :else (digest-title (:kind current) (:period current)))]
    (str "<!DOCTYPE html>"
         (h/html
          [:html {:lang "en"}
           [:head
            [:meta {:charset "utf-8"}]
            [:meta {:name "viewport" :content "width=device-width,initial-scale=1"}]
            [:title (str title " · The Newsroom")]
            [:script {:type "module" :src (asset "/js/datastar.js")}]
            [:link {:rel "stylesheet" :href (asset "/css/style.css")}]]
           [:body
            [:div.layout
             [:aside#sidebar.sidebar (ds/init-opts {:selector "#sidebar"})
              (fragment st current "#sidebar")]
             (if-let [cfg (:config current)]
              [:main#article (config-page/article cfg)]
              [:main#article (dissoc (ds/init-opts {:selector "#article"}) :data-signals)
               (fragment st current "#article")])]
            [:script {:type "module" :src (asset (if (:config current) "/js/config.js" "/js/diagrams.js"))}]]]))))
