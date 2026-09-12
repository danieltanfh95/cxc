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
   ;; D1(ii) 2026-09-08 — typed error records for v1.0 capability
   ;; failures (IOError, HttpError, ShellError, JsonError, EnvError).
   ;; Load-order: after `:system` (companion for Uncaught/SystemPanic),
   ;; before target caps that raise them. See prelude/shared/errors.cxc
   ;; and [[corvid-cap-typed-errors]] memory.
   {:name :errors  :kind :shared :names ["IOError" "HttpError" "ShellError" "JsonError" "EnvError"]}
   ;; Subprocess data types (2026-09-09) — `EnvSpec` variant for
   ;; R17-required `env:` param; `ProcResult` typed return for
   ;; `Subprocess.run_full` / future `run_typed`. Loaded shared so
   ;; user records / aspirational hello-agent can import from a
   ;; single-source-of-truth prelude module regardless of target.
   {:name :subprocess_types :kind :shared :names ["EnvSpec" "ProcResult"]}
   ;; SemVer + VersionRange are used by every plugin's `def plugin:`
   ;; manifest (`version: SemVer!` / `compat: VersionRange!` fields).
   ;; Centralized here so plugin authors never import. Landed 2026-08-31
   ;; R8 (plugin-shape consolidation wave); see
   ;; decisions-frozen-2026-08-31-plugin-shape.md.
   {:name :semver  :kind :shared :names ["SemVer" "VersionRange"]}
   ;; `Plugin` — sole FFI capability at v1.0 (R11 plugin-only wave,
   ;; 2026-09-01). `use plugin: Plugin` inside a shim atom of a
   ;; `def plugin:` module licenses compiler-resolved FFI intrinsics
   ;; (.get/.send/.class_send). Runtime linker (F76) refuses to fill
   ;; the slot outside modules carrying a `def plugin:` marker.
   ;; Method surface is compile-time-resolved by B5 against the
   ;; enclosing plugin's `foreign "..."` sections — no fixed method
   ;; shape declared in the prelude atom. See
   ;; decisions-frozen-2026-09-01-plugin-only.md and prelude/shared/plugin.cxc.
   {:name :plugin  :kind :shared :names ["Plugin"]}
   ;; F74 (B2, 2026-09-02) — OS-primitive vocabulary. Fixed pledge-
   ;; shaped set of syscall-grouped primitives that plugins claim in
   ;; their `requires:` manifest field. F77 unions across all reachable
   ;; plugins into a per-binary `.cxc-caps` manifest. See
   ;; decisions-frozen-2026-08-29.md Q3 (per-primitive storage,
   ;; consolidated into one enum for MVP).
   {:name :os_primitive :kind :shared :names ["OsPrimitive"]}
   {:name :random  :kind :target :names ["Random"]}
   {:name :clock   :kind :target :names ["Clock"]}
   ;; 2c-i (2026-09-02) — `:io` module retired. Console is now a
   ;; plugin-shaped module (`def plugin:` + `def Console:` in
   ;; `console.cxc`) backed by libc `puts`. Still autoloaded via the
   ;; prelude for backward compat with existing consumers; the
   ;; capability surface consumers see is unchanged
   ;; (`use console: Console` / `console.write(text)`). Semantic
   ;; change: `write` now line-terminates (puts adds `\n`) — matches
   ;; the ratified std.console draft; the prior `printf("%s", ...)`
   ;; no-newline behavior is retired. Callers that embedded a
   ;; trailing `\n` in the argument produce a double newline until
   ;; migrated.
   {:name :console :kind :target :names ["Console"]}
   ;; 2d (2026-09-02) — `FileSystem` capability. Foundation-backed
   ;; text file I/O (`read_text` / `write_text` / `exists`). Native
   ;; intrinsics (`native_fs_read_text` etc.) sit in ffi-builtins;
   ;; `FileSystem` is a plain capability atom (like Random/Clock)
   ;; and is NOT plugin-shaped at MVP — Foundation class-selector
   ;; access through `plugin.class_send` would demand NSError**
   ;; out-param plumbing which the FFI layer doesn't cover yet.
   ;; When binary I/O or richer error reporting are needed, migrate
   ;; via the same path Random/Clock will take (plugin-shaped +
   ;; class_selector entries).
   {:name :fs      :kind :target :names ["FileSystem"]}
   ;; 2e (2026-09-02) — `Subprocess` capability. `run(cmd)` returns
   ;; combined stdout+stderr as String (or nil on popen/fork failure).
   ;; MVP shape: single method, no exit-code surface, `2>&1` shell
   ;; redirect for stream unification. Migrate to a proper
   ;; `ProcResult{exit_code, stdout, stderr}` shape via NSTask +
   ;; NSPipe once the FFI carries composite return types.
   {:name :subprocess :kind :target :names ["Subprocess"]}
   ;; 2g (2026-09-02) — `Env` capability. Split D1(i) 2026-09-08 into
   ;; `EnvVars` (read-only, total: get_or/get_or_int/get_or_bool/has)
   ;; and `EnvMut` (mutation, raises EnvError: set/remove). Read
   ;; consumers `use env_vars: EnvVars`; mutation-capable atoms
   ;; `use env_mut: EnvMut`. See [[corvid-env-split]] memory.
   {:name :env :kind :target :names ["EnvVars" "EnvMut"]}
   ;; 2h (2026-09-03) — `Args` capability. `length` (argc as Int)
   ;; + `get_or(index, fallback)` (argv[index] with default).
   ;; Argc/argv accessed via Darwin's `*_NSGetArgc()` / `*_NSGetArgv()`
   ;; (declared in `<crt_externs.h>` which base-includes auto-adds
   ;; when Foundation is on).
   {:name :args :kind :target :names ["Args"]}
   ;; 4 (2026-09-03) — `Json` capability. `encode_str(s)` produces
   ;; a JSON-quoted+escaped String; `field_string(json, key)` reads
   ;; a top-level string-typed field from a JSON object. Backed by
   ;; `NSJSONSerialization` (which we use as a correct escaper by
   ;; wrapping s in a single-element array, serialising, and
   ;; stripping the `[...]`). Numeric/bool/nested-value field
   ;; readers wait on richer FFI surface — MVP is string-only.
   {:name :json :kind :target :names ["Json"]}
   ;; 5 (2026-09-03) — `Http` capability. `get(url, headers_json)`
   ;; + `post(url, headers_json, body)` block synchronously on
   ;; NSURLSession sharedSession dataTasks via dispatch_semaphore.
   ;; Response body returned as UTF-8 String, nil on failure.
   ;; Headers pass as a JSON String (parsed via NSJSONSerialization);
   ;; empty `"{}"` sends no custom headers. Sufficient for Claude
   ;; Messages API + generic REST calls at MVP.
   {:name :http :kind :target :names ["Http"]}
   ;; 2026-09-03 — `Hash` capability. `sha256_hex(data: Bytes) ->
   ;; String` returns the 64-char lowercase hex SHA-256 of the input
   ;; bytes. Backed by CommonCrypto (`CC_SHA256`). Header
   ;; `<CommonCrypto/CommonDigest.h>` auto-added by base-includes
   ;; when this atom is referenced. Common uses: cache keys, dedup,
   ;; content-addressed storage, integrity checks.
   {:name :hash :kind :target :names ["Hash"]}
   ;; 2026-09-03 — `Time` wall-clock capability. `now() -> Instant`
   ;; returns the current wall-clock time as an Instant (millis since
   ;; Unix epoch). Backed by Foundation `[[NSDate date]
   ;; timeIntervalSince1970]` via `native_millis_now`. Distinct from
   ;; Clock (which is a periodic-tick source for streaming); Time is
   ;; a one-shot read. Named `wall_time` at the module registry so
   ;; the file doesn't collide with the shared `:time` module that
   ;; declares the Duration/Instant records this depends on.
   {:name :wall_time :kind :target :names ["Time"]}])

