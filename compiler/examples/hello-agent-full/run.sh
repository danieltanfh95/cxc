#!/usr/bin/env bash
# hello-agent-full runner — builds the binary via the A7 R3 dep flow
# (plugin fetched from a local git repo tagged v0.1) and runs it.
#
# Usage:
#   ./run.sh "your task here"     # one-shot from argv
#   echo "your task" | ./run.sh   # one-shot from stdin
#   ./run.sh                      # bare — reads one line from stdin, then Enter
#
# Requires DEEPSEEK_API_KEY in the environment for real chat responses;
# without it the agent still runs (prints [http 401] + stop: other).

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
COMPILER_DIR="$(cd "$HERE/../.." && pwd)"
PLUGIN_SRC="$COMPILER_DIR/examples/hello-agent-full-plugin-source"
CACHE="$HOME/.cache/cxc-hello-agent-full"
PLUGIN_REPO="$CACHE/plugin"
BUILD_SRC="$CACHE/hello-agent-full"
BUILD_OUT="$CACHE/user-out"
BIN="$BUILD_OUT/hello-agent-full"

# Reset the plugin git repo whenever the source changed. Cheap: git init
# + commit + tag; skips network entirely (Dep points at a file path).
if [[ ! -f "$CACHE/plugin.stamp" ]] || \
   [[ "$(find "$PLUGIN_SRC" -type f -newer "$CACHE/plugin.stamp" 2>/dev/null | head -1)" != "" ]]; then
  rm -rf "$PLUGIN_REPO" "$BUILD_SRC" "$BUILD_OUT"
  mkdir -p "$PLUGIN_REPO" "$BUILD_SRC" "$BUILD_OUT"
  (cd "$PLUGIN_SRC" && tar cf - .) | (cd "$PLUGIN_REPO" && tar xf -)
  git -C "$PLUGIN_REPO" -c commit.gpgsign=false -c user.email=t@t -c user.name=t init -q
  git -C "$PLUGIN_REPO" -c commit.gpgsign=false -c user.email=t@t -c user.name=t add -A
  git -C "$PLUGIN_REPO" -c commit.gpgsign=false -c user.email=t@t -c user.name=t commit -q -m init
  git -C "$PLUGIN_REPO" -c commit.gpgsign=false -c user.email=t@t -c user.name=t tag -f v0.1
  cat > "$BUILD_SRC/project.cxc" <<EOF
Project(
    name:  "hello-agent-full",
    entry: "main",
    deps: [
        Dep(name: "com.acme.http_claude",
            git:  "$PLUGIN_REPO",
            tag:  "v0.1")
    ])
EOF
  cp "$HERE/main.cxc" "$BUILD_SRC/main.cxc"
  (cd "$COMPILER_DIR" && /opt/homebrew/bin/clojure -M -m cxc.core "$BUILD_SRC" "$BUILD_OUT")
  touch "$CACHE/plugin.stamp"
elif [[ "$HERE/main.cxc" -nt "$BIN" ]]; then
  # main.cxc alone changed → recompile without regenerating the plugin
  cp "$HERE/main.cxc" "$BUILD_SRC/main.cxc"
  (cd "$COMPILER_DIR" && /opt/homebrew/bin/clojure -M -m cxc.core "$BUILD_SRC" "$BUILD_OUT")
fi

exec "$BIN" "$@"
