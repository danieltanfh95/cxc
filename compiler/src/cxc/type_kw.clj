(ns cxc.type-kw
  "M1b — cxc type surface: user-facing type name → internal type-kw and
   → emit-ready C type strings. Extracted from cxc.lower.

   Two dictionaries and two directions:

     `cxc-type->c` maps an AST type-node to its C type string. Consumers
     are storage sites (field ivars, ctor params, method returns).

     `cxc-type->type-kw` maps a user-visible cxc type name (`\"Duration\"`)
     to the internal type-kw vocabulary (`:int` / `[:int 32]` /
     `{:record N}` / …). Consumers are inference sites.

     `type-kw->c-type` is the reverse of cxc-type->type-kw for emit —
     inferred observable return types come back as type-kws; this
     converts back to C.

     `type-kw->cxc-name-str` is the reverse of cxc-type->type-kw for
     the user surface — string-comparison against protocol signature
     return types, diagnostic messages.

   Family predicates (`int-family?`, `float-family?`, `numeric-family?`,
   `optional-tk?`) + support helpers (`unwrap-tk`, `int-bit-width`,
   `base-type-name`, `list-container-class-name`, `map-key-val-of`,
   `optional-type-name`) round out the namespace.

   Deps: cxc.env (resolve-decl-lookup, phantom-atom-name?),
         cxc.mangle (->c-safe),
         cxc.diagnostics (fail-nyi!),
         cxc.named-tuple (canonical-named-tuple-name)."
  (:require [clojure.string :as str]
            [cxc.diagnostics :refer [fail-nyi! fail-compile!]]
            [cxc.env :as env]
            [cxc.mangle :refer [->c-safe]]
            [cxc.named-tuple :as named-tuple]))

