#!/usr/bin/env bash
# build.sh — build the AppKit plugin dylib + the hello_window Corvid demo.
#
# Prototype A of the runtime-shape landing. The plugin manifest in
# hello_window.cxc declares `runtime_shape: RuntimeShape.main_runloop`,
# and the (modified) compiler emits `[NSApp run]` at the tail of main()
# instead of `dispatch_main()`.
#
# Usage:  ./build.sh [<demo.cxc>]

set -euo pipefail

HERE="$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
COMPILER_DIR="$( cd "$HERE/../.." && pwd )"
DEMO="${1:-$HERE/hello_window.cxc}"
DEMO_NAME="$( basename "$DEMO" .cxc )"
OUT_DIR="/tmp/appkit-out"

echo "==> building libappkit.dylib"
clang -dynamiclib -fobjc-arc \
    -install_name "@rpath/libappkit.dylib" \
    "$HERE/libappkit.m" \
    -framework Cocoa \
    -o "$HERE/libappkit.dylib"

echo "==> compiling Corvid → Objective-C"
mkdir -p "$OUT_DIR"
cd "$COMPILER_DIR"
clojure -M -e "
(require '[cxc.build :as b])
(let [src (slurp \"$DEMO\")
      out \"$OUT_DIR\"]
  (clojure.java.io/make-parents (str out \"/x\"))
  (let [r (b/compile-source-to-file src \"$DEMO_NAME\" out)]
    (when (seq (:stderr r)) (println \"COMPILER STDERR:\") (println (:stderr r)))
    (println \"status:\" (:status r))))
"

echo "==> linking against libappkit.dylib"
clang -fobjc-arc \
    -Wno-implicit-function-declaration \
    -framework Foundation -framework Cocoa \
    -L"$HERE" \
    -Wl,-rpath,"$HERE" \
    -lappkit \
    "$OUT_DIR/$DEMO_NAME.m" \
    -o "$OUT_DIR/$DEMO_NAME"

echo "==> built: $OUT_DIR/$DEMO_NAME"
echo "run it: $OUT_DIR/$DEMO_NAME"
