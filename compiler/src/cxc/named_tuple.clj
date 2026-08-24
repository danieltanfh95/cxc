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
   optional non-nil suffix). Nested named-tuple fields + parametric
   type fields (`Optional`, `Array of T`) throw `:not-yet-implemented`
   with a targeted message — subsequent F15 L2 sub-landings extend the
   mangling to cover those cases.

   The mangled name is a public ABI symbol (FFI, `nm` output, crash
   traces show it). Do not change the scheme in a compatible-looking
   way — bump a version prefix (`_Cxc_NT2_...`) if the encoding ever
   needs to change."
  (:require [clojure.string :as str]))

(defn- fail-nyi! [what node]
  (throw (ex-info (str "canonical-named-tuple-name: " what)
                  {:kind :not-yet-implemented
                   :what what
                   :node node})))

(defn- mangle-type
  "Mangle a single field's type into the canonical suffix form. Returns
   a C-identifier-safe string. Throws for unsupported shapes so the
   caller sees a targeted diagnostic rather than a corrupted mangling."
  [type-node]
  (case (:node type-node)
    :type-ref
    (let [nm (:name type-node)]
      (when (or (str/starts-with? nm "Nn_")
                (str/starts-with? nm "_Cxc_NT"))
        (fail-nyi! (str "type name `" nm "` collides with a reserved "
                        "F15 L2.a mangling prefix (`Nn_` / `_Cxc_NT`); "
                        "rename the type or extend the mangling scheme "
                        "in a follow-on sub-landing")
                   type-node))
      nm)

    :type-nonnil
    (str "Nn_" (mangle-type (:inner type-node)))

    :type-named-tuple
    (fail-nyi! (str "nested named-tuple fields not supported in F15 "
                    "L2.a MVP mangling; extend in a follow-on sub-"
                    "landing")
               type-node)

    :type-of
    (fail-nyi! (str "parametric-type fields (`" (:outer type-node)
                    " of ...`) not supported in F15 L2.a MVP "
                    "mangling; extend in a follow-on sub-landing")
               type-node)

    (fail-nyi! (str "unsupported field type node kind `"
                    (:node type-node) "` in F15 L2.a MVP mangling")
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
