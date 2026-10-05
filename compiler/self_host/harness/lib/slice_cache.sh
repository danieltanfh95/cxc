# Content-addressed cache for upstream slice outputs (chapter 36).
#
# Sourced by the pipeline harnesses (36 -> 38 -> 37 -> 39 -> 40 -> 41).
# A pipeline harness runs every earlier port on every row before the
# slice under test sees it, and the earlier ports do not change between
# runs, so their output for a given input is cached on disk.
#
#   slice_cache_register <name> <binary>
#       Keys the port by the sha256 of its binary. A rebuilt binary (new
#       source, new compiler, new prelude) has a new hash, so a stale
#       entry is never served.
#   slice_cached <name> <arg>...
#       Runs the registered binary with the args, or replays its stdout
#       and exit status from the cache. The key is the binary hash plus
#       each arg, where an arg naming a file contributes the sha256 of
#       the file's content and an arg naming a directory the hashes of
#       every file under it, so temporary paths never matter.
#
# Only upstream stages go through `slice_cached`. The slice under test
# always runs its own binary directly.
#
# CXC_SLICE_CACHE names the cache directory (default
# /tmp/cxc-slice-cache, outside the repo). CXC_SLICE_CACHE=off runs
# every stage directly. `slice_cache_report` prints the hit count.

SLICE_CACHE_DIR="${CXC_SLICE_CACHE:-/tmp/cxc-slice-cache}"
SLICE_CACHE_HITS=0
SLICE_CACHE_MISSES=0
declare -A SLICE_CACHE_BIN=()
declare -A SLICE_CACHE_SUM=()
[ "$SLICE_CACHE_DIR" = off ] || mkdir -p "$SLICE_CACHE_DIR"

slice_cache_register() {
  SLICE_CACHE_BIN[$1]="$2"
  SLICE_CACHE_SUM[$1]="$(sha256sum < "$2" | cut -c1-64)"
}

# The key stream for one call, hashed by the caller.
_slice_cache_stream() {
  local a
  printf 'bin\0%s\0' "$1"
  shift
  for a in "$@"; do
    if [ -f "$a" ]; then
      printf 'file\0'
      sha256sum < "$a" | cut -c1-64
    elif [ -d "$a" ]; then
      printf 'dir\0'
      (cd "$a" && find . -type f | LC_ALL=C sort | while IFS= read -r f; do
         printf '%s\0' "$f"
         sha256sum < "$f" | cut -c1-64
       done)
    else
      printf 'arg\0%s\0' "$a"
    fi
  done
}

slice_cached() {
  local name="$1"
  shift
  local bin="${SLICE_CACHE_BIN[$name]}"
  if [ "$SLICE_CACHE_DIR" = off ]; then
    "$bin" "$@"
    return
  fi
  local key
  key="$(_slice_cache_stream "${SLICE_CACHE_SUM[$name]}" "$@" | sha256sum | cut -c1-64)"
  local ent="$SLICE_CACHE_DIR/${key:0:2}/$key"
  if [ -f "$ent.rc" ]; then
    SLICE_CACHE_HITS=$((SLICE_CACHE_HITS + 1))
    cat "$ent.out"
    return "$(cat "$ent.rc")"
  fi
  SLICE_CACHE_MISSES=$((SLICE_CACHE_MISSES + 1))
  mkdir -p "${ent%/*}"
  local tmp="$ent.$$.tmp" rc=0
  "$bin" "$@" > "$tmp" || rc=$?
  cat "$tmp"
  # Out first, rc last, so a reader that sees the rc file sees the
  # whole output. A rename keeps concurrent harnesses safe.
  mv -f "$tmp" "$ent.out"
  printf '%s\n' "$rc" > "$ent.rc.$$" && mv -f "$ent.rc.$$" "$ent.rc"
  return "$rc"
}

slice_cache_report() {
  echo "[harness] slice cache: $SLICE_CACHE_HITS hits, $SLICE_CACHE_MISSES misses ($SLICE_CACHE_DIR)"
}
