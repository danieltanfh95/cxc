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
  "Translate a field's type node to a C-type string. MVP: primitives
   and non-nil primitives only. `primitive->c-type` maps a type name
   like \"Int\" to the C type (e.g., `int64_t`)."
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
    (fail-nyi! (str "nested named-tuple field types not supported in "
                    "F15 L2.c.ii typedef emit — extend when the "
                    "inline-vs-reference decision for nested "
                    "typedefs is settled")
               type-node)

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

(defn emit-typedefs
  "F15 L2.c.ii — return a C source string of typedef declarations
   for every shape in `shapes` (as produced by `collect-shapes`).

   `primitive->c-type` is a function `String → String` mapping cxc
   primitive type names (\"Int\", \"String\", \"Bool\", ...) to their
   C-type strings. This ns doesn't know cxc's primitive mapping —
   the caller (typically lower.clj / emit.clj) supplies it.

   Empty input → empty string. Multiple typedefs separate by blank
   lines. Iteration follows the sorted-map order so output is
   deterministic across runs.

   Field-type coverage (MVP): primitives + non-nil primitives.
   Nested named-tuples and parametric field types throw NYI —
   follow-on sub-landings extend."
  ^String [shapes primitive->c-type]
  (if (empty? shapes)
    ""
    (str/join "\n\n"
              (map (fn [[mangled-name shape-node]]
                     (shape->typedef-string primitive->c-type
                                            mangled-name
                                            shape-node))
                   shapes))))
