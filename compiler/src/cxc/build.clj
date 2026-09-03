(ns cxc.build
  "Build orchestration — write ObjC source to disk, invoke clang, return
   the resulting binary's path. Assumes clang is on PATH."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [cxc.parse           :as parse]
            [cxc.lower           :as lower]
            [cxc.emit            :as emit]
            [cxc.manifest        :as manifest]
            [cxc.plugin-manifest :as plugin-manifest]
            [cxc.prelude         :as prelude]
            [cxc.project         :as cxc-project]
            [cxc.resolve         :as resolve]
            [cxc.section         :as section]))

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

;; Layer 6 Step 11 deprecation walker retired 2026-08-30 alongside the
;; annotation-grammar retirement wave. `@deprecated("msg")` no longer
;; parses; deprecation is expressed as a comment or user-code convention
;; at v1.0. See decisions-frozen-2026-08-30.md for the retirement
;; rationale. `section/behavioral-walkers` still iterates the walker
;; map — post-retirement the map is empty and the doseq becomes a
;; no-op, which is fine.

;; Layer 6 Step 2/3 — stdlib import manifest + validation. Defined
;; here (before compile-source-to-file) so the single-file entrypoint
;; can call validate-imports without a forward declare.

(def ^:private stdlib-manifest
  "Known exports from prelude std.* modules. Keyed by dotted module
   name → set of exported symbol names. Layer 6 seeds this from the
   current prelude's `prelude-modules` registry — each declared type
   gets its user-facing name registered under the appropriate
   std.<sub> module. Expands as later Layer 6 steps add capabilities."
  {"std.time"        #{"Duration" "Instant" "Clock"}
   "std.geometry"    #{"Position"}
   "std.random"      #{"Random"}
   "std.console"     #{"Console"}
   "std.input"       #{"TestKeyboardInput"}
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

(defn- compile-parsed-to-file
  "Internal — shared tail for the single-file
   (`compile-source-to-file`) and multi-file
   (`compile-directory-to-file`) paths. Both callers assemble their
   full AST by prepending the parsed prelude to the enriched user AST
   (no source-level concat, no reparse). Takes:

     - `:user-parsed` — the user program's AST (single file's parse,
       or the concat of enriched per-file ASTs interleaved with
       `:module` marker nodes).
     - `:full-parsed` — prelude AST prepended to `user-parsed`.

   Runs behavioral walkers (which need both scopes),
   `prelude/desugar-prelude-class-members`, `lower/lower`,
   `emit/emit`, then
   writes `.m`, computes cache SHA (including the queue-mode flag
   per F32 L6), and either restores from the tier-1 binary cache or
   runs clang.

   Returns the same shape as the public entrypoints:
   `{:m-path :bin-path :status :stdout :stderr :cache-hit?}`."
  [{:keys [user-parsed full-parsed]} name out-dir
   {:keys [single-queue?] :or {single-queue? true}}]
  (let [_        (io/make-parents (io/file out-dir "placeholder"))
        ;; Behavioral-walker dispatch — reads `section/behavioral-walkers`
        ;; (registry-provided walker vars, keyed by seed name). Post
        ;; annotation-grammar-retirement (2026-08-30) no seed carries a
        ;; walker-sym; the loop is a no-op at v1.0 and stays as the
        ;; substrate for future walker landings. Walker calling convention:
        ;; `[user-parsed full-parsed]` — walker owns its own input
        ;; computation.
        _        (doseq [[_ walker-var] (section/behavioral-walkers)]
                   (walker-var user-parsed full-parsed))
        m-src    (-> full-parsed
                     prelude/desugar-prelude-class-members
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
         :cache-hit? false}))))

(defn- process-prelude-nodes
  "2c-i (2026-09-02) — assemble + parse + plugin-pipeline + enrich
   the prelude for a given target. The plugin pipeline (FL-12 objc-
   alias rewrite → manifest extract → validate → FL-1 plugin.get
   rewrite → FL-11 foreign-alias field elision) now runs on the
   prelude as well as user source, so a prelude module carrying
   `def plugin:` (e.g. `console.cxc` post-Console-retirement) gets
   its `plugin.get(LIT)` calls rewritten to `:foreign-ref` before
   lower sees the AST. Same pipeline order as user source in
   `compile-source-to-file` and per-file in `compile-directory-to-
   file`; single site of truth for prelude preprocessing."
  [target]
  (let [ps (parse/parse (prelude/assemble-source target))
        ps (plugin-manifest/rewrite-objc-type-aliases ps)
        manifest (plugin-manifest/extract ps)]
    (when manifest
      (plugin-manifest/validate-plugin-symbol-refs! ps manifest))
    (-> ps
        (plugin-manifest/rewrite-plugin-refs manifest)
        plugin-manifest/rewrite-foreign-alias-fields
        resolve/enrich-prelude-nodes)))

