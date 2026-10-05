#!/usr/bin/env bash
# Parity harness for the chapter-37 companion and container slice (slice
# 42). A pipeline harness. Each row goes through slice 36
# (`parse_program_bootstrap`, argv[2] `prog`), slice 38 (`prelude_program`,
# user mode), and slice 37 (refs mode) into the slice-39 stream and refs
# files, exactly as `harness/lower_observables.sh` builds them. Slice 39
# (`lower_envs`), slice 40 (`lower_records`), and slice 41
# (`lower_observables`) then run on them in the row's check, and their
# printed environments are the input of slice 42 (`lower_containers`),
# which never recomputes them. Slice 42 reads slice 39's `(enum ...)`,
# `(keep ...)`, and `(atom ...)` rows with slice 40's `(record ...)` rows,
# and slice 41's `(spi ...)` rows, an `error|` line from any of them
# passing through. Every upstream stage goes through the content-addressed
# slice cache (`lib/slice_cache.sh`), slice 42 always runs.
#
# Rows are `<stream>-<check>|<payload>` as in the slice-39 harness.
# CXC_CORPUS replaces the hand corpus, CXC_NO_SWEEP skips the sweep, and
# CXC_PREBUILT names a directory holding the seven built ports (p36 p37
# p38 p39 p40 p41 p42) to skip the builds.
#
# Then the real-world sweep (every `.cxc` file under compiler/examples,
# design/examples, and compiler/resources/prelude) in `full-envs`, and its
# first three files in `full-lower`.

set -euo pipefail

pkill -f cxc.daemon >/dev/null 2>&1 || true

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib/slice_cache.sh
. "$HERE/lib/slice_cache.sh"
SELF_HOST="$(cd "$HERE/.." && pwd)"
COMPILER_DIR="$(cd "$SELF_HOST/.." && pwd)"
REPO_DIR="$(cd "$COMPILER_DIR/.." && pwd)"

CORPUS="${CXC_CORPUS:-$SELF_HOST/corpus/lower_containers.txt}"
PARSE_SRC="$SELF_HOST/src/parse_program_bootstrap.cxc"
P38_SRC="$SELF_HOST/src/prelude_program.cxc"
P37_SRC="$SELF_HOST/src/resolve_program_symbols.cxc"
P39_SRC="$SELF_HOST/src/lower_envs.cxc"
P40_SRC="$SELF_HOST/src/lower_records.cxc"
P41_SRC="$SELF_HOST/src/lower_observables.cxc"
SRC="$SELF_HOST/src/lower_containers.cxc"
ORACLE_CLJ="$SELF_HOST/oracle/lower_containers.clj"
P41_CLJ="$SELF_HOST/oracle/lower_observables.clj"
P40_CLJ="$SELF_HOST/oracle/lower_records.clj"
FFI_CLJ="$SELF_HOST/oracle/lower_observables_tables.clj"
P39_CLJ="$SELF_HOST/oracle/lower_envs.clj"
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
REFS="$WORK/refs.txt"
PREFS="$WORK/prelude_refs.txt"
ENVS="$WORK/envs.txt"
ENVS41="$WORK/envs41.txt"
P41OUT="$WORK/p41_out.txt"
ROWS42="$WORK/rows42.txt"
SPI42="$WORK/spi42.txt"
FFI="$WORK/ffi.txt"

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
(cd "$COMPILER_DIR" && clj -M -e "$(cat "$FFI_CLJ")") > "$FFI"

echo "[harness] building cxc ports"
if [ -n "${CXC_PREBUILT:-}" ]; then
  # Reuse the seven binaries under $CXC_PREBUILT/p36 to p42.
  for d in p36 p37 p38 p39 p40 p41 p42; do cp -r "$CXC_PREBUILT/$d" "$WORK/$d"; done
else
  "$COMPILER_DIR/bin/cxc" compile "$PARSE_SRC" "$WORK/p36" >/dev/null
  "$COMPILER_DIR/bin/cxc" compile "$P38_SRC" "$WORK/p38" >/dev/null
  "$COMPILER_DIR/bin/cxc" compile "$P37_SRC" "$WORK/p37" >/dev/null
  "$COMPILER_DIR/bin/cxc" compile "$P39_SRC" "$WORK/p39" >/dev/null
  "$COMPILER_DIR/bin/cxc" compile "$P40_SRC" "$WORK/p40" >/dev/null
  "$COMPILER_DIR/bin/cxc" compile "$P41_SRC" "$WORK/p41" >/dev/null
  "$COMPILER_DIR/bin/cxc" compile "$SRC" "$WORK/p42" >/dev/null
