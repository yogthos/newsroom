(ns newsroom.store
  "The days in sqlite. A day is its briefing (the markdown, which model
  wrote it, when) and the sources gathered for it, numbered as the briefing
  cites them; `cited` marks the ones it cites. A source also keeps its
  embedding, the storyline it belongs to and the weight it was ranked by,
  which later days and the digests read back.

  A digest is a week's or a month's briefing, kept by its kind and period
  with the sources it cites. Digests are not pruned with the days, and
  neither are the projections the briefings, the digests and the outlets
  made, nor the retrospectives that judged them: they are the record the
  next projections are calibrated by. Each
  source's coverage, its storyline, outlets and whether it was cited, is
  kept `coverage-days` longer than the days themselves, so a month's digest
  can always be compared with the four months before it, and so are the
  days' standfirsts and the notes kept on each storyline, which the digests
  are written from.

  Everything a reader may look for is indexed for search as it is saved:
  the briefings and digests, the sources gathered for each day, and the
  storylines' notes and facts, a row each in search_docs, which two FTS5
  indexes read, one by stem for matching and ranking, one by the words as
  written for guessing at misspellings; see newsroom.search.

  The settings, everything about newsroom that can change while it runs,
  are kept here too, a row a key with the value as EDN; see
  newsroom.settings.

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
      precedent integer not null default 0,
      note text,
      informs text,
      gap text,
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
   "create table if not exists day_trends (
      day text not null,
      idx integer not null,
      thread text not null,
      name text not null,
      direction text,
      summary text,
      stories text,
      primary key (day, idx))"
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
      primary key (kind, period, n))"
   "create table if not exists settings (
      key text primary key,
      value text not null)"
   ;; a projection is newsroom's own when it has no source, else the
   ;; expectation an outlet put forward; origin and ref are where it was
   ;; read from, a day's briefing or a week's or month's digest
   "create table if not exists projections (
      id integer primary key,
      origin text not null,
      ref text not null,
      made_on text not null,
      claim text not null,
      subject text not null,
      story text,
      source text,
      outlet text,
      url text,
      by_whom text,
      due text not null,
      status text not null default 'open',
      closeness real,
      reason text,
      checked_kind text,
      checked_period text,
      checked_at text)"
   "create index if not exists projections_due on projections (status, due)"
   "create index if not exists projections_ref on projections (origin, ref)"
   "create table if not exists retrospectives (
      kind text not null,
      period text not null,
      summary text,
      lessons text not null,
      model text,
      provider text,
      created_at text not null,
      primary key (kind, period))"])

(def ^:private search-schema
  ;; ref is what a document is replaced and deleted by: the day for a
  ;; briefing and its sources, kind/period for a digest, the story for a
  ;; note and its facts
  ["create table if not exists search_docs (
      id integer primary key,
      kind text not null,
      ref text not null,
      day text,
      href text not null,
      url text,
      cited integer not null default 0,
      title text,
      body text)"
   "create index if not exists search_docs_ref on search_docs (kind, ref)"
   "create virtual table if not exists search_fts using fts5(
      title, body, content='search_docs', content_rowid='id',
      tokenize='porter unicode61 remove_diacritics 2')"
   "create virtual table if not exists search_words using fts5(
      title, body, content='search_docs', content_rowid='id',
      tokenize='unicode61 remove_diacritics 2', detail=none)"
   "create virtual table if not exists search_vocab using fts5vocab(search_words, 'row')"
   "create trigger if not exists search_docs_ai after insert on search_docs begin
      insert into search_fts (rowid, title, body) values (new.id, new.title, new.body);
      insert into search_words (rowid, title, body) values (new.id, new.title, new.body);
    end"
   "create trigger if not exists search_docs_ad after delete on search_docs begin
      insert into search_fts (search_fts, rowid, title, body) values ('delete', old.id, old.title, old.body);
      insert into search_words (search_words, rowid, title, body) values ('delete', old.id, old.title, old.body);
    end"])

(defn- add-column!
  "An ALTER TABLE for a column an older database may not have, silently
  skipped when it does."
  [conn table column ddl]
  (try (jdbc/execute! conn (str "alter table " table " add column " column " " ddl))
       (catch Exception _ nil)))

