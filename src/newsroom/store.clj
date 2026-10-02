(ns newsroom.store
  "The days in sqlite. A day is its briefing (the markdown, which model
  wrote it, when) and the sources gathered for it, numbered as the briefing
  cites them; `cited` marks the ones it cites. A source also keeps its
  embedding, the storyline it belongs to and the weight it was ranked by,
  which later days and the digests read back.

  A digest is a week's or a month's briefing, kept by its kind and period
  with the sources it cites. Digests are not pruned with the days. Each
  source's coverage, its storyline, outlets and whether it was cited, is
  kept `coverage-days` longer than the days themselves, so a month's digest
  can always be compared with the four months before it, and so are the
  days' standfirsts and the notes kept on each storyline, which the digests
  are written from.

  One connection, serialized by a lock: sqlite writes one at a time anyway,
  and the server's handlers and the pipeline share it."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [db.jdbc]
            [jdbc.core :as jdbc]
            [jolt.time]
            [newsroom.embed :as embed]
            [newsroom.news :as news]))

(def ^:private schema
  ["create table if not exists briefings (
      day text primary key,
      markdown text not null,
      model text,
      provider text,
      tldr text,
      created_at text not null)"
   "create table if not exists sources (
      day text not null,
      n integer not null,
      title text not null,
      url text not null,
      source text,
      summary text,
      published text,
      also text,
      vector text,
      story text,
      weight real,
      cited integer not null default 0,
      primary key (day, n))"
   "create table if not exists source_health (
      name text primary key,
      last_ok text,
      consecutive_failures integer not null default 0,
      last_error text)"
   "create table if not exists coverage (
      day text not null,
      n integer not null,
      story text,
      outlets integer not null default 1,
      cited integer not null default 0,
      primary key (day, n))"
   "create table if not exists standfirsts (
      day text primary key,
      tldr text not null)"
   "create table if not exists story_notes (
      story text primary key,
      title text,
      summary text,
      facts text not null,
      first_day text,
      last_day text not null,
      updated_at text not null)"
   "create table if not exists digests (
      kind text not null,
      period text not null,
      markdown text not null,
      model text,
      provider text,
      tldr text,
      created_at text not null,
      primary key (kind, period))"
   "create table if not exists digest_sources (
      kind text not null,
      period text not null,
      n integer not null,
      day text,
      title text not null,
      url text not null,
      source text,
      cited integer not null default 0,
      primary key (kind, period, n))"])

(defn- add-column!
  "An ALTER TABLE for a column an older database may not have, silently
  skipped when it does."
  [conn table column ddl]
  (try (jdbc/execute! conn (str "alter table " table " add column " column " " ddl))
       (catch Exception _ nil)))

(defn- coverage-row [day cited {:keys [n story] :as source}]
  [day n story (news/outlets source) (if (contains? cited n) 1 0)])

