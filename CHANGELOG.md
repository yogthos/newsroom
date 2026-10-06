# Changelog

## Unreleased

### Changed

- **Each part of the briefing says something new.** The default prompt
  gives every part its own job: the overview names the day's main
  developments without their detail, the domain parts tell each story in
  full once and leave projections to the outlook, "How it all connects"
  points back to the events instead of retelling them, and the outlook
  starts from what comes next rather than recapping the day. The digest
  prompt gets the same rules. The critic has a new kind of problem,
  `repeated`, for a passage that retells what an earlier part already told,
  and the analyst cuts it or brings it down to a reference back. A prompt
  already saved keeps its text, so use "Reset to default" on the config
  page to pick up the new one.

## v0.8.2 — 5 October 2026

### Added

- **A critic checks each briefing.** Once the analyst has written the day's
  briefing, a critic reads it against the sources and against the material
  facts, and lists the claims that don't hold up: a plan an actor has no
  means to carry out, an interested claim taken at face value, a
  fact the sources don't carry, or reasoning that doesn't follow. When it
  finds any, the analyst rewrites the briefing with them fixed. A critic or
  a revision that fails leaves the draft as it was. `:critique-briefing`
  turns it off, a `:critic` role gives it its own model, and its prompt is
  `:critic-prompt`.

## v0.8.1 — 5 October 2026

### Added

- **Windows builds.** A release carries an x86_64 Windows archive, with the
  sqlite, libxml2, OpenSSL and zlib DLLs it loads beside `newsroom.exe`, and
  the test suite runs on Windows too. It needs Windows 10 or later, and jolt
  0.8.16 or later to build.

### Changed

- **Briefings say what's new.** The model gets the whole previous briefing
  rather than its overview, and the reports on stories that briefing cited
  are marked as already in it. It's told to give the room to what moved
  since then, keep a running story that didn't move to a line at most, and
  check yesterday's projections against what happened.
- **Digests build on what came before them.** A weekly digest reads the
  week's daily briefings and follows how events evolved across them, and a
  monthly digest reads the month's weekly digests, with the overviews of
  any days they don't cover. The digest prompt has a `{{briefings}}` place
  for them.

### Fixed

- **secrets.edn on Windows.** The check that only its owner can read the
  file is skipped where the file system has no POSIX permissions, which
  used to stop the start.

## v0.8.0 — 5 October 2026

### Added

- **A YouTube plugin.** `plugins/youtube` follows channels by handle,
  channel ID or address. When a channel has published in the last few days,
  its latest video is read for its transcript, through the same API
  YouTube's apps use, with no key. The video is an item like any other, and
  its transcript goes to the desk as its full text, so the dossier on its
  story is written from what was said rather than from the description.
- **An item's own text.** A plugin's item can carry `:text`, its full text,
  which the desk reads in place of fetching the page at its URL.
- **Plugins on the config page.** The Plugins section lists every plugin
  found at startup, the source types each adds, and why one failed to load,
  with a field for each setting a plugin declares, whether or not it has
  been set yet.
- **A source's own lookback.** `:lookback-days` on a source, or on a source
  type's shape, overrides the run's for that source's items, for a source
  that publishes every few days rather than every day.

## v0.7.0 — 4 October 2026

The briefing is no longer written from the raw reports in one pass. A desk
works the day over first, in stages, each a narrower model call: it sorts
the reports into stories, reads the main ones in full, writes a dossier on
each from its reports alone, and maps the trends running through the day
and how the stories drive one another. The analyst then writes from the
dossiers and the map, with the numbered sources to cite.

### Added

- **Stories.** `:group-stories` (on by default) has the model sort the
  day's reports into stories, the reports on one event from different sides
  together and related events apart, each with a neutral title, an
  importance from 1 to 10 and a status. A story's reports share a storyline
  and are ranked together: their outlets are summed and the weight scaled by
  the importance. Reports that aren't news at all, like index pages and
  adverts, are left out. The analyst sees a story's reports listed together
  under its title.
- **Full text.** `:read-articles` (default 3) reports of each dossier's
  story, one an outlet, are read from the outlet's page, its structured data
  or its paragraphs. A page behind a paywall keeps its feed summary.
