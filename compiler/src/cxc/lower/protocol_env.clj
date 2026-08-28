(ns cxc.lower.protocol-env
  "Build the protocol-env from a top-level def AST. Signature-only defs
   (`protocol :=` or class-form with `:kind :protocol`) carry the
   protocol's surface — `:observation-signature` / `:receive-signature`
   nodes for accepts entries — plus bodyful observables as defaults
   (F67 L4, Proposal G class-body collapse; the `methods:` section
   is retired).

   The env slot for each protocol is
   `{:accepts       [{:name :params :return-type}]
     :accepts-index {name → entry}
     :conforms      [type-node ...]
     :defaults      [method-shape entry ...]}`

   Post-pass `infer-protocol-defaults` (in lower.clj — needs `infer-
   type`) fills in `:cxc-return-ty` on helper defaults whose body
   didn't match a signature-typed protocol accept."
  (:require [cxc.diagnostics :refer [fail-compile!]]
            [cxc.lower.method-entry :refer [observable->method-entry]]
            [cxc.type-kw :refer [base-type-name]]))

(defn- protocol-defs
  "Return defs inferred as protocols from their signature-only body shape.
   The class/kind check preserves the parser's downstream-stable reshape;
   inferred-kind also makes this consumer tolerant of a raw signature def."
  [top-nodes]
  (filter (fn [n]
            (or (= :protocol (:inferred-kind n))
                (and (= :class (:node n)) (= :protocol (:kind n)))))
          top-nodes))

(defn- protocol-signature->accepts-entry [sig]
  (case (:node sig)
    :observation-signature
    {:node :accepts-entry
     :name (:name sig)
     :params (:params sig)
     :return-type (:return-type sig)}

    :receive-signature
    {:node :accepts-entry
     :name (:name sig)
     :params (:params sig)
     :return-type nil}

    nil))

(defn- validate-unique-protocol-signatures! [proto-name entries]
  (let [names (map :name entries)
        dupes (->> names frequencies (filter #(> (val %) 1)) (map key))]
    (when (seq dupes)
      (fail-compile! :cxc-f9-duplicate-accepts
                     (str "protocol `" proto-name "` declares signature `"
                          (first dupes) "` more than once — each selector "
                          "appears at most once in a protocol's surface. "
                          "See spec §3.4.5.")
                     {:proto proto-name :dupes (vec dupes)}))))

(defn build-protocol-env
  "Scan signature-only defs and build the protocol-env. Parser reshapes
   them to `:class :kind :protocol`, while their async/sync signature AST
   nodes remain in `:body` as the source of internal accepts/witness entries.

   F67 L4 — protocol defaults are bodyful observables inside the protocol
   body. The `methods:` section retires (Proposal G class-body collapse).
   Return types on default observables fall back to the matching sync
   signature's return type; helper defaults without a signature infer via
   `infer-protocol-defaults` post-inference."
  [top-nodes]
  (reduce
   (fn [env p]
     (let [nm       (:name p)
           signature-nodes (filterv #(contains? #{:observation-signature
                                                  :receive-signature}
                                                (:node %))
                                    (:body p))
           accepts  (mapv protocol-signature->accepts-entry signature-nodes)
           _        (validate-unique-protocol-signatures! nm accepts)
           accepts-by-name (into {} (map (juxt :name identity) accepts))
           obs-defaults (filterv #(= :observable (:node %)) (:body p))
           defaults (mapv
                     (fn [o]
                       (let [entry  (observable->method-entry nm o)
                             accept (get accepts-by-name (:name o))
                             ret    (:return-type accept)
                             ret-ty (when ret
                                      (case (:node ret)
                                        :type-ref      (:name ret)
                                        :type-of       (:outer ret)
                                        :type-nullable (base-type-name ret)
                                        :type-nonnil   (base-type-name ret)
                                        nil            nil
                                        (:name ret)))]
                         (assoc entry
                                :cxc-return-ty      ret-ty
                                :cxc-return-ty-node ret)))
                     obs-defaults)
           conforms (vec (:conforms p))]
       (assoc env nm {:accepts       accepts
                      :accepts-index accepts-by-name
                      :conforms      conforms
                      :defaults      defaults})))
   {}
   (protocol-defs top-nodes)))
