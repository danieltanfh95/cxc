(ns cxc.lower.call-shape
  "Call/dot-node shape predicates used during inference AND lowering
   to distinguish enum-payload construction (`Direction.up`,
   `Color.rgb(1,2,3)`), record-from-bytes intercept
   (`Bytes.from(source)` / `<Record>.from(...)`), and bare enum-case
   access (`Direction.up` in a value position).

   Each predicate returns nil on no-match; on match it returns a
   structured map with `:name`, `:module` (F51 provenance from
   `:decl->module`), `:kind` sentinel, and the resolved env `:info`
   so callers don't re-lookup."
  (:require [cxc.env :as env]))

(defn enum-payload-call?
  "F16 L1 — a `:call` whose callee is `<Enum>.<case>` where `<Enum>`
   is a known enum and `<case>` is one of its payload-carrying cases.

   Returns nil on no match, else a structured map:
     {:name   \"Enum\"              ; enum declared name
      :module <module-kw>          ; F51 provenance from :decl->module
      :kind   :enum-payload
      :case   \"caseName\"           ; the specific case selected
      :info   <enum-env entry>}    ; :case-index, :payload-index, etc.
   Callers destructure the fields they need — no re-lookup required."
  [call-node ctx]
  (let [callee (:callee call-node)]
    (when (and (= :dot (:node callee))
               (= :ident (:node (:target callee))))
      (let [tgt     (:target callee)
            en      (:name tgt)
            case-nm (:field callee)
            info    (env/decl-lookup ctx :enum-env tgt)
            payload (get-in info [:payload-index case-nm])]
        (when (and info payload (seq payload))
          {:name   en
           :module (get (:decl->module ctx) en)
           :kind   :enum-payload
           :case   case-nm
           :info   info})))))

(defn record-from-bytes-call?
  "F37 L6 — a `:call` whose callee is `<Record>.from` where `<Record>`
   is a known record type. Distinguished from `enum-payload-call?` by
   looking at the record-env rather than the enum-env, and requiring
   the field to be `from`. Eligibility (all primitive fields) is
   checked at lower time, not here — non-eligible receivers still
   match this predicate so the intercept can raise a targeted
   diagnostic naming the offending field type.

   Returns nil on no match, else a structured map:
     {:name   \"Record\"            ; record declared name
      :module <module-kw>          ; F51 provenance
      :kind   :record-from-bytes
      :info   <record-env entry>}  ; :fields, :field-index, ..."
  [call-node ctx]
  (let [callee (:callee call-node)]
    (when (and (= :dot (:node callee))
               (= :ident (:node (:target callee)))
               (= "from" (:field callee)))
      (let [tgt  (:target callee)
            rn   (:name tgt)
            info (env/decl-lookup ctx :record-env tgt)]
        (when info
          {:name   rn
           :module (get (:decl->module ctx) rn)
           :kind   :record-from-bytes
           :info   info})))))

(defn enum-case-access?
  "A `:dot` whose target is an `:ident` naming an enum, and whose field
   is one of that enum's cases. Not to be confused with instance field
   access on an aggregate.

   Returns nil on no match, else a structured map:
     {:name   \"Enum\"              ; enum declared name
      :module <module-kw>          ; F51 provenance
      :kind   :enum-case
      :case   \"caseName\"           ; the specific case selected
      :info   <enum-env entry>}"
  [dot-node ctx]
  (let [tgt (:target dot-node)]
    (when (= :ident (:node tgt))
      (when-let [info (env/decl-lookup ctx :enum-env tgt)]
        (when (contains? (:case-index info) (:field dot-node))
          {:name   (:name tgt)
           :module (get (:decl->module ctx) (:name tgt))
           :kind   :enum-case
           :case   (:field dot-node)
           :info   info})))))