(defn- coverage-row [day cited {:keys [n story] :as source}]
  ;; a precedent was background, and a gap search's result was found for a
  ;; story rather than reported on it
  (when-not (or (:precedent source) (:gap source))
    [day n story (news/outlets source) (if (contains? cited n) 1 0)]))

(defn- insert-coverage! [conn rows]
  (doseq [row (remove nil? rows)]
    (jdbc/execute! conn (into ["insert or replace into coverage (day, n, story, outlets, cited)
                                values (?, ?, ?, ?, ?)"]
                              row))))

(defn- backfill-coverage!
  "Coverage for the sources stored before it was kept, once."
  [conn]
  (when (and (nil? (jdbc/fetch-one conn "select 1 as x from coverage limit 1"))
             (jdbc/fetch-one conn "select 1 as x from sources limit 1"))
    (jdbc/atomic conn
      (insert-coverage! conn (for [r (jdbc/fetch conn "select day, n, url, source, summary, also, story, cited, precedent from sources")
                                   :when (not= 1 (:precedent r))]
                               (coverage-row (:day r) (if (= 1 (:cited r)) #{(:n r)} #{})
                                             {:n (:n r) :story (:story r) :url (:url r) :source (:source r)
                                              :summary (:summary r)
                                              :also (when (:also r)
                                                      (mapv #(if (string? %) {:source %} %) (edn/read-string (:also r))))}))))))

;; --- the search index ------------------------------------------------------------

(defn- headline
  "A briefing's or digest's title, its first # heading, or nil."
  [markdown]
  (some #(some-> (re-matches #"#\s+(.+)" %) second str/trim) (str/split-lines (str markdown))))

(defn- plain
  "A briefing's or digest's text as it is searched: without its title, its
  diagrams, its citations or its Sources list, which are searched as the
  sources themselves, and with the markdown's marks gone."
  [markdown]
  (-> (str markdown)
      (str/split #"(?m)^## Sources\s*$" 2)
      first
      (str/replace #"(?m)^#\s.*$" "")
      (str/replace #"(?s)```.*?(?:```|$)" " ")
      (str/replace #"\[\[\d+\]\]\([^)\s]*\)" "")
      (str/replace #"\[\d+(?:,\s*\d+)*\]" "")
      (str/replace #"!?\[([^\]]*)\]\([^)]*\)" (fn [[_ text]] text))
      (str/replace #"(?m)^\s{0,3}(?:#{1,6}|>|[-*+]|\d+\.)\s+" "")
      (str/replace #"[*_`]" "")
      (str/replace #"[ \t]+" " ")
      (str/replace #"\n\s*\n+" "\n\n")
      str/trim))

(defn- unindex!
  "Take the documents of `kinds` kept under `ref` out of the index."
  [conn kinds ref]
  (jdbc/execute! conn (into [(str "delete from search_docs where ref = ? and kind in ("
                                  (str/join ", " (repeat (count kinds) "?")) ")")
                             ref]
                            kinds)))

(defn- index! [conn docs]
  (doseq [{:keys [kind ref day href url cited? title body]} docs]
    (jdbc/execute! conn ["insert into search_docs (kind, ref, day, href, url, cited, title, body)
                          values (?, ?, ?, ?, ?, ?, ?, ?)"
                         kind ref day href url (if cited? 1 0) title body])))

(defn- day-docs
  "A day's briefing and each source gathered for it, as documents."
  [day markdown sources cited]
  (cons {:kind "day" :ref day :day day :href (str "/day/" day)
         :title (or (headline markdown) day) :body (plain markdown)}
        (for [{:keys [n title url summary note gap]} sources]
          {:kind "source" :ref day :day day :href (str "/day/" day "#source-" n) :url url
           :cited? (contains? cited n) :title title
           :body (str/join "\n\n" (remove str/blank? [summary note gap]))})))

(defn- digest-docs [kind period markdown]
  [{:kind "digest" :ref (str kind "/" period) :href (str "/" kind "/" period)
    :title (or (headline markdown) period) :body (plain markdown)}])

(defn- note-docs
  "A storyline's note and each of its facts, as documents."
  [story {:keys [title summary facts last-day]}]
  (cons {:kind "story" :ref story :day last-day :href (str "/story/" story) :title title :body summary}
        (for [{:keys [day text url source headline]} facts]
          {:kind "fact" :ref story :day day :href (str "/story/" story) :url url :title title
           :body (str/join "\n\n" (remove str/blank? [text (str/join ", " (remove str/blank? [headline source]))]))})))

(defn- backfill-search!
  "Index everything kept before the search was, once."
  [conn]
  (when (and (nil? (jdbc/fetch-one conn "select 1 as x from search_docs limit 1"))
             (or (jdbc/fetch-one conn "select 1 as x from briefings limit 1")
                 (jdbc/fetch-one conn "select 1 as x from digests limit 1")
                 (jdbc/fetch-one conn "select 1 as x from story_notes limit 1")))
    (jdbc/atomic conn
      (doseq [{:keys [day markdown]} (jdbc/fetch conn "select day, markdown from briefings")]
        (let [rows (jdbc/fetch conn ["select n, title, url, summary, note, gap, cited from sources where day = ?" day])]
          (index! conn (day-docs day markdown rows (set (map :n (filter #(= 1 (:cited %)) rows)))))))
      (doseq [{:keys [kind period markdown]} (jdbc/fetch conn "select kind, period, markdown from digests")]
        (index! conn (digest-docs kind period markdown)))
      (doseq [r (jdbc/fetch conn "select story, title, summary, facts, last_day from story_notes")]
        (index! conn (note-docs (:story r) {:title (:title r) :summary (:summary r) :last-day (:last_day r)
                                            :facts (edn/read-string (:facts r))}))))))

(defn open
  "A store on the sqlite database at `uri` (a path, or sqlite::memory:)."
  [uri]
  (let [conn (jdbc/connection (if (re-find #"^sqlite:" uri) uri (str "sqlite:" uri)))]
    (doseq [stmt schema] (jdbc/execute! conn stmt))
    ;; databases from before these columns have no place for them
    (add-column! conn "briefings" "tldr" "text")
    (doseq [[column ddl] [["also" "text"] ["vector" "text"] ["story" "text"] ["weight" "real"]
                          ["precedent" "integer not null default 0"] ["note" "text"] ["informs" "text"]
                          ["gap" "text"]]]
      (add-column! conn "sources" column ddl))
    (add-column! conn "story_notes" "status" "text")
    (jdbc/execute! conn "create index if not exists sources_story on sources (story)")
    (jdbc/execute! conn "create index if not exists day_trends_thread on day_trends (thread)")
    (jdbc/execute! conn "create index if not exists coverage_story on coverage (story)")
    (backfill-coverage! conn)
    (doseq [stmt search-schema] (jdbc/execute! conn stmt))
    (backfill-search! conn)
    (jdbc/execute! conn "insert or ignore into standfirsts (day, tldr)
                         select day, tldr from briefings where tldr is not null")
    {:conn conn :lock (Object.) :vocab (atom nil)}))

(defn close [{:keys [conn]}] (.close conn))

(defmacro ^:private with-db [[conn store] & body]
  `(locking (:lock ~store)
     (let [~conn (:conn ~store)] ~@body)))

(defn- now [] (str (java.time.Instant/now)))

(defn- stale!
  "Forget the vocabulary read for searching, once the index has changed."
  [store]
  (reset! (:vocab store) nil))

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
        (doseq [{:keys [n title url source summary published also story weight note informs gap]
                 v :vector precedent? :precedent} sources]
          (jdbc/execute! conn ["insert into sources (day, n, title, url, source, summary, published, also,
                                                     vector, story, weight, precedent, note, informs, gap, cited)
                                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                               day n title url source summary published
                               (when (seq also) (pr-str also))
                               (embed/encode v) story weight (if precedent? 1 0)
                               note (when (seq informs) (pr-str (vec informs))) gap
                               (if (contains? cited n) 1 0)]))
        (unindex! conn ["day" "source"] day)
        (index! conn (day-docs day markdown sources cited))))
    (stale! store)
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
    (= 1 (:precedent r)) (assoc :precedent true)
    (:note r) (assoc :note (:note r))
    (:informs r) (assoc :informs (edn/read-string (:informs r)))
    (:gap r) (assoc :gap (:gap r))
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
           (jdbc/fetch conn [(str "select day, n, title, url, source, summary, published, also, story, weight, precedent, note, informs, gap, cited"
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
                                  where story is null and precedent = 0 and day >= ? and day <= ? order by day" from to]))))

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

(defn briefings-between
  "The briefings of the days from `from` to `to`, both inclusive, oldest
  first, as {:day :markdown}: only the days :keep-days hasn't dropped."
  [store from to]
  (with-db [conn store]
    (mapv (fn [r] {:day (:day r) :markdown (:markdown r)})
          (jdbc/fetch conn ["select day, markdown from briefings where day >= ? and day <= ? order by day" from to]))))

(defn save-notes!
  "Store storyline notes, {story note}, each replacing the story's last."
  [store notes]
  (with-db [conn store]
    (jdbc/atomic conn
      (doseq [[story {:keys [title summary status facts first-day last-day]}] notes]
        (jdbc/execute! conn ["insert or replace into story_notes
                                (story, title, summary, status, facts, first_day, last_day, updated_at)
                              values (?, ?, ?, ?, ?, ?, ?, ?)"
                             story title summary status (pr-str (vec facts)) first-day last-day (now)])
        (unindex! conn ["story" "fact"] story)
        (index! conn (note-docs story {:title title :summary summary :facts facts :last-day last-day})))))
  (stale! store)
  nil)

(defn- row->note [r]
  (cond-> {:story (:story r) :title (:title r) :summary (:summary r)
           :facts (edn/read-string (:facts r))
           :first-day (:first_day r) :last-day (:last_day r)}
    (:status r) (assoc :status (:status r))))

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
                         (unindex! conn ["day" "source"] d)
                         (jdbc/execute! conn ["delete from sources where day = ?" d])
                         (jdbc/execute! conn ["delete from briefings where day = ?" d]))
                       (jdbc/execute! conn ["delete from coverage where day not in
                                               (select distinct day from coverage order by day desc limit ?)"
                                            (+ n coverage-days)])
                       ;; what outlives the days goes with the coverage
                       (jdbc/execute! conn "delete from standfirsts where day < (select min(day) from coverage)")
                       (jdbc/execute! conn "delete from day_trends where day < (select min(day) from coverage)")
                       (jdbc/execute! conn "delete from search_docs where kind in ('story', 'fact') and ref in
                                              (select story from story_notes where last_day < (select min(day) from coverage))")
                       (jdbc/execute! conn "delete from story_notes where last_day < (select min(day) from coverage)")))
                   gone))]
      (when (seq gone)
        (stale! store)
        (with-db [conn store] (jdbc/execute! conn "vacuum")))
      gone)))

;; --- trends ----------------------------------------------------------------------

(defn save-trends!
  "Store the trends the desk found on `day`, {:thread :name :direction
  :summary :stories}, the stories by their storylines, in place of the
  day's last."
  [store day trends]
  (with-db [conn store]
    (jdbc/atomic conn
      (jdbc/execute! conn ["delete from day_trends where day = ?" day])
      (doseq [[i {:keys [thread name direction summary stories]}] (map-indexed vector trends)]
        (jdbc/execute! conn ["insert into day_trends (day, idx, thread, name, direction, summary, stories)
                              values (?, ?, ?, ?, ?, ?, ?)"
                             day i thread name direction summary (pr-str (vec stories))]))))
  nil)

(defn trends-between
  "The trends found on the days from `from` to `to`, both inclusive, in
  order: {:day :thread :name :direction :summary :stories}. They are kept
  as long as the coverage."
  [store from to]
  (with-db [conn store]
    (mapv (fn [r] {:day (:day r) :thread (:thread r) :name (:name r) :direction (:direction r)
                   :summary (:summary r) :stories (some-> (:stories r) edn/read-string)})
          (jdbc/fetch conn ["select * from day_trends where day >= ? and day <= ? order by day, idx" from to]))))

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
                               k period n day title url source (if (contains? cited n) 1 0)]))
        (unindex! conn ["digest"] (str k "/" period))
        (index! conn (digest-docs k period markdown))))
    (stale! store)
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

;; --- projections and retrospectives -----------------------------------------------

(defn save-projections!
  "Store the projections read from `origin` (\"day\", \"week\" or
  \"month\") `ref`, its day or period, in place of the ones it had."
  [store origin ref projections]
  (with-db [conn store]
    (jdbc/atomic conn
      (jdbc/execute! conn ["delete from projections where origin = ? and ref = ?" origin ref])
      (doseq [{:keys [made-on claim subject story source outlet url by due]} projections]
        (jdbc/execute! conn ["insert into projections (origin, ref, made_on, claim, subject, story, source,
                                                       outlet, url, by_whom, due)
                              values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                             origin ref made-on claim subject story source outlet url by due]))))
  nil)

(defn- row->projection [r]
  (cond-> {:id (long (:id r)) :origin (:origin r) :ref (:ref r) :made-on (:made_on r) :claim (:claim r)
           :subject (:subject r) :due (:due r) :status (:status r)}
    (:story r) (assoc :story (:story r))
    (:source r) (assoc :source (:source r))
    (:outlet r) (assoc :outlet (:outlet r))
    (:url r) (assoc :url (:url r))
    (:by_whom r) (assoc :by (:by_whom r))
    (:closeness r) (assoc :closeness (double (:closeness r)))
    (:reason r) (assoc :reason (:reason r))
    (:checked_kind r) (assoc :checked-kind (:checked_kind r))
    (:checked_period r) (assoc :checked-period (:checked_period r))))

(defn projections-due
  "The projections the retrospective of `kind` and `period`, which ends on
  `to`, judges, oldest first: the open ones due by then, and the ones it
  judged already, so a retrospective written again judges them again."
  [store to kind period]
  (with-db [conn store]
    (mapv row->projection
          (jdbc/fetch conn ["select * from projections
                             where (status = 'open' and due <= ?) or (checked_kind = ? and checked_period = ?)
                             order by made_on, id"
                            to (name kind) period]))))

(defn open-projections
  "Newsroom's own projections made before `day` that no retrospective has
  settled yet, the soonest due first."
  [store day]
  (with-db [conn store]
    (mapv row->projection
          (jdbc/fetch conn ["select * from projections
                             where status = 'open' and source is null and outlet is null and made_on < ?
                             order by due, made_on desc, id"
                            day]))))

(defn save-verdicts!
  "Store what a retrospective found of each of `projections`, by :id: its
  status, closeness, reason and due day, and which retrospective it was."
  [store projections]
  (with-db [conn store]
    (jdbc/atomic conn
      (doseq [{:keys [id status closeness reason due checked-kind checked-period]} projections]
        (jdbc/execute! conn ["update projections set status = ?, closeness = ?, reason = ?, due = ?,
                                checked_kind = ?, checked_period = ?, checked_at = ?
                              where id = ?"
                             status closeness reason due checked-kind checked-period (now) id]))))
  nil)

(defn projections-checked
  "The projections the retrospective of `kind` and `period` judged, in the
  order they were made."
  [store kind period]
  (with-db [conn store]
    (mapv row->projection
          (jdbc/fetch conn ["select * from projections where checked_kind = ? and checked_period = ?
                             order by made_on, id"
                            (name kind) period]))))

(defn judged-projections
  "Every projection a retrospective settled, held, partly or failed, the
  latest judged first: what the standings are made from."
  [store]
  (with-db [conn store]
    (mapv row->projection
          (jdbc/fetch conn "select * from projections where status in ('held', 'partly', 'failed')
                            order by checked_at desc, id desc"))))

(defn save-retrospective!
  "Store a retrospective, {:kind :period :summary :lessons :model
  :provider}, replacing the period's last."
  [store {:keys [kind period summary lessons model provider]}]
  (with-db [conn store]
    (jdbc/execute! conn ["insert or replace into retrospectives (kind, period, summary, lessons, model, provider, created_at)
                          values (?, ?, ?, ?, ?, ?, ?)"
                         (name kind) period summary (pr-str (vec lessons)) model provider (now)]))
  nil)

(defn- row->retrospective [r]
  {:kind (keyword (:kind r)) :period (:period r) :summary (:summary r)
   :lessons (edn/read-string (:lessons r)) :model (:model r) :provider (:provider r)
   :created-at (:created_at r)})

(defn retrospective
  "The retrospective of `kind` and `period`, or nil."
  [store kind period]
  (with-db [conn store]
    (some-> (jdbc/fetch-one conn ["select * from retrospectives where kind = ? and period = ?" (name kind) period])
            row->retrospective)))

(defn latest-retrospective
  "The retrospective written last, whose lessons are the ones kept, or
  nil."
  [store]
  (with-db [conn store]
    (some-> (jdbc/fetch-one conn "select * from retrospectives order by created_at desc, rowid desc limit 1")
            row->retrospective)))

;; --- settings ----------------------------------------------------------------------

(defn settings
  "The stored settings, {key value}; empty before any are saved."
  [store]
  (with-db [conn store]
    (into {}
          (map (fn [r] [(keyword (:key r)) (edn/read-string (:value r))]))
          (jdbc/fetch conn "select key, value from settings"))))

(defn save-settings!
  "Store `settings`, {key value}, in place of every setting there was."
  [store settings]
  (with-db [conn store]
    (jdbc/atomic conn
      (jdbc/execute! conn "delete from settings")
      (doseq [[k v] settings]
        (jdbc/execute! conn ["insert into settings (key, value) values (?, ?)" (name k) (pr-str v)]))))
  nil)

;; --- search ------------------------------------------------------------------------

(defn vocabulary
  "Every word the index has as written, {word documents}, which misspelled
  words are matched against; read once until the index changes."
  [store]
  (with-db [conn store]
    (or @(:vocab store)
        (reset! (:vocab store)
                (into {} (map (fn [r] [(:term r) (long (:doc r))]))
                      (jdbc/fetch conn "select term, doc from search_vocab"))))))

(defn search
  "The documents matching the FTS5 expression `expr`, best first, or the
  latest first with :sort :newest: {:kind :ref :day :href :url :cited?
  :title :snippet}, the snippet a part of the text with each match between
  \u0002 and \u0003. `opts`: :kind, :limit, :offset. A title matches
  four times as strongly as the text, and a source a briefing cited a
  little more than one it didn't."
  [store expr {:keys [kind limit offset sort] :or {limit 30 offset 0}}]
  (with-db [conn store]
    (mapv (fn [r] {:kind (keyword (:kind r)) :ref (:ref r) :day (:day r) :href (:href r) :url (:url r)
                   :cited? (= 1 (:cited r)) :title (:title r) :snippet (:snippet r)})
          (jdbc/fetch conn (cond-> [(str "select d.kind, d.ref, d.day, d.href, d.url, d.cited, d.title,
                                                 snippet(search_fts, 1, char(2), char(3), '…', 24) as snippet
                                          from search_fts join search_docs d on d.id = search_fts.rowid
                                          where search_fts match ?"
                                         (when kind " and d.kind = ?")
                                         " order by "
                                         (when (= :newest sort) "d.day desc, ")
                                         "bm25(search_fts, 4.0, 1.0) * (case when d.cited = 1 then 1.25 else 1.0 end)
                                          limit ? offset ?")
                                    expr]
                             kind (conj (name kind))
                             true (conj limit offset))))))

(defn search-counts
  "How many documents of each kind match `expr`, {kind count}."
  [store expr]
  (with-db [conn store]
    (into {}
          (map (fn [r] [(keyword (:kind r)) (long (:n r))]))
          (jdbc/fetch conn ["select d.kind, count(*) as n
                             from search_fts join search_docs d on d.id = search_fts.rowid
                             where search_fts match ? group by d.kind"
                            expr]))))
