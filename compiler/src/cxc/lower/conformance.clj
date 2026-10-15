(ns cxc.lower.conformance
  "F9 L5 + L6 — explicit conformance validation and default-method
   inheritance.

   L5 (validate-explicit-conformances!) walks record-env + atom-env
   post-L4-composition and checks every `:conforms` claim against
   protocol-env: unknown protocol names hard-error, missing method
   surface hard-errors, signature mismatches hard-error with a
   pointer to the specific parameter/return that diverged.

   L6 (inherit-defaults-into-env) folds protocol defaults into each
   class's `:methods` list, re-anchoring `self` to the concrete class.
   L6b widens inheritance to structurally-conforming classes (satisfy
   all required signatures) that carry inheritable defaults. Diamond
   ambiguity — two or more conforming protocols supplying a default
   for the same method the class doesn't override — hard-errors with
   a §3.4.4 diagnostic.

   Signature matching (name, param names + types, return type) is
   shared between L5 and L6 via `signature-mismatch-detail`; the
   observable-projection view lets observables count as conformance-
   eligible methods (F67 L4)."
  (:require [clojure.string :as str]
            [cxc.diagnostics :refer [fail-compile!]]
            [cxc.lower.method-entry :refer [observable->method-entry]]
            [cxc.type-kw :refer [base-type-name type-kw->cxc-name-str]]))

(defn type-node-name
  "Extract the outer type name from an AST type-node. Nil-safe."
  [ty]
  (when ty
    (case (:node ty)
      :type-ref      (:name ty)
      :type-of       (:outer ty)
      :type-nullable (base-type-name ty)
      :type-nonnil   (base-type-name ty)
      nil)))

(defn non-self-params
  "Filter out `self` — it's implicit in both class methods (marked
   `:is-self? true` by normalize-method-param) and protocol signature
   entries (users may write `foo(self, x: Int)` or the shorter
   `foo(x: Int)` since self is implied)."
  [params]
  (remove #(or (:is-self? %) (= "self" (:name %))) params))

(defn- signature-mismatch-detail
  "Return a human-readable string describing the first signature
   mismatch between a composed-table method and a protocol signature
   entry, or nil if they match.

   F67 L4b — observable-derived entries carry `:cxc-return-ty`
   populated from the inferred body type (see `observable-methods-view`);
   return-type mismatch now fires uniformly whether the entry came from
   a legacy `:method` or a post-collapse observable. Method-typed
   entries with truly-nil `:cxc-return-ty` (rare — pre-inference legacy
   paths) still short-circuit as inferred-and-matches."
  [method accepts-entry]
  (let [method-params  (non-self-params (:params method))
        accepts-params (non-self-params (:params accepts-entry))
        expected-ret   (type-node-name (:return-type accepts-entry))
        actual-ret     (:cxc-return-ty method)]
    (cond
      (not= (count method-params) (count accepts-params))
      (str "expected " (count accepts-params) " param(s), got "
           (count method-params))

      (and (some? actual-ret)
           (not= expected-ret actual-ret))
      (str "return type mismatch — expected `" expected-ret
           "`, got `" actual-ret "`")

      :else
      (some (fn [[mp ap]]
              (let [expected-ty (type-node-name (:type ap))]
                (cond
                  (not= (:name mp) (:name ap))
                  (str "param name mismatch — expected `" (:name ap)
                       "`, got `" (:name mp) "`")
                  (not= (:cxc-ty mp) expected-ty)
                  (str "param `" (:name ap) "` type mismatch — expected `"
                       expected-ty "`, got `" (:cxc-ty mp) "`"))))
            (map vector method-params accepts-params)))))

(defn- validate-one-conformance!
  "Validate one class-conforms-to-protocol claim. Throws on failure
   with a targeted §3.4.5 diagnostic. Composed-methods is the
   post-L4 method vector for the class."
  [class-name protocol-name composed-methods protocol-env]
  (let [proto (get protocol-env protocol-name)]
    (when-not proto
      (fail-compile! :cxc-f9-unknown-protocol-conformance
                     (str "class `" class-name "` declares conformance to "
                          "unknown protocol `" protocol-name "` — check "
                          "imports and typo. See spec §3.4.5.")
                     {:class class-name :protocol protocol-name}))
    (let [method-by-name (into {} (map (juxt :name identity) composed-methods))
          defaults-by-name (into {} (map (juxt :name identity) (:defaults proto)))]
      (doseq [ae (:accepts proto)]
        (let [ae-name (:name ae)
              m       (get method-by-name ae-name)]
          (cond
            m
            (when-let [detail (signature-mismatch-detail m ae)]
              (fail-compile! :cxc-f9-signature-mismatch
                             (str "class `" class-name "." ae-name
                                  "` does not match protocol `" protocol-name
                                  "`'s signature declaration — " detail
                                  ". See spec §3.4.5.")
                             {:class class-name :protocol protocol-name
                              :method ae-name}))

            (contains? defaults-by-name ae-name)
            :ok

            :else
            (fail-compile! :cxc-f9-missing-conformance-method
                           (str "class `" class-name "` claims conformance "
                                "to `" protocol-name "` but neither "
                                "implements nor inherits a default for `"
                                ae-name "`. Either add `def " ae-name
                                "(...)` on `" class-name "` (or an "
                                "extension), or provide a default in `"
                                protocol-name "`. See spec §3.4.5.")
                           {:class class-name :protocol protocol-name
                            :missing ae-name})))))))

(defn- observable-methods-view
  "F67 L4 — for conformance checks, project a class's observables into
   method-shape entries so `validate-one-conformance!` can match them
   against protocol signatures uniformly with own + extension
   methods.

   F67 L4b — sources `:cxc-return-ty` from the post-inference
   `:observable-index` entry's `:type-kw` (populated by
   `infer-observables-in-{record,atom}-env`) so return-type mismatch
   checks fire against the class's declared observable body — no
   observable-header return-type annotation required."
  [class-name entry]
  (into (vec (:methods entry))
        (mapv (fn [obs]
                (let [me         (observable->method-entry class-name obs)
                      inferred   (get-in entry [:observable-index (:name obs)])
                      inferred-tk (:type-kw inferred)
                      name-str   (type-kw->cxc-name-str inferred-tk)]
                  (assoc me :cxc-return-ty name-str)))
              (:observables entry))))

