(ns cxc.manifest
  "F53 L1 — per-module JSON manifest emission.

   For every user module compiled by `compile-directory-to-file`,
   write `<out-dir>/<module>.cxc-manifest.json` capturing:
   - module name, source files, source hash (sha256)
   - exports (public declarations with kind, variant, member shape)
   - imports (from-import and import entries)
   - compiled-at timestamp, compiler version

   The manifest is the consumer-facing artifact for downstream tools
   (docs, LSP, incremental compile, dep-graph). Schema is `cxc-manifest/v1`.

   Serialization goes through `clojure.data.json/write-str` with
   `:indent true` for 2-space pretty-print. Keys reach this layer as
   unqualified keywords; qualified-keyword namespaces would silently
   drop under `name`, so build-manifest / build-dep-graph are
   responsible for keeping keys unqualified."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def schema-tag "cxc-manifest/v1")

(def compiler-version
  "F53 L1 — pinned string. Bump on manifest schema changes (v1 → v2)
   and on compiler releases that change lowering semantics visible in
   exports. Kept in-source rather than reading from a properties file
   to keep the compile pipeline self-contained."
  "0.11.0")

;; ---------------------------------------------------------------------------
;; Symbol-table entry → manifest export shape
;; ---------------------------------------------------------------------------

(defn- field->export [f]
  {:name (:name f)
   :type (:type f)})

(defn- observable->export [obs]
  {:name    (:name obs)
   :params  (mapv (fn [p] {:name (:name p) :type (:type p)})
                  (:params obs))
   :returns (:return obs)})

(defn- transition->export [t]
  {:source (:source t)
   :message (:msg t)
   :target (:target t)})

;; User-metadata folding retired 2026-08-30 alongside the annotation-
;; grammar-retirement wave. `@metadata(...)` no longer parses, so the
;; folding fns had no upstream producer and are deleted. Manifest
;; emission is now compiler-derived facts only (name, kind, variant,
;; members). When a replacement user-metadata surface lands
;; (e.g. `meta = Meta(...)` module- or def-scope binding), the reader
;; re-enters here as a resolved-value reader, not an AST-annotation
;; folder. See decisions-frozen-2026-08-30.md.

(defn entry->export
  "Convert a resolve/build-symbol-table entry to its manifest exports
   shape. Kind-specific member details flatten to arrays of maps that
   downstream JSON consumers can traverse without knowing the parse
   AST shapes."
  [{entry-name :name :keys [kind variant members]}]
  (cond-> {:name entry-name
           :kind (name kind)}
    variant
    (assoc :variant (name variant))

    (= :record kind)
    (assoc :members
           {:fields      (mapv field->export (:fields members))
            :observables (mapv observable->export (:observables members))})

    (= :def-atom kind)
    (assoc :members
           {:fields       (mapv field->export (:data-fields members))
            :observables  (mapv observable->export (:observables members))
            :transitions  (mapv transition->export (:transitions members))
            :use-caps     (mapv (fn [c] {:name (:name c) :type (:type c)})
                                (:use-caps members))})

    (= :enum kind)
    (assoc :members {:cases (vec (:cases members))})))

;; ---------------------------------------------------------------------------
;; Manifest construction
;; ---------------------------------------------------------------------------

(defn- import->manifest [n]
  (case (:node n)
    :from-import
    {:kind  "from-import"
     :from  (:from n)
     :names (mapv (fn [e] {:name (:name e) :as (:as e)}) (:names n))}
    :import
    {:kind "import"
     :from (:from n)
     :as   (:as n)}))

(defn build-manifest
  "Pure fn — produce the Clojure map representing a module's manifest.
   Callers hand in:
   - `:module`         — module keyword (e.g. `:foo.bar`)
   - `:source-files`   — vector of source path strings (sorted)
   - `:source-hash`    — sha256 hex of concatenated sources for this module
   - `:symbol-table`   — full symbol table (filtered to this module below)
   - `:imports`        — vector of `:from-import` / `:import` AST nodes
                        drawn from every file in this module
   - `:compiled-at`    — ISO-8601 timestamp string"
  [{:keys [module source-files source-hash symbol-table imports
           compiled-at]}]
  (let [module-entries (->> (vals symbol-table)
                            (filter #(= module (:module %)))
                            (sort-by :name))]
    {:$schema          schema-tag
     :module           (name module)
     :compiler-version compiler-version
     :compiled-at      compiled-at
     :source-hash      source-hash
     :source-files     (vec source-files)
     :exports          (mapv entry->export module-entries)
     :imports          (mapv import->manifest imports)}))

(defn emit-manifest!
  "Serialize a manifest map to JSON and write to
   `<out-dir>/<module>.cxc-manifest.json`. Returns the written File."
  [^java.io.File out-dir module manifest]
  (let [f (io/file out-dir (str (name module)
                                ".cxc-manifest.json"))]
    (io/make-parents f)
    (spit f (json/write-str manifest :indent true :escape-slash false))
    f))

;; ---------------------------------------------------------------------------
;; F53 L2 — program-wide dependency graph artifact (cxc-deps.json).
;;
;; Consumed by doc generators (who imports Duration?), impact-analysis
;; tooling (which modules rebuild when foo.util changes?), and F53 L3
;; incremental compile cache keying.
;; ---------------------------------------------------------------------------

(def dep-graph-schema-tag "cxc-deps/v1")

(defn- reverse-edges
  "Given `{module → #{deps}}`, produce `{module → #{depended-by}}`."
  [graph]
  (reduce (fn [acc [m deps]]
            (reduce (fn [a d] (update a d (fnil conj #{}) m))
                    acc deps))
          {} graph))

(defn build-dep-graph
  "Pure fn — produce the Clojure map for the program-wide dep graph.

   Inputs:
   - `:graph`             — `{module-kw → #{imported-module-kws}}`
                            (as produced by `build/module-dependency-graph`)
   - `:user-modules`      — set of user module keywords
   - `:sorted-modules`    — user modules in dep order (topological-sort output)
   - `:compiled-at`       — ISO-8601 timestamp string

   The `modules` map covers every user module plus every prelude module
   that appears as a target of some import. Prelude modules carry an
   empty `depends-on` (prelude has no cross-prelude edges we track);
   their `depended-by` lists the user modules that consume them.

   The `topological-order` puts prelude modules first (sorted by name)
   then user modules in dep order — matches the compilation order the
   build uses."
  [{:keys [graph user-modules sorted-modules compiled-at]}]
  (let [reversed        (reverse-edges graph)
        prelude-targets (into (sorted-set)
                              (remove user-modules
                                      (mapcat val graph)))
        all-modules     (into (sorted-set)
                              (concat user-modules prelude-targets))
        module-entry    (fn [m]
                          [(name m)
                           {:depends-on  (mapv name
                                               (sort (get graph m #{})))
                            :depended-by (mapv name
                                               (sort (get reversed m #{})))}])
        modules-map     (into (sorted-map)
                              (map module-entry) all-modules)]
    {:$schema           dep-graph-schema-tag
     :compiled-at       compiled-at
     :topological-order (into (mapv name prelude-targets)
                              (mapv name sorted-modules))
     :modules           modules-map}))

(defn emit-dep-graph!
  "Serialize a dep-graph map to JSON and write to
   `<out-dir>/cxc-deps.json`. Returns the written File."
  [^java.io.File out-dir dep-graph]
  (let [f (io/file out-dir "cxc-deps.json")]
    (io/make-parents f)
    (spit f (json/write-str dep-graph :indent true :escape-slash false))
    f))