- **Dossiers.** `:dossier-stories` (default 20) of the day's top stories get
  a dossier, written a few stories a call from their reports alone: the
  salient facts with their citations, a fact citing no report of its story
  dropped; the actors with their stated positions and their interests; the
  accounts that disagree; how outlets frame the story; what the reports
  leave out; the forces it is an instance of; and why it matters. A report a
  dossier covers comes to the analyst without its summary.
- **The map.** `:connect-stories` (on by default) has the model map, from
  the dossiers, the trends running through the day and the links of cause
  and effect between stories, each with its mechanism and how sure the
  evidence makes it. A trend continues one of the days before by name, so
  trends are followed from day to day. The briefing's graph is drawn from
  the links, a link the evidence is least sure of dashed, and put at the
  start of "How it all connects". A briefing whose prompt still asks it to
  draw its own keeps its own.
- **Trends in the research and the digests.** The researcher looking for
  precedents is given the day's trends, and the digests the trends followed
  through the period, how long each ran and which way it moved. `{{trends}}`
  places them in the precedent and digest prompts.
- **Gap searches.** The dossiers say what the reports leave out, with a
  search that could find it, and up to `:gap-searches` (default 6) of those
  searches are run. What they find joins the sources, marked with the gap it
  was found for.
- **A status on the notes.** A storyline's note says whether it is
  developing, escalating, de-escalating, concluding or static, and the notes
  are updated with the dossiers' facts.
- **A `:desk` role** gives the desk's work its own model; the analyst's when
  it's left out.
- **Resetting a prompt.** Each prompt on the config page has a button that
  fills in the default packaged with newsroom, and says whether the saved
  prompt is that default. Nothing changes until the form is saved, so a
  prompt can be looked over, or parts of an old one carried across, first.
- **secrets.edn.** Keys can be kept in `secrets.edn` in the config directory,
  a map from each key's name to its value, instead of being exported in the
  shell that starts newsroom. Whatever names a key, `:api-key-env`, a
  `${VAR}` in a provider or a plugin's settings, or a built-in name like
  `DEEPSEEK_API_KEY` or `EXA_API_KEY`, finds it there when the environment
  doesn't have it; the environment wins when both do. The first start
  writes an empty one, readable only by its owner; after that it is read
  once at startup and never reaches the database or the export. Newsroom
  won't start if users other than its owner can get at it, or if it isn't a
  map of strings, and the error doesn't repeat what's in it.

### Changed

- The default prompts ask the analyst to build on the desk's work. A prompt
  already saved in the settings is left alone: the dossiers and the map go
  just before its sources, through `{{analysis}}`, and it goes on drawing its
  own graph until it is reset to the default, or changed to use `{{graph}}`
  as the default does.
- **The item cap is per outlet.** `:max-items-per-source` now caps each
  outlet within a source, by the outlet its items are credited to, rather
  than the source as a whole. A source that reads one feed is unchanged; a
  search whose queries credit outlets, or a plugin that reads many feeds
  through one source, gives each outlet its own share instead of 12 between
  them.

## v0.6.0 — 3 October 2026

Before the analyst writes the briefing, a researcher grounds it in history.
It reads the day's stories together, with how long each has run and the
note on its storyline, to see the trends running through the day, and looks
first for past periods when a similar combination of trends came together
and how they resolved, then for earlier events like single stories. It
searches over a few rounds, reading what each round finds before it searches
again, then picks the results that show how today's situation may develop,
with a note on each: what happened, how long it took, how it resolved, and
how today differs. Its picks join the day's sources as precedents, numbered
after today's, tied to the stories they bear on, cited like any other and
stored with the day, notes and all. The analyst is told to build its
projections on them.

### Added

- **Historical precedents.** `:precedent-searches` (default 12) is the most
  searches the researcher may run, over at most four rounds; blank on the
  config page turns the research off. The precedents take no part in the
  storylines or their ranking: they are background, not stories that ran,
  and they don't count as told when the next day's stories are checked
  against the last briefings. A failed search or model call is logged and
  the day is analysed without precedents rather than lost.
- **The researcher's prompt**, `:precedent-prompt`, is edited on the config
  page with the others. Its default is `resources/defaults/precedents.md`.
- **A `:research` role** gives the research its own model; the analyst's
  when it's left out.
- **The day page** shows each precedent's note and links the stories it
  bears on.

### Changed

