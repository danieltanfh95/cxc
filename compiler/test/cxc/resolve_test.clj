(ns cxc.resolve-test
  (:require [clojure.test :refer [deftest is testing]]
            [cxc.parse :as parse]
            [cxc.prelude :as prelude]
            [cxc.resolve :as resolve]))

(defn- table-from
  "Parse `src` (user source), prepend prelude, run the same rename
   postwalk `lower` sees, and build the symbol table over it. Mirrors
   what `cxc.lower/lower` does internally so table shape matches
   what downstream landings will consume."
  [user-src]
  (let [combined  (parse/parse (str prelude/source user-src))
        renamed   (prelude/rewrite-prelude-names combined)]
    (resolve/build-symbol-table renamed)))

(defn- table-from-with-module
  "F51 L1 — like `table-from` but with a synthetic `module <mod>`
   header prepended to the user source, mirroring what
   `build/compile-directory-to-file` injects per file. Verifies
   `build-symbol-table` walks `:module` nodes statefully and
   attributes downstream user decls to that module (rather than
   falling back to `:user`)."
  [module-nm user-src]
  (let [tagged   (str "module " module-nm "\n\n" user-src)
        ;; F73 (2026-08-20) — user `module` headers rejected at parse
        ;; time; the resolve test mimics the multi-file build's synthetic
        ;; injection and uses the same internal entry point the build does.
        combined (parse/parse-with-build-modules (str prelude/source tagged))
        renamed  (prelude/rewrite-prelude-names combined)]
    (resolve/build-symbol-table renamed)))

(deftest table-carries-prelude-provenance
  (testing "F51 — each prelude declaration is attributed to its owning std.* module"
    (let [table (table-from "def main:\n    -> running\n        then: print(\"hi\")\n")]
      ;; Post-rename, prelude Duration is _Cxc_Duration in the AST;
      ;; provenance lookup handles both forms.
      (is (= :std.time (get-in table ["_Cxc_Duration" :module])))
      (is (= :std.time (get-in table ["_Cxc_Instant"  :module])))
      (is (= :std.geom (get-in table ["_Cxc_Position" :module])))
      (is (= :std.input (get-in table ["_Cxc_Key"     :module])))
      (is (= :std.input (get-in table ["_Cxc_TestKeyboardInput" :module])))
      (is (= :std.io   (get-in table ["_Cxc_Console"  :module])))
      (is (= :std.clock (get-in table ["_Cxc_Clock"   :module])))
      (is (= :std.random (get-in table ["_Cxc_Random" :module]))))))

(deftest table-carries-user-provenance
  (testing "F51 — user declarations get :user provenance"
    (let [table (table-from "def V:\n    x: Int\n\ndef main:\n    -> running\n")]
      (is (= :user (get-in table ["V" :module])))
      (is (= :user (get-in table ["main" :module]))))))

(deftest table-tags-kind
  (testing "F51 — each entry's :kind matches its declaration kind"
    (let [table (table-from "def V:\n    x: Int\n\ndef E:\n    case a\n    case b\n\ndef main:\n    -> running\n")]
      (is (= :record   (get-in table ["V"    :kind])))
      (is (= :enum     (get-in table ["E"    :kind])))
      (is (= :def-atom (get-in table ["main" :kind]))))))

(deftest table-carries-record-members
  (testing "F51 — records expose their fields + observables in :members. F67 L4: methods retire; observables carry the callable surface. `:methods` mirrors `:observables` for query-shape parity."
    (let [table (table-from "def V:\n    x: Int\n    y: Int\n\n    sum() :< x + y\n\ndef main:\n    -> running\n")
          v     (get table "V")
          fs    (get-in v [:members :fields])
          ms    (get-in v [:members :methods])]
      (is (= ["x" "y"] (mapv :name fs)))
      (is (= ["sum"]   (mapv :name ms))))))

(deftest table-carries-atom-members
  (testing "F51 — atoms expose data-fields + transitions + observables"
    (let [table (table-from "def Counter:\n    n: Int = 0\n\n    tally :< n\n\n    -> running\n\n    running <: bump -> self\n        then: n => n + 1\n\ndef main:\n    c: Counter = Counter()\n\n    -> running\n")
          counter (get table "Counter")]
      (is (= ["n"]           (mapv :name (get-in counter [:members :data-fields]))))
      (is (>= (count (get-in counter [:members :transitions])) 2)
          "Counter has at least two transitions: -> running and running <: bump"))))