fi
PARSE_BIN="$WORK/p36/parse_program_bootstrap"
P38_BIN="$WORK/p38/prelude_program"
P37_BIN="$WORK/p37/resolve_program_symbols"
P39_BIN="$WORK/p39/lower_envs"
P40_BIN="$WORK/p40/lower_records"
P41_BIN="$WORK/p41/lower_observables"
BIN="$WORK/p42/lower_containers"
for b in "$PARSE_BIN" "$P38_BIN" "$P37_BIN" "$P39_BIN" "$P40_BIN" "$P41_BIN" "$BIN"; do
  if [ ! -x "$b" ]; then
    echo "[harness] cxc build produced no executable at $b" >&2
    exit 1
  fi
done
slice_cache_register p36 "$PARSE_BIN"
slice_cache_register p38 "$P38_BIN"
slice_cache_register p37 "$P37_BIN"
slice_cache_register p39 "$P39_BIN"
slice_cache_register p40 "$P40_BIN"
slice_cache_register p41 "$P41_BIN"

echo "[harness] building the prelude stream"
slice_cached p38 assemble "$REGISTRY" "$COMPILER_DIR/resources" > "$ASM"
slice_cached p36 "$(cat "$ASM")" prog > "$ONE"
slice_cached p38 prelude "$ONE" "$INDEX" > "$PSTREAM"
slice_cached p38 praw "$ONE" "$INDEX" > "$TWO"
slice_cached p38 prefs "$TWO" "$INDEX" > "$PREFS"
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

# One piece through slice 36 (and slice 38 unless raw) into $TWO. A
# user piece also appends its slice-37 refs rows to $REFS as piece <n>.
piece() {
  local kind="$1" src="$2" n="$3"
  slice_cached p36 "$src" prog > "$ONE"
  if [ "$kind" = raw ]; then
    cp "$ONE" "$TWO"
  else
    slice_cached p38 user "$ONE" "$INDEX" "$STDLIB" > "$TWO"
    printf '(piece %s)\n' "$n" >> "$REFS"
    slice_cached p37 "$TWO" "$INDEX" "" refs >> "$REFS"
  fi
}

run_row() {
  local mode="$1" payload="$2"
  local kind="${mode%%-*}" check="${mode#*-}"
  printf '%s|%s\n' "$mode" "$payload" >> "$ORACLE_IN"
  local body="" err=""
  : > "$REFS"
  case "$kind" in
    raw)
      piece raw "$payload" 1
      if grep -q '^error|' "$TWO"; then err="$(cat "$TWO")"; else body="$(inner "$TWO")"; fi
      ;;
    full)
      { echo '(piece 0)'; cat "$PREFS"; } >> "$REFS"
      piece full "$payload" 1
      if grep -q '^error|' "$TWO"; then err="$(cat "$TWO")"; else body="$PIN (piece)$(inner "$TWO")"; fi
      ;;
    multi)
      { echo '(piece 0)'; cat "$PREFS"; } >> "$REFS"
      body="$PIN"
      local pcs pc m="" n=0
      IFS=$'\t' read -r -a pcs <<< "$payload"
      for pc in "${pcs[@]}"; do
        n=$((n + 1))
        piece full "${pc#*=}" "$n"
        if [ -z "$err" ] && grep -q '^error|' "$TWO"; then err="$(cat "$TWO")"; fi
        m="${pc%%=*}"
        [ -n "$m" ] && body="$body (module $m)"
        body="$body (piece)$(inner "$TWO")"
      done
      ;;
  esac
  if [ -n "$err" ]; then printf '%s\n' "$err" > "$STREAM"; else printf '(stream%s)\n' "$body" > "$STREAM"; fi
  slice_cached p39 "$check" "$STREAM" "$INDEX" "$REFS" > "$TWO"
  grep -E '^(\((decl|module|enum|keep) |error\|)' "$TWO" > "$ENVS" || true
  grep -E '^(\((decl|enum|keep|atom) |error\|)' "$TWO" > "$ENVS41" || true
  slice_cached p40 "$check" "$STREAM" "$INDEX" "$REFS" "$ENVS" | grep -E '^(\(record |error\|)' >> "$ENVS41" || true
  slice_cached p41 "$check" "$STREAM" "$ENVS41" "$FFI" > "$P41OUT"
  grep -E '^(\((enum|keep|atom|record) |error\|)' "$ENVS41" > "$ROWS42" || true
  grep -E '^(\(spi |error\|)' "$P41OUT" > "$SPI42" || true
  "$BIN" "$check" "$ROWS42" "$SPI42" >> "$CXC_OUT"
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

slice_cache_report
echo "[harness] running clj oracle"
(cd "$COMPILER_DIR" && CXC_P41_ORACLE="$P41_CLJ" CXC_P40_ORACLE="$P40_CLJ" CXC_P39_ORACLE="$P39_CLJ" CXC_P38_ORACLE="$P38_CLJ" CXC_SER_ORACLE="$SER_CLJ" clj -M -e "$(cat "$ORACLE_CLJ")") < "$ORACLE_IN" > "$ORACLE_OUT"

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