(def primitive-c-type
  ;; Phase D F36a — spec §3.1 primitive substrate.
  ;;
  ;; `Int` is a prelude alias for `Int64` — 64-bit signed always. Users
  ;; who want tighter storage write a domain atom (see §3.1.2) or reach
  ;; for a sized atom directly.
  ;;
  ;; `Float` = 32-bit IEEE 754 (fixes v0.11 lowering bug — was `double`).
  ;; `Double` = 64-bit IEEE 754.
  ;;
  ;; Sized atoms (UInt8..UInt64, Int8..Int64) pin exact widths for
  ;; interop, wire formats, and explicit-width contexts.
  ;;
  ;; Precision-preservation across arithmetic + observables (a full
  ;; type-kw split into `:int-N` / `:float-N` variants) is Landing 1b —
  ;; currently all integer widths share `:int`, all float widths share
  ;; `:float`. Storage widths on FIELDS are correct.
  {"Int"     "int64_t"      ; §3.1: Int aliased to Int64
   "Int8"    "int8_t"
   "Int16"   "int16_t"
   "Int32"   "int32_t"
   "Int64"   "int64_t"
   "UInt8"   "uint8_t"
   "UInt16"  "uint16_t"
   "UInt32"  "uint32_t"
   "UInt64"  "uint64_t"
   "Float"   "float"        ; §3.1: 32-bit IEEE 754
   "Double"  "double"       ; §3.1: 64-bit IEEE 754
   ;; F65 — String is NSString * per spec §3.10.5 (`kind: regular`,
   ;; toll-free bridged to NSString). Reference-shaped: ARC handles
   ;; retain/release, nullable at C level (like any ObjC object
   ;; pointer). Full grapheme-native method surface per §3.10.5
   ;; (grapheme_count, first_grapheme, substring, ...) is a separate
   ;; follow-on landing.
   "String"  "NSString *"
   "Bool"    "int"
   ;; Phase D F36a Landing 3a — spec §3.1 non-numeric substrate.
   ;;
   ;; Unit: the type with exactly one value, `()`. Return-position only for
   ;; MVP; usage as field/param yields a clang-visible `void field;` error.
   ;; `()` literal syntax + typed use in expression position is a follow-on.
   ;;
   ;; Never: divergent return. Emitted as `void` for MVP; `_Noreturn`
   ;; attribute wiring depends on flow analysis and lands separately.
   ;;
   ;; Any: dynamic type per §3.1.3. Ships as ObjC `id` — nil-allowable
   ;; at C level, which matches the runtime-typed nature. Method dispatch
   ;; on Any values (runtime lookup, fail-if-no-selector) is a follow-on.
   ;;
   ;; Bytes: mutable byte buffer, reference-shaped per §3.10.4. Ships as
   ;; NSMutableData *. Method surface (append/read/slice/length/...) is a
   ;; follow-on Bytes-methods landing.
   "Unit"    "void"
   "Never"   "void"
   "Any"     "id"
   "Bytes"   "NSMutableData *"
   ;; Layer 6 Step 12 — escaping-block field type. MVP is zero-arg
   ;; void return only; a future landing generalizes to
   ;; `Block[(params) -> R]` grammar with full signature support.
   "Block"   "void (^)(void)"
   ;; FL-2 (2026-09-02) — FFI-primitive aliases for `foreign "...":`
   ;; signatures. `Byte` = raw byte (uint8_t alias for foreign-boundary
   ;; use); `SizeT` / `SsizeT` = size_t / ssize_t for libc-style
   ;; length + return-code slots. Deliberately distinct from UInt8 so
   ;; the source reads FFI-purposed rather than sized-int-arithmetic.
   ;; `Ptr of T` parametric container is deferred until a fixture
   ;; needs it.
   "Byte"    "uint8_t"
   "SizeT"   "size_t"
   "SsizeT"  "ssize_t"
   ;; FL-8 (2026-09-02) — explicit C-string type for foreign signature
   ;; positions where the boundary type is `const char *` and the user
   ;; wants it distinct from cxc `String` (NSString *). std.env's
   ;; `getenv(name: CString) -> CString` uses this. FL-6 auto-marshals
   ;; String literals into CString slots via `[UTF8String]`; users
   ;; passing an already-`char *`-typed value pass through unchanged.
   ;; Reverse marshaling (`const char *` return → NSString) is deferred
   ;; until a fixture asks — for now CString return values are opaque
   ;; `const char *` at cxc surface (usable in further FFI calls).
   "CString" "const char *"})

(declare optional-type-name type-kw->cxc-name-str
         optional-tk? unwrap-tk base-type-name)