- **writ** is at v0.1.2 for the spec check.

- **The default prompts are stored in the database** the first time
  newsroom starts without them, so the config page holds each one to edit.
  A prompt already saved is left alone.
- **The briefing's prompt** tells the analyst to ground its projections in
  the precedents and cite them.

## v0.5.0 — 3 October 2026

Everything that can change while newsroom runs now lives in its database and
is edited on a config page, where it takes effect without a restart. Sources
are checked as they're added and read with timeouts and retries of their own.
`config.edn` keeps only where the page is served and the database kept; an
existing setup is moved over at the first start.

### Added

- **A config page.** `/config`, linked from the sidebar, shows every setting
  with a note on what it does and saves them to the database, where they take
  effect at once: the next run, the plugins and the schedule all pick them up
  without a restart. Sources, providers, roles, plugin settings and both
  prompts are edited there too.
- **Source and plugin shapes.** A source type declares the fields its sources
  take, which the config page shows as a form, a reddit source's subreddits as
  a list to add to, say. Plugins declare theirs with a map in `defsource`, and
  their settings with `defsettings`. Keys a shape leaves out can still be set
  as EDN.
- **Import and export.** The config page exports the settings as EDN in
  `config.edn`'s form, and imports them from a file or pasted text, checked as
  a save is. An old `config.edn` imports as it is; the keys it names replace
  the settings, and `:host`, `:port` and `:db` are passed over. An import is
  checked against a malli schema generated from the fields and shapes.
- **Timeouts and retries per source.** A request that fails for a passing
  reason (429, 408, 5xx, a dropped connection) is retried after the server's
  `Retry-After` or `x-ratelimit-reset`, else a doubling wait, while it still
  fits in the source's time. `:source-timeout-ms`, `:source-retries` and
  `:source-retry-wait-ms` set it for every source; a source's own
  `:timeout-ms`, `:retries` and `:retry-wait-ms` override them, and a plugin's
  shape can give its type a `:policy` in between. Reddit sources get 90s and
  a retry, enough to sit out reddit's once-a-minute limit. Web search's own
  one-off retry is folded into this.
- **Testing a source.** Every source on the config page has a test button
  that reads it as a run would, within the source timeout, and shows the
  headlines it found or why it failed, without saving anything.

### Changed

- **Prompts are Selmer templates.** `{{date}}`, `{{sources}}`, `{{previous}}`
  and the digest's variables work as before, and tags like `{% if previous %}`
  now work too. A template Selmer can't read is refused when it's saved.
- **Ring middleware and ruuter routes.** The handler is a ruuter route table
  under ring-core's params middleware, with the adapter's multipart middleware
  for uploads, in place of hand-matched paths and hand-parsed query strings
  and forms.

- **Settings live in the database.** `config.edn` keeps only `:host`, `:port`
  and `:db`. At startup, any other key in it is moved into the database, as are
  `prompt.md` and `digest.md`, with the old files kept as `.bak`, so an
  existing setup carries over by itself.

## v0.4.0 — 2 October 2026

Briefings now remember stories across days, rank them by how widely and how
long they've been covered, keep a running note on each storyline, and roll the
stored days up into weekly and monthly digests.

### Added

- **Near-duplicate collapsing.** Stories from different outlets whose headlines
  and summaries embed within `:dupe-threshold` (0.62) are folded into one
  source that credits the other outlets. The embeddings come from
  potion-mxbai-128d-v2, bundled in `resources/embed` and run in-process, with
  no model server.
- **Storylines and ranking.** Each day's items are linked by embedding to the
  storylines of the last `:trend-days` (7), within `:story-threshold` (0.6).
  An item's weight is the outlets carrying its story today plus those on
  earlier days, an earlier day counting half as much every `:half-life-days`
  (2). The heaviest stories come first in the prompt with a coverage line, and
  are the last cut by `:max-items`.
- **Story notes.** After each briefing, one more model call keeps a running
  note on every storyline the briefing cited: a stable title, where it stands,
  and up to `:story-notes` (12) dated facts, each tied to the report it came
  from. The next briefing gets the notes of the stories still running as
  background. A `:notes` role can give the job a cheaper model.
