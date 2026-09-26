#!/usr/bin/env bash
# build.sh — compile the Cocoa viewer plugin + a Corvid demo that uses it.
#
# Usage:
#   ./build.sh [<demo.cxc>]
#
# Default demo is dota2_frame.cxc. Produces:
#   ./libcocoa_viewer.dylib
#   /tmp/cocoa-viewer-out/<demo_name>
#
# Then: run the produced binary. A Cocoa window will open.
# Quit via Cmd-Q or by closing the window.

set -euo pipefail

HERE="$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
COMPILER_DIR="$( cd "$HERE/../.." && pwd )"
DEMO="${1:-$HERE/dota2_frame.cxc}"
DEMO_NAME="$( basename "$DEMO" .cxc )"
OUT_DIR="/tmp/cocoa-viewer-out"

echo "==> building dylib"
clang -dynamiclib \
    -install_name "@rpath/libcocoa_viewer.dylib" \
    "$HERE/libcocoa_viewer.m" \
    -framework Cocoa \
    -o "$HERE/libcocoa_viewer.dylib"

echo "==> building viewerd"
clang -fobjc-arc \
    "$HERE/viewerd.m" \
    -framework Cocoa \
    -o "$HERE/viewerd"

echo "==> compiling Corvid → Objective-C"
mkdir -p "$OUT_DIR"
cd "$COMPILER_DIR"
clojure -M -e "
(require '[cxc.build :as b])
(let [src (slurp \"$DEMO\")
      out \"$OUT_DIR\"]
  (clojure.java.io/make-parents (str out \"/x\"))
  (let [r (b/compile-source-to-file src \"$DEMO_NAME\" out)]
    (when (seq (:stderr r)) (println \"COMPILER STDERR:\") (println (:stderr r)))))
"

echo "==> linking against libcocoa_viewer.dylib"
clang -fobjc-arc \
    -Wno-implicit-function-declaration \
    -framework Foundation -framework Cocoa \
    -L"$HERE" \
    -Wl,-rpath,"$HERE" \
    -lcocoa_viewer \
    "$OUT_DIR/$DEMO_NAME.m" \
    -o "$OUT_DIR/$DEMO_NAME"

echo "==> built: $OUT_DIR/$DEMO_NAME"
echo "run it: $OUT_DIR/$DEMO_NAME"