(defn assemble-source
  "Load and concatenate prelude module sources for a given target.
   Shared modules load unconditionally; `:target` modules load from
   the selected target's directory. Returns the same byte-equivalent
   string historical `source` produced when target=:macos."
  [target]
  (clojure.string/join "\n" (map #(load-module-src % target) prelude-modules)))

(def source
  "Default-target prelude source text — kept as a public convenience
   for tests that concat prelude + user source before parsing. The
   production build path parses prelude via `assemble-source` +
   `parse` and AST-prepends the result to the enriched user AST (see
   `cxc.build`). Indentation is canonical cxc (4-space blocks); keep
   flush-left at column 0. Users write bare names; mangling to
   `_Cxc_<Name>` happens at emit-time via `->c-safe` in `cxc.lower`.

   F55 Landing 2: assembled from `prelude-modules` loaded via
   `io/resource` from the classpath prelude directory tree. Target
   selection through `assemble-source` shipped in Landing 3; this
   var defaults to `default-target` (macos)."
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
  #{"Clock" "Random" "TestKeyboardInput" "Console" "SystemPanic" "Plugin" "OsPrimitive"
    "FileSystem" "Subprocess" "EnvVars" "EnvMut" "Args" "Json" "Http" "Time" "Hash"})

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
  "F56 diagnostic — raise a targeted compile error when a user top-level
   declaration reuses a protected prelude name (spec §4.20 — prelude
   declarations are unshadowable at v1.0). Namespace-scoped shadowing
   support (whereby a module could shadow a prelude name locally
   without collision) is still deferred beyond F51 L3b's current
   landing.

   Takes user-only top-nodes (before prelude prepending) — runs right
   after `parse/parse` on the user source and before the prelude AST
   prepends onto it."
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
