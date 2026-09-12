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
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [cxc.parse :as parse]))

(def ^:private manifest-filename
  "Reserved filename at the src-dir root. Never compiled as user code."
  "project.cxc")

(defn manifest-file
  "Return the `project.cxc` file under `src-dir`, or nil if none."
  [^java.io.File src-dir]
  (let [f (io/file src-dir manifest-filename)]
    (when (.isFile f) f)))

(def ^:private project-field-set
  #{"name" "entry" "deps"})

(def ^:private dep-field-set
  #{"name" "git" "tag"})

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

(defn- extract-dep-record
  "One `Dep(name: ..., git: ..., tag: ...)` ctor call → Clojure map.
   Validates the callee is exactly `Dep`, args are named, field set
   matches `dep-field-set`, and every value is a string literal."
  [node]
  (when-not (= :call (:node node))
    (fail! (str "expected `Dep(...)` ctor call in `deps` list; got `"
                (:node node) "`.")))
  (let [callee (:callee node)
        _      (when-not (and (= :ident (:node callee))
                              (= "Dep" (:name callee)))
                 (fail! (str "expected `Dep(...)` ctor call; got callee `"
                             (pr-str callee) "`.")))
        args   (:args node)]
    (reduce
      (fn [acc arg]
        (when-not (= :named (:kind arg))
          (fail! "Dep(...) arguments must use named form (`field: value`)."))
        (let [nm (:name arg)]
          (when-not (contains? dep-field-set nm)
            (fail! (str "unknown Dep field `" nm "` — known fields: "
                        (pr-str (sort dep-field-set))
                        ". Rejecting unknown fields at v1 so v2-shaped "
                        "manifests fail cleanly.")))
          (when (contains? acc (keyword nm))
            (fail! (str "duplicate Dep field `" nm "`.")))
          (assoc acc (keyword nm) (extract-string (:value arg) (str "Dep." nm)))))
      {}
      args)))

(defn- extract-deps-list
  "List-literal of `Dep(...)` ctor calls → vector of Clojure maps."
  [node]
  (when-not (= :list (:node node))
    (fail! (str "field `deps` must be a list literal `[Dep(...), ...]`; got `"
                (:node node) "`.")))
  (mapv extract-dep-record (:items node)))

(defn- extract-project-record
  "Top-level `Project(...)` ctor call node → Clojure map with
   `:name`, `:entry`, `:deps` keys."
  [node]
  (when-not (= :call (:node node))
    (fail! (str "value must be a `Project(...)` ctor call; got `"
                (:node node) "`.")))
  (let [callee (:callee node)
        _      (when-not (and (= :ident (:node callee))
                              (= "Project" (:name callee)))
                 (fail! (str "value must be `Project(...)` ctor call; got callee `"
                             (pr-str callee) "`.")))
        args   (:args node)
        seen   (atom {})]
    (doseq [arg args]
      (when-not (= :named (:kind arg))
        (fail! "Project(...) arguments must use named form (`field: value`)."))
      (let [nm (:name arg)]
        (when-not (contains? project-field-set nm)
          (fail! (str "unknown Project field `" nm "` — known fields: "
                      (pr-str (sort project-field-set))
                      ". Rejecting unknown fields at v1 so v2-shaped "
                      "manifests fail cleanly.")))
        (when (contains? @seen nm)
          (fail! (str "duplicate Project field `" nm "`.")))
        (swap! seen assoc nm
               (case nm
                 "name"  (extract-string (:value arg) "Project.name")
                 "entry" (extract-string (:value arg) "Project.entry")
                 "deps"  (extract-deps-list (:value arg))))))
    (doseq [required project-field-set]
      (when-not (contains? @seen required)
        (fail! (str "Project field `" required "` is required at v1."))))
    {:name  (get @seen "name")
     :entry (get @seen "entry")
     :deps  (get @seen "deps")}))

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

(def ^:private lockfile-filename "project.lock")

(defn lockfile-file
  "Return the `project.lock` file alongside the manifest, whether it
   exists yet or not."
  [^java.io.File src-dir]
  (io/file src-dir lockfile-filename))

(defn read-lockfile
  "Return the parsed EDN as a map, or nil if the file is absent /
   malformed / has a version we don't support."
  [^java.io.File src-dir]
  (let [f (lockfile-file src-dir)]
    (when (.isFile f)
      (try
        (let [data (edn/read-string (slurp f))]
          (when (and (map? data)
                     (= lockfile-version (:cxc-lockfile-version data)))
            data))
        (catch Exception _ nil)))))

(defn write-lockfile!
  "Serialize the resolved-deps vector to `<src-dir>/project.lock` as
   pretty-printed EDN. Overwrites any prior lockfile."
  [^java.io.File src-dir resolved-deps]
  (let [data {:cxc-lockfile-version lockfile-version
              :deps                 (vec resolved-deps)}]
    (spit (lockfile-file src-dir)
          (with-out-str (pprint/pprint data)))))

(defn- lockfile-matches?
  "True when the lockfile has entries covering every manifest dep with
   identical name+git+tag triples."
  [manifest-deps lockfile-data]
  (let [locked-by-key (into {}
                            (map (fn [d] [[(:name d) (:git d) (:tag d)] d]))
                            (:deps lockfile-data))]
    (every? (fn [d]
              (get locked-by-key [(:name d) (:git d) (:tag d)]))
            manifest-deps)))

(defn- shell-out
  "Run a shell command, return {:exit :out :err}. Args are strings
   passed verbatim to `ProcessBuilder` (no shell interpolation)."
  [args]
  (let [pb   (ProcessBuilder. (into-array String args))
        _    (.redirectErrorStream pb false)
        proc (.start pb)
        out  (slurp (.getInputStream proc))
        err  (slurp (.getErrorStream proc))
        exit (.waitFor proc)]
    {:exit exit :out out :err err}))

(defn- resolve-tag-to-sha
  "`git ls-remote <url> refs/tags/<tag>` → SHA (40 hex chars). Falls
   back to `<url> <tag>` (any ref) when the tag-refs form returns
   nothing — supports SHA-pinned tags, branches, and raw SHAs the
   user pastes into `tag:`."
  [git-url tag]
  (let [try-refspec (fn [ref]
                      (let [{:keys [exit out]} (shell-out ["git" "ls-remote" git-url ref])]
                        (when (zero? exit)
                          (first (str/split (str/trim out) #"\s+")))))]
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
  (let [dest (cache-path-for-sha sha)]
    (when-not (.isDirectory dest)
      (.mkdirs (.getParentFile dest))
      (let [{:keys [exit err]} (shell-out ["git" "clone" "--quiet" git-url (.getPath dest)])]
        (when-not (zero? exit)
          (fail! (str "git clone failed for `" git-url "`: " err))))
      (let [{:keys [exit err]} (shell-out ["git" "-C" (.getPath dest)
                                            "checkout" "--quiet" sha])]
        (when-not (zero? exit)
          (fail! (str "git checkout failed for `" sha "` in `"
                      (.getPath dest) "`: " err)))))))

(defn resolve-and-populate!
  "Given a manifest map, return a vector of `{:name :git :tag :sha
   :cache-path}` entries with SHAs resolved (via lockfile if fresh,
   else via `git ls-remote`) and caches populated. Writes the
   lockfile as a side effect when it drifted (or was absent)."
  [^java.io.File src-dir manifest]
  (let [deps       (:deps manifest)
        lockfile   (read-lockfile src-dir)
        fresh?     (and lockfile (lockfile-matches? deps lockfile))
        locked-by  (when fresh?
                     (into {}
                           (map (fn [d] [[(:name d) (:git d) (:tag d)] d]))
                           (:deps lockfile)))
        resolved   (mapv
                    (fn [d]
                      (let [sha (if fresh?
                                  (:sha (get locked-by [(:name d) (:git d) (:tag d)]))
                                  (resolve-tag-to-sha (:git d) (:tag d)))]
                        (ensure-cache-populated! (:git d) sha)
                        (-> d
                            (assoc :sha sha)
                            (assoc :cache-path (.getPath (cache-path-for-sha sha))))))
                    deps)]
    (when-not fresh?
      (write-lockfile! src-dir
                       (mapv #(select-keys % [:name :git :tag :sha]) resolved)))
    resolved))