(deftest table-carries-enum-cases
  (testing "F51 — enums expose their case list"
    (let [table (table-from "def Color:\n    case red\n    case green\n    case blue\n\ndef main:\n    -> running\n")]
      (is (= ["red" "green" "blue"]
             (get-in table ["Color" :members :cases]))))))

(deftest lookup-returns-entry-or-nil
  (testing "F51 — `lookup` fetches by name; nil for unknown"
    (let [table (table-from "def V:\n    x: Int\n\ndef main:\n    -> running\n")
          ctx   {:symbol-table table}]
      (is (some? (resolve/lookup ctx "V")))
      (is (nil?  (resolve/lookup ctx "Nonexistent"))))))

(deftest f38-record-variant-auto-detected
  (testing "F38 — a record with no callables is :struct; a record with any observable is :class. Trigger is structural — no user syntax."
    (let [table (table-from "def P:\n    x: Int\n\ndef V:\n    n: Int\n\n    bumped() :< n + 1\n\ndef main:\n    -> running\n")]
      (is (= :struct (get-in table ["P" :variant]))
          "P has no observables → :struct variant")
      (is (= :class  (get-in table ["V" :variant]))
          "V has one observable → :class variant"))))

(deftest f38-prelude-records-with-methods-are-class-variant
  (testing "F38 — prelude Duration/Instant/Position all carry methods → all classified :class"
    (let [table (table-from "def main:\n    -> running\n")]
      (is (= :class (get-in table ["_Cxc_Duration" :variant])))
      (is (= :class (get-in table ["_Cxc_Instant"  :variant])))
      (is (= :class (get-in table ["_Cxc_Position" :variant]))))))

(deftest lookup-member-drills-into-declaration
  (testing "F51 — `lookup-member` finds a member by kind + name inside a decl. F67 L4: `:methods` mirrors `:observables` for the parametric-reader surface."
    (let [table (table-from "def V:\n    x: Int\n\n    bump() :< x\n\ndef main:\n    -> running\n")
          ctx   {:symbol-table table}]
      (is (= "bump" (:name (resolve/lookup-member ctx "V" :methods "bump"))))
      (is (= "x"    (:name (resolve/lookup-member ctx "V" :fields  "x"))))
      (is (nil?     (resolve/lookup-member ctx "V" :methods "nope"))))))

;; ---------------------------------------------------------------------------
;; F51 L1 — module header tags user decls with their file's derived module.
;; The build layer synthesizes `module <derived>` per file; these tests
;; exercise the symbol-table side of that shape directly.
;; ---------------------------------------------------------------------------

(deftest f51-l1-module-header-attributes-following-decls
  (testing "F51 L1 — a `module foo` header attributes every subsequent user decl to `:foo`. Prelude decls keep their `:std.*` attribution regardless."
    (let [table (table-from-with-module "foo" "def V:\n    x: Int\n\ndef main:\n    -> running\n")]
      (is (= :foo   (get-in table ["V"    :module])))
      (is (= :foo   (get-in table ["main" :module])))
      (is (= :std.time (get-in table ["_Cxc_Duration" :module]))
          "prelude decls unaffected by user module header"))))

(deftest f51-l1-no-module-header-defaults-user-provenance
  (testing "F51 L1 — a source with no `module` header (single-file compile before build injects the synthetic `main` header) keeps the legacy `:user` provenance for backward compatibility."
    (let [table (table-from "def V:\n    x: Int\n\ndef main:\n    -> running\n")]
      (is (= :user (get-in table ["V"    :module])))
      (is (= :user (get-in table ["main" :module]))))))

(deftest f51-l1-module-provenance-flips-mid-stream
  (testing "F51 L1 — two `module` headers in one node stream (as multi-file compile produces after concatenation) attribute each region to its declared module."
    (let [table (table-from-with-module "foo"
                  "def V:\n    x: Int\n\nmodule bar\n\ndef W:\n    y: Int\n\ndef main:\n    -> running\n")]
      (is (= :foo (get-in table ["V"    :module])))
      (is (= :bar (get-in table ["W"    :module])))
      (is (= :bar (get-in table ["main" :module]))))))

;; ---------------------------------------------------------------------------
;; F51 L3b (a) — module scope index + unscoped type-ref discovery. The
;; walker's output feeds the WARN pass in build/compile-directory-to-file
;; so users can migrate before L3b (b) retires the flat-namespace shim.
;; ---------------------------------------------------------------------------

