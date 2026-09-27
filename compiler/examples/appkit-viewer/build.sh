#!/usr/bin/env bash
# build.sh — build the AppKit plugin dylib + the Corvid demo.
#
# Multi-file compile: `appkit/window.cxc` declares the plugin manifest
# (including `runtime_shape: RuntimeShape.main_runloop`) and a `Window`
# capability atom; `main.cxc` does `from appkit import Window` and
# never touches the FFI or the manifest directly. The compiler routes
# the runtime shape from the imported module through emit so main()
# still ends in `[NSApp run]` under the hood.
#
# The native side of the plugin (libappkit.m) lives in appkit/
# alongside the Corvid module. Built into libappkit.dylib in the same
# dir.
#
# Usage:  ./build.sh

set -euo pipefail

HERE="$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
COMPILER_DIR="$( cd "$HERE/../.." && pwd )"
APPKIT_DIR="$HERE/appkit"
BIN_NAME="dota2"
OUT_DIR="/tmp/appkit-out"

echo "==> building libappkit.dylib"
clang -dynamiclib -fobjc-arc \
    -install_name "@rpath/libappkit.dylib" \
    "$APPKIT_DIR/libappkit.m" \
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
