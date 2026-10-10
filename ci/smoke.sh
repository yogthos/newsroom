#!/usr/bin/env bash
# Start a built newsroom binary against an empty config and check it serves a
# page. It loads every native library at startup, so this is what proves a
# release archive carries what it needs. Usage: ci/smoke.sh ./newsroom
set -u
bin="$1"
port=3799
home=$(mktemp -d)
printf '{:port %s :run-at nil}\n' "$port" > "$home/config.edn"
# the plugins packaged next to the binary, which have to load in it
[ -d plugins ] && cp -r plugins "$home/plugins"
# a Windows binary reads a Windows path
if command -v cygpath >/dev/null; then home_arg=$(cygpath -w "$home"); else home_arg="$home"; fi

NEWSROOM_HOME="$home_arg" "$bin" > smoke.log 2>&1 &
pid=$!
ok=0
for _ in $(seq 1 60); do
  # the page, and the scripts and stylesheet the binary serves from its embedded resources
  if curl -sf "http://127.0.0.1:${port}/day/2026-01-01" | grep -q "No briefing for this day" &&
     curl -sf "http://127.0.0.1:${port}/js/datastar.js" | grep -q "Datastar" &&
     curl -sf "http://127.0.0.1:${port}/js/diagrams.js" | grep -q "mermaid" &&
     curl -sf "http://127.0.0.1:${port}/config" | grep -q "data-cfg-list" &&
     curl -sf "http://127.0.0.1:${port}/js/config.js" | grep -q "data-cfg-add" &&
     curl -sf "http://127.0.0.1:${port}/css/style.css" | grep -q -- "--paper"; then
    ok=1
    break
  fi
  sleep 1
done
# an https source read through the test button: the TLS libraries have to
# find CA certificates on a machine that isn't the one they were built on
tls=""
if [ "$ok" = 1 ]; then
  tls=$(curl -s -X POST "http://127.0.0.1:${port}/config/test-source" \
    --data-urlencode "_test=sources.0" \
    --data-urlencode "sources.0.type=rss" \
    --data-urlencode "sources.0.name=smoke" \
    --data-urlencode "sources.0.url=https://github.com/yogthos/newsroom/releases.atom")
fi
kill "$pid" 2>/dev/null
command -v taskkill >/dev/null && taskkill //F //IM "$(basename "$bin")" >/dev/null 2>&1
cat smoke.log
if [ "$ok" != 1 ]; then echo "smoke: no page after 60s"; exit 1; fi
echo "smoke: served a page"
if ! printf %s "$tls" | grep -q "Read [0-9]* item"; then
  echo "smoke: couldn't read an https source:"
  printf %s "$tls" | sed 's/<[^>]*>/ /g' | tr -s ' \n' | head -c 500; echo
  exit 1
fi
echo "smoke: read an https source"
for p in plugins/*/; do
  [ -d "$p" ] || continue
  p=$(basename "$p")
  grep -q "loaded plugin $p" smoke.log || { echo "smoke: plugin $p didn't load"; exit 1; }
  echo "smoke: loaded plugin $p"
done
# the speech engines, linked in and beside the binary, where they're built
if [ -f libnewsroom_tts_mini.so ] || [ -f libnewsroom_tts_mini.dylib ]; then
  grep -q "speech engines: .*KittenTTS mini" smoke.log || { echo "smoke: the small speech engine didn't load"; exit 1; }
  echo "smoke: loaded the small speech engine"
fi