(defn- parse-file
  "Test helper: parse a single file's source under the multi-file build's
   entrypoint (accepts synthetic `module` headers). Returns a
   `files-with-nodes` entry shaped like `build/compile-directory-to-file`
   produces after topsort."
  [module src]
  {:module module
   :file   (java.io.File. (str (clojure.core/name module) ".cxc"))
   :nodes  (parse/parse-with-build-modules
             (str "module " (clojure.core/name module) "\n\n" src))})

(deftest f51-l3b-scope-index-includes-own-decls-imports-and-prelude
  (testing "F51 L3b (a) — a module's scope-set is own decls ∪ imports ∪ prelude. `import X as Y` binds `Y` (not `X`); `from X import a as b` binds `b`."
    (let [files [(parse-file :geom "def Point:\n    x: Int\n    y: Int\n")
                 (parse-file :viz  "from geom import Point\nimport std.time as T\n\ndef main:\n    -> running\n")]
          idx   (resolve/build-module-scope-index files)]
      (is (contains? (:geom idx) "Point")
          "own module's decl in scope")
      (is (contains? (:viz idx) "Point")
          "imported name in scope")
      (is (contains? (:viz idx) "T")
          "aliased import binds the alias, not the module name")
      (is (not (contains? (:viz idx) "std.time"))
          "un-aliased module name isn't in scope when :as is set")
      (is (contains? (:viz idx) "Duration")
          "prelude names always in scope")
      (is (contains? (:geom idx) "Duration")
          "prelude visibility isn't module-specific"))))

(deftest f51-l3b-find-unscoped-flags-cross-module-refs-without-import
  (testing "F51 L3b (a) — a type-ref to a name declared in another user module but not imported is unscoped. It compiles today via the flat-namespace shim; L3b (b) will reject it."
    (let [files   [(parse-file :geom "def Point:\n    x: Int\n")
                   (parse-file :viz  "def Scene:\n    origin: Point\n")]
          unscoped (resolve/find-unscoped-type-refs files)]
      (is (some (fn [{:keys [module name]}]
                  (and (= :viz module) (= "Point" name)))
                unscoped)
          "`Point` in :viz isn't declared, imported, or in prelude"))))

(deftest f51-l3b-find-unscoped-silent-on-same-module-refs
  (testing "F51 L3b (a) — a type-ref that resolves inside its own module (declared in the same or a sibling file of the same module) isn't flagged."
    (let [files   [(parse-file :geom "def Point:\n    x: Int\n")
                   (parse-file :geom "def Scene:\n    origin: Point\n")]
          unscoped (resolve/find-unscoped-type-refs files)]
      (is (not (some #(= "Point" (:name %)) unscoped))
          "same-module refs are silent — the scope-set aggregates decls across every file in the module"))))

(deftest f51-l3b-find-unscoped-silent-on-imported-and-prelude-refs
  (testing "F51 L3b (a) — explicit imports and prelude names both silence the walker."
    (let [files   [(parse-file :geom "def Point:\n    x: Int\n")
                   (parse-file :viz  "from geom import Point\n\ndef Scene:\n    origin: Point\n    elapsed: Duration\n")]
          unscoped (resolve/find-unscoped-type-refs files)]
      (is (not (some #(= "Point" (:name %)) unscoped))
          "explicitly imported name silences the WARN")
      (is (not (some #(= "Duration" (:name %)) unscoped))
          "prelude name silences the WARN"))))

(deftest f51-l3b-find-unscoped-flags-cross-module-ctor-calls
  (testing "F51 L3b (a) — capitalized bare :ident at a :call :callee position (ctor call — `Foo(x: 1)`) is a module-scope reference. Cross-module ctor calls without an explicit import are flagged."
    (let [files    [(parse-file :geom "def Point:\n    x: Int\n")
                    (parse-file :viz  "def main:\n    -> running\n        then: print(Point(x: 7).x)\n")]
          unscoped (resolve/find-unscoped-type-refs files)]
      (is (some (fn [{:keys [module name]}]
                  (and (= :viz module) (= "Point" name)))
                unscoped)
          "`Point(...)` ctor call in :viz isn't declared, imported, or in prelude"))))

(deftest f51-l3b-find-unscoped-silent-on-lowercase-call-targets
  (testing "F51 L3b (a) — lowercase bare :ident at :call :callee (e.g. `print(...)`) is a function call, not a ctor. Not flagged as a module-scope reference — spec convention reserves TitleCase for types."
    (let [files    [(parse-file :geom "def main:\n    -> running\n        then: print(1)\n")]
          unscoped (resolve/find-unscoped-type-refs files)]
      (is (empty? unscoped)
          "`print(1)` shouldn't fire the walker"))))
