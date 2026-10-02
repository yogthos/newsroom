(ns newsroom.store
  "The days in sqlite. A day is its briefing (the markdown, which model
  wrote it, when) and the sources gathered for it, numbered as the briefing
  cites them; `cited` marks the ones it cites.

  One connection, serialized by a lock: sqlite writes one at a time anyway,
  and the server's handlers and the pipeline share it."
  (:require [db.jdbc]
            [jdbc.core :as jdbc]
            [jolt.time]))

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
      cited integer not null default 0,
      primary key (day, n))"
   "create table if not exists source_health (
      name text primary key,
      last_ok text,
      consecutive_failures integer not null default 0,
      last_error text)"])

(defn- add-column!
  "An ALTER TABLE for a column an older database may not have, silently
  skipped when it does."
  [conn table column ddl]
  (try (jdbc/execute! conn (str "alter table " table " add column " column " " ddl))
       (catch Exception _ nil)))

(defn open
  "A store on the sqlite database at `uri` (a path, or sqlite::memory:)."
  [uri]
  (let [conn (jdbc/connection (if (re-find #"^sqlite:" uri) uri (str "sqlite:" uri)))]
    (doseq [stmt schema] (jdbc/execute! conn stmt))
    ;; databases from before :tldr and :also have no place for them
    (add-column! conn "briefings" "tldr" "text")
    (add-column! conn "sources" "also" "text")
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
        (jdbc/execute! conn ["delete from briefings where day = ?" day])
        (jdbc/execute! conn ["insert into briefings (day, markdown, model, provider, tldr, created_at)
                              values (?, ?, ?, ?, ?, ?)"
                             day markdown model provider tldr (now)])
        (doseq [{:keys [n title url source summary published also]} sources]
          (jdbc/execute! conn ["insert into sources (day, n, title, url, source, summary, published, also, cited)
                                values (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                               day n title url source summary published
                               (when (seq also) (pr-str also))
                               (if (contains? cited n) 1 0)]))))
    nil))

(defn- row->source [r]
  (cond-> {:n (long (:n r)) :title (:title r) :url (:url r) :source (:source r)
           :summary (:summary r) :published (:published r)}
    (:also r) (assoc :also (read-string (:also r)))))

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
  when it last worked."
  [store results]
  (with-db [conn store]
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

(defn prune!
  "Delete every day but the newest `n`, with its sources, and return the
  days deleted, newest first. A negative `n` keeps everything. The file is
  vacuumed after a deletion so the space goes back to the disk."
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
                         (jdbc/execute! conn ["delete from briefings where day = ?" d]))))
                   gone))]
      (when (seq gone)
        (with-db [conn store] (jdbc/execute! conn "vacuum")))
      gone)))
