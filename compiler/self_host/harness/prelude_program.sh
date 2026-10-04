#!/usr/bin/env bash
# Parity harness for the chapter-32 pipeline slice (slice 38). A
# composition harness. Source rows go through slice 36
# (`parse_program_bootstrap`, argv[2] `prog`) and its output, through a
# file, into slice 38 (`prelude_program`). The oracle runs the real
# Clojure passes in `build/compile-source-to-file` order on the same
# rows.
#
# Ambient tables, written once per run. The slice-37 prelude index
# (`resolve_program_symbols_index.clj`), and `build/stdlib-manifest` and
# `prelude/prelude-modules` (`prelude_program_tables.clj`).
#
# Rows, in order.
# 1. Assemble. The port assembles the macOS prelude from the registry
#    table and `compiler/resources`, the oracle runs
#    `prelude/assemble-source :macos`. A second row uses a registry with
#    a module that does not exist.
# 2. The prelude stream. The port's assembled prelude through slice 36
#    and slice 38 prelude mode, against `build/process-prelude-nodes` on
#    the same source. Its output is kept as the prelude stream for the
#    `full` rows. Then the same through praw mode (the prelude chain
#    without the class-member desugar, `process-prelude-nodes`'s own
#    output) and prefs mode, which prints its references with the
#    `enrich-prelude-nodes` ladder.
# 3. The hand corpus, `<mode>|<source>` rows. `user` runs the user
#    chain, `prelude` the prelude chain on the row (then praw and prefs,
#    so a prelude row prints its stream and its refs), and `full` the user
#    chain with the prelude stream prepended, the whole node vector
#    `lower/lower` receives.
# 4. The real-world sweep in user mode (every `.cxc` file under
#    compiler/examples, design/examples, and compiler/resources/prelude,
#    collapsed to one row on the fly as in the slice-36 harness), then
#    the first three sweep files again in full mode.

set -euo pipefail

pkill -f cxc.daemon >/dev/null 2>&1 || true

HERE="$(cd "$(dirname "$0")" && pwd)"
SELF_HOST="$(cd "$HERE/.." && pwd)"
COMPILER_DIR="$(cd "$SELF_HOST/.." && pwd)"
REPO_DIR="$(cd "$COMPILER_DIR/.." && pwd)"

CORPUS="$SELF_HOST/corpus/prelude_program.txt"
PARSE_SRC="$SELF_HOST/src/parse_program_bootstrap.cxc"
SRC="$SELF_HOST/src/prelude_program.cxc"
ORACLE_CLJ="$SELF_HOST/oracle/prelude_program.clj"
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
BROKEN="$WORK/registry_broken.txt"
ASM="$WORK/prelude_row.txt"
PSTREAM="$WORK/prelude_stream.txt"
ONE="$WORK/one.txt"
TWO="$WORK/two.txt"

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
printf 'time|shared\nnot_a_module|shared\nclock|target\n' > "$BROKEN"
echo "[harness] tables: $(wc -l < "$INDEX") index, $(wc -l < "$STDLIB") stdlib, $(wc -l < "$REGISTRY") registry rows"

echo "[harness] building cxc ports"
"$COMPILER_DIR/bin/cxc" compile "$PARSE_SRC" "$WORK/p36" >/dev/null
"$COMPILER_DIR/bin/cxc" compile "$SRC" "$WORK/p38" >/dev/null
PARSE_BIN="$WORK/p36/parse_program_bootstrap"
BIN="$WORK/p38/prelude_program"
for b in "$PARSE_BIN" "$BIN"; do
  if [ ! -x "$b" ]; then
    echo "[harness] cxc build produced no executable at $b" >&2
    exit 1
  fi
done

: > "$CXC_OUT"
: > "$ORACLE_IN"

echo "[harness] assembling and processing the prelude"
"$BIN" assemble "$REGISTRY" "$COMPILER_DIR/resources" > "$ASM"
cat "$ASM" >> "$CXC_OUT"
"$BIN" assemble "$BROKEN" "$COMPILER_DIR/resources" >> "$CXC_OUT"
printf 'assemble|\nassemble|%s\n' "$BROKEN" >> "$ORACLE_IN"
"$PARSE_BIN" "$(cat "$ASM")" prog > "$ONE"
"$BIN" prelude "$ONE" "$INDEX" > "$PSTREAM"
cat "$PSTREAM" >> "$CXC_OUT"
"$BIN" praw "$ONE" "$INDEX" > "$TWO"
"$BIN" prefs "$TWO" "$INDEX" >> "$CXC_OUT"
printf 'prelude|%s\nprefs|%s\n' "$(cat "$ASM")" "$(cat "$ASM")" >> "$ORACLE_IN"
echo "[harness] prelude stream: $(wc -c < "$PSTREAM") bytes"

run_row() {
  local mode="$1" src="$2"
  "$PARSE_BIN" "$src" prog > "$ONE"
  printf '%s|%s\n' "$mode" "$src" >> "$ORACLE_IN"
  case "$mode" in
    user)    "$BIN" user "$ONE" "$INDEX" "$STDLIB" >> "$CXC_OUT" ;;
    full)    "$BIN" user "$ONE" "$INDEX" "$STDLIB" "$PSTREAM" >> "$CXC_OUT" ;;
    prelude) "$BIN" prelude "$ONE" "$INDEX" >> "$CXC_OUT"
             "$BIN" praw "$ONE" "$INDEX" > "$TWO"
             "$BIN" prefs "$TWO" "$INDEX" >> "$CXC_OUT"
             printf 'prefs|%s\n' "$src" >> "$ORACLE_IN" ;;
  esac
}

echo "[harness] running the composition on the hand corpus"
while IFS= read -r line || [ -n "$line" ]; do
  run_row "${line%%|*}" "${line#*|}"
done < "$CORPUS"

echo "[harness] running the composition on the sweep"
while IFS= read -r line || [ -n "$line" ]; do
  run_row user "$line"
done < "$SWEEP"
head -3 "$SWEEP" | while IFS= read -r line; do
  run_row full "$line"
done

echo "[harness] running clj oracle"
(cd "$COMPILER_DIR" && CXC_SER_ORACLE="$SER_CLJ" clj -M -e "$(cat "$ORACLE_CLJ")") < "$ORACLE_IN" > "$ORACLE_OUT"

echo "[harness] output rows: $(wc -l < "$CXC_OUT"), errors: $(grep -c '^error|' "$CXC_OUT" || true)"
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