(defn compile-source-to-file
  "Compile cxc source text through parse → lower → emit → clang.
   Parses the compiler-shipped prelude (see cxc.prelude) separately
   and prepends its AST to the enriched user AST so records/atoms
   defined there are visible to user code without an import
   statement. Writes .m to `<out-dir>/<name>.m` and produces binary
   at `<out-dir>/<name>`.

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
  ([source name out-dir {:keys [target allow-build-modules?]
                         :or {target prelude/default-target}
                         :as opts}]
  (let [parse-fn (if allow-build-modules?
                   parse/parse-with-build-modules
                   parse/parse)
        parsed   (parse-fn source)
        _        (validate-imports parsed)
        _        (prelude/validate-no-prelude-collisions! parsed)
        _        (plugin-manifest/validate-plugin-capability-uses! parsed)
        ;; FL-12 (2026-09-02): before extracting the manifest, rewrite
        ;; foreign-block signature type-refs matching registered
        ;; `type "NSFoo"` aliases to `Any` (id). This way B5's
        ;; foreign-index + FL-1's :foreign-ref nodes carry the
        ;; substituted signatures.
        parsed   (plugin-manifest/rewrite-objc-type-aliases parsed)
        ;; B5 (2026-09-02): resolve `plugin.get(LIT)` against the
        ;; module's `foreign "..."` blocks. Extract manifest first;
        ;; nil when this module isn't a plugin — resolver no-ops then.
        module-manifest (plugin-manifest/extract parsed)
        _        (plugin-manifest/validate-plugin-symbol-refs!
                   parsed module-manifest)
        ;; FL-1 (2026-09-02): rewrite `plugin.get(LIT)` calls into
        ;; `:foreign-ref` AST nodes so lower/emit deal in resolved
        ;; symbols rather than `plugin.<method>` grammar. Runs after
        ;; B5 validation — every literal is guaranteed to resolve.
        parsed   (plugin-manifest/rewrite-plugin-refs parsed module-manifest)
        ;; FL-11 (2026-09-02): elide foreign-alias fields — fields
        ;; typed as `:foreign-fn-type` with `:foreign-ref` defaults
        ;; become source-level aliases; `self.<name>(args)` in the same
        ;; atom body gets rewritten to a direct foreign-ref call.
        parsed   (plugin-manifest/rewrite-foreign-alias-fields parsed)
        ;; F51 L3b(b.ii)-5c (2026-08-27) — enrich user AST with
        ;; `:resolves-to` provenance before concatenating with prelude.
        ;; Single-file callers implicitly declare into the `:main`
        ;; module (F51 L1 convention). Parse prelude separately and
        ;; concat ASTs so `:resolves-to` survives — no reparse of
        ;; concatenated source (mirrors b.ii-3 for multi-file).
        enriched-parsed
        (-> [{:module :main :nodes parsed}]
            resolve/resolve-references
            first
            :nodes)
        prelude-nodes (process-prelude-nodes target)
        full-parsed   (into (vec prelude-nodes) enriched-parsed)
        result        (compile-parsed-to-file
                        {:user-parsed enriched-parsed :full-parsed full-parsed}
                        name out-dir opts)]
    ;; B7 / F77 — emit `<binary>.cxc-caps` on successful compile. Single-
    ;; file entry has at most one plugin manifest (the source itself if it
    ;; carries `def plugin:`).
    (when (zero? (:status result))
      (plugin-manifest/emit-caps!
        (:bin-path result)
        (plugin-manifest/build-caps-map
          [(plugin-manifest/extract parsed)]
          target
          manifest/compiler-version)))
    result)))

(defn run-binary
  "Execute the compiled binary and return {:exit :stdout :stderr}."
  [bin-path]
  (let [{:keys [exit out err]} (shell/sh bin-path)]
    {:exit exit :stdout out :stderr err}))

