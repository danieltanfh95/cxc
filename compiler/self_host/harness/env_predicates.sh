#!/usr/bin/env bash
# Parity harness for the env predicate port.
#
# Each corpus query line is `<kind>|<arg>`. For `lookup` queries the
# env file path is appended to the argv as a third positional. The
# oracle reads the env file via CXC_ENV_FILE.

set -euo pipefail

# Chapter 4 (2026-10-04): kill any stale cxc-daemon before this harness
# builds. The daemon caches loaded source and does not auto-reload on
# compiler-side edits; see memory corvid-stale-daemon-bites-parity.
pkill -f cxc.daemon >/dev/null 2>&1 || true

HERE="$(cd "$(dirname "$0")" && pwd)"
SELF_HOST="$(cd "$HERE/.." && pwd)"
COMPILER_DIR="$(cd "$SELF_HOST/.." && pwd)"

ENV_FILE="$SELF_HOST/corpus/env_predicates_env.txt"
CORPUS="$SELF_HOST/corpus/env_predicates.txt"
SRC="$SELF_HOST/src/env_predicates.cxc"
ORACLE_CLJ="$SELF_HOST/oracle/env_predicates.clj"

WORK="$(mktemp -d)"
CXC_OUT_DIR="$WORK/cxc_build"
CXC_OUT="$WORK/cxc_out.txt"
ORACLE_OUT="$WORK/oracle_out.txt"

echo "[harness] building cxc port"
"$COMPILER_DIR/bin/cxc" compile "$SRC" "$CXC_OUT_DIR" >/dev/null
BIN="$CXC_OUT_DIR/env_predicates"
if [ ! -x "$BIN" ]; then
  echo "[harness] cxc build produced no executable at $BIN" >&2
  exit 1
fi

echo "[harness] running cxc port on corpus"
: > "$CXC_OUT"
while IFS='|' read -r kind arg || [ -n "$kind" ]; do
  "$BIN" "$kind" "$arg" "$ENV_FILE" >> "$CXC_OUT"
done < "$CORPUS"

echo "[harness] running clj oracle on corpus"
(cd "$COMPILER_DIR" && CXC_ENV_FILE="$ENV_FILE" clj -M -e "$(cat "$ORACLE_CLJ")") < "$CORPUS" > "$ORACLE_OUT"

echo "[harness] diffing"
if diff -q "$CXC_OUT" "$ORACLE_OUT"; then
  echo "[harness] PARITY OK"
  rm -rf "$WORK"
  exit 0
else
  echo "[harness] PARITY FAIL. outputs preserved at $WORK"
  diff "$CXC_OUT" "$ORACLE_OUT" | head -40 || true
  exit 2
fi
