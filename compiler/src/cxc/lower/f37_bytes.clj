(ns cxc.lower.f37-bytes
  "F37 canonical bytes — synthesis of `<Record>_cxc_as_bytes` /
   `<Record>_cxc_from_bytes` / `<Enum>_cxc_as_bytes` + the
   eligibility predicates that gate them.

   Spec §3.4.6 / §3.10.10. Encodes big-endian, fields in
   declaration order, per-type envelopes per the wire-format
   table (primitives naked, String length-prefixed, nested
   records inlined, Optional/nullable prefixed with presence
   byte, enums via tag + payload).

   All fns public — the cluster is internally recursive and
   also called from lower-method-call (`.as()` / `.from()`
   intercepts), the `lower` orchestrator, and the F15 protocol-
   shape seed. Consumers `:refer` the ones they need.

   Retirement: none. This IS the F37 landing surface at v1.0.
   F37 L7-8 (`class function from(source: Bytes) -> Self`
   user overrides) will land alongside these synth fns."
  (:require [clojure.string :as str]
            [cxc.mangle :refer [->c-safe]]
            [cxc.type-kw :refer [optional-type-name]]
            [cxc.lower.record-synth :refer [synthesize-record-equals-fn
                                            synthesize-record-hash-fn]]))

(declare enum-eligible-for-as-bytes-synthesis?
         f37-decode-eligible-field?)

(defn record-synth-as-bytes-name [record-name]
  (str (->c-safe record-name) "_cxc_as_bytes"))

(def f37-primitive-byte-widths
  ;; C fixed widths per §3.10.10. Bool is one byte (0/1).
  {"Bool"   1
   "UInt8"  1  "Int8"   1
   "UInt16" 2  "Int16"  2
   "UInt32" 4  "Int32"  4
   "UInt64" 8  "Int64"  8  "Int" 8
   "Float"  4  "Double" 8})

(defn f37-primitive-byte-field?
  "F37 Landing 2 eligibility: field type is a Bool / sized integer /
   Int / Float / Double, non-Optional. Nested records, String, Char,
   Bytes, enum, atom, and Optional-boxed variants land later."
  [f]
  (and (not (:optional? f))
       (contains? f37-primitive-byte-widths (:cxc-ty f))))

(defn f37-string-byte-field?
  "F37 Landing 3 — a `String!` field. Encoded as `UInt32` big-endian
   length prefix + UTF-8 bytes per §3.10.10 String row. Reference-
   type, non-Optional (bare `String` is nullable per §3.6.1; L3
   handles the `String!` non-nil case; bare-`String` waits on F39 L9
   Optional plumbing for the presence-byte envelope)."
  [f]
  (and (:nonnil? f) (= "String" (:cxc-ty f))))

(defn f37-nested-record-field?
  "F37 Landing 4 — a nested record field (value-typed, non-Optional).
   Recursively encoded via the nested record's own synthesized
   `<Nested>_cxc_as_bytes`. Fields concatenated in declaration order
   without a length prefix (record encoding is fixed-shape per
   §3.10.10 record row). The nested record itself must be eligible
   at the enclosing record's synthesis time; the field-type-validity
   check surfaces at fail-nyi otherwise."
  [f record-env]
  (and (not (:optional? f))
       (contains? record-env (:cxc-ty f))))

(defn f37-optional-primitive-field?
  "F39 Landing 9 — a bare-T value-typed field (Optional-boxed under
   F39 L5b-ii-c Sub-P) whose inner type is primitive-byte-eligible.
   Encoded as `UInt8` presence byte (0/1) + T bytes when present per
   §3.10.10 Optional row. `T!` fields skip the envelope (already
   handled by L2)."
  [f]
  (and (:optional? f)
       (contains? f37-primitive-byte-widths (:cxc-ty f))))

(defn f37-optional-nested-record-field?
  "F39 Landing 9 — a bare-T value-typed field where T is a nested
   record. Encoded as presence byte + T's canonical bytes when
   present."
  [f record-env]
  (and (:optional? f)
       (contains? record-env (:cxc-ty f))))

(defn f37-enum-field?
  "F37 encoder-symmetry (2026-08-27) — a non-Optional enum field. The
   enum must be in enum-env AND eligible for `.as() -> Bytes`
   synthesis (all payload cases bytes-encodable). Encodes by
   dispatching to `<Enum>_cxc_as_bytes(a.<field>)` mirror of the
   nested-record path — no length prefix; enum encoding is fixed-shape
   per the tag + payload layout."
  [f enum-env record-env]
  (and (not (:optional? f))
       (contains? enum-env (:cxc-ty f))
       (enum-eligible-for-as-bytes-synthesis?
        (get enum-env (:cxc-ty f)) record-env)))

(defn f37-nullable-string-field?
  "F37 L6 nullable-String extension (2026-08-27) — bare-T `String`
   field. String is excluded from F39 L5b-ii-c Sub-P value-typed
   boxing (reference type stays reference); storage is `NSString *`
   directly, with nil representing absence. Wire format follows
   §3.10.10 Optional row: `UInt8` presence byte + (when present)
   `UInt32` big-endian length prefix + raw UTF-8 payload."
  [f]
  (and (= "String" (:cxc-ty f))
       (not (:nonnil? f))))

(defn f37-optional-value-only-enum-field?
  "F37 L6 Optional-value-only-enum extension (2026-08-27) — a bare-T
   value-typed field whose inner type is a value-only enum (no payload
   cases). Encoded as `UInt8` presence byte + discriminator bytes when
   present. Discriminator width follows the case-count rule shared with
   value-only-enum encode/decode (`f37-enum-discriminator-width`)."
  [f enum-env]
  (and (:optional? f)
       (contains? enum-env (:cxc-ty f))
       (not (get-in enum-env [(:cxc-ty f) :tagged?]))))

(declare f37-tagged-enum-payload-field-eligible?)

(defn f37-optional-tagged-enum-field?
  "F37 L6 Optional-tagged-enum extension (2026-08-27) — a bare-T value-
   typed field whose inner is a tagged enum with all payload fields
   primitive or `String!` (same eligibility rule as the direct tagged-
   enum arm). Encoded as `UInt8` presence byte + (when present)
   discriminator + per-case payload bytes."
  [f enum-env]
  (and (:optional? f)
       (contains? enum-env (:cxc-ty f))
       (get-in enum-env [(:cxc-ty f) :tagged?])
       (every? (fn [ce]
                 (every? f37-tagged-enum-payload-field-eligible?
                         (:payload ce)))
               (:case-entries (get enum-env (:cxc-ty f))))))

(defn f37-bytes-encodable-field?
  "F37 Landings 2+3+4+5 + F39 L9 — union of the shipped eligibility
   checks. Any field for which the encoder function has a synthesis
   path. Nested-record + enum fields require env access; 3-arity form
   adds the enum + Optional-value-only-enum arms."
  ([f]
   (or (f37-primitive-byte-field? f)
       (f37-string-byte-field? f)
       (f37-optional-primitive-field? f)
       (f37-nullable-string-field? f)))
  ([f record-env]
   (or (f37-primitive-byte-field? f)
       (f37-string-byte-field? f)
       (f37-nested-record-field? f record-env)
       (f37-optional-primitive-field? f)
       (f37-nullable-string-field? f)
       (f37-optional-nested-record-field? f record-env)))
  ([f record-env enum-env]
   (or (f37-primitive-byte-field? f)
       (f37-string-byte-field? f)
       (f37-nested-record-field? f record-env)
       (f37-optional-primitive-field? f)
       (f37-nullable-string-field? f)
       (f37-optional-nested-record-field? f record-env)
       (f37-enum-field? f enum-env record-env)
       (f37-optional-value-only-enum-field? f enum-env)
       (f37-optional-tagged-enum-field? f enum-env))))

(defn f37-primitive-value-encode-src
  "Return the C-statement string encoding a primitive value bound at
   variable name `val` into `__d`. Shared between direct (non-Optional)
   emission and the Optional-envelope inner path."
  [cxc-ty val]
  (case cxc-ty
    "Bool"
    (str "uint8_t __v = " val " ? 0x01 : 0x00;"
         " [__d appendBytes:&__v length:1];")

    ("UInt8" "Int8")
    (str "uint8_t __v = (uint8_t)" val ";"
         " [__d appendBytes:&__v length:1];")

    ("UInt16" "Int16")
    (str "uint16_t __v = OSSwapHostToBigInt16((uint16_t)" val ");"
         " [__d appendBytes:&__v length:2];")

    ("UInt32" "Int32")
    (str "uint32_t __v = OSSwapHostToBigInt32((uint32_t)" val ");"
         " [__d appendBytes:&__v length:4];")

    ("UInt64" "Int64" "Int")
    (str "uint64_t __v = OSSwapHostToBigInt64((uint64_t)" val ");"
         " [__d appendBytes:&__v length:8];")

    "Float"
    (str "uint32_t __v; memcpy(&__v, &" val ", 4);"
         " __v = OSSwapHostToBigInt32(__v);"
         " [__d appendBytes:&__v length:4];")

    "Double"
    (str "uint64_t __v; memcpy(&__v, &" val ", 8);"
         " __v = OSSwapHostToBigInt64(__v);"
         " [__d appendBytes:&__v length:8];")))

