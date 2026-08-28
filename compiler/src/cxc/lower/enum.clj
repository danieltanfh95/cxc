(ns cxc.lower.enum
  "Top-level `def <Enum>:` lowerer. Produces one of three C shapes
   depending on the parsed enum's structure:

   - Value-only (all cases bare)  → `:c-enum`         (typedef enum)
   - Raw-valued  (`case a = 1`)   → `:c-enum` + `:raw-c-type` (typedef
                                     enum : uint8_t / … per §3.3)
   - Payload-carrying (F16 L1)    → `:c-tagged-enum`  (typedef struct
                                     with discriminator tag +
                                     per-case payload union)

   Emit renders the actual C from these nodes. Consulted by the
   top-level orchestrator in `cxc.lower/lower` — no other lowering
   passes touch this file."
  (:require [cxc.type-kw :refer [cxc-type->c]]))

(defn lower-enum
  "Top-level `def Foo:` with case markers.
   Value-only enums (all cases bare) lower to a C typedef enum with
   `Foo_a`, `Foo_b`, ... as members — pre-F16 behavior preserved.

   F16 L1 — if any case carries a payload, lower to a tagged-union
   struct: `typedef struct { <Foo>_Tag tag; union { struct { … } case1;
   … } data; } Foo;` with an accompanying `<Foo>_Tag` enum for the
   discriminator. Emit resolves per-field C types via record-env /
   atom-env / enum-env.

   F16 L2 — raw-value enums (all cases have `= <int>`) preserve their
   raw values on the `:c-enum` node so emit can render explicit
   `<Enum>_<case> = <value>` initializers with a typed underlying
   integer (`typedef enum : <raw-type> { … }`, per §3.3)."
  [enum-node record-env atom-env enum-env module-map]
  (let [tagged?    (some #(seq (:payload %)) (:case-entries enum-node))
        raw-values (:raw-values enum-node)
        raw-type   (:raw-type   enum-node)]
    (cond
      tagged?
      {:node    :c-tagged-enum
       :name    (:name enum-node)
       :entries (mapv (fn [ce]
                        {:name    (:name ce)
                         :payload (mapv (fn [p]
                                          {:name   (:name p)
                                           :c-type (cxc-type->c (:type p)
                                                                record-env
                                                                atom-env
                                                                enum-env
                                                                module-map)})
                                        (:payload ce))})
                      (:case-entries enum-node))}

      raw-values
      {:node       :c-enum
       :name       (:name enum-node)
       :cases      (:cases enum-node)
       :raw-values raw-values
       :raw-c-type (get {"UInt8" "uint8_t" "UInt16" "uint16_t"
                         "UInt32" "uint32_t" "UInt64" "uint64_t"}
                        raw-type)}

      :else
      {:node  :c-enum
       :name  (:name enum-node)
       :cases (:cases enum-node)})))