(defn validate-explicit-conformances!
  "Walk record-env + atom-env post-L4-composition and validate every
   `:conforms` entry against protocol-env. Throws on the first
   failure with a targeted §3.4.5 diagnostic. F67 L4: observables
   count as conformance-eligible methods."
  [record-env atom-env protocol-env]
  (doseq [[class-name entry] record-env
          proto-ty (:conforms entry)]
    (validate-one-conformance! class-name (type-node-name proto-ty)
                               (observable-methods-view class-name entry)
                               protocol-env))
  (doseq [[class-name entry] atom-env
          proto-ty (:conforms entry)]
    (validate-one-conformance! class-name (type-node-name proto-ty)
                               (observable-methods-view class-name entry)
                               protocol-env)))

(defn- extract-default-as-method
  "Given a default method-shape entry (produced by observable->method-entry
   at build-protocol-env time) and the concrete class name, re-anchor
   `self`'s :cxc-ty to `class-name` so downstream lowering treats it
   as a normal class method. F67 L4: raw `:function` default nodes are
   retired with the `methods:` section; defaults arrive here already
   in method-shape via the observable-defaults path."
  [class-name default-entry]
  (update default-entry :params
          (fn [ps]
            (mapv (fn [p]
                    (if (:is-self? p)
                      (assoc p :cxc-ty class-name)
                      p))
                  ps))))

(defn- class-satisfies-protocol-structurally?
  "L6b — true if every required signature in `proto` has a matching method
   (name + signature) in `class-methods`. Empty signature set → trivially
   true (a protocol with no requirements is satisfied by every class,
   but such a protocol is pointless and this predicate is only called
   under `structural-conformances-with-defaults` which also gates on
   `(seq (:defaults proto))`, so the pathological case is filtered
   out upstream). Reuses `signature-mismatch-detail` — L5's signature-
   match rule (name, param names, param types, return type)."
  [class-methods proto]
  (let [by-name (into {} (map (juxt :name identity) class-methods))]
    (every? (fn [ae]
              (when-let [m (get by-name (:name ae))]
                (nil? (signature-mismatch-detail m ae))))
            (:accepts proto))))

