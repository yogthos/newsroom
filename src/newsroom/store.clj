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

  A day's podcast, its briefing talked through by two hosts, is kept with
  it as MP3, with its script; see newsroom.narration. It tells the
  briefing it was written from, so a rerun of the day drops it, and it is
  pruned with its day.

  The settings, everything about newsroom that can change while it runs,
  are kept here too, a row a key with the value as EDN; see
  newsroom.settings.

  Everything above is a reader's own. Each row carries the user_id of the
  reader it belongs to, and a store is scoped to one reader with
  `for-user`; a store opened plain is the local reader's, the one reader
  there is when newsroom runs for one person (see newsroom.auth). The
  readers are kept in users, by the issuer and subject their sign-in
  names, and a signed-in browser's session in sessions.

  What a source gives doesn't depend on who reads it, so what one reader's
  run fetched is kept for the others for a while, in the shared tables:
  the items a source gave, by what the source asks for, the text of the
  articles read in full, and the embeddings of the items, by their text.

  One connection, serialized by a lock: sqlite writes one at a time anyway,
  and the server's handlers and the pipeline share it."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [db.jdbc]
            [jdbc.core :as jdbc]
            [jolt.time]
            [newsroom.embed :as embed]
            [newsroom.news :as news]))

(def local-user-id
  "The reader a store opened plain belongs to: the only one when newsroom
  runs for one person, and the owner of what a database from before there
  were readers holds."
  1)

(def ^:private tables
  "Each reader's tables, by name, every row under its reader's user_id."
  (array-map
   "briefings" "create table if not exists briefings (
      user_id integer not null,
      day text not null,
      markdown text not null,
      model text,
      provider text,
      tldr text,
      created_at text not null,
      primary key (user_id, day))"
   "sources" "create table if not exists sources (
      user_id integer not null,
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
      kind text,
      cited integer not null default 0,
      primary key (user_id, day, n))"
   "source_health" "create table if not exists source_health (
      user_id integer not null,
      name text not null,
      last_ok text,
      consecutive_failures integer not null default 0,
      last_error text,
      primary key (user_id, name))"
   "coverage" "create table if not exists coverage (
      user_id integer not null,
      day text not null,
      n integer not null,
      story text,
      outlets integer not null default 1,
      cited integer not null default 0,
      primary key (user_id, day, n))"
   "standfirsts" "create table if not exists standfirsts (
      user_id integer not null,
      day text not null,
      tldr text not null,
      primary key (user_id, day))"
   "story_notes" "create table if not exists story_notes (
      user_id integer not null,
      story text not null,
      title text,
      summary text,
      status text,
      facts text not null,
      first_day text,
      last_day text not null,
      updated_at text not null,
      primary key (user_id, story))"
   "story_note_versions" "create table if not exists story_note_versions (
      user_id integer not null,
      story text not null,
      day text not null,
      title text,
      summary text,
      status text,
      facts text not null,
      first_day text,
      updated_at text not null,
      primary key (user_id, story, day))"
   "day_trends" "create table if not exists day_trends (
      user_id integer not null,
      day text not null,
      idx integer not null,
      thread text not null,
      level text,
      name text not null,
      direction text,
      summary text,
      stories text,
      primary key (user_id, day, idx))"
   "entity_mentions" "create table if not exists entity_mentions (
      user_id integer not null,
      day text not null,
      entity text not null,
      story text not null,
      name text not null,
      kind text,
      title text,
      importance integer,
      cited integer not null default 0,
      primary key (user_id, day, entity, story))"
   "digests" "create table if not exists digests (
      user_id integer not null,
      kind text not null,
      period text not null,
      markdown text not null,
      model text,
      provider text,
      tldr text,
      created_at text not null,
      primary key (user_id, kind, period))"
   "digest_sources" "create table if not exists digest_sources (
      user_id integer not null,
      kind text not null,
      period text not null,
      n integer not null,
      day text,
      title text not null,
      url text not null,
      source text,
      cited integer not null default 0,
      primary key (user_id, kind, period, n))"
   "settings" "create table if not exists settings (
      user_id integer not null,
      key text not null,
      value text not null,
      primary key (user_id, key))"
   ;; a projection is newsroom's own when it has no source, else the
   ;; expectation an outlet put forward; origin and ref are where it was
   ;; read from, a day's briefing or a week's or month's digest
   "projections" "create table if not exists projections (
      id integer primary key,
      user_id integer not null,
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
   ;; a day's podcast: the episode as MP3, and its script as EDN,
   ;; [{:speaker :text}]
   "narrations" "create table if not exists narrations (
      user_id integer not null,
      day text not null,
      mp3 blob not null,
      transcript text,
      seconds real,
      model text,
      provider text,
      created_at text not null,
      primary key (user_id, day))"
   "retrospectives" "create table if not exists retrospectives (
      user_id integer not null,
      kind text not null,
      period text not null,
      summary text,
      lessons text not null,
      model text,
      provider text,
      created_at text not null,
      primary key (user_id, kind, period))"
   ;; the desk's runs, digests and follow-ups and the podcasts, kept so a
   ;; restart can show how each reader's last one went and pick up the ones
   ;; it cut short: kind run, digest, followups or podcast; args and status
   ;; as edn; state queued, running, done, failed, cancelled or interrupted
   "jobs" "create table if not exists jobs (
      id integer primary key autoincrement,
      user_id integer not null,
      kind text not null,
      args text not null,
      origin text not null,
      state text not null,
      status text,
      started_at text not null,
      finished_at text)"
   ;; what is left to do for a day or digest stored before its follow-up
   ;; steps (the notes, the projections, ...) were done, with what each
   ;; step needs, as an edn vector of [step args] in order; written with
   ;; the day, so a restart can finish them
   "followups" "create table if not exists followups (
      user_id integer not null,
      kind text not null,
      ref text not null,
      steps text not null,
      created_at text not null,
      primary key (user_id, kind, ref))"))

(def ^:private shared-schema
  "The readers, their sessions, and what is shared between them."
  ["create table if not exists users (
      id integer primary key,
      issuer text not null,
      subject text not null,
      name text,
      email text,
      created_at text not null,
      seen_at text,
      unique (issuer, subject))"
   ;; a session's id is the sha-256 of the cookie that names it, so the
   ;; database never holds what signs a browser in
   "create table if not exists sessions (
      id text primary key,
      user_id integer not null,
      id_token text,
      created_at integer not null,
      expires_at integer not null)"
   ;; key is the sha-256 of what the source asks for and the day; name is
   ;; what the items were credited to when it was fetched
   "create table if not exists shared_fetches (
      key text primary key,
      name text,
      items text not null,
      fetched_at integer not null)"
   "create table if not exists shared_articles (
      url text primary key,
      body text not null,
      fetched_at integer not null)"
   "create table if not exists shared_vectors (
      hash text primary key,
      vector text not null,
      used_at integer not null)"
   ;; a model's answer, by the hash of exactly what it was asked
   "create table if not exists shared_replies (
      hash text primary key,
      reply text not null,
      saved_at integer not null)"])