(defn cxc-type->c
  "Primitives → C type name; records → own struct name (value); atoms →
   `<name> *` (pointer, already nullable in ObjC); enums → own typedef
   name (int-backed).

   Nullability (spec §3.6.1, unified rule 2026-08-20; L5a landed
   same day): bare `T` is nil-allowed at every position; `T!` is the
   non-nil contract on every shape; `T?` is BANNED for all shapes,
   fires `:cxc-f39-nullable-suffix-banned`. `T!` accepts on every
   shape and emits raw `T` (value types opt out of the Optional
   envelope; L3 runtime nonnil-check fires regardless). L5b lands
   the bare-`T` value-typed → Optional-boxed C-level flip.

   F51 L3b(b.ii)-5d Batch 5 — `module-map` is passed explicitly
   rather than read from the ambient `*module-map*`. Retires the
   ambient at Batch 6. Callers that have a ctx map pass
   `(:module-map ctx)`; bootstrap paths (build-atom-env,
   build-record-env) thread it through their own signatures."
  [ty-node record-env atom-env enum-env module-map]
  (case (:node ty-node)
    :type-nullable
    ;; F39 Landing 5a (2026-08-20) — unified rule: `T?` is BANNED at
    ;; every position (reference, value, container, tuple). Bare `T`
    ;; is nil-allowed universally; `T!` is the non-nil contract.
    ;; Single diagnostic `:cxc-f39-nullable-suffix-banned` fires for
    ;; every inner shape (subsumes L1's `:cxc-f39-nullable-on-
    ;; reference` and the value-typed `fail-nyi!` that pointed at L5).
    (let [inner    (:inner ty-node)
          inner-nm (case (:node inner)
                     :type-ref (:name inner)
                     :type-of  (str (:outer inner) " of ...")
                     :type-tuple "(...)"
                     :type-named-tuple "(name: T, ...)"
                     (str "<" (name (:node inner)) ">"))]
      (fail-compile!
       :cxc-f39-nullable-suffix-banned
       (str "`" inner-nm "?` — the `?` suffix is banned universally "
            "(spec §3.6.1 unified rule 2026-08-20). Fix: use bare `"
            inner-nm "` for the nullable form (nil-allowed by default) "
            "or `" inner-nm "!` for the non-nil contract.")
       ty-node))
    :type-nonnil
    ;; F39 Landing 5a (2026-08-20) — `T!` is the non-nil contract on
    ;; every shape. Under the unified rule, bare `T` is nil-allowed
    ;; (references: nil pointer; values: Optional-boxed at C level
    ;; post-L5b), so `T!` is meaningful for value types too: it opts
    ;; OUT of the Optional envelope and lowers to raw `T`. Runtime
    ;; enforcement at the three observation points (ctor bind, then:
    ;; write, method return) fires via F39 L3 regardless of shape.
    ;; Pre-L5a diagnostic `:cxc-f39-nonnil-on-value` retired — see
    ;; plans/g3-structural-canonical/f39-nullability.md L5 companion changes.
    (let [inner (:inner ty-node)]
      (case (:node inner)
        :type-ref
        (let [nm  (:name inner)
              hit (:resolves-to inner)]
          (cond
            (env/resolve-decl-lookup atom-env module-map nm hit)
            (str (->c-safe nm) " *")
            (= "String" nm)                 "NSString *"
            (contains? primitive-c-type nm) (primitive-c-type nm)
            (env/resolve-decl-lookup record-env module-map nm hit)
            (->c-safe nm)
            (env/resolve-decl-lookup enum-env module-map nm hit)
            (->c-safe nm)
            :else
            ;; Bootstrap: build-atom-env resolves ctor-param / data-
            ;; field types with record-env={} because records haven't
            ;; been built yet. If T isn't yet resolvable, treat as a
            ;; value-shaped user type — same as bare `T` fallback.
            ;; A genuine typo surfaces later at the bare `T` :type-ref
            ;; site as "unknown type".
            (->c-safe nm)))
        (fail-nyi! (str "cxc-type->c: `!` on unknown inner " (:node inner))
                   ty-node)))
    :type-ref
    ;; F39 L5b-ii-b (2026-08-21) — `cxc-type->c` intentionally stays
    ;; raw for `:type-ref` at this landing. The Optional-boxed flip
    ;; applies to FIELD STORAGE only, done in `fields-from-data-
    ;; section` where the field's `:nonnil?` flag is in scope and the
    ;; POSITION (field vs param vs return) is unambiguous. Applying
    ;; the flip here would cascade into ctor params + method params +
    ;; return types, requiring call-site adaptation for every value-
    ;; typed argument — a much larger surface than the field-storage
    ;; landing. Params + returns get Optional-boxed in a follow-on
    ;; sub-landing that pairs with call-site wrapping (L5b-ii-b* /
    ;; L5b-ii-c).
    (let [nm  (:name ty-node)
          hit (:resolves-to ty-node)]
      (cond
        (contains? primitive-c-type nm) (primitive-c-type nm)
        (env/resolve-decl-lookup record-env module-map nm hit)
        (->c-safe nm)
        (env/resolve-decl-lookup atom-env module-map nm hit)
        (str (->c-safe nm) " *")
        (env/resolve-decl-lookup enum-env module-map nm hit)
        (->c-safe nm)
        ;; L-panic-cap (2026-08-26) — phantom capability atom types
        ;; render as atom pointers (never actually referenced at
        ;; runtime; the capability is compile-time only).
        (env/phantom-atom-name? nm)
        (str (->c-safe nm) " *")
        :else                            (fail-nyi! (str "type " nm) ty-node)))
    :type-named-tuple
    ;; F15 L2.c.iii.c.α (2026-08-24) — a named-tuple param type lowers
    ;; to a reference to its canonical mangled struct name, consistent
    ;; with L2.c.ii-b's field-side convention. Dead code today because
    ;; `base-type-name` still fails-nyi on `:type-named-tuple` upstream
    ;; (in ctor-params + method-params lowering paths); reaching this
    ;; branch requires L2.c.iii.c.β to teach base-type-name too AND to
    ;; preserve the raw `:type` node on lowered params so collect-shapes
    ;; finds the shape and emit-typedefs synthesizes the typedef.
    (named-tuple/canonical-named-tuple-name ty-node)

    :type-of
    (cond
      (= "List" (:outer ty-node))
      ;; Compiler-synthesized List class — reference-typed, pointer at
      ;; the C level. Element-type validity is checked at the atom-env
      ;; registration site.
      (str (base-type-name ty-node) " *")

      (= "Map" (:outer ty-node))
      ;; Compiler-synthesized Map class (Layer 6 Step 8). Same shape as
      ;; List — mangled class name, pointer-typed at C. K/V validity
      ;; checked at atom-env registration.
      (str (base-type-name ty-node) " *")

      (= "Set" (:outer ty-node))
      ;; Compiler-synthesized Set class (Layer 6 Step 9). Same shape
      ;; as List/Map — pointer-typed at C.
      (str (base-type-name ty-node) " *")

      (= "Ptr" (:outer ty-node))
      ;; FL-7 (2026-09-02) — FFI-only pointer type. `Ptr of T` lowers
      ;; to `<C-type of T> *` — a raw C pointer, no ObjC / ARC / boxing
      ;; involved. Meant strictly for `foreign "..."` signature slots
      ;; and pointer-typed FFI locals; not for ordinary storage. The
      ;; inner element type must render on its own (primitive, CString,
      ;; or nested Ptr of T for `Ptr of CString` = `char **`).
      (str (cxc-type->c (:inner ty-node) record-env atom-env enum-env module-map)
           " *")

      :else
      (fail-nyi! (str "cxc-type->c: `" (:outer ty-node)
                      " of ...` unsupported")
                 ty-node))
    (fail-nyi! (str "cxc-type->c: unknown type-node " (:node ty-node)) ty-node)))

