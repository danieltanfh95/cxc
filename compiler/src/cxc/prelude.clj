(ns cxc.prelude
  "Compiler-shipped prelude: records + capability shapes that every
   cxc program can assume exist without importing. Parsed separately
   from user source and AST-prepended to the enriched user AST so
   lowering resolves prelude references from the same top-level
   nodes vector. (F51 L3b(b.ii)-3/5c retired the source-level concat
   + reparse the earlier pipeline used.)

   Prelude atoms are tree-shaken by cxc.lower: only those actually
   referenced from user code are emitted, so shipping capability
   stubs like Clock doesn't pull their fanout selectors into
   programs that never subscribe to them.

   Kept minimal — anything here becomes part of the language's stable
   surface for callers. Grows conservatively.

   Naming (Layer 5 minimum). Users write bare `Duration`, `Instant`,
   `Clock`. At emit time, each identifier flows through `->c-safe`
   (in `cxc.lower`) which consults `resolve/mangled-name-for` and
   produces `_Cxc_Duration`, `_Cxc_Instant`, `_Cxc_Clock` so the
   emitted C/ObjC source dodges CoreServices' `Duration` SInt32
   typedef and any other Cocoa/CoreFoundation collision that
   Foundation.h drags in. F56 L2+L3 (2026-08-27) retired the
   parse-time rename postwalk: mangling now happens at the C-id output
   boundary rather than as a wholesale AST rewrite. Layer 6's full
   module system supersedes this with proper namespace-scoped
   resolution."
  (:require [clojure.java.io :as io]
            [clojure.string]
            [clojure.walk :as walk]))

;; F55 Landing 2 (2026-08-15) — prelude lives in a real directory tree
;; on the classpath: `resources/prelude/shared/*.cxc` (cross-target,
;; pure cxc) and `resources/prelude/target/<target>/*.cxc` (calls
;; native_* runtime — target-specific). The registry below names each
;; module and its declared user-visible names; sources load on demand
;; via `io/resource` so a runtime with a stripped-down classpath (e.g.
;; a future non-macos target) doesn't need to ship the .cxc it never
;; assembles. Selected target is passed through `assemble-source` —
;; F55 Landing 3 wired `:target` through `build/compile-source-to-
;; file` + `compile-directory-to-file`; unset callers fall back to
;; `default-target` (:macos).

(def default-target
  "Default target when the compiler is invoked without an explicit
   target. F55 Landing 3 wired target-selection through the build
   entrypoints; this value is the fallback when no `:target` is
   passed."
  :macos)

(defn- load-module-src
  "Read the .cxc source for a prelude module via classpath resource
   lookup. Path: `prelude/shared/<name>.cxc` or
   `prelude/target/<target>/<name>.cxc`. Throws with an actionable
   message if the resource is missing (indicates a packaging error
   or a name typo in the registry, not a user problem)."
  [{:keys [name kind]} target]
  (let [path (case kind
               :shared (str "prelude/shared/" (clojure.core/name name) ".cxc")
               :target (str "prelude/target/" (clojure.core/name target)
                            "/" (clojure.core/name name) ".cxc"))
        r    (io/resource path)]
    (when-not r
      (throw (ex-info (str "prelude module resource missing: `" path
                           "`. Check compiler/resources/prelude/ and "
                           "deps.edn `:paths`.")
                      {:module name :kind kind :target target :path path})))
    (slurp r)))

(def prelude-modules
  "Per-module registry. Ordering is the concatenation order for the
   assembled source string — chosen so declared types precede their
   users (historical: Duration before Random/Clock). The resolver
   will make ordering irrelevant once it lands.

   :name — symbolic module name (matches `<name>.cxc` on disk)
   :kind — `:shared` (cross-target) or `:target` (needs runtime)
   :names — user-visible declarations in the module. Used by F51/F56
            to build the resolver's name→module index without
            re-parsing the .cxc source."
  [{:name :time    :kind :shared :names ["Duration" "Instant"]}
   {:name :geom    :kind :shared :names ["Position"]}
   {:name :input   :kind :shared :names ["Key" "TestKeyboardInput"]}
   {:name :system  :kind :shared :names ["SystemPanic" "Uncaught"]}
   {:name :random  :kind :target :names ["Random"]}
   {:name :clock   :kind :target :names ["Clock"]}
   {:name :io      :kind :target :names ["Console"]}])

