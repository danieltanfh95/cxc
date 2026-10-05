#!/usr/bin/env bash
# Parity harness for the chapter-33 environment-builder slice (slice 39).
# A composition harness. Source rows go through slice 36
# (`parse_program_bootstrap`, argv[2] `prog`) and slice 38
# (`prelude_program`, user mode), the outputs are assembled into one
# `(stream ...)` file with the slice-38 prelude stream in front, and slice
# 39 (`lower_envs`) prints the environments. The oracle runs the real
# Clojure builders on the same rows.
#
# Rows are `<stream>-<check>|<payload>` (see `oracle/lower_envs.clj`).
#   full   prelude stream, then the user nodes of the row.
#   raw    slice 36 output alone, no prelude and no slice-38 pass.
#   multi  tab-separated `<module>=<source>` pieces, each through slice
#          36 and 38, after the prelude stream, each behind a
#          `(module <module>)` marker unless <module> is empty.
#   check  `lower` (real lower/lower order, with the `def main` check) or
#          `envs` (the builders alone).
# An `error|...` from slice 36 or 38 is the stream file, and slice 39
# passes it through.
#
# Then the real-world sweep (every `.cxc` file under compiler/examples,
# design/examples, and compiler/resources/prelude, collapsed on the fly
# as in the slice-36 harness) in `full-envs`, and its first three files
# in `full-lower`.

set -euo pipefail

pkill -f cxc.daemon >/dev/null 2>&1 || true

HERE="$(cd "$(dirname "$0")" && pwd)"
SELF_HOST="$(cd "$HERE/.." && pwd)"
COMPILER_DIR="$(cd "$SELF_HOST/.." && pwd)"
REPO_DIR="$(cd "$COMPILER_DIR/.." && pwd)"

CORPUS="$SELF_HOST/corpus/lower_envs.txt"
PARSE_SRC="$SELF_HOST/src/parse_program_bootstrap.cxc"
P38_SRC="$SELF_HOST/src/prelude_program.cxc"
SRC="$SELF_HOST/src/lower_envs.cxc"
ORACLE_CLJ="$SELF_HOST/oracle/lower_envs.clj"
P38_CLJ="$SELF_HOST/oracle/prelude_program.clj"
TABLES_CLJ="$SELF_HOST/oracle/prelude_program_tables.clj"
INDEX_CLJ="$SELF_HOST/oracle/resolve_program_symbols_index.clj"
SER_CLJ="$SELF_HOST/oracle/parse_program_bootstrap.clj"

WORK="$(mktemp -d)"
CXC_OUT="$WORK/cxc_out.txt"
ORACLE_IN="$WORK/oracle_in.txt"
ORACLE_OUT="$WORK/oracle_out.txt"
SWEEP="$WORK/sweep_rows.txt"
INDEX="$WORK/prelude_index.txt"
STDLIB="$WORK/stdlib.txt"
REGISTRY="$WORK/registry.txt"
ASM="$WORK/prelude_row.txt"
PSTREAM="$WORK/prelude_stream.txt"
ONE="$WORK/one.txt"
TWO="$WORK/two.txt"
STREAM="$WORK/stream.txt"

echo "[harness] collapsing the real-world sweep"
: > "$SWEEP"
(cd "$REPO_DIR" && find compiler/examples design/examples compiler/resources/prelude -name '*.cxc' | LC_ALL=C sort) |
while IFS= read -r f; do
  perl -0pe 's/\\n/\\N/g; s/\n/\\n/g; $_ .= "\n"' "$REPO_DIR/$f" >> "$SWEEP"
done
echo "[harness] sweep rows: $(wc -l < "$SWEEP")"

echo "[harness] writing the ambient tables"
(cd "$COMPILER_DIR" && clj -M -e "$(cat "$INDEX_CLJ")") > "$INDEX"
(cd "$COMPILER_DIR" && CXC_TABLE=stdlib clj -M -e "$(cat "$TABLES_CLJ")") > "$STDLIB"
(cd "$COMPILER_DIR" && CXC_TABLE=registry clj -M -e "$(cat "$TABLES_CLJ")") > "$REGISTRY"

