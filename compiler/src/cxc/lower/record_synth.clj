(ns cxc.lower.record-synth
  "Structural equals + hash synthesis for records.

   Emits one `<Record>_cxc_equals(a, b) -> bool` and one
   `<Record>_cxc_hash(a) -> long` per record. Field-level
   dispatch handles primitives (`==`), NSString
   (`-isEqualToString:`), nested records (recurse into their
   own synthesized fns), tagged enums (memcmp/byte-hash over
   the C99 designated-init'd struct), atoms (pointer identity),
   and F39 Optional-boxed slots (presence + inner rule).

   Called from `synthesized-record-functions` in
   cxc.lower.f37-bytes (the emit-side orchestrator that folds
   equals/hash together with F37 canonical-bytes synth)."
  (:require [cxc.env :as env]
            [cxc.mangle :refer [->c-safe]]
            [cxc.type-kw :refer [primitive-c-type]]
            [clojure.string :as str]))

(defn record-synth-equals-name [record-name]
  (str (->c-safe record-name) "_cxc_equals"))

(defn record-synth-hash-name [record-name]
  (str (->c-safe record-name) "_cxc_hash"))

(defn primitive-eq-op?
  "True when the field's cxc type name is a primitive whose C
   representation supports raw `==` (all numerics, char, bool, and
   VALUE-ONLY enums-as-int). Tagged enums are structs — comparing
   them needs `memcmp` or per-case dispatch; those hit distinct arms
   in `field-equals-c-src` / `field-hash-c-src`."
  [cxc-ty ctx]
  (or (contains? primitive-c-type cxc-ty)
      (and (contains? (:enum-env ctx) cxc-ty)
           (not (:tagged? (env/env-lookup ctx :enum-env cxc-ty))))))

(defn tagged-enum-type? [cxc-ty ctx]
  (boolean (:tagged? (env/env-lookup ctx :enum-env cxc-ty))))

(defn field-equals-c-src
  "Emit a C expression comparing field `f` between two record variables
   `a` and `b`. Dispatched on field type: primitives + enums use `==`;
   NSString uses `-isEqualToString:`; nested records recurse into their
   synthesized equals; atoms and NSObject containers use pointer / NSObject
   equality.

   F39 L5b-ii-c (Sub-N) — when the field's storage is Optional-boxed,
   compare presence + value: two `nil` are equal; `nil` vs `present`
   are not; both `present` compare `.value`s with the underlying
   equality rule. Dead until Sub-P flips `:optional?`."
  [f a-name b-name ctx]
  (let [fname   (:name f)
        cxc-ty  (:cxc-ty f)
        a-fld   (str a-name "." fname)
        b-fld   (str b-name "." fname)]
    (cond
      (:optional? f)
      (let [a-val (str a-fld ".value")
            b-val (str b-fld ".value")
            a-pres (str a-fld ".present")
            b-pres (str b-fld ".present")
            inner-eq (cond
                       (= "String" cxc-ty)
                       (str "((" a-val " == " b-val ") || "
                            "[" a-val " isEqualToString:" b-val "])")
                       (contains? (:record-env ctx) cxc-ty)
                       (str (record-synth-equals-name cxc-ty)
                            "(" a-val ", " b-val ")")
                       (tagged-enum-type? cxc-ty ctx)
                       ;; F37 L6 Optional-tagged-enum extension (2026-08-27) —
                       ;; the memcmp/designated-init contract that
                       ;; makes tagged-enum equals deterministic
                       ;; extends into the Optional's inner storage.
                       (str "(memcmp(&" a-val ", &" b-val
                            ", sizeof(" cxc-ty ")) == 0)")
                       :else
                       (str "(" a-val " == " b-val ")"))]
        (str "((" a-pres " == " b-pres ") && (!" a-pres " || " inner-eq "))"))

      (= "String" cxc-ty)
      (str "((" a-fld " == " b-fld ") || "
           "[" a-fld " isEqualToString:" b-fld "])")

      (contains? (:record-env ctx) cxc-ty)
      (str (record-synth-equals-name cxc-ty)
           "(" a-fld ", " b-fld ")")

      (tagged-enum-type? cxc-ty ctx)
      ;; F16 tagged enums are structs `{tag; union{...}}`; C99
      ;; designated-init ctors zero-init unspecified bytes so memcmp
      ;; on the whole struct is deterministic. Bytes-based equality
      ;; matches structural equality when the same ctor path produced
      ;; each value — which is the only way to construct these.
      (str "(memcmp(&" a-fld ", &" b-fld ", sizeof(" cxc-ty ")) == 0)")

      (contains? (:atom-env ctx) cxc-ty)
      (str "(" a-fld " == " b-fld ")")

      (primitive-eq-op? cxc-ty ctx)
      (str "(" a-fld " == " b-fld ")")

      :else
      (str "[" a-fld " isEqual:" b-fld "]"))))

(defn field-hash-c-src
  "Emit a C expression producing a `long` hash contribution for one
   field. Combined with 31-multiply accumulator.

   F39 L5b-ii-c (Sub-N) — Optional field hashes to 0 when nil, else
   the underlying value's hash. Dead until Sub-P."
  [f var-name ctx]
  (let [fname   (:name f)
        cxc-ty  (:cxc-ty f)
        fld     (str var-name "." fname)]
    (cond
      (:optional? f)
      (let [val (str fld ".value")
            inner (cond
                    (= "String" cxc-ty)  (str "(long)[" val " hash]")
                    (contains? (:record-env ctx) cxc-ty)
                    (str (record-synth-hash-name cxc-ty) "(" val ")")
                    (tagged-enum-type? cxc-ty ctx)
                    ;; F37 L6 Optional-tagged-enum extension (2026-08-27) —
                    ;; mirror the direct tagged-enum arm's byte-
                    ;; accumulated hash over the inner struct storage
                    ;; so equal Optional<TaggedEnum> values hash equal.
                    (str "({ long __h = 0; const unsigned char *__b = "
                         "(const unsigned char *)&" val "; "
                         "for (size_t __i = 0; __i < sizeof(" cxc-ty
                         "); __i++) __h = __h * 31 + __b[__i]; __h; })")
                    :else                 (str "(long)" val))]
        (str "(" fld ".present ? " inner " : 0)"))

      (= "String" cxc-ty)
      (str "(long)[" fld " hash]")

      (contains? (:record-env ctx) cxc-ty)
      (str (record-synth-hash-name cxc-ty) "(" fld ")")

      (tagged-enum-type? cxc-ty ctx)
      ;; F16 tagged-enum hash: FNV-1a-ish accumulation over the raw
      ;; struct bytes. Determinism relies on the same C99
      ;; designated-init contract that lets `memcmp` in
      ;; `field-equals-c-src` compare struct-equal values correctly.
      (str "({ long __h = 0; const unsigned char *__b = "
           "(const unsigned char *)&" fld "; "
           "for (size_t __i = 0; __i < sizeof(" cxc-ty "); __i++) "
           "__h = __h * 31 + __b[__i]; __h; })")

      (contains? (:atom-env ctx) cxc-ty)
      (str "(long)(intptr_t)" fld)

      (primitive-eq-op? cxc-ty ctx)
      (str "(long)" fld)

      :else
      (str "(long)[" fld " hash]"))))

(defn synthesize-record-equals-fn
  "Produce a `:c-function` node implementing structural equality for
   `record-name`. Empty records return `true` (all zero fields agree)."
  [record-name info ctx]
  (let [fields   (:fields info)
        body-src (if (empty? fields)
                   "true"
                   (str/join "\n           && "
                             (map #(field-equals-c-src % "a" "b" ctx) fields)))]
    {:node        :c-function
     :name        (record-synth-equals-name record-name)
     :return-type "bool"
     :params      [{:name "a" :c-type (->c-safe record-name)}
                   {:name "b" :c-type (->c-safe record-name)}]
     :body        [{:node :raw-c :text (str "return " body-src ";")}]}))

(defn synthesize-record-hash-fn
  "Produce a `:c-function` node implementing structural hash for
   `record-name`. Uses 31-multiply accumulator over per-field hashes;
   empty records hash to 0."
  [record-name info ctx]
  (let [fields   (:fields info)
        lines    (map (fn [f]
                        (str "  h = h * 31 + " (field-hash-c-src f "a" ctx) ";"))
                      fields)
        body-src (str "long h = 0;\n" (str/join "\n" lines) "\n  return h;")]
    {:node        :c-function
     :name        (record-synth-hash-name record-name)
     :return-type "long"
     :params      [{:name "a" :c-type (->c-safe record-name)}]
     :body        [{:node :raw-c :text body-src}]}))
