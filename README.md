# newsroom

Newsroom reads the day's news from a bunch of RSS feeds and web searches, then hands the stories to an LLM that writes a briefing about them. The briefing covers politics, economics, and science and tech, but what it really cares about is how those push on each other. When tariffs go up, or bond yields climb, or a lab in one country pulls ahead, the briefing tries to trace what happens next. Every claim links back to the story it came from, so you can always check what the model is telling you.

![A day's briefing in newsroom, with the archive and the run desk in the sidebar](img/newsroom.jpg)

The default sources lean global on purpose. Western outlets sit next to Al Jazeera, CGTN, Global Times, Xinhua and a few others, since a single set of papers tends to tell a single story.

It's written in Clojure and runs on [jolt](https://github.com/jolt-lang/jolt).

## Running it

The [releases](https://github.com/yogthos/newsroom/releases) page has builds for macOS and Linux, so you can grab one of those and run `newsroom` without installing anything else. Windows isn't there yet, since the web server it runs on doesn't support Windows sockets. From a checkout you'd run it with jolt instead.

Running in dev mode from the repo:

```
jolt serve
```

Building and running your own binary:

```
jolt build -m newsroom.core -o newsroom
./newsroom
```

Or grab a release binary.

Open http://127.0.0.1:3000 and you'll get a page for today with the archive in the sidebar. On the first start it writes a default setup to `~/.config/newsroom`. If the day's scheduled time has already passed and there's no briefing for today, it goes and gathers one right away, and you can watch that happen, since the sidebar shows each feed it reads and each search it runs while the model thinks and writes. After that it runs every morning at the time set in `:run-at`, 07:00 by default, and the button in the sidebar gets you a fresh one whenever you want. `:run-every-hours` sets how far apart the runs are, a day by default, so something like 6 refreshes the day's briefing through the day.

Each briefing picks up where the last one left off. Stories that were already in one of the last few briefings (`:seen-days`, 3 by default) are left out, matched by their address or by their headline, since the same wire story often turns up at a new URL the next day. When several outlets carry the same story on the same day, their copies are folded into one source that notes the others, matched by an embedding of the headline and summary that newsroom works out itself, with no model server. An outlet is counted by its publisher's web domain, so CGTN's four feeds carrying the same item count once, and a story whose dateline names a wire agency, like `WASHINGTON (AP) —`, counts as the agency's however many outlets reprint it. The model also gets the previous briefing's overview as what's already been established, so it writes about what changed instead of retelling yesterday. If you've edited the prompt, `{{previous}}` marks where that overview goes, and without it it goes just before the sources.

Newsroom also follows a story from day to day. Every source is stored with its embedding, so a new day's story that sits close to one from the past week is treated as the same storyline continuing. Stories are then ranked by how widely and how long they've run: the outlets carrying one today, plus the outlets that carried it on each earlier day, with an earlier day counting half as much every two days. A story that many outlets pick up, or one that keeps coming back, goes to the top of the list the model reads, with a note on its coverage, and it's the last to be cut when there are more stories than `:max-items`. `:story-threshold`, `:trend-days` and `:half-life-days` tune this.

After each briefing the model also keeps a running note on every storyline the briefing cited, and keeps the ones it already has up to date: a title, a couple of sentences on where it stands, and its key facts, each dated and tied to the report it came from. Each day's reports get compacted into the note, so repeats merge and superseded details drop out, and the note ends up holding the course of the story rather than every report on it. The next day's briefing gets the notes of the stories still running as background. `:story-notes` sets how many facts a note keeps, and a `:notes` role can point the job at a cheaper model.

Once a week is over, and once a month is, newsroom writes a digest of it. It doesn't go back to the feeds. It ranks the period's storylines by their coverage, and compares each one with the four periods before to label it emerging, persistent, fading or steady. The model gets those storylines with the facts from their notes and the standfirst of each day's briefing, and writes about the longer movement under the daily news. The coverage, the notes and the standfirsts are kept four months longer than the days themselves, so a month's digest doesn't depend on `:keep-days`. Digests show up in the sidebar and live at `/week/2026-W40` and `/month/2026-09`. A digest that's missing can be written from its page. They have a prompt of their own, and `:digests` says which ones get written.

The model needs an API key. DeepSeek is the default and reads `DEEPSEEK_API_KEY`, though GLM, OpenAI, Ollama and a local llama.cpp server work just as well.

## Making it yours

Everything you'd want to change is on the config page, at http://127.0.0.1:3000/config or through the link in the sidebar. The sources, the schedule, the models, the plugins' settings and the prompt the model gets are all there, each with a note on what it does, and saving puts them in effect straight away, with no restart. Edit the prompt when you want a different kind of briefing. A source can be an RSS feed or a web search, and a site with no feed can still be read by scraping the story links off its front page. Each type of source shows its own fields, and a source of a type that comes from a plugin shows the fields the plugin declared. To follow a source newsroom doesn't know about, like a Slack or Telegram channel, add a plugin.

The settings are kept in the database. Only what newsroom needs before it can open the database stays in `~/.config/newsroom/config.edn`: `:host`, `:port`, and `:db` if the database should live somewhere else. Any other key found in `config.edn` at startup is moved into the database, and so are `prompt.md` and `digest.md`, with the old files kept as `.bak`. A config from an older newsroom carries over that way. The config page can also export the settings as EDN in that same form, and import them back, or import a whole `config.edn`, like [`examples/config.edn`](examples/config.edn), which walks through every kind of source and every model provider. An import replaces the settings it names and keeps the rest.

Each day goes into a sqlite database and gets written out as a markdown file in `briefings/` too. Only the last 100 days are kept, which stops a long-running server from slowly eating the disk, and you can set `:keep-days` to -1 if you'd rather keep everything.

All of that lives somewhere else when `NEWSROOM_HOME` is set, which is the way to run newsroom as a service. With `NEWSROOM_HOME=/var/newsroom` the config, plugins, database and briefings all go in `/var/newsroom`, and the first start writes the defaults there, so the service's user needs to be able to write to it. A systemd unit might look like this:

```ini
[Service]
User=newsroom
Environment=NEWSROOM_HOME=/var/newsroom
EnvironmentFile=/etc/newsroom.env
ExecStart=/usr/local/bin/newsroom
Restart=on-failure
```

with the model's key, like `DEEPSEEK_API_KEY=...`, in `/etc/newsroom.env`. Set `:host` in the config if the page should be reachable from other machines.

## Plugins

A plugin is a folder in `~/.config/newsroom/plugins/` holding Clojure namespaces named after it, so `plugins/slack/core.clj` is `slack.core`. Every namespace in the folder gets loaded when newsroom starts, and a plugin that fails to load is reported and skipped. A plugin small enough for one file can also be a single `plugins/<name>.clj`. Plugins are written against `newsroom.plugin`: `defsource` adds a source type, `defsettings` says what the plugin's settings are, `config` returns the plugin's settings, `get-json` and `post-json` talk to JSON APIs, `fetch-text`, `parse-xml` and `page-meta` read feeds and article pages, and `item` builds the items a source hands back. `jolt.http-client` and `clojure.data.json` can be required directly for anything else.

A plugin's settings are edited on the config page, under the plugin's folder name, and a `"${VAR}"` string there is read from the environment. Settings that change from one source to the next, like which channel to read, go on the source itself, since the plugin gets the whole source map. As data, they look like this:

```clojure
:plugins {:slack {:token "${SLACK_BOT_TOKEN}" :workspace "acme"}}
:sources [{:type :slack :name "Slack #news" :channel "C0123456789"}]
```

A source type is a function from the source map and the run's context to a list of items. The map before its arguments is the shape of a source of the type, which the config page builds the source's fields from, and `defsettings` does the same for the plugin's settings. A field has a `:key`, a `:type`, which is `:string`, `:text`, `:int`, `:number`, `:boolean`, `:strings` for a list of strings, `:keyword`, `:keywords`, or `:records` for a list of maps with `:fields` of their own, and a `:doc` saying what it's for. `:required?` and `:default` are optional. Keys a shape leaves out can still be set, as EDN, and a plugin with no shape at all still works, with its sources edited as EDN.

```clojure
(ns slack.core
  (:require [newsroom.plugin :as plugin]))

(plugin/defsettings
  {:fields [{:key :token :type :string :required? true
             :doc "A bot token, or ${SLACK_BOT_TOKEN}."}]})

(plugin/defsource :slack
  {:doc "A Slack channel's recent messages."
   :fields [{:key :channel :type :string :required? true :doc "The channel's ID."}]}
  [source ctx]
  (let [{:keys [token]} (plugin/config :slack)]
    (plugin/emit! ctx (str "Reading " (plugin/source-name source)))
    (for [m (:messages (plugin/get-json "https://slack.com/api/conversations.history"
                                        {:headers {"Authorization" (str "Bearer " token)}
                                         :query-params {"channel" (:channel source)}}))]
      (plugin/item source {:title (:text m) :url "..." :summary (:text m)}))))
```

`emit!` shows what the plugin is doing in the sidebar while a run goes, and `defname` says what a source is called when it has no `:name`, which matters because the sidebar tracks sources by name, so two sources of one type need different ones.

The repo's [`plugins/`](plugins) folder has two that work as they are. To use one, copy its folder into `~/.config/newsroom/plugins/`.

- [`reddit`](plugins/reddit/core.clj) reads subreddit feeds, like `{:type :reddit :subreddit ["technology" "worldnews"]}`, which the config page shows as a list of subreddits to add to, and follows each post to the story it links to. The items are the articles themselves, with the outlet's headline and description, and text posts and reddit-hosted images and videos are skipped. Reddit only lets a client read about one feed a minute without logging in, so list every subreddit in one source rather than adding a source per subreddit.
- [`slack`](plugins/slack/core.clj) is the full version of the plugin above. It reads a channel with a bot token.

## Tests

```
jolt test
```

That runs the test suite and then checks the pure core against its [writ](https://github.com/jlt-commons/writ) spec in `test/newsroom/news_spec.clj`.

The embedding model, [potion-mxbai-128d-v2](https://huggingface.co/blobbybob/potion-mxbai-128d-v2), is committed under `resources/embed`. `jolt dev/make-embed-model.clj` downloads it again and rewrites those files.