(defn base-type-name
  "Extract the underlying type name from either a :type-ref or a
   :type-nullable wrapper. Used to key the type env regardless of
   nullability, since nullability is orthogonal to which env the type
   lives in.

   F15 L2.c.iii.c.β.1 (2026-08-24) — `:type-named-tuple` returns
   the canonical mangled name (D4-consistent reference), matching
   `cxc-type->c`'s L2.c.iii.c.α branch. Dead code today: the
   downstream ctor-params / method-params lowering paths don't yet
   preserve `:type` on lowered params, so `collect-shapes` doesn't
   discover the shape and the typedef never emits — reaching this
   branch would produce a mangled reference to an undefined C type.
   L2.c.iii.c.β.2 preserves `:type` so the pipeline is end-to-end."
  [ty-node]
  (case (:node ty-node)
    :type-ref      (:name ty-node)
    :type-nullable (base-type-name (:inner ty-node))
    :type-nonnil   (base-type-name (:inner ty-node))
    :type-named-tuple (named-tuple/canonical-named-tuple-name ty-node)
    :type-of       (cond
                     (= "List" (:outer ty-node))
                     ;; List of T → mangled ObjC class name. Deterministic
                     ;; function of the element type, no env lookup needed.
                     ;; Registered in atom-env by build-atom-env so
                     ;; infer-type / method-call resolution find it.
                     (str "_Cxc_ListOf" (base-type-name (:inner ty-node)))

                     (= "Map" (:outer ty-node))
                     ;; Map of (K, V) → mangled name incorporating both
                     ;; type params. :inner is a :type-tuple with :types
                     ;; [K-node V-node].
                     (let [tuple (:inner ty-node)
                           _ (when-not (and (= :type-tuple (:node tuple))
                                            (= 2 (count (:types tuple))))
                               (fail-nyi! (str "Map of ... expects (K, V) — got "
                                               (pr-str tuple))
                                          ty-node))
                           [k v] (:types tuple)]
                       (str "_Cxc_MapOf"
                            (base-type-name k) (base-type-name v)))

                     (= "Set" (:outer ty-node))
                     ;; Set of T (Layer 6 Step 9) — same shape as List
                     ;; but distinct backing (NSMutableSet). Grammar
                     ;; reuses single-type-arg after `of`.
                     (str "_Cxc_SetOf" (base-type-name (:inner ty-node)))

                     (= "Ptr" (:outer ty-node))
                     ;; FL-7 (2026-09-02) — FFI pointer. Base-type-name
                     ;; is used to key env lookups and to build mangled
                     ;; container class names; Ptr has neither (raw C
                     ;; pointer, no cxc class). Return a stable synthetic
                     ;; name that won't collide with user decls
                     ;; (`_Cxc_Ptr_<inner>`) so downstream `contains?`
                     ;; env checks stay well-defined.
                     (str "_Cxc_Ptr_" (base-type-name (:inner ty-node)))

                     :else
                     (fail-nyi! (str "base-type-name: `" (:outer ty-node)
                                     " of ...` — only `List of T`, "
                                     "`Map of (K, V)`, `Set of T`, and "
                                     "`Ptr of T` (FFI) supported")
                                ty-node))
    (fail-nyi! (str "base-type-name: unknown type-node " (:node ty-node)) ty-node)))