;; ---------------------------------------------------------------------------
;; Multi-file compile (F51 L3b(b.ii)-3 — per-module AST-in path)
;;
;; Reads every `*.cxc` file under a root directory, parses each file
;; individually, validates imports against the stdlib manifest + user
;; modules, sorts modules topologically, enriches the per-file ASTs
;; with `:resolves-to` provenance metadata via
;; `resolve/resolve-references`, assembles a flat top-node stream
;; (synthetic `:module` marker per file for lower's attribution walk),
;; prepends the prelude AST, and feeds the whole thing directly to
;; `compile-parsed-to-file`. No source-level concatenation, no reparse
;; of a synthesized combined source — `:resolves-to` metadata now
;; flows all the way to `lower`.
;;
;; Stdlib import validation: `from std.<sub> import <name>` is
;; checked against the prelude — if the name isn't exported from
;; that module (or the module doesn't exist), compile fails cleanly
;; instead of hitting an inscrutable downstream unresolved-ref error.
;; ---------------------------------------------------------------------------

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

(defn- reject-reserved-std-subdir!
  "A7 (2026-09-03) — `std.*` is the prelude/stdlib namespace. A user
   directory `<src-dir>/std/` (at src-dir root) would produce a
   `:std` (or `:std.foo`) user module that collides with the
   prelude-owned import namespace (`from std.args import Args`,
   `from std.collections import List`). Reject at compile start with
   a targeted diagnostic + rename suggestion. Nested `<src-dir>/foo/std/`
   is fine — the collision is only at the top level."
  [^java.io.File src-dir]
  (let [top-std (io/file src-dir "std")]
    (when (.isDirectory top-std)
      (throw (ex-info
              (str "A7: top-level `std/` directory in src-dir would "
                   "produce a user module in the reserved `std.*` namespace "
                   "(prelude/stdlib). Rename the directory — any other name "
                   "works, DNS-reverse (`com.<vendor>.<pkg>/`) or flat "
                   "(`vendor/<pkg>/`) are the recommended shapes.")
              {:kind :cxc-a7-reserved-std-namespace
               :dir (.getPath top-std)})))))

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
;; Full namespace-scoped resolution (L3b) retired the flat-namespace
;; source concatenation (b.ii-3/4). Unresolved-name / ambiguous-import
;; diagnostics still WARN today via `warn-unscoped-type-refs!` and
;; harden into errors at b.ii-5e when the short-name fallback in
;; `lower/decl-lookup` retires.

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
;; Warn users about cross-module type references that would fail once
;; `lower/decl-lookup`'s short-name fallback retires (b.ii-5e). The
;; b.ii-5a-c wiring already consumes the `:resolves-to` hint that
;; b.ii-1's resolver produces (multi-file: b.ii-3; single-file:
;; b.ii-5c), so cross-module refs with a hint resolve strictly today
;; — the short-name fallback catches unmigrated sites and cases
;; where the hint is nil. The check remains advisory; hardens into
;; an error at b.ii-5e. Users see one line per unresolved ref
;; pointing at the file and the module the ref would need to import.
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
                       " import " name "` before lower's short-"
                       "name fallback is retired.")
                (str " is ambiguous — `" name "` is declared in "
                     (count declared-in) " modules ("
                     (str/join ", " (map clojure.core/name declared-in))
                     "). Qualify the reference (`<module>." name "`) or "
                     "add a `from <module> import " name " as <alias>` "
                     "before lower's short-name fallback is retired."))))))))

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

;; `user-metadata-from-symbol-table` retired 2026-08-30 with the
;; annotation-grammar-retirement wave. `:metadata-annotations` no
;; longer exists on symbol-table entries (resolve.clj pipe-through
;; deleted alongside `@metadata(...)` grammar retirement). When a
;; replacement user-metadata surface lands (e.g. `meta = Meta(...)`
;; module-let), a new source function goes here.

(defn- emit-module-manifests!
  "F53 L1 — write `<out-dir>/<module>.cxc-manifest.json` for every
   user module present in `files-with-nodes`. Deterministic order:
   modules sorted by name for reproducible builds."
  [out-dir files-with-nodes]
  (let [symbol-table (symbol-table-across-modules files-with-nodes)
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
           :imports       (import-nodes-for-module files-with-nodes module)
           :compiled-at   compiled-at})))))

