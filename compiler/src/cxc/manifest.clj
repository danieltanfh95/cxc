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

(defn entry->export
  "Convert a resolve/build-symbol-table entry to its manifest exports
   shape. Kind-specific member details flatten to arrays of maps that
   downstream JSON consumers can traverse without knowing the parse
   AST shapes."
  [{:keys [name kind variant members]}]
  (cond-> {:name    name
           :kind    (clojure.core/name kind)}
    variant
    (assoc :variant (clojure.core/name variant))

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
                         (:cases members))})))

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
   - `:compiled-at`    — ISO-8601 timestamp string
   - `:compiler-version` — optional override (defaults to `compiler-version`)"
  [{:keys [module source-files source-hash symbol-table imports
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
     :exports          (mapv entry->export module-entries)
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