(defn list-container-class-name
  "Mangled ObjC class name for `List of <element-name>`. Kept in sync
   with base-type-name's :type-of branch."
  [element-name]
  (str "_Cxc_ListOf" element-name))

(defn cxc-type->type-kw
  "Map a cxc type-ref name to the type-keyword vocab used by infer-type.
   Primitive names → :int/:float/:string/:bool (family markers, possibly
   width-carrying). Aggregate names → {:record N}, {:atom N}, or
   {:enum N} depending on which env they resolve in.

   Phase D F36a — sized numeric atoms carry their C storage width in
   metadata on the type-kw so downstream `type-kw->c-type` can preserve
   the width without a full type-kw split. `:int` (default, Int/Int64)
   and `:float` (default, Double) stay as the bare kw for existing
   pattern matches; width variants attach `:cxc-c-type` metadata.

   Width preservation across observables + arithmetic + typed payloads
   flows through the metadata-carrying `type-kw->c-type` — narrower
   variants preserve their C type in emission, same-width family
   arithmetic still works via bare-kw pattern matches."
  [cxc-name record-env atom-env enum-env node]
  (case cxc-name
    "Int"    :int
    "Int8"   ^{:cxc-c-type "int8_t"}   [:int 8]
    "Int16"  ^{:cxc-c-type "int16_t"}  [:int 16]
    "Int32"  ^{:cxc-c-type "int32_t"}  [:int 32]
    "Int64"  ^{:cxc-c-type "int64_t"}  [:int 64]
    "UInt8"  ^{:cxc-c-type "uint8_t"}  [:uint 8]
    "UInt16" ^{:cxc-c-type "uint16_t"} [:uint 16]
    "UInt32" ^{:cxc-c-type "uint32_t"} [:uint 32]
    "UInt64" ^{:cxc-c-type "uint64_t"} [:uint 64]
    "Float"  ^{:cxc-c-type "float"}    [:float 32]
    "Double" :float
    "String" :string
    "Bool"   :bool
    ;; Phase D F36a Landing 3a — non-numeric substrate. Each gets a
    ;; distinct type-kw so infer-type can reject arithmetic on them
    ;; (Unit/Never/Any/Bytes are not numeric family) and so downstream
    ;; type-kw->c-type resolves them to the correct C type.
    "Unit"   :unit
    "Never"  :never
    "Any"    :any
    "Bytes"  :bytes
    ;; Layer 6 Step 12 — Block is a first-class type at the type-kw
    ;; level so infer-call-type / lower-expr can dispatch on it when
    ;; a self-field-typed callee gets invoked.
    "Block"  :block
    ;; FL-2 — FFI-primitive aliases. Each carries the target C-type in
    ;; metadata so `type-kw->c-type`'s width-variants branch recovers it
    ;; on the emit side.
    "Byte"   ^{:cxc-c-type "uint8_t"} [:uint 8]
    "SizeT"  ^{:cxc-c-type "size_t"}  [:uint 64]
    "SsizeT" ^{:cxc-c-type "ssize_t"} [:int 64]
    ;; FL-8 — CString as its own type-kw so infer-type can distinguish
    ;; a CString return from a String return; the `:cxc-c-type` metadata
    ;; surfaces at type-kw->c-type so downstream storage/emit renders
    ;; `const char *`. Distinct kw prevents accidental NSString-selector
    ;; dispatch on it.
    "CString" ^{:cxc-c-type "const char *"} [:cstring]
    (cond
      (contains? record-env cxc-name) {:record cxc-name}
      (contains? atom-env   cxc-name) {:atom   cxc-name}
      (contains? enum-env   cxc-name) {:enum   cxc-name}
      ;; L-panic-cap (2026-08-26) — phantom capability atoms are
      ;; compiler-known types. Recognized here so type resolution
      ;; passes even when no user def exists.
      (env/phantom-atom-name? cxc-name)
      {:atom cxc-name}
      ;; F15 L2.c.iii.c.γ.α (2026-08-24) — mangled named-tuple names
      ;; (D4-reserved `_Cxc_NT` prefix, per named-tuple/reserved-type-
      ;; name-prefix?) resolve to a `:named-tuple` type-kw without a
      ;; separate env lookup. Downstream pattern matches don't handle
      ;; `:named-tuple` yet; today's programs never reach this branch
      ;; (upstream lowering paths still fail-nyi on named-tuple ctor
      ;; params before this point in the pipeline). Prefix check
      ;; matches L2.a's reserved-prefix guarantee: the compiler owns
      ;; every `_Cxc_NT`-prefixed name, so this recognition is safe.
      (str/starts-with? cxc-name "_Cxc_NT")
      {:named-tuple cxc-name}

      :else (fail-nyi! (str "unresolved type name `" cxc-name "`") node))))

