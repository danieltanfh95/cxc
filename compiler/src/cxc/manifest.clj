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

   Hand-rolls a small JSON writer for the fixed shape below — avoids
   pulling `org.clojure/data.json` for a bounded output surface.
   Extend `write-json` if the shape grows."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cxc.resolve :as resolve]))

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
;; JSON writer — bounded shape (nil, bool, number, string, vector, map).
;; Keys are stringified as-is; string values escape the JSON control chars
;; (backslash, quote, control chars, unicode passthrough). Output is
;; pretty-printed with 2-space indent for human readability.
;; ---------------------------------------------------------------------------

(defn- escape-json-string [^String s]
  (let [sb (StringBuilder.)]
    (doseq [c s]
      (case c
        \" (.append sb "\\\"")
        \\ (.append sb "\\\\")
        \newline (.append sb "\\n")
        \return  (.append sb "\\r")
        \tab     (.append sb "\\t")
        \backspace (.append sb "\\b")
        \formfeed  (.append sb "\\f")
        (if (< (int c) 0x20)
          (.append sb (format "\\u%04x" (int c)))
          (.append sb c))))
    (.toString sb)))

(declare write-json)

(defn- indent [n]
  (apply str (repeat n "  ")))

(defn- write-collection [open close entry-writer items depth]
  (if (empty? items)
    (str open close)
    (let [inner  (indent (inc depth))
          outer  (indent depth)
          parts  (mapv #(str inner (entry-writer % (inc depth))) items)]
      (str open "\n"
           (str/join ",\n" parts) "\n"
           outer close))))

(defn write-json
  "Render `x` as a JSON string. Depth argument is the current indent
   depth (0 at top level). Public so callers can compose custom
   sub-shapes if needed; `emit-manifest!` calls it with depth 0."
  ([x] (write-json x 0))
  ([x depth]
   (cond
     (nil? x)     "null"
     (true? x)    "true"
     (false? x)   "false"
     (number? x)  (str x)
     (keyword? x) (str "\"" (escape-json-string (name x)) "\"")
     (string? x)  (str "\"" (escape-json-string x) "\"")
     (map? x)
     (write-collection "{" "}"
                       (fn [[k v] d]
                         (str "\"" (escape-json-string (name k)) "\": "
                              (write-json v d)))
                       (seq x) depth)
     (sequential? x)
     (write-collection "[" "]"
                       (fn [v d] (write-json v d))
                       (seq x) depth)
     :else
     (throw (ex-info (str "cannot JSON-serialize: " (pr-str x))
                     {:kind :cxc-f53-json-unserializable
                      :value x})))))

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

(defn- metadata-value->export
  "Convert an annotation argument expression into its JSON-facing value.
   Literal metadata becomes the corresponding JSON scalar; lists recurse;
   bare identifiers/type references become names. For expression shapes
   without a scalar interpretation, retain the AST map as opaque metadata
   rather than rejecting open-ended user keys."
  [value]
  (if-not (map? value)
    value
    (case (:node value)
      :lit-string (:value value)
      :lit-char   (:value value)
      :lit-int    (:value value)
      :lit-float  (:value value)
      :lit-bool   (:value value)
      :lit-nil    nil
      :ident      (:name value)
      :type-ref   (:name value)
      :list       (mapv metadata-value->export (:items value))
      :neg        (let [inner (metadata-value->export (:expr value))]
                    (if (number? inner) (- inner) value))
      value)))

(defn- metadata-annotations->export
  "Fold parsed `@metadata(...)` annotations into one deterministic map.
   Named arguments become user-facing fields; repeated fields use the
   later source value. Positional arguments are preserved under `$args`
   so the generic L3a annotation grammar remains lossless even though the
   normal metadata surface uses named fields."
  [annotations]
  (reduce
   (fn [metadata annotation]
     (reduce
      (fn [m {:keys [kind name value]}]
        (if (= :named kind)
          (assoc m (keyword name) (metadata-value->export value))
          (update m :$args (fnil conj [])
                  (metadata-value->export value))))
      metadata
      (:args annotation)))
   (sorted-map)
   annotations))

(defn- normalize-user-metadata
  "Accept either raw parsed metadata annotations or an already-materialized
   metadata map. The map form keeps `build-manifest` useful to tooling/tests
   that construct compiler and user metadata independently of the parser."
  [metadata]
  (cond
    (map? metadata)
    (into (sorted-map)
          (map (fn [[k v]] [k (metadata-value->export v)]))
          metadata)

    (sequential? metadata)
    (metadata-annotations->export metadata)

    :else
    (sorted-map)))

(defn entry->export
  "Convert a resolve/build-symbol-table entry to its manifest exports
   shape. Kind-specific member details flatten to arrays of maps that
   downstream JSON consumers can traverse without knowing the parse
   AST shapes. User `@metadata(...)` fields are additive and live under
   `:metadata`, alongside (without overriding) compiler-derived fields."
  ([entry]
   (entry->export entry nil))
  ([{:keys [name kind variant members metadata-annotations] :as entry}
    user-metadata]
   (let [metadata-input (cond
                          (some? user-metadata) user-metadata
                          (seq metadata-annotations) metadata-annotations
                          (contains? entry :metadata) (:metadata entry)
                          :else nil)
         metadata       (normalize-user-metadata metadata-input)]
     (cond-> {:name    name
              :kind    (clojure.core/name kind)}
       variant
       (assoc :variant (clojure.core/name variant))

       (some? metadata-input)
       (assoc :metadata metadata)

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
       (assoc :members
              {:cases (mapv (fn [c] (if (map? c) (:name c) c))
                            (:cases members))})))))

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
   - `:user-metadata`  — optional declaration-name → parsed metadata
                        annotations/materialized field map. Symbol-table
                        metadata is used when no explicit entry is supplied
   - `:imports`        — vector of `:from-import` / `:import` AST nodes
                        drawn from every file in this module
   - `:compiled-at`    — ISO-8601 timestamp string
   - `:compiler-version` — optional override (defaults to `compiler-version`)"
  [{:keys [module source-files source-hash symbol-table user-metadata imports
           compiled-at compiler-version]
    :or {compiler-version compiler-version}}]
  (let [module-entries (->> (vals symbol-table)
                            (filter #(= module (:module %)))
                            (sort-by :name))]
    {:$schema          schema-tag
     :module           (clojure.core/name module)
     :compiler-version compiler-version
     :compiled-at      compiled-at
     :source-hash      source-hash
     :source-files     (vec source-files)
     :exports          (mapv (fn [entry]
                               (entry->export
                                entry
                                (when (contains? user-metadata (:name entry))
                                  (get user-metadata (:name entry)))))
                             module-entries)
     :imports          (mapv import->manifest imports)}))

(defn emit-manifest!
  "Serialize a manifest map to JSON and write to
   `<out-dir>/<module>.cxc-manifest.json`. Returns the written File."
  [^java.io.File out-dir module manifest]
  (let [f (io/file out-dir (str (clojure.core/name module)
                                ".cxc-manifest.json"))]
    (io/make-parents f)
    (spit f (write-json manifest))
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
                          [(clojure.core/name m)
                           {:depends-on  (mapv clojure.core/name
                                               (sort (get graph m #{})))
                            :depended-by (mapv clojure.core/name
                                               (sort (get reversed m #{})))}])
        modules-map     (into (sorted-map)
                              (map module-entry) all-modules)]
    {:$schema           dep-graph-schema-tag
     :compiled-at       compiled-at
     :topological-order (into (mapv clojure.core/name prelude-targets)
                              (mapv clojure.core/name sorted-modules))
     :modules           modules-map}))

(defn emit-dep-graph!
  "Serialize a dep-graph map to JSON and write to
   `<out-dir>/cxc-deps.json`. Returns the written File."
  [^java.io.File out-dir dep-graph]
  (let [f (io/file out-dir "cxc-deps.json")]
    (io/make-parents f)
    (spit f (write-json dep-graph))
    f))
