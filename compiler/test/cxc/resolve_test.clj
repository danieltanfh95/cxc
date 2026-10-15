(ns cxc.resolve-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk]
            [cxc.parse :as parse]
            [cxc.prelude :as prelude]
            [cxc.resolve :as resolve]))

(defn- table-from
  "Parse `src` (user source), prepend prelude, build the symbol table.
   F56 L2+L3 (2026-08-27): parse-time postwalk retired; AST + table
   key by user-visible names."
  [user-src]
  (let [combined (parse/parse (str prelude/source user-src))]
    (resolve/build-symbol-table combined)))

(defn- table-from-with-module
  "F51 L1 — like `table-from` but with a synthetic `module <mod>`
   header prepended, mirroring what `build/compile-directory-to-file`
   injects per file."
  [module-nm user-src]
  (let [tagged   (str "module " module-nm "\n\n" user-src)
        combined (parse/parse-with-build-modules (str prelude/source tagged))]
    (resolve/build-symbol-table combined)))

(deftest table-carries-prelude-provenance
  (testing "F51 — each prelude declaration is attributed to its owning std.* module"
    (let [table (table-from "def main:\n    -> running\n        then: print(\"hi\")\n")]
      ;; Post-rename, prelude Duration is _Cxc_Duration in the AST;
      ;; provenance lookup handles both forms.
      (is (= :std.time (get-in table ["Duration" :module])))
      (is (= :std.time (get-in table ["Instant"  :module])))
      (is (= :std.geom (get-in table ["Position" :module])))
      (is (= :std.input (get-in table ["Key"     :module])))
      (is (= :std.input (get-in table ["TestKeyboardInput" :module])))
      (is (= :std.console (get-in table ["Console"  :module])))
      (is (= :std.clock (get-in table ["Clock"   :module])))
      (is (= :std.random (get-in table ["Random" :module]))))))

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
    (let [table (table-from "def V:\n    x: Int\n    y: Int\n\n    sum() :- x + y\n\ndef main:\n    -> running\n")
          v     (get table "V")
          fs    (get-in v [:members :fields])
          ms    (get-in v [:members :methods])]
      (is (= ["x" "y"] (mapv :name fs)))
      (is (= ["sum"]   (mapv :name ms))))))

