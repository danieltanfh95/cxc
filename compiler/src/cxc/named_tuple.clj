(ns cxc.named-tuple
  "F15 L2.a — canonical mangled struct name for a named-tuple shape.

   Per `decisions-frozen-2026-08-23.md` §13 (D4):
   - Every unique shape maps to exactly one canonical name (injective).
   - Fields sort alphabetically before mangling so
     `(y: Int, x: Int)` and `(x: Int, y: Int)` share the struct —
     structural equivalence at the type level maps to identity at
     the C level.
   - Widths preserved (`Int32` and `Int64` are distinct manglings).
   - Non-nil (`T!`) participates as a `Nn_` prefix on the inner
     type mangling.

   Scope of L2.a: flat named tuples whose fields are primitives (with
   optional non-nil suffix).
   L2.b (2026-08-24): nested named-tuple fields via `Nt<count>_...`
   prefix. Count is decimal digits so parsers know exactly how many
   pairs to consume; nesting recurses unboundedly.
   L2.b.ii (2026-08-24): single-arg parametric fields via
   `Of<arity>_<outer>_<inner-mangling>` prefix. Arity is decimal
   digits equal to the number of type args; parser consumes exactly
   that many bounded type manglings after the outer name.
   L2.b.iii (2026-08-24): arity 2+ parametric fields (`Map of
   (K, V)`, `Pair of (A, B)`, …) share the same `Of<arity>_<outer>_`
   prefix but consume N bounded type manglings, one per element of
   the tuple-shaped inner. Each element recurses through mangle-type
   independently.

   The mangled name is a public ABI symbol (FFI, `nm` output, crash
   traces show it). Do not change the scheme in a compatible-looking
   way — bump a version prefix (`_Cxc_NT2_...`) if the encoding ever
   needs to change."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]))

(defn- fail-nyi! [what node]
  (throw (ex-info (str "canonical-named-tuple-name: " what)
                  {:kind :not-yet-implemented
                   :what what
                   :node node})))

(declare mangle-type validate-field-name!)

(defn- reserved-type-name-prefix? [^String nm]
  (or (str/starts-with? nm "Nn_")
      (str/starts-with? nm "_Cxc_NT")
      (re-matches #"Nt\d+.*" nm)
      (re-matches #"Of\d+.*" nm)))

(defn- mangle-nested-tuple-body
  "Nested-tuple body: `<count>_<f1>_<T1>_<f2>_<T2>_...` with fields
   sorted alphabetically (same discipline as the top-level fn).
   Count is decimal digits so a downstream parser knows exactly how
   many pairs to consume before the surrounding context resumes.
   Recurses through `mangle-type`."
  [fields]
  (let [sorted (sort-by :name fields)
        parts  (mapcat (fn [{:keys [name type]}]
                         [(validate-field-name! name type)
                          (mangle-type type)])
                       sorted)]
    (str (count sorted) "_" (str/join "_" parts))))

(defn- mangle-type
  "Mangle a single field's type into the canonical suffix form. Returns
   a C-identifier-safe string. Throws for unsupported shapes so the
   caller sees a targeted diagnostic rather than a corrupted mangling.

   Coverage:
   - Primitive (`:type-ref`): bare type name (`Int`).
   - Non-nil primitive (`:type-nonnil`): `Nn_<inner>` prefix.
   - Nested named-tuple (`:type-named-tuple`): `Nt<count>_<segments>`
     (L2.b extension — `<count>` is decimal digits so nested manglings
     unambiguously bound their own segments within a parent context).

   NYI: parametric types (`:type-of`) — L2.b.ii extends."
  [type-node]
  (case (:node type-node)
    :type-ref
    (let [nm (:name type-node)]
      (when (reserved-type-name-prefix? nm)
        (fail-nyi! (str "type name `" nm "` collides with a reserved "
                        "F15 mangling prefix (`Nn_` / `_Cxc_NT` / "
                        "`Nt<digits>`); rename the type or extend the "
                        "mangling scheme in a follow-on sub-landing")
                   type-node))
      nm)

    :type-nonnil
    (str "Nn_" (mangle-type (:inner type-node)))

    :type-named-tuple
    (let [fields (:fields type-node)]
      (when (empty? fields)
        (fail-nyi! "nested empty named-tuple shape has no canonical form"
                   type-node))
      (str "Nt" (mangle-nested-tuple-body fields)))

    :type-of
    (let [outer (:outer type-node)
          inner (:inner type-node)]
      (when-not (and (string? outer) (pos? (count outer)))
        (fail-nyi! "parametric type `:outer` must be a non-empty string"
                   type-node))
      (when (str/includes? outer "_")
        (fail-nyi! (str "parametric outer name `" outer "` cannot "
                        "contain `_` in F15 mangling (would confuse "
                        "the segment separator); extend the scheme in "
                        "a follow-on sub-landing")
                   type-node))
      (when (reserved-type-name-prefix? outer)
        (fail-nyi! (str "parametric outer name `" outer "` collides "
                        "with a reserved F15 mangling prefix")
                   type-node))
      (when-not (map? inner)
        (fail-nyi! (str "parametric type `" outer "` requires a "
                        "single :inner type node or a :type-tuple; "
                        "got `" (pr-str inner) "`")
                   type-node))
      (if (= :type-tuple (:node inner))
        ;; Arity 2+ (or 1 written as a tuple) — inner is a :type-tuple
        ;; whose :types vector holds each argument. Recurse per element.
        (let [types (:types inner)]
          (when (empty? types)
            (fail-nyi! (str "parametric type `" outer "` with empty "
                            "type-tuple inner is degenerate")
                       type-node))
          (str "Of" (count types) "_" outer
               "_" (str/join "_" (map mangle-type types))))
        ;; Arity 1 with the plain single-inner shape (L2.b.ii form).
        (str "Of1_" outer "_" (mangle-type inner))))

    (fail-nyi! (str "unsupported field type node kind `"
                    (:node type-node) "` in F15 L2.b mangling")
               type-node)))

(defn- validate-field-name! [nm original-node]
  (when-not (and (string? nm) (pos? (count nm)))
    (fail-nyi! (str "field name must be a non-empty string; got `"
                    (pr-str nm) "`")
               original-node))
  (when (or (str/includes? nm "_")
            (str/starts-with? nm "Nn"))
    (fail-nyi! (str "field name `" nm "` cannot contain `_` or "
                    "start with `Nn` in F15 L2.a MVP mangling "
                    "(would collide with reserved markers); "
                    "extend the scheme in a follow-on sub-landing")
               original-node))
  nm)

(defn canonical-named-tuple-name
  "F15 L2.a — canonical mangled struct name for a named-tuple shape.

   Input: a `:type-named-tuple` node (as produced by parse):
     {:node   :type-named-tuple
      :fields [{:name \"x\" :type <type-node>}
               {:name \"y\" :type <type-node>}
               ...]}

   Output: a C-identifier-safe string of the form
     `_Cxc_NT_<field1>_<TypeMangling1>_<field2>_<TypeMangling2>_...`
   where fields are alphabetically sorted (regardless of the declared
   order at the use-site) so structurally-identical shapes share the
   name.

   Throws `:not-yet-implemented` for shapes outside the L2.a MVP
   coverage (nested named tuples, parametric types, reserved name
   collisions) so the caller surfaces a targeted diagnostic."
  ^String [type-node]
  (when-not (= :type-named-tuple (:node type-node))
    (fail-nyi! (str "expected :type-named-tuple node; got `"
                    (:node type-node) "`")
               type-node))
  (let [fields (:fields type-node)]
    (when (empty? fields)
      (fail-nyi! "empty named-tuple shape has no canonical form"
                 type-node))
    (let [sorted (sort-by :name fields)
          parts  (mapcat (fn [{:keys [name type]}]
                           [(validate-field-name! name type)
                            (mangle-type type)])
                         sorted)]
      (str "_Cxc_NT_" (str/join "_" parts)))))

;; ---------------------------------------------------------------------------
;; F15 L2.c.i — collect every distinct named-tuple shape used in an AST.
;;
;; Foundation for L2.c.ii (typedef emit) and L2.c.iii (lower-time
;; wire-in). Pure walk: `clojure.walk/postwalk` reaches every
;; `:type-named-tuple` node regardless of where it appears (field
;; type on a record, method-param type, receive-clause payload type,
;; nested inside another named-tuple's field, nested inside a
;; parametric's inner, etc.). Each unique mangled name maps to the
;; representative shape node — later passes use the shape to emit a
;; struct typedef.
;;
;; Nested named-tuples surface as separate collection entries because
;; the walker visits inner nodes independently of the outer's mangling.
;; L2.c.ii will decide whether nested typedefs are emitted-and-shared
;; or inlined into the outer struct — this collector doesn't take a
;; stance.
;;
;; Shapes that fail to mangle (reserved-name collisions, invalid
;; field names, empty forms) surface as the underlying `fail-nyi!`
;; exception from `canonical-named-tuple-name`. Better to fail loudly
;; here than to silently skip a shape the eventual typedef pass would
;; need.
;; ---------------------------------------------------------------------------

(defn collect-shapes
  "Walk `ast` and return `{mangled-name → shape-node}` for every
   distinct `:type-named-tuple` node encountered. Returned map is a
   `sorted-map` so consumers (typedef emit, tests) see deterministic
   ordering across runs.

   Duplicate shapes (same mangled name, different AST occurrences)
   collapse to a single entry — the first-visited shape node
   represents the shape; downstream typedef emit uses only the
   mangled name + field list so the specific representative doesn't
   matter."
  [ast]
  (let [acc (volatile! (transient {}))]
    (walk/postwalk
     (fn [x]
       (when (and (map? x) (= :type-named-tuple (:node x)))
         (let [nm (canonical-named-tuple-name x)]
           (when-not (contains? @acc nm)
             (vswap! acc assoc! nm x))))
       x)
     ast)
    (into (sorted-map) (persistent! @acc))))

;; ---------------------------------------------------------------------------
;; F15 L2.c.ii — emit C typedef declarations for collected shapes.
;;
;; Consumes a `{mangled-name → shape-node}` map (as produced by
;; `collect-shapes`) and a caller-supplied `primitive->c-type` fn so
;; this ns stays free of lower.clj's primitive-mapping logic. Emits
;; one `typedef struct { ... } <mangled>;` per shape, sorted by
;; mangled name (deterministic diffs).
;;
;; MVP scope (this sub-landing): fields whose type is a primitive
;; (`:type-ref`) or non-nil primitive (`:type-nonnil` wrapping a
;; primitive). Nested named-tuples and parametric types as FIELDS
;; throw NYI — extend in follow-on sub-landings once the "inline vs
;; reference" decision for nested typedefs is settled.
;;
;; No wiring into emit.clj yet. Downstream L2.c.iii wires the
;; typedef block into the ObjC program prologue and updates
;; lower/emit to translate `:type-named-tuple` param types to the
;; mangled name.
;; ---------------------------------------------------------------------------

(defn- field-c-type
  "Translate a field's type node to a C-type string.

   Coverage:
   - `:type-ref` — primitive (must have a C-type mapping via
     `primitive->c-type`).
   - `:type-nonnil` — recurse on `:inner`. Non-nil is orthogonal to
     C representation at the struct-field level (the runtime nil-check
     fires at param-binding / method-return sites, not on read of a
     value-typed field).
   - `:type-named-tuple` — F15 L2.c.ii-b (2026-08-24) — nested
     named-tuple fields REFERENCE the nested struct by its canonical
     mangled name (per D4's `structural equivalence at the type level
     maps to identity at the C level` — inlining would create
     multiple non-equivalent C types for one structural type). The
     caller must ensure the nested shape appears in the typedef
     stream before its containers; `emit-typedefs` handles the
     ordering via topological sort.
   - `:type-of` — parametric field types not yet supported; extend
     when the parametric container ABI decision lands."
  [primitive->c-type type-node]
  (case (:node type-node)
    :type-ref
    (let [nm (:name type-node)
          c  (primitive->c-type nm)]
      (when-not c
        (fail-nyi! (str "primitive type `" nm "` has no C-type mapping "
                        "(caller must supply it via `primitive->c-type`)")
                   type-node))
      c)

    :type-nonnil
    (field-c-type primitive->c-type (:inner type-node))

    :type-named-tuple
    (canonical-named-tuple-name type-node)

    :type-of
    (fail-nyi! (str "parametric field types (`" (:outer type-node)
                    " of ...`) not supported in F15 L2.c.ii typedef "
                    "emit — extend in a follow-on sub-landing")
               type-node)

    (fail-nyi! (str "unsupported field type node kind `"
                    (:node type-node) "` in F15 L2.c.ii typedef emit")
               type-node)))

(defn- shape->typedef-string [primitive->c-type mangled-name shape-node]
  (let [fields (sort-by :name (:fields shape-node))
        lines  (map (fn [{:keys [name type]}]
                      (str "    " (field-c-type primitive->c-type type)
                           " " name ";"))
                    fields)]
    (str "typedef struct {\n"
         (str/join "\n" lines)
         "\n} " mangled-name ";")))

(defn- shape-dependencies-in-map
  "Return the set of mangled names in `shapes` that appear as nested
   `:type-named-tuple` field types in `shape-node`. External refs
   (not in `shapes`) filtered out — `collect-shapes` should have
   included them, but defensive filtering prevents infinite waits on
   unresolvable deps."
  [shapes shape-node]
  (let [deps (volatile! (transient #{}))]
    (walk/postwalk
     (fn [x]
       (when (and (map? x) (= :type-named-tuple (:node x)))
         (let [nm (canonical-named-tuple-name x)]
           (when (and (not= nm (canonical-named-tuple-name shape-node))
                      (contains? shapes nm))
             (vswap! deps conj! nm))))
       x)
     (:fields shape-node))
    (persistent! @deps)))

(defn- topologically-sorted-shape-keys
  "Return the shapes' mangled names in an order where each shape
   appears AFTER every shape it contains (nested `:type-named-tuple`
   field type). Ties broken by lexicographic mangled name — matches
   `collect-shapes`'s sorted-map default for shapes with no
   containment relationship. Cycles are impossible under the mangling
   scheme (containment is antisymmetric: a shape's name encodes its
   nested structure, so `A ⊃ B` forces `name(A) ≠ name(B)` and
   `B ⊃ A` would need `name(B)` to encode `A` which encodes `B` ad
   infinitum)."
  [shapes]
  (let [names   (into (sorted-set) (keys shapes))
        deps-of (into {}
                      (map (fn [nm] [nm (shape-dependencies-in-map shapes (get shapes nm))]))
                      names)]
    (loop [placed     []
           placed-set #{}
           remaining  names]
      (if (empty? remaining)
        placed
        (if-let [next-nm (first (filter (fn [nm]
                                          (every? placed-set (get deps-of nm)))
                                        remaining))]
          (recur (conj placed next-nm)
                 (conj placed-set next-nm)
                 (disj remaining next-nm))
          (throw (ex-info "F15 L2.c.ii-b: cyclic named-tuple containment (should be impossible)"
                          {:remaining  remaining
                           :deps-of    deps-of})))))))

(defn emit-typedefs
  "F15 L2.c.ii — return a C source string of typedef declarations
   for every shape in `shapes` (as produced by `collect-shapes`).

   `primitive->c-type` is a function `String → String` mapping cxc
   primitive type names (\"Int\", \"String\", \"Bool\", ...) to their
   C-type strings. This ns doesn't know cxc's primitive mapping —
   the caller (typically lower.clj / emit.clj) supplies it.

   Empty input → empty string. Multiple typedefs separate by blank
   lines. Iteration follows a topological sort by containment
   (nested typedefs appear before their containers), with
   lexicographic mangled name as the deterministic tie-break — so
   output is stable across runs.

   Field-type coverage: primitives, non-nil primitives, nested
   named-tuples (referenced by mangled name — F15 L2.c.ii-b).
   Parametric field types (`Array of T` etc.) throw NYI — follow-on
   sub-landings extend."
  ^String [shapes primitive->c-type]
  (if (empty? shapes)
    ""
    (str/join "\n\n"
              (map (fn [mangled-name]
                     (shape->typedef-string primitive->c-type
                                            mangled-name
                                            (get shapes mangled-name)))
                   (topologically-sorted-shape-keys shapes)))))

;; ---------------------------------------------------------------------------
;; F15 L2.d.i — structural conformance predicate (pure).
;;
;; Given a value's declared field list and a required named-tuple
;; shape's field list, decide whether the value structurally conforms
;; to the required shape per spec §3.4 and F15 plan §Landing 2:
;;
;;   1. Every field named on the required side exists on the value side.
;;   2. For each such field, the value's declared type is identically
;;      equal to the required's declared type (invariant — no
;;      covariance at Landing 2).
;;   3. The value may carry additional fields the required doesn't
;;      name (width subtyping).
;;
;; Pure fn — no coupling to record-env representation or lower state.
;; Callers extract `{:name :type}` seqs on their side and hand them in.
;; Follow-on sub-landing wires this into lower's arg-vs-param check at
;; the sites listed in F15 plan §Landing 2 "Files to touch" (paired
;; with L2.c.iii.c so lower can survive `:type-named-tuple` params
;; reaching the C-emit path).
;;
;; Type-equality uses Clojure structural equality: two `:type-ref` /
;; `:type-nonnil` / `:type-named-tuple` / `:type-of` nodes are equal
;; iff their maps have identical keys and values. Nullability-sensitive
;; automatically (`Nn_<T>` and bare `<T>` differ in the `:node` tag).
;; ---------------------------------------------------------------------------

(defn field-type
  "F15 L2.c.iii.c.ε.β.α — resolve a field's declared type in a named
   tuple, given the shape env (as produced by `collect-shapes`) and
   the shape's canonical mangled name.

   Returns the raw parsed type node (`{:node :type-ref :name ...}`,
   `{:node :type-nonnil ...}`, or `{:node :type-named-tuple ...}` for
   nested shapes) so callers can feed it back through their own
   type-lowering path. Returns nil when the mangled name isn't in
   the env OR the field name isn't in the shape (caller decides
   whether to error or fall through).

   Consumers: ε.β wires this into lower's `infer-type` /
   `infer-method-call-type` / `.field` access dispatch — when the
   target has `:named-tuple` type-kw, look up the field's type
   here instead of the record-env path."
  [shape-env mangled-name field-name]
  (when-let [shape (get shape-env mangled-name)]
    (some (fn [{:keys [name type]}]
            (when (= field-name name) type))
          (:fields shape))))

(defn structural-conforms?
  "F15 L2.d predicate — does a value's field list structurally conform
   to a required named-tuple's field list?

   Rule (spec §3.4 + F15 plan §Landing 2 — invariant field types,
   width subtyping allowed):
   1. Every required field must appear on the value side by name.
   2. Value's field type equals required's field type (Clojure
      structural equality on the type node — nullability-sensitive).
   3. Value may carry additional fields not named by the required
      shape.

   Args:
     value-fields    — seq of maps carrying at least `:name` and
                       `:type` (extra keys like `:default` /
                       `:modifier` are ignored).
     required-fields — seq of maps carrying `:name` + `:type`.

   Returns one of:
     {:conforms true}
     {:conforms false :reason :missing-field :field <name>
      :available <sorted-vec-of-value-field-names>}
     {:conforms false :reason :wrong-type :field <name>
      :expected <type-node> :got <type-node>}

   Field lookup is by exact name (case-sensitive) — field names live
   in the source's identifier space, not the header/keyword space, so
   `x` and `X` are distinct fields (spec §3.2)."
  [value-fields required-fields]
  (let [value-by-name (into {} (map (juxt :name identity)) value-fields)]
    (loop [remaining (seq required-fields)]
      (if-let [req-field (first remaining)]
        (let [req-name (:name req-field)
              req-type (:type req-field)
              v-field  (get value-by-name req-name)]
          (cond
            (nil? v-field)
            {:conforms  false
             :reason    :missing-field
             :field     req-name
             :available (vec (sort (map :name value-fields)))}

            (not= req-type (:type v-field))
            {:conforms false
             :reason   :wrong-type
             :field    req-name
             :expected req-type
             :got      (:type v-field)}

            :else (recur (next remaining))))
        {:conforms true}))))
