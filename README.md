# newsroom

Newsroom reads the day's news from a bunch of RSS feeds and web searches, then hands the stories to an LLM that writes a briefing about them. The briefing covers politics, economics, and science and tech, but what it really cares about is how those push on each other. When tariffs go up, or bond yields climb, or a lab in one country pulls ahead, the briefing tries to trace what happens next. Every claim links back to the story it came from, so you can always check what the model is telling you.

![A day's briefing in newsroom, with the archive and the run desk in the sidebar](img/newsroom.jpg)

The default sources lean global on purpose. Western outlets sit next to Al Jazeera, CGTN, Global Times, Xinhua and a few others, since a single set of papers tends to tell a single story.

It's written in Clojure and runs on [jolt](https://github.com/jolt-lang/jolt).

## Running it

The [releases](https://github.com/yogthos/newsroom/releases) page has builds for macOS and Linux, so you can grab one of those and run `newsroom` without installing anything else. Windows isn't there yet, since the web server it runs on doesn't support Windows sockets. From a checkout you'd run it with jolt instead.

```
jolt serve
```

Open http://127.0.0.1:3000 and you'll get a page for today with the archive in the sidebar. On the first start it writes a default setup to `~/.config/newsroom`, and if today doesn't have a briefing yet it goes and gathers one right away. You can watch that happen, since the sidebar shows each feed it reads and each search it runs while the model thinks and writes. After that it runs every morning at the time you set, and the button in the sidebar gets you a fresh one whenever you want.

The model needs an API key. DeepSeek is the default and reads `DEEPSEEK_API_KEY`, though GLM, OpenAI, Ollama and a local llama.cpp server work just as well.

## Making it yours

Everything you'd want to change lives in `~/.config/newsroom`. The sources, schedule and model are all in `config.edn`, while `prompt.md` holds the instructions the model gets, so that's the file to edit when you want a different kind of briefing. A source can be an RSS feed or a web search, and a site with no feed can still be read by scraping the story links off its front page. [`examples/config.edn`](examples/config.edn) walks through every kind of source and every model provider. To follow a source newsroom doesn't know about, like a Slack or Telegram channel, add a plugin.

Each day goes into a sqlite database and gets written out as a markdown file in `briefings/` too. Only the last 100 days are kept, which stops a long-running server from slowly eating the disk, and you can set `:keep-days` to -1 if you'd rather keep everything.

## Plugins

A plugin is a folder in `~/.config/newsroom/plugins/` holding Clojure namespaces named after it, so `plugins/slack/core.clj` is `slack.core`. Every namespace in the folder gets loaded when newsroom starts, and a plugin that fails to load is reported and skipped. A plugin small enough for one file can also be a single `plugins/<name>.clj`. Plugins are written against `newsroom.plugin`: `defsource` adds a source type, `config` returns the plugin's settings, `get-json` and `post-json` talk to JSON APIs, `fetch-text`, `parse-xml` and `page-meta` read feeds and article pages, and `item` builds the items a source hands back. `jolt.http-client` and `clojure.data.json` can be required directly for anything else.

The settings live in `config.edn` under `:plugins`, keyed by the folder name, and a `"${VAR}"` string there is read from the environment. Settings that change from one source to the next, like which channel to read, go on the source itself, since the plugin gets the whole source map.

```clojure
:plugins {:slack {:token "${SLACK_BOT_TOKEN}" :workspace "acme"}}
:sources [{:type :slack :name "Slack #news" :channel "C0123456789"}]
```

A source type is a function from the source map and the run's context to a list of items:

```clojure
(ns slack.core
  (:require [newsroom.plugin :as plugin]))

(plugin/defsource :slack [source ctx]
  (let [{:keys [token]} (plugin/config :slack)]
    (plugin/emit! ctx (str "Reading " (plugin/source-name source)))
    (for [m (:messages (plugin/get-json "https://slack.com/api/conversations.history"
                                        {:headers {"Authorization" (str "Bearer " token)}
                                         :query-params {"channel" (:channel source)}}))]
      (plugin/item source {:title (:text m) :url "..." :summary (:text m)}))))
```

`emit!` shows what the plugin is doing in the sidebar while a run goes, and `defname` says what a source is called when it has no `:name`, which matters because the sidebar tracks sources by name, so two sources of one type need different ones.

The repo's [`plugins/`](plugins) folder has two that work as they are. To use one, copy its folder into `~/.config/newsroom/plugins/`.

- [`reddit`](plugins/reddit/core.clj) reads subreddit feeds, like `{:type :reddit :subreddit ["technology" "worldnews"]}`, and follows each post to the story it links to. The items are the articles themselves, with the outlet's headline and description, and text posts and reddit-hosted images and videos are skipped. Reddit only lets a client read about one feed a minute without logging in, so list every subreddit in one source rather than adding a source per subreddit.
- [`slack`](plugins/slack/core.clj) is the full version of the plugin above. It reads a channel with a bot token.

## Tests

```
jolt test
```

That runs the test suite and then checks the pure core against its [writ](https://github.com/jlt-commons/writ) spec in `test/newsroom/news_spec.clj`.
