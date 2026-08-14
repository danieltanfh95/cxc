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
  {"std.time"        #{"Duration" "Instant" "Clock" "Ticker"}
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

   Returns {:m-path <path> :bin-path <path> :status <clang-exit-code>
            :stdout <clang-stdout> :stderr <clang-stderr>
            :cache-hit? <bool>}."
  [source name out-dir]
  (let [_        (io/make-parents (io/file out-dir "placeholder"))
        parsed   (parse/parse source)
        _        (validate-imports parsed)
        _        (prelude/validate-no-prelude-collisions! parsed)
        full-parsed (parse/parse (str prelude/source source))
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
         :cache-hit? false}))))

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
   program without redundant/conflicting module headers."
  [src]
  (->> (clojure.string/split-lines src)
       (map (fn [line]
              (let [trimmed (clojure.string/trim line)]
                (cond
                  (clojure.string/starts-with? trimmed "module ") ""
                  (clojure.string/starts-with? trimmed "from ")   ""
                  :else line))))
       (clojure.string/join "\n")))

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

   Same return shape as compile-source-to-file."
  [src-dir name out-dir]
  (let [dir     (io/file src-dir)
        _       (when-not (.isDirectory dir)
                  (throw (ex-info (str "src-dir is not a directory: " src-dir)
                                  {:src-dir src-dir})))
        files   (->> (file-seq dir)
                     (filter #(and (.isFile ^java.io.File %)
                                   (.endsWith (.getName ^java.io.File %) ".cxc")))
                     (sort-by #(.getPath ^java.io.File %)))
        srcs    (mapv slurp files)
        ;; Parse+validate each file independently so import errors
        ;; point at the offending file.
        _       (doseq [[f src] (map vector files srcs)]
                  (try
                    (validate-imports (parse/parse src))
                    (catch clojure.lang.ExceptionInfo e
                      (throw (ex-info (str "in " (.getName ^java.io.File f)
                                           ": " (.getMessage e))
                                      (assoc (ex-data e)
                                             :file (.getPath ^java.io.File f))
                                      e)))))
        combined (clojure.string/join "\n\n"
                                      (map strip-module-and-import-lines srcs))]
    (compile-source-to-file combined name out-dir)))