(def ^:private search-schema
  ;; ref is what a document is replaced and deleted by: the day for a
  ;; briefing and its sources, kind/period for a digest, the story for a
  ;; note and its facts. The index is one for every reader, each document
  ;; under its reader's user_id.
  ["create table if not exists search_docs (
      id integer primary key,
      user_id integer not null,
      kind text not null,
      ref text not null,
      day text,
      href text not null,
      url text,
      cited integer not null default 0,
      title text,
      body text)"
   "create index if not exists search_docs_ref on search_docs (user_id, kind, ref)"
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

(def ^:private search-drops
  ["drop trigger if exists search_docs_ai"
   "drop trigger if exists search_docs_ad"
   "drop table if exists search_vocab"
   "drop table if exists search_fts"
   "drop table if exists search_words"
   "drop table if exists search_docs"])

(def ^:private indexes
  ["create index if not exists sources_story on sources (user_id, story)"
   "create index if not exists day_trends_thread on day_trends (user_id, thread)"
   "create index if not exists entity_mentions_entity on entity_mentions (user_id, entity, day)"
   "create index if not exists coverage_story on coverage (user_id, story)"
   "create index if not exists projections_due on projections (user_id, status, due)"
   "create index if not exists projections_ref on projections (user_id, origin, ref)"
   "create index if not exists sessions_user on sessions (user_id)"
   "create index if not exists jobs_user on jobs (user_id, kind, id)"])

(defn- add-column!
  "An ALTER TABLE for a column an older database may not have, silently
  skipped when it does."
  [conn table column ddl]
  (try (jdbc/execute! conn (str "alter table " table " add column " column " " ddl))
       (catch Exception _ nil)))

(defn- columns
  "The names of `table`'s columns, empty when there is no such table."
  [conn table]
  (mapv :name (jdbc/fetch conn (str "pragma table_info(" table ")"))))

(defn- adopt-legacy!
  "Give what a database from before there were readers holds to the local
  reader: each reader's table without a user_id is made again with one,
  its rows the local reader's, and the search index is dropped, to be
  built again. A column the old table lacks takes its default, as the
  ALTER TABLEs of older versions gave it."
  [conn]
  (let [legacy (filterv (fn [t] (let [cs (columns conn t)]
                                  (and (seq cs) (not (some #{"user_id"} cs)))))
                        (keys tables))]
    (when (seq legacy)
      (jdbc/atomic conn
        (doseq [stmt search-drops] (jdbc/execute! conn stmt))
        (doseq [t legacy]
          (let [old (columns conn t)]
            (jdbc/execute! conn (str "alter table " t " rename to " t "_legacy"))
            (jdbc/execute! conn (get tables t))
            (let [cols (str/join ", " (filter (set (columns conn t)) old))]
              (jdbc/execute! conn (str "insert into " t " (user_id, " cols ") select "
                                       local-user-id ", " cols " from " t "_legacy")))
            (jdbc/execute! conn (str "drop table " t "_legacy"))))))
    legacy))

(defn- coverage-row [day cited {:keys [n story] :as source}]
  ;; a precedent was background, a gap search's result was found for a
  ;; story rather than reported on it, and an analysis argues about one
  (when-not (or (:precedent source) (:gap source) (= :analysis (:kind source)))
    [day n story (news/outlets source) (if (contains? cited n) 1 0)]))

(defn- insert-coverage! [conn uid rows]
  (doseq [row (remove nil? rows)]
    (jdbc/execute! conn (into ["insert or replace into coverage (user_id, day, n, story, outlets, cited)
                                values (?, ?, ?, ?, ?, ?)"
                               uid]
                              row))))

(defn- backfill-coverage!
  "Coverage for the sources stored before it was kept, once, for each
  reader who has sources and no coverage."
  [conn]
  (doseq [{uid :user_id} (jdbc/fetch conn "select distinct user_id from sources
                                           where user_id not in (select distinct user_id from coverage)")]
    (jdbc/atomic conn
      (insert-coverage! conn uid
                        (for [r (jdbc/fetch conn ["select day, n, url, source, summary, also, story, cited, precedent
                                                   from sources where user_id = ?" uid])
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
  "Take the documents of `kinds` the reader `uid` keeps under `ref` out of
  the index."
  [conn uid kinds ref]
  (jdbc/execute! conn (into [(str "delete from search_docs where user_id = ? and ref = ? and kind in ("
                                  (str/join ", " (repeat (count kinds) "?")) ")")
                             uid ref]
                            kinds)))

(defn- index! [conn uid docs]
  (doseq [{:keys [kind ref day href url cited? title body]} docs]
    (jdbc/execute! conn ["insert into search_docs (user_id, kind, ref, day, href, url, cited, title, body)
                          values (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                         uid kind ref day href url (if cited? 1 0) title body])))

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
  "Index everything kept before the search was, or before the index was
  made again, once."
  [conn]
  (when (and (nil? (jdbc/fetch-one conn "select 1 as x from search_docs limit 1"))
             (or (jdbc/fetch-one conn "select 1 as x from briefings limit 1")
                 (jdbc/fetch-one conn "select 1 as x from digests limit 1")
                 (jdbc/fetch-one conn "select 1 as x from story_notes limit 1")))
    (jdbc/atomic conn
      (doseq [{uid :user_id :keys [day markdown]} (jdbc/fetch conn "select user_id, day, markdown from briefings")]
        (let [rows (jdbc/fetch conn ["select n, title, url, summary, note, gap, cited from sources
                                      where user_id = ? and day = ?" uid day])]
          (index! conn uid (day-docs day markdown rows (set (map :n (filter #(= 1 (:cited %)) rows)))))))
      (doseq [{uid :user_id :keys [kind period markdown]} (jdbc/fetch conn "select user_id, kind, period, markdown from digests")]
        (index! conn uid (digest-docs kind period markdown)))
      (doseq [r (jdbc/fetch conn "select user_id, story, title, summary, facts, last_day from story_notes")]
        (index! conn (:user_id r) (note-docs (:story r) {:title (:title r) :summary (:summary r) :last-day (:last_day r)
                                                         :facts (edn/read-string (:facts r))}))))))

(defn- now [] (str (java.time.Instant/now)))

(defn- now-ms [] (System/currentTimeMillis))

(defn open
  "A store on the sqlite database at `uri` (a path, or sqlite::memory:),
  the local reader's; see `for-user`."
  [uri]
  (let [conn (jdbc/connection (if (re-find #"^sqlite:" uri) uri (str "sqlite:" uri)))]
    ;; databases from before these columns have no place for them
    (add-column! conn "briefings" "tldr" "text")
    (doseq [[column ddl] [["also" "text"] ["vector" "text"] ["story" "text"] ["weight" "real"]
                          ["precedent" "integer not null default 0"] ["note" "text"] ["informs" "text"]
                          ["gap" "text"] ["kind" "text"]]]
      (add-column! conn "sources" column ddl))
    (add-column! conn "story_notes" "status" "text")
    (add-column! conn "day_trends" "level" "text")
    (adopt-legacy! conn)
    (doseq [stmt (concat (vals tables) shared-schema)] (jdbc/execute! conn stmt))
    (jdbc/execute! conn ["insert or ignore into users (id, issuer, subject, name, created_at) values (?, '', 'local', ?, ?)"
                         local-user-id "You" (now)])
    (doseq [stmt indexes] (jdbc/execute! conn stmt))
    (backfill-coverage! conn)
    (doseq [stmt search-schema] (jdbc/execute! conn stmt))
    (backfill-search! conn)
    (jdbc/execute! conn "insert or ignore into standfirsts (user_id, day, tldr)
                         select user_id, day, tldr from briefings where tldr is not null")
    {:conn conn :lock (Object.) :vocab (atom nil) :user local-user-id}))

(defn close [{:keys [conn]}] (.close conn))

(defn user-id
  "The reader `store` is scoped to."
  [store]
  (or (:user store) local-user-id))

(defn for-user
  "`store` scoped to the reader `id`: everything read and written through
  it is theirs. It shares the connection, and the shared tables, with
  every other reader's."
  [store id]
  (assoc store :user (long id)))

(defmacro ^:private with-db [[conn store] & body]
  `(locking (:lock ~store)
     (let [~conn (:conn ~store)] ~@body)))

(defn- stale!
  "Forget the vocabulary read for searching, once the index has changed."
  [store]
  (reset! (:vocab store) nil))

;; --- readers and sessions ------------------------------------------------------------

(defn- row->user [r]
  {:id (long (:id r)) :issuer (:issuer r) :subject (:subject r) :name (:name r) :email (:email r)
   :created-at (:created_at r) :seen-at (:seen_at r)})

(defn ensure-user!
  "The id of the reader the sign-in of `subject` at `issuer` names, added
  when they are new; their :name and :email are kept up to date."
  [store {:keys [issuer subject name email]}]
  (with-db [conn store]
    (jdbc/execute! conn ["insert into users (issuer, subject, name, email, created_at, seen_at)
                          values (?, ?, ?, ?, ?, ?)
                          on conflict(issuer, subject) do update set
                            name = coalesce(excluded.name, name),
                            email = coalesce(excluded.email, email),
                            seen_at = excluded.seen_at"
                         issuer subject name email (now) (now)])
    (long (:id (jdbc/fetch-one conn ["select id from users where issuer = ? and subject = ?" issuer subject])))))

(defn user
  "The reader `id`, {:id :issuer :subject :name :email :created-at
  :seen-at}, or nil."
  [store id]
  (with-db [conn store]
    (some-> (jdbc/fetch-one conn ["select * from users where id = ?" id]) row->user)))

(defn users
  "Every reader, the local one first."
  [store]
  (with-db [conn store]
    (mapv row->user (jdbc/fetch conn "select * from users order by id"))))

(defn save-session!
  "Keep the session `id`, the sha-256 of its cookie, for the reader
  `user-id` until `expires-at`, epoch ms, with the :id-token the sign-in
  gave, which signing out hands back to the server."
  [store {:keys [id user-id id-token expires-at]}]
  (with-db [conn store]
    (jdbc/execute! conn ["insert or replace into sessions (id, user_id, id_token, created_at, expires_at)
                          values (?, ?, ?, ?, ?)"
                         id user-id id-token (now-ms) expires-at]))
  nil)

(defn session
  "The session `id` while it lasts, {:id :user-id :id-token :expires-at},
  else nil."
  [store id]
  (with-db [conn store]
    (when-let [r (jdbc/fetch-one conn ["select * from sessions where id = ? and expires_at > ?" id (now-ms)])]
      {:id (:id r) :user-id (long (:user_id r)) :id-token (:id_token r) :expires-at (long (:expires_at r))})))

(defn delete-session! [store id]
  (with-db [conn store]
    (jdbc/execute! conn ["delete from sessions where id = ?" id]))
  nil)

;; --- jobs and follow-ups ----------------------------------------------------------------
;; What the desk and the podcast worker are doing or did last, per reader, so
;; that a restart finds it: see newsroom.pipeline/resume!.

(def ^:private jobs-kept
  "How many of a reader's jobs are kept, the newest."
  100)

(defn add-job!
  "Record a job the reader is starting or queueing: {:kind :args :origin
  :state :status}, kind and origin keywords, args and status data.
  Returns its id. Only the newest jobs-kept of the reader's are kept."
  [store {:keys [kind args origin state status]}]
  (let [uid (user-id store)]
    (with-db [conn store]
      (jdbc/atomic conn
        (jdbc/execute! conn ["insert into jobs (user_id, kind, args, origin, state, status, started_at)
                              values (?, ?, ?, ?, ?, ?, ?)"
                             uid (name kind) (pr-str args) (name origin) (name state)
                             (some-> status pr-str) (now)])
        (let [id (long (:id (jdbc/fetch-one conn "select last_insert_rowid() as id")))]
          (jdbc/execute! conn ["delete from jobs where user_id = ? and id not in
                                  (select id from jobs where user_id = ? order by id desc limit ?)"
                               uid uid jobs-kept])
          id)))))

(defn update-job!
  "Change the job `id`'s :state and :status, either; a :state other than
  queued or running marks it finished."
  [store id {:keys [state status]}]
  (with-db [conn store]
    (when state
      (jdbc/execute! conn ["update jobs set state = ?,
                              finished_at = case when ? in ('queued', 'running') then null else ? end
                            where id = ?"
                           (name state) (name state) (now) id]))
    (when status
      (jdbc/execute! conn ["update jobs set status = ? where id = ?" (pr-str status) id])))
  nil)

(defn- row->job [r]
  (when r
    {:id (long (:id r)) :kind (keyword (:kind r)) :args (edn/read-string (:args r))
     :origin (keyword (:origin r)) :state (keyword (:state r))
     :status (some-> (:status r) edn/read-string)
     :started-at (:started_at r) :finished-at (:finished_at r)}))

(defn latest-job
  "The reader's newest job of one of `kinds`, keywords, or nil."
  [store kinds]
  (let [kinds (map name kinds)]
    (with-db [conn store]
      (row->job (jdbc/fetch-one conn (into [(str "select * from jobs where user_id = ? and kind in ("
                                                 (str/join ", " (repeat (count kinds) "?"))
                                                 ") order by id desc limit 1")
                                            (user-id store)]
                                           kinds))))))

(defn open-jobs
  "The reader's jobs still queued or running, oldest first: after a
  restart, the ones it cut short."
  [store]
  (with-db [conn store]
    (mapv row->job (jdbc/fetch conn ["select * from jobs where user_id = ? and state in ('queued', 'running')
                                      order by id" (user-id store)]))))

(defn- put-followups!
  "Record, on `conn` in its transaction, `steps` still to do for `kind`
  `ref`, replacing any it had; none clears them."
  [conn uid kind ref steps]
  (jdbc/execute! conn ["delete from followups where user_id = ? and kind = ? and ref = ?" uid kind ref])
  (when (seq steps)
    (jdbc/execute! conn ["insert into followups (user_id, kind, ref, steps, created_at) values (?, ?, ?, ?, ?)"
                         uid kind ref (pr-str (vec steps)) (now)])))

(defn followups
  "The reader's days and digests with steps still to do, oldest first:
  [{:kind \"day\" :ref \"2026-09-30\" :steps [[step args] ...]}]."
  [store]
  (with-db [conn store]
    (mapv (fn [r] {:kind (:kind r) :ref (:ref r) :steps (edn/read-string (:steps r))})
          (jdbc/fetch conn ["select kind, ref, steps from followups where user_id = ? order by created_at"
                            (user-id store)]))))

(defn set-followups!
  "Keep `steps` as what is still to do for `kind` `ref`; none clears it."
  [store kind ref steps]
  (with-db [conn store]
    (jdbc/atomic conn
      (put-followups! conn (user-id store) kind ref steps)))
  nil)

;; --- what readers share --------------------------------------------------------------

(defn shared-fetch
  "What a source gave when it was fetched for `key` no more than
  `max-age-ms` ago, {:name :items :fetched-at}, :name what the items were
  credited to then; nil when nothing that recent is kept."
  [store key max-age-ms]
  (with-db [conn store]
    (when-let [r (jdbc/fetch-one conn ["select name, items, fetched_at from shared_fetches
                                        where key = ? and fetched_at >= ?"
                                       key (- (now-ms) max-age-ms)])]
      {:name (:name r) :items (edn/read-string (:items r)) :fetched-at (long (:fetched_at r))})))

(defn save-shared-fetch!
  "Keep the `items` a source gave for `key`, credited to `name`, for the
  readers whose sources ask for the same."
  [store key name items]
  (with-db [conn store]
    (jdbc/execute! conn ["insert or replace into shared_fetches (key, name, items, fetched_at) values (?, ?, ?, ?)"
                         key name (pr-str (vec items)) (now-ms)]))
  nil)

(defn shared-article
  "The text of the article at `url`, when it was read no more than
  `max-age-ms` ago, else nil."
  [store url max-age-ms]
  (with-db [conn store]
    (:body (jdbc/fetch-one conn ["select body from shared_articles where url = ? and fetched_at >= ?"
                                 url (- (now-ms) max-age-ms)]))))

(defn save-shared-article! [store url text]
  (with-db [conn store]
    (jdbc/execute! conn ["insert or replace into shared_articles (url, body, fetched_at) values (?, ?, ?)"
                         url text (now-ms)]))
  nil)

(defn shared-vectors
  "The embeddings kept for `hashes`, {hash vector}; a hash with none is
  missing."
  [store hashes]
  (let [hashes (vec (distinct hashes))]
    (if (empty? hashes)
      {}
      (with-db [conn store]
        (let [marks (str/join ", " (repeat (count hashes) "?"))
              found (into {} (map (fn [r] [(:hash r) (edn/read-string (:vector r))]))
                          (jdbc/fetch conn (into [(str "select hash, vector from shared_vectors where hash in (" marks ")")]
                                                 hashes)))]
          (when (seq found)
            (jdbc/execute! conn (into [(str "update shared_vectors set used_at = ? where hash in (" marks ")") (now-ms)]
                                      (keys found))))
          found)))))

(defn save-shared-vectors!
  "Keep embeddings, {hash vector}."
  [store vectors]
  (with-db [conn store]
    (jdbc/atomic conn
      (doseq [[h v] vectors]
        (jdbc/execute! conn ["insert or replace into shared_vectors (hash, vector, used_at) values (?, ?, ?)"
                             h (pr-str (vec v)) (now-ms)]))))
  nil)

(defn saved-reply
  "The model's answer kept under `hash`, when it was kept less than
  `max-age-ms` ago, else nil."
  [store hash max-age-ms]
  (with-db [conn store]
    (some-> (jdbc/fetch-one conn ["select reply from shared_replies where hash = ? and saved_at > ?"
                                  hash (- (now-ms) max-age-ms)])
            :reply
            edn/read-string)))

(defn save-reply!
  "Keep the model's answer `reply` under `hash`, replacing any there was."
  [store hash reply]
  (with-db [conn store]
    (jdbc/execute! conn ["insert or replace into shared_replies (hash, reply, saved_at) values (?, ?, ?)"
                         hash (pr-str reply) (now-ms)]))
  nil)

(defn prune-shared!
  "Drop what is shared that is older than `max-age-ms`, an embedding by
  when it was last used, a model's answer by when it was kept, and the
  sessions that have expired."
  [store max-age-ms]
  (let [before (- (now-ms) max-age-ms)]
    (with-db [conn store]
      (jdbc/atomic conn
        (jdbc/execute! conn ["delete from shared_fetches where fetched_at < ?" before])
        (jdbc/execute! conn ["delete from shared_articles where fetched_at < ?" before])
        (jdbc/execute! conn ["delete from shared_vectors where used_at < ?" before])
        (jdbc/execute! conn ["delete from shared_replies where saved_at < ?" before])
        (jdbc/execute! conn ["delete from sessions where expires_at <= ?" (now-ms)]))))
  nil)

;; --- days --------------------------------------------------------------------------

(declare put-followups!)

(defn save-day!
  "Store a day's briefing and its sources, replacing whatever the day had.
  `followups`, when given, are the steps still to do for it (see
  put-followups!), stored in the same transaction, so a day is never kept
  without a record of what it still needs."
  [store {:keys [day sources cited markdown model provider tldr followups]}]
  (let [cited (set cited)
        uid (user-id store)]
    (with-db [conn store]
      (jdbc/atomic conn
        (jdbc/execute! conn ["delete from sources where user_id = ? and day = ?" uid day])
        (jdbc/execute! conn ["delete from coverage where user_id = ? and day = ?" uid day])
        (jdbc/execute! conn ["delete from briefings where user_id = ? and day = ?" uid day])
        ;; a podcast of the briefing replaced no longer tells it
        (jdbc/execute! conn ["delete from narrations where user_id = ? and day = ?" uid day])
        (insert-coverage! conn uid (map #(coverage-row day cited %) sources))
        (jdbc/execute! conn ["delete from standfirsts where user_id = ? and day = ?" uid day])
        (when tldr (jdbc/execute! conn ["insert into standfirsts (user_id, day, tldr) values (?, ?, ?)" uid day tldr]))
        (jdbc/execute! conn ["insert into briefings (user_id, day, markdown, model, provider, tldr, created_at)
                              values (?, ?, ?, ?, ?, ?, ?)"
                             uid day markdown model provider tldr (now)])
        (doseq [{:keys [n title url source summary published also story weight note informs gap kind]
                 v :vector precedent? :precedent} sources]
          (jdbc/execute! conn ["insert into sources (user_id, day, n, title, url, source, summary, published, also,
                                                     vector, story, weight, precedent, note, informs, gap, kind, cited)
                                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                               uid day n title url source summary published
                               (when (seq also) (pr-str also))
                               (embed/encode v) story weight (if precedent? 1 0)
                               note (when (seq informs) (pr-str (vec informs))) gap
                               (when (and kind (not= :news kind)) (name kind))
                               (if (contains? cited n) 1 0)]))
        (unindex! conn uid ["day" "source"] day)
        (index! conn uid (day-docs day markdown sources cited))
        (put-followups! conn uid "day" day followups)))
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
    (:kind r) (assoc :kind (keyword (:kind r)))
    (:weight r) (assoc :weight (double (:weight r)))))

(defn day
  "The stored day, or nil: {:day :markdown :model :provider :created-at
  :sources :cited}."
  [store day]
  (let [uid (user-id store)]
    (with-db [conn store]
      (when-let [b (jdbc/fetch-one conn ["select * from briefings where user_id = ? and day = ?" uid day])]
        (let [rows (jdbc/fetch conn ["select * from sources where user_id = ? and day = ? order by n" uid day])]
          {:day day
           :markdown (:markdown b)
           :model (:model b)
           :provider (:provider b)
           :tldr (:tldr b)
           :created-at (:created_at b)
           :sources (mapv row->source rows)
           :cited (mapv row->source (filter #(= 1 (:cited %)) rows))})))))

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
           (jdbc/fetch conn [(str "select day, n, title, url, source, summary, published, also, story, weight, precedent, note, informs, gap, kind, cited"
                                  (when vectors? ", vector")
                                  " from sources where user_id = ? and day >= ? and day <= ? order by day, n")
                             (user-id store) from to])))))

(defn archive
  "The days that have a briefing, newest first, each with its standfirst
  when it has one: for the sidebar's history list."
  [store]
  (with-db [conn store]
    (mapv (fn [r] {:day (:day r) :tldr (:tldr r)})
          (jdbc/fetch conn ["select day, tldr from briefings where user_id = ? order by day desc" (user-id store)]))))

(defn record-source-health!
  "Roll each source's latest fetch up into its health: a failure bumps the
  consecutive count and remembers why, a success clears both and stamps
  when it last worked. A source the run didn't fetch, one taken out of the
  config, is forgotten."
  [store results]
  (let [uid (user-id store)]
    (with-db [conn store]
      (when (seq results)
        (jdbc/execute! conn (into [(str "delete from source_health where user_id = ? and name not in ("
                                        (str/join ", " (repeat (count results) "?")) ")")
                                   uid]
                                  (map :source results))))
      (doseq [{:keys [source error]} results]
        (if error
          (jdbc/execute! conn ["insert into source_health (user_id, name, consecutive_failures, last_error)
                                values (?, ?, 1, ?)
                                on conflict(user_id, name) do update set
                                  consecutive_failures = consecutive_failures + 1,
                                  last_error = excluded.last_error"
                               uid source error])
          (jdbc/execute! conn ["insert into source_health (user_id, name, last_ok, consecutive_failures)
                                values (?, ?, ?, 0)
                                on conflict(user_id, name) do update set
                                  last_ok = excluded.last_ok,
                                  consecutive_failures = 0,
                                  last_error = null"
                               uid source (now)]))))))

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
          (jdbc/fetch conn ["select name, last_ok, consecutive_failures, last_error
                             from source_health where user_id = ? order by name"
                            (user-id store)]))))

(defn days
  "Every day with a briefing, newest first."
  [store]
  (with-db [conn store]
    (mapv :day (jdbc/fetch conn ["select day from briefings where user_id = ? order by day desc" (user-id store)]))))

(defn coverage-between
  "The coverage kept for the days from `from` to `to`, both inclusive:
  {:day :n :story :outlets :cited?} for each source, which outlives the
  sources themselves."
  [store from to]
  (with-db [conn store]
    (mapv (fn [r] {:day (:day r) :n (long (:n r)) :story (:story r)
                   :outlets (long (:outlets r)) :cited? (= 1 (:cited r))})
          (jdbc/fetch conn ["select * from coverage where user_id = ? and day >= ? and day <= ? order by day, n"
                            (user-id store) from to]))))

(defn days-without-storylines
  "The days from `from` to `to` with sources stored before storylines were
  kept, oldest first."
  [store from to]
  (with-db [conn store]
    (mapv :day (jdbc/fetch conn ["select distinct day from sources
                                  where user_id = ? and story is null and precedent = 0 and day >= ? and day <= ?
                                  order by day"
                                 (user-id store) from to]))))

(defn set-storylines!
  "Store the storyline and embedding of each of a day's `sources`, in the
  sources and in their coverage."
  [store day sources]
  (let [uid (user-id store)]
    (with-db [conn store]
      (jdbc/atomic conn
        (doseq [{:keys [n story] v :vector} sources]
          (jdbc/execute! conn ["update sources set story = ?, vector = coalesce(?, vector)
                                where user_id = ? and day = ? and n = ?"
                               story (embed/encode v) uid day n])
          (jdbc/execute! conn ["update coverage set story = ? where user_id = ? and day = ? and n = ?"
                               story uid day n])))))
  nil)

(defn standfirsts-between
  "The standfirsts of the days from `from` to `to`, both inclusive, as
  {:day :tldr}: kept as long as the coverage."
  [store from to]
  (with-db [conn store]
    (mapv (fn [r] {:day (:day r) :tldr (:tldr r)})
          (jdbc/fetch conn ["select day, tldr from standfirsts where user_id = ? and day >= ? and day <= ? order by day"
                            (user-id store) from to]))))

(defn briefings-between
  "The briefings of the days from `from` to `to`, both inclusive, oldest
  first, as {:day :markdown}: only the days :keep-days hasn't dropped."
  [store from to]
  (with-db [conn store]
    (mapv (fn [r] {:day (:day r) :markdown (:markdown r)})
          (jdbc/fetch conn ["select day, markdown from briefings where user_id = ? and day >= ? and day <= ? order by day"
                            (user-id store) from to]))))

(defn- version! [conn uid story day {:keys [title summary status facts first-day]} replace?]
  (jdbc/execute! conn [(str "insert or " (if replace? "replace" "ignore") " into story_note_versions
                               (user_id, story, day, title, summary, status, facts, first_day, updated_at)
                             values (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                       uid story day title summary status (pr-str (vec facts)) first-day (now)]))

(declare row->note)

(defn save-notes!
  "Store storyline notes, {story note}, each replacing the story's last,
  and kept as of its :last-day, the day that wrote it, so that a run of
  that day again reads the note as it was before (see notes-before). A
  note written before its history was kept is kept as of its own last day
  first. An earlier day run again leaves a note a later day has updated
  as it is."
  [store notes]
  (let [uid (user-id store)]
    (with-db [conn store]
      (jdbc/atomic conn
        (doseq [[story {:keys [title summary status facts first-day last-day] :as note}] notes
                :let [current (some-> (jdbc/fetch-one conn ["select * from story_notes where user_id = ? and story = ?"
                                                            uid story])
                                      row->note)]
                :when (or (nil? current) (not (pos? (compare (:last-day current) last-day))))]
          (when current (version! conn uid story (:last-day current) current false))
          (version! conn uid story last-day note true)
          (jdbc/execute! conn ["insert or replace into story_notes
                                  (user_id, story, title, summary, status, facts, first_day, last_day, updated_at)
                                values (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                               uid story title summary status (pr-str (vec facts)) first-day last-day (now)])
          (unindex! conn uid ["story" "fact"] story)
          (index! conn uid (note-docs story {:title title :summary summary :facts facts :last-day last-day}))))))
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
    (mapv row->note (jdbc/fetch conn ["select * from story_notes where user_id = ? order by last_day desc, story"
                                      (user-id store)]))))

(defn recent-stories
  "The `n` storylines with the latest notes, {:story :title :last-day},
  for the sidebar, without reading their facts."
  [store n]
  (with-db [conn store]
    (mapv (fn [r] {:story (:story r) :title (:title r) :last-day (:last_day r)})
          (jdbc/fetch conn ["select story, title, last_day from story_notes where user_id = ?
                             order by last_day desc, story limit ?"
                            (user-id store) n]))))

(defn story-coverage
  "A storyline's coverage, day by day: {:day :outlets :sources :cited?},
  its outlets summed over its sources that day, as long as it is kept."
  [store story]
  (with-db [conn store]
    (mapv (fn [r] {:day (:day r) :outlets (long (:outlets r)) :sources (long (:sources r))
                   :cited? (pos? (:cited r))})
          (jdbc/fetch conn ["select day, sum(outlets) as outlets, count(*) as sources, max(cited) as cited
                             from coverage where user_id = ? and story = ? group by day order by day"
                            (user-id store) story]))))

(defn notes
  "The notes kept on `stories`, {story {:story :title :summary :facts
  :first-day :last-day}}; a story with no note is missing."
  [store stories]
  (if (empty? stories)
    {}
    (with-db [conn store]
      (into {}
            (map (fn [r] [(:story r) (row->note r)]))
            (jdbc/fetch conn (into [(str "select * from story_notes where user_id = ? and story in ("
                                         (str/join ", " (repeat (count (distinct stories)) "?")) ")")
                                    (user-id store)]
                                   (distinct stories)))))))

(defn notes-before
  "The notes on `stories` as they were before `day`'s run updated them,
  {story note}: what a run of `day` builds on, so that running it again
  doesn't take its own notes for earlier briefings'. A note kept from
  before its history was is taken without the facts of `day` and later; a
  story with no note before `day` is missing."
  [store stories day]
  (if (empty? stories)
    {}
    (let [stories (distinct stories)
          marks (str/join ", " (repeat (count stories) "?"))
          uid (user-id store)]
      (with-db [conn store]
        (let [versions (jdbc/fetch conn (into [(str "select * from story_note_versions where user_id = ? and day < ?
                                                     and story in (" marks ") order by day")
                                               uid day]
                                              stories))
              latest (into {} (map (fn [r] [(:story r) (row->note (assoc r :last_day (:day r)))])) versions)
              current (into {} (map (fn [r] [(:story r) (row->note r)]))
                            (jdbc/fetch conn (into [(str "select * from story_notes where user_id = ? and story in ("
                                                         marks ")")
                                                    uid]
                                                   stories)))]
          (into {}
                (keep (fn [story]
                        (let [{:keys [last-day] :as note} (get current story)]
                          (cond
                            (nil? note) nil
                            (neg? (compare last-day day)) [story note]
                            (contains? latest story) [story (get latest story)]
                            :else (let [facts (filterv #(neg? (compare (str (:day %)) day)) (:facts note))]
                                    (when (seq facts)
                                      [story (assoc note :facts facts :last-day (:day (peek facts)))]))))))
                stories))))))

(def coverage-days
  "How many days longer than the days themselves their coverage is kept:
  four months and a few days, a month's digest's baseline."
  124)

(defn prune!
  "Delete every day but the newest `n`, with its sources and its podcast,
  and return the days deleted, newest first; their coverage goes once it
  is `coverage-days` days older still. A negative `n` keeps everything.
  The file is vacuumed after a deletion so the space goes back to the
  disk."
  [store n]
  (if (neg? n)
    []
    (let [uid (user-id store)
          gone (with-db [conn store]
                 (let [gone (mapv :day (jdbc/fetch conn ["select day from briefings where user_id = ?
                                                          order by day desc limit -1 offset ?"
                                                         uid n]))
                       oldest "(select min(day) from coverage where user_id = ?)"]
                   (when (seq gone)
                     (jdbc/atomic conn
                       (doseq [d gone]
                         (unindex! conn uid ["day" "source"] d)
                         (jdbc/execute! conn ["delete from sources where user_id = ? and day = ?" uid d])
                         (jdbc/execute! conn ["delete from briefings where user_id = ? and day = ?" uid d]))
                       (jdbc/execute! conn ["delete from narrations where user_id = ?
                                               and day not in (select day from briefings where user_id = ?)"
                                            uid uid])
                       (jdbc/execute! conn ["delete from coverage where user_id = ? and day not in
                                               (select distinct day from coverage where user_id = ?
                                                order by day desc limit ?)"
                                            uid uid (+ n coverage-days)])
                       ;; what outlives the days goes with the coverage
                       (jdbc/execute! conn [(str "delete from standfirsts where user_id = ? and day < " oldest) uid uid])
                       (jdbc/execute! conn [(str "delete from day_trends where user_id = ? and day < " oldest) uid uid])
                       (jdbc/execute! conn [(str "delete from entity_mentions where user_id = ? and day < " oldest) uid uid])
                       (jdbc/execute! conn [(str "delete from search_docs where user_id = ? and kind in ('story', 'fact')
                                                  and ref in (select story from story_notes
                                                              where user_id = ? and last_day < " oldest ")")
                                            uid uid uid])
                       (jdbc/execute! conn [(str "delete from story_notes where user_id = ? and last_day < " oldest)
                                            uid uid])
                       (jdbc/execute! conn [(str "delete from story_note_versions where user_id = ? and day < " oldest)
                                            uid uid])))
                   gone))]
      (when (seq gone)
        (stale! store)
        (with-db [conn store] (jdbc/execute! conn "vacuum")))
      gone)))

;; --- podcasts --------------------------------------------------------------------

(defn save-narration!
  "Store the podcast of `day`, its :mp3 bytes and its :transcript,
  [{:speaker :text}], with its length in :seconds and the :model and
  :provider that wrote it, replacing the day's last."
  [store {:keys [day mp3 transcript seconds model provider]}]
  (with-db [conn store]
    (jdbc/execute! conn ["insert or replace into narrations (user_id, day, mp3, transcript, seconds, model, provider, created_at)
                          values (?, ?, ?, ?, ?, ?, ?, ?)"
                         (user-id store) day mp3 (pr-str (vec transcript)) (some-> seconds double) model provider (now)]))
  nil)

(defn narration
  "The podcast of `day` without its audio, or nil: {:day :transcript
  :seconds :model :provider :created-at}."
  [store day]
  (with-db [conn store]
    (when-let [r (jdbc/fetch-one conn ["select day, transcript, seconds, model, provider, created_at
                                        from narrations where user_id = ? and day = ?"
                                       (user-id store) day])]
      {:day (:day r)
       :transcript (some-> (:transcript r) edn/read-string)
       :seconds (some-> (:seconds r) double)
       :model (:model r)
       :provider (:provider r)
       :created-at (:created_at r)})))

(defn narration-audio
  "The MP3 of `day`'s podcast as bytes, or nil."
  [store day]
  (with-db [conn store]
    (:mp3 (jdbc/fetch-one conn ["select mp3 from narrations where user_id = ? and day = ?" (user-id store) day]))))

;; --- trends ----------------------------------------------------------------------

(defn save-trends!
  "Store the trends and structural forces the desk found on `day`,
  {:thread :level :name :direction :summary :stories}, :level \"trend\"
  or \"force\", the stories by their storylines, in place of the day's
  last."
  [store day trends]
  (let [uid (user-id store)]
    (with-db [conn store]
      (jdbc/atomic conn
        (jdbc/execute! conn ["delete from day_trends where user_id = ? and day = ?" uid day])
        (doseq [[i {:keys [thread level name direction summary stories]}] (map-indexed vector trends)]
          (jdbc/execute! conn ["insert into day_trends (user_id, day, idx, thread, level, name, direction, summary, stories)
                                values (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                               uid day i thread (or level "trend") name direction summary (pr-str (vec stories))])))))
  nil)

(defn trends-between
  "The trends found on the days from `from` to `to`, both inclusive, in
  order: {:day :thread :level :name :direction :summary :stories}. They are kept
  as long as the coverage."
  [store from to]
  (with-db [conn store]
    (mapv (fn [r] {:day (:day r) :thread (:thread r) :level (or (:level r) "trend") :name (:name r)
                   :direction (:direction r)
                   :summary (:summary r) :stories (some-> (:stories r) edn/read-string)})
          (jdbc/fetch conn ["select * from day_trends where user_id = ? and day >= ? and day <= ? order by day, idx"
                            (user-id store) from to]))))

(defn save-mentions!
  "Store the entities of the day's stories, `mentions` {:day :entity :name
  :kind :story :title :importance :cited}, in place of the day's last."
  [store day mentions]
  (let [uid (user-id store)]
    (with-db [conn store]
      (jdbc/atomic conn
        (jdbc/execute! conn ["delete from entity_mentions where user_id = ? and day = ?" uid day])
        (doseq [{:keys [entity story name kind title importance cited]}
                (vals (into {} (map (juxt (juxt :entity :story) identity)) mentions))]
          (jdbc/execute! conn ["insert into entity_mentions (user_id, day, entity, story, name, kind, title, importance, cited)
                                values (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                               uid day entity story name kind title importance (if cited 1 0)])))))
  nil)

(defn mentions-between
  "The entities of the stories of the days from `from` to `to`, both
  inclusive, by day and entity: {:day :entity :name :kind :story :title
  :importance :cited}. They are kept as long as the coverage."
  [store from to]
  (with-db [conn store]
    (mapv (fn [r] {:day (:day r) :entity (:entity r) :name (:name r) :kind (:kind r) :story (:story r)
                   :title (:title r) :importance (:importance r) :cited (= 1 (:cited r))})
          (jdbc/fetch conn ["select * from entity_mentions where user_id = ? and day >= ? and day <= ?
                             order by day, entity, story"
                            (user-id store) from to]))))

;; --- digests -----------------------------------------------------------------------

(defn save-digest!
  "Store a digest and the sources it was given, replacing whatever the
  period had, with its `followups` as save-day! keeps a day's."
  [store {:keys [kind period sources cited markdown model provider tldr followups]}]
  (let [cited (set cited)
        k (name kind)
        uid (user-id store)]
    (with-db [conn store]
      (jdbc/atomic conn
        (jdbc/execute! conn ["delete from digest_sources where user_id = ? and kind = ? and period = ?" uid k period])
        (jdbc/execute! conn ["delete from digests where user_id = ? and kind = ? and period = ?" uid k period])
        (jdbc/execute! conn ["insert into digests (user_id, kind, period, markdown, model, provider, tldr, created_at)
                              values (?, ?, ?, ?, ?, ?, ?, ?)"
                             uid k period markdown model provider tldr (now)])
        (doseq [{:keys [n day title url source]} sources]
          (jdbc/execute! conn ["insert into digest_sources (user_id, kind, period, n, day, title, url, source, cited)
                                values (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                               uid k period n day title url source (if (contains? cited n) 1 0)]))
        (unindex! conn uid ["digest"] (str k "/" period))
        (index! conn uid (digest-docs k period markdown))
        (put-followups! conn uid k period followups)))
    (stale! store)
    nil))

(defn digest
  "The stored digest, or nil: {:kind :period :markdown :model :provider
  :tldr :created-at :sources}."
  [store kind period]
  (let [uid (user-id store)]
    (with-db [conn store]
      (when-let [d (jdbc/fetch-one conn ["select * from digests where user_id = ? and kind = ? and period = ?"
                                         uid (name kind) period])]
        {:kind kind
         :period period
         :markdown (:markdown d)
         :model (:model d)
         :provider (:provider d)
         :tldr (:tldr d)
         :created-at (:created_at d)
         :sources (mapv (fn [r] {:n (long (:n r)) :day (:day r) :title (:title r) :url (:url r)
                                 :source (:source r) :cited? (= 1 (:cited r))})
                        (jdbc/fetch conn ["select * from digest_sources where user_id = ? and kind = ? and period = ?
                                           order by n"
                                          uid (name kind) period]))}))))

(defn digests
  "Every digest, newest period first, each {:kind :period :tldr}."
  [store]
  (with-db [conn store]
    (mapv (fn [r] {:kind (keyword (:kind r)) :period (:period r) :tldr (:tldr r)})
          (jdbc/fetch conn ["select kind, period, tldr from digests where user_id = ? order by period desc, kind"
                            (user-id store)]))))

;; --- projections and retrospectives -----------------------------------------------

(defn save-projections!
  "Store the projections read from `origin` (\"day\", \"week\" or
  \"month\") `ref`, its day or period, in place of the ones it had."
  [store origin ref projections]
  (let [uid (user-id store)]
    (with-db [conn store]
      (jdbc/atomic conn
        (jdbc/execute! conn ["delete from projections where user_id = ? and origin = ? and ref = ?" uid origin ref])
        (doseq [{:keys [made-on claim subject story source outlet url by due]} projections]
          (jdbc/execute! conn ["insert into projections (user_id, origin, ref, made_on, claim, subject, story, source,
                                                         outlet, url, by_whom, due)
                                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                               uid origin ref made-on claim subject story source outlet url by due])))))
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
                             where user_id = ?
                               and ((status = 'open' and due <= ?) or (checked_kind = ? and checked_period = ?))
                             order by made_on, id"
                            (user-id store) to (name kind) period]))))

(defn open-projections
  "Newsroom's own projections made before `day` that no retrospective has
  settled yet, the soonest due first."
  [store day]
  (with-db [conn store]
    (mapv row->projection
          (jdbc/fetch conn ["select * from projections
                             where user_id = ? and status = 'open' and source is null and outlet is null and made_on < ?
                             order by due, made_on desc, id"
                            (user-id store) day]))))

(defn save-verdicts!
  "Store what a retrospective found of each of `projections`, by :id: its
  status, closeness, reason and due day, and which retrospective it was."
  [store projections]
  (let [uid (user-id store)]
    (with-db [conn store]
      (jdbc/atomic conn
        (doseq [{:keys [id status closeness reason due checked-kind checked-period]} projections]
          (jdbc/execute! conn ["update projections set status = ?, closeness = ?, reason = ?, due = ?,
                                  checked_kind = ?, checked_period = ?, checked_at = ?
                                where id = ? and user_id = ?"
                               status closeness reason due checked-kind checked-period (now) id uid])))))
  nil)

(defn projections-checked
  "The projections the retrospective of `kind` and `period` judged, in the
  order they were made."
  [store kind period]
  (with-db [conn store]
    (mapv row->projection
          (jdbc/fetch conn ["select * from projections where user_id = ? and checked_kind = ? and checked_period = ?
                             order by made_on, id"
                            (user-id store) (name kind) period]))))

(defn judged-projections
  "Every projection a retrospective settled, held, partly or failed, the
  latest judged first: what the standings are made from."
  [store]
  (with-db [conn store]
    (mapv row->projection
          (jdbc/fetch conn ["select * from projections where user_id = ? and status in ('held', 'partly', 'failed')
                             order by checked_at desc, id desc"
                            (user-id store)]))))

(defn save-retrospective!
  "Store a retrospective, {:kind :period :summary :lessons :model
  :provider}, replacing the period's last."
  [store {:keys [kind period summary lessons model provider]}]
  (with-db [conn store]
    (jdbc/execute! conn ["insert or replace into retrospectives (user_id, kind, period, summary, lessons, model, provider, created_at)
                          values (?, ?, ?, ?, ?, ?, ?, ?)"
                         (user-id store) (name kind) period summary (pr-str (vec lessons)) model provider (now)]))
  nil)

(defn- row->retrospective [r]
  {:kind (keyword (:kind r)) :period (:period r) :summary (:summary r)
   :lessons (edn/read-string (:lessons r)) :model (:model r) :provider (:provider r)
   :created-at (:created_at r)})

(defn retrospective
  "The retrospective of `kind` and `period`, or nil."
  [store kind period]
  (with-db [conn store]
    (some-> (jdbc/fetch-one conn ["select * from retrospectives where user_id = ? and kind = ? and period = ?"
                                  (user-id store) (name kind) period])
            row->retrospective)))

(defn latest-retrospective
  "The retrospective written last, whose lessons are the ones kept, or
  nil."
  [store]
  (with-db [conn store]
    (some-> (jdbc/fetch-one conn ["select * from retrospectives where user_id = ?
                                   order by created_at desc, rowid desc limit 1"
                                  (user-id store)])
            row->retrospective)))

;; --- settings ----------------------------------------------------------------------

(defn settings
  "The stored settings, {key value}; empty before any are saved."
  [store]
  (with-db [conn store]
    (into {}
          (map (fn [r] [(keyword (:key r)) (edn/read-string (:value r))]))
          (jdbc/fetch conn ["select key, value from settings where user_id = ?" (user-id store)]))))

(defn save-settings!
  "Store `settings`, {key value}, in place of every setting there was."
  [store settings]
  (let [uid (user-id store)]
    (with-db [conn store]
      (jdbc/atomic conn
        (jdbc/execute! conn ["delete from settings where user_id = ?" uid])
        (doseq [[k v] settings]
          (jdbc/execute! conn ["insert into settings (user_id, key, value) values (?, ?, ?)" uid (name k) (pr-str v)])))))
  nil)

;; --- search ------------------------------------------------------------------------

(defn vocabulary
  "Every word the index has as written, {word documents}, which misspelled
  words are matched against; read once until the index changes. It is
  every reader's words, which only guess at what a word was meant to be:
  what a search matches is still the reader's own."
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
                                          where search_fts match ? and d.user_id = ?"
                                         (when kind " and d.kind = ?")
                                         " order by "
                                         (when (= :newest sort) "d.day desc, ")
                                         "bm25(search_fts, 4.0, 1.0) * (case when d.cited = 1 then 1.25 else 1.0 end)
                                          limit ? offset ?")
                                    expr (user-id store)]
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
                             where search_fts match ? and d.user_id = ? group by d.kind"
                            expr (user-id store)]))))
