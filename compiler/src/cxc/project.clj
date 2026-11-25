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

(defn read-lockfile-detailed
  "Return `{:status <keyword> …}` describing what the lockfile says.

   hickey-cycle-287 (2026-11-21) — round 3 finding #8 slice.
   Pre-cycle, `read-lockfile` collapsed absent + malformed EDN +
   unsupported version + non-map top-level into a single nil. The
   caller could not tell its lockfile had just become unparseable
   from the ordinary first-build case, so it silently re-resolved
   every pin via `git ls-remote`.

   Possible outcomes:
     - `{:status :absent}` — no file at `<src-dir>/project.lock`.
     - `{:status :malformed :reason <string>}` — unreadable EDN, or
       top-level is not a map.
     - `{:status :unsupported :reason <string> :version <value>}` —
       the lockfile exists but its `:cxc-lockfile-version` is not
       the one this compiler understands.
     - `{:status :ok :data <map>}` — a usable lockfile payload."
  [^java.io.File src-dir]
  (let [^java.io.File f (lockfile-file src-dir)]
    (cond
      (not (.isFile f))
      {:status :absent}

      :else
      (let [slurped (try {:ok (slurp f)}
                         (catch Exception e
                           {:err (.getMessage e)}))]
        (if-let [err (:err slurped)]
          {:status :malformed
           :reason (str "could not read lockfile: " err)}
          (let [parsed (try {:ok (edn/read-string (:ok slurped))}
                            (catch Exception e
                              {:err (.getMessage e)}))]
            (cond
              (:err parsed)
              {:status :malformed
               :reason (str "lockfile EDN did not parse: " (:err parsed))}

              (not (map? (:ok parsed)))
              {:status :malformed
               :reason "lockfile top-level value is not a map"}

              (not= lockfile-version (:cxc-lockfile-version (:ok parsed)))
              {:status  :unsupported
               :reason  (str "cxc-lockfile-version is "
                             (pr-str (:cxc-lockfile-version (:ok parsed)))
                             ", this compiler understands "
                             lockfile-version)
               :version (:cxc-lockfile-version (:ok parsed))}

              :else
              {:status :ok :data (:ok parsed)})))))))

(defn read-lockfile
  "Backward-compat wrapper over `read-lockfile-detailed`. Returns the
   parsed EDN map on `:ok`, nil on every other status. New code
   should call the detailed form so malformed and unsupported
   lockfiles can surface to the user rather than silently reset."
  [^java.io.File src-dir]
  (let [r (read-lockfile-detailed src-dir)]
    (when (= :ok (:status r))
      (:data r))))

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
  "Build the name+git+tag → entry index from lockfile data. Returns
   the full index unconditionally so callers can reconcile per dep
   (keep valid pins, resolve only the gaps).

   hickey-cycle-287 (2026-11-21) — round 3 finding #8 slice. Was
   returning nil on partial coverage, which forced `resolve-and-
   populate!` to re-resolve every existing pin on every manifest
   growth. The reconciliation moved into `resolve-and-populate!`
   where per-dep decisions live."
  [lockfile-data]
  (into {} (keep (fn [d]
                   (when (and (map? d)
                              (string? (:sha d))
                              (seq (:sha d)))
                     [(dep-key d) d])))
        (:deps lockfile-data)))

(defn- clean-git-env
  "Return the parent-process environment with every `GIT_*` key
   removed. When cxc runs inside a git hook or any other context
   where git has populated `GIT_DIR` / `GIT_WORK_TREE` /
   `GIT_INDEX_FILE` / etc., passing those through to child `git`
   invocations redirects the child onto the hook's own repo instead
   of the url the user asked us to clone. The scrub keeps dep fetch
   oblivious to the outer git context."
  []
  (into {} (remove (fn [[k _]] (str/starts-with? (name k) "GIT_")))
        (System/getenv)))

(defn- resolve-tag-to-sha
  "`git ls-remote <url> refs/tags/<tag>` → SHA (40 hex chars). Falls
   back to `<url> refs/heads/<tag>` then `<url> <tag>` (any ref) when
   earlier refspecs return nothing — supports SHA-pinned tags,
   branches, and raw SHAs the user pastes into `tag:`."
  [git-url tag]
  (let [env         (clean-git-env)
        try-refspec (fn [ref]
                      (let [{:keys [exit out]} (shell/sh "git" "ls-remote" git-url ref
                                                        :env env)]
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
  (let [^java.io.File dest (cache-path-for-sha sha)
        env (clean-git-env)]
    (when-not (.isDirectory dest)
      (.mkdirs ^java.io.File (.getParentFile dest))
      (let [{:keys [exit err]} (shell/sh "git" "clone" "--quiet" git-url (.getPath dest)
                                         :env env)]
        (when-not (zero? exit)
          (fail! (str "git clone failed for `" git-url "`: " err))))
      (let [{:keys [exit err]} (shell/sh "git" "-C" (.getPath dest)
                                         "checkout" "--quiet" sha
                                         :env env)]
        (when-not (zero? exit)
          (fail! (str "git checkout failed for `" sha "` in `"
                      (.getPath dest) "`: " err)))))))

(defn resolve-and-populate!
  "Given a manifest map, return a vector of `{:name :git :tag :sha
   :cache-path}` entries with SHAs resolved (via lockfile if fresh,
   else via `git ls-remote`) and caches populated. Writes the
   lockfile when any dep was freshly resolved.

   hickey-cycle-287 (2026-11-21) — round 3 finding #8 slice. Pre-
   cycle, a missing match for one dep discarded the entire index
   and every other pin was re-resolved via a fresh `git ls-remote`.
   Now per-dep reconciliation keeps a valid pin whenever its
   (name, git, tag, sha) match the lockfile and only the changed
   or new deps hit the network.

   A malformed or unsupported-version lockfile raises so the user
   learns their lockfile doesn't parse instead of silently losing
   every pin to a surprise mass re-resolve."
  [^java.io.File src-dir manifest]
  (let [deps      (:deps manifest)
        lockfile  (read-lockfile-detailed src-dir)
        _         (case (:status lockfile)
                    (:malformed :unsupported)
                    (let [^java.io.File lf (lockfile-file src-dir)]
                      (throw (ex-info
                              (str "lockfile at "
                                   (.getPath lf)
                                   " is " (name (:status lockfile)) ": "
                                   (:reason lockfile))
                              {:kind :cxc-user-error
                               :file (.getPath lf)
                               :lockfile-status (:status lockfile)})))
                    nil)
        idx       (lockfile-index (:data lockfile))
        results   (mapv
                   (fn [d]
                     (let [pinned (get idx (dep-key d))
                           sha    (or (:sha pinned)
                                      (resolve-tag-to-sha (:git d) (:tag d)))]
                       (ensure-cache-populated! (:git d) sha)
                       {:dep        (-> d
                                        (assoc :sha sha)
                                        (assoc :cache-path (.getPath ^java.io.File (cache-path-for-sha sha))))
                        :from-lock? (boolean pinned)}))
                   deps)
        resolved  (mapv :dep results)
        drifted?  (or (= :absent (:status lockfile))
                      (some #(not (:from-lock? %)) results)
                      ;; If the lockfile holds deps the manifest no longer has,
                      ;; rewriting prunes them.
                      (not= (set (map dep-key deps))
                            (set (map dep-key (:deps (:data lockfile))))))]
    (when drifted?
      (write-lockfile! src-dir
                       (mapv #(select-keys % [:name :git :tag :sha]) resolved)))
    resolved))
