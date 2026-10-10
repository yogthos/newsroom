# newsroom

Newsroom reads the day's news from a bunch of RSS feeds and web searches, then hands the stories to an LLM that writes a briefing about them. The briefing covers politics, economics, and science and tech, but what it really cares about is how those push on each other. When tariffs go up, or bond yields climb, or a lab in one country pulls ahead, the briefing tries to trace what happens next. It treats the world as a system whose state shifts under selection pressures, so a briefing opens with a bird's-eye view of what changed since the last one, the risks to watch with what they'd hit and the signposts that would show them starting, and the outlook, then spends most of its length on the analysis of why things are moving and where they lead, and ends with the trends that bear the analysis out, each with how it has changed over the days it has run. Every claim links back to the story it came from, so you can always check what the model is telling you.

![A day's briefing in newsroom, with the archive and the run desk in the sidebar](img/newsroom.jpg)

The default sources lean global on purpose. Western outlets sit next to Al Jazeera, CGTN, Global Times, Xinhua and a few others, since a single set of papers tends to tell a single story.

It's written in Clojure and runs on [jolt](https://github.com/jolt-lang/jolt).

## Running it

The [releases](https://github.com/yogthos/newsroom/releases) page has builds for macOS, Linux and Windows, so you can grab one of those and run `newsroom` without installing anything else. The Windows archive carries the libraries it needs beside `newsroom.exe`. From a checkout you'd run it with jolt instead.

Running in dev mode from the repo:

```
jolt serve
```

Building and running your own binary:

```
jolt build -m newsroom.core -o newsroom
./newsroom
```

On Linux, with the speech engine built (`jolt tts`, below), the build needs the C++ runtime preloaded: `LD_PRELOAD=$(g++ -print-file-name=libstdc++.so) jolt build -m newsroom.core -o newsroom`.

Or grab a release binary.

Open http://127.0.0.1:3000 and you'll get a page for today with the archive in the sidebar. On the first start it writes a default setup to `~/.config/newsroom`, with an empty `secrets.edn` for API keys. If the day's scheduled time has already passed and there's no briefing for today, it goes and gathers one right away, and you can watch that happen, since the sidebar shows each feed it reads and each search it runs while the model thinks and writes. After that it runs every morning at the time set in `:run-at`, 07:00 by default, and the button in the sidebar gets you a fresh one whenever you want. `:run-every-hours` sets how far apart the runs are, a day by default, so something like 6 refreshes the day's briefing through the day.

Each briefing picks up where the last one left off. Stories that were already in one of the last few briefings (`:seen-days`, 3 by default) are left out, matched by their address or by their headline, since the same wire story often turns up at a new URL the next day. When several outlets carry the same story on the same day, their copies are folded into one source that notes the others, matched by an embedding of the headline and summary that newsroom works out itself, with no model server. An outlet is counted by its publisher's web domain, so CGTN's four feeds carrying the same item count once, and a story whose dateline names a wire agency, like `WASHINGTON (AP) —`, counts as the agency's however many outlets reprint it. A story that keeps running still turns up at new addresses with new headlines, though, and most of those reports only repeat what was known. So the model also gets the whole previous briefing, without its citations, diagram and sources, as what the reader already knows, and the reports on stories that briefing cited are marked as already in it. It's told to write about what's genuinely new: where a running story moved, a line at most where it didn't, and whether yesterday's projections held. The prompts are [Selmer](https://github.com/yogthos/Selmer) templates, so if you've edited one, `{{previous}}` marks where the last briefing goes, `{% if previous %}…{% endif %}` shows text only when there is one, and without either it goes just before the sources.

Newsroom also follows a story from day to day. Every source is stored with its embedding, so a new day's story that sits close to one from the past week is treated as the same storyline continuing. Stories are then ranked by how widely and how long they've run: the outlets carrying one today, plus the outlets that carried it on each earlier day, with an earlier day counting half as much every two days. A story that many outlets pick up, or one that keeps coming back, goes to the top of the list the model reads, with a note on its coverage, and it's the last to be cut when there are more stories than `:max-items`. `:story-threshold`, `:trend-days` and `:half-life-days` tune this.

Before the briefing is written, a desk works the day over in stages, each a narrower model call than the one that writes. First it sorts the day's reports into stories: the central bank's decision, the market's reaction and the minister's answer are one story, told from three sides, and a trade deal and the protests against it at home stay two. Each story gets a neutral title, an importance from 1 to 10 and a status, from developing to concluding, and reports that aren't news at all are left out. A story's reports share a storyline and are ranked together, their outlets summed and the weight scaled by the importance, so a story one outlet broke can still outrank one many outlets carried for a day. Then the desk reads the main reports of the top stories in full from the outlets' pages, `:read-articles` of each, where a paywall doesn't stop it, and writes a dossier on each of the top `:dossier-stories` from its reports alone: the salient facts with their citations, the actors with what they say they want and what they stand to gain, where outlets or actors give different accounts, how each outlet frames it, what the reports leave out, and the broader forces it shows. From the dossiers it maps how the day moves the world, in three levels: the day's stories at the bottom, the trends they push on, like freight costs rising or a chip ban pushing a country to build its own, and on top the few structural forces those feed, like trade splitting into blocs or the tech stacks of the US and China coming apart. Stories aren't linked because they share a country or a topic. Every link is a step of cause and effect that names what it changes, a price, a supply, a capability, an incentive, and says whether it strengthens or weakens its target, how soon it bites and how sure the evidence makes it. The map follows each chain past what the reports say, to the consequences its mechanisms make likely, like inflation expectations after a chokepoint is attacked, since those are what a reader has to act on before the news reports them. It also names the risks it sees, with what they'd hit and their signposts, and an outlook. The analyst writes the briefing from the dossiers and the map, the graph at the start of its analysis is the map drawn from the bottom up, and the forces and trends, each followed from the days before, go to the precedent research and are kept for the digests. Up to `:gap-searches` searches look for what the dossiers say is missing, and what they find joins the sources. `:group-stories` and `:connect-stories` turn the sorting and the map off, and a blank `:dossier-stories` the dossiers and everything after them. A `:desk` role can give the work its own model.

Once the briefing is written, a critic checks it before it's filed. The news carries plenty of claims that were really reported but can't happen the way they're told, like a plan an actor has nothing to carry out with, and an analyst that cites one faithfully still misleads the reader when it treats it as a real prospect. The critic reads the briefing against the sources and against what each actor actually has in industry, resources, money and time, and lists the claims that don't hold up, along with interested claims taken at face value, facts the sources don't carry and reasoning that doesn't follow. When it finds any, the analyst rewrites the briefing with them fixed, and the run's log shows what was found. `:critique-briefing` turns the check off, a `:critic` role can give it a different model from the analyst's, which tends to catch more, and the critic's prompt is on the config page with the others.

After each briefing the model also keeps a running note on every storyline the briefing cited, and keeps the ones it already has up to date: a title, a couple of sentences on where it stands, whether it's escalating, easing or settling, and its key facts, each dated and tied to the report it came from. Each day's reports get compacted into the note, so repeats merge and superseded details drop out, and the note ends up holding the course of the story rather than every report on it. The next day's briefing gets the notes of the stories still running as background. `:story-notes` sets how many facts a note keeps, and a `:notes` role can point the job at a cheaper model.

Once a week is over, and once a month is, newsroom writes a digest of it. It doesn't go back to the feeds. It ranks the period's storylines by their coverage, and compares each one with the four periods before to label it emerging, persistent, fading or steady. The model gets those storylines with the facts from their notes and the standfirst of each day's briefing, and writes about the longer movement under the daily news. A week's digest is built on its daily briefings, which the model reads for how things evolved across the week rather than to retell them, and a month's is built on its weekly digests, with the overviews of any days no weekly digest covers. In an edited digest prompt, `{{briefings}}` marks where they go. The coverage, the notes and the standfirsts are kept four months longer than the days themselves, so a month's digest doesn't depend on `:keep-days`. Digests show up in the sidebar and live at `/week/2026-W40` and `/month/2026-09`. A digest that's missing can be written from its page. They have a prompt of their own, and `:digests` says which ones get written.

Newsroom also keeps score of its own projections. Once a briefing is filed, the model lists the projections it makes, each a claim that can later turn out true or false, with its subject, the storyline it's about and the day by which it should be known. The dossiers do the same for the outlets: when a report puts forward its own expectation of how a story will go, in its own voice or through the analysts it chooses to quote, that's recorded and credited to the outlet. A wire story counts for the agency, however many outlets reprint it. A digest's projections are recorded too. After each weekly and monthly digest, a retrospective takes the projections that have fallen due and checks each one against what happened since: the facts in its storyline's notes, the digest and the days' standfirsts. Each is judged held, partly or failed, with how close the outcome came on a scale from 0 to 1 and the reason it held or missed. One the evidence doesn't settle yet gets another period. From the verdicts the model keeps a short list of lessons, each one a rule for the next projection tied to a subject, like a kind of actor whose announcements keep outrunning what it can do. The briefings and digests are given the lessons, newsroom's own record and the forecasts still open, so each briefing says which way the day moved the forecasts it bears on, and `{{record}}` marks where they go in an edited prompt. Every outlet is ranked within each subject by how close its calls came, so an outlet that reads technology well can still sit at the bottom on geopolitics, and the analyst sees an outlet's record on a subject next to anything that outlet expects on it. The rankings, the lessons and every verdict with its reason are at `/record`, and a digest's page shows the projections its retrospective judged. `:retrospective` turns this off, and a `:retrospective` role can give the job its own model.

The model needs an API key. DeepSeek is the default and reads `DEEPSEEK_API_KEY`, though GLM, OpenAI, Ollama and a local llama.cpp server work just as well. The key can be exported in the shell that starts newsroom, or kept in `secrets.edn` in the config directory, as described under [Making it yours](#making-it-yours).

## The podcast

Once a day's briefing is filed, newsroom also makes a podcast of it, two hosts talking the day through for a listener who wants to know why the world moved, and it plays at the top of the day's page, with its transcript under it. The script is written the way [open-notebook](https://github.com/lfnovo/open-notebook) writes its podcasts. The model first plans the episode as a few segments, `:podcast-segments` of them, 5 by default, each short, medium or long by how much it has to carry, then writes the hosts' dialogue one segment at a time, with the outline and the conversation so far in front of it. It's told every line will be spoken, so the numbers, dates and abbreviations are written out as they're said, and the hosts may use the tags the speech engine knows, an emotion to start a line and a word stressed, to sound alive. KittenTTS also lists vocal events like `<laugh>`, but this checkpoint says them as words, so they're left out. Whatever slips through anyway, markdown, citations, addresses, stage directions, is cleaned out before the engine reads it. The hosts are on the config page, each with a name, a voice, a backstory and a personality the script plays to, and so are the two prompts. A `:podcast` role can give the script its own model.

Recording takes minutes, so the podcast is made after the run rather than during it, and the sidebar shows how far it has got while the next run is free to start. One is made at a time. The episode is kept in the database with its day, goes when the day does, and a day gathered again gets a fresh one. Any day's briefing can be recorded, or recorded again, from its page, and `/day/2026-10-05.mp3` is the episode itself. With `:podcast-auto` off a podcast is recorded only from the page, and `:podcast` turns podcasts off altogether.

The voices are [KittenTTS 2](https://huggingface.co/KittenML/kitten-tts-2), run on the CPU inside newsroom itself, with nothing else to install. It's a speech language model that writes the codec tokens of chatterbox-turbo's S3 decoder, which turns them into audio. The model runs through [llama.cpp](https://github.com/ggml-org/llama.cpp), and the decoder is ported to ggml from [chatterbox.cpp](https://github.com/gianni-cor/chatterbox.cpp), so it matches the upstream Python package sample for sample. LAME makes the MP3. The first podcast downloads the model's files from Hugging Face, about 2 GB, and converts them once into `~/.config/newsroom/tts`, which takes about 2 GB of disk, and about 4 GB of memory while an episode is recorded.

It records on the GPU when there's one it can use, and on the CPU otherwise, which `:podcast-device` can insist on. On a Mac that's Metal, built in: an M1 Max records a fifteen-minute episode in about six minutes, where its CPU alone takes nearly half an hour. On Linux the binary itself runs on the CPU, and a GPU plugin, `libnewsroom_tts_gpu.so`, runs the engine on the GPU through Vulkan, which NVIDIA's, AMD's and Intel's drivers all speak. The release carries the Vulkan plugin in `plugins/speech/`, which goes in the config directory's `plugins/` like the other plugins, so `~/.config/newsroom/plugins/speech/`, or `$NEWSROOM_HOME/plugins/speech/` for a service. It loads when the machine has a Vulkan driver, and when it doesn't, or there's no GPU, newsroom says so and goes on with the CPU. Before it trusts a GPU with the decoder, the engine decodes a few seconds of speech on it and on the CPU and compares them, and when a driver gets it wrong it keeps the decoder on the CPU and the speech model on the GPU. The sidebar says what an episode is being recorded on.

For a machine with neither a GPU it can use nor a fast CPU, `:podcast-engine :kitten-mini` records with [KittenTTS mini](https://huggingface.co/KittenML/kitten-tts-mini-0.8) instead, a small model on the CPU that an M1 runs at about a seventh of real time, so a fifteen-minute episode takes two minutes or so. It's a plainer voice: it reads no emotion or emphasis, so the tags are taken out before it reads a line, and it has only eight voices, so a host whose voice it lacks speaks in its deepest of the same sex, Luna or Bruno. Its first podcast downloads about 80 MB into `~/.config/newsroom/tts/mini`. It runs through [ONNX Runtime](https://onnxruntime.ai), with [espeak-ng](https://github.com/espeak-ng/espeak-ng) for its phonemes, in a plugin of its own, `libnewsroom_tts_mini`, which the macOS and Linux releases carry in `plugins/speech/` too, with ONNX Runtime and espeak-ng's English data in `espeak-ng-data/`. At startup newsroom says which speech engines it found.

From a checkout, `jolt tts` builds the engine into `native/`, which takes a C++ compiler, CMake and make: on macOS with Metal, on Linux for the CPU. `NEWSROOM_TTS_GPU=vulkan jolt tts` builds the Vulkan plugin too, which needs the Vulkan SDK, and `cuda` or `hip` build a plugin for CUDA on NVIDIA or ROCm on AMD instead, with their toolkits, which tend to run faster than Vulkan on those cards. `jolt tts` builds the mini plugin too, unless `NEWSROOM_TTS_MINI=0`. The plugins are found at `NEWSROOM_TTS_GPU_LIB` and `NEWSROOM_TTS_MINI_LIB`, else in the config directory's `plugins/speech/`, else in `native/`, and espeak-ng's data beside the mini plugin or at `NEWSROOM_ESPEAK_DATA`. `jolt tts-test` checks the engine against the upstream package's output in `test/golden/tts`, which `jolt tts-golden` regenerates, and `jolt mini-test` and `jolt mini-golden` do the same for the mini engine in `test/golden/mini`. The release binaries for macOS and Linux have the engine built in; the Windows one doesn't, so it makes no podcasts. The model's weights come under the [Stellon Labs Community License](https://huggingface.co/KittenML/kitten-tts-2/blob/main/LICENSE.md), free for research, non-commercial and smaller commercial use, and chatterbox's decoder under MIT. KittenTTS mini's weights are Apache 2.0.

## Your topics

Any paragraph of a briefing, a digest or a storyline can be added to your topics with the + that shows beside it, and the pane on the right collects them, from as many days as you like. From there the analyst writes a full report on them: it gathers the sources the passages cite, the other reports on their storylines and the notes kept on them, reads the main ones in full from the outlets' pages, and goes deeper than the briefing did, with the background, the actors and their interests, how the topics connect, the risks to watch and the outlook. You can say what it should focus on, and ask it about the topics afterwards, with the report as part of the conversation. The answers stream in as they're written, cite their sources by number and can be downloaded as markdown. The topics and the conversation are kept in your browser, so they follow you from one page to the next.

## Searching

The search box at the top of the sidebar looks through everything newsroom keeps: the briefings and digests, every source gathered for a day, cited or not, and the storylines' notes with each of their facts. Every word has to match, and it's matched by its stem, so `tariff` finds tariffs, and as the start of a longer word. Put words in quotes to match them as a phrase. A word that appears nowhere is taken for a misspelling, so `tarrifs` searches for tariffs too, and the page says what it was taken for. The results are ranked by how well they match, or newest first, and can be narrowed to one kind. A source links to the day it was gathered and to the original report, and a fact links to its storyline. The index is sqlite's own FTS5, kept up to date as each day is filed, and a database from an older version is indexed the first time it's opened.

## Making it yours

Everything you'd want to change is on the config page, at http://127.0.0.1:3000/config or through the link in the sidebar. The sources, the schedule, the models, the plugins' settings and the prompt the model gets are all there, each with a note on what it does, and saving puts them in effect straight away, with no restart. Edit the prompt when you want a different kind of briefing. A prompt you haven't edited follows the default as newsroom updates it, and one you have is left alone. A source can be an RSS feed or a web search, and a site with no feed can still be read by scraping the story links off its front page. Each type of source shows its own fields, and a source of a type that comes from a plugin shows the fields the plugin declared. The test button on a source reads it there and then, as a run would, and shows the headlines it found or why it couldn't, so a new feed can be checked before it's saved.

A source that fails for a passing reason, like a rate limit, an overloaded server or a dropped connection, is tried again, after the wait the server asked for or a doubling wait of its own, as long as that still fits in the source's time. How long a source gets and how many retries it has are set for every source under Gathering, and any source can set its own `:timeout-ms`, `:retries` and `:retry-wait-ms`. A plugin can give its type defaults of its own: reddit, which wants a minute between feeds, gives its sources 90 seconds and a retry. To follow a source newsroom doesn't know about, like a Slack or Telegram channel, add a plugin.

The settings are kept in the database. Only what newsroom needs before it can open the database stays in `~/.config/newsroom/config.edn`: `:host`, `:port`, and `:db` if the database should live somewhere else. Any other key found in `config.edn` at startup is moved into the database, and so are `prompt.md` and `digest.md`, with the old files kept as `.bak`. A config from an older newsroom carries over that way. The config page can also export the settings as EDN in that same form, and import them back, or import a whole `config.edn`, like [`examples/config.edn`](examples/config.edn), which walks through every kind of source and every model provider. An import is checked against a [malli](https://github.com/metosin/malli) schema made from the same fields the page shows, sources by their type's shape and plugins by what they declared, and each problem is reported by where it is, like `:sources 3 :url missing required key`. It replaces the settings it names and keeps the rest.

Each day goes into a sqlite database, and `/day/2026-10-05.md` gives you any day's briefing as markdown. Older versions also wrote every briefing out as a file in `briefings/`. Those files are moved into the database on the first start, any the database is missing included, and the folder is kept as `briefings.bak`. Only the last 100 days are kept, which stops a long-running server from slowly eating the disk, and you can set `:keep-days` to -1 if you'd rather keep everything.

All of that lives somewhere else when `NEWSROOM_HOME` is set, which is the way to run newsroom as a service. With `NEWSROOM_HOME=/var/newsroom` the config, plugins and database all go in `/var/newsroom`, and the first start writes the defaults there, so the service's user needs to be able to write to it. A systemd unit might look like this:

```ini
[Service]
User=newsroom
Environment=NEWSROOM_HOME=/var/newsroom
EnvironmentFile=/etc/newsroom.env
ExecStart=/usr/local/bin/newsroom
Restart=on-failure
```

with the model's key, like `DEEPSEEK_API_KEY=...`, in `/etc/newsroom.env`. Set `:host` in the config if the page should be reachable from other machines.

Keys can also live in `secrets.edn` in the config directory, next to `config.edn`, as a map from each key's name to its value:

```clojure
{"DEEPSEEK_API_KEY" "sk-..."
 "SLACK_BOT_TOKEN" "xoxb-..."}
```

Anything that names a key, a provider's `:api-key-env`, a `"${VAR}"` in a provider or a plugin's settings, or the built-in names like `DEEPSEEK_API_KEY` and `EXA_API_KEY`, finds it there when the environment doesn't have it. The environment wins when both do. The first start writes an empty one, readable only by you, with comments on what goes in it. After that newsroom only reads it, once at startup, and keeps its values out of the database and the export. It won't start if anyone but the file's owner can read it, so if you make the file yourself, `chmod 600 secrets.edn` after writing it. [`examples/secrets.edn`](examples/secrets.edn) has the usual names.

## Plugins

A plugin is a folder in `~/.config/newsroom/plugins/` holding Clojure namespaces named after it, so `plugins/slack/core.clj` is `slack.core`. Every namespace in the folder gets loaded when newsroom starts, and a plugin that fails to load is reported and skipped. A plugin small enough for one file can also be a single `plugins/<name>.clj`. Plugins are written against `newsroom.plugin`: `defsource` adds a source type, `defsettings` says what the plugin's settings are, `config` returns the plugin's settings, `get-json` and `post-json` talk to JSON APIs, `fetch-text`, `parse-xml` and `page-meta` read feeds and article pages, and `item` builds the items a source hands back. An item's `:text` is its full text, like a video's transcript, which the desk reads in place of fetching the page at its `:url`. `jolt.http-client` and `clojure.data.json` can be required directly for anything else.

The config page lists every plugin found at startup, with the source types it adds or, when it failed to load, why. A plugin added or changed is loaded at the next start. A plugin's settings are edited there too, under the plugin's folder name, one field for each that `defsettings` declares, and a `"${VAR}"` string there is read from the environment. Settings that change from one source to the next, like which channel to read, go on the source itself, since the plugin gets the whole source map. As data, they look like this:

```clojure
:plugins {:slack {:token "${SLACK_BOT_TOKEN}" :workspace "acme"}}
:sources [{:type :slack :name "Slack #news" :channel "C0123456789"}]
```

A source type is a function from the source map and the run's context to a list of items. The map before its arguments is the shape of a source of the type, which the config page builds the source's fields from, and `defsettings` does the same for the plugin's settings. A field has a `:key`, a `:type`, which is `:string`, `:text`, `:int`, `:number`, `:boolean`, `:strings` for a list of strings, `:keyword`, `:keywords`, or `:records` for a list of maps with `:fields` of their own, and a `:doc` saying what it's for. `:required?` and `:default` are optional. A `:policy` on the shape, like `{:timeout-ms 90000 :retries 1}`, is how the type's sources are read when they don't say. A `:lookback-days` on the shape, or on a source, lets the type's items be older than the run's lookback, for a source that publishes every few days rather than every day. Requests made with `fetch-text`, `get-json` and `post-json` are retried under the source's policy on their own. Keys a shape leaves out can still be set, as EDN, and a plugin with no shape at all still works, with its sources edited as EDN.

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
- [`youtube`](plugins/youtube/core.clj) follows YouTube channels, like `{:type :youtube :channels ["@DWNews" "UCHnyfMqiRRG1u-2MsSQLbXA"]}`, by handle, channel ID or address. When a channel has published in the last few days (3 by default, `:lookback-days` on the source), its latest video (`:videos` for more) is read for its transcript, and the transcript goes to the desk as the video's full text, so the dossier on its story is written from what was said. Shorts are left out unless `:shorts` is true, and captions are taken in the first of `:languages` the video has. It needs no key.

## Tests

```
jolt test
```

That runs the test suite and then checks the pure core against its [writ](https://github.com/jlt-commons/writ) spec in `test/newsroom/news_spec.clj`.

The embedding model, [potion-mxbai-128d-v2](https://huggingface.co/blobbybob/potion-mxbai-128d-v2), is committed under `resources/embed`. `jolt dev/make-embed-model.clj` downloads it again and rewrites those files.

## License

Newsroom is licensed under the [GNU Affero General Public License v3.0](LICENSE). The speech engine's S3 decoder in `native/s3gen.cpp` is adapted from [chatterbox.cpp](https://github.com/gianni-cor/chatterbox.cpp), MIT-licensed, Copyright (c) 2026 Gianfranco Cordella; llama.cpp and ggml are MIT-licensed and LAME is LGPL 2. The mini plugin links espeak-ng, GPL 3, and loads ONNX Runtime, MIT-licensed.
