(ns cxc.build-test
  "End-to-end regression: cxc source → binary → run → verify stdout.

   Each source program lives as a standalone `.cxc` file under
   `compiler/examples/` so it can be read, hand-run, and iterated on
   outside the test harness. See `examples/README.md` for the current
   coverage table and how to add a new one."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.shell :as shell]
            [cxc.build :as build]
            [cxc.parse]
            [cxc.resolve :as resolve]))

(defn- tmp-dir []
  (let [d (java.nio.file.Files/createTempDirectory
           "cxc-test-" (into-array java.nio.file.attribute.FileAttribute []))]
    (.toFile d)))

(defn- example [name] (slurp (str "examples/" name ".cxc")))

(defn- compile-and-run
  "Compile the named example, run the binary, return {:status :stdout}.
   Fails the surrounding test with clang stderr if compilation fails."
  [example-name]
  (let [src     (example example-name)
        out-dir (tmp-dir)
        {:keys [status stderr bin-path]}
        (build/compile-source-to-file src example-name (.getPath out-dir))]
    (is (zero? status) (str "clang failed for " example-name ":\n" stderr))
    (when (zero? status)
      (build/run-binary bin-path))))

(defn- compile-and-run-concurrent
  "F32 L1b — compile the named example under concurrent mode
   (`:single-queue? false`), run the binary, return {:status :stdout}.
   Exercises the F32 concurrent runtime end-to-end (L1 emit-guarded
   queue target selection, L2 shutdown drain, L3 runtime cycle
   detector, L5 stream queue). Only safe pre-L4 for programs whose
   cross-atom reads all go through observables/methods (which hop
   via cxc_send_async / cxc_wait_for_processing) rather than direct
   `.field` reads (which would need atomic-pointer-swap)."
  [example-name]
  (let [src     (example example-name)
        out-dir (tmp-dir)
        {:keys [status stderr bin-path]}
        (build/compile-source-to-file src example-name (.getPath out-dir)
                                      {:single-queue? false})]
    (is (zero? status)
        (str "concurrent-mode clang failed for " example-name ":\n" stderr))
    (when (zero? status)
      (build/run-binary bin-path))))

(defn- compile-dir-and-run
  "Compile every `*.cxc` file under `examples/<dir-name>` as one
   program (Layer 6 Step 2/3 multi-file compile), run the binary,
   return {:status :stdout}."
  [dir-name]
  (let [src-dir (str "examples/" dir-name)
        out-dir (tmp-dir)
        {:keys [status stderr bin-path]}
        (build/compile-directory-to-file src-dir dir-name (.getPath out-dir))]
    (is (zero? status) (str "clang failed for " dir-name ":\n" stderr))
    (when (zero? status)
      (build/run-binary bin-path))))

(deftest user-declaration-collides-with-prelude-name-rejected
  (testing "Audit F56 — bounded starter: a user declaration reusing a protected prelude name (e.g. `def Duration:`) parses cleanly, but `compile-source-to-file` raises a targeted diagnostic before the prelude is even prepended. Prevents the prelude and user code from colliding on the same declaration name (spec §4.20 unshadowable prelude names)."
    (let [out-dir (tmp-dir)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"collides with a protected prelude name"
           (build/compile-source-to-file
            "def Duration:\n    n: Int\n\ndef main:\n    -> running\n        then: print(1)\n"
            "collision-check"
            (.getPath out-dir)))))))

(deftest end-to-end-hello-world
  (testing "hello.cxc — print of a string literal"
    (let [{:keys [exit stdout]} (compile-and-run "hello")]
      (is (zero? exit))
      (is (= "hello, world\n" stdout)))))

(deftest end-to-end-plugin-marker-b8
  (testing "B8 (2026-09-02) — a source file with `def plugin:` + `def main:` compiles through the full pipeline (parse → B3 manifest resolver → B4 linker gate → B5 symbol resolver → lower elides :def-plugin → emit → clang), the binary runs, and B7 emits a `<binary>.cxc-caps` sidecar with the plugin listed."
    (let [src     (example "hello-plugin")
          out-dir (tmp-dir)
          {:keys [status stderr bin-path]}
          (build/compile-source-to-file src "hello-plugin" (.getPath out-dir))]
      (is (zero? status) (str "clang failed:\n" stderr))
      (when (zero? status)
        (let [{:keys [exit stdout]} (build/run-binary bin-path)]
          (is (zero? exit))
          (is (= "hello from plugin-module\n" stdout)))
        (let [caps-file (java.io.File. (str bin-path ".cxc-caps"))]
          (is (.exists caps-file)
              "B7: .cxc-caps sidecar written next to binary")
          (let [content (slurp caps-file)]
            (is (str/includes? content "\"schema\": \"cxc-caps/v1\""))
            (is (str/includes? content "\"name\": \"hello-plugin\""))
            (is (str/includes? content "\"version\": \"0.1.0\""))
            (is (str/includes? content "\"os_primitives\": []"))))))))

(deftest gate-rejects-use-plugin-outside-plugin-module-b4-integration
  (testing "B4 (2026-09-02) — compile pipeline rejects `use plugin: Plugin` inside an atom when the module has no `def plugin:` marker (R11 plugin-only wave)."
    (let [out-dir (tmp-dir)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"plugin capability"
            (build/compile-source-to-file
              "def App:\n    use plugin: Plugin\n    x: Int! = 1\n\ndef main:\n    -> ready\n        then: print(1)\n"
              "gate-check"
              (.getPath out-dir)))))))

(deftest end-to-end-ffi-lowering-fl-5-and-fl-6
  (testing "FL-5 + FL-6 (2026-09-02) — full FFI pipeline including CString marshaling: def plugin: with `foreign \"libc\":` block declaring abs + puts, `plugin.get(\"puts\")(\"hello via FFI\")` uses FL-6's String→CString marshaling via `[expr UTF8String]`, `plugin.get(\"abs\")(-42)` exercises FL-3's plain call path. Foundation.h covers both symbols. Golden two-line stdout."
    (let [{:keys [exit stdout]} (compile-and-run "hello-plugin-ffi")]
      (is (zero? exit))
      (is (= "hello via FFI\n42\n" stdout)))))

(deftest ffi-foreign-alias-field-fl-11
  (testing "FL-11 (2026-09-02) — foreign-alias field elision. A field typed `:foreign-fn-type` with a `plugin.get(SYM)` default becomes a compile-time symbolic alias; `self.<name>(args)` in the same atom's body rewrites to a direct foreign call, and the field itself is dropped from data-section so no ivar is emitted. Plugin authors get the `_puts: (CString) -> Int = plugin.get(\"puts\")` idiom without paying for real function-pointer storage."
    (let [src (str "def plugin:\n"
                   "    name:    String!       = \"p\"\n"
                   "    version: SemVer!       = SemVer(0, 1, 0)\n"
                   "    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0), max: SemVer(2, 0, 0))\n"
                   "\n"
                   "    foreign \"libc\":\n"
                   "        symbol \"puts\" -> (CString) -> Int\n"
                   "\n"
                   "def main:\n"
                   "    use plugin: Plugin\n"
                   "    _puts: (CString) -> Int = plugin.get(\"puts\")\n"
                   "\n"
                   "    -> ready\n"
                   "        then: self._puts(\"hello via alias\")\n")
          out-dir (tmp-dir)
          {:keys [status stderr bin-path]}
          (build/compile-source-to-file src "ffi-alias" (.getPath out-dir))]
      (is (zero? status) (str "clang failed:\n" stderr))
      (when (zero? status)
        (let [{:keys [exit stdout]} (build/run-binary bin-path)]
          (is (zero? exit))
          (is (= "hello via alias\n" stdout)))))))

(deftest ffi-ptr-of-byte-signature-fl-7
  (testing "FL-7 (2026-09-02) — `Ptr of T` parametric renders as `T *` in foreign signatures. `write -> (Int32, Ptr of Byte, SizeT) -> SsizeT` type-checks, and FL-6 auto-marshaling recognizes String args against `Ptr of Byte` slots (byte-buffer target) same as against `String`. Verifies plugin-draft-realistic write() signature works end-to-end."
    (let [src (str "def plugin:\n"
                   "    name:    String!       = \"p\"\n"
                   "    version: SemVer!       = SemVer(0, 1, 0)\n"
                   "    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0), max: SemVer(2, 0, 0))\n"
                   "\n"
                   "    foreign \"libc\":\n"
                   "        symbol \"write\" -> (Int32, Ptr of Byte, SizeT) -> SsizeT\n"
                   "\n"
                   "def main:\n"
                   "    use plugin: Plugin\n"
                   "    -> ready\n"
                   "        then: print(plugin.get(\"write\")(1, \"hi\\n\", 3))\n")
          out-dir (tmp-dir)
          {:keys [status stderr bin-path]}
          (build/compile-source-to-file src "ffi-ptr" (.getPath out-dir))]
      (is (zero? status) (str "clang failed:\n" stderr))
      (when (zero? status)
        (let [{:keys [exit stdout]} (build/run-binary bin-path)]
          (is (zero? exit))
          (is (= "hi\n3\n" stdout)))))))

(deftest ffi-multi-arg-mixed-marshaling
  (testing "FL-3 + FL-6 (2026-09-02) — multi-arg foreign call with mixed types. `write(1, \"hello\\n\", 6)` — fd (Int32) passes through, buf (String) marshals via [UTF8String], nbyte (SizeT) passes through. Verifies FL-6 per-arg marshaling correctly indexes into the signature's `:type-tuple` args and only wraps positions where the signature-C-type is CString."
    (let [src (str "def plugin:\n"
                   "    name:    String!       = \"p\"\n"
                   "    version: SemVer!       = SemVer(0, 1, 0)\n"
                   "    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0), max: SemVer(2, 0, 0))\n"
                   "\n"
                   "    foreign \"libc\":\n"
                   "        symbol \"write\" -> (Int32, String, SizeT) -> SsizeT\n"
                   "\n"
                   "def main:\n"
                   "    use plugin: Plugin\n"
                   "    -> ready\n"
                   "        then: print(plugin.get(\"write\")(1, \"hello\\n\", 6))\n")
          out-dir (tmp-dir)
          {:keys [status stderr bin-path]}
          (build/compile-source-to-file src "ffi-write" (.getPath out-dir))]
      (is (zero? status) (str "clang failed:\n" stderr))
      (when (zero? status)
        (let [{:keys [exit stdout]} (build/run-binary bin-path)]
          (is (zero? exit))
          (is (= "hello\n6\n" stdout)
              "write(2) drops 'hello\\n' onto stdout and returns 6"))))))

