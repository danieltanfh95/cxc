(ns cxc.build
  "Build orchestration — write ObjC source to disk, invoke clang, return
   the resulting binary's path. Assumes clang is on PATH."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [cxc.parse    :as parse]
            [cxc.alias    :as alias]
            [cxc.lower    :as lower]
            [cxc.emit     :as emit]
            [cxc.manifest :as manifest]
            [cxc.prelude  :as prelude]
            [cxc.resolve  :as resolve]
            [cxc.section  :as section]))

;; ---------------------------------------------------------------------------
;; Build cache (tier 1: .m → binary)
;;
;; The emit output (.m source) fully determines the binary output — no
;; hidden inputs, no timestamps, no environment. So a content-hash of
;; the .m is a safe cache key: identical .m ⇒ identical binary. When
;; the compiler internals change (parse/lower/emit), the emit output
;; changes too, and the hash naturally invalidates the entry.
;;
;; Clang dominates the build: ~200–500ms per invocation. On a repeat
;; run with no compiler change, the cache serves every example from
;; disk in a few ms total.
;;
;; Side benefit: the LAST emitted .m for every named example is copied
;; to `.cxc-cache/last/<name>.m` for browsing. Point of the cache dir
;; is convenience, not correctness — nuking it and rebuilding always
;; produces the same output.
;; ---------------------------------------------------------------------------

(def ^:private cache-root (io/file ".cxc-cache"))
(def ^:private cache-bin-dir  (io/file cache-root "bin"))
(def ^:private cache-last-dir (io/file cache-root "last"))