(deftest table-carries-atom-members
  (testing "F51 — atoms expose data-fields + transitions + observables"
    (let [table (table-from "def Counter:\n    n: Int = 0\n\n    tally :- n\n\n    -> running\n\n    running <: bump -> self\n        then: n => n + 1\n\ndef main:\n    c: Counter = Counter()\n\n    -> running\n")
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
    (let [table (table-from "def P:\n    x: Int\n\ndef V:\n    n: Int\n\n    bumped() :- n + 1\n\ndef main:\n    -> running\n")]
      (is (= :struct (get-in table ["P" :variant]))
          "P has no observables → :struct variant")
      (is (= :class  (get-in table ["V" :variant]))
          "V has one observable → :class variant"))))

(deftest f38-prelude-records-with-methods-are-class-variant
  (testing "F38 — prelude Duration/Instant/Position all carry methods → all classified :class"
    (let [table (table-from "def main:\n    -> running\n")]
      (is (= :class (get-in table ["Duration" :variant])))
      (is (= :class (get-in table ["Instant"  :variant])))
      (is (= :class (get-in table ["Position" :variant]))))))

(deftest lookup-member-drills-into-declaration
  (testing "F51 — `lookup-member` finds a member by kind + name inside a decl. F67 L4: `:methods` mirrors `:observables` for the parametric-reader surface."
    (let [table (table-from "def V:\n    x: Int\n\n    bump() :- x\n\ndef main:\n    -> running\n")
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
      (is (= :std.time (get-in table ["Duration" :module]))
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

(deftest f51-l3b-find-unscoped-flags-cross-module-extend-target
  (testing "F51 L3b (a) — `extend Foo:` targets a top-level type. Extension of a type declared in another user module without an explicit import is a module-scope concern; the walker flags the extend target."
    (let [files    [(parse-file :geom "def Point:\n    x: Int\n")
                    (parse-file :viz  "extend Point:\n    bumped :- 1\n\ndef main:\n    -> running\n")]
          unscoped (resolve/find-unscoped-type-refs files)]
      (is (some (fn [{:keys [module name]}]
                  (and (= :viz module) (= "Point" name)))
                unscoped)
          "`extend Point:` in :viz should flag Point as an unscoped extend target"))))

(deftest f51-l3b-find-unscoped-flags-cross-module-dot-target
  (testing "F51 L3b (a) — a `:dot` whose target is a capitalized bare :ident refers to a top-level type (receive-glyph source, static access, etc.). Cross-module use without an import surfaces under the walker."
    (let [files    [(parse-file :sources "def SomeSource:\n    msg :- 1\n")
                    (parse-file :sink    "def Sink:\n    running <: SomeSource.msg -> next\n")]
          unscoped (resolve/find-unscoped-type-refs files)]
      (is (some (fn [{:keys [module name]}]
                  (and (= :sink module) (= "SomeSource" name)))
                unscoped)
          "`SomeSource.msg` receive-glyph source in :sink should flag SomeSource"))))

(deftest f51-l3b-find-unscoped-silent-on-lowercase-dot-targets
  (testing "F51 L3b (a) — `:dot` whose target is a lowercase :ident (e.g. `x.field`, `local.name`) is a local access. Not flagged — L3b's resolution there is scope-local, not module-scope."
    (let [files    [(parse-file :geom (str "def Point:\n"
                                           "    x: Int\n\n"
                                           "def main:\n"
                                           "    -> running\n"
                                           "        then:\n"
                                           "            let p: Point = Point(x: 1)\n"
                                           "            print(p.x)\n"))]
          unscoped (resolve/find-unscoped-type-refs files)]
      (is (empty? unscoped)
          "`p.x` (lowercase local dot) shouldn't fire the walker"))))

(deftest f51-l3b-find-unscoped-flags-cross-module-parametric-outer
  (testing "F51 L3b (a) — `MyContainer of Int` at a field type position has :outer held as a plain string (not a :type-ref). Cross-module use of a user-declared parametric type without an import surfaces under the walker. Builtin containers (List / Map / Set / Array / Optional) stay silent — they're in prelude-name-set."
    (let [files    [(parse-file :containers "def MyBox:\n    x: Int\n")
                    (parse-file :main       "def R:\n    xs: MyBox of Int\n")]
          unscoped (resolve/find-unscoped-type-refs files)]
      (is (some (fn [{:keys [module name]}]
                  (and (= :main module) (= "MyBox" name)))
                unscoped)
          "`MyBox of Int` in :main should flag MyBox as an unscoped parametric outer"))))

(deftest f51-l3b-find-unscoped-silent-on-builtin-parametric-outer
  (testing "F51 L3b (a) — `List of Int`, `Map of (K, V)`, `Set of T` — builtin containers stay silent because their :outer names live in `builtin-type-names`."
    (let [files    [(parse-file :main "def R:\n    xs: List of Int\n    m: Map of (String, Int)\n")]
          unscoped (resolve/find-unscoped-type-refs files)]
      (is (empty? unscoped)
          "builtin container outers shouldn't fire the walker"))))

;; ---------------------------------------------------------------------------
;; F51 L3b (b.i) — declaring-module hint on unscoped-ref entries. Per D2
;; ratification (decisions-frozen §10) the shadow-mode migration needs
;; users to see WHERE an unscoped name is actually declared in the
;; program so they can add the right import. `find-unscoped-type-refs`
;; now enriches each entry with `:declared-in` — a sorted vec of module
;; keywords where the name is defined.
;; ---------------------------------------------------------------------------

(deftest f51-l3b-bi-declared-in-points-at-single-source-module
  (testing "F51 L3b (b.i) — when an unscoped name is declared in exactly one other user module, `:declared-in` names it. Users get a concrete `from <module> import <name>` to write."
    (let [files    [(parse-file :geom "def Point:\n    x: Int\n")
                    (parse-file :viz  "def Scene:\n    origin: Point\n")]
          entries  (resolve/find-unscoped-type-refs files)
          point    (first (filter #(= "Point" (:name %)) entries))]
      (is (= [:geom] (:declared-in point))
          "Point is declared in :geom; the WARN hint should point at that module"))))

(deftest f51-l3b-bi-declared-in-empty-when-name-is-undefined
  (testing "F51 L3b (b.i) — when an unscoped name isn't declared anywhere in the program, `:declared-in` is empty. Users see \"undefined\" rather than a bogus \"add import\" suggestion."
    (let [files    [(parse-file :main "def Scene:\n    origin: Nonexistent\n")]
          entries  (resolve/find-unscoped-type-refs files)
          entry    (first (filter #(= "Nonexistent" (:name %)) entries))]
      (is (= [] (:declared-in entry))
          "Nonexistent is defined in no module; declared-in is empty"))))

(deftest f51-l3b-bi-declared-in-lists-all-modules-on-ambiguous-name
  (testing "F51 L3b (b.i) — when a name is declared in multiple user modules, `:declared-in` lists all of them (sorted). Users must qualify or alias to disambiguate."
    (let [files    [(parse-file :a       "def Config:\n    x: Int\n")
                    (parse-file :b       "def Config:\n    y: Int\n")
                    (parse-file :consumer "def R:\n    cfg: Config\n")]
          entries  (resolve/find-unscoped-type-refs files)
          entry    (first (filter #(and (= :consumer (:module %))
                                        (= "Config" (:name %)))
                                  entries))]
      (is (= [:a :b] (:declared-in entry))
          "Config is declared in both :a and :b; declared-in lists both sorted"))))

;; ---------------------------------------------------------------------------
;; F51 L3b sub-landing (b.ii) — reference resolution enrichment
;; ---------------------------------------------------------------------------

(defn- first-type-ref-with-name
  "Walk enriched nodes and return the first :type-ref whose :name matches."
  [nodes nm]
  (let [found (atom nil)]
    (clojure.walk/postwalk
     (fn [x]
       (when (and (nil? @found)
                  (map? x)
                  (= :type-ref (:node x))
                  (= nm (:name x)))
         (reset! found x))
       x)
     nodes)
    @found))

(defn- first-callee-with-name
  "Walk enriched nodes and return the first :call whose :callee is a bare :ident with the given name."
  [nodes nm]
  (let [found (atom nil)]
    (clojure.walk/postwalk
     (fn [x]
       (when (and (nil? @found)
                  (map? x)
                  (= :call (:node x))
                  (let [c (:callee x)]
                    (and (map? c) (= :ident (:node c)) (= nm (:name c)))))
         (reset! found (:callee x)))
       x)
     nodes)
    @found))

(deftest f51-l3b-bii-resolves-own-module-type-ref
  (testing "F51 L3b (b.ii) — a type-ref to a name declared in the same module resolves to that module. Pure metadata enrichment; the original :name is unchanged."
    (let [files    [(parse-file :geom "def Point:\n    x: Int\n")
                    (parse-file :geom "def Scene:\n    origin: Point\n")]
          enriched (resolve/resolve-references files)
          scene    (second enriched)
          point-ref (first-type-ref-with-name (:nodes scene) "Point")]
      (is (some? point-ref) "found the :type-ref for Point")
      (is (= :geom (:resolves-to point-ref))
          "Point resolves to :geom (own module)"))))

(deftest f51-l3b-bii-resolves-imported-type-ref
  (testing "F51 L3b (b.ii) — a type-ref to an imported name resolves to the source module named in the import."
    (let [files    [(parse-file :geom "def Point:\n    x: Int\n")
                    (parse-file :viz  "from geom import Point\n\ndef Scene:\n    origin: Point\n")]
          enriched (resolve/resolve-references files)
          scene    (second enriched)
          point-ref (first-type-ref-with-name (:nodes scene) "Point")]
      (is (= :geom (:resolves-to point-ref))
          "Point in :viz resolves to :geom via the explicit import"))))

(deftest f51-l3b-bii-resolves-prelude-name
  (testing "F51 L3b (b.ii) — a type-ref to a prelude name (`Duration`) resolves to the prelude's owning module (`:std.time`) regardless of the referencing module."
    (let [files    [(parse-file :app "def Job:\n    elapsed: Duration\n")]
          enriched (resolve/resolve-references files)
          job      (first enriched)
          dur-ref  (first-type-ref-with-name (:nodes job) "Duration")]
      (is (= :std.time (:resolves-to dur-ref))
          "Duration always resolves to :std.time regardless of caller module"))))

(deftest f51-l3b-bii-unresolved-name-leaves-node-bare
  (testing "F51 L3b (b.ii) — a reference to a name declared in another module without an explicit import leaves the node WITHOUT :resolves-to (unresolved is L3b(a) WARN territory; the enrichment pass doesn't fabricate cross-module bindings)."
    (let [files    [(parse-file :geom "def Point:\n    x: Int\n")
                    (parse-file :viz  "def Scene:\n    origin: Point\n")]
          enriched (resolve/resolve-references files)
          scene    (second enriched)
          point-ref (first-type-ref-with-name (:nodes scene) "Point")]
      (is (not (contains? point-ref :resolves-to))
          "Point in :viz has no explicit import from :geom; enrichment leaves it bare"))))

(deftest f51-l3b-bii-resolves-ctor-callee
  (testing "F51 L3b (b.ii) — a `Foo(x: 1)` ctor call whose callee is a capitalized bare :ident carries :resolves-to on the callee node when Foo's owning module is known."
    (let [files    [(parse-file :geom "def Point:\n    x: Int\n")
                    (parse-file :app  "from geom import Point\n\ndef main:\n    -> running\n        then: print(Point(x: 7).x)\n")]
          enriched (resolve/resolve-references files)
          app      (second enriched)
          callee   (first-callee-with-name (:nodes app) "Point")]
      (is (= :geom (:resolves-to callee))
          "Point ctor callee in :app resolves to :geom via the from-import"))))

(deftest f51-l3b-bii-enrichment-preserves-existing-node-attrs
  (testing "F51 L3b (b.ii) — enrichment is ADDITIVE only. Existing node attributes (`:name`, `:node`, `:nonnil?`, ...) survive untouched; downstream consumers that ignore `:resolves-to` see byte-identical AST."
    (let [files    [(parse-file :app "def Job:\n    elapsed: Duration\n")]
          orig     (:nodes (first files))
          enriched (:nodes (first (resolve/resolve-references files)))
          find-dur (fn [ns] (first-type-ref-with-name ns "Duration"))]
      (is (= (dissoc (find-dur enriched) :resolves-to)
             (find-dur orig))
          "removing :resolves-to from enriched yields the original node"))))

;; ---------------------------------------------------------------------------
;; F56 L1 — resolver-produced mangled names. Pure fn; additive
;; scaffolding for L2's emit consumers + L3's postwalk retirement.
;; ---------------------------------------------------------------------------

(deftest f56-l1-mangled-name-for-prelude
  (testing "F56 L1 — a declaration in a `:std.*` module mangles to `_Cxc_<Name>`. FFI hatches that hardcode the mangled name (`_Cxc_Duration`, `_Cxc_Clock`, ...) match this shape; after L2+L3 (2026-08-27) the emit-boundary `->c-safe` helper produces this form."
    (is (= "_Cxc_Duration" (resolve/mangled-name-for :std.time "Duration")))
    (is (= "_Cxc_Clock"    (resolve/mangled-name-for :std.clock "Clock")))
    (is (= "_Cxc_Console"  (resolve/mangled-name-for :std.io "Console")))))

(deftest f56-l1-mangled-name-for-main
  (testing "F56 L1 — a `:main` module declaration passes through unchanged. Single-file / root-of-src-dir programs use unmangled names for user decls."
    (is (= "Bag"   (resolve/mangled-name-for :main "Bag")))
    (is (= "Point" (resolve/mangled-name-for :main "Point")))
    (is (= "main"  (resolve/mangled-name-for :main "main")))))

(deftest f56-l1-mangled-name-for-user-module
  (testing "F56 L1 — a user module non-`:main` non-`:std.*` mangles to `<module-with-dots-as-underscores>_<name>`. Explicit prefix keeps debuggable C-symbol output (per F56 plan §5 Q1 ratification 2026-08-17: hardcode Option A). Dots collapse to underscores so `user.myapp.foo` module becomes `user_myapp_foo` prefix."
    (is (= "geom_Point"          (resolve/mangled-name-for :geom "Point")))
    (is (= "user_myapp_Foo"      (resolve/mangled-name-for :user.myapp "Foo")))
    (is (= "com_example_a_b_Bar" (resolve/mangled-name-for :com.example.a.b "Bar")))))

(deftest f56-l1-mangled-name-for-nil-module-returns-input
  (testing "F56 L1 — nil module treated as \"no attribution\": return the name unchanged. Guards callers who may pass an entry lacking `:module` (compiler-synthesized names in envs, for instance). Landing 2's emit consumers rely on this fallback so migration doesn't require pre-filtering."
    (is (= "Foo" (resolve/mangled-name-for nil "Foo")))))

(deftest f56-l1-mangled-name-consults-table
  (testing "F56 L1 — the two-arity `mangled-name` looks up `user-name` in the symbol table and dispatches to `mangled-name-for` with the entry's `:module`. Returns the input verbatim on table miss (compiler-synthesized names bypass the mangle)."
    (let [table {"Duration" {:name "Duration" :module :std.time :kind :def-atom}
                 "Bag"      {:name "Bag"      :module :main      :kind :record}
                 "Point"    {:name "Point"    :module :geom      :kind :record}}]
      (is (= "_Cxc_Duration" (resolve/mangled-name table "Duration")))
      (is (= "Bag"           (resolve/mangled-name table "Bag")))
      (is (= "geom_Point"    (resolve/mangled-name table "Point")))
      (is (= "Nowhere"       (resolve/mangled-name table "Nowhere"))
          "table miss returns input verbatim"))))