(defn- structural-conformances-with-defaults
  "L6b — return the set of protocol names the class structurally
   conforms to (satisfies all required signatures) that also have at least one
   default AND are not already in the class's explicit conformance
   set. Protocols with no defaults are skipped — structural
   conformance without inheritable defaults isn't observable here
   (dispatch-only structural conformance is a call-site concern).
   O(|protocols| × |signatures| × |methods|) per class — negligible at
   compile-scale."
  [class-methods explicit-name-set protocol-env]
  (reduce-kv
   (fn [acc pn proto]
     (if (and (not (contains? explicit-name-set pn))
              (seq (:defaults proto))
              (class-satisfies-protocol-structurally? class-methods proto))
       (conj acc pn)
       acc))
   #{}
   protocol-env))

(defn- collect-defaults-by-name
  "For a seq of protocol names, collect (default-name → [candidates])
   from each protocol's defaults, filtering out names the class
   already owns. `candidates` carry both the protocol name and the
   raw default node so downstream can extract the body per-class."
  [protocol-names own-names protocol-env]
  (reduce
   (fn [acc pn]
     (let [proto (get protocol-env pn)]
       (reduce
        (fn [a d]
          (let [mn (:name d)]
            (if (contains? own-names mn)
              a
              (update a mn (fnil conj [])
                      {:protocol pn :node d}))))
        acc
        (:defaults proto))))
   (array-map)
   protocol-names))

(defn- inherit-defaults-for-class
  "For one class entry, produce an updated :methods list with any
   applicable protocol defaults inherited. L6 covers explicit
   `:conforms`; L6b widens to include protocols the class satisfies
   structurally (has all required methods with matching signatures).
   Fires the diamond diagnostic on multi-candidate conflicts —
   whether the candidates came from explicit or structural paths
   doesn't change the ambiguity rule (§3.4.4 call-site error;
   detected at class-composition site here). Returns the class entry
   unchanged when no inheritance applies."
  [class-name entry protocol-env]
  (let [own-method-view   (observable-methods-view class-name entry)
        own-names         (set (map :name own-method-view))
        explicit-conforms (:conforms entry)
        explicit-set      (into #{} (map type-node-name explicit-conforms))
        structural-set    (structural-conformances-with-defaults
                           own-method-view explicit-set protocol-env)
        all-protos       (concat (map type-node-name explicit-conforms)
                                 structural-set)
        defaults-by-name (collect-defaults-by-name all-protos own-names
                                                   protocol-env)
        _ (doseq [[mn cands] defaults-by-name]
            (when (> (count cands) 1)
              (fail-compile!
               :cxc-f9-diamond-ambiguity
               (str "diamond ambiguity: method `" mn "` on class `"
                    class-name "` has default definitions in multiple "
                    "conforming protocols ("
                    (str/join ", " (map #(str "`" (:protocol %) "`") cands))
                    "). At least one of these conformances may be "
                    "structural (F9 L6b) — check protocol signatures. "
                    "Resolve by (a) defining `" mn "` on `" class-name
                    "` directly (own or via extension — this shadows all "
                    "defaults per §3.4.4), (b) dropping conformance "
                    "to an ambiguous protocol, or (c) changing a "
                    "method signature so structural conformance no "
                    "longer matches. See spec §3.4.4.")
               {:class class-name :method mn
                :candidates (mapv :protocol cands)})))
        inherited (mapv (fn [[_ cands]]
                          (extract-default-as-method
                           class-name (:node (first cands))))
                        defaults-by-name)]
    (if (seq inherited)
      (let [new-methods (into (vec (:methods entry)) inherited)]
        (assoc entry
               :methods       new-methods
               :method-index  (into {} (map (juxt :name identity) new-methods))
               :variant       (if (seq new-methods) :class :struct)))
      entry)))

(defn inherit-defaults-into-env
  "Walk an env (record-env or atom-env) and produce an updated env
   with inherited default methods folded into each class's :methods."
  [env protocol-env]
  (reduce-kv
   (fn [acc class-name entry]
     (assoc acc class-name (inherit-defaults-for-class
                            class-name entry protocol-env)))
   {}
   env))