(defn- sha256-hex [^String s]
  (let [md   (java.security.MessageDigest/getInstance "SHA-256")
        _    (.update md (.getBytes s "UTF-8"))
        raw  (.digest md)]
    (apply str (map #(format "%02x" %) raw))))

(defn- copy-file [^java.io.File src ^java.io.File dst]
  (io/make-parents dst)
  (java.nio.file.Files/copy (.toPath src) (.toPath dst)
                            (into-array java.nio.file.CopyOption
                                        [java.nio.file.StandardCopyOption/REPLACE_EXISTING])))

(defn- ensure-executable [^java.io.File f]
  ;; Files/copy preserves mode on some platforms but not reliably —
  ;; mark the binary +x explicitly so a run after a cache-hit doesn't
  ;; hit "Permission denied".
  (.setExecutable f true false))

;; Layer 6 Step 11 — deprecation walker. `@deprecated("msg")` above
;; a def attaches a message to the declaration. Use-site walker below
;; finds every `:ident` /
;; `:type-ref` / `:call` reference to a deprecated name and emits a
;; warning to stderr with the migration message.

(defn- collect-deprecations
  "Walk top-level nodes; return map of {name → message} for every
   def-atom or record carrying a `@deprecated(\"msg\")` annotation."
  [top-nodes]
  (into {}
        (keep (fn [n]
                (when (contains? #{:def-atom :record} (:node n))
                  (let [dep (first (filter #(= "deprecated" (:name %))
                                           (:annotations n)))]
                    (when dep
                      [(:name n) (get-in dep [:args 0 :value :value])]))))
              top-nodes)))

(defn warn-deprecated-uses
  "F8 L3d walker for `:deprecated`. Standard L3d.ii.β walker signature:
   `[user-parsed full-parsed]` — the walker owns its own input
   computation. `user-parsed` is the AST for user source (what we
   warn against); `full-parsed` is `prelude + user` (what we scan for
   deprecation annotations, so a deprecated prelude export triggers
   a warning when user code uses it).

   Prints one warning per reference to *err*. Refs checked:
   `:ident` (name uses), `:type-ref` (type annotations); `:call`
   with an ident callee is covered transitively via the callee's
   `:ident` node, so covering `:call` too would double-warn.
   Definition sites in the deprecated def itself are skipped — a
   deprecated Foo referencing its own name in its methods doesn't
   self-warn.

   Public so `cxc.section/behavioral-walkers` can resolve it via
   `requiring-resolve` from the `:deprecated` seed's `:walker-sym`
   without depending on private-var access."
  [top-nodes full-parsed]
  (let [deprecations (collect-deprecations full-parsed)]
    (when (seq deprecations)
      (doseq [n top-nodes]
        (let [decl-name (:name n)
              skip-self (and (contains? #{:def-atom :record} (:node n))
                             (contains? deprecations decl-name))]
          (clojure.walk/postwalk
           (fn [x]
             (when (map? x)
               ;; Only :ident and :type-ref — a `:call` node with an
               ;; ident callee has its callee's :ident visited by the
               ;; postwalk already, so covering :call too would
               ;; double-warn on `Foo(...)` (once for the callee, once
               ;; for the call).
               (let [ref-name (cond
                                (= :ident (:node x))    (:name x)
                                (= :type-ref (:node x)) (:name x))]
                 (when (and ref-name
                            (contains? deprecations ref-name)
                            (not (and skip-self (= ref-name decl-name))))
                   (binding [*out* *err*]
                     (println (str "warning: `" ref-name
                                   "` is deprecated: "
                                   (get deprecations ref-name)))))))
             x)
           n))))))

;; Layer 6 Step 2/3 — stdlib import manifest + validation. Defined
;; here (before compile-source-to-file) so the single-file entrypoint
;; can call validate-imports without a forward declare.

(def ^:private stdlib-manifest
  "Known exports from prelude std.* modules. Keyed by dotted module
   name → set of exported symbol names. Layer 6 seeds this from the
   current prelude's rename-table — each mangled type gets its
   user-facing name registered under the appropriate std.<sub>
   module. Expands as later Layer 6 steps add capabilities."
  {"std.time"        #{"Duration" "Instant" "Clock"}
   "std.geometry"    #{"Position"}
   "std.random"      #{"Random"}
   "std.io"          #{"TestKeyboardInput" "Console"}
   "std.math"        #{"clamp"}
   "std.stream"      #{"Stream"}
   "std.collections" #{"List" "Map" "Set"}})

(defn- validate-imports
  "Walk parsed top-nodes, check every `:from-import` and `:import`
   entry against the known-module set (prelude modules from
   `stdlib-manifest` plus the `user-modules` set for multi-file
   builds). Prelude `from`-imports also check per-name exports;
   user-module imports only check the module target — per-name export
   validation lands with F51 L3 resolution.

   F52 L1 — `import X` and `import X as Y` validate the module target
   only (no per-name list to check)."
  ([top-nodes] (validate-imports top-nodes #{}))
  ([top-nodes user-modules]
   (doseq [n top-nodes
           :when (contains? #{:from-import :import} (:node n))]
     (let [from     (:from n)
           ;; F52 L2 — `:names` entries are `{:name :as}` maps post-parse.
           ;; The source-side name (what the exporter provides) is `:name`;
           ;; the local binding is `:as` when present, else `:name`.
           names    (when (= :from-import (:node n))
                      (mapv :name (:names n)))
           exported (get stdlib-manifest from)
           user?    (contains? user-modules (keyword from))
           surface  (if (= :from-import (:node n))
                      (str "from " from " import ...")
                      (str "import " from
                           (when (:as n) (str " as " (:as n)))))]
       (cond
         user?    nil
         exported (doseq [nm names]
                    (when-not (contains? exported nm)
                      (throw (ex-info
                              (str "module `" from "` does not export `" nm
                                   "`; available: " (sort exported))
                              {:kind :unknown-import
                               :module from :name nm}))))
         :else    (throw (ex-info
                          (str "unknown module `" from "` in `" surface
                               "` — must be a prelude module or another "
                               "user module in src-dir")
                          {:kind :unknown-module :module from})))))))

(defn compile-source-to-file
  "Compile cxc source text through parse → lower → emit → clang.
   Prepends the compiler-shipped prelude (see cxc.prelude) before
   parsing so records/atoms defined there are visible to user code
   without an import statement. Writes .m to `<out-dir>/<name>.m`
   and produces binary at `<out-dir>/<name>`.

   Tier-1 cache: content-hashes the emitted .m; on hit, copies the
   cached binary into place and skips clang entirely. On miss, runs
   clang as usual and, on success, stores the binary under
   `.cxc-cache/bin/<sha>`. Regardless of hit or miss, the .m is
   copied to `.cxc-cache/last/<name>.m` so you can browse the most
   recently emitted ObjC for any named example without hunting
   through test tmpdirs.

   F55 Landing 3: optional `:target` key selects the platform prelude
   (`prelude/target/<target>/*.cxc`). Defaults to `prelude/default-target`
   (:macos) so existing callers are backward-compatible.

   F32 L6: optional `:single-queue?` key controls the
   `CXC_SINGLE_MAIN_QUEUE` preprocessor value passed to clang.
   Default `true` (Layer 4 single-main-queue mode — every atom's
   own_queue targets main; deterministic scheduling for existing
   tests). Set to `false` to opt into F32 concurrent mode
   (`-DCXC_SINGLE_MAIN_QUEUE=0` — atom queues target the global
   pool per L1's runtime check, stream queues drop main-target per
   L5, shutdown drain fires per L2). The flag value participates
   in the cache key so single-queue and concurrent builds of the
   same source don't share a binary.

   Returns {:m-path <path> :bin-path <path> :status <clang-exit-code>
            :stdout <clang-stdout> :stderr <clang-stderr>
            :cache-hit? <bool>}."
  ([source name out-dir]
   (compile-source-to-file source name out-dir {}))
  ([source name out-dir {:keys [target allow-build-modules? single-queue?]
                         :or {target prelude/default-target
                              single-queue? true}}]
  (let [parse-fn (if allow-build-modules?
                   parse/parse-with-build-modules
                   parse/parse)
        _        (io/make-parents (io/file out-dir "placeholder"))
        parsed   (parse-fn source)
        _        (validate-imports parsed)
        _        (prelude/validate-no-prelude-collisions! parsed)
        prelude-src (prelude/assemble-source target)
        full-parsed (parse-fn (str prelude-src source))
        ;; F8 L3d.ii.β / L3b reshape — keep K3 walker dispatch through
        ;; the behavioral registry. `:deprecated` remains the sole entry;
        ;; only its input source changed from body sections to annotations.
        ;; Walker calling convention: `[user-parsed full-parsed]` —
        ;; the walker owns its own input computation (e.g. K3's
        ;; `warn-deprecated-uses` calls `collect-deprecations` on
        ;; `full-parsed` internally).
        _        (doseq [[_ walker-var] (section/behavioral-walkers)]
                   (walker-var parsed full-parsed))
        m-src    (-> full-parsed
                     alias/expand
                     prelude/rewrite-prelude-names
                     lower/lower
                     emit/emit)
        m-file   (io/file out-dir (str name ".m"))
        bin-file (io/file out-dir name)
        _        (spit m-file m-src)
        ;; Browse-cache: always keep the last emitted .m for each name.
        _        (copy-file m-file (io/file cache-last-dir (str name ".m")))
        ;; F32 L6 — the queue-mode flag doesn't change m-src (emit is
        ;; preprocessor-guarded, not mode-branched) but it does change
        ;; the compiled binary's runtime behavior. Fold it into the SHA
        ;; input so single-queue and concurrent builds don't collide.
        cxc-mq   (if single-queue? "1" "0")
        sha      (sha256-hex (str m-src "\n;; CXC_SINGLE_MAIN_QUEUE=" cxc-mq))
        cache-bin (io/file cache-bin-dir sha)]
    (if (.exists cache-bin)
      (do (copy-file cache-bin bin-file)
          (ensure-executable bin-file)
          {:m-path     (.getPath m-file)
           :bin-path   (.getPath bin-file)
           :status     0
           :stdout     ""
           :stderr     ""
           :cache-hit? true})
      (let [{:keys [exit out err]}
            (shell/sh "clang" "-fobjc-arc"
                      "-framework" "Foundation"
                      (str "-DCXC_SINGLE_MAIN_QUEUE=" cxc-mq)
                      "-o" (.getPath bin-file) (.getPath m-file))]
        (when (zero? exit)
          (copy-file bin-file cache-bin))
        {:m-path     (.getPath m-file)
         :bin-path   (.getPath bin-file)
         :status     exit
         :stdout     out
         :stderr     err
         :cache-hit? false})))))

(defn run-binary
  "Execute the compiled binary and return {:exit :stdout :stderr}."
  [bin-path]
  (let [{:keys [exit out err]} (shell/sh bin-path)]
    {:exit exit :stdout out :stderr err}))

;; ---------------------------------------------------------------------------
;; Multi-file compile (Layer 6 Steps 2+3 — minimum viable module system)
;;
;; Reads every `*.cxc` file under a root directory, concatenates the
;; sources (module/import lines strip to whitespace so downstream
;; parsing sees one big program), and feeds through the single-file
;; pipeline. Module boundaries are ADVISORY at this landing —
;; concatenation makes every user-authored declaration globally
;; visible. Real per-module name scoping ships when the compiler grows
;; a name-resolution pass; this landing proves the multi-file build
;; entrypoint + import parse without requiring that pass yet.
;;
;; Stdlib import validation: `from std.<sub> import <name>` is
;; checked against the prelude — if the name isn't exported from
;; that module (or the module doesn't exist), compile fails cleanly
;; instead of hitting an inscrutable downstream unresolved-ref error.
;; ---------------------------------------------------------------------------

(defn- strip-module-and-import-lines
  "Blank out `module <dotted>` and `from <dotted> import ...` lines in
   a source string. Keeps line numbers stable for downstream errors
   while making the concatenated multi-file source parse as a single
   program without redundant/conflicting module headers.

   F73 (2026-08-20) — `module` headers are hard-rejected at parse-time.
   The strip here only sees `from` and `import` lines in practice;
   the module-line branch is dead but kept for defensive tolerance
   until F51 L3 removes the flat-namespace concatenation shim entirely.

   F52 L1 (2026-08-23) — `import X` and `import X as Y` also strip
   here, matching the `from`-line treatment. Namespace-scoped binding
   waits on F51 L3."
  [src]
  (->> (clojure.string/split-lines src)
       (map (fn [line]
              (let [trimmed (clojure.string/trim line)]
                (cond
                  (clojure.string/starts-with? trimmed "module ") ""
                  (clojure.string/starts-with? trimmed "from ")   ""
                  (clojure.string/starts-with? trimmed "import ") ""
                  :else line))))
       (clojure.string/join "\n")))

(def ^:private main-module-kw
  "F51 L1 — root of src-dir (and single-file compile) is module `:main`.
   Subdirectory named `main` collides with this — see
   `reject-reserved-main-subdir!`."
  :main)

(defn- derive-module-name
  "F51 L1 — derive a module keyword from a file's containing directory
   relative to `src-dir`. Files directly in `src-dir/` → `:main`;
   files in `src-dir/foo/` → `:foo`; files in `src-dir/foo/bar/` →
   `:foo.bar`."
  [^java.io.File src-dir ^java.io.File file]
  (let [parent (.getParentFile file)]
    (if (or (nil? parent) (= (.getCanonicalPath parent)
                             (.getCanonicalPath src-dir)))
      main-module-kw
      (let [src-path    (.getCanonicalPath src-dir)
            parent-path (.getCanonicalPath parent)
            rel         (if (clojure.string/starts-with? parent-path
                                                         (str src-path "/"))
                          (subs parent-path (inc (count src-path)))
                          parent-path)
            dotted      (clojure.string/replace rel "/" ".")]
        (keyword dotted)))))

;; `reject-user-module-header!` retired 2026-08-20 (F73). The parser
;; now hard-rejects `module <name>` at parse-time with a targeted
;; diagnostic; the build-time check is unreachable and removed.

(defn- reject-reserved-main-subdir!
  "F51 L1 — a subdirectory named `main` at any level under `src-dir`
   would collide with the reserved `:main` module (root of src-dir /
   single-file compile). Refuse at compile start."
  [^java.io.File src-dir]
  (doseq [^java.io.File d (file-seq src-dir)]
    (when (and (.isDirectory d)
               (= "main" (.getName d))
               (not= (.getCanonicalPath d) (.getCanonicalPath src-dir)))
      (throw (ex-info
              (str "F51 L1: subdirectory `" (.getPath d)
                   "` collides with the reserved `:main` module "
                   "(root of src-dir / single-file compile). Rename "
                   "the directory (any other name works).")
              {:kind :cxc-f51-reserved-directory-name
               :dir (.getPath d)})))))

(defn- reject-duplicate-declarations-per-module!
  "F51 L1 — a declared name may appear at most once per module. Fires
   with the offending name and both module positions so the user can
   fix the collision."
  [files-with-nodes]
  (let [by-module (reduce
                    (fn [acc {:keys [module nodes file]}]
                      (reduce (fn [acc n]
                                (if (contains? #{:record :def-atom :enum}
                                               (:node n))
                                  (update-in acc [module (:name n)]
                                             (fnil conj []) file)
                                  acc))
                              acc nodes))
                    {}
                    files-with-nodes)]
    (doseq [[module by-name] by-module
            [nm files] by-name
            :when (> (count files) 1)]
      (throw (ex-info
              (str "F51 L1: declaration `" nm "` appears more than once "
                   "in module `" (name module) "` (files: "
                   (mapv #(.getName ^java.io.File %) files) ").")
              {:kind :cxc-f51-duplicate-declaration-in-module
               :module module :name nm
               :files (mapv #(.getPath ^java.io.File %) files)})))))

;; F51 L3a (2026-08-23) — user declarations must not collide with a
;; locally-bound imported name in the same module. Local binding of
;; `from X import Foo` is `Foo`; `from X import Foo as Bar` binds
;; `Bar` (so `def Foo:` alongside the aliased import is fine, but
;; `def Bar:` collides). `import X` binds `X`; `import X as Y` binds
;; `Y`. Fix-it: drop one, or add `as` to the import.
;;
;; Full namespace-scoped resolution (L3b) will retire the flat-
;; namespace concatenation and add unresolved-name / ambiguous-import
;; diagnostics on top of this collision guard.

(defn- import-local-bindings
  "Return [{:name <local-binding> :source <surface>} ...] for a parsed
   file — the names the import statements bind into local scope. Used
   by `reject-import-collides-with-decl!` for collision detection."
  [nodes]
  (into []
        (mapcat
          (fn [n]
            (case (:node n)
              :from-import
              (map (fn [entry]
                     {:name   (or (:as entry) (:name entry))
                      :source (str "from " (:from n) " import "
                                   (:name entry)
                                   (when (:as entry)
                                     (str " as " (:as entry))))})
                   (:names n))
              :import
              [{:name   (or (:as n) (:from n))
                :source (str "import " (:from n)
                             (when (:as n) (str " as " (:as n))))}]
              nil)))
        nodes))

(defn- reject-import-collides-with-decl!
  "F51 L3a — a user declaration and an imported name that bind the
   same identifier in the same module cannot coexist. Fires with the
   colliding name, the module, both file positions, and the import
   surface so the user can either drop one or add `as`."
  [files-with-nodes]
  (let [decls-by-module   (reduce
                            (fn [acc {:keys [module nodes file]}]
                              (reduce (fn [acc n]
                                        (if (contains? #{:record :def-atom :enum}
                                                       (:node n))
                                          (update-in acc [module (:name n)]
                                                     (fnil conj []) file)
                                          acc))
                                      acc nodes))
                            {} files-with-nodes)
        imports-by-module (reduce
                            (fn [acc {:keys [module nodes file]}]
                              (reduce (fn [acc {:keys [name source]}]
                                        (update-in acc [module name]
                                                   (fnil conj [])
                                                   {:file file :source source}))
                                      acc (import-local-bindings nodes)))
                            {} files-with-nodes)]
    (doseq [[module by-name] imports-by-module
            [nm entries]     by-name
            :let [decl-files (get-in decls-by-module [module nm])]
            :when (seq decl-files)]
      (throw (ex-info
              (str "F51 L3a: `" nm "` in module `" (name module)
                   "` is both declared (in "
                   (mapv #(.getName ^java.io.File %) decl-files)
                   ") and imported (via `"
                   (:source (first entries)) "` in "
                   (mapv #(.getName ^java.io.File (:file %)) entries)
                   "). Rename the declaration, drop the import, or "
                   "add `as <alias>` to the import.")
              {:kind          :cxc-f51-import-collides-with-decl
               :module        module :name nm
               :decl-files    (mapv #(.getPath ^java.io.File %) decl-files)
               :import-files  (mapv #(.getPath ^java.io.File (:file %)) entries)
               :import-source (:source (first entries))})))))

;; ---------------------------------------------------------------------------
;; F51 L3b (a) — compat-scan for unscoped type references.
;;
;; Before L3b (b) retires the flat-namespace concatenation shim, warn
;; users about cross-module type references that would fail under
;; strict per-module scoping. The check is advisory — it doesn't block
;; the build; downstream lower still resolves via the shim's flat
;; namespace. Users see one line per unresolved ref pointing at the
;; file and the module the ref would need to import.
;; ---------------------------------------------------------------------------

(defn- warn-unscoped-type-refs!
  "Emit a stderr warning per type-ref whose name doesn't resolve inside
   its module's scope (own decls + imports + prelude). Fires from
   `compile-directory-to-file` after collision + cycle checks so users
   see the harder-error diagnostics first.

   L3b (b.i) enhancement: when the unscoped name IS declared somewhere
   in the program, the warning names the declaring module(s) so users
   have a concrete import to add. Three shapes:
   - Declared in exactly one module → point at that module.
   - Declared in multiple modules → list them; user must qualify to
     disambiguate.
   - Not declared anywhere → surfaces as `undefined`; user needs to
     define the type or fix a typo."
  [files-with-nodes]
  (binding [*out* *err*]
    (doseq [{:keys [module file name declared-in]}
            (resolve/find-unscoped-type-refs files-with-nodes)]
      (let [where (str "type-ref `" name
                       "` in module `" (clojure.core/name module)
                       "` (" (.getName ^java.io.File file) ")")]
        (println
         (str "warning: F51 L3b: " where
              (case (count declared-in)
                0 (str " is undefined — no module in this program declares `"
                       name "`. Check for a typo or add the declaration.")
                1 (str " isn't imported here — `" name "` is declared in module `"
                       (clojure.core/name (first declared-in))
                       "`. Add `from " (clojure.core/name (first declared-in))
                       " import " name "` before F51 L3b retires the "
                       "flat-namespace shim.")
                (str " is ambiguous — `" name "` is declared in "
                     (count declared-in) " modules ("
                     (str/join ", " (map clojure.core/name declared-in))
                     "). Qualify the reference (`<module>." name "`) or "
                     "add a `from <module> import " name " as <alias>` "
                     "before F51 L3b retires the flat-namespace shim."))))))))

;; ---------------------------------------------------------------------------
;; F51 L2 — Module import dependency graph + topological order + cycle detection.
;; ---------------------------------------------------------------------------

(def ^:private prelude-module-kws
  "Prelude modules pre-seeded as `already resolved` in the dep graph;
   user modules never wait on them and cycles across them are impossible."
  (into #{} (map keyword) (keys stdlib-manifest)))

(defn- file-imports
  "F51 L2 + F52 L1 — return a set of imported module keywords for a
   parsed file. Both `:from-import` (`from X import a`) and `:import`
   (`import X` / `import X as Y`) contribute an edge to module `X`."
  [nodes]
  (into #{}
        (comp (filter #(contains? #{:from-import :import} (:node %)))
              (map #(keyword (:from %))))
        nodes))

(defn- module-dependency-graph
  "F51 L2 — `{module-kw → #{imported-module-kws}}` from per-file parsed
   nodes tagged with their derived module. Files in the same module
   contribute additively; prelude edges stay in the graph and are
   filtered out at cycle/topsort time."
  [files-with-nodes]
  (reduce (fn [acc {:keys [module nodes]}]
            (update acc module (fnil into #{}) (file-imports nodes)))
          {} files-with-nodes))

(defn- reject-self-imports!
  "F51 L2 — a module importing itself is trivially wrong."
  [graph]
  (doseq [[m deps] graph
          :when (contains? deps m)]
    (throw (ex-info
            (str "F51 L2: module `" (name m) "` imports itself "
                 "(`from " (name m) " import ...`) — remove the self-import")
            {:kind :cxc-f51-self-import :module m}))))

(defn- reject-unknown-imports!
  "F51 L2 — every imported module must be a known user module or prelude."
  [graph user-modules]
  (let [known (into prelude-module-kws user-modules)]
    (doseq [[m deps] graph
            d deps
            :when (not (contains? known d))]
      (throw (ex-info
              (str "F51 L2: module `" (name m) "` imports unknown module `"
                   (name d) "` — must be another user module in src-dir "
                   "or a prelude module")
              {:kind :cxc-f51-unknown-module :module m :imported d})))))

(defn- find-cycle
  "F51 L2 — DFS with in-path / fully-done marking. Returns a vector of
   module keywords `[m1 m2 ... mN m1]` forming a cycle, or nil if
   `user-graph` (user-module edges only) is acyclic. Deterministic —
   nodes and children visited in sorted-name order."
  [user-graph]
  (let [in-path    (atom #{})
        fully-done (atom #{})
        cycle      (atom nil)]
    (letfn [(dfs [node path]
              (when-not @cycle
                (cond
                  (contains? @in-path node)
                  (let [idx (.indexOf ^clojure.lang.PersistentVector
                                      path node)]
                    (reset! cycle (conj (subvec path idx) node)))

                  (contains? @fully-done node) nil

                  :else
                  (do
                    (swap! in-path conj node)
                    (doseq [child (sort (get user-graph node #{}))
                            :while (not @cycle)]
                      (dfs child (conj path node)))
                    (swap! in-path disj node)
                    (swap! fully-done conj node)))))]
      (doseq [n (sort (keys user-graph))
              :while (not @cycle)]
        (dfs n []))
      @cycle)))

(defn- topological-sort
  "F51 L2 — Kahn's algorithm on user modules (prelude edges pruned).
   Returns a vector of module keywords in dependency order (imports
   first). Throws `:cxc-f51-module-cycle` with the offending cycle path
   if the graph is cyclic. Deterministic tie-break by sorted module
   name."
  [graph user-modules]
  (let [user-graph (into {} (map (fn [m]
                                   [m (into #{} (filter user-modules)
                                            (get graph m #{}))]))
                        user-modules)]
    (when-let [c (find-cycle user-graph)]
      (throw (ex-info
              (str "F51 L2: module import cycle: "
                   (clojure.string/join " → " (map name c)))
              {:kind :cxc-f51-module-cycle :cycle c})))
    (let [in-degree (into {} (map (fn [[m deps]] [m (count deps)])) user-graph)
          consumers (reduce (fn [acc [m deps]]
                              (reduce (fn [a d]
                                        (update a d (fnil conj #{}) m))
                                      acc deps))
                            {} user-graph)]
      (loop [in-deg in-degree
             sorted []
             ready  (into (sorted-set)
                          (keep (fn [[m d]] (when (zero? d) m)) in-deg))]
        (if (empty? ready)
          sorted
          (let [m (first ready)
                cs (get consumers m #{})
                in-deg' (reduce (fn [acc c] (update acc c dec))
                                in-deg cs)
                new-ready (keep (fn [c] (when (zero? (get in-deg' c 1)) c))
                                cs)]
            (recur in-deg' (conj sorted m)
                   (into (disj ready m) new-ready))))))))

(defn- sort-files-by-module-order
  "F51 L2 — reorder `files-with-nodes` so that a module's files appear
   after every module they import. Within a module, files keep their
   incoming sorted-path order for determinism."
  [files-with-nodes sorted-modules]
  (let [module-rank (zipmap sorted-modules (range))]
    (sort-by (fn [{:keys [module file]}]
               [(get module-rank module Long/MAX_VALUE)
                (.getPath ^java.io.File file)])
             files-with-nodes)))

;; ---------------------------------------------------------------------------
;; F53 L1 — per-module manifest emission (post-successful-compile).
;;
;; For every user module, group its files, compute a source hash across
;; the sorted file contents, gather its import nodes, and hand the
;; per-module descriptor to `manifest/emit-manifest!`. The symbol
;; table is derived from the concatenated file nodes so entries carry
;; correct `:module` provenance for filtering downstream.
;; ---------------------------------------------------------------------------

(defn- iso-utc-now
  "Return the current UTC instant formatted as an ISO-8601 string
   without sub-second precision (`2026-08-23T12:34:56Z`). Kept in one
   place so future manifest changes can shift to a fixed clock for
   deterministic tests."
  []
  (let [inst (java.time.Instant/now)]
    (-> inst
        (.truncatedTo java.time.temporal.ChronoUnit/SECONDS)
        (.toString))))

(defn- symbol-table-across-modules
  "Build a unified symbol table across every file in every user
   module. Prepends a synthetic `:module <name>` node before each
   file's parsed nodes so `resolve/build-symbol-table` attributes
   each declaration to its owning module. Prelude decls aren't part
   of these files, so per-module filtering later yields only
   user-declared entries."
  [files-with-nodes]
  (let [tagged (mapcat (fn [{:keys [module nodes]}]
                         (cons {:node :module :name (clojure.core/name module)}
                               nodes))
                       files-with-nodes)]
    (resolve/build-symbol-table (vec tagged))))

(defn- import-nodes-for-module [files-with-nodes module]
  (into []
        (comp (filter #(= module (:module %)))
              (mapcat :nodes)
              (filter #(contains? #{:from-import :import} (:node %))))
        files-with-nodes))

(defn- source-hash-for-module [files-with-nodes module]
  (let [sources (into []
                      (comp (filter #(= module (:module %)))
                            (map #(slurp (:file %))))
                      files-with-nodes)]
    (sha256-hex (clojure.string/join "\n" sources))))

(defn- source-files-for-module [files-with-nodes module]
  (into []
        (comp (filter #(= module (:module %)))
              (map #(.getPath ^java.io.File (:file %)))
              (map str))
        files-with-nodes))

(defn- user-metadata-from-symbol-table
  "L3c NEW-INPUT — collect declaration metadata annotations retained by
   resolve. `build-manifest` accepts this map separately from the compiler-
   derived symbol-table facts so user fields cannot overwrite name/kind/
   variant/member output."
  [symbol-table]
  (into {}
        (keep (fn [[name entry]]
                (when (seq (:metadata-annotations entry))
                  [name (:metadata-annotations entry)])))
        symbol-table))

(defn- emit-module-manifests!
  "F53 L1 — write `<out-dir>/<module>.cxc-manifest.json` for every
   user module present in `files-with-nodes`. Deterministic order:
   modules sorted by name for reproducible builds."
  [out-dir files-with-nodes]
  (let [symbol-table (symbol-table-across-modules files-with-nodes)
        user-metadata (user-metadata-from-symbol-table symbol-table)
        compiled-at  (iso-utc-now)
        modules      (into (sorted-set) (map :module) files-with-nodes)]
    (doseq [module modules]
      (manifest/emit-manifest!
        (io/file out-dir)
        module
        (manifest/build-manifest
          {:module        module
           :source-files  (source-files-for-module files-with-nodes module)
           :source-hash   (source-hash-for-module files-with-nodes module)
           :symbol-table  symbol-table
           :user-metadata user-metadata
           :imports       (import-nodes-for-module files-with-nodes module)
           :compiled-at   compiled-at})))))

(defn compile-directory-to-file
  "Compile every `*.cxc` file under `src-dir` as one program. Files
   are read in sorted order (stable across runs), imports are
   validated against the stdlib manifest, module/from-import lines
   are stripped from each source (all decls concatenate into one
   flat namespace at this Layer 6 landing), and the combined source
   feeds through the single-file pipeline.

   Real per-module name scoping (a name-resolution pass that respects
   module boundaries) is a future Layer 6 followon — the entrypoint
   here proves the multi-file build shape and validates imports.

   F55 Landing 3: `opts` map with `:target` selects the platform
   prelude; passed through to `compile-source-to-file`.

   F53 L1: on successful compile, emits `<module>.cxc-manifest.json`
   for every user module under `out-dir`.

   F52 L3: `opts :source-overrides {module → path}` replaces the
   on-disk source for the named module with `(slurp path)`. The
   file's provenance (path, derived module) stays unchanged — only
   the source string is substituted. Overrides for modules that
   aren't discovered under `src-dir` are silently ignored with a
   stderr warning (they don't add new modules; that's a follow-up).
   Useful for tests, mocks, or platform-specific implementations.

   Same return shape as compile-source-to-file."
  ([src-dir name out-dir]
   (compile-directory-to-file src-dir name out-dir {}))
  ([src-dir name out-dir opts]
  (let [dir     (io/file src-dir)
        _       (when-not (.isDirectory dir)
                  (throw (ex-info (str "src-dir is not a directory: " src-dir)
                                  {:src-dir src-dir})))
        ;; F51 L1 — refuse `src-dir/main/` up front; it would produce
        ;; a `:main` module colliding with the root's default.
        _       (reject-reserved-main-subdir! dir)
        files   (->> (file-seq dir)
                     (filter #(and (.isFile ^java.io.File %)
                                   (.endsWith (.getName ^java.io.File %) ".cxc")))
                     (sort-by #(.getPath ^java.io.File %)))
        ;; F52 L3 — resolve source overrides against discovered modules.
        ;; Keys accept string or keyword; normalize to keyword to match
        ;; `derive-module-name`'s return type. CLI callers pass strings;
        ;; Clojure callers may pass keywords directly.
        source-overrides (reduce-kv (fn [m k v]
                                      (assoc m (keyword (clojure.core/name k)) v))
                                    {}
                                    (:source-overrides opts {}))
        discovered-modules (into #{} (map #(derive-module-name dir %)) files)
        _        (doseq [[m _] source-overrides
                         :when (not (contains? discovered-modules m))]
                   (binding [*out* *err*]
                     (println (str "warning: source override for `"
                                   (clojure.core/name m)
                                   "` targets a module not discovered under "
                                   src-dir " — ignored (F52 L3)."))))
        srcs    (mapv (fn [^java.io.File f]
                        (let [m (derive-module-name dir f)]
                          (if-let [override-path (get source-overrides m)]
                            (slurp override-path)
                            (slurp f))))
                      files)
        ;; F51 L2 — enumerate user modules up-front so per-file import
        ;; validation can accept `from <user-module> import ...`.
        user-modules (into #{} (map #(derive-module-name dir %)) files)
        ;; F51 L1 — parse each file separately, tag with derived
        ;; module, and check import validity against prelude + user modules.
        files-with-nodes
        (mapv (fn [[^java.io.File f src]]
                (let [module (derive-module-name dir f)
                      parsed (try
                               (parse/parse src)
                               (catch clojure.lang.ExceptionInfo e
                                 (throw (ex-info (str "in " (.getName f)
                                                      ": " (.getMessage e))
                                                 (assoc (ex-data e)
                                                        :file (.getPath f))
                                                 e))))]
                  (try
                    (validate-imports parsed user-modules)
                    (catch clojure.lang.ExceptionInfo e
                      (throw (ex-info (str "in " (.getName f)
                                           ": " (.getMessage e))
                                      (assoc (ex-data e)
                                             :file (.getPath f))
                                      e))))
                  ;; F52 L3 — carry the (possibly overridden) source string
                  ;; forward so the downstream concatenation doesn't re-slurp
                  ;; the on-disk file and lose the override.
                  {:file f :module module :nodes parsed :src src}))
              (map vector files srcs))
        _       (reject-duplicate-declarations-per-module! files-with-nodes)
        ;; F51 L2 — build the module import dep graph, reject self-imports
        ;; and unknown-module imports, then topologically sort user modules.
        ;; Files re-ordered so imported modules concatenate before their
        ;; consumers (deterministic tie-break by sorted file path within a
        ;; module).
        dep-graph        (module-dependency-graph files-with-nodes)
        _                (reject-self-imports! dep-graph)
        _                (reject-unknown-imports! dep-graph user-modules)
        ;; F51 L3a — collision check runs after structural import validity
        ;; so more specific diagnostics (self-import, unknown-module) fire
        ;; first when both would apply.
        _                (reject-import-collides-with-decl! files-with-nodes)
        ;; F51 L3b (a) — compat scan: emit a WARN for every type-ref that
        ;; wouldn't resolve under strict per-module scoping (own decls +
        ;; imports + prelude). The flat-namespace concatenation shim still
        ;; accepts these references today; L3b (b) will retire the shim
        ;; and turn each unresolved ref into a hard error. Fires only for
        ;; cross-module implicit references — same-module and prelude
        ;; refs stay silent.
        _                (warn-unscoped-type-refs! files-with-nodes)
        sorted-modules   (topological-sort dep-graph user-modules)
        files-with-nodes (sort-files-by-module-order files-with-nodes
                                                     sorted-modules)
        ;; F51 L1 — prepend a synthetic `module <derived>` line to
        ;; each source string before concatenation. The parser sees
        ;; it as a normal `:module` node; `resolve/build-symbol-table`
        ;; walks the stream stateful and attributes downstream decls
        ;; to that module. `strip-module-and-import-lines` still runs
        ;; to blank `from ...` (the flat-namespace concatenation shim
        ;; stays until F51 L3 lands scoped resolution).
        with-module-headers
        (mapv (fn [{:keys [module src]}]
                (str "module " (clojure.core/name module) "\n\n"
                     (strip-module-and-import-lines src)))
              files-with-nodes)
        combined (clojure.string/join "\n\n" with-module-headers)
        ;; F73 note: user `module` headers are rejected at parse-time; the
        ;; build injects synthetic ones here for per-file provenance and
        ;; passes `:allow-build-modules?` so the re-parse in
        ;; `compile-source-to-file` accepts them via `parse-with-build-modules`.
        result   (compile-source-to-file combined name out-dir
                                          (assoc opts :allow-build-modules? true))]
    ;; F53 L1 — emit one `<module>.cxc-manifest.json` per user module.
    ;; F53 L2 — emit `cxc-deps.json` at out-dir root capturing the
    ;; program-wide module dependency graph.
    ;; Only fires on successful compile so a clang failure doesn't
    ;; leave behind manifests for code that didn't build.
    (when (zero? (:status result))
      (emit-module-manifests! out-dir files-with-nodes)
      (manifest/emit-dep-graph!
        (io/file out-dir)
        (manifest/build-dep-graph
          {:graph          dep-graph
           :user-modules   user-modules
           :sorted-modules sorted-modules
           :compiled-at    (iso-utc-now)})))
    result)))