(defn- insert-coverage! [conn rows]
  (doseq [row rows]
    (jdbc/execute! conn (into ["insert or replace into coverage (day, n, story, outlets, cited)
                                values (?, ?, ?, ?, ?)"]
                              row))))

(defn- backfill-coverage!
  "Coverage for the sources stored before it was kept, once."
  [conn]
  (when (and (nil? (jdbc/fetch-one conn "select 1 as x from coverage limit 1"))
             (jdbc/fetch-one conn "select 1 as x from sources limit 1"))
    (jdbc/atomic conn
      (insert-coverage! conn (for [r (jdbc/fetch conn "select day, n, url, source, summary, also, story, cited from sources")]
                               (coverage-row (:day r) (if (= 1 (:cited r)) #{(:n r)} #{})
                                             {:n (:n r) :story (:story r) :url (:url r) :source (:source r)
                                              :summary (:summary r)
                                              :also (when (:also r)
                                                      (mapv #(if (string? %) {:source %} %) (edn/read-string (:also r))))}))))))

(defn open
  "A store on the sqlite database at `uri` (a path, or sqlite::memory:)."
  [uri]
  (let [conn (jdbc/connection (if (re-find #"^sqlite:" uri) uri (str "sqlite:" uri)))]
    (doseq [stmt schema] (jdbc/execute! conn stmt))
    ;; databases from before these columns have no place for them
    (add-column! conn "briefings" "tldr" "text")
    (doseq [[column ddl] [["also" "text"] ["vector" "text"] ["story" "text"] ["weight" "real"]]]
      (add-column! conn "sources" column ddl))
    (jdbc/execute! conn "create index if not exists sources_story on sources (story)")
    (jdbc/execute! conn "create index if not exists coverage_story on coverage (story)")
    (backfill-coverage! conn)
    (jdbc/execute! conn "insert or ignore into standfirsts (day, tldr)
                         select day, tldr from briefings where tldr is not null")
    {:conn conn :lock (Object.)}))

(defn close [{:keys [conn]}] (.close conn))

(defmacro ^:private with-db [[conn store] & body]
  `(locking (:lock ~store)
     (let [~conn (:conn ~store)] ~@body)))

(defn- now [] (str (java.time.Instant/now)))

(defn save-day!
  "Store a day's briefing and its sources, replacing whatever the day had."
  [store {:keys [day sources cited markdown model provider tldr]}]
  (let [cited (set cited)]
    (with-db [conn store]
      (jdbc/atomic conn
        (jdbc/execute! conn ["delete from sources where day = ?" day])
        (jdbc/execute! conn ["delete from coverage where day = ?" day])
        (jdbc/execute! conn ["delete from briefings where day = ?" day])
        (insert-coverage! conn (map #(coverage-row day cited %) sources))
        (jdbc/execute! conn ["delete from standfirsts where day = ?" day])
        (when tldr (jdbc/execute! conn ["insert into standfirsts (day, tldr) values (?, ?)" day tldr]))
        (jdbc/execute! conn ["insert into briefings (day, markdown, model, provider, tldr, created_at)
                              values (?, ?, ?, ?, ?, ?)"
                             day markdown model provider tldr (now)])
        (doseq [{:keys [n title url source summary published also story weight] v :vector} sources]
          (jdbc/execute! conn ["insert into sources (day, n, title, url, source, summary, published, also,
                                                     vector, story, weight, cited)
                                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                               day n title url source summary published
                               (when (seq also) (pr-str also))
                               (embed/encode v) story weight
                               (if (contains? cited n) 1 0)]))))
    nil))

(defn- read-also
  "The copies a source was collapsed with, {:source :url :title}; rows
  from before the copies were kept name only the outlet."
  [s]
  (when s
    (mapv #(if (string? %) {:source %} %) (edn/read-string s))))

(defn- row->source [r]
  (cond-> {:n (long (:n r)) :title (:title r) :url (:url r) :source (:source r)
           :summary (:summary r) :published (:published r)}
    (:also r) (assoc :also (read-also (:also r)))
    (:story r) (assoc :story (:story r))
    (:weight r) (assoc :weight (double (:weight r)))))

(defn day
  "The stored day, or nil: {:day :markdown :model :provider :created-at
  :sources :cited}."
  [store day]
  (with-db [conn store]
    (when-let [b (jdbc/fetch-one conn ["select * from briefings where day = ?" day])]
      (let [rows (jdbc/fetch conn ["select * from sources where day = ? order by n" day])]
        {:day day
         :markdown (:markdown b)
         :model (:model b)
         :provider (:provider b)
         :tldr (:tldr b)
         :created-at (:created_at b)
         :sources (mapv row->source rows)
         :cited (mapv row->source (filter #(= 1 (:cited %)) rows))}))))

(defn sources-between
  "Every source stored for the days from `from` to `to`, both inclusive,
  as the day's sources are plus its :day and whether the day's briefing
  cited it, :cited?: what storylines and digests are worked out from. Each
  has its decoded :vector unless `opts` says :vectors? false, which a
  month of sources reads much faster without."
  ([store from to] (sources-between store from to {:vectors? true}))
  ([store from to {:keys [vectors?]}]
   (with-db [conn store]
     (mapv (fn [r] (cond-> (assoc (row->source r) :day (:day r) :cited? (= 1 (:cited r)))
                     (and vectors? (:vector r)) (assoc :vector (embed/decode (:vector r)))))
           (jdbc/fetch conn [(str "select day, n, title, url, source, summary, published, also, story, weight, cited"
                                  (when vectors? ", vector")
                                  " from sources where day >= ? and day <= ? order by day, n")
                             from to])))))

(defn archive
  "The days that have a briefing, newest first, each with its standfirst
  when it has one: for the sidebar's history list."
  [store]
  (with-db [conn store]
    (mapv (fn [r] {:day (:day r) :tldr (:tldr r)})
          (jdbc/fetch conn "select day, tldr from briefings order by day desc"))))

(defn record-source-health!
  "Roll each source's latest fetch up into its health: a failure bumps the
  consecutive count and remembers why, a success clears both and stamps
  when it last worked. A source the run didn't fetch, one taken out of the
  config, is forgotten."
  [store results]
  (with-db [conn store]
    (when (seq results)
      (jdbc/execute! conn (into [(str "delete from source_health where name not in ("
                                      (str/join ", " (repeat (count results) "?")) ")")]
                                (map :source results))))
    (doseq [{:keys [source error]} results]
      (if error
        (jdbc/execute! conn ["insert into source_health (name, consecutive_failures, last_error)
                              values (?, 1, ?)
                              on conflict(name) do update set
                                consecutive_failures = consecutive_failures + 1,
                                last_error = excluded.last_error"
                             source error])
        (jdbc/execute! conn ["insert into source_health (name, last_ok, consecutive_failures)
                              values (?, ?, 0)
                              on conflict(name) do update set
                                last_ok = excluded.last_ok,
                                consecutive_failures = 0,
                                last_error = null"
                             source (now)])))))

(defn source-health
  "Every source's health, alphabetical by name: {:name :last-ok
  :consecutive-failures :last-error}, the run desk's long view of which
  feeds have been failing."
  [store]
  (with-db [conn store]
    (mapv (fn [r] {:name (:name r)
                   :last-ok (:last_ok r)
                   :consecutive-failures (long (:consecutive_failures r))
                   :last-error (:last_error r)})
          (jdbc/fetch conn "select name, last_ok, consecutive_failures, last_error
                            from source_health order by name"))))

(defn days
  "Every day with a briefing, newest first."
  [store]
  (with-db [conn store]
    (mapv :day (jdbc/fetch conn "select day from briefings order by day desc"))))

(defn coverage-between
  "The coverage kept for the days from `from` to `to`, both inclusive:
  {:day :n :story :outlets :cited?} for each source, which outlives the
  sources themselves."
  [store from to]
  (with-db [conn store]
    (mapv (fn [r] {:day (:day r) :n (long (:n r)) :story (:story r)
                   :outlets (long (:outlets r)) :cited? (= 1 (:cited r))})
          (jdbc/fetch conn ["select * from coverage where day >= ? and day <= ? order by day, n" from to]))))

(defn days-without-storylines
  "The days from `from` to `to` with sources stored before storylines were
  kept, oldest first."
  [store from to]
  (with-db [conn store]
    (mapv :day (jdbc/fetch conn ["select distinct day from sources
                                  where story is null and day >= ? and day <= ? order by day" from to]))))

(defn set-storylines!
  "Store the storyline and embedding of each of a day's `sources`, in the
  sources and in their coverage."
  [store day sources]
  (with-db [conn store]
    (jdbc/atomic conn
      (doseq [{:keys [n story] v :vector} sources]
        (jdbc/execute! conn ["update sources set story = ?, vector = coalesce(?, vector) where day = ? and n = ?"
                             story (embed/encode v) day n])
        (jdbc/execute! conn ["update coverage set story = ? where day = ? and n = ?" story day n]))))
  nil)

(defn standfirsts-between
  "The standfirsts of the days from `from` to `to`, both inclusive, as
  {:day :tldr}: kept as long as the coverage."
  [store from to]
  (with-db [conn store]
    (mapv (fn [r] {:day (:day r) :tldr (:tldr r)})
          (jdbc/fetch conn ["select day, tldr from standfirsts where day >= ? and day <= ? order by day" from to]))))

(defn save-notes!
  "Store storyline notes, {story note}, each replacing the story's last."
  [store notes]
  (with-db [conn store]
    (jdbc/atomic conn
      (doseq [[story {:keys [title summary facts first-day last-day]}] notes]
        (jdbc/execute! conn ["insert or replace into story_notes
                                (story, title, summary, facts, first_day, last_day, updated_at)
                              values (?, ?, ?, ?, ?, ?, ?)"
                             story title summary (pr-str (vec facts)) first-day last-day (now)]))))
  nil)

(defn- row->note [r]
  {:story (:story r) :title (:title r) :summary (:summary r)
   :facts (edn/read-string (:facts r))
   :first-day (:first_day r) :last-day (:last_day r)})

(defn all-notes
  "Every storyline's note, the latest updated first."
  [store]
  (with-db [conn store]
    (mapv row->note (jdbc/fetch conn "select * from story_notes order by last_day desc, story"))))

(defn recent-stories
  "The `n` storylines with the latest notes, {:story :title :last-day},
  for the sidebar, without reading their facts."
  [store n]
  (with-db [conn store]
    (mapv (fn [r] {:story (:story r) :title (:title r) :last-day (:last_day r)})
          (jdbc/fetch conn ["select story, title, last_day from story_notes order by last_day desc, story limit ?" n]))))

(defn story-coverage
  "A storyline's coverage, day by day: {:day :outlets :sources :cited?},
  its outlets summed over its sources that day, as long as it is kept."
  [store story]
  (with-db [conn store]
    (mapv (fn [r] {:day (:day r) :outlets (long (:outlets r)) :sources (long (:sources r))
                   :cited? (pos? (:cited r))})
          (jdbc/fetch conn ["select day, sum(outlets) as outlets, count(*) as sources, max(cited) as cited
                             from coverage where story = ? group by day order by day" story]))))

(defn notes
  "The notes kept on `stories`, {story {:story :title :summary :facts
  :first-day :last-day}}; a story with no note is missing."
  [store stories]
  (if (empty? stories)
    {}
    (with-db [conn store]
      (into {}
            (map (fn [r] [(:story r) (row->note r)]))
            (jdbc/fetch conn (into [(str "select * from story_notes where story in ("
                                         (str/join ", " (repeat (count (distinct stories)) "?")) ")")]
                                   (distinct stories)))))))

(def coverage-days
  "How many days longer than the days themselves their coverage is kept:
  four months and a few days, a month's digest's baseline."
  124)

(defn prune!
  "Delete every day but the newest `n`, with its sources, and return the
  days deleted, newest first; their coverage goes once it is
  `coverage-days` days older still. A negative `n` keeps everything. The
  file is vacuumed after a deletion so the space goes back to the disk."
  [store n]
  (if (neg? n)
    []
    (let [gone (with-db [conn store]
                 (let [gone (mapv :day (jdbc/fetch conn ["select day from briefings order by day desc
                                                          limit -1 offset ?" n]))]
                   (when (seq gone)
                     (jdbc/atomic conn
                       (doseq [d gone]
                         (jdbc/execute! conn ["delete from sources where day = ?" d])
                         (jdbc/execute! conn ["delete from briefings where day = ?" d]))
                       (jdbc/execute! conn ["delete from coverage where day not in
                                               (select distinct day from coverage order by day desc limit ?)"
                                            (+ n coverage-days)])
                       ;; what outlives the days goes with the coverage
                       (jdbc/execute! conn "delete from standfirsts where day < (select min(day) from coverage)")
                       (jdbc/execute! conn "delete from story_notes where last_day < (select min(day) from coverage)")))
                   gone))]
      (when (seq gone)
        (with-db [conn store] (jdbc/execute! conn "vacuum")))
      gone)))

;; --- digests -----------------------------------------------------------------------

(defn save-digest!
  "Store a digest and the sources it was given, replacing whatever the
  period had."
  [store {:keys [kind period sources cited markdown model provider tldr]}]
  (let [cited (set cited)
        k (name kind)]
    (with-db [conn store]
      (jdbc/atomic conn
        (jdbc/execute! conn ["delete from digest_sources where kind = ? and period = ?" k period])
        (jdbc/execute! conn ["delete from digests where kind = ? and period = ?" k period])
        (jdbc/execute! conn ["insert into digests (kind, period, markdown, model, provider, tldr, created_at)
                              values (?, ?, ?, ?, ?, ?, ?)"
                             k period markdown model provider tldr (now)])
        (doseq [{:keys [n day title url source]} sources]
          (jdbc/execute! conn ["insert into digest_sources (kind, period, n, day, title, url, source, cited)
                                values (?, ?, ?, ?, ?, ?, ?, ?)"
                               k period n day title url source (if (contains? cited n) 1 0)]))))
    nil))

(defn digest
  "The stored digest, or nil: {:kind :period :markdown :model :provider
  :tldr :created-at :sources}."
  [store kind period]
  (with-db [conn store]
    (when-let [d (jdbc/fetch-one conn ["select * from digests where kind = ? and period = ?" (name kind) period])]
      {:kind kind
       :period period
       :markdown (:markdown d)
       :model (:model d)
       :provider (:provider d)
       :tldr (:tldr d)
       :created-at (:created_at d)
       :sources (mapv (fn [r] {:n (long (:n r)) :day (:day r) :title (:title r) :url (:url r)
                               :source (:source r) :cited? (= 1 (:cited r))})
                      (jdbc/fetch conn ["select * from digest_sources where kind = ? and period = ? order by n"
                                        (name kind) period]))})))

(defn digests
  "Every digest, newest period first, each {:kind :period :tldr}."
  [store]
  (with-db [conn store]
    (mapv (fn [r] {:kind (keyword (:kind r)) :period (:period r) :tldr (:tldr r)})
          (jdbc/fetch conn "select kind, period, tldr from digests order by period desc, kind"))))
