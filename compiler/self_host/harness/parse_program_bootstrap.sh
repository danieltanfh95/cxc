#!/usr/bin/env bash
# Parity harness for the chapter-30 whole-program parse slice (slice
# 36). Two passes, both through argv[2] `prog` on the port and the
# public `cxc.parse/parse` in the oracle.
#
# 1. The corpus, hand rows plus seeded generator rows (seed 30).
# 2. The real-world sweep. Every `.cxc` file under compiler/examples,
#    design/examples, and compiler/resources/prelude, in byte order of
#    its path, collapsed to one row on the fly (a literal backslash-n
#    becomes `\N`, then each newline becomes `\n`, the chapter-23
#    corpus convention). The rows are rebuilt on every run, so a new or
#    edited example joins the sweep without a corpus update.

set -euo pipefail

pkill -f cxc.daemon >/dev/null 2>&1 || true

HERE="$(cd "$(dirname "$0")" && pwd)"
SELF_HOST="$(cd "$HERE/.." && pwd)"
COMPILER_DIR="$(cd "$SELF_HOST/.." && pwd)"
REPO_DIR="$(cd "$COMPILER_DIR/.." && pwd)"

CORPUS="$SELF_HOST/corpus/parse_program_bootstrap.txt"
SRC="$SELF_HOST/src/parse_program_bootstrap.cxc"
ORACLE_CLJ="$SELF_HOST/oracle/parse_program_bootstrap.clj"

WORK="$(mktemp -d)"
CXC_OUT_DIR="$WORK/cxc_build"
CXC_OUT="$WORK/cxc_out.txt"
ORACLE_OUT="$WORK/oracle_out.txt"
SWEEP="$WORK/sweep_rows.txt"

echo "[harness] collapsing the real-world sweep"
: > "$SWEEP"
(cd "$REPO_DIR" && find compiler/examples design/examples compiler/resources/prelude -name '*.cxc' | LC_ALL=C sort) |
while IFS= read -r f; do
  perl -0pe 's/\\n/\\N/g; s/\n/\\n/g; $_ .= "\n"' "$REPO_DIR/$f" >> "$SWEEP"
done
echo "[harness] sweep rows: $(wc -l < "$SWEEP")"

echo "[harness] building cxc port"
rm -f "$CXC_OUT_DIR/parse_program_bootstrap"
"$COMPILER_DIR/bin/cxc" compile "$SRC" "$CXC_OUT_DIR" >/dev/null
BIN="$CXC_OUT_DIR/parse_program_bootstrap"
if [ ! -x "$BIN" ]; then
  echo "[harness] cxc build produced no executable at $BIN" >&2
  exit 1
fi

echo "[harness] running cxc port on corpus"
: > "$CXC_OUT"
while IFS= read -r line || [ -n "$line" ]; do
  "$BIN" "$line" prog >> "$CXC_OUT"
done < "$CORPUS"
echo "[harness] running cxc port on the sweep"
while IFS= read -r line || [ -n "$line" ]; do
  "$BIN" "$line" prog >> "$CXC_OUT"
done < "$SWEEP"

echo "[harness] running clj oracle on corpus and sweep"
(cd "$COMPILER_DIR" && clj -M -e "$(cat "$ORACLE_CLJ")") < "$CORPUS" > "$ORACLE_OUT"
(cd "$COMPILER_DIR" && clj -M -e "$(cat "$ORACLE_CLJ")") < "$SWEEP" >> "$ORACLE_OUT"

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
