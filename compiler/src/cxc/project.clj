(ns cxc.project
  "A7 (2026-09-03) — project manifest reader.

   User-facing shape (`<src-dir>/project.cxc`):

       Project(
           name:  \"hello-agent\",
           entry: \"main\",
           deps: [
               Dep(name: \"com.acme.http_claude\",
                   git:  \"https://github.com/acme/http-claude\",
                   tag:  \"v1.2\"),
           ])

   Manifest source is a bare `Project(...)` expression at file scope —
   no `let project = ...` prefix (R3 2026-08-31 retired module-scope
   `let` universally; this namespace consumes the expression form
   directly via `cxc.parse/parse-expression`). The compiler special-
   cases `project.cxc` — it never contributes to the compiled program's
   namespace. Instead, this namespace parses the file and pattern-
   matches the AST to extract the field values as pure Clojure data:

       {:name \"hello-agent\"
        :entry \"main\"
        :deps [{:name \"com.acme.http_claude\"
                :git  \"https://...\"
                :tag  \"v1.2\"} ...]}

   Values must be literal expressions at v1 — `String`, `Int`,
   `List of Dep!`, `Dep(...)` named-ctor with literal args. Any
   `:call` other than `Project`/`Dep`, any `:binop`, any `:ident`
   reference gets a targeted diagnostic (`manifest values must be
   literals at v1; found <kind>`) so v2 can add compile-time
   execution semantics without silently changing v1 behavior.

   Rejects unknown fields on `Project` and `Dep` for the same
   reason — v2 will add fields (targets, codegen hooks, validators),
   and rejecting unknown fields at v1 means v2-shaped manifests fail
   cleanly against a v1 compiler rather than silently ignoring the
   new fields."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.pprint :as pprint]
            [clojure.set :as set]
            [clojure.string :as str]
            [cxc.parse :as parse]))

(set! *warn-on-reflection* true)

(defn manifest-file
  "Return the `project.cxc` file under `src-dir`, or nil if none."
  [^java.io.File src-dir]
  (let [f (io/file src-dir "project.cxc")]
    (when (.isFile f) f)))

(defn- fail!
  [msg]
  (throw (ex-info (str "A7 manifest: " msg)
                  {:kind :cxc-a7-manifest-error})))

(defn- extract-string
  "Value AST → String, else diagnostic."
  [node ctx]
  (case (:node node)
    :lit-string (:value node)
    (fail! (str "field `" ctx "` must be a string literal; got `"
                (:node node) "`."))))

(declare extract-dep-record)

(defn- extract-deps-list
  "List-literal of `Dep(...)` ctor calls → vector of Clojure maps."
  [node _ctx]
  (when-not (= :list (:node node))
    (fail! (str "field `deps` must be a list literal `[Dep(...), ...]`; got `"
                (:node node) "`.")))
  (mapv extract-dep-record (:items node)))

(def ^:private project-fields
  "Field spec for `Project(...)`. Each entry carries the field name,
   the extractor that converts the arg's value AST to a Clojure
   value, and the ctx string handed to the extractor for error
   messages."
  [{:name "name"  :extract extract-string    :ctx "Project.name"}
   {:name "entry" :extract extract-string    :ctx "Project.entry"}
   {:name "deps"  :extract extract-deps-list :ctx "Project.deps"}])

(def ^:private dep-fields
  [{:name "name" :extract extract-string :ctx "Dep.name"}
   {:name "git"  :extract extract-string :ctx "Dep.git"}
   {:name "tag"  :extract extract-string :ctx "Dep.tag"}])

(defn- extract-record
  "Walk a `<CtorName>(field: value, ...)` call AST into a
   keyword-keyed map. `fields` is a field-spec vector (see
   `project-fields` / `dep-fields`). Rejects wrong callee, unnamed
   args, unknown fields, duplicate fields, and missing required
   fields. All fields in the spec are required at v1."
  [ctor-name fields node]
  (when-not (= :call (:node node))
    (fail! (str "expected `" ctor-name "(...)` ctor call; got `"
                (:node node) "`.")))
  (let [callee (:callee node)]
    (when-not (and (= :ident (:node callee))
                   (= ctor-name (:name callee)))
      (fail! (str "expected `" ctor-name "(...)` ctor call; got callee `"
                  (pr-str callee) "`."))))
  (let [by-name  (into {} (map (juxt :name identity)) fields)
        known    (set (keys by-name))
        parsed   (reduce
                   (fn [acc arg]
                     (when-not (= :named (:kind arg))
                       (fail! (str ctor-name "(...) arguments must use named form (`field: value`).")))
                     (let [nm (:name arg)]
                       (when-not (contains? known nm)
                         (fail! (str "unknown " ctor-name " field `" nm "` — known fields: "
                                     (pr-str (sort known))
                                     ". Rejecting unknown fields at v1 so v2-shaped "
                                     "manifests fail cleanly.")))
                       (when (contains? acc nm)
                         (fail! (str "duplicate " ctor-name " field `" nm "`.")))
                       (let [{:keys [extract ctx]} (get by-name nm)]
                         (assoc acc nm (extract (:value arg) ctx)))))
                   {}
                   (:args node))]
    (when-let [missing (seq (set/difference known (set (keys parsed))))]
      (fail! (str ctor-name " fields missing at v1: " (pr-str (sort missing)) ".")))
    (into {} (map (fn [[k v]] [(keyword k) v])) parsed)))

(defn- extract-dep-record [node]
  (extract-record "Dep" dep-fields node))

(defn- extract-project-record [node]
  (extract-record "Project" project-fields node))

(defn read-manifest
  "Parse `<src-dir>/project.cxc` as a single `Project(...)` expression
   and extract its field values as pure Clojure data. Returns nil when
   the file is absent. Throws with a diagnostic when the file exists
   but doesn't conform to the v1 shape."
  [^java.io.File src-dir]
  (when-let [f (manifest-file src-dir)]
    (let [src (slurp f)]
      (extract-project-record (parse/parse-expression src)))))

;; ---------------------------------------------------------------------------
;; Lockfile — pinned SHAs alongside the manifest.
;;
;; Shape: {:cxc-lockfile-version 1
;;         :deps [{:name "..." :git "..." :tag "..." :sha "..."} ...]}
;;
;; Written next to `project.cxc` as `project.lock` (EDN). Compiler
;; consults it on rebuild: if every manifest dep has a matching
;; lockfile entry (same name+git+tag), use the pinned SHA — no
;; network. If any dep is missing or drifted, resolve via `git
;; ls-remote` and re-write the whole lockfile.
;; ---------------------------------------------------------------------------

(def ^:private lockfile-version 1)

(defn lockfile-file
  "Return the `project.lock` file alongside the manifest, whether it
   exists yet or not."
  [^java.io.File src-dir]
  (io/file src-dir "project.lock"))

(defn read-lockfile
  "Return the parsed EDN as a map, or nil if the file is absent /
   malformed / has a version we don't support."
  [^java.io.File src-dir]
  (let [^java.io.File f (lockfile-file src-dir)]
    (when (.isFile f)
      (try
        (let [data (edn/read-string (slurp f))]
          (when (and (map? data)
                     (= lockfile-version (:cxc-lockfile-version data)))
            data))
        (catch Exception _ nil)))))

(defn write-lockfile!
  "Serialize the resolved-deps vector to `<src-dir>/project.lock` as
   pretty-printed EDN. Writes to a sibling `.tmp` file first and
   renames into place so a crash mid-write cannot leave a
   half-serialized EDN."
  [^java.io.File src-dir resolved-deps]
  (let [data                 {:cxc-lockfile-version lockfile-version
                              :deps                 (vec resolved-deps)}
        ^java.io.File target  (lockfile-file src-dir)
        ^java.io.File tmp     (io/file src-dir (str (.getName target) ".tmp"))]
    (spit tmp (with-out-str (pprint/pprint data)))
    (.renameTo tmp target)))

(defn- dep-key
  "Composite identity for a dep: name + git + tag."
  [d]
  [(:name d) (:git d) (:tag d)])

(defn- lockfile-index
  "Build the name+git+tag index from lockfile data. Returns the
   index map or nil when the lockfile does not cover every manifest
   dep. One pass serves both the coverage check and SHA lookup, so
   `resolve-and-populate!` does not rebuild it."
  [manifest-deps lockfile-data]
  (let [idx (into {} (map (juxt dep-key identity)) (:deps lockfile-data))]
    (when (every? #(get idx (dep-key %)) manifest-deps)
      idx)))

(defn- resolve-tag-to-sha
  "`git ls-remote <url> refs/tags/<tag>` → SHA (40 hex chars). Falls
   back to `<url> refs/heads/<tag>` then `<url> <tag>` (any ref) when
   earlier refspecs return nothing — supports SHA-pinned tags,
   branches, and raw SHAs the user pastes into `tag:`."
  [git-url tag]
  (let [try-refspec (fn [ref]
                      (let [{:keys [exit out]} (shell/sh "git" "ls-remote" git-url ref)]
                        (when (zero? exit)
                          (let [trimmed (str/trim out)]
                            (when (seq trimmed)
                              (first (str/split trimmed #"\s+")))))))]
    (or (try-refspec (str "refs/tags/" tag))
        (try-refspec (str "refs/heads/" tag))
        (try-refspec tag)
        (fail! (str "could not resolve `" tag "` against `" git-url
                    "` via git ls-remote. Verify the tag/branch exists "
                    "and the URL is reachable.")))))

(defn- cache-dir
  "Base cache directory: `$HOME/.cxc/cache/`. Created lazily by
   `ensure-cache-populated!`."
  []
  (io/file (System/getProperty "user.home") ".cxc" "cache"))

(defn cache-path-for-sha
  "`$HOME/.cxc/cache/<sha>/` — the mount root for a specific commit."
  [sha]
  (io/file (cache-dir) sha))

(defn- ensure-cache-populated!
  "Clone `<git>` at SHA into `<cache>/<sha>/` if it's not there yet.
   Uses `git clone` + `git checkout <sha>` (since `--branch` doesn't
   accept a raw SHA). Idempotent — a populated cache dir is left
   alone."
  [git-url sha]
  (let [^java.io.File dest (cache-path-for-sha sha)]
    (when-not (.isDirectory dest)
      (.mkdirs ^java.io.File (.getParentFile dest))
      (let [{:keys [exit err]} (shell/sh "git" "clone" "--quiet" git-url (.getPath dest))]
        (when-not (zero? exit)
          (fail! (str "git clone failed for `" git-url "`: " err))))
      (let [{:keys [exit err]} (shell/sh "git" "-C" (.getPath dest)
                                         "checkout" "--quiet" sha)]
        (when-not (zero? exit)
          (fail! (str "git checkout failed for `" sha "` in `"
                      (.getPath dest) "`: " err)))))))

(defn resolve-and-populate!
  "Given a manifest map, return a vector of `{:name :git :tag :sha
   :cache-path}` entries with SHAs resolved (via lockfile if fresh,
   else via `git ls-remote`) and caches populated. Writes the
   lockfile as a side effect when it drifted (or was absent)."
  [^java.io.File src-dir manifest]
  (let [deps     (:deps manifest)
        idx      (lockfile-index deps (read-lockfile src-dir))
        resolved (mapv
                   (fn [d]
                     (let [sha (if idx
                                 (:sha (get idx (dep-key d)))
                                 (resolve-tag-to-sha (:git d) (:tag d)))]
                       (ensure-cache-populated! (:git d) sha)
                       (-> d
                           (assoc :sha sha)
                           (assoc :cache-path (.getPath ^java.io.File (cache-path-for-sha sha))))))
                   deps)]
    (when-not idx
      (write-lockfile! src-dir
                       (mapv #(select-keys % [:name :git :tag :sha]) resolved)))
    resolved))
