(ns cxc.lower.protocol-shapes
  "F15 L4 — compiler-known parametric protocol shapes registry +
   structural conformance check.

   v1.0 has no user-declared parametric protocols per
   [[corvid-no-user-shape-variables]]; `As<T>` and `From<T>` are
   compiler-baked shapes described by `compiler-known-protocol-
   shapes`. Placeholders `:T` (the type parameter) and `:Self` (the
   type being checked) are resolved before matching against a
   candidate type's method-index / class-fn-index.

   `structurally-satisfies-protocol?` returns TRUE when the record
   described by `type-info` structurally satisfies a compiler-known
   protocol at some `t-tk`. Sub-2 (`seed-class-fn-index-from-f37-
   decode`) seeds each decode-eligible record's `:class-fn-index`
   with F37 L6's synthesized `from` shape so `From<Bytes>` conformance
   fires without user-declared class functions."
  (:require [cxc.lower.conformance :refer [non-self-params]]
            [cxc.lower.f37-bytes :refer [record-decode-eligible?
                                         decode-outcome-struct-name]]
            [cxc.type-kw :refer [type-kw->cxc-name-str]]))

(def ^:private compiler-known-protocol-shapes
  "Fixed registry of compiler-known parametric protocol shapes. Keys are
   outer protocol names (as users would write in an explicit conforms
   clause once F9 lands; today they only appear implicitly via `.as()` /
   `.from(source:)` dispatch). Values map to `:instance-methods` (self-
   receiver methods keyed by name) and `:class-methods` (class-fn
   requirements keyed by name; class-fn env isn't wired at v1.0, so a
   `From<T>` conformer needs Sub-2's env addition to actually resolve
   here). Method entries carry `:params` (vec of `{:name :type}`) and
   `:returns` (type-kw or placeholder `:T`/`:Self`). See spec §3.4 +
   §3.4.6 + §3.10.10."
  {"As"   {:instance-methods {"as"   {:params  []
                                      :returns :T}}
           :class-methods    {}}
   ;; F15 L4 Sub-2 (2026-08-27) — `From<T>` follows the F37 L6 canonical
   ;; bytes contract: the class function `from(source: T)` returns
   ;; `DecodeOutcome<Self>` (fallible), not bare `Self`. The
   ;; `{:apply "DecodeOutcome" :inner :Self}` marker on `:returns` is
   ;; what `protocol-req-matches-method?` resolves to the record's
   ;; `_Cxc_DecodeOutcome_<Rec>` struct name when checking conformance.
   "From" {:instance-methods {}
           :class-methods    {"from" {:params  [{:name "source" :type :T}]
                                      :returns {:apply "DecodeOutcome"
                                                :inner :Self}}}}})

(defn- protocol-req-matches-method?
  "F15 L4 Sub-1 — does `method-entry` (a `:method-index` value from a
   record env slot) match protocol-required signature `req` at
   instantiation T=`t-tk`, Self=`self-cxc-name`? Placeholders in `req`
   are resolved to concrete cxc-name-str form before string-comparing
   against the method entry's own `:cxc-ty` / `:cxc-return-ty`
   (symmetric with `signature-mismatch-detail`, which also compares
   cxc-name-strings). Signature identity per trait-qualified dispatch
   is (param names, param types, return type) — an empty required-
   params list rejects methods that carry extra non-self params."
  [req method-entry t-tk self-cxc-name]
  (let [resolve-str    (fn resolve-str [ph]
                         (cond
                           (= :T    ph)    (type-kw->cxc-name-str t-tk)
                           (= :Self ph)    self-cxc-name
                           (and (map? ph) (= "DecodeOutcome" (:apply ph)))
                           (str "_Cxc_DecodeOutcome_" (resolve-str (:inner ph)))

                           :else ph))
        req-params     (:params req)
        method-params  (non-self-params (:params method-entry))
        expected-ret   (resolve-str (:returns req))
        actual-ret     (:cxc-return-ty method-entry)]
    (and (= (count req-params) (count method-params))
         (= expected-ret actual-ret)
         (every? (fn [[rp mp]]
                   (and (= (:name rp) (:name mp))
                        (= (resolve-str (:type rp)) (:cxc-ty mp))))
                 (map vector req-params method-params)))))

(defn seed-class-fn-index-from-f37-decode
  "F15 L4 Sub-2 — seed `:class-fn-index` on every decode-eligible
   record in `record-env` with the synthetic entry describing F37
   L6's `<Rec>_cxc_from_bytes(NSMutableData *)` shape. Presence of
   this entry lets `structurally-satisfies-protocol?` return TRUE
   for `From<Bytes>` conformance on eligible records — the F37
   synthesis is now discoverable via the same protocol-shape lookup
   Sub-1's registry describes.

   Method-entry shape mirrors `observable->method-entry` / extension
   composition (see f15-l4-record-info helper in lower_test.clj):

     {:name          \"from\"
      :params        [{:name \"source\" :cxc-ty \"Bytes\"
                       :is-self? false :default nil}]
      :cxc-return-ty \"_Cxc_DecodeOutcome_<Rec>\"}

   `:cxc-return-ty` matches what `protocol-req-matches-method?`
   compares against after resolving the `From<T>` shape's
   `{:apply \"DecodeOutcome\" :inner :Self}` marker at `T=:bytes`.

   Scaffolding only at this landing — no consumer of the seeded
   index has been wired yet. Sub-3 unifies F37's ad-hoc `.from()`
   intercept with the structural check that consults this seed."
  [record-env enum-env]
  (reduce-kv
    (fn [env rn _info]
      (if (record-decode-eligible? rn env enum-env)
        (assoc-in env [rn :class-fn-index "from"]
                  {:name          "from"
                   :params        [{:name "source" :cxc-ty "Bytes"
                                    :is-self? false :default nil}]
                   :cxc-return-ty (decode-outcome-struct-name rn)})
        env))
    record-env
    record-env))

(defn structurally-satisfies-protocol?
  "F15 L4 Sub-1 — does the record described by `type-info` (a record-env
   slot: `{:method-index ... :class-fn-index? ...}`) structurally satisfy
   compiler-known protocol `shape-name` when instantiated at T=`t-tk`?
   `self-cxc-name` names the type being checked (used to resolve `:Self`
   placeholders). Returns bool.

   Instance-method requirements consult `:method-index` — the same slot
   direct method dispatch consults, so structural conformance is
   symmetric with direct lookup on the eligible surface.

   Class-method requirements consult `:class-fn-index`, which isn't
   populated at v1.0 — a `From<T>` structural check returns false today.
   The env slot goes live when Sub-2 seeds it from F37 L6 synthesis (or
   from user-declared class functions once F37 L6b picks up class-fn
   consumption). Predicate shape is stable across that landing.

   Returns nil for a shape name not in `compiler-known-protocol-shapes`.
   Empty required-methods on both sides is vacuously true — no such
   shape exists in the registry today, but Constructible / other v1.1+
   shapes may fall into this case."
  [type-info self-cxc-name shape-name t-tk]
  (when-let [shape (get compiler-known-protocol-shapes shape-name)]
    (and (every? (fn [[mn req]]
                   (when-let [m (get-in type-info [:method-index mn])]
                     (protocol-req-matches-method? req m t-tk self-cxc-name)))
                 (:instance-methods shape))
         (every? (fn [[mn req]]
                   (when-let [m (get-in type-info [:class-fn-index mn])]
                     (protocol-req-matches-method? req m t-tk self-cxc-name)))
                 (:class-methods shape)))))