(defn assemble-source
  "Load and concatenate prelude module sources for a given target.
   Shared modules load unconditionally; `:target` modules load from
   the selected target's directory. Returns the same byte-equivalent
   string historical `source` produced when target=:macos."
  [target]
  (clojure.string/join "\n" (map #(load-module-src % target) prelude-modules)))

(def source
  "Prelude source text spliced ahead of user source. Indentation is
   canonical cxc (4-space blocks); keep flush-left at column 0.
   Users write bare names; mangling to `_Cxc_<Name>` happens at
   emit-time via `->c-safe` in `cxc.lower`.

   F55 Landing 2: assembled from `prelude-modules` loaded via
   `io/resource` from the classpath prelude directory tree. Target
   defaults to `default-target` (macos); explicit target selection
   through `assemble-source` waits on Landing 3."
  (assemble-source default-target))

;; F56 L2+L3 (2026-08-27) — the parse-time rename postwalk is
;; retired. Every downstream consumer (emit, resolve, lower) works in
;; user-visible name space and mangles at C-id output boundary via
;; `->c-safe`, which consults `resolve/mangled-name-for`. The old
;; `rename-table` / `rename-if` / `rewrite-prelude-names` /
;; `derive-prelude-rename-entries` defs went with it — `_Cxc_<Name>`
;; mangling now flows from `resolve/mangled-name-for` computed against
;; `prelude-modules` at the boundary. `Stream` remains a runtime-
;; provided helper mapped separately to `CxcStream` inside emit.

(def atom-names
  "Names of def-atom entries in the prelude, in user-visible form
   (F56 L2+L3, 2026-08-27 — postwalk retired). Tree-shaking in
   cxc.lower compares against these; entries match what the parsed
   AST carries verbatim from prelude source."
  #{"Clock" "Random" "TestKeyboardInput" "Console" "SystemPanic"})

;; F5 retirement (2026-08-15): unit-suffix desugaring (`100ms`,
;; `2s`, `1.5min` → `Duration(millis: N)`) is retired per spec
;; §2.7.1 (suffix syntax not part of v1). Users construct dimensioned
;; values via the type's ordinary constructor. Lex + parse reject the
;; suffix at source-position with a targeted diagnostic; this walker
;; only needs to handle class-member desugars now.

(def ^:private class-member-desugars
  "Static-like accessors on prelude record types. Rewrite
   `<Type>.<member>` to an equivalent value construction. Small,
   closed set — general record-class-members are Layer 5.5+
   machinery, but a handful of designated static constants are enough
   for the common cases (`Instant.epoch`, `Position.origin`).

   Keyed by user-visible name (F56 L2+L3, 2026-08-27) — postwalk
   retired; every consumer works in user-visible name space and
   mangles at C-id output boundary via `->c-safe`."
  {["Instant" "epoch"]
   {:node   :call
    :callee {:node :ident :name "Instant"}
    :args   [{:kind :named :name "millis"
              :value {:node :lit-int :value 0}}]}
   ["Position" "origin"]
   {:node   :call
    :callee {:node :ident :name "Position"}
    :args   [{:kind :named :name "x" :value {:node :lit-float :value 0.0}}
             {:kind :named :name "y" :value {:node :lit-float :value 0.0}}]}})

(defn- desugar-class-member
  "If `node` is a `:dot` whose target is a bare ident naming a
   prelude type with a known static member, replace it with the
   equivalent constructor call. Otherwise return unchanged."
  [node]
  (if (and (= :dot (:node node))
           (map? (:target node))
           (= :ident (get-in node [:target :node])))
    (or (get class-member-desugars
            [(get-in node [:target :name]) (:field node)])
        node)
    node))

(defn desugar-prelude-class-members
  "F56 L2+L3 (2026-08-27) — standalone pass that desugars
   `<PreludeType>.<member>` references (`Instant.epoch`,
   `Position.origin`) to their equivalent constructor calls.
   Formerly performed inside `rewrite-prelude-names` (retired).
   Runs on the parsed AST before lower."
  [nodes]
  (walk/postwalk
    (fn [x]
      (if (and (map? x) (= :dot (:node x)))
        (desugar-class-member x)
        x))
    nodes))

(def ^:private user-declaration-kinds
  ;; Top-level node kinds whose `:name` field is a user-visible
  ;; identifier that would collide with a prelude type.
  #{:def-atom :record :enum})

(def ^:private protected-prelude-names
  "Set of user-visible prelude declaration names (from
   `prelude-modules`). Used by `validate-no-prelude-collisions!` to
   reject `def Duration:` / `def Instant:` at the source. Runtime-
   provided `Stream` is not in this set — it isn't a user-declared
   prelude type."
  (into #{} (mapcat :names) prelude-modules))

(defn validate-no-prelude-collisions!
  "Audit F56 — bounded starter for Phase D prelude architecture: raise
   a targeted compile error when a user top-level declaration reuses a
   protected prelude name (spec §4.20 — prelude declarations are
   unshadowable). Full namespace-scoped resolution that would let user
   code shadow prelude names is deferred to Phase D.

   Takes user-only top-nodes (before prelude prepending) — expects to
   run right after `parse/parse` on the user source and before the
   prelude+user re-parse."
  [user-top-nodes]
  (doseq [n user-top-nodes
          :when (and (map? n)
                     (contains? user-declaration-kinds (:node n))
                     (contains? protected-prelude-names (:name n)))]
    (throw (ex-info
            (str "user declaration `" (:name n)
                 "` collides with a protected prelude name (spec §4.20). "
                 "Rename the declaration; namespace-scoped resolution that "
                 "would let user code shadow prelude names is deferred to "
                 "Phase D.")
            {:kind :prelude-name-collision
             :name (:name n)
             :node (:node n)}))))
