(ns cxc.prelude
  "Compiler-shipped prelude: records + capability shapes that every
   cxc program can assume exist without importing. Prepended verbatim
   to the user's source text before parsing so a single parse pass
   sees both together and lowering resolves prelude references from
   the same top-level nodes vector.

   Prelude atoms are tree-shaken by cxc.lower: only those actually
   referenced from user code are emitted, so shipping capability
   stubs like Clock doesn't pull their fanout selectors into
   programs that never subscribe to them.

   Kept minimal — anything here becomes part of the language's stable
   surface for callers. Grows conservatively.

   Naming (Layer 5 minimum). Users write bare `Duration`, `Instant`,
   `Clock`. Post-parse, `rewrite-prelude-names` mangles every
   occurrence to `_Cxc_Duration`, `_Cxc_Instant`, `_Cxc_Clock` so the
   emitted C/ObjC source dodges CoreServices' `Duration` SInt32
   typedef and any other Cocoa/CoreFoundation collision that
   Foundation.h drags in. This replaces Layer 4b's user-visible
   `Cxc` prefix — the mangling is now internal-only. Layer 6's full
   module system supersedes the rename table with proper
   namespace-scoped resolution."
  (:require [clojure.java.io :as io]
            [clojure.string]
            [clojure.walk :as walk]))

(def rename-table
  "User-visible → mangled internal name. Applied post-parse to every
   `:name`, `:type`, `:outer` string in the AST. Table entries are
   specific enough that a user program that doesn't touch prelude
   types is a no-op; a user program that redefines a prelude name
   (e.g. `def Duration:`) gets silently rewritten too, which is a
   collision the compiler doesn't diagnose in Layer 5 minimum."
  {"Duration"           "_Cxc_Duration"
   "Instant"            "_Cxc_Instant"
   "Clock"              "_Cxc_Clock"
   "Random"             "_Cxc_Random"
   "TestKeyboardInput"  "_Cxc_TestKeyboardInput"
   "Ticker"             "_Cxc_Ticker"
   "Key"                "_Cxc_Key"
   "Position"           "_Cxc_Position"
   "Console"            "_Cxc_Console"
   ;; Layer 5c Step 8: Stream is the user-visible type for
   ;; method-returned reactive streams. Maps to CxcStream, the
   ;; runtime helper class defined inline in emit's helpers block
   ;; (see cxc.emit/cxc-runtime-helpers). Registered in atom-env at
   ;; lower time as a runtime-provided kind so type-refs resolve
   ;; but no class synthesis runs on it.
   "Stream"             "CxcStream"})

(defn rename-if
  "Rename `s` via `rename-table` if `s` is a string that appears in
   the table; otherwise return `s` unchanged. Non-string inputs pass
   through so callers can apply this uniformly across AST fields
   whose values may be strings, maps, or nil."
  [s]
  (if (and (string? s) (contains? rename-table s))
    (get rename-table s)
    s))

(def atom-names
  "Names of def-atom entries in the prelude, in their POST-RENAME
   (mangled) form. Tree-shaking in cxc.lower compares against these
   after the rewrite pass runs, so entries here match what the
   lowered AST carries — not what the prelude source spelled."
  #{"_Cxc_Clock" "_Cxc_Random" "_Cxc_TestKeyboardInput" "_Cxc_Ticker"
    "_Cxc_Console"})

;; F55 Landing 2 (2026-08-15) — prelude lives in a real directory tree
;; on the classpath: `resources/prelude/shared/*.cxc` (cross-target,
;; pure cxc) and `resources/prelude/target/<target>/*.cxc` (calls
;; native_* runtime — target-specific). The registry below names each
;; module and its declared user-visible names; sources load on demand
;; via `io/resource` so a runtime with a stripped-down classpath (e.g.
;; a future non-macos target) doesn't need to ship the .cxc it never
;; assembles. Selected target is passed through `assemble-source`;
;; default is :macos until Landing 3 wires proper target selection.

(def default-target
  "Default target when the compiler is invoked without an explicit
   target. Landing 3 will introduce target-selection plumbing."
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
   {:name :random  :kind :target :names ["Random"]}
   {:name :ticker  :kind :target :names ["Ticker"]}
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
   Users write bare names; `rewrite-prelude-names` mangles them
   post-parse.

   F55 Landing 2: assembled from `prelude-modules` loaded via
   `io/resource` from the classpath prelude directory tree. Target
   defaults to `default-target` (macos); explicit target selection
   through `assemble-source` waits on Landing 3."
  (assemble-source default-target))