(defn compile-directory-to-file
  "Compile every `*.cxc` file under `src-dir` as one program. Files
   are read in sorted order (stable across runs), imports are
   validated against the stdlib manifest, per-file ASTs are enriched
   with `:resolves-to` provenance via `resolve/resolve-references`
   (F51 L3b(b.ii)-3), and the assembled top-node stream (synthetic
   `:module` markers per file + prepended prelude AST) feeds directly
   to `compile-parsed-to-file` — no source-level concat, no reparse.

   Cross-module scoping surfaces as WARN via
   `warn-unscoped-type-refs!` today; b.ii-5e retires
   `lower/decl-lookup`'s short-name fallback and turns the check into
   a hard error.

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
        ;; A7 (2026-09-03) — refuse `src-dir/std/` up front; user
        ;; modules must not squat on the prelude namespace.
        _       (reject-reserved-std-subdir! dir)
        ;; A7 — read `project.cxc` at src-dir root (if present),
        ;; fetch any declared deps, then walk src-dir + each cached
        ;; dep path as extra roots. Files under a dep's cache path
        ;; derive their module name from THAT cache path (not src-dir),
        ;; so `<cache>/<sha>/com/acme/http_claude/*.cxc` resolves as
        ;; module `:com.acme.http_claude` regardless of where the
        ;; user's src-dir lives.
        manifest         (cxc-project/read-manifest dir)
        resolved-deps    (when manifest
                           (cxc-project/resolve-and-populate! dir manifest))
        extra-roots      (mapv #(io/file (:cache-path %)) resolved-deps)
        manifest-file    (cxc-project/manifest-file dir)
        exclude-file?    (fn [^java.io.File f]
                           (and manifest-file
                                (= (.getCanonicalPath f)
                                   (.getCanonicalPath ^java.io.File manifest-file))))
        collect-cxc      (fn [^java.io.File root]
                           (->> (file-seq root)
                                (filter #(and (.isFile ^java.io.File %)
                                              (.endsWith (.getName ^java.io.File %) ".cxc")
                                              (not (exclude-file? %))))))
        files-with-roots (->> (into [{:root dir :files (collect-cxc dir)}]
                                    (map (fn [r] {:root r :files (collect-cxc r)}))
                                    extra-roots)
                              (mapcat (fn [{:keys [root files]}]
                                        (map (fn [f] {:root root :file f}) files)))
                              (sort-by #(.getPath ^java.io.File (:file %)))
                              vec)
        files            (mapv :file files-with-roots)
        root-for-file    (into {} (map (juxt :file :root) files-with-roots))
        module-of-file   (fn [^java.io.File f]
                           (derive-module-name (root-for-file f) f))
        ;; F52 L3 — resolve source overrides against discovered modules.
        ;; Keys accept string or keyword; normalize to keyword to match
        ;; `derive-module-name`'s return type. CLI callers pass strings;
        ;; Clojure callers may pass keywords directly.
        source-overrides (reduce-kv (fn [m k v]
                                      (assoc m (keyword (clojure.core/name k)) v))
                                    {}
                                    (:source-overrides opts {}))
        discovered-modules (into #{} (map module-of-file) files)
        _        (doseq [[m _] source-overrides
                         :when (not (contains? discovered-modules m))]
                   (binding [*out* *err*]
                     (println (str "warning: source override for `"
                                   (clojure.core/name m)
                                   "` targets a module not discovered under "
                                   src-dir " — ignored (F52 L3)."))))
        srcs    (mapv (fn [^java.io.File f]
                        (let [m (module-of-file f)]
                          (if-let [override-path (get source-overrides m)]
                            (slurp override-path)
                            (slurp f))))
                      files)
        ;; F51 L2 — enumerate user modules up-front so per-file import
        ;; validation can accept `from <user-module> import ...`.
        user-modules (into #{} (map module-of-file) files)
        ;; F51 L1 — parse each file separately, tag with derived
        ;; module, and check import validity against prelude + user modules.
        files-with-nodes
        (mapv (fn [[^java.io.File f src]]
                (let [module (module-of-file f)
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
                  ;; B4 (F76): per-file linker gate — `use plugin: Plugin`
                  ;; only legal inside modules carrying a `def plugin:`
                  ;; marker (R11 plugin-only wave). Runs alongside import
                  ;; validation so the diagnostic points at the offending
                  ;; file.
                  (try
                    (plugin-manifest/validate-plugin-capability-uses! parsed)
                    (catch clojure.lang.ExceptionInfo e
                      (throw (ex-info (str "in " (.getName f)
                                           ": " (.getMessage e))
                                      (assoc (ex-data e)
                                             :file (.getPath f))
                                      e))))
                  ;; FL-12: rewrite `type "NSFoo" as Alias` refs to
                  ;; `Any` (id) BEFORE manifest extraction so the
                  ;; foreign-index carries substituted signatures.
                  (let [parsed (plugin-manifest/rewrite-objc-type-aliases
                                 parsed)
                        ;; B5: per-file `plugin.get(LIT)` resolution against
                        ;; the file's own foreign blocks. Manifest is
                        ;; module-scoped; cross-plugin symbol reach is
                        ;; grammatically impossible under R11.
                        file-manifest
                        (try
                          (let [mfst (plugin-manifest/extract parsed)]
                            (plugin-manifest/validate-plugin-symbol-refs!
                              parsed mfst)
                            mfst)
                          (catch clojure.lang.ExceptionInfo e
                            (throw (ex-info (str "in " (.getName f)
                                                 ": " (.getMessage e))
                                            (assoc (ex-data e)
                                                   :file (.getPath f))
                                            e))))
                        ;; FL-1: rewrite plugin.get(LIT) → :foreign-ref
                        ;; before lower sees the AST. Idempotent on
                        ;; non-plugin files.
                        parsed (plugin-manifest/rewrite-plugin-refs
                                 parsed file-manifest)
                        ;; FL-11: elide foreign-alias fields per atom.
                        parsed (plugin-manifest/rewrite-foreign-alias-fields
                                 parsed)]
                    {:file f :module module :nodes parsed})))
              (map vector files srcs))
        _       (reject-duplicate-declarations-per-module! files-with-nodes)
        ;; F51 L2 — build the module import dep graph, reject self-imports
        ;; and unknown-module imports, then topologically sort user modules.
        ;; Files re-ordered so imported modules appear in the flat top-node
        ;; stream before their consumers (deterministic tie-break by sorted
        ;; file path within a module).
        dep-graph        (module-dependency-graph files-with-nodes)
        _                (reject-self-imports! dep-graph)
        _                (reject-unknown-imports! dep-graph user-modules)
        ;; F51 L3a — collision check runs after structural import validity
        ;; so more specific diagnostics (self-import, unknown-module) fire
        ;; first when both would apply.
        _                (reject-import-collides-with-decl! files-with-nodes)
        ;; F51 L3b (a) — compat scan: emit a WARN for every type-ref that
        ;; wouldn't resolve under strict per-module scoping (own decls +
        ;; imports + prelude). Lower's short-name fallback still accepts
        ;; these references today; a future consumer-wiring landing will
        ;; retire the fallback and turn each unresolved ref into a hard
        ;; error. Fires only for cross-module implicit references —
        ;; same-module and prelude refs stay silent.
        _                (warn-unscoped-type-refs! files-with-nodes)
        sorted-modules   (topological-sort dep-graph user-modules)
        files-with-nodes (sort-files-by-module-order files-with-nodes
                                                     sorted-modules)
        ;; F51 L3b(b.ii)-3 — retire the concat+reparse shim. Enrich each
        ;; per-file AST with `:resolves-to` provenance metadata via
        ;; `resolve/resolve-references`, then assemble a flat top-node
        ;; stream (synthetic `:module` marker per file for lower's
        ;; module-attribution walk), prepend the prelude AST, and feed
        ;; directly into `compile-parsed-to-file`. The `:resolves-to`
        ;; metadata now survives to lower (previously stripped by the
        ;; reparse) — activating the qualified-lookup path in
        ;; `resolve-decl-lookup` for any future consumer.
        enriched-files   (resolve/resolve-references files-with-nodes)
        user-parsed      (into []
                               (mapcat (fn [{:keys [module nodes]}]
                                         (cons {:node :module
                                                :name (clojure.core/name module)}
                                               nodes)))
                               enriched-files)
        prelude-nodes    (process-prelude-nodes
                           (:target opts prelude/default-target))
        full-parsed      (into (vec prelude-nodes) user-parsed)
        result           (compile-parsed-to-file
                           {:user-parsed user-parsed :full-parsed full-parsed}
                           name out-dir opts)]
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
           :compiled-at    (iso-utc-now)}))
      ;; B7 / F77 — union all reachable plugin modules' manifests into
      ;; one `<binary>.cxc-caps` sidecar. Per-file manifests come from
      ;; `files-with-nodes` (post-import validation); non-plugin files
      ;; contribute nil (filtered by build-caps-map).
      (plugin-manifest/emit-caps!
        (:bin-path result)
        (plugin-manifest/build-caps-map
          (plugin-manifest/extract-all-manifests
            (map :nodes files-with-nodes))
          (:target opts prelude/default-target)
          manifest/compiler-version)))
    result)))