- **Weekly and monthly digests.** Once a week (Monday to Sunday) or a month is
  over, a digest is written from its days: the storylines ranked by coverage
  and labelled emerging, persistent, fading or steady against the four periods
  before, citing the facts in their notes, with each day's standfirst.
  `digest.md` is their prompt, `:digests` chooses which run, and
  `:digest-stories` (15) is how many storylines one is given. A missing digest
  can be written from its page.
- **New pages.** `/week/YYYY-Www` and `/month/YYYY-MM` for digests (with
  `.md` versions), `/stories` for every storyline, and `/story/DAY/N` for one
  storyline's summary, timeline of facts and coverage by day.
- **Sidebar.** Storylines, then Monthly, Weekly and Daily sections, a
  standfirst under each day, and feed health on the desk: each source's last
  success and how many times in a row it has failed.
- **Standfirsts.** The briefing opens with a one-sentence `>` blockquote,
  stored as the day's standfirst and shown in the archive.

### Changed

- A story told in any of the last `:seen-days` (3) briefings is left out of
  today's, not only one from yesterday's. Collapsed copies count as told too.
- Outlets are counted by publisher domain, so one publisher's several feeds
  count once, and a story whose dateline names a wire agency, like
  `WASHINGTON (AP) —`, counts as the agency's however many outlets reprint it.
- The daily prompt explains the coverage lines, and that a story many outlets
  carry or that keeps coming back deserves more weight.
- Each source's coverage, the story notes and the standfirsts are kept four
  months longer than `:keep-days`, so a month's digest can always be compared
  with the four months before it.
- The stylesheet and diagram script are files under `resources/public`.
- `dev/make-embed-model.clj` packages the embedding model with jolt, replacing
  the Python script.
- The user agent is `newsroom/0.4`.

### Upgrading

The database gains its new tables and columns on first start. Days stored
before this release get storylines worked out from their stored headlines the
first time a run or a digest reads them, which can take a few minutes once.
Story notes start with the first briefing after the upgrade, and the
Storylines section appears in the sidebar then. `config.edn` needs no
changes, since every new key has a default; `examples/config.edn` documents
them. `digest.md` is written to the config directory on first start.

## v0.3.0 — 1 October 2026

### Added

- `:run-every-hours` (24 by default) sets how far apart runs are after
  `:run-at`. Wake times come from the clock, so they don't drift. A wake
  missed while the machine slept is skipped, and a wake only gathers when the
  day's briefing is at least an interval old.
- Each briefing builds on the last. Stories that were in the previous briefing
  are left out, matched by canonical URL or by headline. The model gets that
  briefing's overview as what's already established, at `{{previous}}` in the
  prompt or just before the sources.

### Changed

- The user agent is `newsroom/0.3`.

## v0.2.1 — 30 September 2026

### Added

- The README documents `NEWSROOM_HOME` and a systemd unit for running
  newsroom as a service.

## v0.2.0 — 30 September 2026

### Added

- `:host` chooses the interface the server listens on. It defaults to
  loopback; `"0.0.0.0"` serves the page to other machines.
- Plugins are folders of namespaces under `plugins/<name>`, written against
  `newsroom.plugin` (`defsource`, `defname`, `config`, `get-json`/`post-json`,
  `fetch-text`, `parse-xml`, `page-meta`, `item`, `emit!`), with settings
  under `:plugins` in `config.edn` and `"${VAR}"` strings read from the
  environment. A single `plugins/*.clj` still loads.
- A reddit plugin that follows each post to the article it links, reading
  several subreddits in one request to stay within reddit's rate limit, and a
  Slack plugin that reads a channel. Both ship with the release, and the smoke
  test checks they load.

### Fixed

- A page read over plain http at a LAN address didn't update when a run
  finished, because the tab id needed a secure context.

## v0.1.0 — 30 September 2026

The first release: a daily news briefing on jolt.

- Gathers the day's items from RSS and Atom feeds, Exa web search and
  scraped front pages, dedupes them, and has an LLM write a cited briefing on
  politics, economics and science and technology and how they drive each
  other.
- Days are stored in sqlite and as markdown files, and served as a live page
  with an archive, a run log and zoomable mermaid diagrams.
- Providers for DeepSeek, GLM, OpenAI, Ollama and local OpenAI-compatible
  servers, with the config, prompt and plugins in `~/.config/newsroom`.
- `examples/config.edn` shows every source type and provider.
- Release builds for macOS and Linux.
