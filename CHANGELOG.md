# Changelog

## Unreleased

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