(deftest ffi-selector-call-fl-13
  (testing "FL-13 (2026-09-02) — plugin.send(SEL, recv, args...) resolves against `foreign \"...\": selector \"cls\" \"sel:\"` entries and emits `[recv sel:args]` via lower's :objc-msg. Zero-arg selector `length` reaches emit as `[recv length]`; one-arg selector `stringByAppendingString:` reaches emit as `[recv stringByAppendingString:arg]`. Verifies end-to-end that (a) B5 accepts .send with a receiver, (b) FL-13 rewrite produces :foreign-selector-call, (c) FL-12 collapses NSString-typed slots to id so the compile succeeds, (d) lower emits :objc-msg with the correct selector-parts interleaving, and (e) the resulting binary computes `[[@\"hello, world\" length] intValue]` = 12."
    (let [src (str "def plugin:\n"
                   "    name:    String!       = \"p\"\n"
                   "    version: SemVer!       = SemVer(0, 1, 0)\n"
                   "    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0), max: SemVer(2, 0, 0))\n"
                   "\n"
                   "    foreign \"Foundation\":\n"
                   "        type \"NSString\" as NSStr\n"
                   "        selector \"NSString\" \"stringByAppendingString:\" -> (NSStr) -> NSStr\n"
                   "        selector \"NSString\" \"length\" -> () -> Int\n"
                   "\n"
                   "def main:\n"
                   "    use plugin: Plugin\n"
                   "    -> ready\n"
                   "        then:\n"
                   "            let greet = \"hello, \"\n"
                   "            let world = \"world\"\n"
                   "            let combined = plugin.send(\"stringByAppendingString:\", greet, world)\n"
                   "            let n = plugin.send(\"length\", combined)\n"
                   "            print(n)\n")
          out-dir (tmp-dir)
          {:keys [status stderr bin-path m-path]}
          (build/compile-source-to-file src "ffi-selector" (.getPath out-dir))]
      (is (zero? status) (str "clang failed:\n" stderr))
      (when (zero? status)
        (let [emitted (slurp m-path)]
          (is (re-find #"\[greet stringByAppendingString:world\]" emitted)
              "one-arg selector emits [recv sel:arg]")
          (is (re-find #"\[combined length\]" emitted)
              "zero-arg selector emits [recv sel]"))
        (let [{:keys [exit stdout]} (build/run-binary bin-path)]
          (is (zero? exit))
          (is (= "12\n" stdout)
              "[@\"hello, world\" length] returns 12"))))))

(deftest ffi-class-selector-call-fl-14
  (testing "FL-14 (2026-09-02) — plugin.class_send(SEL, args...) resolves against `foreign \"...\": class_selector \"cls\" \"sel:\"` entries and emits `[ClassName sel:args]` via lower's :objc-msg with a `:name` receiver. Verifies (a) B5 accepts .class_send with no explicit receiver, (b) FL-14 rewrite produces :foreign-class-selector-call, (c) FL-12 collapses NSString-typed slots to id, (d) lower emits `[NSString stringWithUTF8String:...]` with FL-6 String→CString marshaling on the arg, and (e) chaining with FL-13 `.send` on the returned id yields `[[NSString stringWithUTF8String:...] length]` = 9."
    (let [src (str "def plugin:\n"
                   "    name:    String!       = \"p\"\n"
                   "    version: SemVer!       = SemVer(0, 1, 0)\n"
                   "    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0), max: SemVer(2, 0, 0))\n"
                   "\n"
                   "    foreign \"Foundation\":\n"
                   "        type \"NSString\" as NSStr\n"
                   "        class_selector \"NSString\" \"stringWithUTF8String:\" -> (CString) -> NSStr\n"
                   "        selector \"NSString\" \"length\" -> () -> Int\n"
                   "\n"
                   "def main:\n"
                   "    use plugin: Plugin\n"
                   "    -> ready\n"
                   "        then:\n"
                   "            let raw = \"greetings\"\n"
                   "            let s = plugin.class_send(\"stringWithUTF8String:\", raw)\n"
                   "            let n = plugin.send(\"length\", s)\n"
                   "            print(n)\n")
          out-dir (tmp-dir)
          {:keys [status stderr bin-path m-path]}
          (build/compile-source-to-file src "ffi-cls-selector" (.getPath out-dir))]
      (is (zero? status) (str "clang failed:\n" stderr))
      (when (zero? status)
        (let [emitted (slurp m-path)]
          (is (re-find #"\[NSString stringWithUTF8String:" emitted)
              "class-send emits [ClassName sel:args] with the class name as receiver"))
        (let [{:keys [exit stdout]} (build/run-binary bin-path)]
          (is (zero? exit))
          (is (= "9\n" stdout)
              "[[NSString stringWithUTF8String:greetings] length] = 9"))))))

(deftest ffi-ptr-deref-and-index-fl-15
  (testing "FL-15 (2026-09-02) — pointer methods on `Ptr of T`. `.deref()` emits `(*p)`, `.at(i)` emits `(p)[i]`. Enables the libc errno pattern: `plugin.get(\"__error\")()` returns `Ptr of Int32`; `.deref()` yields the current errno as Int32. No bounds checks — caller's responsibility (raw C pointer)."
    (let [src (str "def plugin:\n"
                   "    name:    String!       = \"p\"\n"
                   "    version: SemVer!       = SemVer(0, 1, 0)\n"
                   "    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0), max: SemVer(2, 0, 0))\n"
                   "\n"
                   "    foreign \"libc\":\n"
                   "        symbol \"__error\" -> () -> Ptr of Int32\n"
                   "\n"
                   "def main:\n"
                   "    use plugin: Plugin\n"
                   "    -> ready\n"
                   "        then:\n"
                   "            let ep = plugin.get(\"__error\")()\n"
                   "            let via_deref = ep.deref()\n"
                   "            let via_at    = ep.at(0)\n"
                   "            print(via_deref)\n"
                   "            print(via_at)\n")
          out-dir (tmp-dir)
          {:keys [status stderr bin-path m-path]}
          (build/compile-source-to-file src "ffi-ptr-deref" (.getPath out-dir))]
      (is (zero? status) (str "clang failed:\n" stderr))
      (when (zero? status)
        (let [emitted (slurp m-path)]
          (is (re-find #"\(\*ep\)" emitted)
              ".deref() emits (*ep)")
          (is (re-find #"\(ep\)\[0\]" emitted)
              ".at(0) emits (ep)[0]"))
        (let [{:keys [exit stdout]} (build/run-binary bin-path)]
          (is (zero? exit))
          (let [lines (clojure.string/split stdout #"\n")]
            (is (= 2 (count (filter seq lines))))
            (is (= (first lines) (second lines))
                "both deref forms read the same underlying int32")))))))

(deftest ffi-ptr-deref-arity-mismatch-fl-15
  (testing "FL-15 — arity checks on pointer methods. `.deref(x)` and `.at()` both reject with actionable diagnostics."
    (let [src-base (str "def plugin:\n"
                        "    name:    String!       = \"p\"\n"
                        "    version: SemVer!       = SemVer(0, 1, 0)\n"
                        "    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0), max: SemVer(2, 0, 0))\n"
                        "\n"
                        "    foreign \"libc\":\n"
                        "        symbol \"__error\" -> () -> Ptr of Int32\n"
                        "\n"
                        "def main:\n"
                        "    use plugin: Plugin\n")
          bad-deref (str src-base
                         "    -> ready\n"
                         "        then:\n"
                         "            let ep = plugin.get(\"__error\")()\n"
                         "            print(ep.deref(0))\n")
          bad-at    (str src-base
                         "    -> ready\n"
                         "        then:\n"
                         "            let ep = plugin.get(\"__error\")()\n"
                         "            print(ep.at())\n")
          out-dir   (tmp-dir)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"FL-15.*deref\(\) takes no args"
            (build/compile-source-to-file bad-deref "fl15-bad-deref" (.getPath out-dir))))
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"FL-15.*at\(idx\) takes one arg"
            (build/compile-source-to-file bad-at "fl15-bad-at" (.getPath out-dir)))))))

(deftest ffi-stackalloc-out-param-array-fl-16
  (testing "FL-16 (2026-09-02) — `stackalloc(T, N)` intrinsic yields `Ptr of T` backed by a fixed-size stack array. Enables out-param arrays like `pipe(int[2])`: allocate `stackalloc(Int32, 2)`, hand it to a foreign `pipe -> (Ptr of Int32) -> Int32` symbol, then read back via FL-15 `.at(i)`. Emits a GNU stmt-expr `({ T __cxc_sa[N] = {0}; (T *)__cxc_sa; })`. Verifies (a) the intrinsic type-checks as `Ptr of Int32`, (b) it composes with FL-6/FL-7 marshaling (Ptr of Int32 passes verbatim, no wrap), (c) FL-15 `.at(0)` / `.at(1)` reads the written fds, and (d) `pipe(2)` returns 0 with fds ≥ 3 on macOS."
    (let [src (str "def plugin:\n"
                   "    name:    String!       = \"p\"\n"
                   "    version: SemVer!       = SemVer(0, 1, 0)\n"
                   "    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0), max: SemVer(2, 0, 0))\n"
                   "\n"
                   "    foreign \"libc\":\n"
                   "        symbol \"pipe\" -> (Ptr of Int32) -> Int32\n"
                   "\n"
                   "def main:\n"
                   "    use plugin: Plugin\n"
                   "    -> ready\n"
                   "        then:\n"
                   "            let fds = stackalloc(Int32, 2)\n"
                   "            let rc = plugin.get(\"pipe\")(fds)\n"
                   "            print(rc)\n"
                   "            print(fds.at(0))\n"
                   "            print(fds.at(1))\n")
          out-dir (tmp-dir)
          {:keys [status stderr bin-path m-path]}
          (build/compile-source-to-file src "ffi-stackalloc" (.getPath out-dir))]
      (is (zero? status) (str "clang failed:\n" stderr))
      (when (zero? status)
        (let [emitted (slurp m-path)]
          (is (re-find #"int32_t __cxc_sa\[2\] = \{0\}" emitted)
              "emit produces `T __cxc_sa[N] = {0}`")
          (is (re-find #"\(int32_t \*\)__cxc_sa" emitted)
              "emit yields `(T *)__cxc_sa` as the stmt-expr value")
          (is (re-find #"pipe\(fds\)" emitted)
              "foreign call passes the pointer verbatim"))
        (let [{:keys [exit stdout]} (build/run-binary bin-path)]
          (is (zero? exit))
          (let [lines (clojure.string/split stdout #"\n")]
            (is (>= (count lines) 3))
            (is (= "0" (first lines))
                "pipe(2) returns 0 on success")
            (is (every? (fn [ln] (>= (Integer/parseInt ln) 3))
                        (take 2 (rest lines)))
                "read/write fds are ≥ 3 (0/1/2 reserved for stdio)")))))))

(deftest ffi-stackalloc-arity-and-shape-diagnostics-fl-16
  (testing "FL-16 — arity and shape diagnostics on `stackalloc`. Wrong-arity, non-ident first arg, non-literal second arg, and non-positive length all reject with actionable messages."
    (let [preamble (str "def plugin:\n"
                        "    name:    String!       = \"p\"\n"
                        "    version: SemVer!       = SemVer(0, 1, 0)\n"
                        "    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0), max: SemVer(2, 0, 0))\n"
                        "\n"
                        "def main:\n"
                        "    use plugin: Plugin\n")
          out-dir (tmp-dir)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"stackalloc expects 2 args"
            (build/compile-source-to-file
              (str preamble
                   "    -> ready\n"
                   "        then: print(stackalloc(Int32))\n")
              "fl16-arity" (.getPath out-dir))))
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"stackalloc first arg must be a bare type identifier"
            (build/compile-source-to-file
              (str preamble
                   "    -> ready\n"
                   "        then: print(stackalloc(\"Int32\", 2))\n")
              "fl16-shape-ty" (.getPath out-dir))))
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"stackalloc second arg must be a compile-time integer literal"
            (build/compile-source-to-file
              (str preamble
                   "    n: Int! = 2\n"
                   "    -> ready\n"
                   "        then: print(stackalloc(Int32, self.n))\n")
              "fl16-shape-n" (.getPath out-dir)))))))

(deftest ffi-cstring-to-string-fl-17
  (testing "FL-17 (2026-09-02) — `CString.to_string() -> String` intrinsic. NULL-tolerant: `getenv` returning NULL on an unset name yields nil String rather than crashing `+stringWithUTF8String:` (which is documented unsafe with NULL). Emits `((c) ? [NSString stringWithUTF8String:c] : nil)` — a ternary guard around the Foundation constructor. Verifies (a) the emitted C carries the guarded ternary, (b) a present env var round-trips through the intrinsic and prints, (c) an absent env var prints NSLog's nil rendering `(null)` with a clean exit code (no crash)."
    (let [src (str "def plugin:\n"
                   "    name:    String!       = \"p\"\n"
                   "    version: SemVer!       = SemVer(0, 1, 0)\n"
                   "    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0), max: SemVer(2, 0, 0))\n"
                   "\n"
                   "    foreign \"libc\":\n"
                   "        symbol \"getenv\" -> (CString) -> CString\n"
                   "\n"
                   "def main:\n"
                   "    use plugin: Plugin\n"
                   "    use console: Console\n"
                   "    -> ready\n"
                   "        then:\n"
                   "            print(plugin.get(\"getenv\")(\"FL17_PRESENT\").to_string())\n"
                   "            print(plugin.get(\"getenv\")(\"FL17_DEFINITELY_UNSET_XYZ\").to_string())\n")
          out-dir (tmp-dir)
          {:keys [status stderr bin-path m-path]}
          (build/compile-source-to-file src "ffi-cstring-to-string" (.getPath out-dir))]
      (is (zero? status) (str "clang failed:\n" stderr))
      (when (zero? status)
        (let [emitted (slurp m-path)]
          (is (re-find #"\?\s*\[NSString stringWithUTF8String:" emitted)
              "emit includes NULL-tolerant ternary around stringWithUTF8String:")
          (is (re-find #":\s*nil\)" emitted)
              "ternary's nil-branch present"))
        (let [pb  (java.lang.ProcessBuilder. [bin-path])
              env (.environment pb)
              _   (.put env "FL17_PRESENT" "hello-from-fl17")
              _   (.remove env "FL17_DEFINITELY_UNSET_XYZ")
              _   (.redirectErrorStream pb true)
              p   (.start pb)
              _   (.waitFor p)
              exit   (.exitValue p)
              stdout (slurp (.getInputStream p))]
          (is (zero? exit))
          (let [lines (clojure.string/split stdout #"\n")]
            (is (>= (count lines) 2))
            (is (= "hello-from-fl17" (first lines))
                "present var round-trips through CString.to_string()")
            (is (= "(null)" (second lines))
                "absent var yields nil String → NSLog prints (null); no crash")))))))

(deftest ffi-per-symbol-header-augment-fl-18
  (testing "FL-18 (2026-09-02) — foreign-symbol-driven include augmentation. `_NSGetArgv` / `_NSGetArgc` live in `<crt_externs.h>` (outside cxc's default include set of `<Foundation/…>` / `<stdlib.h>` / `<string.h>` / `<libkern/OSByteOrder.h>` / `<execinfo.h>`). A foreign-block declaring either symbol causes the compiler to add `<crt_externs.h>` to the file's `#import` list. Extern generation from plugin manifests is deliberately NOT done (cxc's Int / CString types don't match libc's int / char*, so machine-generated externs conflict with stdlib prototypes); the narrower per-symbol header map is what makes `_NSGetArgv`-style symbols usable at v1.0. Verifies (a) `<crt_externs.h>` appears in the emit when `_NSGetArgv` is referenced, (b) the resulting binary runs argv iteration end-to-end (`_NSGetArgv().deref.at(i).to_string()` composes FL-15 / FL-17 to yield the ith arg as String), (c) argv[0] is the program path (crt1 invariant)."
    (let [src (str "def plugin:\n"
                   "    name:    String!       = \"p\"\n"
                   "    version: SemVer!       = SemVer(0, 1, 0)\n"
                   "    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0), max: SemVer(2, 0, 0))\n"
                   "\n"
                   "    foreign \"libc\":\n"
                   "        symbol \"_NSGetArgc\" -> () -> Ptr of Int32\n"
                   "        symbol \"_NSGetArgv\" -> () -> Ptr of Ptr of CString\n"
                   "\n"
                   "def main:\n"
                   "    use plugin: Plugin\n"
                   "    use console: Console\n"
                   "    -> ready\n"
                   "        then:\n"
                   "            console.write(plugin.get(\"_NSGetArgv\")().deref().at(0).to_string())\n")
          out-dir (tmp-dir)
          {:keys [status stderr bin-path m-path]}
          (build/compile-source-to-file src "fl18-argv" (.getPath out-dir))]
      (is (zero? status) (str "clang failed:\n" stderr))
      (when (zero? status)
        (let [emitted (slurp m-path)]
          (is (re-find #"#import <crt_externs\.h>" emitted)
              "<crt_externs.h> augmented into the include set"))
        (let [{:keys [exit stdout]} (build/run-binary bin-path)]
          (is (zero? exit))
          (is (str/includes? stdout bin-path)
              "argv[0] resolves to the program path (crt1 invariant)"))))))

(deftest ffi-cstring-to-string-arity-mismatch-fl-17
  (testing "FL-17 — arity check. `.to_string(x)` rejects with actionable diagnostic."
    (let [src (str "def plugin:\n"
                   "    name:    String!       = \"p\"\n"
                   "    version: SemVer!       = SemVer(0, 1, 0)\n"
                   "    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0), max: SemVer(2, 0, 0))\n"
                   "\n"
                   "    foreign \"libc\":\n"
                   "        symbol \"getenv\" -> (CString) -> CString\n"
                   "\n"
                   "def main:\n"
                   "    use plugin: Plugin\n"
                   "    -> ready\n"
                   "        then:\n"
                   "            print(plugin.get(\"getenv\")(\"HOME\").to_string(1))\n")
          out-dir (tmp-dir)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"FL-17.*to_string\(\).* takes no args"
            (build/compile-source-to-file src "fl17-bad-arity" (.getPath out-dir)))))))

(deftest ffi-bare-foreign-ref-not-called-rejected
  (testing "FL-3 diagnostic — storing plugin.get(SYM) as a function pointer (not calling it inline) rejected with a fail-nyi pointing at the MVP-supported call form."
    (let [src (str "def plugin:\n"
                   "    name:    String!       = \"p\"\n"
                   "    version: SemVer!       = SemVer(0, 1, 0)\n"
                   "    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0), max: SemVer(2, 0, 0))\n"
                   "\n"
                   "    foreign \"libc\":\n"
                   "        symbol \"abs\" -> (Int) -> Int\n"
                   "\n"
                   "def main:\n"
                   "    use plugin: Plugin\n"
                   "    handle: Int! = plugin.get(\"abs\")\n"
                   "    -> ready\n"
                   "        then: print(handle)\n")
          out-dir (tmp-dir)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"FL-3.*plugin\.get.*not immediately called"
            (build/compile-source-to-file src "ffi-bare" (.getPath out-dir)))))))

(deftest resolver-rejects-unresolved-plugin-get-b5-integration
  (testing "B5 (2026-09-02) — compile pipeline rejects `plugin.get(\"nope\")` when the symbol is not declared in any foreign block."
    (let [src (str "def plugin:\n"
                   "    name:    String!       = \"p\"\n"
                   "    version: SemVer!       = SemVer(0, 1, 0)\n"
                   "    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0), max: SemVer(2, 0, 0))\n"
                   "\n"
                   "    foreign \"libc\":\n"
                   "        symbol \"write\" -> Int32\n"
                   "\n"
                   "def MyShim:\n"
                   "    use plugin: Plugin\n"
                   "    x: Int! = plugin.get(\"nope\")\n"
                   "\n"
                   "def main:\n"
                   "    -> ready\n"
                   "        then: print(1)\n")
          out-dir (tmp-dir)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"plugin\.get\(\"nope\"\)"
            (build/compile-source-to-file src "unresolved-check" (.getPath out-dir)))))))

(deftest end-to-end-f52-l4-module-let-basic
  (testing "F52 L4 (2026-08-27) — top-level `let SEED = 41` binds an immutable compile-time constant. Pre-lower `substitute-module-let-refs` postwalks the AST replacing every `SEED` `:ident` node with the `:lit-int` value. `Counter(seed: SEED).bump` constructs with seed=41 and yields 42; program prints `42\\n`."
    (let [{:keys [exit stdout]} (compile-and-run "f52-l4-module-let-basic")]
      (is (zero? exit))
      (is (= "42\n" stdout)))))

(deftest end-to-end-direct-field-syntax
  (testing "L6 — custom-section.cxc migrated to direct field syntax; the field is folded into :data-section and remains observable end-to-end"
    (let [{:keys [exit stdout]} (compile-and-run "custom-section")]
      (is (zero? exit))
      (is (= "42\n" stdout)))))

(deftest end-to-end-f8-computed
  (testing "L8 — direct observable syntax at def-body position. `V { n: Int = 21 }` declares `doubled :- n * 2` directly; `V().doubled` dispatches through the observable path and prints 42."
    (let [{:keys [exit stdout]} (compile-and-run "f8-computed")]
      (is (zero? exit))
      (is (= "42\n" stdout)))))

(deftest end-to-end-f39-optional-bytes
  (testing "F39 L9 — mixed bare `Int32` (Optional-boxed) + `Int32!` (non-nil) fields. Bare field emits 1-byte presence + 4-byte payload (when present); `Int32!` emits 4 bytes flat. `Opt(x:1, y:2)` yields 1+4+4 = 9 bytes."
    (let [{:keys [exit stdout]} (compile-and-run "f39-optional-bytes")]
      (is (zero? exit))
      (is (= "9\n" stdout)))))

(deftest end-to-end-f37-nested-record-bytes
  (testing "F37 L4 — nested record `Outer{ i: Inner!, k: Int32! }` where Inner is `{ n: Int32! }`. Encoding recurses into Inner's synthesized fn via `appendData:`. Inner emits 4 bytes; Outer contributes those 4 + k's 4 = 8 total (no length prefix per §3.10.10 record row)."
    (let [{:keys [exit stdout]} (compile-and-run "f37-nested-record-bytes")]
      (is (zero? exit))
      (is (= "8\n" stdout)))))

(deftest end-to-end-f37-string-bytes
  (testing "F37 L3 — record with mixed Int32! + String! fields. `n` encodes as 4-byte big-endian Int32; `text` encodes as 4-byte UInt32 length prefix + UTF-8 bytes. `\"hi\"` is 2 UTF-8 bytes; total = 4 + 4 + 2 = 10."
    (let [{:keys [exit stdout]} (compile-and-run "f37-string-bytes")]
      (is (zero? exit))
      (is (= "10\n" stdout)))))

(deftest end-to-end-f37-primitive-bytes
  (testing "F37 L2 — end-to-end canonical bytes for a record with primitive Int32! fields. `Vec(x:1, y:2).as()` synthesizes `Vec_cxc_as_bytes` which returns an NSMutableData * of exactly 8 bytes (2 × 4-byte Int32, big-endian). `b.length` prints 8."
    (let [{:keys [exit stdout]} (compile-and-run "f37-primitive-bytes")]
      (is (zero? exit))
      (is (= "8\n" stdout)))))

(deftest end-to-end-f37-l6a-bytes-at
  (testing "F37 L6a — `Bytes.at(offset)` reads a single byte and widens to Int. `Vec(x:1, y:2).as()` produces big-endian bytes `00 00 00 01 00 00 00 02`; `b.at(3)` reads the low byte of x = 1."
    (let [{:keys [exit stdout]} (compile-and-run "f37-l6a-bytes-at")]
      (is (zero? exit))
      (is (= "1\n" stdout)))))

(deftest end-to-end-f37-l6-from-bytes
  (testing "F37 L6 — `<Record>.from(source: b)` primitive-only decoder synthesis end-to-end. `Vec(x:1,y:2).as()` produces 8 big-endian bytes; `Vec.from(source: b)` dispatches to the synthesized `Vec_cxc_from_bytes` returning `_Cxc_DecodeOutcome_Vec`. Consumer surface (cxc-visible DecodeOutcome enum) waits on F16 integration, so the call's return value is discarded — the test proves compile+link cleanliness of the synth pipeline (struct + factories + decoder) plus intercept dispatch. `b.length` prints 8 as it does in the L2 fixture."
    (let [{:keys [exit stdout]} (compile-and-run "f37-l6-from-bytes")]
      (is (zero? exit))
      (is (= "8\n" stdout)))))

(deftest end-to-end-f37-l6-string-from-bytes
  (testing "F37 L6 String field extension — round-trip `.as()` then `.from(source:)` on a String!-only record. `Msg(text: \"hello\").as()` produces UInt32 big-endian length prefix (4 bytes) + UTF-8 payload (5 bytes for \"hello\") = 9 bytes; `Msg.from(source: b)` dispatches to the synthesized `Msg_cxc_from_bytes` which reads the prefix + payload + validates UTF-8. DecodeOutcome result discarded (waits on F16 consumer surface); `b.length` prints 9 confirming compile+link cleanliness of the String decode path."
    (let [{:keys [exit stdout]} (compile-and-run "f37-l6-string-from-bytes")]
      (is (zero? exit))
      (is (= "9\n" stdout)))))

(deftest end-to-end-f37-l6-nested-from-bytes
  (testing "F37 L6 nested-record extension — round-trip `.as()` then `.from(source:)` on an Outer record with a fixed-size Inner nested field. `Outer(i: Inner(n: 7), k: 42).as()` produces 4 bytes (Inner) + 4 bytes (k) = 8 bytes; `Outer.from(source: b)` dispatches to `Outer_cxc_from_bytes` which slices Inner's fixed 4 bytes, calls `Inner_cxc_from_bytes` on the slice, unpacks the outcome, then reads k. DecodeOutcome discarded (waits on F16 consumer surface); `b.length` prints 8 confirming compile+link cleanliness of the nested-record decode path."
    (let [{:keys [exit stdout]} (compile-and-run "f37-l6-nested-from-bytes")]
      (is (zero? exit))
      (is (= "8\n" stdout)))))

(deftest end-to-end-f37-l6-optional-from-bytes
  (testing "F37 L6 Optional-primitive extension — round-trip `.as()` then `.from(source:)` on a record with a bare-T (Optional-boxed) primitive field. `Opt(x: 42).as()` produces 1 presence byte + 4 payload bytes = 5 bytes; `Opt.from(source: b)` reads presence + payload into `_Cxc_Optional_Int32 __x_val`, then constructs Opt via designated initializer. DecodeOutcome discarded; `b.length` prints 5."
    (let [{:keys [exit stdout]} (compile-and-run "f37-l6-optional-from-bytes")]
      (is (zero? exit))
      (is (= "5\n" stdout)))))

(deftest end-to-end-f37-l6-enum-from-bytes
  (testing "F37 L6 value-only enum extension + F37 encoder-symmetry — round-trip `.as()` then `.from(source:)` on a record with a value-only enum field. `R(sig: Signal.green).as()` produces 1 discriminator byte (Signal has ≤256 cases so UInt8). `R.from(source: b)` reads the discriminator, range-checks against case count 3, casts to Signal. DecodeOutcome discarded; `b.length` prints 1."
    (let [{:keys [exit stdout]} (compile-and-run "f37-l6-enum-from-bytes")]
      (is (zero? exit))
      (is (= "1\n" stdout)))))

(deftest end-to-end-f37-l6-optional-nested-from-bytes
  (testing "F37 L6 Optional-nested-record extension — round-trip on a record with a bare-T value-typed field where T is a fixed-size nested record. `R(i: Inner(n: 7)).as()` produces 1 presence byte + 4 payload bytes = 5 bytes; `R.from(source: b)` reads the presence, slices Inner's fixed 4 bytes on present, dispatches to Inner_cxc_from_bytes, unpacks into `_Cxc_Optional_Inner.value`. DecodeOutcome discarded; `b.length` prints 5."
    (let [{:keys [exit stdout]} (compile-and-run "f37-l6-optional-nested-from-bytes")]
      (is (zero? exit))
      (is (= "5\n" stdout)))))

(deftest end-to-end-f37-l6-tagged-enum-from-bytes
  (testing "F37 L6 tagged-enum extension — round-trip on a record with a tagged enum field. `R(msg: Msg.pong(seq: 42)).as()` produces 1 discriminator byte + 4 payload bytes = 5 bytes; `R.from(source: b)` reads the discriminator, writes the tag, dispatches to the pong arm which decodes seq into `.data.pong.seq`. DecodeOutcome discarded; `b.length` prints 5. Also exercises the `memcmp`-based tagged-enum equals/hash fix landing alongside."
    (let [{:keys [exit stdout]} (compile-and-run "f37-l6-tagged-enum-from-bytes")]
      (is (zero? exit))
      (is (= "5\n" stdout)))))

(deftest end-to-end-f37-l6-optional-enum-from-bytes
  (testing "F37 L6 Optional-value-only-enum extension (2026-08-27) — round-trip on a record with a bare-T value-typed field where T is a value-only enum. `R(sig: Signal.green).as()` produces 1 presence byte + 1 discriminator byte = 2 bytes; `R.from(source: b)` reads presence, on present reads + range-checks the discriminator, casts to Signal into `_Cxc_Optional_Signal.value`. DecodeOutcome discarded; `b.length` prints 2."
    (let [{:keys [exit stdout]} (compile-and-run "f37-l6-optional-enum-from-bytes")]
      (is (zero? exit))
      (is (= "2\n" stdout)))))

(deftest end-to-end-f37-l6-optional-tagged-enum-from-bytes
  (testing "F37 L6 Optional-tagged-enum extension (2026-08-27) — round-trip on a record with a bare-T value-typed field where T is a tagged enum with primitive payload cases. `R(msg: Msg.pong(seq: 42)).as()` produces 1 presence + 1 discriminator + 4 payload = 6 bytes; `R.from(source: b)` reads presence, on present reads + range-checks the discriminator, writes tag + switches to decode payload into `_Cxc_Optional_Msg.value.data.pong.seq`. DecodeOutcome discarded; `b.length` prints 6."
    (let [{:keys [exit stdout]} (compile-and-run "f37-l6-optional-tagged-enum-from-bytes")]
      (is (zero? exit))
      (is (= "6\n" stdout)))))

(deftest end-to-end-f37-l6-nullable-string-from-bytes
  (testing "F37 L6 nullable-String extension (2026-08-27) — round-trip on a record with a bare `String` field (nullable; storage is `NSString *` directly, not Optional-boxed). `R(msg: \"hi\").as()` produces 1 presence + 4 length-prefix + 2 UTF-8 payload = 7 bytes; `R.from(source: b)` reads presence, on present reads + validates length prefix + payload bytes + UTF-8 into `NSString *__msg_val`. DecodeOutcome discarded; `b.length` prints 7."
    (let [{:keys [exit stdout]} (compile-and-run "f37-l6-nullable-string-from-bytes")]
      (is (zero? exit))
      (is (= "7\n" stdout)))))

(deftest end-to-end-f37-l6-varlen-nested-from-bytes
  (testing "F37 L6 variable-length nested-record (2026-08-27 ABI change) — round-trip on Outer{i: Inner!, k: Int32!} where Inner{text: String!, n: Int32!} is variable-length. Encoding: Inner text (4 length prefix + 2 UTF-8) + Inner n (4) + Outer k (4) = 14 bytes. Decoder calls `Inner_cxc_from_bytes(src, __off, &__consumed)` with the new ABI; Inner reports 10 consumed; Outer advances __off by 10 and reads k. DecodeOutcome discarded; `b.length` prints 14."
    (let [{:keys [exit stdout]} (compile-and-run "f37-l6-varlen-nested-from-bytes")]
      (is (zero? exit))
      (is (= "14\n" stdout)))))

(deftest end-to-end-f37-l6-optional-varlen-nested-from-bytes
  (testing "F37 L6 Optional-nested-record + variable-length ABI (both 2026-08-27) — round-trip on R{i: Inner} where Inner{text: String!, n: Int32!} is variable-length. Encoding: 1 presence + 4 length prefix + 2 UTF-8 + 4 n = 11 bytes. Decoder reads presence, on present dispatches to Inner with the ABI, Inner reports 10 consumed. DecodeOutcome discarded; `b.length` prints 11."
    (let [{:keys [exit stdout]} (compile-and-run "f37-l6-optional-varlen-nested-from-bytes")]
      (is (zero? exit))
      (is (= "11\n" stdout)))))


(deftest end-to-end-k5-if-expression
  (testing "K5 L1 — `if` at expression position (spec §3.8.1). Uses both simple `if...else` and `if...else if...else` chain. Result flows through `let` bindings. Lowered as C ternary; prints the selected branch value."
    (let [{:keys [exit stdout]} (compile-and-run "k5-if-expression")]
      (is (zero? exit))
      (is (= "2\n" stdout)))))

(deftest end-to-end-f18-do-expression
  (testing "F18 L2 — `do:` at expression position (spec §3.8.2). Indented `do:` block with two `let` bindings; the final expression `a + b` is the block's value. Lowered as GNU statement-expression `({ ... })`; the enclosing `let r = ...` binds `r` to that value; `print(r)` yields 7."
    (let [{:keys [exit stdout]} (compile-and-run "f18-do-expression")]
      (is (zero? exit))
      (is (= "7\n" stdout)))))

(deftest end-to-end-f13-weak-nils-out
  (testing "F13 L3 — `weak <AtomType>` field on `Parent` nils out when the target Child's last strong ref goes away. Parent stores `ref: weak Child`; the Child() passed to Parent's ctor is a temporary with no other __strong owner, so ARC releases at end-of-full-expression and zeroes the weak ivar. Reading `p.ref.value` observes the nilled weak → auto-lift produces Optional-nil for `Int` (F39 L5b-ii-c) → print renders `nil\\n`. Failure mode this catches: ARC unexpectedly keeps Child alive (autorelease-pool or accidental __strong storage) → print would render `42`."
    (let [{:keys [exit stdout]} (compile-and-run "f13-weak-nils-out")]
      (is (zero? exit))
      (is (= "nil\n" stdout)))))

(deftest end-to-end-different-message
  (testing "greet.cxc — different string round-trips through the pipeline"
    (let [{:keys [exit stdout]} (compile-and-run "greet")]
      (is (zero? exit))
      (is (= "cxc is running\n" stdout)))))

(deftest end-to-end-arithmetic-in-print
  (testing "arithmetic.cxc — int binops through print; precedence preserved"
    (let [{:keys [exit stdout]} (compile-and-run "arithmetic")]
      (is (zero? exit))
      (is (= "11\n" stdout)))))

(deftest end-to-end-record-construction-and-field-access
  (testing "record-value.cxc — record → C struct; V(x:,y:).x + 100 = 103"
    (let [{:keys [exit stdout]} (compile-and-run "record-value")]
      (is (zero? exit))
      (is (= "103\n" stdout)))))

(deftest end-to-end-nested-record-field-chain
  (testing "nested-record.cxc — record-of-record; .a.b field chain works"
    (let [{:keys [exit stdout]} (compile-and-run "nested-record")]
      (is (zero? exit))
      (is (= "1.500000\n" stdout)))))

(deftest end-to-end-record-defaults
  (testing "record-defaults.cxc — omitted fields fall back to declared defaults"
    (let [{:keys [exit stdout]} (compile-and-run "record-defaults")]
      (is (zero? exit))
      (is (= "3\n" stdout)))))

(deftest end-to-end-def-atom-alloc-init
  (testing "atom-simple.cxc — def-atom → ObjC class; Counter() → alloc/init; .n → property"
    (let [{:keys [exit stdout]} (compile-and-run "atom-simple")]
      (is (zero? exit))
      (is (= "42\n" stdout)))))

(deftest end-to-end-record-methods
  (testing "record-methods.cxc — v.plus(other) → V_plus(v, other); result field access chains"
    (let [{:keys [exit stdout]} (compile-and-run "record-methods")]
      (is (zero? exit))
      (is (= "4.000000\n" stdout)))))

(deftest end-to-end-atom-ctor-params
  (testing "atom-ctor-params.cxc — Counter(initial: 42) → initWith selector; default fills for Counter()"
    (let [{:keys [exit stdout]} (compile-and-run "atom-ctor-params")]
      (is (zero? exit))
      (is (= "42\n" stdout)))))

(deftest end-to-end-nullable-nil-punning
  (testing (str "nullable-nil-punning.cxc — F39 L5b-ii-c update: `.n` on the "
                "nil `other` slot now propagates nil through the Optional "
                "boxing (was scalar zero-pun pre-flip 2026-08-22). print "
                "renders Optional-nil as `nil\\n`.")
    (let [{:keys [exit stdout]} (compile-and-run "nullable-nil-punning")]
      (is (zero? exit))
      (is (= "nil\n" stdout)))))

(deftest end-to-end-enum-cases
  (testing "enum-cases.cxc — enum decl + Key.arrow_down accesses index-1 case"
    (let [{:keys [exit stdout]} (compile-and-run "enum-cases")]
      (is (zero? exit))
      (is (= "1\n" stdout)))))

(deftest end-to-end-f16-payload-enum
  (testing "F16 L1 — payload-carrying enum case. `Msg.pong(reply_to: 7, seq: 42)` lowers to a tagged-union compound literal `((Msg){.tag = Msg_Tag_pong, .data.pong = {.reply_to = 7, .seq = 42}})`. Dispatch on `m` binds `r` and `s` via `<field>: let name` in the matched arm; `r + s = 49`."
    (let [{:keys [exit stdout]} (compile-and-run "f16-payload-enum")]
      (is (zero? exit))
      (is (= "49\n" stdout)))))

(deftest end-to-end-f37-enum-bytes
  (testing "F37 L5 — enum canonical bytes. Discriminator is 1 byte (≤256 cases per §3.10.10). `Msg.pong(seq: 42).as()` yields 1 tag byte + 4 payload bytes = 5."
    (let [{:keys [exit stdout]} (compile-and-run "f37-enum-bytes")]
      (is (zero? exit))
      (is (= "5\n" stdout)))))

(deftest end-to-end-f16-raw-value-enum
  (testing "F16 L2 — raw-value enum. `case not_found = 404` lowers to `HTTP_not_found = 404` in a typed C enum `typedef enum : uint16_t { … } HTTP;`. Print prints the integer value directly."
    (let [{:keys [exit stdout]} (compile-and-run "f16-raw-value-enum")]
      (is (zero? exit))
      (is (= "404\n" stdout)))))

(deftest end-to-end-dispatch-basic
  (testing "dispatch-basic.cxc — F69 L4b `<:` receive-glyph dispatch on a tagged-variant scrutinee. Binds `s = Signal.yellow`, dispatches with case + wildcard arms; the `yellow` branch fires and prints 200."
    (let [{:keys [exit stdout]} (compile-and-run "dispatch-basic")]
      (is (zero? exit))
      (is (= "200\n" stdout)))))

(deftest end-to-end-dispatch-multi-pattern
  (testing "dispatch-multi-pattern.cxc — F69 L4c2 comma-separated multi-pattern arms per spec §3.7. `c = Warmth.orange` matches the `red, orange, yellow` arm; prints 1."
    (let [{:keys [exit stdout]} (compile-and-run "dispatch-multi-pattern")]
      (is (zero? exit))
      (is (= "1\n" stdout)))))

(deftest end-to-end-dispatch-guard
  (testing "dispatch-guard.cxc — F69 L4c3 guards (`<: pattern when: <cond>:`). Reuses the class-body-transition `when:` guard keyword. `l = Level.medium`, `strict = true`; the `medium when: strict` arm fires and prints 5."
    (let [{:keys [exit stdout]} (compile-and-run "dispatch-guard")]
      (is (zero? exit))
      (is (= "5\n" stdout)))))

(deftest end-to-end-dispatch-literal
  (testing "dispatch-literal.cxc — F69 L4c4 integer-literal patterns. Scrutinee `n = 42` matches the `<: 42:` arm; prints 2."
    (let [{:keys [exit stdout]} (compile-and-run "dispatch-literal")]
      (is (zero? exit))
      (is (= "2\n" stdout)))))

(deftest end-to-end-dispatch-leading-dot
  (testing "dispatch-leading-dot.cxc — F69 L4c5 leading-dot shorthand per spec §3.7.2. `<: .ok:` resolves to `<: Outcome.ok:` via the scrutinee's inferred type. Prints 1."
    (let [{:keys [exit stdout]} (compile-and-run "dispatch-leading-dot")]
      (is (zero? exit))
      (is (= "1\n" stdout)))))

(deftest end-to-end-bool-and-is
  (testing "bool-and-is.cxc — and/or/is lower to C boolean ops; identity check on nil"
    (let [{:keys [exit stdout]} (compile-and-run "bool-and-is")]
      (is (zero? exit))
      (is (= "1\n" stdout)))))

(deftest end-to-end-atom-methods
  (testing "atom-methods.cxc — def-atom methods → ObjC instance methods; both zero-arg and multi-arg"
    (let [{:keys [exit stdout]} (compile-and-run "atom-methods")]
      (is (zero? exit))
      (is (= "26\n" stdout)))))

(deftest end-to-end-atom-handler
  (testing "atom-handler.cxc — F14: `-> shout(msg: Int):` on an atom lowers as a void ObjC method sharing the atom's dispatch table (spec §1265, §2160). Externally invoked via `logger.shout(42)` from main."
    (let [{:keys [exit stdout]} (compile-and-run "atom-handler")]
      (is (zero? exit))
      (is (= "42\n" stdout)))))

(deftest end-to-end-atom-on-enter
  (testing "atom-on-enter.cxc — `-> ready then: n => 100` fires during init, overrides data default"
    (let [{:keys [exit stdout]} (compile-and-run "atom-on-enter")]
      (is (zero? exit))
      (is (= "100\n" stdout)))))

(deftest end-to-end-atom-on-enter-self-field
  (testing "atom-on-enter-self-field.cxc — then: hp => max_hp reads sibling data field (unqualified → self ivar)"
    (let [{:keys [exit stdout]} (compile-and-run "atom-on-enter-self-field")]
      (is (zero? exit))
      (is (= "30\n" stdout)))))

(deftest end-to-end-observable-simple
  (testing "observable-simple.cxc — `doubled :- raw + raw` synthesizes a zero-arg getter; .doubled reads it"
    (let [{:keys [exit stdout]} (compile-and-run "observable-simple")]
      (is (zero? exit))
      (is (= "42\n" stdout)))))

(deftest end-to-end-observable-chain
  (testing "observable-chain.cxc — later observable references earlier one (declaration order enables chaining)"
    (let [{:keys [exit stdout]} (compile-and-run "observable-chain")]
      (is (zero? exit))
      (is (= "7\n" stdout)))))

(deftest end-to-end-observable-parametric
  (testing "F67 L2 — observable-parametric.cxc: `scaled_area(sx: Int, sy: Int) :- (w * sx) * (h * sy)` — parametric observable emits as ObjC method with declared params; caller invokes `r.scaled_area(2, 5)` and gets (3*2)*(4*5) = 120."
    (let [{:keys [exit stdout]} (compile-and-run "observable-parametric")]
      (is (zero? exit))
      (is (= "120\n" stdout)))))

(deftest end-to-end-invariant-pass
  (testing "invariant-pass.cxc — init satisfies invariants; program runs to completion"
    (let [{:keys [exit stdout]} (compile-and-run "invariant-pass")]
      (is (zero? exit))
      (is (= "50\n" stdout)))))

(deftest end-to-end-invariant-fail
  (testing "invariant-fail.cxc — data init violates `value <= max`; init aborts with a diagnostic on stderr"
    (let [{:keys [exit stderr]} (compile-and-run "invariant-fail")]
      (is (not (zero? exit))
          "expected non-zero exit from abort()")
      (is (re-find #"Gauge invariant #1 failed" (or stderr ""))
          "expected diagnostic naming the failing invariant index"))))

(deftest end-to-end-panic-halts
  (testing "panic-halts.cxc — F31/F35 L2. `panic(\"boom\")` writes `panic: <msg>` + backtrace to stderr, then traps. Uncatchable — bypasses `@try/@catch` wrappers (which stay for BEAM-style transition isolation). Exit is SIGTRAP (128 + 5 = 133 on darwin)."
    (let [{:keys [exit stderr]} (compile-and-run "panic-halts")]
      (is (not (zero? exit))
          "expected non-zero exit from __builtin_trap()")
      (is (re-find #"panic: boom" (or stderr ""))
          "expected `panic:` prefix + user-supplied message on stderr"))))

(deftest end-to-end-nil-dot-method
  (testing "nil-dot-method.cxc — F39 Landing 4 + L5b-ii-c. `.method()` on a nil atom returns the zero-value for its Optional return type, whose `present` bit is false. Locks in the nil-flow-through-`.` promise (spec §3.6.1 line 3690, §5.7 line 7448)."
    (let [{:keys [exit stdout]} (compile-and-run "nil-dot-method")]
      (is (zero? exit))
      (is (= "nil\n" stdout)
          "nil.get_n() returns Optional-nil via ObjC zero-value messaging"))))

(deftest end-to-end-use-cap
  (testing "use-cap.cxc — direct `use src: OffsetSource` sugars to required ctor-param + data-field; capability accessible via `src.offset` in observables. (Renamed away from the prelude `Clock` collision that F56 diagnostic now rejects — the example's intent is direct `use` sugar, not shadowing.)"
    (let [{:keys [exit stdout]} (compile-and-run "use-cap")]
      (is (zero? exit))
      (is (= "1042\n" stdout)))))

(deftest end-to-end-chained-comparison
  (testing "chained-comparison.cxc — `lo <= val <= hi` desugars to `(lo<=val) and (val<=hi)` at parse-time; passing case runs through"
    (let [{:keys [exit stdout]} (compile-and-run "chained-comparison")]
      (is (zero? exit))
      (is (= "5\n" stdout)))))

(deftest end-to-end-nested-slot
  (testing "nested-slot.cxc — `paddle: Paddle = Paddle()` self-constructs a sub-atom in parent init; observable reads through the slot"
    (let [{:keys [exit stdout]} (compile-and-run "nested-slot")]
      (is (zero? exit))
      (is (= "7\n" stdout)))))

(deftest end-to-end-nested-slot-ctor-args
  (testing "nested-slot-ctor-args.cxc — `cell: Cell = Cell(v: 99)` passes named args to the sub-atom's initWith selector"
    (let [{:keys [exit stdout]} (compile-and-run "nested-slot-ctor-args")]
      (is (zero? exit))
      (is (= "99\n" stdout)))))

(deftest end-to-end-self-sync-send-in-on-enter
  (testing "self-sync-send-in-on-enter.cxc — `self |> bump` in on-enter fires the transition twice; n = 2"
    (let [{:keys [exit stdout]} (compile-and-run "self-sync-send-in-on-enter")]
      (is (zero? exit))
      (is (= "2\n" stdout)))))

(deftest end-to-end-when-guard-saturation
  (testing "when-guard-saturation.cxc — 4 bumps, arm-head `|: n < max` blocks the 4th; n saturates at max=3"
    (let [{:keys [exit stdout]} (compile-and-run "when-guard-saturation")]
      (is (zero? exit))
      (is (= "3\n" stdout)))))

(deftest end-to-end-multi-phase-toggle
  (testing "multi-phase-toggle.cxc — same selector `flip` on two phases, first-match dispatch groups into one method; 3 flips = boot→on→off→on, state=1"
    (let [{:keys [exit stdout]} (compile-and-run "multi-phase-toggle")]
      (is (zero? exit))
      (is (= "1\n" stdout)))))

(deftest end-to-end-non-initial-wildcard
  (testing "non-initial-wildcard.cxc — `-> armed then: n => 100` fires when the `arm` transition enters `armed` (not at init); final n = 101 after `fire` adds 1"
    (let [{:keys [exit stdout]} (compile-and-run "non-initial-wildcard")]
      (is (zero? exit))
      (is (= "101\n" stdout)))))

(deftest end-to-end-message-payload
  (testing "message-payload.cxc — `add(by: Int)` payload binds `by` as local in the arm-head `|:` proposition and `then:`; three sends with 5/10/3 sum to 18"
    (let [{:keys [exit stdout]} (compile-and-run "message-payload")]
      (is (zero? exit))
      (is (= "18\n" stdout)))))

(deftest end-to-end-cross-atom-send
  (testing "cross-atom-send.cxc — Source constructs then sends `absorb(amount:)` to a Sink capability; sink.total = 12 after two sends"
    (let [{:keys [exit stdout]} (compile-and-run "cross-atom-send")]
      (is (zero? exit))
      (is (= "12\n" stdout)))))

(deftest end-to-end-invariant-post-transition-fail
  (testing "invariant-post-transition-fail.cxc — third bump makes value(=6) > max(=5); post-transition invariant check aborts mid-init"
    (let [{:keys [exit stderr]} (compile-and-run "invariant-post-transition-fail")]
      (is (not (zero? exit)) "expected non-zero exit from abort()")
      (is (re-find #"Gauge invariant #0 failed" (or stderr ""))
          "expected diagnostic naming the violated invariant"))))

(deftest end-to-end-main-shutdown-on-end
  (testing "main-shutdown-on-end.cxc — `self :> stop` triggers `running <: stop -> end`; `-> end do:` prints 7; CxcMain's transitionToEnd invokes cxc_shutdown() to exit the dispatch_main loop cleanly (Steps 16/17)"
    (let [{:keys [exit stdout]} (compile-and-run "main-shutdown-on-end")]
      (is (zero? exit))
      (is (= "7\n" stdout)))))

(deftest end-to-end-stream-simple
  (testing "stream-simple.cxc — Emitter emits `:> beep` in `-> end do:` after self-firing; main subscribes to `source.beep` via CxcMain's cxc_runtimeBinding; Emitter fanout dispatches to CxcMain via cxc_send_async; CxcMain's transition prints 9 then shuts down (Step 13)"
    (let [{:keys [exit stdout]} (compile-and-run "stream-simple")]
      (is (zero? exit))
      (is (= "9\n" stdout)))))

(deftest end-to-end-stream-typed-payload
  (testing "stream-typed-payload.cxc — `:> value(n: 42)` on Emitter carries a typed payload; main subscribes with `source.value{n: Int}`, binds `n` locally, stashes it in `last_n` via `then:`, self-sends `done` to advance to `end`, and `-> end then: print(last_n)` prints 42. Exercises cross-emit-site type inference on Emitter, typed method signature on `-cxc_emit_value_Withn:`, block-capture of typed args on fan-out, and cross-atom type check at the subscribe site."
    (let [{:keys [exit stdout]} (compile-and-run "stream-typed-payload")]
      (is (zero? exit))
      (is (= "42\n" stdout)))))

(deftest end-to-end-stream-nullable-slot
  (testing "stream-nullable-slot.cxc — nullable slot `source: Emitter? = nil` filled dynamically via `then: source => Emitter()`; slot setter unsubs from old (nil→no-op), assigns, subs to new; subscription actually registers with Emitter at that point; Emitter fires beep → main receives → prints 9 → exits (Layer 4b nullable-slot dynamic subscription)"
    (let [{:keys [exit stdout]} (compile-and-run "stream-nullable-slot")]
      (is (zero? exit))
      (is (= "9\n" stdout)))))

(deftest end-to-end-prelude-duration
  (testing "prelude-duration.cxc — the prelude-shipped Duration + Instant records are visible without an import; construct + method-call chain (Instant + Duration = Instant with summed millis) prints 1500. Layer 5 Step 1 dropped the Cxc user-visible prefix; internal names are `_Cxc_Duration`/`_Cxc_Instant` via the rename table."
    (let [{:keys [exit stdout]} (compile-and-run "prelude-duration")]
      (is (zero? exit))
      (is (= "1500\n" stdout)))))

(deftest end-to-end-sync-receive
  (testing "sync-receive.cxc — `running <| bump(by: Int)` sync-receive under Layer 4 single-thread compiles identically to `<:` (serial-queue semantics already provide `<|`'s no-interleave guarantee); two `|>` sync-sends followed by `|> stop` cleanly walks main to end printing 15"
    (let [{:keys [exit stdout]} (compile-and-run "sync-receive")]
      (is (zero? exit))
      (is (= "15\n" stdout)))))

(deftest end-to-end-prelude-clock
  (testing "prelude-clock.cxc — prelude-shipped Clock capability atom fires 3 ticks then a `done` stream; main subscribes to both, increments count on each tick, and transitions to end on done, printing 3. Exercises prelude tree-shaking (Clock only included because referenced), main-atom-lifecycle with a nested-slot cap, and multi-stream subscription per source atom."
    (let [{:keys [exit stdout]} (compile-and-run "prelude-clock")]
      (is (zero? exit))
      (is (= "3\n" stdout)))))

(deftest end-to-end-list-count
  (testing "list-count.cxc — Layer 5 Step 3 lands `List of T` as a compiler-synth class per instantiation. main declares `xs: List of Int = [10,20,30,40]`; init constructs a `_Cxc_ListOfInt` instance backed by NSMutableArray; `xs.count` observable getter returns the element count via (int)[_elements count]. Prints 4."
    (let [{:keys [exit stdout]} (compile-and-run "list-count")]
      (is (zero? exit))
      (is (= "4\n" stdout)))))

(deftest end-to-end-list-add
  (testing "list-add.cxc — audit F44 landing: `then: xs.add(e)` on a List-typed field dispatches through the container's method-index (name `add` → selector `addWithe:`), lowering to `[_xs addWithe:@(e)]` with call-site NSNumber boxing for primitive elements. Retires the former `then: xs => xs + e` pattern-match (which violated spec §3.9 arithmetic-only + / - and spec §1.11 pure-then: rule). Initial [1,2].add(3) → count=3."
    (let [{:keys [exit stdout]} (compile-and-run "list-add")]
      (is (zero? exit))
      (is (= "3\n" stdout)))))

(deftest end-to-end-list-remove
  (testing "list-remove.cxc — mirror of list-add under audit F44: `then: xs.remove(e)` routes to `-removeWithe:` → `[_elements removeObject:@(e)]`. NSMutableArray isEqual: matches boxed NSNumber, so removing 20 from [10,20,30] leaves count=2."
    (let [{:keys [exit stdout]} (compile-and-run "list-remove")]
      (is (zero? exit))
      (is (= "2\n" stdout)))))

(deftest end-to-end-list-for-each
  (testing "list-for-each.cxc — Layer 5 Step 5. `xs.for_each(e => print(e))` lowers to `[xs cxc_for_each:^(id _cxc_e) { int e = [_cxc_e intValue]; ... }]` on a List container. Element unboxed per element-type (Int→intValue). Prints 7 11 13. (F20 REJECTED 2026-08-18 — the `for e in xs:` sugar retired; iteration is method dispatch.)"
    (let [{:keys [exit stdout]} (compile-and-run "list-for-each")]
      (is (zero? exit))
      (is (= "7\n11\n13\n" stdout)))))

(deftest end-to-end-import-only
  (testing "import-only.cxc — `from std.time import Duration, Instant` names resolve against the prelude at Layer 5 (all names still resolve globally; namespace-scoped resolution is Layer 6). Program computes 500 + 250 → 750. (Renamed from module-header 2026-08-20: F73 retired the user `module <name>` header — module identity is file path — so the file no longer carries or exercises that shape.)"
    (let [{:keys [exit stdout]} (compile-and-run "import-only")]
      (is (zero? exit))
      (is (= "750\n" stdout)))))

(deftest end-to-end-clock-now
  (testing "clock-now.cxc — Layer 5b Step A + Step 9. Clock gains a `now :- native_millis_now()` observable via the compiler-internal FFI hatch (native_* builtins lower to raw C expressions). Program takes `clock.now > 0` — always 1 on any system with a valid wall clock — as a liveness check on the observable and prints 1. Also exercises the fanout-selector NSObject category emitted at the top of the file: Clock's tick/done streams have no subscribers here, and Apple clang treats an unknown-selector send to an `id`-typed receiver as a hard error; the category makes every fanout selector declared program-wide."
    (let [{:keys [exit stdout]} (compile-and-run "clock-now")]
      (is (zero? exit))
      (is (= "1\n" stdout)))))

(deftest end-to-end-f16-l3-enum-first-class
  (testing "f16-l3-enum-first-class.cxc — F16 L3 verify: enum values flow as first-class expressions in ctor calls (`Box(d: Direction.down)`) and as field defaults (`d: Direction = Direction.up`). Two coupled fixes: (1) parser — `looks-like-msg-payload-parens?` now excludes `.` after the type ident (previously the heuristic misparsed `Box(d: Direction.down)` as a `:msg-shape` payload declaration because it only excluded `(`); (2) lower — `default-expr-const-shape?` now accepts `:dot` when target is a bare `:ident` naming an enum in enum-env AND field is a bare (no-payload) case, satisfying spec §3.8.5 rule 1 for enum-typed defaults. Payload-case defaults (`.ok(v: 1)`) remain rejected (compound-literal defaults deferred). Prints 0/1/2 on three lines."
    (let [{:keys [exit stdout]} (compile-and-run "f16-l3-enum-first-class")]
      (is (zero? exit))
      (is (= "0\n1\n2\n" stdout)))))

(deftest end-to-end-auto-nil-visible-to-next-parent-guard
  (testing "auto-nil-visible-to-next-parent-guard.cxc — F35 L7 pins spec §1.11 line 1284: sub-atom slot auto-nils atomically at the end of the sub-atom's `end`-transition, BEFORE any other transition on the parent evaluates its arm-head `|:` proposition. Child emits `:> finished` in its die-transition, then transitionToEnd runs sync (L5 cascade + L6 [home nilSlot:@\"child\"] direct call) → `home._child = nil`. Home's `alive <: child.finished` receive dispatches later, proposition `|: child is nil` MUST hold (proof of L7 guarantee). The matching path emits `:> ok` back to main. Main waits on `home.ok` before ending — so if the proposition were false, no ok emit fires, dispatch_main idles forever (test hangs, not prints 0). Prints 1."
    (let [{:keys [exit stdout]} (compile-and-run "auto-nil-visible-to-next-parent-guard")]
      (is (zero? exit))
      (is (= "1\n" stdout)))))

(deftest end-to-end-random-below
  (testing "random-below.cxc — Layer 5b Step 11. Prelude ships `Random` with `next_u32 :- native_random_u32()` observable and `below(n)` method wrapping `native_random_below(n)` — both hatched through the FFI-builtin registry to `arc4random`/`arc4random_uniform`. Deterministic assertion: `rng.below(1)` always returns 0."
    (let [{:keys [exit stdout]} (compile-and-run "random-below")]
      (is (zero? exit))
      (is (= "0\n" stdout)))))

(deftest end-to-end-test-keyboard
  (testing "test-keyboard.cxc — Layer 5b Step 12. Prelude ships `TestKeyboardInput(events: Int)` — an async self-send loop emitting `pressed(key: Int)` `events` times followed by `done`, mirroring the Clock stub shape. Main subscribes to both, counts presses in a `then:` update, transitions on `done`, prints 3. Also confirms the collect-message-shapes fix: dot-sourced receives (`<: input.pressed(key: Int)`) route through the subscription-map path, not the internal message-index (which would collide on nil msg-name for two dot-sourced streams). Prelude enum `Key` is emitted but unused by this program — game-toys is Step 14."
    (let [{:keys [exit stdout]} (compile-and-run "test-keyboard")]
      (is (zero? exit))
      (is (= "3\n" stdout)))))

(deftest end-to-end-game-loop
  (testing "game-loop.cxc — F62-migrated. Composes two prelude capabilities (Clock + TestKeyboardInput) in one main atom. Uses a two-transition arm-head `|:` proposition pattern on `clock.every(rate_ms: 5){t: Instant}` — one running proposition that increments through 4 ticks, one end proposition that holds when the count reaches 4. Also subscribes to input.pressed (adds 1) and input.done (no-op). Total = 2 presses + 4 ticks = 6. Deterministic across runs since 5ms is well under the input-fire deadline; both sources drain into main's own_queue and serialize."
    (let [{:keys [exit stdout]} (compile-and-run "game-loop")]
      (is (zero? exit))
      (is (= "6\n" stdout)))))

(deftest end-to-end-clock-every
  (testing "clock-every.cxc — F62-migrated. Method-returned Stream subscribe with receive-side counting bound (no external Ticker). Clock ships `every(rate_ms: Int) -> Stream of Instant` (spec §1913 signature) via `native_clock_every` FFI hatch — creates a CxcStream carrying a per-instance receiver-side selector plus a dispatch_source_t timer. Main subscribes via `<: clock.every(rate_ms: 5){t: Instant}` with a two-transition arm-head `|:` proposition pair (increment through 1 tick, then exit). `beats > 0` at end verifies the stream fired at least once."
    (let [{:keys [exit stdout]} (compile-and-run "clock-every")]
      (is (zero? exit))
      (is (= "1\n" stdout)))))

;; f8-l3d-ii-beta-walker-* + end-to-end-deprecation-warning retired
;; 2026-08-30 with the annotation-grammar-retirement wave. `@deprecated`
;; no longer parses; K3 walker deleted; deprecation is a comment/user
;; convention at v1.0. deprecation-warning.cxc example file retired
;; separately (see examples/ cleanup).

(deftest end-to-end-event-callback
  (testing "event-callback.cxc — Layer 6 Step 12 MVP. `data: cb: Block` on main (bare `Block` type = zero-arg void-return escaping block, `void (^)(void)` at C). `then: cb => () => print(42)` installs the block via a new zero-arg-lambda grammar path — parser recognizes `()` `=>` as `{:node :lambda :params [] :body <expr>}`, lowered to `{:node :objc-block-literal :params [] :stmts [<body-expr>]}` when the containing type context is Block. `then: cb()` invokes the stored block — bare-ident-callee resolution recognizes Block-typed self-fields and emits `_cb()`. Block field promotes `def main` to a real atom class so ivar storage + ctx-with-self are available. ARC-wise, block property is `nonatomic copy` so stack blocks migrate to heap on assignment (standard ObjC block-property pattern). Prints `42`."
    (let [{:keys [exit stdout]} (compile-and-run "event-callback")]
      (is (zero? exit))
      (is (= "42\n" stdout)))))

(deftest end-to-end-set-add-remove
  (testing "set-add-remove.cxc — Layer 6 Step 9. `Set of String` field on main; add alpha, add beta, add alpha (dup — no-op), remove beta. Then check `.count` (1), `.contains(\"alpha\")` (1), `.contains(\"beta\")` (0). Verifies: (1) grammar reuse of `Set of T` (existing `of` single-arg shape works — only base-type-name/cxc-type->c gained a `Set` branch); (2) mangled class name `_Cxc_SetOfString`; (3) NSMutableSet backing with value-equality dedup; (4) same K/V boxing helpers as Map cover Int/String; (5) parallel auto-init-to-empty for Set fields without an explicit default (no set-literal grammar at MVP)."
    (let [{:keys [exit stdout]} (compile-and-run "set-add-remove")]
      (is (zero? exit))
      (is (= "1\n1\n0\n" stdout)))))

(deftest end-to-end-map-basic
  (testing "map-basic.cxc — Layer 6 Step 8. `Map of (String, Int)` field on main, populated via `.add(k, v)`, mutated via `.remove(k)`, queried via `.count` observable and `.contains(k)`. Verifies: (1) grammar for `Map of (K, V)` (paren-tuple as `of` operand → :type-tuple :inner); (2) mangled class name `_Cxc_MapOfStringInt` from base-type-name; (3) auto-init to empty NSMutableDictionary when no field default (MVP has no map-literal grammar; explicit defaults fail-nyi); (4) K/V restricted to Int/String at MVP — String boxes via `[NSString stringWithUTF8String:]`, Int via `@(v)`; (5) synthesized container class ships with `count` observable and `addWithk:v:` / `removeWithk:` / `containsWithk:` methods on the atom method-dispatch path. Add apples=3, pears=7, remove apples → count=1, contains(apples)=0, contains(pears)=1. Prints `1`, `0`, `1`."
    (let [{:keys [exit stdout]} (compile-and-run "map-basic")]
      (is (zero? exit))
      (is (= "1\n0\n1\n" stdout)))))

(deftest end-to-end-console-hello
  (testing "console-hello.cxc — Layer 6 Step 5. Console prelude atom with `write(msg: String) -> Int` method wrapping the `native_console_write` FFI hatch (printf %s). Main is capability-provisioned (`def main(console: Console)`) and calls `console.write(\"hi\\n\")` in its `-> running do:`. Prints `hi` + newline."
    (let [{:keys [exit stdout]} (compile-and-run "console-hello")]
      (is (zero? exit))
      (is (= "hi\n" stdout)))))

(deftest end-to-end-multi-file-demo
  (testing "multi-file-demo/ — Layer 6 Steps 2+3 (module system minimum viable). Two-file program: `counter.cxc` declares a `Counter` record; `main.cxc` uses it via `Counter(seed: 41).bump()` and declares `from std.time import Duration` (advisory — unused). `compile-directory-to-file` reads both files, synthesizes per-file `module <derived>` headers internally for provenance (F73: user-facing `module <name>` retired, module identity is file path), validates the import against `stdlib-manifest`, strips the synthesized headers before lower, and processes as one program. Prints 42."
    (let [{:keys [exit stdout]} (compile-dir-and-run "multi-file-demo")]
      (is (zero? exit))
      (is (= "42\n" stdout)))))

(deftest end-to-end-hello-slice-b8-minimum
  (testing "hello-slice/ — B8 minimum viable (2026-09-02). Two-file program proving cross-module capability propagation for plugin-authored capability atoms end-to-end. `hello_slice/writer.cxc` declares `def plugin:` with a libc `puts` foreign symbol PLUS a peer `def Writer:` capability atom whose body carries `use plugin: Plugin` and a `write(msg) :- plugin.get(\"puts\")(msg)` observable. `main.cxc` at the src-dir root does `from hello_slice import Writer` + `use writer: Writer` and calls `writer.write(\"hello from B8 slice\")`. Verifies the F76-cousin auto-provisioning: main's F50 bootstrap constructs Writer via `Writer()`; Writer's ctor-params include a Plugin cap that main did NOT supply; `lower-atom-ctor` recognizes Plugin as a runtime-auto-provisioned capability and fills the slot inline via a recursive Plugin() construction. Before this landing the same fixture failed with `no value provided for param plugin`. Prints one line to stdout."
    (let [{:keys [exit stdout]} (compile-dir-and-run "hello-slice")]
      (is (zero? exit))
      (is (= "hello from B8 slice\n" stdout)))))

(deftest end-to-end-list-atom-fan-in
  (testing "list-atom-fan-in.cxc — F62-migrated. `List of AtomType` fan-in with element identity. Container holds a `List of Emitter` populated by a literal `[Emitter(seed: 10), Emitter(seed: 20)]`. Each Emitter emits `beep(v: val)` on its `-> running do:`. Main subscribes via `<: xs.beep(e: Emitter, v: Int)` and accumulates `v` into total; a Clock.every(50) receive-side count bound (single tick) fires `-> end` after both beeps arrive. Verifies three landing pieces: (1) atom-typed elements accepted in list literals (previously fail-nyi as `not boxable`); (2) container auto-subscribes to each element's fan-in stream at `-initWithElements:` for literal-loaded elements (runtime `cxc_add:` path already did this; init-load path was silently unhooked); (3) untargeted `:>` in transition then: blocks defers via `cxc_send_async(self, ...)` so subscribers registered in later init phases (container init, main runtimeBinding) are hooked up when the fanout runs on dispatch_main. Total = 10 + 20 = 30."
    (let [{:keys [exit stdout]} (compile-and-run "list-atom-fan-in")]
      (is (zero? exit))
      (is (= "30\n" stdout)))))

(deftest end-to-end-clock-every-typed
  (testing "clock-every-typed.cxc — method-stream typed payload. Extends clock-every.cxc by reading `t.millis` in the subscriber body (`then: got_millis => t.millis`). Verifies the whole typed-payload chain: `method-stream-payload-shapes` registry entry for `[_Cxc_Clock every]` declaring `t: Instant`, receive-side validation matching the declared shape, selector `cxc_stream_every_from__Cxc_ClockWithsender:t:` synthesized on both fanout and receive sides, and native_clock_every's specialized event handler building a fresh `_Cxc_Instant` per fire and dispatching via `((void(*)(id,SEL,id,_Cxc_Instant))objc_msgSend)` cast. `got_millis > 0` at end because timeIntervalSince1970 is always positive on any live system. Prints 1."
    (let [{:keys [exit stdout]} (compile-and-run "clock-every-typed")]
      (is (zero? exit))
      (is (= "1\n" stdout)))))

(deftest end-to-end-duration-basics
  (testing "duration-basics.cxc — F5 retirement. Unit suffixes (`500ms`, `2s`, `16ms`) removed per spec §2.7.1 (\"suffix syntax is not part of v1\"). Users construct durations explicitly via `Duration(millis: N)`; suffix machinery in lex + prelude retired. Expression `Duration(millis: 500).millis + Duration(millis: 2000).millis` computes 500 + 2000 = 2500."
    (let [{:keys [exit stdout]} (compile-and-run "duration-basics")]
      (is (zero? exit))
      (is (= "2500\n" stdout)))))

(deftest end-to-end-invariant-chained
  (testing "invariant-chained.cxc — L2b / stale-check retirement. Own-line `|: -lim <= val <= lim` feeds the existing F49 machinery. Chained comparisons desugar to `(A <= B) and (B <= C)` at parse time (parser's `desugar-chained-comparisons` walks the entire AST); the stale fail-nyi in lower's invariant path was dead code and is retired. Unary minus (`-lim`) lands as `:neg` (parser) → `:unary` op `-` in emit — pairs cleanly with lower's new `:neg` inference (numeric-preserving). Program constructs Bounded(y: 3, limit: 7); passing invariant runs through; prints 3."
    (let [{:keys [exit stdout]} (compile-and-run "invariant-chained")]
      (is (zero? exit))
      (is (= "3\n" stdout)))))

(deftest end-to-end-picker-narrowing-with-f41-ctor-params
  (testing "picker-narrowing-f41.cxc — L2b × F41 × F36 Landing 2a × F49 Landing A end-to-end. Age has a direct `raw_value: Int` field plus own-line lower/upper-bound propositions (picker narrows storage to uint8_t). Program constructs `Age(value: 42)`, reads back the value, prints 42. In-range case runs to completion — storage narrowing is transparent to the user."
    (let [{:keys [exit stdout]} (compile-and-run "picker-narrowing-f41")]
      (is (zero? exit))
      (is (= "42\n" stdout)))))

(deftest end-to-end-picker-narrowing-out-of-range-aborts-on-pre-store-check
  (testing "picker-narrowing-oor.cxc — same shape but `Age(value: 300)` violates the invariant. Pre-store check (F49 Landing A) fires on the WIDE int64 ctor param BEFORE the narrowing uint8_t assignment. Without pre-store check, storage would silently truncate 300 → 44 (300 & 0xff) and the post-store check would pass on 44. With pre-store check, aborts with the source-value-violates diagnostic — 300 never reaches the narrowing store."
    (let [{:keys [exit stderr]} (compile-and-run "picker-narrowing-oor")]
      (is (not (zero? exit)) "expected non-zero exit from pre-store check abort")
      (is (re-find #"Age invariant #1 failed at construction \(source value violates\)"
                   (or stderr ""))
          "expected pre-store diagnostic naming construction site + source value violation"))))

(deftest end-to-end-stdlib-basics
  (testing "stdlib-basics.cxc — three stdlib additions in one program. (1) Prelude `Position` record with `x: Float, y: Float`; ctor + field access. (2) `clamp(x, lo, hi)` compiler builtin — polymorphic on first arg's numeric type (Int here), lowers to nested-ternary via a fresh :ffi-call with an inline emit-c-fn. (3) `Instant.epoch` desugars via `desugar-prelude-class-members` to `Instant(millis: 0)` — a small closed set of prelude class-member statics registered in `class-member-desugars`. Program: `clamp(15,0,10) + Instant.epoch.millis + Position(x:1.0, y:2.0).x` → 10 + 0 + 1.0 = 11.0."
    (let [{:keys [exit stdout]} (compile-and-run "stdlib-basics")]
      (is (zero? exit))
      (is (= "11.000000\n" stdout)))))

(deftest end-to-end-explicit-target-macos
  (testing "F55 Landing 3 — passing `:target :macos` through the opts arity produces byte-equivalent output to the default arity (backward compat + wiring proof). Uses hello.cxc which touches no target-specific caps so behavior is identical; the assertion here is 'no regression from opts arity'."
    (let [src     (example "hello")
          out-dir (tmp-dir)
          {:keys [status stderr bin-path]}
          (build/compile-source-to-file src "hello" (.getPath out-dir) {:target :macos})]
      (is (zero? status) (str "compile failed with explicit :macos target:\n" stderr))
      (when (zero? status)
        (let [{:keys [exit stdout]} (build/run-binary bin-path)]
          (is (zero? exit))
          (is (= "hello, world\n" stdout)))))))

(deftest end-to-end-let-binding
  (testing "let-binding.cxc — F18 L1 statement-form `let x = expr` inside a `then:` clause. Post F18 L1' Landing 2 (2026-08-24) the transition `let:` sub-block is retired; the example migrated to statement-form. Bindings emit as C var-decls at the point they appear; sequential bindings reference each other. Program: `let doubled = 10 * 2; let plus_one = doubled + 1; print(doubled); print(plus_one)` prints 20, then 21."
    (let [{:keys [exit stdout]} (compile-and-run "let-binding")]
      (is (zero? exit))
      (is (= "20\n21\n" stdout)))))

(deftest end-to-end-record-equality
  (testing "record-equality.cxc — F37 synthesized structural equality. Vec2(1,2)==Vec2(1,2) prints 1; Vec2(1,2)==Vec2(1,3) prints 0; nested Line equality with per-field record recursion prints 1. Prior lowering emitted raw C `==` on struct values (illegal C); F37 reroutes through `<Record>_cxc_equals`."
    (let [{:keys [exit stdout]} (compile-and-run "record-equality")]
      (is (zero? exit))
      (is (= "1\n0\n1\n" stdout)))))

(deftest f32-l1b-concurrent-mode-main-transitions-and-shutdown-drain
  (testing "F32 L1b (integration) — a main-only program with explicit transitions (`self :> stop` → running-consumes-stop → `-> end`) runs to completion under concurrent mode (`:single-queue? false`). Exercises: L1's runtime class-name check keeps CxcMain on main-target; L2's shutdown drain fires under `!CXC_SINGLE_MAIN_QUEUE` before `exit(0)` (harmless here since main runs synchronously, but the barrier is emitted); L3's runtime cycle detector is active (no cycle to trip). Output must match single-queue mode."
    (let [r-conc (compile-and-run-concurrent "main-shutdown-on-end")]
      (is (zero? (:exit r-conc)))
      (is (= "7\n" (:stdout r-conc))
          "concurrent-mode stdout matches the expected sequence for main-shutdown-on-end"))))

(deftest f32-l1b-concurrent-mode-cross-atom-message-send
  (testing "F32 L1b (integration) — console-hello dispatches a method-send (`console.write(\"hi\\n\")`) to a non-main prelude atom. Under concurrent mode, `console`'s own_queue targets the global pool (L1's runtime class-name check picks non-main branch); message delivery goes via `cxc_send_async` onto that queue. The write must complete before the process drains via L2's barrier and `exit(0)`. Proves async cross-atom sends survive the concurrent runtime end-to-end."
    (let [r-conc (compile-and-run-concurrent "console-hello")]
      (is (zero? (:exit r-conc)))
      (is (= "hi\n" (:stdout r-conc))
          "cross-atom async send completes under concurrent mode"))))

(deftest f32-l6-single-queue-flag-default-preserves-layer4-behavior
  (testing "F32 L6 — default `:single-queue?` (unset) matches `:single-queue? true` and produces a working binary. Layer 4 backward-compat: existing callers that don't know about the flag see zero change."
    (let [src     (example "hello")
          out-dir (tmp-dir)
          r-default (build/compile-source-to-file
                     src "hello" (.getPath out-dir))
          r-explicit (build/compile-source-to-file
                      src "hello-sq" (.getPath out-dir) {:single-queue? true})]
      (is (zero? (:status r-default))
          (str "default-flag compile failed:\n" (:stderr r-default)))
      (is (zero? (:status r-explicit))
          (str "explicit :single-queue? true compile failed:\n" (:stderr r-explicit)))
      (let [{exit1 :exit out1 :stdout} (build/run-binary (:bin-path r-default))
            {exit2 :exit out2 :stdout} (build/run-binary (:bin-path r-explicit))]
        (is (zero? exit1))
        (is (zero? exit2))
        (is (= out1 out2) "single-queue default and explicit=true produce byte-identical stdout")
        (is (= "hello, world\n" out1))))))

(deftest f32-l6-concurrent-mode-flag-threads-to-clang
  (testing "F32 L6 — `:single-queue? false` opts into F32 concurrent mode. `-DCXC_SINGLE_MAIN_QUEUE=0` reaches clang, the L1 concurrent-mode branch selects the global pool for non-main atoms, L2's shutdown drain fires, L5 keeps the stream queue off main. A trivial `hello` program (no atoms beyond main, no observables) still prints correctly — its main atom stays main-targeted per L1's runtime class-name check."
    (let [src     (example "hello")
          out-dir (tmp-dir)
          r       (build/compile-source-to-file
                   src "hello-concurrent" (.getPath out-dir)
                   {:single-queue? false})]
      (is (zero? (:status r))
          (str "concurrent-mode compile failed:\n" (:stderr r)))
      (when (zero? (:status r))
        (let [{:keys [exit stdout]} (build/run-binary (:bin-path r))]
          (is (zero? exit))
          (is (= "hello, world\n" stdout)))))))

(deftest f32-l6-flag-participates-in-cache-key
  (testing "F32 L6 — the queue-mode flag is folded into the cache SHA so a single-queue build and a concurrent build of the same source don't collide. Emit itself is preprocessor-guarded (m-src is identical), but the clang command line and thus the binary differ; sharing the cache would silently return the wrong binary. Two compiles of the same source, one per flag value, must produce two distinct cache-bin entries. Confirmed by: first call with each flag is a miss (unique-per-test source ensures cache doesn't leak across test runs); a second call with the SAME flag is a hit (proves normal cache works); a call with the OTHER flag is a miss (proves the flag key participates)."
    ;; Unique-per-run source (nonce baked into a print) guarantees the
    ;; SHA input is fresh, so the repo-scoped `.cxc-cache` doesn't leak
    ;; from prior test runs.
    (let [nonce   (str (System/nanoTime))
          src     (str "def main:\n"
                       "    -> running\n"
                       "        then: print(\"f32-l6 " nonce "\")\n")
          out-dir (tmp-dir)
          ;; Prime with single-queue mode (miss — fresh SHA).
          r-sq-1  (build/compile-source-to-file
                   src "hello-cache-sq1" (.getPath out-dir) {:single-queue? true})
          ;; Re-run same mode → hit.
          r-sq-2  (build/compile-source-to-file
                   src "hello-cache-sq2" (.getPath out-dir) {:single-queue? true})
          ;; Switch mode → miss (flag folded into key).
          r-conc  (build/compile-source-to-file
                   src "hello-cache-cn" (.getPath out-dir) {:single-queue? false})]
      (is (false? (:cache-hit? r-sq-1))
          "first compile of fresh source is a miss")
      (is (true? (:cache-hit? r-sq-2))
          "second compile with same flag hits the cache")
      (is (false? (:cache-hit? r-conc))
          "flipping the flag misses the cache (proves the flag is part of the key)"))))

(deftest explicit-target-unknown-rejected
  (testing "F55 Landing 3 — an unknown target name raises a targeted diagnostic naming the missing resource path"
    (let [src     (example "hello")
          out-dir (tmp-dir)
          ex      (try (build/compile-source-to-file
                        src "hello" (.getPath out-dir) {:target :nowhere})
                       nil
                       (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "expected an exception for an unknown target")
      (is (re-find #"prelude module resource missing" (ex-message ex))))))

;; ---------------------------------------------------------------------------
;; F51 L1 — module identity + provenance at compile-directory entry.
;; ---------------------------------------------------------------------------

(defn- write-file! [^java.io.File dir rel-path content]
  (let [f (java.io.File. dir rel-path)]
    (.mkdirs (.getParentFile f))
    (spit f content)
    f))

(deftest f73-user-module-header-rejected-with-fix-it
  (testing "F73 (2026-08-20, supersedes F51 L1) — a `module <name>` header at the top of any source file is a hard error at parse time. Module identity is the file path relative to the source root; the resolver walks the tree. Diagnostic names the retirement and points at the fix (delete the line)."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "hello.cxc"
                               "module com.example.hello\n\ndef main:\n    -> running\n        then: print(1)\n")
          ex      (try
                    (build/compile-directory-to-file (.getPath src-dir)
                                                     "hello" (.getPath out-dir))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "expected fail! on the retired header")
      (is (re-find #"`module <name>` declaration is retired" (ex-message ex)))
      (is (re-find #"file path relative to the source root" (ex-message ex))
          "diagnostic names the file-path-authoritative rule"))))

(deftest f51-l1-reserved-main-subdirectory-rejected
  (testing "F51 L1 — a `src-dir/main/` subdirectory collides with the reserved `:main` module (root of src-dir + single-file compile). Rejected at compile start with a rename fix-it."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "root.cxc"
                               "def main:\n    -> running\n        then: print(1)\n")
          _       (write-file! src-dir "main/other.cxc"
                               "def X:\n    n: Int\n")
          ex      (try
                    (build/compile-directory-to-file (.getPath src-dir)
                                                     "prog" (.getPath out-dir))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (re-find #"F51 L1: subdirectory" (ex-message ex)))
      (is (re-find #"reserved `:main`" (ex-message ex))))))

(deftest f51-l1-duplicate-declaration-in-same-module-rejected
  (testing "F51 L1 — same declaration name in two files in the same directory (== same module) is a hard error naming the collision and both files."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "a.cxc"
                               "def V:\n    n: Int\n")
          _       (write-file! src-dir "b.cxc"
                               "def V:\n    n: Int\n\ndef main:\n    -> running\n        then: print(1)\n")
          ex      (try
                    (build/compile-directory-to-file (.getPath src-dir)
                                                     "prog" (.getPath out-dir))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (re-find #"F51 L1: declaration `V` appears more than once"
                   (ex-message ex)))
      (is (re-find #"main" (ex-message ex))
          "names the module (root of src-dir → :main)"))))

(deftest f51-l1-multi-file-demo-migrates-cleanly
  (testing "F51 L1 — the migrated multi-file-demo (module headers removed) still compiles + runs. Uses the existing example on disk."
    (let [{:keys [exit stdout]} (compile-dir-and-run "multi-file-demo")]
      (is (zero? exit) "runs successfully after header removal")
      (is (= "42\n" stdout) "prints Counter(seed: 41).bump = 42"))))

;; ---------------------------------------------------------------------------
;; F51 L2 — module dep graph + cycle detection + topological order.
;; ---------------------------------------------------------------------------

(deftest f51-l2-topological-order-basic
  (testing "F51 L2 — a module that imports another user module compiles cleanly with dep-order concatenation (imported module's decls come first)."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "lib/util.cxc"
                               "def Helper:\n    x: Int\n")
          _       (write-file! src-dir "app.cxc"
                               "from lib import Helper\n\ndef main:\n    -> running\n        then: print(1)\n")
          {:keys [status stderr]}
          (build/compile-directory-to-file (.getPath src-dir)
                                           "prog" (.getPath out-dir))]
      (is (zero? status) (str "clang failed: " stderr)))))

(deftest f51-l2-cycle-two-modules-rejected
  (testing "F51 L2 — module `a` imports `b` and `b` imports `a` — cycle rejected with a diagnostic naming both modules in the cycle path."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "a/x.cxc"
                               "from b import Y\n\ndef X:\n    n: Int\n")
          _       (write-file! src-dir "b/y.cxc"
                               "from a import X\n\ndef Y:\n    n: Int\n")
          _       (write-file! src-dir "main.cxc"
                               "from a import X\n\ndef main:\n    -> running\n        then: print(1)\n")
          ex      (try
                    (build/compile-directory-to-file (.getPath src-dir)
                                                     "prog" (.getPath out-dir))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "expected cycle rejection")
      (is (re-find #"F51 L2: module import cycle" (ex-message ex)))
      (is (re-find #"\ba\b" (ex-message ex)) "diagnostic names module `a`")
      (is (re-find #"\bb\b" (ex-message ex)) "diagnostic names module `b`")
      (is (= :cxc-f51-module-cycle (:kind (ex-data ex)))))))

(deftest f51-l2-cycle-three-modules-rejected
  (testing "F51 L2 — a 3-module cycle `a → b → c → a` is rejected and the full cycle path is in the diagnostic."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "a/x.cxc"
                               "from b import B\n\ndef A:\n    n: Int\n")
          _       (write-file! src-dir "b/x.cxc"
                               "from c import C\n\ndef B:\n    n: Int\n")
          _       (write-file! src-dir "c/x.cxc"
                               "from a import A\n\ndef C:\n    n: Int\n")
          _       (write-file! src-dir "main.cxc"
                               "from a import A\n\ndef main:\n    -> running\n        then: print(1)\n")
          ex      (try
                    (build/compile-directory-to-file (.getPath src-dir)
                                                     "prog" (.getPath out-dir))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (re-find #"F51 L2: module import cycle" (ex-message ex)))
      (is (re-find #"\ba\b" (ex-message ex)))
      (is (re-find #"\bb\b" (ex-message ex)))
      (is (re-find #"\bc\b" (ex-message ex))))))

(deftest f51-l2-unknown-module-import-rejected
  (testing "F51 L2 — `from unknown_mod import X` in a user file is rejected: the module is not another user module in src-dir and not a prelude module."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "main.cxc"
                               "from unknown_mod import X\n\ndef main:\n    -> running\n        then: print(1)\n")
          ex      (try
                    (build/compile-directory-to-file (.getPath src-dir)
                                                     "prog" (.getPath out-dir))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "expected unknown-module rejection")
      (is (re-find #"unknown module `unknown_mod`" (ex-message ex))
          "diagnostic names the unknown module"))))

(deftest f51-l2-self-import-rejected
  (testing "F51 L2 — a module importing itself (e.g. `from foo import X` inside a file in module `foo`) is trivially wrong; rejected explicitly with a per-module diagnostic."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "foo/a.cxc"
                               "from foo import Y\n\ndef X:\n    n: Int\n")
          _       (write-file! src-dir "foo/b.cxc"
                               "def Y:\n    n: Int\n")
          _       (write-file! src-dir "main.cxc"
                               "from foo import X\n\ndef main:\n    -> running\n        then: print(1)\n")
          ex      (try
                    (build/compile-directory-to-file (.getPath src-dir)
                                                     "prog" (.getPath out-dir))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (re-find #"F51 L2: module `foo` imports itself" (ex-message ex)))
      (is (= :cxc-f51-self-import (:kind (ex-data ex)))))))

(deftest f52-l1-import-x-parses-and-adds-dep-edge
  (testing "F52 L1 — `import X` parses to a `:import` top node and contributes an edge to module `X` in the dep graph (same as `from X import ...`). The prog compiles cleanly because `lib` is a known user module and its decls concatenate before the consumer under topological order."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "lib/util.cxc"
                               "def Helper:\n    x: Int\n")
          _       (write-file! src-dir "app.cxc"
                               "import lib\n\ndef main:\n    -> running\n        then: print(1)\n")
          {:keys [status stderr]}
          (build/compile-directory-to-file (.getPath src-dir)
                                           "prog" (.getPath out-dir))]
      (is (zero? status) (str "clang failed: " stderr)))))

(deftest f52-l1-import-with-alias-parses
  (testing "F52 L1 — `import X as Y` parses cleanly; the `as` alias name is preserved on the AST node for downstream binding at F51 L3."
    (let [imp (first (cxc.parse/parse "import foo.bar as baz\n"))]
      (is (= :import (:node imp)))
      (is (= "foo.bar" (:from imp)))
      (is (= "baz" (:as imp))))))

(deftest f52-l1-import-unknown-module-rejected
  (testing "F52 L1 — `import unknown_mod` in a user file is rejected at build with the same diagnostic that `from unknown_mod import ...` would produce."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "main.cxc"
                               "import nowhere\n\ndef main:\n    -> running\n        then: print(1)\n")
          ex      (try
                    (build/compile-directory-to-file (.getPath src-dir)
                                                     "prog" (.getPath out-dir))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "expected unknown-module rejection")
      (is (re-find #"unknown module `nowhere`" (ex-message ex))))))

(deftest f52-l2-from-import-with-alias-parses
  (testing "F52 L2 — `from X import a as b, c` produces `:names [{:name \"a\" :as \"b\"} {:name \"c\" :as nil}]`. Aliased and plain entries mix on one import line."
    (let [n (first (cxc.parse/parse "from std.time import Duration as D, Instant\n"))]
      (is (= :from-import (:node n)))
      (is (= "std.time" (:from n)))
      (is (= [{:name "Duration" :as "D"} {:name "Instant" :as nil}]
             (:names n))))))

(deftest f52-l2-from-import-alias-of-nonexistent-name-rejected
  (testing "F52 L2 — `from std.time import Nope as N` rejects because `Nope` is not exported by `std.time`. Alias doesn't change export-check semantics."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "main.cxc"
                               "from std.time import Nope as N\n\ndef main:\n    -> running\n        then: print(1)\n")
          ex      (try
                    (build/compile-directory-to-file (.getPath src-dir)
                                                     "prog" (.getPath out-dir))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "expected unknown-import rejection")
      (is (re-find #"does not export `Nope`" (ex-message ex))))))

(deftest f52-l1-import-self-rejected
  (testing "F52 L1 — a module `import`ing itself (via a file in the same module directory) hits the same self-import rejection as `from foo import ...`."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "foo/a.cxc"
                               "import foo\n\ndef X:\n    n: Int\n")
          _       (write-file! src-dir "foo/b.cxc"
                               "def Y:\n    n: Int\n")
          _       (write-file! src-dir "main.cxc"
                               "from foo import X\n\ndef main:\n    -> running\n        then: print(1)\n")
          ex      (try
                    (build/compile-directory-to-file (.getPath src-dir)
                                                     "prog" (.getPath out-dir))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (re-find #"F51 L2: module `foo` imports itself" (ex-message ex)))
      (is (= :cxc-f51-self-import (:kind (ex-data ex)))))))

;; ---------------------------------------------------------------------------
;; F51 L3a — user declaration + imported name collision (per-module).
;; ---------------------------------------------------------------------------

(deftest f51-l3a-from-import-name-collides-with-decl-rejected
  (testing "F51 L3a — `from lib import Helper` alongside `def Helper:` in the same module is a hard collision: the imported name and the declared name bind the same local identifier."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "lib/util.cxc"
                               "def Helper:\n    x: Int\n")
          _       (write-file! src-dir "app.cxc"
                               "from lib import Helper\n\ndef Helper:\n    n: Int\n\ndef main:\n    -> running\n        then: print(1)\n")
          ex      (try
                    (build/compile-directory-to-file (.getPath src-dir)
                                                     "prog" (.getPath out-dir))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "expected collision rejection")
      (is (re-find #"F51 L3a: `Helper` in module `main`" (ex-message ex)))
      (is (re-find #"both declared" (ex-message ex)))
      (is (re-find #"and imported" (ex-message ex)))
      (is (re-find #"add `as <alias>`" (ex-message ex)))
      (is (= :cxc-f51-import-collides-with-decl (:kind (ex-data ex)))))))

(deftest f51-l3a-aliased-from-import-avoids-collision
  (testing "F51 L3a — `from lib import Helper as H` alongside `def Helper:` does NOT fire the L3a collision diagnostic: the alias makes the import's local binding `H`, disjoint from the declared `Helper`. Downstream flat-namespace concatenation collision is a separate concern that F51 L3b (full resolver) resolves."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "lib/util.cxc"
                               "def Helper:\n    x: Int\n")
          _       (write-file! src-dir "app.cxc"
                               "from lib import Helper as H\n\ndef Helper:\n    n: Int\n\ndef main:\n    -> running\n        then: print(1)\n")
          ex      (try
                    (build/compile-directory-to-file (.getPath src-dir)
                                                     "prog" (.getPath out-dir))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (not= :cxc-f51-import-collides-with-decl
                (some-> ex ex-data :kind))
          "aliased import must not trip L3a collision"))))

(deftest f51-l3a-aliased-import-target-collides-with-decl-rejected
  (testing "F51 L3a — `from lib import Foo as Helper` alongside `def Helper:` collides: the alias binds to `Helper` locally, and the declaration also uses `Helper`. The alias'd name is the surface for collision, not the source-side name."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "lib/util.cxc"
                               "def Foo:\n    x: Int\n")
          _       (write-file! src-dir "app.cxc"
                               "from lib import Foo as Helper\n\ndef Helper:\n    n: Int\n\ndef main:\n    -> running\n        then: print(1)\n")
          ex      (try
                    (build/compile-directory-to-file (.getPath src-dir)
                                                     "prog" (.getPath out-dir))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "expected collision on the aliased local binding")
      (is (re-find #"F51 L3a: `Helper` in module `main`" (ex-message ex)))
      (is (= :cxc-f51-import-collides-with-decl (:kind (ex-data ex)))))))

(deftest f51-l3a-import-x-collides-with-decl-rejected
  (testing "F51 L3a — `import lib` alongside `def lib:` collides: the whole-module import binds the module name `lib` into the current scope, and the user's declaration also names `lib`. Fix-it: `import lib as L`."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "lib/util.cxc"
                               "def Helper:\n    x: Int\n")
          _       (write-file! src-dir "app.cxc"
                               "import lib\n\ndef lib:\n    n: Int\n\ndef main:\n    -> running\n        then: print(1)\n")
          ex      (try
                    (build/compile-directory-to-file (.getPath src-dir)
                                                     "prog" (.getPath out-dir))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "expected collision on the module-name binding")
      (is (re-find #"F51 L3a: `lib` in module `main`" (ex-message ex)))
      (is (= :cxc-f51-import-collides-with-decl (:kind (ex-data ex)))))))

(deftest f51-l3a-collision-across-files-in-same-module
  (testing "F51 L3a — the collision surface is the module, not the file: `def Helper:` in file a.cxc + `from lib import Helper` in file b.cxc (both under module `foo`) collides on `Helper`. The diagnostic names both files."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "lib/util.cxc"
                               "def Helper:\n    x: Int\n")
          _       (write-file! src-dir "foo/a.cxc"
                               "def Helper:\n    n: Int\n")
          _       (write-file! src-dir "foo/b.cxc"
                               "from lib import Helper\n")
          _       (write-file! src-dir "main.cxc"
                               "from foo import Helper\n\ndef main:\n    -> running\n        then: print(1)\n")
          ex      (try
                    (build/compile-directory-to-file (.getPath src-dir)
                                                     "prog" (.getPath out-dir))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "expected cross-file collision within the module")
      (is (re-find #"F51 L3a: `Helper` in module `foo`" (ex-message ex)))
      (is (re-find #"a\.cxc" (ex-message ex))
          "diagnostic names the declaration file")
      (is (re-find #"b\.cxc" (ex-message ex))
          "diagnostic names the import file"))))

;; ---------------------------------------------------------------------------
;; F53 L1 — per-module cxc-manifest.json emission.
;; ---------------------------------------------------------------------------

(defn- manifest-path [^java.io.File out-dir module]
  (java.io.File. out-dir (str module ".cxc-manifest.json")))

(deftest f53-l1-manifest-emitted-for-single-file
  (testing "F53 L1 — a root file emits main.cxc-manifest.json listing its declarations. Post annotation-grammar-retirement (2026-08-30) the manifest carries compiler-derived facts only (name/kind/variant/members); user-metadata surface awaits a replacement (e.g. `meta = Meta(...)` module-let)."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "app.cxc"
                               "def Foo:\n    n: Int\n\ndef main:\n    -> running\n        then: print(1)\n")
          {:keys [status stderr]}
          (build/compile-directory-to-file (.getPath src-dir)
                                           "prog" (.getPath out-dir))
          m-file  (manifest-path out-dir "main")
          m-json  (slurp m-file)]
      (is (zero? status) (str "clang failed: " stderr))
      (is (.exists m-file) "main.cxc-manifest.json written to out-dir")
      (is (pos? (.length m-file)) "manifest is non-empty")
      (is (re-find #"\"name\": \"Foo\"" m-json) "Foo export present"))))

(deftest f53-l1-manifest-includes-all-declarations
  (testing "F53 L1 — every user declaration in the module (records, atoms, enums) appears in the manifest's exports array with its kind."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "app.cxc"
                               (str "def Widget:\n    n: Int\n\n"
                                    "def Kind:\n    case red\n    case blue\n\n"
                                    "def main:\n    -> running\n        then: print(1)\n"))
          {:keys [status]}
          (build/compile-directory-to-file (.getPath src-dir)
                                           "prog" (.getPath out-dir))
          m-json  (slurp (manifest-path out-dir "main"))]
      (is (zero? status))
      (is (re-find #"\"name\": \"Widget\"" m-json) "Widget export present")
      (is (re-find #"\"kind\": \"record\"" m-json) "record kind serialised")
      (is (re-find #"\"name\": \"Kind\"" m-json) "Kind export present")
      (is (re-find #"\"kind\": \"enum\"" m-json) "enum kind serialised")
      (is (re-find #"\"name\": \"main\"" m-json) "main atom export present")
      (is (re-find #"\"kind\": \"def-atom\"" m-json) "def-atom kind serialised"))))

(deftest f53-l1-manifest-shape-matches-schema
  (testing "F53 L1 — manifest schema keys stay intact. Post annotation-grammar-retirement the `:metadata` object no longer appears (no source annotations to fold); reinstated when a replacement user-metadata surface lands."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "app.cxc"
                               "def main:\n    -> running\n        then: print(1)\n")
          _       (build/compile-directory-to-file (.getPath src-dir)
                                                    "prog" (.getPath out-dir))
          m-json  (slurp (manifest-path out-dir "main"))]
      (is (re-find #"\"\$schema\": \"cxc-manifest/v1\"" m-json))
      (is (re-find #"\"module\": \"main\"" m-json))
      (is (re-find #"\"compiler-version\":" m-json))
      (is (re-find #"\"compiled-at\":" m-json))
      (is (re-find #"\"source-hash\":" m-json))
      (is (re-find #"\"source-files\":" m-json))
      (is (re-find #"\"exports\":" m-json))
      (is (re-find #"\"imports\":" m-json)))))

(deftest f53-l1-manifest-emitted-per-module-in-multi-file
  (testing "F53 L1 — in multi-file mode with modules `lib` and `main`, one manifest is written per user module and each manifest lists only that module's declarations."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "lib/util.cxc"
                               "def Helper:\n    x: Int\n")
          _       (write-file! src-dir "app.cxc"
                               "from lib import Helper\n\ndef main:\n    -> running\n        then: print(1)\n")
          _       (build/compile-directory-to-file (.getPath src-dir)
                                                    "prog" (.getPath out-dir))
          lib-json  (slurp (manifest-path out-dir "lib"))
          main-json (slurp (manifest-path out-dir "main"))
          main-exports (first (str/split main-json #"\"imports\":"))]
      (is (re-find #"\"module\": \"lib\"" lib-json) "lib manifest names lib")
      (is (re-find #"\"name\": \"Helper\"" lib-json) "lib manifest lists Helper")
      (is (not (re-find #"\"name\": \"Helper\"" main-exports))
          "main manifest's exports do NOT list Helper (that's lib's export)")
      (is (re-find #"\"module\": \"main\"" main-json) "main manifest names main")
      (is (re-find #"\"name\": \"main\"" main-exports) "main manifest exports the main atom")
      (is (re-find #"\"kind\": \"from-import\"" main-json)
          "main manifest records its from-import of Helper"))))

(deftest f53-l1-manifest-source-hash-changes-on-source-change
  (testing "F53 L1 — module source content participates in the source-hash. Distinct sources yield distinct hashes; identical sources yield identical hashes."
    (let [compile-once
          (fn [src-content]
            (let [src-dir (tmp-dir)
                  out-dir (tmp-dir)
                  _       (write-file! src-dir "app.cxc" src-content)
                  _       (build/compile-directory-to-file (.getPath src-dir)
                                                            "prog" (.getPath out-dir))
                  json    (slurp (manifest-path out-dir "main"))]
              [(second (re-find #"\"source-hash\": \"([^\"]+)\"" json))
               json]))
          [h1 _] (compile-once
                   "def Foo:\n    n: Int\n\ndef main:\n    -> running\n        then: print(1)\n")
          [h2 _] (compile-once
                   "def Foo:\n    n: Int = 1\n\ndef main:\n    -> running\n        then: print(1)\n")]
      (is (some? h1) "hash extractable from first manifest")
      (is (some? h2) "hash extractable from second manifest")
      (is (not= h1 h2) "source change → different source-hash"))))

;; ---------------------------------------------------------------------------
;; F53 L2 — program-wide cxc-deps.json emission.
;; ---------------------------------------------------------------------------

(defn- deps-path [^java.io.File out-dir]
  (java.io.File. out-dir "cxc-deps.json"))

(deftest f53-l2-dep-graph-basic
  (testing "F53 L2 — a user module `app` importing user module `lib` produces `cxc-deps.json` at out-dir root, with `lib` declared and `app` listing lib in its depends-on."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "lib/util.cxc"
                               "def Helper:\n    x: Int\n")
          _       (write-file! src-dir "app.cxc"
                               "from lib import Helper\n\ndef main:\n    -> running\n        then: print(1)\n")
          _       (build/compile-directory-to-file (.getPath src-dir)
                                                    "prog" (.getPath out-dir))
          deps-file (deps-path out-dir)
          deps-json (slurp deps-file)]
      (is (.exists deps-file) "cxc-deps.json written at out-dir root")
      (is (re-find #"\"\$schema\": \"cxc-deps/v1\"" deps-json))
      (is (re-find #"\"topological-order\":" deps-json))
      (is (re-find #"\"modules\":" deps-json))
      (is (re-find #"\"lib\":" deps-json) "lib module present in graph")
      (is (re-find #"\"main\":" deps-json) "main module present in graph")
      (is (re-find #"\"depends-on\":\s*\[\s*\"lib\"\s*\]" deps-json)
          "main's depends-on lists lib"))))

(deftest f53-l2-dep-graph-includes-prelude-deps
  (testing "F53 L2 — when a user module imports a prelude module (e.g. std.time), the prelude module appears in the graph with reverse depended-by pointing at the user."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "app.cxc"
                               "from std.time import Duration\n\ndef main:\n    -> running\n        then: print(1)\n")
          _       (build/compile-directory-to-file (.getPath src-dir)
                                                    "prog" (.getPath out-dir))
          deps-json (slurp (deps-path out-dir))]
      (is (re-find #"\"std\.time\":" deps-json) "prelude module appears")
      (is (re-find #"\"main\":" deps-json))
      (is (re-find #"\"depends-on\":\s*\[\s*\"std\.time\"\s*\]" deps-json)
          "main depends on std.time"))))

(deftest f53-l2-dep-graph-reverse-edges-correct
  (testing "F53 L2 — the depended-by list is the correct reverse of depends-on: when app imports lib, lib.depended-by contains app."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "lib/util.cxc"
                               "def Helper:\n    x: Int\n")
          _       (write-file! src-dir "app.cxc"
                               "from lib import Helper\n\ndef main:\n    -> running\n        then: print(1)\n")
          _       (build/compile-directory-to-file (.getPath src-dir)
                                                    "prog" (.getPath out-dir))
          deps-json (slurp (deps-path out-dir))
          ;; Extract lib's block so we can assert on its depended-by
          lib-block (re-find #"\"lib\":\s*\{[^}]*\}" deps-json)]
      (is (some? lib-block) "lib module block present")
      (is (re-find #"\"depended-by\":\s*\[\s*\"main\"\s*\]" lib-block)
          "lib is depended-by main"))))

;; ---------------------------------------------------------------------------
;; F52 L3 — source overrides (`:source-overrides {module → path}`).
;; ---------------------------------------------------------------------------

(deftest f52-l3-source-override-replaces-module-contents
  (testing "F52 L3 — `:source-overrides {\"lib\" path}` substitutes the on-disk source for module `lib` with the contents of `path`. The original file's path + derived module stay intact; only the source string swaps. Verifies the swap took effect by having the on-disk `lib` fail-nyi (references an unknown ident) while the override provides a real declaration — a successful compile proves the override won."
    (let [src-dir      (tmp-dir)
          out-dir      (tmp-dir)
          override-dir (tmp-dir)
          _            (write-file! src-dir "lib/util.cxc"
                                    "def Helper:\n    x: PRODUCTION_MISSING_TYPE\n")
          override-f   (write-file! override-dir "override.cxc"
                                    "def Helper:\n    x: Int\n")
          _            (write-file! src-dir "app.cxc"
                                    "from lib import Helper\n\ndef main:\n    -> running\n        then: print(1)\n")
          result       (build/compile-directory-to-file
                        (.getPath src-dir) "prog" (.getPath out-dir)
                        {:source-overrides {"lib" (.getPath override-f)}})]
      (is (zero? (:status result))
          (str "override should let compile succeed even though on-disk lib is broken; stderr: "
               (:stderr result))))))

(deftest f52-l3-source-override-unknown-module-warns
  (testing "F52 L3 — an override targeting a module not discovered under `src-dir` is silently ignored with a stderr warning. Prevents footguns from typoed override keys silently succeeding while producing the on-disk source."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          err     (java.io.StringWriter.)
          _       (write-file! src-dir "app.cxc"
                               "def main:\n    -> running\n        then: print(1)\n")
          result  (binding [*err* err]
                    (build/compile-directory-to-file
                     (.getPath src-dir) "prog" (.getPath out-dir)
                     {:source-overrides {"nonexistent" "/tmp/does-not-matter.cxc"}}))]
      (is (zero? (:status result)) "compile still succeeds despite unused override")
      (is (re-find #"source override for `nonexistent`" (str err))
          "stderr warning fires for the unmatched override")
      (is (re-find #"F52 L3" (str err))
          "diagnostic cites the responsible landing"))))

(deftest end-to-end-q2-bounded-mailbox-sync-block
  (testing "Q2 (2026-08-26) — end-to-end sync `|>` to a `queue: bounded(2)` target. Sender fires three sequential `|>` calls; per spec §1.11 the sync-block discipline blocks the sender until the receiver drains — so all three complete in order, then print(1) runs. Zero output would indicate deadlock (bounded blocking mishandled); output `1` confirms the sem was correctly signaled by each dispatched block on the target's queue, allowing subsequent enqueues to proceed."
    (let [{:keys [exit stdout]} (compile-and-run "bounded-mailbox-sync-block")]
      (is (zero? exit))
      (is (= "1\n" stdout)))))

(deftest end-to-end-q1-or-recovery-typed-arm
  (testing "Q1 (2026-08-26) — end-to-end runtime routing for sync-send `or:` recovery. Target R declares `\\|: n < 3 as MyError(msg: \"boom\")` (class-level invariant). Sender fires three pulses; third pulse violates the invariant (n reaches 3), F48 boxes MyError + re-throws (Q1 change), sync helper captures on sender's frame, arm chain dispatches on isKindOfClass, destructures msg into m, prints `boom`. Pre-Q1 this test would print nothing — F48 swallowed the exception before the sender's arm chain saw it."
    (let [{:keys [exit stdout]} (compile-and-run "or-recovery-typed-arm")]
      (is (zero? exit))
      (is (= "boom\n" stdout)))))

(deftest end-to-end-raise-caught-by-or-arm-2d-iii
  (testing "2d-iii (2026-09-02) — statement-position `raise MyError(msg: \"boom\")` inside a target's `then:` body throws a `_Cxc_R_MyError` boxed reason via `@throw [NSException ... userInfo:@{@\"reason\": box}]` — identical wire shape to `|: cond as MyError(...)` (Track A/4). The transition's L12c end-catch-wrap catches it; the sender's `or MyError(msg: let m):` arm on the sync `|>` send dispatches through the existing Track B/3 `isKindOfClass:` chain, destructures msg into m, and prints `boom`. Verifies the raise mechanism plugs into the existing typed-recovery pipeline with no new runtime machinery."
    (let [{:keys [exit stdout]} (compile-and-run "raise-caught-by-or-arm")]
      (is (zero? exit))
      (is (= "boom\n" stdout)))))

(deftest end-to-end-fs-write-then-read-2d
  (testing "2d (2026-09-02) — `FileSystem` prelude capability, Foundation-backed text I/O. `fs.write_text(path, content)` calls `-[NSString writeToFile:atomically:encoding:error:]` and returns 0/-1; `fs.read_text(path)` calls `+[NSString stringWithContentsOfFile:encoding:error:]` and returns String (nil on failure). Test writes a fixed string to /tmp, reads it back, prints via Console. Verifies the three native intrinsics (`native_fs_write_text`, `native_fs_read_text`, `native_fs_exists`) wire correctly and the FileSystem atom auto-loads from the prelude registry. Cleans /tmp/cxc-fs-test.txt before the run so the write path is exercised even on a re-run."
    (let [test-path "/tmp/cxc-fs-test.txt"]
      (try
        (.delete (java.io.File. test-path))
        (let [{:keys [exit stdout]} (compile-and-run "fs-write-then-read")]
          (is (zero? exit))
          (is (= "hello from cxc\n" stdout))
          (is (= "hello from cxc" (slurp test-path))
              "the file on disk should contain what write_text wrote"))
        (finally (.delete (java.io.File. test-path)))))))

(deftest end-to-end-subprocess-echo-2e
  (testing "2e (2026-09-02) — `Subprocess` prelude capability. `shell.run(cmd)` runs the command via libc `popen(cmd + \" 2>&1\", \"r\")` and returns combined stdout+stderr as a String (nil on popen failure). Test runs `echo hi from subprocess` and pipes the output through Console. Verifies the `native_subprocess_run` intrinsic emits a valid GNU stmt-expr, popen/pclose lifecycle is clean, and the Subprocess atom auto-loads from the prelude registry. MVP shape does NOT expose exit code — that migration waits on a composite-return FFI marshal."
    (let [{:keys [exit stdout]} (compile-and-run "subprocess-echo")]
      (is (zero? exit))
      ;; Double newline: echo emits `hi from subprocess\n` into popen's pipe,
      ;; then Console.write (puts) appends another `\n`. Not a bug — just
      ;; the interaction between echo's default trailing newline and puts.
      (is (= "hi from subprocess\n\n" stdout)))))

(deftest end-to-end-string-concat
  (testing "2026-09-02 — `String + String` binop lowers to `-[NSString stringByAppendingString:]`. `+` on two String operands returns a fresh String (nil-safe via ObjC nil-messaging). Predicate lives in `infer-binop-type` above the arithmetic-widening branch, so numeric `+` is unchanged; the lower-side sits above the eq/arith dispatch. Exercised inline via mini-agent (see below); this test verifies the simplest shape."
    (let [src "def main:\n    use console: Console\n    -> running\n        then: console.write(\"hello\" + \" \" + \"world\")\n    -> end\n"
          out-dir (tmp-dir)
          {:keys [status stderr bin-path]}
          (build/compile-source-to-file src "concat-test" (.getPath out-dir))]
      (is (zero? status) (str "clang failed:\n" stderr))
      (let [{:keys [exit stdout]} (build/run-binary bin-path)]
        (is (zero? exit))
        (is (= "hello world\n" stdout))))))

(deftest end-to-end-string-helpers
  (testing "2026-09-03 — String content-equality + Foundation-backed helpers + Bool.to_string(). `String == String` reroutes through `-isEqualToString:` (with a leading pointer-eq short-circuit for the nil-nil case) so equal-content Strings from different sources compare true; raw C `==` before this landed only worked for pooled literals. Adds `s.length() -> Int`, `s.contains(sub) -> Bool`, `s.starts_with(pre) -> Bool`, `s.ends_with(suf) -> Bool` (all NSString direct methods) and `Bool.to_string() -> String` (literal \"true\"/\"false\") to round out the log-message interpolation surface."
    (let [{:keys [exit stdout]} (compile-and-run "string-helpers")]
      (is (zero? exit))
      (is (clojure.string/includes? stdout "a==b: same"))
      (is (clojure.string/includes? stdout "a==c: diff"))
      (is (clojure.string/includes? stdout "contains world: yes"))
      (is (clojure.string/includes? stdout "starts_with hello: yes"))
      (is (clojure.string/includes? stdout "ends_with world: yes"))
      (is (clojure.string/includes? stdout "length: 11"))
      (is (clojure.string/includes? stdout "true.to_string: true"))
      (is (clojure.string/includes? stdout "false.to_string: false"))
      (is (clojure.string/includes? stdout "to_int_or 42: 42"))
      (is (clojure.string/includes? stdout "to_int_or abc: -1"))
      (is (clojure.string/includes? stdout "to_int_or ws17: 17"))
      (is (clojure.string/includes? stdout "to_int_or 42abc: -1"))
      (is (clojure.string/includes? stdout "upper: HELLO WORLD"))
      (is (clojure.string/includes? stdout "lower: hello world")))))

(deftest end-to-end-console-write-err
  (testing "2026-09-03 — `console.write_err(msg)` writes to stderr with a trailing newline (mirrors puts-to-stdout used by console.write). Verified by running the program with stdout redirected to /dev/null and asserting the message shows up on stderr — a stdout-only capture wouldn't see it. This gives programs a diagnostic channel that won't intermix with pipeable output."
    (let [src     "def main:\n    use console: Console\n    -> running\n        then:\n            console.write(\"to stdout\")\n            let _: Int = console.write_err(\"to stderr\")\n    -> end\n"
          out-dir (tmp-dir)
          {:keys [status stderr bin-path]}
          (build/compile-source-to-file src "write-err-smoke" (.getPath out-dir))]
      (is (zero? status) (str "clang failed:\n" stderr))
      (when (zero? status)
        (let [{:keys [exit out err]} (shell/sh bin-path)]
          (is (zero? exit))
          (is (str/includes? out "to stdout"))
          (is (str/includes? err "to stderr"))
          (is (not (str/includes? out "to stderr")))
          (is (not (str/includes? err "to stdout"))))))))

(deftest end-to-end-json-bool-array
  (testing "2026-09-03 — `json.field_bool_or(json, key, fallback)` reads a strictly-typed JSON Bool (RFC 8259: only literal true/false accepted — numeric 1/0 falls through to the fallback). `json.array_length_at(json, path)` walks a dot-path (supports numeric indices for array walks) and returns the terminal array's count, or -1 on invalid JSON / missing path / non-array terminus. Covers positive-Bool, negative-Bool, null-as-Bool, absent-Bool, numeric-as-Bool, top-level array, nested-array, non-array-terminus, and missing-path cases."
    (let [{:keys [exit stdout]} (compile-and-run "json-bool-array")]
      (is (zero? exit))
      (is (str/includes? stdout "ok: true"))
      (is (str/includes? stdout "cancelled: false"))
      (is (str/includes? stdout "absent: true"))
      (is (str/includes? stdout "numeric_as_bool: false"))
      (is (str/includes? stdout "null_as_bool: true"))
      (is (str/includes? stdout "items_len: 3"))
      (is (str/includes? stdout "nested_list_len: 2"))
      (is (str/includes? stdout "non_array: -1"))
      (is (str/includes? stdout "missing_path: -1")))))

(deftest end-to-end-random-hex
  (testing "2026-09-03 — `random.hex(nybbles)` returns a hex String of exactly `nybbles` characters (odd counts truncated from the last byte). Backed by libc `arc4random_buf` (CSPRNG per Corvid's Random guarantee — see corvid-random-cryptographic memory). Bounds: non-positive → empty; > 4096 clamped to 4096 (to keep the stack byte buffer bounded). Test asserts length invariants across zero / one / even / odd / negative — the sampled value is non-deterministic so we don't assert content."
    (let [{:keys [exit stdout]} (compile-and-run "random-hex")]
      (is (zero? exit))
      (is (str/includes? stdout "len0: 0"))
      (is (str/includes? stdout "len1: 1"))
      (is (str/includes? stdout "len8: 8"))
      (is (str/includes? stdout "len32: 32"))
      (is (str/includes? stdout "len_neg: 0")))))

(deftest end-to-end-env-remove-fs-append
  (testing "2026-09-03 — `env.remove(name)` (libc unsetenv wrapper; verified by round-trip set→get→remove→get returning fallback) + `fs.append_text(path, content)` (fopen `a` mode; verified by appending two lines then reading back). Together these round out Env (get_or / set / remove) and FileSystem (read_text / write_text / append_text / exists / mkdirs / remove). Cleans its own scratch file before + after so residue doesn't skew assertions."
    (shell/sh "rm" "-f" "/tmp/cxc-append-example.log")
    (let [{:keys [exit stdout]} (compile-and-run "env-remove-fs-append")]
      (is (zero? exit))
      (is (str/includes? stdout "before_rm: temp"))
      (is (str/includes? stdout "after_rm: GONE"))
      (is (str/includes? stdout "append_rc1: 0"))
      (is (str/includes? stdout "append_rc2: 0"))
      (is (str/includes? stdout "read_back: line one\nline two")))))

(deftest end-to-end-fs-tmp-path
  (testing "2026-09-03 — `fs.tmp_path(prefix)` returns a unique scratch path under NSTemporaryDirectory. Two calls with the same prefix return different paths (CSPRNG-strong 16-hex-char suffix via arc4random_buf). Empty prefix omits the leading dash. Does NOT create the file — writing is the caller's job (verified by writing/reading/removing in one flow)."
    (let [{:keys [exit stdout]} (compile-and-run "fs-tmp-path")]
      (is (zero? exit))
      (is (str/includes? stdout "a_ne_b: false"))
      (is (str/includes? stdout "a_has_agent: true"))
      (is (str/includes? stdout "c_no_dash: false"))
      (is (str/includes? stdout "ex_a: 1"))
      (is (str/includes? stdout "sz_a: 10")))))

(deftest end-to-end-fs-copy-rename-size
  (testing "2026-09-03 — `fs.copy(from, to)`, `fs.rename(from, to)`, `fs.size(path)` via NSFileManager. Verified end-to-end by writing a 10-byte file, copying it (size preserved), renaming the copy (source disappears, target appears with same size), and checking `size` on missing path (→ -1) and on a directory (→ -1; Foundation only returns file size for regular files). Test pre-cleans + post-cleans its scratch paths."
    (doseq [p ["/tmp/cxc-copy-src.txt"
               "/tmp/cxc-copy-dst.txt"
               "/tmp/cxc-copy-renamed.txt"]]
      (shell/sh "rm" "-f" p))
    (let [{:keys [exit stdout]} (compile-and-run "fs-copy-rename-size")]
      (is (zero? exit))
      (is (str/includes? stdout "size_src: 10"))
      (is (str/includes? stdout "copy_rc: 0"))
      (is (str/includes? stdout "size_dst: 10"))
      (is (str/includes? stdout "rename_rc: 0"))
      (is (str/includes? stdout "dst_gone: 0"))
      (is (str/includes? stdout "renamed_exists: 1"))
      (is (str/includes? stdout "size_renamed: 10"))
      (is (str/includes? stdout "missing_size: -1"))
      (is (str/includes? stdout "dir_size: -1")))))

(deftest end-to-end-fs-mkdirs-remove
  (testing "2026-09-03 — `FileSystem.mkdirs(path)` (mkdir -p: NSFileManager createDirectoryAtPath withIntermediateDirectories:YES) and `FileSystem.remove(path)` (recursive delete). Round-trip: create a nested dir tree, write a file inside, remove the whole tree, verify absence afterwards. Test cleans its own scratch path (`/tmp/cxc-mkdirs-example`) before running so residue from earlier iterations doesn't spuriously fail the mkdirs return code."
    ;; Best-effort pre-clean so a residual dir from a previous failed
    ;; run doesn't skew the assertion — the program itself is what's
    ;; under test, not the harness.
    (shell/sh "rm" "-rf" "/tmp/cxc-mkdirs-example")
    (let [{:keys [exit stdout]} (compile-and-run "fs-mkdirs-remove")]
      (is (zero? exit))
      (is (str/includes? stdout "mk_rc: 0"))
      (is (str/includes? stdout "ex_after_mk: 1"))
      (is (str/includes? stdout "wr_rc: 0"))
      (is (str/includes? stdout "rm_rc: 0"))
      (is (str/includes? stdout "ex_after_rm: 0")))))

(deftest end-to-end-env-set-time-since-sleep
  (testing "2026-09-03 — three landings verified together. `env.set(name, value)` wraps libc `setenv(...,overwrite=1)` returning 0/-1 (verified by round-tripping through `env.get_or`). `time.since(t)` returns a Duration for wall-clock elapsed since an Instant (uses Int subtraction via `native_millis_now`). `time.sleep_ms(ms)` wraps libc `usleep(ms*1000)` — verified by measuring the elapsed time after a 50ms sleep (lower bound 40ms accommodates coarse scheduler quanta; upper bound 500ms guards against runaway waits)."
    (let [{:keys [exit stdout]} (compile-and-run "env-set-time-since-sleep")]
      (is (zero? exit))
      (is (str/includes? stdout "set_rc: 0"))
      (is (str/includes? stdout "env_roundtrip: hello-from-cxc"))
      (is (str/includes? stdout "slept_ms_lower: true"))
      (is (str/includes? stdout "slept_ms_upper: true")))))

(deftest end-to-end-console-readln
  (testing "2026-09-03 — `console.readln()` reads one line from stdin, strips the trailing newline, returns nil on EOF. Backed by libc `fgets` on a 4 KiB stack buffer, wrapped in a stmt-expr that yields an NSString*. Round-trip: pipe a known line into stdin, assert the program echoes it back verbatim through `console.write`."
    (let [src     (example "console-readln")
          out-dir (tmp-dir)
          {:keys [status stderr bin-path]}
          (build/compile-source-to-file src "console-readln" (.getPath out-dir))]
      (is (zero? status) (str "clang failed:\n" stderr))
      (when (zero? status)
        (let [{:keys [exit out]} (shell/sh bin-path :in "hello from stdin\n")]
          (is (zero? exit))
          (is (str/includes? out "got: hello from stdin")))))))

(deftest end-to-end-time-iso
  (testing "2026-09-03 — `time.now_iso()` returns a human-readable ISO 8601 UTC timestamp with fractional-second precision (`YYYY-MM-DDTHH:MM:SS.fffZ`, length 24). Uses `NSISO8601DateFormatter` cached under dispatch_once so repeated calls don't reallocate. Shape assertions only (value depends on wall clock)."
    (let [{:keys [exit stdout]} (compile-and-run "time-iso")]
      (is (zero? exit))
      (is (str/includes? stdout "len: 24"))
      (is (str/includes? stdout "has_T: true"))
      (is (str/includes? stdout "has_Z: true"))
      (is (str/includes? stdout "starts_20: true")))))

(deftest end-to-end-time-wall-clock
  (testing "2026-09-03 — `Time` wall-clock capability. `time.now()` returns an `Instant` whose `.millis` field is Unix epoch milliseconds. Backed by `[[NSDate date] timeIntervalSince1970] * 1000` (int64_t; the prior `int` cast in `native_millis_now` silently truncated for any modern timestamp). Verified by asserting the returned millis exceeds 1.7 trillion (Nov 2023 boundary) — proves both non-zero and beyond-int32 range. Kept distinct from `Clock` (periodic-tick stream source); `Time` is a one-shot wall-clock read."
    (let [{:keys [exit stdout]} (compile-and-run "time-wall-clock")]
      (is (zero? exit))
      (is (clojure.string/includes? stdout "wall_positive: true"))
      (is (clojure.string/includes? stdout "wall_recent: true")))))

(deftest hello-agent-mini-compiles-6
  (testing "6 (2026-09-03) — MVP hello-agent shape. Composes Args + Env + Http + Json + Console + String concat into a direct Claude Messages API POST. Assembles headers as a JSON String (x-api-key from env, anthropic-version, content-type) and body as a JSON String (model from env, single user message from argv[1] or default) using Json.encode_str for every String-typed value so escaping is correct. Sends via http.post, reads top-level `stop_reason` + `type` fields from the response with Json.field_string, prints everything via Console. This is the maximum agent-shape currently expressible without closures, `List of T`, variant destructuring, or or-recovery on non-emit expressions. Manual network verification only (needs ANTHROPIC_API_KEY); CI-side asserts the compile path clears cleanly."
    (let [out-dir (tmp-dir)
          {:keys [status stderr]}
          (build/compile-source-to-file (slurp "examples/hello-agent-mini.cxc")
                                         "hello-agent-mini" (.getPath out-dir))]
      (is (zero? status) (str "clang failed:\n" stderr)))))

(deftest logged-fetch-compiles-cleanly
  (testing "2026-09-03 — six-capability composition (Http + Time + Random + Json + FileSystem + Console). Fetches a URL via `http.get_envelope`, brackets the call with `time.now`/`time.since` for latency, formats a single log line with a `random.hex(16)` correlation ID + epoch timestamp + status + body byte count, appends it via `fs.append_text`, reads it back, cleans up. Compile-only (would hit the network at runtime); manual verification path documented in the example. Validates that the newer prelude capabilities compose cleanly at the type / lowering / clang level."
    (let [src     (example "logged-fetch")
          out-dir (tmp-dir)
          {:keys [status stderr]}
          (build/compile-source-to-file src "logged-fetch" (.getPath out-dir))]
      (is (zero? status) (str "clang failed:\n" stderr)))))

(deftest http-compiles-cleanly-5
  (testing "5 (2026-09-03) — `Http` prelude capability compiles cleanly. Does NOT hit the network (would flake in CI); this only asserts a program that uses `use http: Http` + `http.get` + `http.post` clears parse, lower, and clang. Network round-trip verification is manual (examples/http-example-com.cxc)."
    (let [src "def main:\n    use http: Http\n    use console: Console\n    -> running\n        then:\n            let body: String = http.get(\"https://example.com\", \"{}\")\n            let post: String = http.post(\"https://example.com\", \"{}\", \"{}\")\n            console.write(\"compiled ok\")\n    -> end\n"
          out-dir (tmp-dir)
          {:keys [status stderr]}
          (build/compile-source-to-file src "http-smoke" (.getPath out-dir))]
      (is (zero? status) (str "clang failed:\n" stderr)))))

(deftest end-to-end-json-roundtrip
  (testing "4 (2026-09-03) — `Json` prelude capability. `json.encode_str(s)` wraps s in a JSON array via NSJSONSerialization, then strips the `[...]` to leave the quoted-and-escaped String literal. `json.field_string(json, key)` parses the JSON as a top-level dict and returns the String at key (nil on invalid JSON / missing key / non-String value). Round-trip test: encode a bare name into a JSON string literal, embed it in a hand-rolled payload with an escaped quote in another field, extract both fields back — verifies escape output and unescape roundtrip through NSJSONSerialization."
    (let [{:keys [exit stdout]} (compile-and-run "json-roundtrip")]
      (is (zero? exit))
      (is (clojure.string/includes? stdout "encoded: \"cxc\""))
      (is (clojure.string/includes? stdout "tool: cxc"))
      (is (clojure.string/includes? stdout "detail: quotes \" and newlines")))))

(deftest end-to-end-mini-agent
  (testing "2026-09-03 — cross-capability composition proof. `mini-agent.cxc` uses Args + FileSystem + Subprocess + Console + Env + Int.to_string + String concat in a single `then:` block: reads argv[1] via `args.get_or(1, fallback)`, otherwise consults `MINI_AGENT_CMD` env var (default `date`); runs the resolved shell command via popen, writes captured output to a file via NSString `writeToFile:`, prints a summary (including argc + write status via `Int.to_string()`). Verifies the six MVP capabilities compose without conflicting on runtime, prelude autoloading, or emit shape. run-binary calls shell/sh with no args, so argc=1 (just the program name) and the env-var fallback wins."
    (let [{:keys [exit stdout]} (compile-and-run "mini-agent")]
      (is (zero? exit))
      (is (clojure.string/includes? stdout "argc: 1"))
      (is (clojure.string/includes? stdout "cmd: date"))
      (is (clojure.string/includes? stdout "log: "))
      (is (clojure.string/includes? stdout "write status: 0"))
      (is (clojure.string/includes? stdout "output:")))))

(deftest f52-l3-source-override-defaults-to-none-when-opts-omit
  (testing "F52 L3 — opts without :source-overrides is a no-op (default `{}`); on-disk sources are used as before. Regression guard for the 3-arity + 4-arity dispatch: existing callers pass no opts (line 613 pattern) or opts without the key (line 615 pattern)."
    (let [src-dir (tmp-dir)
          out-dir (tmp-dir)
          _       (write-file! src-dir "app.cxc"
                               "def main:\n    -> running\n        then: print(9)\n")
          result  (build/compile-directory-to-file
                   (.getPath src-dir) "prog" (.getPath out-dir))
          {:keys [exit stdout]} (build/run-binary (:bin-path result))]
      (is (zero? (:status result)))
      (is (zero? exit))
      (is (= "9\n" stdout) "on-disk source used when no override provided"))))

(deftest f51-l3b-b-ii-5c-single-file-threads-resolve-references
  (testing "F51 L3b(b.ii)-5c — `compile-source-to-file` runs `resolve/resolve-references` on the parsed user AST before feeding lower. Prevents regression to the pre-71c46c9 shim (reparse of concatenated prelude + user source, which stripped `:resolves-to` metadata). Spy on the resolver call — if a future refactor drops it, this test flags the drop before behavior diverges silently."
    (let [call-count  (atom 0)
          orig-fn     @#'resolve/resolve-references
          spy-fn      (fn [& args]
                        (swap! call-count inc)
                        (apply orig-fn args))
          out-dir     (tmp-dir)]
      (with-redefs [resolve/resolve-references spy-fn]
        (build/compile-source-to-file
         "def main:\n    -> running\n        then: print(1)\n"
         "resolve-thread-probe" (.getPath out-dir)))
      (is (>= @call-count 1)
          "compile-source-to-file must invoke resolve-references (single-file path enriches user AST before feeding lower)"))))
