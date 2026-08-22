(ns cxc.build
  "Build orchestration — write ObjC source to disk, invoke clang, return
   the resulting binary's path. Assumes clang is on PATH."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [cxc.parse   :as parse]
            [cxc.alias   :as alias]
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
           names    (when (= :from-import (:node n)) (:names n))
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

   Returns {:m-path <path> :bin-path <path> :status <clang-exit-code>
            :stdout <clang-stdout> :stderr <clang-stderr>
            :cache-hit? <bool>}."
  ([source name out-dir]
   (compile-source-to-file source name out-dir {}))
  ([source name out-dir {:keys [target allow-build-modules?]
                         :or {target prelude/default-target}}]
  (let [parse-fn (if allow-build-modules?
                   parse/parse-with-build-modules
                   parse/parse)
        _        (io/make-parents (io/file out-dir "placeholder"))
        parsed   (parse-fn source)
        _        (validate-imports parsed)
        _        (prelude/validate-no-prelude-collisions! parsed)
        prelude-src (prelude/assemble-source target)
        full-parsed (parse-fn (str prelude-src source))
        deprecations (collect-deprecations full-parsed)
        _        (warn-deprecated-uses parsed deprecations)
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
                  {:file f :module module :nodes parsed}))
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
        (mapv (fn [{:keys [file module]}]
                (str "module " (clojure.core/name module) "\n\n"
                     (strip-module-and-import-lines (slurp file))))
              files-with-nodes)
        combined (clojure.string/join "\n\n" with-module-headers)]
    ;; F73 note: user `module` headers are rejected at parse-time; the
    ;; build injects synthetic ones here for per-file provenance and
    ;; passes `:allow-build-modules?` so the re-parse in
    ;; `compile-source-to-file` accepts them via `parse-with-build-modules`.
    (compile-source-to-file combined name out-dir
                            (assoc opts :allow-build-modules? true)))))