(def duration-units
  "Recognized unit suffixes on numeric literals. Value = multiplier
   into `millis` — the canonical unit for `Duration`. Layer 5.4
   scope: time only; other physical dimensions (bytes, distance) are
   spec-committed but wait for a program that needs them.

   Fractional multipliers (e.g. `1.5s` at :float-unit) are handled
   by the same table via double arithmetic before construction."
  {"ms"  1
   "s"   1000
   "min" 60000})

(defn- desugar-unit-literal
  "Rewrite a `:lit-int-unit` or `:lit-float-unit` node into an
   equivalent `Duration(millis: N)` construction if the unit is one
   of the recognized time units. Unrecognized units drop through
   (lower still fails on them, keeping the diagnostic tied to a
   real user surface).

   Post-rename note: this runs INSIDE `rewrite-prelude-names`'s
   walker, which has already mapped `Duration` → `_Cxc_Duration`
   at the type-ref level — but the synthesized ctor callee here is
   a fresh `:ident` node we insert *after* renaming has traversed
   the tree, so we spell the mangled name directly."
  [node]
  (let [unit (:unit node)
        mult (get duration-units unit)]
    (if-not mult
      node
      (let [raw   (:value node)
            millis (if (= :lit-float-unit (:node node))
                     (long (Math/round ^double (double (* raw mult))))
                     (* raw mult))]
        {:node   :call
         :callee {:node :ident :name "_Cxc_Duration"}
         :args   [{:kind  :named
                   :name  "millis"
                   :value {:node :lit-int :value millis}}]}))))

(def ^:private class-member-desugars
  "Static-like accessors on prelude record types. Rewrite
   `<Type>.<member>` (post-rename mangled) to an equivalent value
   construction. Small, closed set — general record-class-members
   are Layer 5.5+ machinery, but a handful of designated static
   constants are enough for the common cases (`Instant.epoch`,
   `Position.origin`)."
  {["_Cxc_Instant" "epoch"]
   {:node   :call
    :callee {:node :ident :name "_Cxc_Instant"}
    :args   [{:kind :named :name "millis"
              :value {:node :lit-int :value 0}}]}
   ["_Cxc_Position" "origin"]
   {:node   :call
    :callee {:node :ident :name "_Cxc_Position"}
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

(def ^:private user-declaration-kinds
  ;; Top-level node kinds whose `:name` field is a user-visible
  ;; identifier that would collide with a prelude type.
  #{:def-atom :record :enum})

(defn validate-no-prelude-collisions!
  "Audit F56 — bounded starter for Phase D prelude architecture: raise
   a targeted compile error when a user top-level declaration reuses a
   protected prelude name (spec §4.20 — prelude aliases protected and
   unshadowable). Previously `rewrite-prelude-names` would silently
   rename a `def Duration:` to `def _Cxc_Duration:` and merge it into
   the prelude namespace, producing baffling downstream errors.

   Takes user-only top-nodes (before prelude prepending) — expects to
   run right after `parse/parse` on the user source and before the
   prelude+user re-parse. Full namespace-scoped resolution (retiring
   the postwalk entirely) is deferred to Phase D."
  [user-top-nodes]
  (doseq [n user-top-nodes
          :when (and (map? n)
                     (contains? user-declaration-kinds (:node n))
                     (contains? rename-table (:name n)))]
    (throw (ex-info
            (str "user declaration `" (:name n)
                 "` collides with a protected prelude name (spec §4.20). "
                 "Rename the declaration; namespace-scoped resolution that "
                 "would let user code shadow prelude names is deferred to "
                 "Phase D.")
            {:kind    :prelude-name-collision
             :name    (:name n)
             :node    (:node n)
             :mangled (get rename-table (:name n))}))))

(defn rewrite-prelude-names
  "Post-parse walk that mangles every occurrence of a user-visible
   prelude name to its internal form. Walks the parsed top-nodes
   vector; renames strings at map keys that carry type/identifier
   names (`:name`, `:type`, `:outer`). Other :inner values are
   maps (nested type nodes) and get renamed recursively by
   postwalk's own descent, not by this fn's direct value check.

   Also desugars unit-suffixed numeric literals (`1000ms`, `1.5s`)
   into `_Cxc_Duration(millis: N)` calls at the same walk — same
   post-parse timing, same postwalk mechanism.

   Safe to run over any parsed AST — the rename table only contains
   specific prelude names, so a program that never mentions them is
   an identity transform."
  [nodes]
  (walk/postwalk
    (fn [x]
      (if (map? x)
        (cond
          (contains? #{:lit-int-unit :lit-float-unit} (:node x))
          (desugar-unit-literal x)

          (= :dot (:node x))
          (desugar-class-member x)

          :else
          (cond-> x
            (contains? x :name)  (update :name  rename-if)
            (contains? x :type)  (update :type  rename-if)
            (contains? x :outer) (update :outer rename-if)))
        x))
    nodes))
