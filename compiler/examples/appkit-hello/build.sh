#!/usr/bin/env bash
# build.sh — build the minimal AppKit hello demo.
#
# The `appkit/` module is self-contained: its own window.cxc plugin
# manifest plus the Cocoa glue in libappkit.m.
#
# Usage:  ./build.sh

set -euo pipefail

HERE="$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
COMPILER_DIR="$( cd "$HERE/../.." && pwd )"
APPKIT_DIR="$HERE/appkit"
NATIVE_SRC="$APPKIT_DIR/libappkit.m"
BIN_NAME="hello"
OUT_DIR="/tmp/appkit-hello-out"

echo "==> building libappkit.dylib"
clang -dynamiclib -fobjc-arc \
    -install_name "@rpath/libappkit.dylib" \
    "$NATIVE_SRC" \
    -framework Cocoa \
    -o "$APPKIT_DIR/libappkit.dylib"

echo "==> compiling Corvid → Objective-C (multi-file)"
mkdir -p "$OUT_DIR"
cd "$COMPILER_DIR"
clojure -M -e "
(require '[cxc.build :as b])
(let [src-dir \"$HERE\"
      out     \"$OUT_DIR\"]
  (clojure.java.io/make-parents (str out \"/x\"))
  (let [r (b/compile-directory-to-file src-dir \"$BIN_NAME\" out)]
    (when (seq (:stderr r)) (println \"COMPILER STDERR:\") (println (:stderr r)))
    (println \"status:\" (:status r))))
"

echo "==> linking against libappkit.dylib"
clang -fobjc-arc \
    -framework Foundation -framework Cocoa \
    -L"$APPKIT_DIR" \
    -Wl,-rpath,"$APPKIT_DIR" \
    -lappkit \
    "$OUT_DIR/$BIN_NAME.m" \
    -o "$OUT_DIR/$BIN_NAME"

echo "==> built: $OUT_DIR/$BIN_NAME"
echo "run it: $OUT_DIR/$BIN_NAME"
