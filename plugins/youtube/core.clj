(ns youtube.core
  "YouTube channels as a source: each channel's latest videos from the
  last few days, read for what is said in them. Each item is a video, its
  summary the description and the opening of the transcript, and its
  transcript handed to the desk as the video's full text, so a dossier on
  its story is written from what was said rather than from the
  description.

  Copy this folder to plugins/youtube in the config directory, and add a
  source of type youtube on the config page. As data, a source is

    {:type :youtube :channels [\"@veritasium\" \"UCHnyfMqiRRG1u-2MsSQLbXA\"]}

  A channel is a handle, a channel ID, or the address of the channel's
  page. A handle is looked up on the channel's page once a run of the
  server; an ID saves that, and is the way around a page that can't be
  read, as behind a consent screen.

  The latest :videos of each channel (1 by default) published in the last
  :lookback-days (3) are read, leaving out Shorts unless :shorts is true.
  Days with no new video add nothing. Captions are taken in the first of
  :languages a video has them in, those written by people over those made
  by YouTube, and else in whatever language there is. :transcript-chars
  (12000, about a quarter hour of speech) caps how much of a transcript
  the analysis reads. A video with no captions is still an item, from its
  description.

  YouTube's channel feeds fail for many channels; a channel whose feed
  can't be read is read from its videos tab instead, which has no Shorts.

  Nothing is needed in the plugin's settings. Transcripts are read through
  the API YouTube's apps use, which asks which app is calling; when
  YouTube stops answering one, a newer one can be set there:

    :plugins {:youtube {:client-name \"ANDROID\" :client-version \"20.10.38\"}}"
  (:require [clojure.string :as str]
            [newsroom.plugin :as plugin]))

;; --- channels ------------------------------------------------------------------------

(def ^:private channel-id-re #"UC[A-Za-z0-9_-]{22}")

(defonce ^:private resolved
  (atom {}))

(defn channel-page
  "The address of the page a channel named by a handle or a page address
  is looked up on."
  [channel]
  (let [c (str/trim channel)]
    (cond
      (re-find #"^https?://" c) c
      (str/starts-with? c "@") (str "https://www.youtube.com/" c)
      :else (str "https://www.youtube.com/@" c))))

(defn page-channel-id
  "The channel ID a channel's page says it is, or nil."
  [html]
  (some (fn [re] (some-> (re-find re html) second))
        [#"<link rel=\"canonical\" href=\"https://www\.youtube\.com/channel/(UC[A-Za-z0-9_-]{22})\""
         #"\"externalId\":\"(UC[A-Za-z0-9_-]{22})\""
         #"\"channelId\":\"(UC[A-Za-z0-9_-]{22})\""]))

(defn- channel-id
  "The ID of `channel`: itself, the one in its address, or the one its page
  gives, looked up once."
  [channel opts]
  (let [c (str/trim channel)]
    (or (re-matches channel-id-re c)
        (some->> (re-find #"/channel/(UC[A-Za-z0-9_-]{22})" c) second)
        (get @resolved c)
        (let [id (page-channel-id (plugin/fetch-text (channel-page c) opts))]
          (when-not id
            (throw (ex-info (str "couldn't find the channel ID on " (channel-page c)
                                 "; give the channel by its ID, UC...")
                            {:channel c})))
          (swap! resolved assoc c id)
          id))))

;; --- the feed ------------------------------------------------------------------------

(defn- tag [el] (some-> (:tag el) name (str/replace #"^.*:" "")))

(defn- first-tag [el tag-name]
  (some #(when (and (map? %) (= tag-name (tag %))) %) (tree-seq map? :content el)))

(defn- text-of [el tag-name]
  (some->> (first-tag el tag-name) :content (filter string?) (apply str) str/trim not-empty))

(defn videos
  "The videos in a parsed channel feed, newest first, each {:id :title
  :url :published :description :channel :short?}."
  [doc]
  (let [channel (some-> (first (filter #(= "author" (tag %)) (:content doc))) (text-of "name"))]
    (vec (for [e (:content doc)
               :when (= "entry" (tag e))
               :let [id (text-of e "videoId")]
               :when id]
           {:id id
            :title (text-of e "title")
            :url (str "https://www.youtube.com/watch?v=" id)
            :published (text-of e "published")
            :description (text-of e "description")
            :channel (or (some-> (first-tag e "author") (text-of "name")) channel)
            :short? (boolean (some-> (first-tag e "link") :attrs :href (str/includes? "/shorts/")))}))))

(defn recent-videos
  "The latest `n` of `videos` published from day `from` on, Shorts left
  out unless `shorts?`."
  [videos from n shorts?]
  (->> videos
       (filter #(and (:published %) (<= 0 (compare (subs (:published %) 0 (min 10 (count (:published %)))) from))))
       (remove #(and (:short? %) (not shorts?)))
       (take n)))

;; --- the videos tab, when the feed fails -------------------------------------------
;; YouTube's feeds answer 404 or 500 for many channels, so a channel whose feed
;; can't be read is read from its videos tab instead: the video IDs from the
;; browse API, then each video's date from the player API, newest first.

(def ^:private web-client {:clientName "WEB" :clientVersion "2.20250101.00.00" :hl "en"})

;; the videos tab of a channel, as the browse API is asked for it
(def ^:private videos-tab "EgZ2aWRlb3PyBgQKAjoA")

(defn tab-video-ids
  "The IDs of the videos in a browse API answer, in the order given."
  [browse]
  (->> (tree-seq coll? #(if (map? %) (vals %) %) browse)
       (keep (fn [x]
               (when (map? x)
                 (or (some-> (:videoRenderer x) :videoId)
                     (let [l (:lockupViewModel x)]
                       (when (= "LOCKUP_CONTENT_TYPE_VIDEO" (:contentType l)) (:contentId l)))))))
       distinct))

(defn player-video
  "A video as the player API describes it, as videos gives one, or nil
  for a premiere or stream still to come."
  [player]
  (let [d (:videoDetails player)
        id (:videoId d)]
    (when (and id (not (:isUpcoming d)))
      {:id id
       :title (:title d)
       :url (str "https://www.youtube.com/watch?v=" id)
       :published (get-in player [:microformat :playerMicroformatRenderer :publishDate])
       :description (:shortDescription d)
       :channel (:author d)
       :short? false})))

(defn- tab-videos
  "The latest `n` videos of channel `id` published from day `from` on,
  read from its videos tab, which leaves Shorts out. The tab is newest
  first, so the reading stops at the first older video, and after a few
  more than `n`."
  [id from n opts]
  (let [api #(plugin/post-json (str "https://www.youtube.com/youtubei/v1/" % "?prettyPrint=false")
                               (assoc %2 :context {:client web-client})
                               (select-keys opts [:timeout-ms]))]
    (loop [[v & more] (take (+ n 3) (tab-video-ids (api "browse" {:browseId id :params videos-tab})))
           found []]
      (if (or (nil? v) (= n (count found)))
        found
        (let [video (player-video (api "player" {:videoId v}))]
          (cond
            (nil? video) (recur more found)
            (empty? (recent-videos [video] from 1 true)) found
            :else (recur more (conj found video))))))))

;; --- transcripts ---------------------------------------------------------------------

(def ^:private default-client {:clientName "ANDROID" :clientVersion "20.10.38"})

(defn- lang-of [track] (first (str/split (str (:languageCode track)) #"-")))

(defn pick-track
  "The caption track to read from `tracks`: the first of `languages` a
  video has, written by people before made by YouTube (kind asr), else the
  first track there is."
  [tracks languages]
  (let [asr? #(= "asr" (:kind %))]
    (or (first (for [l languages
                     want [false true]
                     t tracks
                     :when (and (= l (lang-of t)) (= want (asr? t)))]
                 t))
        (first (remove asr? tracks))
        (first tracks))))

(defn transcript-text
  "The text of a timed-text document, its lines joined. YouTube escapes
  the lines' entities twice, so they're decoded twice."
  [xml]
  (->> (re-seq #"(?s)<(?:text|p)\b[^>]*>(.*?)</(?:text|p)>" (str xml))
       (map (comp plugin/plain-text plugin/plain-text second))
       (remove str/blank?)
       (str/join " ")))

(defn- clip [s n]
  (if (<= (count s) n)
    s
    (let [cut (subs s 0 n)
          space (str/last-index-of cut " ")]
      (str (if (and space (pos? space)) (subs cut 0 space) cut) " …"))))

(defn- transcript
  "The transcript of video `id` in one of `languages`, or nil when it has
  no captions."
  [id languages client opts]
  (let [player (plugin/post-json "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
                                 {:context {:client client} :videoId id}
                                 (select-keys opts [:timeout-ms]))
        tracks (get-in player [:captions :playerCaptionsTracklistRenderer :captionTracks])]
    (when-let [track (pick-track tracks languages)]
      (not-empty (transcript-text (plugin/fetch-text (str/replace (:baseUrl track) #"&fmt=[^&]*" "") opts))))))

;; --- the source ----------------------------------------------------------------------

(defn- description-lead
  "The first paragraph of a description, without its links and hashtags."
  [description]
  (some->> (str/split (str description) #"\n\s*\n")
           (map #(-> % (str/replace #"https?://\S+" "") (str/replace #"(^|\s)#\S+" " ") str/trim))
           (remove str/blank?)
           first))

(defn- video-item
  "A video as an item, with its transcript when it can be read."
  [source ctx {:keys [languages client transcript-chars opts]} video]
  (let [said (try
               (transcript (:id video) languages client opts)
               (catch Exception e
                 (when (instance? InterruptedException e) (throw e))
                 (plugin/emit! ctx (str "couldn't read the transcript of " (:url video) ": " (ex-message e))
                               {:level :error :url (:url video)})
                 nil))
        said (some-> said (clip transcript-chars))]
    (plugin/item source {:title (:title video)
                         :url (:url video)
                         ;; each commentator by name, for what they argue and their record
                         :source (:channel video)
                         :origin (:channel video)
                         :summary (str/join " " (remove str/blank? [(description-lead (:description video)) said]))
                         :published (:published video)
                         :text (when said (str "Transcript of the video on " (:channel video) ":\n" said))})))

(defn- channel-videos
  "The videos of `channel` to read, or the exception reading its feed threw."
  [channel from {:keys [n shorts? opts]}]
  (try
    (let [id (channel-id channel opts)
          feed (try
                 (plugin/fetch-text (str "https://www.youtube.com/feeds/videos.xml?channel_id=" id) opts)
                 (catch clojure.lang.ExceptionInfo e
                   (when-not (:status (ex-data e)) (throw e))))]
      (if feed
        (recent-videos (videos (plugin/parse-xml feed)) from n shorts?)
        (tab-videos id from n opts)))
    (catch Exception e
      (when (instance? InterruptedException e) (throw e))
      e)))

(defn- channels [source]
  (let [c (:channels source)] (if (coll? c) (vec c) (if (str/blank? c) [] [c]))))

(plugin/defsettings
  {:doc "Nothing is needed here."
   :fields [{:key :client-name :type :string
             :doc "The YouTube app transcripts are asked for as, ANDROID by default."}
            {:key :client-version :type :string
             :doc "That app's version, 20.10.38 by default; a newer one when YouTube stops answering."}]})

(plugin/defname :youtube [source]
  (str "YouTube: " (str/join ", " (channels source))))

(plugin/defsource :youtube
  {:doc (str "YouTube channels' latest videos from the last few days, read for their transcripts, "
             "which the desk takes as the videos' full text.")
   ;; a channel posts every few days, not every day
   :lookback-days 3
   ;; what a channel says frames the news; it isn't reporting
   :kind :analysis
   ;; a handle's page, the feeds or the videos tabs, and up to three requests a video
   :policy {:timeout-ms 60000}
   :fields [{:key :channels :type :strings :required? true :label "Channels"
             :doc "The channels, each a handle like @veritasium, a channel ID like UC..., or the channel's address."}
            {:key :videos :type :int :default 1 :doc "The most videos read from each channel, latest first."}
            {:key :lookback-days :type :int :default 3
             :doc "How many days back a video may have been published to be read."}
            {:key :shorts :type :boolean :default false :doc "Whether Shorts are read too."}
            {:key :languages :type :strings :label "Languages"
             :doc "The caption languages wanted, in order, like en; en by default."}
            {:key :transcript-chars :type :int :default 12000
             :doc "The most of a transcript the analysis reads."}]}
  [source ctx]
  (let [{:keys [client-name client-version]} (plugin/config :youtube)
        lookback (plugin/lookback-days source (:config ctx))
        today (or (:day ctx) (str (java.time.LocalDate/now)))
        from (str (.minusDays (java.time.LocalDate/parse today) lookback))
        opts {:timeout-ms 20000}
        want {:n (:videos source 1) :shorts? (:shorts source false) :opts opts}
        reading {:languages (or (seq (:languages source)) ["en"])
                 :client (cond-> default-client
                           (not (str/blank? client-name)) (assoc :clientName client-name)
                           (not (str/blank? client-version)) (assoc :clientVersion client-version))
                 :transcript-chars (:transcript-chars source 12000)
                 :opts opts}
        chs (channels source)]
    (when (empty? chs)
      (throw (ex-info "a youtube source needs :channels" {})))
    (plugin/emit! ctx (str "Reading " (plugin/source-name source)))
    (let [found (mapv deref (mapv #(future (channel-videos % from want)) chs))
          failed (filter #(instance? Throwable %) found)
          vids (vec (apply concat (remove #(instance? Throwable %) found)))]
      (doseq [[ch e] (map vector chs found) :when (instance? Throwable e)]
        (plugin/emit! ctx (str "couldn't read the channel " ch ": " (ex-message e)) {:level :error}))
      (when (and (seq failed) (= (count failed) (count chs)))
        (throw (first failed)))
      (plugin/emit! ctx (str (count vids) (if (= 1 (count vids)) " video" " videos")
                             " since " from " on " (plugin/source-name source)))
      (->> vids
           (mapv #(future (video-item source ctx reading %)))
           (mapv deref)))))