echo "[harness] building cxc ports"
"$COMPILER_DIR/bin/cxc" compile "$PARSE_SRC" "$WORK/p36" >/dev/null
"$COMPILER_DIR/bin/cxc" compile "$P38_SRC" "$WORK/p38" >/dev/null
"$COMPILER_DIR/bin/cxc" compile "$SRC" "$WORK/p39" >/dev/null
PARSE_BIN="$WORK/p36/parse_program_bootstrap"
P38_BIN="$WORK/p38/prelude_program"
BIN="$WORK/p39/lower_envs"
for b in "$PARSE_BIN" "$P38_BIN" "$BIN"; do
  if [ ! -x "$b" ]; then
    echo "[harness] cxc build produced no executable at $b" >&2
    exit 1
  fi
done

echo "[harness] building the prelude stream"
"$P38_BIN" assemble "$REGISTRY" "$COMPILER_DIR/resources" > "$ASM"
"$PARSE_BIN" "$(cat "$ASM")" prog > "$ONE"
"$P38_BIN" prelude "$ONE" "$INDEX" > "$PSTREAM"
PIN="$(cat "$PSTREAM")"
PIN="${PIN#(program}"
PIN="${PIN%)}"

# The nodes of one rendering, `(program X Y)` to ` X Y`.
inner() {
  local s
  s="$(cat "$1")"
  s="${s#(program}"
  printf '%s' "${s%)}"
}

# One piece through slice 36 (and slice 38 unless raw) into $TWO.
piece() {
  local kind="$1" src="$2"
  "$PARSE_BIN" "$src" prog > "$ONE"
  if [ "$kind" = raw ]; then
    cp "$ONE" "$TWO"
  else
    "$P38_BIN" user "$ONE" "$INDEX" "$STDLIB" > "$TWO"
  fi
}

run_row() {
  local mode="$1" payload="$2"
  local kind="${mode%%-*}" check="${mode#*-}"
  printf '%s|%s\n' "$mode" "$payload" >> "$ORACLE_IN"
  local body="" err=""
  case "$kind" in
    raw|full)
      piece "$kind" "$payload"
      if grep -q '^error|' "$TWO"; then err="$(cat "$TWO")"; else body="$(inner "$TWO")"; fi
      [ "$kind" = full ] && body="$PIN$body"
      ;;
    multi)
      body="$PIN"
      local pcs pc
      IFS=$'\t' read -r -a pcs <<< "$payload"
      for pc in "${pcs[@]}"; do
        piece full "${pc#*=}"
        if [ -z "$err" ] && grep -q '^error|' "$TWO"; then err="$(cat "$TWO")"; fi
        local m="${pc%%=*}"
        [ -n "$m" ] && body="$body (module $m)"
        body="$body$(inner "$TWO")"
      done
      ;;
  esac
  if [ -n "$err" ]; then printf '%s\n' "$err" > "$STREAM"; else printf '(stream%s)\n' "$body" > "$STREAM"; fi
  "$BIN" "$check" "$STREAM" "$INDEX" >> "$CXC_OUT"
}

: > "$CXC_OUT"
: > "$ORACLE_IN"

echo "[harness] running the composition on the hand corpus"
while IFS= read -r line || [ -n "$line" ]; do
  run_row "${line%%|*}" "${line#*|}"
done < "$CORPUS"

echo "[harness] running the composition on the sweep"
[ -n "${CXC_NO_SWEEP:-}" ] && : > "$SWEEP"
while IFS= read -r line || [ -n "$line" ]; do
  run_row full-envs "$line"
done < "$SWEEP"
head -3 "$SWEEP" | while IFS= read -r line; do
  run_row full-lower "$line"
done

echo "[harness] running clj oracle"
(cd "$COMPILER_DIR" && CXC_P38_ORACLE="$P38_CLJ" CXC_SER_ORACLE="$SER_CLJ" clj -M -e "$(cat "$ORACLE_CLJ")") < "$ORACLE_IN" > "$ORACLE_OUT"

echo "[harness] output lines: $(wc -l < "$CXC_OUT"), errors: $(grep -c '^error|' "$CXC_OUT" || true)"
echo "[harness] diffing"
if diff -q "$CXC_OUT" "$ORACLE_OUT"; then
  echo "[harness] PARITY OK"
  rm -rf "$WORK"
  exit 0
else
  echo "[harness] PARITY FAIL. outputs preserved at $WORK"
  diff "$CXC_OUT" "$ORACLE_OUT" | head -40 | cut -c1-600 || true
  exit 2
fi