(defn f37-field-encoding-c-src
  "Emit a C compound statement that appends field `f`'s big-endian
   encoding to `__d` (the NSMutableData accumulator). Assumes the
   record var is bound as `a`. Landings 2 (primitive) + 3 (String) +
   4 (nested record) + F39 L9 (Optional envelope) + encoder-symmetry
   2026-08-27 (enum fields) — other field types raise the caller's
   fail-nyi guard first. record-env consulted for L4/L9; enum-env
   consulted for the enum-field arm."
  [f record-env enum-env]
  (let [fname (:name f)
        cxc-ty (:cxc-ty f)
        fld    (str "a." fname)]
    (cond
      (:optional? f)
      ;; F39 L9 — Optional envelope: presence byte (0/1) + payload
      ;; when present. Storage rep is `_Cxc_Optional_<T>` with
      ;; `.present` (uint8) + `.value` (raw T). Per §3.10.10:
      ;;   0x00 → absent, no payload bytes
      ;;   0x01 → present, followed by T's canonical bytes
      ;;
      ;; Inner-T dispatch order: nested-record → primitive → value-only
      ;; enum (2026-08-27 extension). Tagged-enum Optional inner is a
      ;; follow-on landing; unmatched cxc-ty at v1.0 raises the outer
      ;; encoder's fail-nyi via `record-eligible-for-as-bytes-synthesis?`.
      (let [pres-src (str "uint8_t __p = " fld ".present ? 0x01 : 0x00;"
                          " [__d appendBytes:&__p length:1];")
            val-src  (cond
                       (contains? record-env cxc-ty)
                       (str "NSData *__nd = " (->c-safe cxc-ty) "_cxc_as_bytes("
                            fld ".value); [__d appendData:__nd];")

                       (contains? f37-primitive-byte-widths cxc-ty)
                       (f37-primitive-value-encode-src cxc-ty
                                                       (str fld ".value"))

                       (and (contains? enum-env cxc-ty)
                            (not (get-in enum-env [cxc-ty :tagged?])))
                       (str "NSData *__nd = " (->c-safe cxc-ty) "_cxc_as_bytes("
                            fld ".value); [__d appendData:__nd];")

                       (and (contains? enum-env cxc-ty)
                            (get-in enum-env [cxc-ty :tagged?]))
                       (str "NSData *__nd = " (->c-safe cxc-ty) "_cxc_as_bytes("
                            fld ".value); [__d appendData:__nd];"))]
        (str "    {\n"
             "        " pres-src "\n"
             "        if (" fld ".present) { " val-src " }\n"
             "    }"))

      (contains? record-env cxc-ty)
      ;; F37 Landing 4 — nested record dispatches to its own
      ;; synthesized fn. No length prefix per §3.10.10 record row
      ;; (fixed-shape concatenation). Forward declarations at emit
      ;; time make dispatch order irrelevant.
      (str "    { NSData *__nd = " (->c-safe cxc-ty) "_cxc_as_bytes(" fld ");"
           " [__d appendData:__nd]; }")

      (f37-enum-field? f enum-env record-env)
      ;; F37 encoder-symmetry (2026-08-27) — enum field dispatches to
      ;; the enum's synthesized `<Enum>_cxc_as_bytes`. Same shape as
      ;; nested-record: no length prefix, encoding is discriminator +
      ;; (per-case) payload per §3.10.10. Enables round-trip with the
      ;; L6 enum decode extension.
      (str "    { NSData *__nd = " (->c-safe cxc-ty) "_cxc_as_bytes(" fld ");"
           " [__d appendData:__nd]; }")

      (f37-nullable-string-field? f)
      ;; F37 L6 nullable-String extension (2026-08-27) — reference-
      ;; typed nullable `String` (bare `String`, not `String!`). Wire
      ;; format follows §3.10.10 Optional row directly: `UInt8`
      ;; presence byte (0 if nil, 1 if not) + (when present) `UInt32`
      ;; big-endian length prefix + raw UTF-8 payload. No struct
      ;; envelope; presence keyed off `a.<field> != nil`. NSString
      ;; round-trips through UTF-8 losslessly, so no encode-side
      ;; validity guard.
      (str "    {\n"
           "        uint8_t __p = (" fld " != nil) ? 0x01 : 0x00;\n"
           "        [__d appendBytes:&__p length:1];\n"
           "        if (" fld " != nil) {\n"
           "            NSData *__sd = [" fld " dataUsingEncoding:"
           "NSUTF8StringEncoding];\n"
           "            uint32_t __sdlen = OSSwapHostToBigInt32("
           "(uint32_t)[__sd length]);\n"
           "            [__d appendBytes:&__sdlen length:4];\n"
           "            [__d appendData:__sd];\n"
           "        }\n"
           "    }")

      :else
      (case cxc-ty
      "Bool"
      (str "    { uint8_t __v = " fld " ? 0x01 : 0x00;"
           " [__d appendBytes:&__v length:1]; }")

      ("UInt8" "Int8")
      (str "    { uint8_t __v = (uint8_t)" fld ";"
           " [__d appendBytes:&__v length:1]; }")

      ("UInt16" "Int16")
      (str "    { uint16_t __v = OSSwapHostToBigInt16((uint16_t)" fld ");"
           " [__d appendBytes:&__v length:2]; }")

      ("UInt32" "Int32")
      (str "    { uint32_t __v = OSSwapHostToBigInt32((uint32_t)" fld ");"
           " [__d appendBytes:&__v length:4]; }")

      ("UInt64" "Int64" "Int")
      (str "    { uint64_t __v = OSSwapHostToBigInt64((uint64_t)" fld ");"
           " [__d appendBytes:&__v length:8]; }")

      "Float"
      ;; Type-pun through uint32_t to byteswap IEEE 754 bits without
      ;; touching the float value's representation.
      (str "    { uint32_t __v; memcpy(&__v, &" fld ", 4);"
           " __v = OSSwapHostToBigInt32(__v);"
           " [__d appendBytes:&__v length:4]; }")

      "Double"
      (str "    { uint64_t __v; memcpy(&__v, &" fld ", 8);"
           " __v = OSSwapHostToBigInt64(__v);"
           " [__d appendBytes:&__v length:8]; }")

      "String"
      ;; F37 Landing 3 — String canonical bytes: `UInt32` big-endian
      ;; length prefix + raw UTF-8 bytes per §3.10.10 String row.
      ;; NSString round-trips to valid UTF-8 (Unicode bijection at the
      ;; Unicode-scalar level); no encode-side invalidity to guard
      ;; against. Decode-side invalidity is F37 Landing 6.
      (str "    {\n"
           "        NSData *__sd = [" fld " dataUsingEncoding:NSUTF8StringEncoding];\n"
           "        uint32_t __len = OSSwapHostToBigInt32((uint32_t)[__sd length]);\n"
           "        [__d appendBytes:&__len length:4];\n"
           "        [__d appendData:__sd];\n"
           "    }")))))

(defn record-has-user-as-method?
  "True when the record explicitly declares an `as` callable — either
   as an extension method landed in `:method-index` or as an own
   observable landed in `:observable-index`. Either surface shadows
   the synthesized `.as() -> Bytes` default per spec §3.3.2 rule 6.
   Post F67 L4 records carry their own callable readers as
   observables, so checking `:method-index` alone (the pre-collapse
   surface) missed same-record overrides."
  [info]
  (or (contains? (get-in info [:method-index])     "as")
      (contains? (get-in info [:observable-index]) "as")))

