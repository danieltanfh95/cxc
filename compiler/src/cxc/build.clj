(ns cxc.build
  "Build orchestration — write ObjC source to disk, invoke clang, return
   the resulting binary's path. Assumes clang is on PATH."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [cxc.parse   :as parse]
            [cxc.lower   :as lower]
            [cxc.emit    :as emit]
            [cxc.prelude :as prelude]))

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

;; Layer 6 Step 11 — deprecation walker. `deprecated: "msg"` inside
;; a def-atom body or record body attaches a message to the enclosing
;; declaration. Use-site walker below finds every `:ident` /
;; `:type-ref` / `:call` reference to a deprecated name and emits a
;; warning to stderr with the migration message.

(defn- collect-deprecations
  "Walk top-level nodes; return map of {name → message} for every
   def-atom or record carrying a `:deprecated` section."
  [top-nodes]
  (into {}
        (keep (fn [n]
                (when (contains? #{:def-atom :record} (:node n))
                  (let [body (or (:body n) (:sections n) [])
                        dep  (first (filter #(= :deprecated (:node %)) body))]
                    (when dep
                      [(:name n) (:message dep)]))))
              top-nodes)))

(defn- warn-deprecated-uses
  "Walk the parsed AST looking for references to any name in the
   `deprecations` map (built by collect-deprecations). Prints one
   warning per reference to *err*. Refs checked: `:ident` (name
   uses), `:type-ref` (type annotations), `:call` (constructor
   calls via ident callee). Definition sites in the deprecated def
   itself are skipped — a deprecated Foo referencing its own name
   in its methods doesn't self-warn."
  [top-nodes deprecations]
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
         n)))))

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
  "Walk parsed top-nodes, check every `:from-import` entry: (a) the
   from-module exists in stdlib-manifest, (b) each imported name is
   exported by it. Returns nil on success, throws with a specific
   diagnostic on the first bad import."
  [top-nodes]
  (doseq [n top-nodes
          :when (= :from-import (:node n))]
    (let [from  (:from n)
          names (:names n)
          exported (get stdlib-manifest from)]
      (when-not exported
        (throw (ex-info (str "unknown module `" from "` in `from " from
                             " import ...` — check `stdlib-manifest` in "
                             "cxc.build for registered modules")
                        {:kind :unknown-module :module from})))
      (doseq [nm names]
        (when-not (contains? exported nm)
          (throw (ex-info (str "module `" from "` does not export `" nm
                               "`; available: " (sort exported))
                          {:kind :unknown-import :module from :name nm})))))))

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

   Returns {:m-path <path> :bin-path <path> :status <clang-exit-code>
            :stdout <clang-stdout> :stderr <clang-stderr>
            :cache-hit? <bool>}."
  ([source name out-dir]
   (compile-source-to-file source name out-dir {}))
  ([source name out-dir {:keys [target] :or {target prelude/default-target}}]
  (let [_        (io/make-parents (io/file out-dir "placeholder"))
        parsed   (parse/parse source)
        _        (validate-imports parsed)
        _        (prelude/validate-no-prelude-collisions! parsed)
        prelude-src (prelude/assemble-source target)
        full-parsed (parse/parse (str prelude-src source))
        deprecations (collect-deprecations full-parsed)
        _        (warn-deprecated-uses parsed deprecations)
        m-src    (-> full-parsed
                     prelude/rewrite-prelude-names
                     lower/lower
                     emit/emit)
        m-file   (io/file out-dir (str name ".m"))
        bin-file (io/file out-dir name)
        _        (spit m-file m-src)
        ;; Browse-cache: always keep the last emitted .m for each name.
        _        (copy-file m-file (io/file cache-last-dir (str name ".m")))
        sha      (sha256-hex m-src)
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

   F51 L1 (2026-08-18) — `module` headers are now rejected at compile-
   directory entry (see `reject-user-module-header!`); the strip here
   only sees `from` lines in practice. Kept as `strip-module-and-...`
   until L3 removes the flat-namespace concatenation shim entirely."
  [src]
  (->> (clojure.string/split-lines src)
       (map (fn [line]
              (let [trimmed (clojure.string/trim line)]
                (cond
                  (clojure.string/starts-with? trimmed "module ") ""
                  (clojure.string/starts-with? trimmed "from ")   ""
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

(defn- reject-user-module-header!
  "F51 L1 — user-authored `module <name>` header is retired. Modules
   are derived from directory structure (§7.2 refactor); a per-file
   header is redundant and a source of drift. Fires a targeted
   `:cxc-f51-module-header-removed` naming the file's derived module
   and pointing at the migration (delete the line)."
  [^java.io.File file parsed-nodes derived-module]
  (when-let [m (first (filter #(= :module (:node %)) parsed-nodes))]
    (throw (ex-info
            (str "F51 L1: `module " (:name m) "` header in " (.getName file)
                 " is retired — cxc modules are derived from directory "
                 "structure (spec §7.2). This file's module is `"
                 (name derived-module) "` based on its path relative to "
                 "src-dir. Delete the `module` line.")
            {:kind :cxc-f51-module-header-removed
             :file (.getPath file)
             :header-name (:name m)
             :derived-module derived-module}))))

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
        srcs    (mapv slurp files)
        ;; F51 L1 — parse each file separately, tag with derived
        ;; module, reject user-authored `module` headers, and check
        ;; import validity.
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
                  (reject-user-module-header! f parsed module)
                  (try
                    (validate-imports parsed)
                    (catch clojure.lang.ExceptionInfo e
                      (throw (ex-info (str "in " (.getName f)
                                           ": " (.getMessage e))
                                      (assoc (ex-data e)
                                             :file (.getPath f))
                                      e))))
                  {:file f :module module :nodes parsed}))
              (map vector files srcs))
        _       (reject-duplicate-declarations-per-module! files-with-nodes)
        ;; F51 L1 — prepend a synthetic `module <derived>` line to
        ;; each source string before concatenation. The parser sees
        ;; it as a normal `:module` node; `resolve/build-symbol-table`
        ;; walks the stream stateful and attributes downstream decls
        ;; to that module. `strip-module-and-import-lines` still runs
        ;; to blank `from ...` (the flat-namespace concatenation shim
        ;; stays until F51 L3 lands scoped resolution).
        with-module-headers
        (mapv (fn [[{:keys [module]} src]]
                (str "module " (clojure.core/name module) "\n\n"
                     (strip-module-and-import-lines src)))
              (map vector files-with-nodes srcs))
        combined (clojure.string/join "\n\n" with-module-headers)]
    (compile-source-to-file combined name out-dir opts))))
