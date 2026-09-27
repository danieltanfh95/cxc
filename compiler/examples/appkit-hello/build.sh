#!/usr/bin/env bash
# build.sh — build the minimal AppKit hello demo.
#
# Same shape as ../appkit-viewer/build.sh but slimmer. The `appkit/`
# module is local (its own window.cxc + plugin manifest), but the
# native side (libappkit.m) is shared with appkit-viewer to avoid
# duplicating ~400 lines of Cocoa glue. If you're extracting this
# example on its own, copy ../appkit-viewer/appkit/libappkit.m into
# appkit/ and drop the SHARED_LIB reference below.
#
# Usage:  ./build.sh

set -euo pipefail

HERE="$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
COMPILER_DIR="$( cd "$HERE/../.." && pwd )"
SHARED_LIB="$HERE/../appkit-viewer/appkit/libappkit.m"
APPKIT_DIR="$HERE/appkit"
BIN_NAME="hello"
OUT_DIR="/tmp/appkit-hello-out"

echo "==> building libappkit.dylib (shared from ../appkit-viewer)"
clang -dynamiclib -fobjc-arc \
    -install_name "@rpath/libappkit.dylib" \
    "$SHARED_LIB" \
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
    -Wno-implicit-function-declaration \
    -framework Foundation -framework Cocoa \
    -L"$APPKIT_DIR" \
    -Wl,-rpath,"$APPKIT_DIR" \
    -lappkit \
    "$OUT_DIR/$BIN_NAME.m" \
    -o "$OUT_DIR/$BIN_NAME"

echo "==> built: $OUT_DIR/$BIN_NAME"
echo "run it: $OUT_DIR/$BIN_NAME"