(defn record-eligible-for-as-bytes-synthesis?
  ([info]
   (and (not (record-has-user-as-method? info))
        (every? f37-bytes-encodable-field? (:fields info))))
  ([info record-env]
   (and (not (record-has-user-as-method? info))
        (every? #(f37-bytes-encodable-field? % record-env) (:fields info))))
  ([info record-env enum-env]
   (and (not (record-has-user-as-method? info))
        (every? #(f37-bytes-encodable-field? % record-env enum-env)
                (:fields info)))))

(defn synthesize-record-as-bytes-fn
  "F37 Landings 2 + 3 + 4 — produce a `:c-function` node implementing
   `<Record>_cxc_as_bytes(<Record> a) -> NSMutableData *`. Precondition:
   `record-eligible-for-as-bytes-synthesis?` returned true.

   Capacity hint sums the compile-time-known widths of primitive
   fields; String / nested-record / enum fields are variable-length
   (or opaque) so any record containing one drops the hint (passes 0)."
  [record-name info record-env enum-env]
  (let [fields    (:fields info)
        all-fixed? (every? f37-primitive-byte-field? fields)
        total     (if all-fixed?
                    (reduce + 0 (map #(get f37-primitive-byte-widths (:cxc-ty %))
                                     fields))
                    0)
        head      (str "    NSMutableData *__d = [NSMutableData dataWithCapacity:"
                       total "];")
        blocks    (map #(f37-field-encoding-c-src % record-env enum-env) fields)
        body-src  (str/join "\n" (concat [head] blocks ["    return __d;"]))]
    {:node        :c-function
     :name        (record-synth-as-bytes-name record-name)
     :return-type "NSMutableData *"
     :params      [{:name "a" :c-type (->c-safe record-name)}]
     :body        [{:node :raw-c :text body-src}]}))

(defn enum-synth-as-bytes-name [enum-name]
  (str (->c-safe enum-name) "_cxc_as_bytes"))

(defn enum-synth-to-string-name [enum-name]
  (str (->c-safe enum-name) "_cxc_to_string"))

(defn synthesize-enum-to-string-fn
  "2026-10-02 — produce a `:c-function` node implementing
   `<Enum>_cxc_to_string(<Enum> a) -> NSString *`. Switch over the
   case tag (value-only enums switch on the enum value directly;
   tagged enums switch on `a.tag`). Each arm returns an interned
   `@\"<case-name>\"` NSString. Payload values are not formatted —
   just the case name — matching Python / Clojure keyword semantics."
  [enum-name info]
  (let [entries   (:case-entries info)
        tagged?   (:tagged? info)
        disc-expr (if tagged? "a.tag" "a")
        safe-en   (->c-safe enum-name)
        arms      (for [ce entries]
                    (let [case-nm (:name ce)
                          tag-c   (if tagged?
                                    (str safe-en "_Tag_" case-nm)
                                    (str safe-en "_" case-nm))]
                      (str "    case " tag-c ": return @\"" case-nm "\";")))
        body-src  (str "    switch (" disc-expr ") {\n"
                       (str/join "\n" arms) "\n"
                       "    }\n"
                       "    return @\"?\";")]
    {:node        :c-function
     :name        (enum-synth-to-string-name enum-name)
     :return-type "NSString *"
     :params      [{:name "a" :c-type safe-en}]
     :body        [{:node :raw-c :text body-src}]}))

(defn f37-enum-discriminator-width
  "F37 L5 — discriminator width in bytes per §3.10.10: ≤256 → 1, ≤65536
   → 2, otherwise 4. Case count is total, not tag-value range — a
   256-case enum uses `UInt8` because tag values are 0..255."
  [case-count]
  (cond
    (<= case-count 256)    1
    (<= case-count 65536)  2
    :else                  4))

(defn f37-enum-discriminator-c
  "F37 L5 — emit a C statement appending the discriminator to `__d`.
   `disc-expr` is the source expression for the tag (`a` for value-
   only enums, `a.tag` for tagged enums). Big-endian for widths > 1."
  [disc-expr width]
  (case width
    1 (str "uint8_t __disc = (uint8_t)(" disc-expr ");"
           " [__d appendBytes:&__disc length:1];")
    2 (str "uint16_t __disc = OSSwapHostToBigInt16((uint16_t)(" disc-expr "));"
           " [__d appendBytes:&__disc length:2];")
    4 (str "uint32_t __disc = OSSwapHostToBigInt32((uint32_t)(" disc-expr "));"
           " [__d appendBytes:&__disc length:4];")))

(defn f37-enum-payload-field-for-encoding
  "F37 L5 — normalize an enum payload param into the field shape
   `f37-field-encoding-c-src` expects: `{:name :cxc-ty :nonnil?
   :optional?}`. Payload params carry their `:type` node with a
   `:type-nonnil` wrapper when the user wrote `T!`."
  [param]
  (let [ty-node (:type param)
        nonnil? (= :type-nonnil (:node ty-node))
        inner   (if nonnil? (:inner ty-node) ty-node)
        cxc-ty  (:name inner)]
    {:name      (:name param)
     :cxc-ty    cxc-ty
     :nonnil?   nonnil?
     :optional? false}))

(defn f37-enum-payload-field-encoding-c-src
  "F37 L5 — encode one payload field inside a case block. Reads from
   `a.data.<case>.<field>` rather than `a.<field>` (record) — uses
   the same primitive/String encoding helpers by rewriting the
   accessor prefix."
  [case-nm payload-field record-env]
  (let [f       (f37-enum-payload-field-for-encoding payload-field)
        cxc-ty  (:cxc-ty f)
        fname   (:name f)
        fld     (str "a.data." case-nm "." fname)]
    (cond
      (contains? record-env cxc-ty)
      (str "        { NSData *__nd = " (->c-safe cxc-ty) "_cxc_as_bytes(" fld ");"
           " [__d appendData:__nd]; }")

      (= "String" cxc-ty)
      (str "        {\n"
           "            NSData *__sd = [" fld " dataUsingEncoding:NSUTF8StringEncoding];\n"
           "            uint32_t __len = OSSwapHostToBigInt32((uint32_t)[__sd length]);\n"
           "            [__d appendBytes:&__len length:4];\n"
           "            [__d appendData:__sd];\n"
           "        }")

      (contains? f37-primitive-byte-widths cxc-ty)
      (str "        { " (f37-primitive-value-encode-src cxc-ty fld) " }"))))

(defn synthesize-enum-as-bytes-fn
  "F37 Landing 5 — produce a `:c-function` node implementing
   `<Enum>_cxc_as_bytes(<Enum> a) -> NSMutableData *`. Discriminator
   width per §3.10.10 case count. Payload cases append per-field
   encoding after the discriminator via a switch on the tag.

   Value-only enums (no case carries payload) skip the switch — the
   discriminator alone is the full encoding. Tagged enums emit a
   `switch (a.tag) { case E_Tag_<case>: {...encode fields...} break; ... }`."
  [enum-name info record-env]
  (let [entries    (:case-entries info)
        tagged?    (:tagged? info)
        disc-width (f37-enum-discriminator-width (count entries))
        disc-expr  (if tagged? "a.tag" "a")
        head       (str "    NSMutableData *__d = "
                        "[NSMutableData dataWithCapacity:" disc-width "];")
        disc-src   (str "    " (f37-enum-discriminator-c disc-expr disc-width))
        switch-src
        (when tagged?
          (let [arms
                (for [ce entries]
                  (let [case-nm (:name ce)
                        fields  (:payload ce)
                        blocks  (if (seq fields)
                                  (str/join "\n"
                                            (map #(f37-enum-payload-field-encoding-c-src
                                                   case-nm % record-env)
                                                 fields))
                                  "")]
                    (str "    case " (->c-safe enum-name) "_Tag_" case-nm ": {\n"
                         (when (seq blocks) (str blocks "\n"))
                         "        break;\n"
                         "    }")))]
            (str "    switch (a.tag) {\n"
                 (str/join "\n" arms)
                 "\n    }")))
        body-src   (str/join "\n"
                             (remove nil?
                                     [head disc-src switch-src "    return __d;"]))]
    {:node        :c-function
     :name        (enum-synth-as-bytes-name enum-name)
     :return-type "NSMutableData *"
     :params      [{:name "a" :c-type (->c-safe enum-name)}]
     :body        [{:node :raw-c :text body-src}]}))

(defn enum-eligible-for-as-bytes-synthesis?
  "F37 L5 — every payload field on every case must be bytes-encodable.
   Value-only enums (no payload cases) are always eligible."
  [info record-env]
  (every? (fn [ce]
            (every? #(f37-bytes-encodable-field?
                       (f37-enum-payload-field-for-encoding %)
                       record-env)
                    (:payload ce)))
          (:case-entries info)))

;; ---------------------------------------------------------------------------
;; F37 Landing 6 — `class function from(source: Bytes) -> DecodeOutcome<Self>`
;; synthesis. Mirror of Landing 2's `.as() -> Bytes` encoder. Per plan §Landing
;; 6, DecodeOutcome is per-record monomorphized (no runtime generics) — each
;; record gets its own `_Cxc_DecodeOutcome_<R>` struct + factory helpers.
;;
;; Primitive-only MVP: records whose fields are all primitive-byte-eligible
;; (Bool / sized int / Int / Float / Double). String / nested / Optional /
;; enum decoding lands as follow-on sub-landings — the intercept fails-nyi
;; with a targeted diagnostic pointing at the specific extension needed.
;; ---------------------------------------------------------------------------

(defn decode-outcome-struct-name [record-name]
  (str "_Cxc_DecodeOutcome_" (->c-safe record-name)))

(defn decode-invalid-fn-name [record-name]
  (str "_cxc_decode_invalid_" (->c-safe record-name)))

(defn decode-converted-fn-name [record-name]
  (str "_cxc_decode_converted_" (->c-safe record-name)))

(defn record-synth-from-bytes-name [record-name]
  (str (->c-safe record-name) "_cxc_from_bytes"))

(defn f37-string-field-decode-c-src
  "F37 L6 String extension — decode a `String!` field. Wire format
   per §3.10.10: UInt32 big-endian length prefix + raw UTF-8 bytes.
   Two bounds checks (prefix + payload) and one UTF-8 validity check;
   any short read or invalid UTF-8 short-circuits to
   `_cxc_decode_invalid_<R>(offset, reason)` with a per-failure-mode
   reason. Sets `NSString *__<field>_val` bound to the decoded string."
  [record-name f]
  (let [fname   (:name f)
        bind    (str "__" fname "_val")
        invalid (decode-invalid-fn-name record-name)]
    (str "    if (__len < __off + 4) return " invalid
         "((int64_t)__off, @\"expected 4 bytes for field " fname
         " length, source has less\");\n"
         "    uint32_t " bind "_len_raw;\n"
         "    memcpy(&" bind "_len_raw, __bytes + __off, 4);\n"
         "    uint32_t " bind "_len = OSSwapBigToHostInt32(" bind "_len_raw);\n"
         "    __off += 4;\n"
         "    if (__len < __off + " bind "_len) return " invalid
         "((int64_t)__off, @\"expected N bytes for field " fname
         ", source has less than declared length\");\n"
         "    NSString *" bind " = [[NSString alloc] "
         "initWithBytes:__bytes + __off length:" bind "_len "
         "encoding:NSUTF8StringEncoding];\n"
         "    if (!" bind ") return " invalid
         "((int64_t)__off, @\"invalid UTF-8 for field " fname "\");\n"
         "    __off += " bind "_len;")))

(defn f37-primitive-field-decode-c-src
  "Return the C statement string decoding a primitive field from
   `__bytes` at `__off`, binding the result to a local named
   `__<field>_val` typed appropriately, then advancing `__off`.
   Bounds-check emitted before the read; returns `_cxc_decode_invalid_<R>(...)`
   on short input. `record-name` is the enclosing record — used to name
   the invalid factory (per-record outcome type)."
  [record-name f]
  (let [fname  (:name f)
        cxc-ty (:cxc-ty f)
        width  (get f37-primitive-byte-widths cxc-ty)
        bind   (str "__" fname "_val")
        bounds (str "    if (__len < __off + " width ") return "
                    (decode-invalid-fn-name record-name)
                    "((int64_t)__off, "
                    "@\"expected " width " bytes for field " fname
                    ", source has less\");")
        read-src
        (case cxc-ty
          "Bool"
          (str "    uint8_t " bind "_raw = __bytes[__off];\n"
               "    if (" bind "_raw != 0x00 && " bind "_raw != 0x01) return "
               (decode-invalid-fn-name record-name)
               "((int64_t)__off, @\"invalid Bool byte 0x%02x for field "
               fname "\");\n"
               "    bool " bind " = (" bind "_raw == 0x01);\n"
               "    __off += 1;")

          ("UInt8" "Int8")
          (str "    uint8_t " bind " = __bytes[__off];\n"
               "    __off += 1;")

          ("UInt16" "Int16")
          (str "    uint16_t " bind "_raw;\n"
               "    memcpy(&" bind "_raw, __bytes + __off, 2);\n"
               "    " (if (= cxc-ty "Int16") "int16_t " "uint16_t ")
               bind " = " (if (= cxc-ty "Int16") "(int16_t)" "")
               "OSSwapBigToHostInt16(" bind "_raw);\n"
               "    __off += 2;")

          ("UInt32" "Int32")
          (str "    uint32_t " bind "_raw;\n"
               "    memcpy(&" bind "_raw, __bytes + __off, 4);\n"
               "    " (if (= cxc-ty "Int32") "int32_t " "uint32_t ")
               bind " = " (if (= cxc-ty "Int32") "(int32_t)" "")
               "OSSwapBigToHostInt32(" bind "_raw);\n"
               "    __off += 4;")

          ("UInt64" "Int64" "Int")
          (str "    uint64_t " bind "_raw;\n"
               "    memcpy(&" bind "_raw, __bytes + __off, 8);\n"
               "    int64_t " bind " = (int64_t)OSSwapBigToHostInt64("
               bind "_raw);\n"
               "    __off += 8;")

          "Float"
          (str "    uint32_t " bind "_raw;\n"
               "    memcpy(&" bind "_raw, __bytes + __off, 4);\n"
               "    " bind "_raw = OSSwapBigToHostInt32(" bind "_raw);\n"
               "    float " bind ";\n"
               "    memcpy(&" bind ", &" bind "_raw, 4);\n"
               "    __off += 4;")

          "Double"
          (str "    uint64_t " bind "_raw;\n"
               "    memcpy(&" bind "_raw, __bytes + __off, 8);\n"
               "    " bind "_raw = OSSwapBigToHostInt64(" bind "_raw);\n"
               "    double " bind ";\n"
               "    memcpy(&" bind ", &" bind "_raw, 8);\n"
               "    __off += 8;"))]
    (str bounds "\n" read-src)))

(defn record-decode-eligible?
  "F37 L6 (2026-08-27 variable-length ABI) — a record is decode-
   eligible when every field is `f37-decode-eligible-field?` at 2-arity
   or 3-arity coverage. Terminates on any valid program because value-
   typed field cycles (A→B→A stored inline) would be infinite storage
   and rejected earlier."
  ([record-name record-env]
   (when-let [info (get record-env record-name)]
     (every? #(f37-decode-eligible-field? % record-env) (:fields info))))
  ([record-name record-env enum-env]
   (when-let [info (get record-env record-name)]
     (every? #(f37-decode-eligible-field? % record-env enum-env)
             (:fields info)))))

(defn f37-nested-record-decode-field?
  "F37 L6 nested-record extension eligibility (variable-length ABI,
   2026-08-27). Non-Optional field whose type is a record in record-env
   AND that record is transitively decode-eligible (fixed-size + String
   + Optional-* + enum fields all fine). Prior fixed-size-only gate
   retired with the ABI change — Inner is called at the outer's __off
   with `consumed_out` to report bytes read, so variable-length inner
   is no longer a size-unknown-at-slice-time problem."
  [f record-env]
  (and (not (:optional? f))
       (contains? record-env (:cxc-ty f))
       (record-decode-eligible? (:cxc-ty f) record-env)))

(declare f37-value-only-enum-decode-field?
         f37-optional-nested-record-decode-field?
         f37-tagged-enum-decode-field?
         f37-optional-value-only-enum-decode-field?
         f37-optional-tagged-enum-decode-field?
         f37-nullable-string-decode-field?)

(defn f37-decode-eligible-field?
  "F37 L6 field-level eligibility. Primitive (Bool / sized-int / Int /
   Float / Double), `String!`, nested-record (fixed-size inner),
   Optional-boxed primitive, and value-only enum fields all decode.
   Tagged-enum / variable-length-nested-record / Optional-nested-record
   fields defer to follow-on sub-landings.

   Three arities: 1-arity for env-agnostic checks (primitive + String +
   Optional-primitive); 2-arity adds the nested-record path; 3-arity
   adds the value-only enum path. Callers with access to enum-env should
   prefer the 3-arity form so enum fields aren't misdiagnosed as
   non-eligible."
  ([f]
   (or (f37-primitive-byte-field? f)
       (f37-string-byte-field? f)
       (f37-optional-primitive-field? f)
       (f37-nullable-string-decode-field? f)))
  ([f record-env]
   (or (f37-primitive-byte-field? f)
       (f37-string-byte-field? f)
       (f37-optional-primitive-field? f)
       (f37-nullable-string-decode-field? f)
       (f37-nested-record-decode-field? f record-env)
       (f37-optional-nested-record-decode-field? f record-env)))
  ([f record-env enum-env]
   (or (f37-primitive-byte-field? f)
       (f37-string-byte-field? f)
       (f37-optional-primitive-field? f)
       (f37-nullable-string-decode-field? f)
       (f37-nested-record-decode-field? f record-env)
       (f37-optional-nested-record-decode-field? f record-env)
       (f37-value-only-enum-decode-field? f enum-env)
       (f37-tagged-enum-decode-field? f enum-env)
       (f37-optional-value-only-enum-decode-field? f enum-env)
       (f37-optional-tagged-enum-decode-field? f enum-env))))

(defn record-eligible-for-from-bytes-synthesis?
  "F37 L6 eligibility: every field must be decode-eligible. Coverage
   grows with each arity: 1-arity handles primitive + `String!` +
   Optional-primitive; 2-arity adds fixed-size nested-record;
   3-arity adds value-only enum. Variable-length nested-record /
   tagged enum / Optional-nested-record / Optional-enum fields defer
   to follow-on sub-landings; those records emit a fail-nyi at the
   `.from()` intercept rather than at synthesis time (avoids
   generating unreachable code).

   Prefer the highest-arity form callers have envs for so field kinds
   aren't misdiagnosed as non-eligible."
  ([info]
   (and (not (record-has-user-as-method? info))
        (every? f37-decode-eligible-field? (:fields info))))
  ([info record-env]
   (and (not (record-has-user-as-method? info))
        (every? #(f37-decode-eligible-field? % record-env) (:fields info))))
  ([info record-env enum-env]
   (and (not (record-has-user-as-method? info))
        (every? #(f37-decode-eligible-field? % record-env enum-env)
                (:fields info)))))

(defn synthesize-decode-outcome-struct
  "Per-record `_Cxc_DecodeOutcome_<R>` C struct. `.tag` selects
   `_CXC_DECODE_CONVERTED` or `_CXC_DECODE_INVALID`;
   `.data.converted.value` holds the decoded record on success;
   `.data.invalid` carries `.offset` + `.reason` on failure. Mirror of
   F16 tagged-enum lowering, but hand-rolled here because DecodeOutcome
   isn't a user-declared enum (no F16 AST node to route through).

   Uses `struct` (not `union`) for `data` because ObjC ARC forbids
   strong `NSString *` inside a union — ARC can't decide when to
   release across variant transitions. The struct form carries both
   variants at once (wastes a few bytes per outcome) but the code
   remains ARC-clean and the caller reads `.tag` before dereferencing
   the matching payload."
  [record-name]
  {:node :raw-c-decl
   :text (str "typedef struct " (decode-outcome-struct-name record-name)
              " {\n"
              "    uint8_t tag;\n"
              "    struct {\n"
              "        struct { " (->c-safe record-name) " value; } converted;\n"
              "        struct { int64_t offset; NSString *reason; } invalid;\n"
              "    } data;\n"
              "} " (decode-outcome-struct-name record-name) ";\n"
              "#ifndef _CXC_DECODE_CONVERTED\n"
              "#define _CXC_DECODE_CONVERTED 1\n"
              "#define _CXC_DECODE_INVALID   0\n"
              "#endif")})

(defn synthesize-decode-factory-fns
  "Emit the two per-record factory C functions:
     _cxc_decode_converted_<R>(<R> v) -> <_Cxc_DecodeOutcome_R>
     _cxc_decode_invalid_<R>(int64_t offset, NSString *reason) -> <_Cxc_DecodeOutcome_R>
   Compact designated-initializer constructors for the outcome struct."
  [record-name]
  (let [outcome (decode-outcome-struct-name record-name)]
    [{:node        :c-function
      :name        (decode-converted-fn-name record-name)
      :return-type outcome
      :params      [{:name "v" :c-type (->c-safe record-name)}]
      :body        [{:node :raw-c
                     :text (str "    return (" outcome
                                "){ .tag = _CXC_DECODE_CONVERTED,"
                                " .data.converted.value = v };")}]}
     {:node        :c-function
      :name        (decode-invalid-fn-name record-name)
      :return-type outcome
      :params      [{:name "offset" :c-type "int64_t"}
                    {:name "reason" :c-type "NSString *"}]
      :body        [{:node :raw-c
                     :text (str "    return (" outcome
                                "){ .tag = _CXC_DECODE_INVALID,"
                                " .data.invalid = { .offset = offset,"
                                " .reason = reason } };")}]}]))

(defn f37-nested-record-field-decode-c-src
  "F37 L6 nested-record extension — decode a nested-record field.
   Since 2026-08-27 (variable-length ABI change): calls the inner's
   `_cxc_from_bytes(src, __off, &__consumed)` directly, without pre-
   slicing or a fixed-size bounds check — the inner does its own bounds
   checks against `src`'s full length at the correct starting offset,
   and reports bytes consumed via the out-param. On invalid, propagate
   the offset directly (already absolute since inner started at `__off`).
   On converted, extract `.value` and advance `__off += __consumed`.
   Variable-length inner (Inner containing String!, Optional, another
   variable-length inner) works transitively."
  [record-name f _record-env]
  (let [fname   (:name f)
        inner   (:cxc-ty f)
        bind    (str "__" fname "_val")
        res     (str "__" fname "_res")
        cons    (str "__" fname "_consumed")
        invalid (decode-invalid-fn-name record-name)]
    (str "    int64_t " cons " = 0;\n"
         "    " (decode-outcome-struct-name inner) " " res
         " = " (record-synth-from-bytes-name inner)
         "(src, (int64_t)__off, &" cons ");\n"
         "    if (" res ".tag == _CXC_DECODE_INVALID) return " invalid
         "(" res ".data.invalid.offset, " res ".data.invalid.reason);\n"
         "    " (->c-safe inner) " " bind " = " res ".data.converted.value;\n"
         "    __off += (NSUInteger)" cons ";")))

(defn f37-optional-nested-record-decode-field?
  "F37 L6 Optional-nested-record extension (2026-08-27) — bare-T
   value-typed field where T is a decode-eligible nested record.
   Variable-length inner allowed since the 2026-08-27 ABI change:
   inner is called at __off with `consumed_out` reporting bytes read.
   Wire format per §3.10.10 Optional row: UInt8 presence byte +
   (when present) T's canonical bytes."
  [f record-env]
  (and (:optional? f)
       (contains? record-env (:cxc-ty f))
       (record-decode-eligible? (:cxc-ty f) record-env)))

(defn f37-optional-nested-record-field-decode-c-src
  "F37 L6 Optional-nested-record extension — decode a bare-T value-
   typed field where T is a decode-eligible nested record. Presence
   byte with validity check; on present, dispatch to
   `T_cxc_from_bytes(src, __off, &__consumed)` with the variable-
   length ABI (2026-08-27) — no pre-slice, no fixed-size bounds check;
   inner reads at __off and reports bytes consumed. On invalid,
   propagate directly (offset already absolute). On converted, land
   in `_Cxc_Optional_<T>.value` and advance by consumed."
  [record-name f _record-env]
  (let [fname   (:name f)
        inner   (:cxc-ty f)
        bind    (str "__" fname "_val")
        res     (str "__" fname "_res")
        cons    (str "__" fname "_consumed")
        invalid (decode-invalid-fn-name record-name)
        opt-ty  (optional-type-name inner)]
    (str "    if (__len < __off + 1) return " invalid
         "((int64_t)__off, @\"expected 1 presence byte for Optional "
         "nested-record field " fname ", source has less\");\n"
         "    uint8_t " bind "_presence = __bytes[__off];\n"
         "    __off += 1;\n"
         "    if (" bind "_presence != 0x00 && " bind "_presence != 0x01) return "
         invalid "((int64_t)(__off - 1), @\"invalid presence byte for "
         "Optional nested-record field " fname "\");\n"
         "    " opt-ty " " bind " = { .present = " bind "_presence, .value = {0} };\n"
         "    if (" bind "_presence == 0x01) {\n"
         "        int64_t " cons " = 0;\n"
         "        " (decode-outcome-struct-name inner) " " res
         " = " (record-synth-from-bytes-name inner)
         "(src, (int64_t)__off, &" cons ");\n"
         "        if (" res ".tag == _CXC_DECODE_INVALID) return " invalid
         "(" res ".data.invalid.offset, " res ".data.invalid.reason);\n"
         "        " bind ".value = " res ".data.converted.value;\n"
         "        __off += (NSUInteger)" cons ";\n"
         "    }")))

(defn f37-tagged-enum-payload-field-eligible?
  "F37 L6 tagged-enum extension — a payload field is decode-eligible
   when its normalized shape is a fixed-width primitive or `String!`.
   Nested-record / enum / Optional payload fields defer (would need
   nested outcome propagation or presence envelopes)."
  [payload-param]
  (let [f (f37-enum-payload-field-for-encoding payload-param)]
    (or (f37-primitive-byte-field? f)
        (f37-string-byte-field? f))))

(defn f37-tagged-enum-decode-field?
  "F37 L6 tagged-enum extension eligibility — non-Optional field whose
   type is a tagged enum (`:tagged? true`) where every payload field on
   every case is either primitive or `String!`."
  [f enum-env]
  (and (not (:optional? f))
       (contains? enum-env (:cxc-ty f))
       (get-in enum-env [(:cxc-ty f) :tagged?])
       (every? (fn [ce]
                 (every? f37-tagged-enum-payload-field-eligible?
                         (:payload ce)))
               (:case-entries (get enum-env (:cxc-ty f))))))

(defn f37-tagged-enum-payload-decode-c-src
  "F37 L6 tagged-enum extension — emit the C for decoding a single
   payload field into `<bind>.data.<case>.<pfield-name>`. `bind` is the
   caller-supplied storage path: `__<record-field>_val` for the direct
   tagged-enum field arm, `__<record-field>_val.value` for the Optional-
   tagged-enum arm (F37 L6 Optional-tagged-enum extension 2026-08-27).
   Shares the primitive + String decode helpers but writes directly
   into the target member rather than a fresh local binding."
  [record-name record-field-name case-nm pparam bind]
  (let [f (f37-enum-payload-field-for-encoding pparam)
        pname (:name f)
        target (str bind ".data." case-nm "." pname)
        invalid (decode-invalid-fn-name record-name)]
    (cond
      (f37-primitive-byte-field? f)
      (let [cxc-ty (:cxc-ty f)
            width  (get f37-primitive-byte-widths cxc-ty)
            raw    (str "__" record-field-name "_" case-nm "_" pname "_raw")
            bounds (str "            if (__len < __off + " width ") return "
                        invalid "((int64_t)__off, @\"expected " width
                        " bytes for payload field " pname " of "
                        record-field-name "." case-nm ", source has less\");")
            read-src
            (case cxc-ty
              "Bool"
              (str "            uint8_t " raw " = __bytes[__off];\n"
                   "            if (" raw " != 0x00 && " raw " != 0x01) return "
                   invalid "((int64_t)__off, @\"invalid Bool byte in payload "
                   pname " of " record-field-name "." case-nm "\");\n"
                   "            " target " = (" raw " == 0x01);\n"
                   "            __off += 1;")
              ("UInt8" "Int8")
              (str "            " target " = (" cxc-ty ")__bytes[__off];\n"
                   "            __off += 1;")
              ("UInt16" "Int16")
              (str "            uint16_t " raw ";\n"
                   "            memcpy(&" raw ", __bytes + __off, 2);\n"
                   "            " target " = " (if (= cxc-ty "Int16") "(int16_t)" "")
                   "OSSwapBigToHostInt16(" raw ");\n"
                   "            __off += 2;")
              ("UInt32" "Int32")
              (str "            uint32_t " raw ";\n"
                   "            memcpy(&" raw ", __bytes + __off, 4);\n"
                   "            " target " = " (if (= cxc-ty "Int32") "(int32_t)" "")
                   "OSSwapBigToHostInt32(" raw ");\n"
                   "            __off += 4;")
              ("UInt64" "Int64" "Int")
              (str "            uint64_t " raw ";\n"
                   "            memcpy(&" raw ", __bytes + __off, 8);\n"
                   "            " target " = (int64_t)OSSwapBigToHostInt64(" raw ");\n"
                   "            __off += 8;")
              "Float"
              (str "            uint32_t " raw ";\n"
                   "            memcpy(&" raw ", __bytes + __off, 4);\n"
                   "            " raw " = OSSwapBigToHostInt32(" raw ");\n"
                   "            memcpy(&" target ", &" raw ", 4);\n"
                   "            __off += 4;")
              "Double"
              (str "            uint64_t " raw ";\n"
                   "            memcpy(&" raw ", __bytes + __off, 8);\n"
                   "            " raw " = OSSwapBigToHostInt64(" raw ");\n"
                   "            memcpy(&" target ", &" raw ", 8);\n"
                   "            __off += 8;"))]
        (str bounds "\n" read-src))

      (f37-string-byte-field? f)
      (let [lenv (str "__" record-field-name "_" case-nm "_" pname "_len")
            raw  (str lenv "_raw")]
        (str "            if (__len < __off + 4) return " invalid
             "((int64_t)__off, @\"expected 4 bytes for payload String "
             "length " pname " of " record-field-name "." case-nm
             ", source has less\");\n"
             "            uint32_t " raw ";\n"
             "            memcpy(&" raw ", __bytes + __off, 4);\n"
             "            uint32_t " lenv " = OSSwapBigToHostInt32(" raw ");\n"
             "            __off += 4;\n"
             "            if (__len < __off + " lenv ") return " invalid
             "((int64_t)__off, @\"expected " lenv " bytes for payload "
             "String " pname " of " record-field-name "." case-nm
             ", source has less than declared length\");\n"
             "            " target " = [[NSString alloc] "
             "initWithBytes:__bytes + __off length:" lenv " "
             "encoding:NSUTF8StringEncoding];\n"
             "            if (!" target ") return " invalid
             "((int64_t)__off, @\"invalid UTF-8 in payload String "
             pname " of " record-field-name "." case-nm "\");\n"
             "            __off += " lenv ";")))))

(defn f37-tagged-enum-field-decode-c-src
  "F37 L6 tagged-enum extension — decode a field whose type is a
   tagged enum. Reads discriminator (width per case count), range-
   checks, then switches on the tag to decode per-case payloads
   directly into the union member. Bare cases produce an empty arm."
  [record-name f enum-env]
  (let [fname   (:name f)
        cxc-ty  (:cxc-ty f)
        c-ty    (->c-safe cxc-ty)
        info    (get enum-env cxc-ty)
        entries (:case-entries info)
        n       (count entries)
        width   (f37-enum-discriminator-width n)
        bind    (str "__" fname "_val")
        raw     (str "__" fname "_disc")
        invalid (decode-invalid-fn-name record-name)
        disc-read
        (case width
          1 (str "    uint8_t " raw " = __bytes[__off];\n"
                 "    __off += 1;")
          2 (str "    uint16_t " raw "_be;\n"
                 "    memcpy(&" raw "_be, __bytes + __off, 2);\n"
                 "    uint16_t " raw " = OSSwapBigToHostInt16(" raw "_be);\n"
                 "    __off += 2;")
          4 (str "    uint32_t " raw "_be;\n"
                 "    memcpy(&" raw "_be, __bytes + __off, 4);\n"
                 "    uint32_t " raw " = OSSwapBigToHostInt32(" raw "_be);\n"
                 "    __off += 4;"))
        arms
        (for [ce entries]
          (let [case-nm (:name ce)
                fields  (:payload ce)
                blocks  (if (seq fields)
                          (str/join "\n"
                                    (map #(f37-tagged-enum-payload-decode-c-src
                                           record-name fname case-nm % bind)
                                         fields))
                          "")]
            (str "        case " c-ty "_Tag_" case-nm ": {\n"
                 (when (seq blocks) (str blocks "\n"))
                 "            break;\n"
                 "        }")))]
    (str "    if (__len < __off + " width ") return " invalid
         "((int64_t)__off, @\"expected " width " bytes for tagged enum "
         "field " fname " (" cxc-ty "), source has less\");\n"
         disc-read "\n"
         "    if (" raw " >= " n ") return " invalid
         "((int64_t)(__off - " width "), @\"invalid discriminator for "
         "tagged enum field " fname " (" cxc-ty ")\");\n"
         "    " c-ty " " bind ";\n"
         "    " bind ".tag = (" c-ty "_Tag)" raw ";\n"
         "    switch (" raw ") {\n"
         (str/join "\n" arms) "\n"
         "    }")))

(defn f37-value-only-enum-decode-field?
  "F37 L6 enum extension eligibility (value-only enums only, this
   sub-landing). Field type is an enum in enum-env and the enum has no
   payload cases (`:tagged? false`). Tagged-enum decode is a follow-on
   sub-landing (needs per-case payload switch + union write)."
  [f enum-env]
  (and (not (:optional? f))
       (contains? enum-env (:cxc-ty f))
       (not (get-in enum-env [(:cxc-ty f) :tagged?]))))

(defn f37-optional-value-only-enum-decode-field?
  "F37 L6 Optional-value-only-enum extension (2026-08-27) — bare-T
   value-typed field whose inner is a value-only enum. Wire format:
   UInt8 presence byte + (when present) discriminator bytes per the
   width dictated by the case count. Tagged-enum inner is a follow-on
   sub-landing."
  [f enum-env]
  (and (:optional? f)
       (contains? enum-env (:cxc-ty f))
       (not (get-in enum-env [(:cxc-ty f) :tagged?]))))

(defn f37-optional-value-only-enum-field-decode-c-src
  "F37 L6 Optional-value-only-enum extension — decode a bare-T value-
   typed field whose inner is a value-only enum. Presence byte with
   validity check; on present, discriminator read + range check + cast
   into `_Cxc_Optional_<Enum>.value`. Inlines the value-only-enum
   decode shape rather than dispatching to a synthesized enum decoder
   (F37 L6 doesn't synthesize `<Enum>_cxc_from_bytes` today — enum
   decode ships as inline field-level machinery)."
  [record-name f enum-env]
  (let [fname   (:name f)
        cxc-ty  (:cxc-ty f)
        info    (get enum-env cxc-ty)
        entries (:case-entries info)
        n       (count entries)
        width   (f37-enum-discriminator-width n)
        bind    (str "__" fname "_val")
        raw     (str "__" fname "_disc")
        invalid (decode-invalid-fn-name record-name)
        opt-ty  (optional-type-name cxc-ty)
        disc-read
        (case width
          1 (str "        uint8_t " raw " = __bytes[__off];\n"
                 "        __off += 1;")
          2 (str "        uint16_t " raw "_be;\n"
                 "        memcpy(&" raw "_be, __bytes + __off, 2);\n"
                 "        uint16_t " raw " = OSSwapBigToHostInt16(" raw "_be);\n"
                 "        __off += 2;")
          4 (str "        uint32_t " raw "_be;\n"
                 "        memcpy(&" raw "_be, __bytes + __off, 4);\n"
                 "        uint32_t " raw " = OSSwapBigToHostInt32(" raw "_be);\n"
                 "        __off += 4;"))]
    (str "    if (__len < __off + 1) return " invalid
         "((int64_t)__off, @\"expected 1 presence byte for Optional "
         "enum field " fname ", source has less\");\n"
         "    uint8_t " bind "_presence = __bytes[__off];\n"
         "    __off += 1;\n"
         "    if (" bind "_presence != 0x00 && " bind "_presence != 0x01) return "
         invalid "((int64_t)(__off - 1), @\"invalid presence byte for "
         "Optional enum field " fname "\");\n"
         "    " opt-ty " " bind " = { .present = " bind "_presence, .value = 0 };\n"
         "    if (" bind "_presence == 0x01) {\n"
         "        if (__len < __off + " width ") return " invalid
         "((int64_t)__off, @\"expected " width " bytes for Optional "
         "enum field " fname " (" cxc-ty ") payload, source has less\");\n"
         disc-read "\n"
         "        if (" raw " >= " n ") return " invalid
         "((int64_t)(__off - " width "), @\"invalid discriminator for "
         "Optional enum field " fname " (" cxc-ty ")\");\n"
         "        " bind ".value = (" cxc-ty ")" raw ";\n"
         "    }")))

(defn f37-nullable-string-decode-field?
  "F37 L6 nullable-String extension (2026-08-27) — bare `String` (not
   `String!`). Storage is `NSString *`; wire format is Optional per
   §3.10.10 — `UInt8` presence byte + (when present) `UInt32` big-
   endian length prefix + UTF-8 payload with validity check."
  [f]
  (and (= "String" (:cxc-ty f))
       (not (:nonnil? f))))

(defn f37-nullable-string-field-decode-c-src
  "F37 L6 nullable-String extension — decode a nullable `String` field.
   Presence byte + validity check; on present, UInt32 length prefix
   bounds check + read + payload bounds check + `NSString
   initWithBytes:length:encoding:` with UTF-8 validity. Binds
   `NSString * __<field>_val` directly (no `_Cxc_Optional_String`
   envelope; nil at rest represents absent, mirroring F39's String-
   excluded-from-value-typed rule)."
  [record-name f]
  (let [fname   (:name f)
        bind    (str "__" fname "_val")
        invalid (decode-invalid-fn-name record-name)
        lenv    (str "__" fname "_slen")
        raw     (str lenv "_raw")]
    (str "    if (__len < __off + 1) return " invalid
         "((int64_t)__off, @\"expected 1 presence byte for nullable "
         "String field " fname ", source has less\");\n"
         "    uint8_t " bind "_presence = __bytes[__off];\n"
         "    __off += 1;\n"
         "    if (" bind "_presence != 0x00 && " bind "_presence != 0x01) return "
         invalid "((int64_t)(__off - 1), @\"invalid presence byte for "
         "nullable String field " fname "\");\n"
         "    NSString *" bind " = nil;\n"
         "    if (" bind "_presence == 0x01) {\n"
         "        if (__len < __off + 4) return " invalid
         "((int64_t)__off, @\"expected 4 bytes for nullable String "
         "field " fname " length prefix, source has less\");\n"
         "        uint32_t " raw ";\n"
         "        memcpy(&" raw ", __bytes + __off, 4);\n"
         "        uint32_t " lenv " = OSSwapBigToHostInt32(" raw ");\n"
         "        __off += 4;\n"
         "        if (__len < __off + " lenv ") return " invalid
         "((int64_t)__off, @\"expected " lenv " bytes for nullable "
         "String field " fname ", source has less than declared length\");\n"
         "        " bind " = [[NSString alloc] "
         "initWithBytes:__bytes + __off length:" lenv " "
         "encoding:NSUTF8StringEncoding];\n"
         "        if (!" bind ") return " invalid
         "((int64_t)__off, @\"invalid UTF-8 for nullable String field "
         fname "\");\n"
         "        __off += " lenv ";\n"
         "    }")))

;; `f37-optional-tagged-enum-decode-field?` was an exact alias for
;; `f37-optional-tagged-enum-field?` at line 125 — the encode and
;; decode predicates share the same eligibility rule. Alias here so
;; the decode call sites keep their intent-naming without a second
;; identical body.
(def f37-optional-tagged-enum-decode-field? f37-optional-tagged-enum-field?)

(defn f37-optional-tagged-enum-field-decode-c-src
  "F37 L6 Optional-tagged-enum extension — decode a bare-T value-typed
   field whose inner is a tagged enum. Presence byte with validity
   check; on present, discriminator read + range check + per-case
   payload arms writing directly into `_Cxc_Optional_<Enum>.value.data.
   <case>.<pfield>` (target-prefix passed to
   `f37-tagged-enum-payload-decode-c-src`). Same eligibility rule as
   direct tagged-enum decode; nested-record / enum / Optional payload
   fields still defer."
  [record-name f enum-env]
  (let [fname   (:name f)
        cxc-ty  (:cxc-ty f)
        c-ty    (->c-safe cxc-ty)
        info    (get enum-env cxc-ty)
        entries (:case-entries info)
        n       (count entries)
        width   (f37-enum-discriminator-width n)
        bind    (str "__" fname "_val")
        raw     (str "__" fname "_disc")
        invalid (decode-invalid-fn-name record-name)
        opt-ty  (optional-type-name cxc-ty)
        disc-read
        (case width
          1 (str "        uint8_t " raw " = __bytes[__off];\n"
                 "        __off += 1;")
          2 (str "        uint16_t " raw "_be;\n"
                 "        memcpy(&" raw "_be, __bytes + __off, 2);\n"
                 "        uint16_t " raw " = OSSwapBigToHostInt16(" raw "_be);\n"
                 "        __off += 2;")
          4 (str "        uint32_t " raw "_be;\n"
                 "        memcpy(&" raw "_be, __bytes + __off, 4);\n"
                 "        uint32_t " raw " = OSSwapBigToHostInt32(" raw "_be);\n"
                 "        __off += 4;"))
        inner-bind (str bind ".value")
        arms
        (for [ce entries]
          (let [case-nm (:name ce)
                fields  (:payload ce)
                blocks  (if (seq fields)
                          (str/join "\n"
                                    (map #(f37-tagged-enum-payload-decode-c-src
                                           record-name fname case-nm % inner-bind)
                                         fields))
                          "")]
            (str "        case " c-ty "_Tag_" case-nm ": {\n"
                 (when (seq blocks) (str blocks "\n"))
                 "            break;\n"
                 "        }")))]
    (str "    if (__len < __off + 1) return " invalid
         "((int64_t)__off, @\"expected 1 presence byte for Optional "
         "tagged-enum field " fname ", source has less\");\n"
         "    uint8_t " bind "_presence = __bytes[__off];\n"
         "    __off += 1;\n"
         "    if (" bind "_presence != 0x00 && " bind "_presence != 0x01) return "
         invalid "((int64_t)(__off - 1), @\"invalid presence byte for "
         "Optional tagged-enum field " fname "\");\n"
         "    " opt-ty " " bind " = { .present = " bind "_presence, .value = {0} };\n"
         "    if (" bind "_presence == 0x01) {\n"
         "        if (__len < __off + " width ") return " invalid
         "((int64_t)__off, @\"expected " width " bytes for Optional "
         "tagged-enum field " fname " (" cxc-ty ") payload, source has less\");\n"
         disc-read "\n"
         "        if (" raw " >= " n ") return " invalid
         "((int64_t)(__off - " width "), @\"invalid discriminator for "
         "Optional tagged-enum field " fname " (" cxc-ty ")\");\n"
         "        " inner-bind ".tag = (" c-ty "_Tag)" raw ";\n"
         "        switch (" raw ") {\n"
         (str/join "\n" arms) "\n"
         "        }\n"
         "    }")))

(defn f37-value-only-enum-field-decode-c-src
  "F37 L6 value-only enum extension — decode an enum field where the
   enum has no payload cases. Discriminator width per §3.10.10
   (`f37-enum-discriminator-width`). Reads big-endian at width > 1;
   range-checks against case count (invalid discriminator → decode
   failure). Assigns to the enum's C tag type via cast."
  [record-name f enum-env]
  (let [fname   (:name f)
        cxc-ty  (:cxc-ty f)
        c-ty    (->c-safe cxc-ty)
        info    (get enum-env cxc-ty)
        entries (:case-entries info)
        n       (count entries)
        width   (f37-enum-discriminator-width n)
        bind    (str "__" fname "_val")
        raw     (str "__" fname "_raw")
        invalid (decode-invalid-fn-name record-name)
        read-src
        (case width
          1 (str "    uint8_t " raw " = __bytes[__off];\n"
                 "    __off += 1;")
          2 (str "    uint16_t " raw "_be;\n"
                 "    memcpy(&" raw "_be, __bytes + __off, 2);\n"
                 "    uint16_t " raw " = OSSwapBigToHostInt16(" raw "_be);\n"
                 "    __off += 2;")
          4 (str "    uint32_t " raw "_be;\n"
                 "    memcpy(&" raw "_be, __bytes + __off, 4);\n"
                 "    uint32_t " raw " = OSSwapBigToHostInt32(" raw "_be);\n"
                 "    __off += 4;"))]
    (str "    if (__len < __off + " width ") return " invalid
         "((int64_t)__off, @\"expected " width " bytes for enum field "
         fname " (" cxc-ty "), source has less\");\n"
         read-src "\n"
         "    if (" raw " >= " n ") return " invalid
         "((int64_t)(__off - " width "), @\"invalid discriminator for enum field "
         fname " (" cxc-ty ")\");\n"
         "    " c-ty " " bind " = (" c-ty ")" raw ";")))

(defn f37-optional-primitive-field-decode-c-src
  "F37 L6 Optional-primitive extension — decode a bare-T value-typed
   field (Optional-boxed, primitive inner) per §3.10.10 Optional row:
   UInt8 presence byte (0/1 only) + T bytes when present. Absent (0)
   leaves `.value` bit-zero; present (1) decodes T using the primitive
   helper pattern. Binds `_Cxc_Optional_<T> __<field>_val` for the
   designated-initializer constructor at the tail."
  [record-name f]
  (let [fname   (:name f)
        cxc-ty  (:cxc-ty f)
        width   (get f37-primitive-byte-widths cxc-ty)
        bind    (str "__" fname "_val")
        opt-ty  (optional-type-name cxc-ty)
        invalid (decode-invalid-fn-name record-name)
        inner-bind (str "__" fname "_inner")
        inner-read
        (case cxc-ty
          "Bool"
          (str "        uint8_t " inner-bind "_raw = __bytes[__off];\n"
               "        if (" inner-bind "_raw != 0x00 && " inner-bind "_raw != 0x01) return "
               invalid "((int64_t)__off, @\"invalid Bool byte in Optional field "
               fname "\");\n"
               "        bool " inner-bind " = (" inner-bind "_raw == 0x01);\n"
               "        " bind ".value = " inner-bind ";\n"
               "        __off += 1;")

          ("UInt8" "Int8")
          (str "        uint8_t " inner-bind " = __bytes[__off];\n"
               "        " bind ".value = " inner-bind ";\n"
               "        __off += 1;")

          ("UInt16" "Int16")
          (str "        uint16_t " inner-bind "_raw;\n"
               "        memcpy(&" inner-bind "_raw, __bytes + __off, 2);\n"
               "        " (if (= cxc-ty "Int16") "int16_t" "uint16_t") " " inner-bind
               " = " (if (= cxc-ty "Int16") "(int16_t)" "")
               "OSSwapBigToHostInt16(" inner-bind "_raw);\n"
               "        " bind ".value = " inner-bind ";\n"
               "        __off += 2;")

          ("UInt32" "Int32")
          (str "        uint32_t " inner-bind "_raw;\n"
               "        memcpy(&" inner-bind "_raw, __bytes + __off, 4);\n"
               "        " (if (= cxc-ty "Int32") "int32_t" "uint32_t") " " inner-bind
               " = " (if (= cxc-ty "Int32") "(int32_t)" "")
               "OSSwapBigToHostInt32(" inner-bind "_raw);\n"
               "        " bind ".value = " inner-bind ";\n"
               "        __off += 4;")

          ("UInt64" "Int64" "Int")
          (str "        uint64_t " inner-bind "_raw;\n"
               "        memcpy(&" inner-bind "_raw, __bytes + __off, 8);\n"
               "        int64_t " inner-bind
               " = (int64_t)OSSwapBigToHostInt64(" inner-bind "_raw);\n"
               "        " bind ".value = " inner-bind ";\n"
               "        __off += 8;")

          "Float"
          (str "        uint32_t " inner-bind "_raw;\n"
               "        memcpy(&" inner-bind "_raw, __bytes + __off, 4);\n"
               "        " inner-bind "_raw = OSSwapBigToHostInt32(" inner-bind "_raw);\n"
               "        float " inner-bind ";\n"
               "        memcpy(&" inner-bind ", &" inner-bind "_raw, 4);\n"
               "        " bind ".value = " inner-bind ";\n"
               "        __off += 4;")

          "Double"
          (str "        uint64_t " inner-bind "_raw;\n"
               "        memcpy(&" inner-bind "_raw, __bytes + __off, 8);\n"
               "        " inner-bind "_raw = OSSwapBigToHostInt64(" inner-bind "_raw);\n"
               "        double " inner-bind ";\n"
               "        memcpy(&" inner-bind ", &" inner-bind "_raw, 8);\n"
               "        " bind ".value = " inner-bind ";\n"
               "        __off += 8;"))]
    (str "    if (__len < __off + 1) return " invalid
         "((int64_t)__off, @\"expected 1 presence byte for Optional field "
         fname ", source has less\");\n"
         "    uint8_t " bind "_presence = __bytes[__off];\n"
         "    __off += 1;\n"
         "    if (" bind "_presence != 0x00 && " bind "_presence != 0x01) return "
         invalid "((int64_t)(__off - 1), @\"invalid presence byte for Optional field "
         fname "\");\n"
         "    " opt-ty " " bind " = { .present = " bind "_presence, .value = 0 };\n"
         "    if (" bind "_presence == 0x01) {\n"
         "        if (__len < __off + " width ") return " invalid
         "((int64_t)__off, @\"expected " width " bytes for Optional field "
         fname " payload, source has less\");\n"
         inner-read "\n"
         "    }")))

(defn f37-field-decode-c-src
  "Dispatch per-field-kind at F37 L6 decode synthesis. Primitive → the
   primitive helper; `String!` → the String helper; Optional-primitive →
   the Optional helper; nested-record → the nested-record helper;
   value-only enum → the enum helper. Precondition:
   `f37-decode-eligible-field?` returned true. New field kinds land as
   follow-on sub-landings with their own helper."
  [record-name f record-env enum-env]
  (cond
    (f37-primitive-byte-field? f)          (f37-primitive-field-decode-c-src record-name f)
    (f37-string-byte-field? f)             (f37-string-field-decode-c-src record-name f)
    (f37-optional-primitive-field? f)
    (f37-optional-primitive-field-decode-c-src record-name f)
    (f37-nested-record-decode-field? f record-env)
    (f37-nested-record-field-decode-c-src record-name f record-env)
    (f37-optional-nested-record-decode-field? f record-env)
    (f37-optional-nested-record-field-decode-c-src record-name f record-env)
    (f37-value-only-enum-decode-field? f enum-env)
    (f37-value-only-enum-field-decode-c-src record-name f enum-env)
    (f37-tagged-enum-decode-field? f enum-env)
    (f37-tagged-enum-field-decode-c-src record-name f enum-env)
    (f37-optional-value-only-enum-decode-field? f enum-env)
    (f37-optional-value-only-enum-field-decode-c-src record-name f enum-env)
    (f37-optional-tagged-enum-decode-field? f enum-env)
    (f37-optional-tagged-enum-field-decode-c-src record-name f enum-env)
    (f37-nullable-string-decode-field? f)
    (f37-nullable-string-field-decode-c-src record-name f)))

(defn synthesize-record-from-bytes-fn
  "F37 L6 — emit `<Record>_cxc_from_bytes(NSMutableData *src,
   int64_t start_off, int64_t *consumed_out) ->
   _Cxc_DecodeOutcome_<Record>`. Per-field decode blocks in declaration
   order, each with a bounds-check that returns
   `_cxc_decode_invalid_<Record>(offset, reason)` on short input. Success
   path assembles a designated-initializer constructor, writes
   `*consumed_out = __off - start_off` (guarded), and returns
   `_cxc_decode_converted_<Record>(...)`. Precondition:
   `record-eligible-for-from-bytes-synthesis?` returned true.

   ABI updated 2026-08-27 for variable-length nested-record support:
   the outer decoder passes its own `__off` as `start_off` so inner
   can read into `src` at the correct position without slicing, and
   reads back the inner's `consumed_out` to advance its own `__off`.
   Callers of the whole-buffer form (`.from(source:)` intercept) pass
   `(src, 0, NULL)` — identical behavior to the pre-ABI-change form.
   Invalid-return paths don't touch `consumed_out` (caller doesn't
   advance on invalid).

   `record-env` and `enum-env` are threaded through for the nested-
   record arm (allows variable-length transitively-decode-eligible
   inner) and the value-only enum arm (needs case count for
   discriminator width + range check)."
  [record-name info record-env enum-env]
  (let [fields   (:fields info)
        outcome  (decode-outcome-struct-name record-name)
        head     (str "    NSUInteger __off = (NSUInteger)start_off;\n"
                      "    const uint8_t *__bytes = (const uint8_t *)[src bytes];\n"
                      "    NSUInteger __len = [src length];")
        blocks   (map #(f37-field-decode-c-src record-name % record-env enum-env) fields)
        ctor-args (str/join ", "
                            (map (fn [f]
                                   (str "." (:name f) " = __" (:name f) "_val"))
                                 fields))
        tail      (str "    if (consumed_out) *consumed_out = (int64_t)"
                       "(__off - (NSUInteger)start_off);\n"
                       "    return " (decode-converted-fn-name record-name)
                       "((" (->c-safe record-name) "){ " ctor-args " });")
        body-src  (str/join "\n\n" (concat [head] blocks [tail]))]
    {:node        :c-function
     :name        (record-synth-from-bytes-name record-name)
     :return-type outcome
     :params      [{:name "src" :c-type "NSMutableData *"}
                   {:name "start_off" :c-type "int64_t"}
                   {:name "consumed_out" :c-type "int64_t *"}]
     :body        [{:node :raw-c :text body-src}]}))

(defn synthesized-record-functions
  "Emit the synthesized equals + hash function pair for every record,
   plus F37 Landings 2 + 3 + 4 canonical-bytes synthesis when the
   record's fields are all bytes-encodable (primitive / String! /
   nested-record) AND the user hasn't declared their own
   `.as(self) -> Bytes` (§3.3.2 rule 6 override). Forward decls at
   emit time make nested-record dispatch order irrelevant.

   F37 L5 folds enum canonical-bytes into the same pipeline — enums
   whose payload cases are all bytes-encodable synth
   `<Enum>_cxc_as_bytes`."
  [ctx]
  (let [record-env (:record-env ctx)
        enum-env   (:enum-env ctx)
        record-fns
        (vec (for [[rec-name info] record-env
                   f-fn            [synthesize-record-equals-fn
                                    synthesize-record-hash-fn
                                    (fn [rn ri _]
                                      (when (record-eligible-for-as-bytes-synthesis?
                                             ri record-env enum-env)
                                        (synthesize-record-as-bytes-fn
                                         rn ri record-env enum-env)))]
                   :let            [emitted (f-fn rec-name info ctx)]
                   :when           emitted]
               emitted))
        ;; F37 L6 MVP — for every primitive-field-eligible record, emit
        ;; the two factory helpers + the from-bytes decoder. Ordered
        ;; alongside `_cxc_as_bytes` so a `.as().from()` round-trip finds
        ;; both symbols at emit time. String / nested / Optional / enum
        ;; support ships in follow-on sub-landings; those records skip
        ;; synthesis here and the `.from()` intercept fail-nyi's with a
        ;; targeted diagnostic pointing at the missing extension.
        decode-fns
        (vec (mapcat (fn [[rec-name info]]
                       (when (record-eligible-for-from-bytes-synthesis?
                              info record-env enum-env)
                         (concat (synthesize-decode-factory-fns rec-name)
                                 [(synthesize-record-from-bytes-fn
                                   rec-name info record-env enum-env)])))
                     record-env))
        enum-fns
        (vec (for [[en-name info] enum-env
                   :when          (enum-eligible-for-as-bytes-synthesis? info record-env)]
               (synthesize-enum-as-bytes-fn en-name info record-env)))
        ;; 2026-10-02 — every enum gets `<Enum>_cxc_to_string` so the
        ;; `.to_string()` method intercept has a target. Unconditional:
        ;; the body only reads the discriminator, no payload-encoding
        ;; eligibility to check.
        enum-to-string-fns
        (vec (for [[en-name info] enum-env]
               (synthesize-enum-to-string-fn en-name info)))]
    (into (into (into record-fns decode-fns) enum-fns) enum-to-string-fns)))