(defn int-family?
  "True when tk is any integer type-kw: bare `:int` or a `[:int N]`/`[:uint N]` variant."
  [tk]
  (or (= :int tk)
      (and (vector? tk) (contains? #{:int :uint} (first tk)))))

(defn float-family?
  "True when tk is any float type-kw: bare `:float` or a `[:float N]` variant."
  [tk]
  (or (= :float tk)
      (and (vector? tk) (= :float (first tk)))))

(defn numeric-family?
  "True when tk is any numeric type-kw (int or float family)."
  [tk]
  (or (int-family? tk) (float-family? tk)))

(defn int-bit-width
  "Width in bits of an integer type-kw. Bare `:int` = 64 (Int alias)."
  [tk]
  (cond
    (= :int tk)                              64
    (and (vector? tk) (int-family? tk))      (second tk)
    :else                                    nil))

(defn primitive-nil-sentinel
  "2026-09-04 — map a primitive type-kw to the C nil-sentinel constant
   name (defined in the emit-time preamble via `cxc-nil-sentinels`).
   Returns nil for non-primitive or unsupported types (uint family
   omitted at v1.0 — no natural sentinel; boxed representation TBD)."
  [tk]
  (cond
    (= :int    tk) "CXC_INT_NIL"
    (= :bool   tk) "CXC_BOOL_NIL"
    (= :float  tk) "CXC_DOUBLE_NIL"          ; bare :float is Double
    (and (vector? tk) (= :int (first tk)))
    (case (second tk)
      64 "CXC_INT_NIL"
      32 "CXC_INT32_NIL"
      16 "CXC_INT16_NIL"
       8 "CXC_INT8_NIL"
       nil)
    (and (vector? tk) (= :float (first tk)))
    (case (second tk)
      32 "CXC_FLOAT_NIL"
      64 "CXC_DOUBLE_NIL"
      nil)
    :else nil))

(defn primitive-envelope-eligible?
  "2026-09-04 Path 1 slice 12 — predicate for Path 1 envelope
   storage eligibility. Broader than `primitive-nil-sentinel` (which
   returns nil for UInt because UInt has no natural sentinel): under
   the envelope representation every primitive value-type is
   representable, so UInt joins Int/Bool/Float/Double. Callers
   deciding whether to lift a nullable-primitive local to envelope
   storage should consult this predicate, not `primitive-nil-
   sentinel` (which is specific to the sentinel-lifting fallback)."
  [tk]
  (or (= :int tk) (= :bool tk) (= :float tk)
      (and (vector? tk)
           (contains? #{:int :uint :float} (first tk)))))

(defn primitive-nil-check
  "2026-09-04 — map a primitive type-kw to the C nil-check macro name.
   Returns nil for non-primitive or unsupported types."
  [tk]
  (cond
    (= :int    tk) "CXC_INT_IS_NIL"
    (= :bool   tk) "CXC_BOOL_IS_NIL"
    (= :float  tk) "CXC_DOUBLE_IS_NIL"
    (and (vector? tk) (= :int (first tk)))
    (case (second tk)
      64 "CXC_INT_IS_NIL"
      32 "CXC_INT32_IS_NIL"
      16 "CXC_INT16_IS_NIL"
       8 "CXC_INT8_IS_NIL"
       nil)
    (and (vector? tk) (= :float (first tk)))
    (case (second tk)
      32 "CXC_FLOAT_IS_NIL"
      64 "CXC_DOUBLE_IS_NIL"
      nil)
    :else nil))

(defn type-kw->c-type
  "Reverse of cxc-type->type-kw for the C emit side. Observable return
   types are inferred as type-kws directly; this converts them back to
   emit-ready C type strings.

   Phase D F36a Landing 1b — width variants like `[:int 32]` and
   `[:float 32]` carry their C type in `:cxc-c-type` metadata (set by
   `cxc-type->type-kw`); this fn reads that metadata. Bare `:int` (Int
   alias for Int64) and bare `:float` (Double) fall back to `int64_t`
   and `double`."
  [tk node]
  (cond
    (= :int    tk) "int64_t"
    (= :float  tk) "double"
    (= :string tk) "NSString *"           ; F65 §3.10.5
    (= :bool   tk) "int"
    (= :unit   tk) "void"
    (= :never  tk) "void"
    (= :any    tk) "id"
    (= :bytes  tk) "NSMutableData *"
    (= :block  tk) "void (^)(void)"
    ;; Width variants carry their C type in metadata (Landing 1b).
    (and (vector? tk) (:cxc-c-type (meta tk))) (:cxc-c-type (meta tk))
    ;; F39 L5b-ii-c — Optional propagation. `[:optional inner]` renders
    ;; as the synthesized `_Cxc_Optional_<inner-name>` typedef.
    (and (vector? tk) (= :optional (first tk)))
    (optional-type-name (type-kw->cxc-name-str (second tk)))
    (and (map? tk) (:record tk)) (->c-safe (:record tk))
    (and (map? tk) (:atom   tk)) (str (->c-safe (:atom tk)) " *")
    (and (map? tk) (:enum   tk)) (->c-safe (:enum tk))
    ;; F15 L2.c.iii.c.γ.β (2026-08-24) — `:named-tuple` type-kw
    ;; renders as the bare mangled struct name, matching L2.c.ii-b's
    ;; field-side convention. Value type (no pointer suffix); the
    ;; typedef itself is emitted via `:named-tuple-typedefs` on the
    ;; program AST (L2.c.iii.b wire-in). Dead code today since γ.α's
    ;; recognition branch isn't reached from any real lowering path
    ;; (upstream lowering doesn't produce lowered `:cxc-ty` starting
    ;; with `_Cxc_NT` in any test program).
    (and (map? tk) (:named-tuple tk)) (:named-tuple tk)
    :else (fail-nyi! (str "unrepresentable observable type " (pr-str tk))
                     node)))

(defn map-key-val-of
  "If ty-node is `Map of (K, V)`, return {:map-key K-name :map-val
   V-name}; else return {}. Used to decorate fields/ctor-params so
   downstream map-container instantiation collection can recover K/V
   from the mangled type without re-parsing the name."
  [ty-node]
  (if (and (= :type-of (:node ty-node))
           (= "Map" (:outer ty-node))
           (= :type-tuple (get-in ty-node [:inner :node])))
    (let [[k v] (get-in ty-node [:inner :types])]
      {:map-key (base-type-name k)
       :map-val (base-type-name v)})
    {}))

(defn optional-tk?
  "True when a type-kw is [:optional inner] shape."
  [tk]
  (and (vector? tk) (= :optional (first tk))))

(defn unwrap-tk
  "Return the inner type-kw if `tk` is [:optional inner]; else `tk` unchanged."
  [tk]
  (if (optional-tk? tk) (second tk) tk))

(defn type-kw->cxc-name-str
  "F67 L4b — reverse mapping from the internal type-kw vocabulary
   (populated by `cxc-type->type-kw` + `infer-type`) back to the
   user-facing cxc type name used by `signature-mismatch-detail`
   for string-comparison against protocol signature return types.
   Symmetric with the primitives table in `cxc-type->type-kw`. Sized
   numeric variants read the C-type metadata (which is deterministic)
   and map back to the user name (Int8/UInt32/Float etc.)."
  [tk]
  (cond
    (nil? tk)            nil
    (= :int tk)          "Int"
    (= :float tk)        "Double"
    (= :string tk)       "String"
    (= :bool tk)         "Bool"
    (= :unit tk)         "Unit"
    (= :never tk)        "Never"
    (= :any tk)          "Any"
    (= :bytes tk)        "Bytes"
    (= :block tk)        "Block"
    (and (vector? tk) (= :int (first tk)))   (str "Int"   (second tk))
    (and (vector? tk) (= :uint (first tk)))  (str "UInt"  (second tk))
    (and (vector? tk) (= :float (first tk))) (if (= 32 (second tk)) "Float" "Double")
    (and (map? tk) (:record tk)) (:record tk)
    (and (map? tk) (:atom   tk)) (:atom   tk)
    (and (map? tk) (:enum   tk)) (:enum   tk)
    :else                nil))

(defn optional-type-name
  "Mangle a value-type name into its Optional-boxed C typedef name.
   `Int` → `_Cxc_Optional_Int64` (Int aliases Int64 per §3.1). Other
   primitives pass through unchanged (`Int32` → `_Cxc_Optional_Int32`).
   Records / enums pass through (`Position` → `_Cxc_Optional_Position`).

   For parametric types (`List of T`) L5b-ii-a stays scalar-only —
   containers are reference-shaped and already nil-carrying; they
   never Optional-box. This helper only runs on names that reached
   the value-type branch of `cxc-type->c`."
  [cxc-ty-name]
  (let [canonical (if (= "Int" cxc-ty-name) "Int64" cxc-ty-name)]
    (str "_Cxc_Optional_" canonical)))
