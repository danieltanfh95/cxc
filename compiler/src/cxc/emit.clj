(ns cxc.emit
  "Emit ObjC/C source from the ObjC AST produced by cxc.lower.

   Output is a single `.m` string per :objc-program. Grown feature by
   feature alongside the lower pass."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]))

(declare emit-expr emit-stmt emit-class emit-struct indent-lines)

;; ---------------------------------------------------------------------------
;; Expression emission
;; ---------------------------------------------------------------------------

(defn- escape-c-string [s]
  ;; Minimal escaping: backslash, double-quote, newline, tab, return.
  ;; Enough for the current subset; extend as needed.
  (-> s
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")
      (str/replace "\t" "\\t")
      (str/replace "\r" "\\r")))

(def ^:private int-c-types
  #{"int64_t" "int32_t" "int16_t" "int8_t"
    "uint64_t" "uint32_t" "uint16_t" "uint8_t"
    "int" "long" "short" "char" "_Bool" "BOOL"
    "long long" "unsigned int" "unsigned long"})

(defn- fmt-value-expr
  "Return [printf-fmt-fragment argument-exprs] for one field of type ct
   read from an lvalue like `__cxc_snap._foo`. Fragments concatenate
   into a single snprintf format string; args become the tail arguments.
   Returned args are a vector so struct types can produce multiple."
  [ct lvalue]
  (cond
    (or (= ct "id") (and (string? ct) (.contains ^String ct "*")))
    ["%p" [(str "(__bridge void *)(" lvalue ")")]]

    (contains? int-c-types ct)
    ["%lld" [(str "(long long)(" lvalue ")")]]

    (= ct "_Cxc_Optional_Int64")
    ["{p=%d,v=%lld}"
     [(str "(int)(" lvalue ".present)") (str "(long long)(" lvalue ".value)")]]

    (= ct "_Cxc_Optional_Bool")
    ["{p=%d,v=%d}"
     [(str "(int)(" lvalue ".present)") (str "(int)(" lvalue ".value)")]]

    :else
    ["?" []]))

(defn- field-write-trace
  "Emit a CXC_TRACE_ENABLED-guarded field_write event for one data-field
   commit at :commit-next-state. Fires only when the ivar's current
   value differs from __cxc_next's value, so no-op commits stay silent.

   Dispatch on c-type:
   - integer/bool                 → old=N,new=N
   - pointer (`id`, `X *`)        → old=%p,new=%p (ARC-bridged)
   - `_Cxc_Optional_Int64/Bool`   → present+value pair
   - other struct                 → memcmp, log `type=X,changed`

   All comparisons use `!=` for scalars/pointers and `memcmp` for
   structs so block-typed fields (whose type name splices in the field
   name) stay well-formed without a saved old-local."
  [f]
  (let [ct   (:c-type f)
        nm   (:name f)
        ivar (str "_" nm)
        nxt  (str "__cxc_next._" nm)
        opt-int?  (= ct "_Cxc_Optional_Int64")
        opt-bool? (= ct "_Cxc_Optional_Bool")
        [cmp fmt-args]
        (cond
          (or (= ct "id") (and (string? ct) (.contains ^String ct "*")))
          [(str ivar " != " nxt)
           (str "\"old=%p,new=%p\", (__bridge void *)(" ivar "), (__bridge void *)(" nxt ")")]

          (contains? int-c-types ct)
          [(str ivar " != " nxt)
           (str "\"old=%lld,new=%lld\", (long long)(" ivar "), (long long)(" nxt ")")]

          opt-int?
          [(str "memcmp(&" ivar ", &" nxt ", sizeof(" ivar ")) != 0")
           (str "\"old={p=%d,v=%lld},new={p=%d,v=%lld}\", "
                "(int)(" ivar ".present), (long long)(" ivar ".value), "
                "(int)(" nxt ".present), (long long)(" nxt ".value)")]

          opt-bool?
          [(str "memcmp(&" ivar ", &" nxt ", sizeof(" ivar ")) != 0")
           (str "\"old={p=%d,v=%d},new={p=%d,v=%d}\", "
                "(int)(" ivar ".present), (int)(" ivar ".value), "
                "(int)(" nxt ".present), (int)(" nxt ".value)")]

          :else
          [(str "memcmp(&" ivar ", &" nxt ", sizeof(" ivar ")) != 0")
           (str "\"type=" (escape-c-string (or ct "?")) ",changed\"")])]
    (str "#ifdef CXC_TRACE_ENABLED\n"
         "if (" cmp ") {\n"
         "    char __cxc_fw[192];\n"
         "    snprintf(__cxc_fw, sizeof(__cxc_fw), " fmt-args ");\n"
         "    CXC_TRACE_EX(\"field_write\", nil, self, \"" nm "\", __cxc_fw);\n"
         "}\n"
         "#endif\n")))

(defn- emit-expr [node]
  (case (:node node)
    :str             (str "\"" (escape-c-string (:value node)) "\"")
    :objc-str        (str "@\"" (escape-c-string (:value node)) "\"")
    :int             (str (:value node))
    :float           (str (:value node))
    :nil             (or (:sentinel node) "nil")
    :name            (:sym node)
    ;; Track A/2.b (2026-08-26) — expression-position raw-C escape
    ;; hatch used by lower for shapes that don't fit an existing
    ;; structured node (e.g. `((_Cxc_R_T *)tmp)->value.field` for
    ;; reason-box payload bindings). Cousin of the statement-form
    ;; `:raw-c`; kept narrow — prefer structured nodes wherever the
    ;; ergonomics allow.
    :raw-c-expr      (:text node)
    ;; A3 (2026-09-03) — tagged-variant helpers for `is` patterns.
    ;; `:c-tag-access` reads the discriminator: `(<inner>).tag`.
    ;; `:c-payload-access` reads a payload field via the tagged-union
    ;; storage: `(<inner>).data.<case>.<field>`. Only the arm-head
    ;; lowerer produces these; wrapping the inner in parens keeps
    ;; grouping safe when `<inner>` is a compound expression.
    :c-tag-access
    (if (:optional? node)
      (str "(" (emit-expr (:inner node)) ").value.tag")
      (str "(" (emit-expr (:inner node)) ").tag"))
    :c-payload-access
    (if (:optional? node)
      (str "(" (emit-expr (:inner node)) ").value.data."
           (:case-name node) "." (:field-name node))
      (str "(" (emit-expr (:inner node)) ").data."
           (:case-name node) "." (:field-name node)))
    :c-optional-present
    (str "(" (emit-expr (:inner node)) ").present")

    ;; 2026-09-04 Path 1 slice 1 — bare unwrap `env.value`. Used at
    ;; method-dispatch sites where the receiver type-kw is envelope
    ;; but the callee's body expects the raw inner value. No presence
    ;; check — nil-propagation for method returns is Slice 7's
    ;; concern; slice 1 just makes the unwrap path exist. Under the
    ;; current invariant that no local produces envelope, this branch
    ;; is dead code today; slice 2 flips locals and starts firing it.
    :c-optional-unwrap-value
    (str "(" (emit-expr (:inner node)) ").value")
    ;; 2026-09-04 Path 1 slice 5c — envelope-Bool as raw truthy:
    ;; `.present && .value` in a raw-int position. Used at dispatch-
    ;; arm guard combine sites where an envelope guard is `&&`'d with
    ;; a pattern-cond (raw int); the wrapper produces 0 for
    ;; present-false / absent, 1 for present-true.
    :c-optional-truthy
    (let [inner-c (emit-expr (:inner node))]
      (str "({ __typeof__(" inner-c ") __cxc_ot = (" inner-c "); "
           "__cxc_ot.present && __cxc_ot.value; })"))
    ;; 2026-09-04 Path 1 slice 2 — envelope construction shapes. Nil
    ;; literal → `(T){.present = 0}`; value literal → `(T){.present =
    ;; 1, .value = <inner>}`. Used by `lower-let-stmt` when the LHS is
    ;; a bare nullable primitive and the RHS is nil or a raw scalar.
    :c-envelope-nil
    (str "(" (:c-type node) "){.present = 0}")
    :c-envelope-lit
    (str "(" (:c-type node) "){.present = 1, .value = "
         (emit-expr (:value node)) "}")
    ;; A3 — `:is-check` at expression position emits only the gate.
    ;; Binding decls are surfaced separately by the transition-branch
    ;; lowerer (which prepends them to the arm body); nothing at emit
    ;; time needs them.
    :is-check
    (emit-expr (:gate node))
    :binop           (str "(" (emit-expr (:left node))
                          " " (:op node) " "
                          (emit-expr (:right node)) ")")
    ;; F39 L6 (2026-08-21) — C ternary. Emitted only by the `??`
    ;; coalesce lowering today; a general-purpose expression form
    ;; also usable by any future landing needing conditional-value
    ;; semantics without a full `:c-if-chain` block.
    ;; 2026-09-04 Gap 6 — bool nil-punning at ternary cond, same
    ;; discipline as :c-if-chain / :c-while. `-1` (sentinel nil)
    ;; degrades to false in boolean context; standard 0/1 producers
    ;; unaffected by the `> 0` wrap. `||:` multi-arm dispatch lowers
    ;; into a right-fold of :c-ternary chains, so this reaches every
    ;; guard in the chain.
    :c-ternary       (let [cond-c (emit-expr (:cond node))
                           guard  (if (:cond-envelope? node)
                                    (str "({ __typeof__(" cond-c ") __cxc_tg = ("
                                         cond-c "); __cxc_tg.present && __cxc_tg.value; })")
                                    (str "((" cond-c ")) > 0"))]
                       (str "(" guard
                            " ? " (emit-expr (:then node))
                            " : " (emit-expr (:else node)) ")"))

    ;; F39 L6 (2026-08-21) — `??` coalesce. Emits a GNU
    ;; statement-expression that binds the LHS to a temporary
    ;; (`__typeof__(x) t = (x)`) so a side-effecting LHS fires
    ;; exactly once, then ternaries on presence.
    ;;
    ;; :mode :reference — LHS is a nullable pointer / id / class
    ;; instance / container. Check is `t != nil`.
    ;;
    ;; :mode :optional (L5b-ii-b onward) — LHS is `_Cxc_Optional_<T>`.
    ;; Check is `t.present`; unwrapped value is `t.value`.
    ;;
    ;; :mode :identity (2026-09-14) — LHS is a bare primitive
    ;; (int64_t / bool / double / sized variants). Can't be nil at
    ;; the C level, so `??` collapses to the LHS. RHS lowered for
    ;; type-check but not emitted. Nil-punning discipline: a value
    ;; that can't be nil passes through `??` untouched.
    :c-coalesce
    (let [left-c  (emit-expr (:left node))
          right-c (emit-expr (:right node))]
      (case (:mode node)
        :reference
        (str "({ __typeof__(" left-c ") __cxc_coal = (" left-c "); "
             "(__cxc_coal != nil) ? __cxc_coal : (" right-c "); })")
        :optional
        (str "({ __typeof__(" left-c ") __cxc_coal = (" left-c "); "
             "(__cxc_coal.present) ? __cxc_coal.value : (" right-c "); })")
        :identity
        left-c
        ;; 2026-09-04 Gap 5 — sentinel primitive `??`. Use the
        ;; type-specific IS_NIL macro (INT64_MIN / -1 / NaN) instead
        ;; of pointer-comparison; `(INT64_MIN != nil)` in C would
        ;; yield `true` and keep the sentinel value silently.
        :primitive
        (let [chk (:nil-check-macro node)]
          (str "({ __typeof__(" left-c ") __cxc_coal = (" left-c "); "
               chk "(__cxc_coal) ? (" right-c ") : __cxc_coal; })"))))

    ;; F39 L8 (spec §4.10.9) — nil-receiver dispatch fallback. When the
    ;; callee atom declares an `on_nil:` entry for the selector, the
    ;; method call site is wrapped in a guarded ternary: on non-nil
    ;; receiver the ObjC message fires, on nil the declared constant
    ;; falls through. GNU stmt-expr binds `recv` once (side-effecting
    ;; receivers like `Counter().other.method()` fire exactly once —
    ;; matches L6-ref `??` and L5b-ii-d unwrap patterns).
    ;;
    ;; The `:send` sub-node is a `:objc-msg` whose `:recv` is `target-lo`
    ;; (the raw receiver expression); we override to reference the
    ;; single-eval temp so the wire read of `_other` fires once, not
    ;; twice.
    :c-on-nil-dispatch
    (let [recv-c     (emit-expr (:recv node))
          send-node  (:send node)
          ;; Rebind the receiver-slot on the send-node to the single-eval
          ;; temp. Two shapes: `:objc-msg` (arg-form method call) carries
          ;; `:recv`; `:field` (bare `.name` observable read) carries
          ;; `:target`. Both surfaces need to reference the same temp so
          ;; the receiver-expression fires exactly once.
          rebound    (case (:node send-node)
                       :objc-msg (assoc send-node :recv
                                        {:node :name :sym "__cxc_recv"})
                       :field    (assoc send-node :target
                                        {:node :name :sym "__cxc_recv"})
                       send-node)
          send-c     (emit-expr rebound)
          fallback-c (emit-expr (:fallback node))]
      (str "({ __typeof__(" recv-c ") __cxc_recv = (" recv-c "); "
           "(__cxc_recv != nil) ? " send-c " : (" fallback-c "); })"))

    ;; 2026-09-04 Gap 6 — Kleene 3-valued `and` / `or` for nullable
    ;; Bool operands. Preserves short-circuit on the dominant value
    ;; (AND short-circuits on false, OR on true) so `x != nil and
    ;; x.method()` doesn't fire the method on nil. When neither side
    ;; short-circuits, either operand being nil (-1) yields nil,
    ;; else both are true/false and the arithmetic C result stands.
    :c-kleene-bool-op
    (let [left-c  (emit-expr (:left  node))
          right-c (emit-expr (:right node))
          op      (:op node)
          ;; AND short-circuits on 0 (false), yielding 0.
          ;; OR  short-circuits on 1 (true),  yielding 1.
          [sc-val fallback-val]
          (case op
            "and" ["0" "1"]
            "or"  ["1" "0"])]
      (str "({ __typeof__(" left-c ") __cxc_kl = (" left-c "); "
           "(__cxc_kl == " sc-val ") ? " sc-val
           " : ({ __typeof__(" right-c ") __cxc_kr = (" right-c "); "
           "(__cxc_kr == " sc-val ") ? " sc-val
           " : ((__cxc_kl == -1 || __cxc_kr == -1) ? -1 : " fallback-val "); }); })"))

    ;; 2026-09-04 Path 1 slice 6 — envelope Kleene AND/OR. Semantics:
    ;;   L=false → present false (AND short-circuit)
    ;;   R=false → present false (AND short-circuit)
    ;;   L present, R present → present true (AND)
    ;;   otherwise → nil
    ;; Mirror for OR: short-circuit on present-true, else nil.
    ;;
    ;; Mixed operand support (`:left-opt?` / `:right-opt?`): raw side
    ;; treated as always-present with its literal value; envelope side
    ;; consults `.present` / `.value`. Result is always envelope-Bool
    ;; (`_Cxc_Optional_Bool`) — Kleene's third state exists only in
    ;; the envelope shape.
    :c-envelope-kleene-op
    (let [left-c   (emit-expr (:left  node))
          right-c  (emit-expr (:right node))
          l-opt?   (:left-opt?  node)
          r-opt?   (:right-opt? node)
          opt-c    (:result-opt-c-type node)
          l-pres   (if l-opt? "__cxc_kl.present" "1")
          r-pres   (if r-opt? "__cxc_kr.present" "1")
          l-val    (if l-opt? "__cxc_kl.value"   "__cxc_kl")
          r-val    (if r-opt? "__cxc_kr.value"   "__cxc_kr")
          op       (:op node)
          [dom-val recessive-val]
          (case op
            "and" ["0" "1"]  ; false dominates AND
            "or"  ["1" "0"]) ; true  dominates OR
          nil-lit    (str "(" opt-c "){.present = 0}")
          present-t  (str "(" opt-c "){.present = 1, .value = "
                          recessive-val "}")
          present-d  (str "(" opt-c "){.present = 1, .value = "
                          dom-val "}")]
      (str "({ __typeof__(" left-c ") __cxc_kl = (" left-c "); "
           "__typeof__(" right-c ") __cxc_kr = (" right-c "); "
           "(" l-pres " && " l-val " == " dom-val ") ? " present-d " : "
           "(" r-pres " && " r-val " == " dom-val ") ? " present-d " : "
           "(" l-pres " && " r-pres ") ? " present-t " : "
           nil-lit "; })"))

    ;; 2026-09-04 Gap 2 — method returning `T!` primitive invoked on
    ;; a nullable atom receiver. Spec §3.6.1 says the `!` contract
    ;; MUST hold; ObjC's nil-messaging silently returns 0/false/0.0
    ;; for primitive returns on nil receivers, which would violate
    ;; the contract undetected. Fire the warning-grade
    ;; `cxc_nonnil_violation` diagnostic (same helper as L3 T!
    ;; enforcement — stderr write, no abort) so the violation is
    ;; loud, then let the ObjC nil-messaging produce its zero-value
    ;; (matches L3's non-abort discipline).
    :c-nonnil-return-guard
    (let [recv-c    (emit-expr (:recv node))
          send-node (:send node)
          rebound   (case (:node send-node)
                      :objc-msg (assoc send-node :recv
                                       {:node :name :sym "__cxc_recv"})
                      :field    (assoc send-node :target
                                       {:node :name :sym "__cxc_recv"})
                      send-node)
          send-c    (emit-expr rebound)
          label     (:site-label node)]
      (str "({ __typeof__(" recv-c ") __cxc_recv = (" recv-c "); "
           "if (__cxc_recv == nil) cxc_nonnil_violation("
           "\"" (escape-c-string label) "\", "
           "\"" (escape-c-string "receiver") "\"); "
           send-c "; })"))

    ;; F39 L5b-ii-d — Optional operand feeding a `T!` value-typed
    ;; observation point (field write, ctor bind, method return). GNU
    ;; stmt-expr binds once, fires cxc_nonnil_violation on absent (same
    ;; warning-grade helper L3 uses for reference `T!` sites — writes to
    ;; stderr, returns), then yields `.value`. Warning-grade: on absent
    ;; the assign proceeds with the zero-inited `.value` (matches L3's
    ;; non-abort discipline; caller sees garbage rather than panic).
    :c-value-nonnil-unwrap
    (let [inner-c (emit-expr (:inner node))]
      (str "({ __typeof__(" inner-c ") __cxc_uv = (" inner-c "); "
           "if (!__cxc_uv.present) cxc_nonnil_violation("
           "\"" (escape-c-string (:site node)) "\", "
           "\"" (escape-c-string (:name node)) "\"); "
           "__cxc_uv.value; })"))

    ;; F39 L5b-ii-c — invariant-position Optional<Bool>. Emits as
    ;; `(present && value)` with single-eval binding, so `if (!cond)`
    ;; in the enclosing invariant-check fires iff the Optional yielded
    ;; a *present false*. Nil operands skip the invariant (nil-punning
    ;; makes the whole predicate nil; skipping matches the "if data is
    ;; present, invariant must hold" reading).
    :c-optional-cond
    (let [inner-c (emit-expr (:inner node))]
      (str "({ __typeof__(" inner-c ") __cxc_oc = (" inner-c "); "
           "(__cxc_oc.present && __cxc_oc.value); })"))

    ;; F39 L5b-ii-c (2026-08-21) — presence check on Optional.
    ;; `x is nil` / `x == nil` / `x != nil` lower here when x is Optional.
    ;; Single-eval binding so a side-effecting operand fires once.
    :c-optional-nil-check
    (let [inner-c (emit-expr (:inner node))
          op      (if (:negate? node) "!=" "==")]
      (str "({ __typeof__(" inner-c ") __cxc_pc = (" inner-c "); "
           "(__cxc_pc.present " op " 0); })"))

    ;; 2026-09-05 slice 10 — `:c-primitive-nil-check` node retired.
    ;; Path 1 storage flip means all nullable primitives are envelope;
    ;; `x == nil` on envelope routes through `:c-optional-nil-check`,
    ;; while raw-primitive `== nil` is a compile-time constant handled
    ;; at lower time.

    ;; F39 L5b-ii-c (2026-08-21) — nil-punning binop. Arithmetic /
    ;; ordering propagate (return Optional); equality reduces to Bool.
    :c-optional-binop
    (let [left-c  (emit-expr (:left  node))
          right-c (emit-expr (:right node))
          op      (:op node)
          l-opt?  (:left-opt?  node)
          r-opt?  (:right-opt? node)
          l-val   (if l-opt? "__cxc_ol.value"   "__cxc_ol")
          r-val   (if r-opt? "__cxc_or.value"   "__cxc_or")
          l-pres  (if l-opt? "__cxc_ol.present" "1")
          r-pres  (if r-opt? "__cxc_or.present" "1")
          opt-c   (:result-opt-c-type node)]
      (case (:result-kind node)
        :arith
        (str "({ __typeof__(" left-c ") __cxc_ol = (" left-c "); "
             "__typeof__(" right-c ") __cxc_or = (" right-c "); "
             "(" l-pres " && " r-pres ") "
             "? (" opt-c "){.present = 1, .value = " l-val " " op " " r-val "} "
             ": (" opt-c "){0}; })")
        :ord
        (str "({ __typeof__(" left-c ") __cxc_ol = (" left-c "); "
             "__typeof__(" right-c ") __cxc_or = (" right-c "); "
             "(" l-pres " && " r-pres ") "
             "? (" opt-c "){.present = 1, .value = (" l-val " " op " " r-val ")} "
             ": (" opt-c "){0}; })")
        :eq
        (str "({ __typeof__(" left-c ") __cxc_ol = (" left-c "); "
             "__typeof__(" right-c ") __cxc_or = (" right-c "); "
             "((" l-pres " == " r-pres ") "
             "&& (!" l-pres " || " l-val " == " r-val ")); })")
        :neq
        (str "({ __typeof__(" left-c ") __cxc_ol = (" left-c "); "
             "__typeof__(" right-c ") __cxc_or = (" right-c "); "
             "(!((" l-pres " == " r-pres ") "
             "&& (!" l-pres " || " l-val " == " r-val "))); })")))

    ;; F39 L5b-ii-c — Optional-propagating unary op (`-`, `!`).
    :c-optional-unary
    (let [inner-c (emit-expr (:inner node))
          opt-c   (:result-opt-c-type node)
          op      (:op node)]
      (str "({ __typeof__(" inner-c ") __cxc_ou = (" inner-c "); "
           "__cxc_ou.present "
           "? (" opt-c "){.present = 1, .value = " op "__cxc_ou.value} "
           ": (" opt-c "){0}; })"))

    ;; F39 L5b-ii-c — Optional-propagating dot access: `x.field` where
    ;; x is Optional-Record. Two shapes:
    ;;   inner-scalar   → wrap `.value.field` into an Optional
    ;;   inner-Optional → the inner `.value.field` IS the Optional; pass
    ;;                    through when outer present, else nil-of-inner
    :c-optional-dot
    (let [target-c (emit-expr (:target node))
          fname    (:field node)
          opt-c    (:result-opt-c-type node)]
      (if (:inner-already-opt? node)
        (str "({ __typeof__(" target-c ") __cxc_od = (" target-c "); "
             "__cxc_od.present "
             "? __cxc_od.value." fname " "
             ": (" opt-c "){0}; })")
        (str "({ __typeof__(" target-c ") __cxc_od = (" target-c "); "
             "__cxc_od.present "
             "? (" opt-c "){.present = 1, .value = __cxc_od.value." fname "} "
             ": (" opt-c "){0}; })")))

    ;; F39 L5b-ii-c — print of Optional arg. Present → format inner;
    ;; nil → "nil\n". Matches ObjC's reference-nil `print(nil)` convention.
    :c-print-optional
    (let [arg-c    (emit-expr (:arg node))
          inner-tk (:inner-arg-type node)
          inner-fmt (:inner-fmt node)
          fmt-esc  (escape-c-string inner-fmt)
          family (cond
                   (= :int inner-tk)   :int
                   (= :float inner-tk) :float
                   (= :bool inner-tk)  :int
                   (vector? inner-tk)  (first inner-tk)
                   :else               :int)
          cast     (case family
                     :int   "(long long)"
                     :uint  "(unsigned long long)"
                     :float "(double)"
                     "")]
      (str "({ __typeof__(" arg-c ") __cxc_po = (" arg-c "); "
           "__cxc_po.present "
           "? printf(\"" fmt-esc "\", " cast "(__cxc_po.value)) "
           ": printf(\"nil\\n\"); })"))

    :unary           (str "(" (:op node) (emit-expr (:expr node)) ")")

    ;; F39 L5b-ii-c — Optional-aware chained-cmp. Single-eval each
    ;; operand into a `__typeof__` local, compute all-present flag
    ;; from operand-Optional markers, then either return
    ;; Optional<Bool>{present:1, value:chain-of-`.value`-comparisons}
    ;; or Optional<Bool>{0}. Dead until Sub-P flips storage.
    :c-optional-chained-cmp
    (let [operands   (:operands node)
          opts?      (:operand-opts? node)
          ops        (:ops node)
          n          (count operands)
          rendered   (mapv emit-expr operands)
          binding-of (fn [i] (str "__cxc_occ_" i))
          prelude    (->> (range n)
                          (map (fn [i]
                                 (str "__typeof__(" (nth rendered i) ") "
                                      (binding-of i) " = ("
                                      (nth rendered i) "); ")))
                          (apply str))
          present-parts (->> (range n)
                             (map (fn [i]
                                    (if (nth opts? i)
                                      (str (binding-of i) ".present")
                                      "1")))
                             (str/join " && "))
          value-of  (fn [i]
                      (if (nth opts? i)
                        (str (binding-of i) ".value")
                        (binding-of i)))
          chain     (->> (range (dec n))
                         (map (fn [i]
                                (str (value-of i) " " (nth ops i) " " (value-of (inc i)))))
                         (str/join " && "))
          opt-c     (:result-opt-c-type node)]
      (str "({ " prelude
           "(" present-parts ") "
           "? (" opt-c "){.present = 1, .value = (" chain ")} "
           ": (" opt-c "){0}; })"))

    ;; F18 L2 — `do:` at expression position lowers to :c-stmt-expr. Emit
    ;; as GNU statement-expression `({ stmts; })`. Each lowered stmt is
    ;; either a stmt-node (:var-decl, :c-while, …) that emit-stmt renders
    ;; with a trailing `;` or an expression node from lower-expr on an
    ;; :expr-stmt (emit-stmt appends `;` in the :call / :objc-msg / …
    ;; branches). GNU stmt-expr yields the value of the last expression-
    ;; statement it contains (else void). Empty body renders `((void)0)`
    ;; per open-Q3 ratified decision (decisions-frozen §F18).
    :c-stmt-expr
    (let [lines (mapv emit-stmt (:stmts node))]
      (if (empty? lines)
        "((void)0)"
        (str "({ " (str/join " " lines) " })")))

    :chained-cmp
    ;; F27 semantic once-only: render `-max <= counter.next() <= max`
    ;; as a GNU statement-expression that binds each *interior*
    ;; operand (operands[1..len-2]) to a `__typeof__` local, then
    ;; ANDs the chained comparisons using the bound locals. Head +
    ;; tail operands appear once each; interior operands appear once
    ;; at the binding site and once per adjacent comparison against
    ;; the bound local. `__typeof__` is compile-time and doesn't
    ;; evaluate its operand.
    (let [operands   (:operands node)
          ops        (:ops node)
          n          (count operands)
          rendered   (mapv emit-expr operands)
          interior?  (fn [i] (and (pos? i) (< i (dec n))))
          binding-of (fn [i] (str "__cxc_cc_" i))
          prelude    (->> (range n)
                          (filter interior?)
                          (map (fn [i]
                                 (str "__typeof__(" (nth rendered i) ") "
                                      (binding-of i) " = ("
                                      (nth rendered i) "); ")))
                          (apply str))
          eff        (fn [i]
                       (if (interior? i)
                         (binding-of i)
                         (nth rendered i)))
          chain      (->> (range (dec n))
                          (map (fn [i]
                                 (str (eff i) " " (nth ops i) " " (eff (inc i)))))
                          (str/join " && "))]
      (str "({ " prelude chain "; })"))
    :field
    ;; F32 L4.d.β.β (2026-08-24) — cross-atom `.field` reads carrying
    ;; `:cross-atom-cls-name` route through the atom's committed state
    ;; buffer under `!CXC_SINGLE_MAIN_QUEUE` (acquire-ordered atomic
    ;; load + pointer projection via `_cxc_atom_state_ptr`). Under
    ;; the default preprocessor flag the ObjC property access path
    ;; stays — byte-identical to pre-L4.d.β.β for the emitted binary.
    ;; L4.d.β.α's initial-state-commit guarantees `_state` is
    ;; populated before any observer can reach it, so the acquire
    ;; load is well-defined on the first read.
    (let [target (emit-expr (:target node))
          field  (:field node)
          cls    (:cross-atom-cls-name node)]
      (if cls
        (str "("
             "\n#if !CXC_SINGLE_MAIN_QUEUE\n"
             "((" cls "_State *)_cxc_atom_state_ptr(&" target "->_state))->_" field
             "\n#else\n"
             target "." field
             "\n#endif\n"
             ")")
        (str target "." field)))
    :struct-lit      (str "((" (:type node) "){"
                          (str/join ", "
                                    (map (fn [f]
                                           (str "." (:name f) " = "
                                                (emit-expr (:value f))))
                                         (:fields node)))
                          "})")
    :c-tagged-init   (let [en   (:enum-name node)
                           cs   (:case-name node)
                           fs   (:fields node)
                           data (when (seq fs)
                                  (str ", .data." cs " = {"
                                       (str/join ", "
                                                 (map (fn [f]
                                                        (str "." (:name f) " = "
                                                             (emit-expr (:value f))))
                                                      fs))
                                       "}"))]
                       ;; F16 L1 — designated-initializer compound literal
                       ;; for a tagged-union enum value. Bare cases in a
                       ;; tagged enum omit the `.data.<case>` part.
                       (str "((" en "){.tag = " en "_Tag_" cs
                            (or data "") "})"))
    :objc-alloc-init (str "[[" (:class node) " alloc] init]")
    :objc-init-with  (let [parts (:args node)
                           first-part (str "initWith" (:name (first parts)) ":"
                                           (emit-expr (:value (first parts))))
                           rest-parts (str/join " "
                                                (map (fn [p]
                                                       (str (:name p) ":" (emit-expr (:value p))))
                                                     (rest parts)))]
                       (str "[[" (:class node) " alloc] "
                            first-part
                            (when-not (str/blank? rest-parts) (str " " rest-parts))
                            "]"))
    :call            (let [args (:args node)
                           ;; Phase D F36a Landing 1a — printf varargs
                           ;; need explicit width matching. lower-print
                           ;; tags the call with :printf-arg-type so we
                           ;; can wrap arg[1] (the value; arg[0] is the
                           ;; format string) in a cast to the format
                           ;; specifier's expected width. Prevents UB
                           ;; when a narrower sized-int flows into
                           ;; `%lld` or when Double flows into `%f`.
                           printf-tk (:printf-arg-type node)
                           ;; Phase D F36a Landing 1b — printf-arg-type
                           ;; is either `:int`/`:float` (defaults) or a
                           ;; width variant `[:int N]`/`[:uint N]`/
                           ;; `[:float N]`. Signed ints cast to long
                           ;; long; unsigned ints to unsigned long
                           ;; long; floats to double.
                           ;;
                           ;; F65: for :string, extract UTF8 bytes at
                           ;; the print site — String is NSString *
                           ;; (§3.10.5), but `%s` printf takes const
                           ;; char *. `[str UTF8String]` bridges.
                           printf-family (cond
                                           (= :int printf-tk)    :int
                                           (= :float printf-tk)  :float
                                           (= :string printf-tk) :string
                                           (vector? printf-tk)   (first printf-tk))
                           wrap-arg  (case printf-family
                                       :int    (fn [s] (str "(long long)(" s ")"))
                                       :uint   (fn [s] (str "(unsigned long long)(" s ")"))
                                       :float  (fn [s] (str "(double)(" s ")"))
                                       :string (fn [s] (str "[" s " UTF8String]"))
                                       nil)
                           ;; 2026-09-18 — Async-send arg snapshot.
                           ;; `cxc_send_async(target, name, ^{ [target selWith:<expr>]; })`
                           ;; runs the block at dispatch time, so `<expr>`
                           ;; re-evaluates against post-mutation ivars
                           ;; (e.g. `p0.hand.at(0)` reads the current top
                           ;; card, not the top at enqueue). Snapshot each
                           ;; msg-send arg into a `__typeof__`-inferred
                           ;; local BEFORE the block; block refers to the
                           ;; local. Matches the sender-site "capture by
                           ;; copy" comment on :objc-block-literal below.
                           async-send-fn? (contains?
                                            #{"cxc_send_async"
                                              "cxc_send_async_bounded"}
                                            (:fn node))
                           last-arg       (last args)
                           block-arg?     (and async-send-fn?
                                               (map? last-arg)
                                               (= :objc-block-literal
                                                  (:node last-arg))
                                               (= 1 (count (:stmts last-arg)))
                                               (= :objc-msg
                                                  (get-in last-arg [:stmts 0 :node]))
                                               (seq (get-in last-arg [:stmts 0 :args])))
                           snap-form      (when block-arg?
                                            (let [inner (get-in last-arg [:stmts 0])
                                                  inner-args (:args inner)
                                                  snap-names (mapv #(str "__cxc_snap" %)
                                                                   (range (count inner-args)))
                                                  snap-decls (str/join
                                                               " "
                                                               (map (fn [nm a]
                                                                      (let [e (emit-expr a)]
                                                                        (str "__typeof__(" e ") "
                                                                             nm " = (" e ");")))
                                                                    snap-names inner-args))
                                                  new-inner  (assoc inner :args
                                                                    (mapv (fn [nm]
                                                                            {:node :raw-c-expr
                                                                             :text nm})
                                                                          snap-names))
                                                  new-block  (assoc last-arg :stmts [new-inner])
                                                  head-args  (butlast args)
                                                  head-strs  (map emit-expr head-args)
                                                  send-str   (str (:fn node) "("
                                                                  (str/join ", "
                                                                            (concat head-strs
                                                                                    [(emit-expr new-block)]))
                                                                  ")")]
                                              (str "({ " snap-decls " " send-str "; })")))
                           arg-strs  (map-indexed
                                       (fn [i a]
                                         (let [s (emit-expr a)]
                                           (if (and wrap-arg (= i 1))
                                             (wrap-arg s)
                                             s)))
                                       args)]
                       (or snap-form
                           (str (:fn node)
                                "("
                                (str/join ", " arg-strs)
                                ")")))
    :objc-block-literal
    ;; `^{ stmt; stmt; }` (or `^(T1 a, T2 b) { ... }` when :params
    ;; present) — an ObjC block literal. Used both to wrap message-
    ;; send bodies for cxc_send_async / cxc_send_sync (void^(void))
    ;; and to pass callbacks into container methods like
    ;; `-cxc_for_each:` (void^(id) — Layer 5 Step 5). Blocks capture
    ;; surrounding variables by copy — atom pointers via ARC-strong
    ;; retain, primitives by value; safe by construction because
    ;; the block's target lifetime is the outer expression.
    (let [stmts    (str/join "\n" (map emit-stmt (:stmts node)))
          indented (indent-lines 4 stmts)
          params   (:params node)
          ret      (:return-c-type node)
          ;; A5 (2026-09-03) — optional explicit return type
          ;; (`^BOOL(id x){...}`) for blocks whose call-site
          ;; parameter type requires a specific block return type
          ;; that clang won't infer from the last stmt (BOOL vs int
          ;; is the common case). Absent → inferred.
          sig      (if (seq params)
                     (str "^" (or ret "") "("
                          (str/join ", "
                                    (map (fn [p] (str (:c-type p) " " (:name p)))
                                         params))
                          ")")
                     (str "^" (or ret "")))]
      (str sig "{\n" indented "\n}"))
    :objc-cast       (str "(" (:c-type node) ")" (emit-expr (:expr node)))
    :objc-num-lit    (case (:kind node)
                       :int   (str "@" (:value node))
                       :float (str "@(" (:value node) ")")
                       :bool  (if (:value node) "@YES" "@NO")
                       (str "@(" (emit-expr (:expr node)) ")"))
    :objc-array-literal
    ;; ObjC boxed-array literal `@[<items>]`. Items are already emitted
    ;; in their boxed form (NSNumber for primitives, id for references).
    ;; Used by List of T initializers to feed the container class'
    ;; -initWithElements:(NSArray *) constructor.
    (str "@[" (str/join ", " (map emit-expr (:items node))) "]")
    :objc-nsvalue-box
    ;; NSValue wrap around a C struct value (List-of-record boxing).
    ;; `:inner` is the struct expression. Materialize to a named local
    ;; via GCC statement-expression so `&__cxc_nv` is always an lvalue
    ;; — the address form works whether inner is a compound literal
    ;; (lvalue) or a nested stmt-expr / ternary / call (rvalue, e.g.
    ;; the `??`-coalesced record produced by `remaining.at(i) ?? sentinel`).
    ;; NSValue copies the bytes at call time so lifetime is safe.
    ;; Downstream unbox uses -getValue:size: (see unbox-prelude-for-element).
    (str "({ " (:c-type node) " __cxc_nv = " (emit-expr (:inner node))
         "; [NSValue valueWithBytes:&__cxc_nv objCType:@encode("
         (:c-type node) ")]; })")
    :ffi-call
    ;; Compiler-recognized native call (Layer 5b Step A). The lower
    ;; pass attached `:emit-c-fn`, a fn taking rendered arg strings
    ;; and returning the raw C expression. Keeps emit registry-free
    ;; while letting one place (lower's ffi-builtins map) own the
    ;; native surface.
    ((:emit-c-fn node) (mapv emit-expr (:args node)))
    :objc-msg        (let [sel (:selector node)
                           args (:args node)]
                       ;; ObjC selectors either have zero colons (bare name,
                       ;; zero args) or one colon per arg baked into the
                       ;; selector string. Interleave selector parts with args.
                       (if (empty? args)
                         (str "[" (emit-expr (:recv node)) " " sel "]")
                         ;; str/split strips trailing empties, so the parts
                         ;; vector already lines up 1:1 with args.
                         (let [parts (str/split sel #":")]
                           (str "[" (emit-expr (:recv node)) " "
                                (str/join " "
                                          (map (fn [p a] (str p ":" (emit-expr a)))
                                               parts args))
                                "]"))))
    ;; D1(ii) 2026-09-09 — `raise T(...)` in expression position
    ;; (e.g. inside a `||:` arm body of an observation shim like
    ;; `read_text(path) :- String! ||: r == nil : raise IOError(...) ||: else : r`).
    ;; The statement handler at emit-stmt renders the same shape.
    ;; Wrapping here in a GNU stmt-expr (`({ ... ; nil; })`) lets the
    ;; block sit at expression position; @throw is control-flow
    ;; terminating, so the trailing value expression never executes.
    ;; The trailing slot uses `((__typeof__(peer))nil)` when
    ;; `:peer-typeof-expr` is present (multi-arm-dispatch lowering
    ;; attaches the peer arm's lowered expression) so ARC's
    ;; conditional-arm type-compatibility check passes for object /
    ;; primitive returns alike. Bare `nil` fallback for callers that
    ;; don't attach a peer (statement-form raise sits at emit-stmt,
    ;; not here).
    :raise-emit
    (let [box   (:box-name node)
          ctor  (:record-ctor node)
          ;; Zero-init compound literal: `(__typeof__(peer)){0}` gives
          ;; NULL for pointer types, 0 for scalars, and all-fields-zero
          ;; for structs (envelope types like `_Cxc_Optional_Int64`).
          ;; `((type)nil)` doesn't work for struct types — nil is
          ;; `(void*)0` which isn't castable to a struct in C. `0` also
          ;; loses on struct casts, so the compound-literal shape is
          ;; the only form that satisfies every peer C-type this shim
          ;; pattern produces.
          trail (if-let [peer (:peer-typeof-expr node)]
                  (str "(__typeof__(" (emit-expr peer) ")){0};")
                  "nil;")]
      (str "({\n"
           "    fprintf(stderr, \"" (escape-c-string (:label node)) "\\n\");\n"
           "    " box " *__cxc_r = [[" box " alloc] init];\n"
           "    __cxc_r->value = " (emit-expr ctor) ";\n"
           "    @throw [NSException exceptionWithName:@\"CxcReason\" "
           "reason:@\"" (escape-c-string (:label node))
           "\" userInfo:@{@\"reason\": __cxc_r}];\n"
           "    " trail "\n"
           "})"))
    (throw (ex-info (str "emit-expr: unknown node " (:node node))
                    {:node node}))))

;; ---------------------------------------------------------------------------
;; Statement emission
;; ---------------------------------------------------------------------------

(defn- emit-stmt [node]
  (case (:node node)
    :call     (str (emit-expr node) ";")
    :objc-msg (str (emit-expr node) ";")
    :ffi-call (str (emit-expr node) ";")
    ;; F39 L5b-ii-c — Optional-aware print in statement position.
    :c-print-optional (str (emit-expr node) ";")
    ;; F39 L8 — nil-receiver dispatch at statement position (the ternary
    ;; discards its value; the fallback is still evaluated but its result
    ;; is dropped — matches ObjC nil-message-return semantics).
    :c-on-nil-dispatch (str (emit-expr node) ";")
    :c-nonnil-return-guard (str (emit-expr node) ";")
    :return   (str "return " (emit-expr (:value node)) ";")
    :observable-return
    ;; obs_return trace at observable-method exit. Evaluate the body
    ;; into a temp so the trace call sees the same value as the
    ;; return. Format follows the same int/ptr/other dispatch as
    ;; field_write. Zero-cost under !CXC_TRACE_ENABLED.
    (let [ct     (:c-type node)
          nm     (:observable-name node)
          v-expr (emit-expr (:value node))
          [fmt-args]
          (cond
            (or (= ct "id") (and (string? ct) (.contains ^String ct "*")))
            [(str "\"obs=" nm ",ret=%p\", (__bridge void *)(__cxc_ov)")]
            (contains? int-c-types ct)
            [(str "\"obs=" nm ",ret=%lld\", (long long)(__cxc_ov)")]
            :else
            [(str "\"obs=" nm ",ret=?\"")])]
      (str "{ " ct " __cxc_ov = (" v-expr ");\n"
           "#ifdef CXC_TRACE_ENABLED\n"
           "  char __cxc_or[192];\n"
           "  snprintf(__cxc_or, sizeof(__cxc_or), " fmt-args ");\n"
           "  CXC_TRACE_EX(\"obs_return\", nil, self, "
           "[NSStringFromSelector(_cmd) UTF8String], __cxc_or);\n"
           "#endif\n"
           "return __cxc_ov; }"))
    :assign   (str (emit-expr (:lhs node)) " = " (emit-expr (:rhs node)) ";")
    :var-decl (str (:c-type node) " " (:name node) " = " (emit-expr (:rhs node)) ";")
    ;; A4 (2026-09-03) — `let x: T = expr or T(...): body` statement.
    ;; Emits an uninitialized decl followed by `@try/@catch` that
    ;; assigns `x` from either the successful RHS or the matching arm's
    ;; body-terminal expression. Arm dispatch mirrors
    ;; `:sync-send-with-recovery` (isKindOfClass chain, Uncaught synth,
    ;; @throw on fallthrough when no catchall). Lower has already
    ;; rewritten each arm body's terminal `:expr-stmt` as `:assign` of
    ;; the target name (see `rewrite-arm-body-last-as-assign`) so the
    ;; body stmts emit as ordinary C statements here.
    :let-with-catch
    (let [nm        (:name node)
          ct        (:c-type node)
          rhs-c     (emit-expr (:rhs node))
          arms      (:arms node)
          catchall? (some #(let [p (:pattern %)]
                             (or (nil? p) (= :bind (:kind p))))
                          arms)
          arm-blocks
          (map-indexed
           (fn [i arm]
             (let [pat  (:pattern arm)
                   body (str/join "\n" (map emit-stmt (:body arm)))
                   blk  (indent-lines 4 (if (str/blank? body) "(void)0;" body))
                   head (if (zero? i) "if " "else if ")]
               (cond
                 (or (nil? pat) (= :bind (:kind pat)))
                 (if (zero? i)
                   (str "{\n" blk "\n}")
                   (str "else {\n" blk "\n}"))

                 :else
                 (str head "([__cxc_reason isKindOfClass:["
                      (:box-name arm) " class]]) {\n"
                      blk "\n}"))))
           arms)
          chain      (str/join " " arm-blocks)
          fallthrough (if catchall? "" " else { @throw __cxc_or_e; }")]
      (str ct " " nm ";\n"
           "@try {\n"
           "    " nm " = " rhs-c ";\n"
           "} @catch (NSException *__cxc_or_e) {\n"
           "    id __cxc_reason = __cxc_or_e.userInfo[@\"reason\"];\n"
           "    if (__cxc_reason == nil) {\n"
           "        _Cxc_R__Cxc_Uncaught *__cxc_u = [[_Cxc_R__Cxc_Uncaught alloc] init];\n"
           "        __cxc_u->value = (_Cxc_Uncaught){.msg = (__cxc_or_e.reason ?: (__cxc_or_e.name ?: @\"unknown\"))};\n"
           "        __cxc_reason = __cxc_u;\n"
           "    }\n"
           (indent-lines 4 (str chain fallthrough))
           "\n}"))
    ;; F18 L2 — expression-position value wrapped as statement inside a
    ;; :c-stmt-expr (GNU `({ … })`). Rendered as `<expr>;`; when it's
    ;; the last stmt in the wrapper, its value is the block value.
    :c-expr-stmt (str (emit-expr (:expr node)) ";")

    ;; 2d-iii (2026-09-02) — `raise T(args)` statement form. Constructs
    ;; a `_Cxc_R_<T>` box, sets its `->value` to the compiled record
    ;; ctor struct-lit, then @throws NSException with
    ;; `userInfo[@"reason"]` pointing at the box. Mirrors the Track A/4
    ;; `|:` proposition-throw shape exactly (see emit.clj:597) so the
    ;; existing L12c end-catch-wrap and `sync-send-with-recovery` chain
    ;; can catch these raises with no new machinery. The label is
    ;; printed to stderr for post-mortem; @throw is control-flow
    ;; terminating so subsequent stmts in the enclosing block are dead.
    :raise-emit
    (let [box  (:box-name node)
          ctor (:record-ctor node)]
      (str "{\n"
           "    fprintf(stderr, \"" (escape-c-string (:label node)) "\\n\");\n"
           "    " box " *__cxc_r = [[" box " alloc] init];\n"
           "    __cxc_r->value = " (emit-expr ctor) ";\n"
           "    @throw [NSException exceptionWithName:@\"CxcReason\" "
           "reason:@\"" (escape-c-string (:label node))
           "\" userInfo:@{@\"reason\": __cxc_r}];\n"
           "}"))

    ;; F17 L1 — statement-position while / break / continue. Loop body
    ;; is a flat vector of already-lowered stmts; each terminates itself
    ;; with `;` via emit-stmt so we just newline-join and brace.
    :c-while
    ;; 2026-09-04 — bool nil-punning at control-flow. Bool sentinel is
    ;; -1; C's `while (-1)` would loop forever. `((cond) > 0)` maps
    ;; true(1)→1, false(0)→0, nil(-1)→0 — nil degrades to false in
    ;; boolean context. Semantic no-op for the many 0/1-producing
    ;; conds (ptr-null, int compare) already emitted here.
    ;;
    ;; Path 1 slice 5 — `:cond-envelope?` flag switches to envelope
    ;; unwrap: absent → false, present → the wrapped value. GNU
    ;; stmt-expr binds cond once so side-effecting cond fires once.
    (let [cond-expr (emit-expr (:cond node))
          body-lines (mapv emit-stmt (:body node))
          guard (if (:cond-envelope? node)
                  (str "({ __typeof__(" cond-expr ") __cxc_wg = ("
                       cond-expr "); __cxc_wg.present && __cxc_wg.value; })")
                  (str "((" cond-expr ")) > 0"))]
      (str "while (" guard ") {\n"
           (str/join "\n" (map #(str "    " %) body-lines))
           "\n}"))
    :c-break    "break;"
    :c-continue "continue;"

    ;; F69 L4 — nested scope emit `{ stmt; stmt; }`. Used by `:dispatch`
    ;; lowering to bind the scrutinee to a temporary local without leaking
    ;; the name into the enclosing scope. Flat vector of already-lowered
    ;; stmts, one per line, brace-wrapped.
    :c-scope
    (let [body-lines (mapv emit-stmt (:stmts node))]
      (str "{\n"
           (str/join "\n" (map #(str "    " %) body-lines))
           "\n}"))

    ;; F69 L4 — chained if / else-if / else. Branches whose `:cond` is
    ;; nil emit as bare `else` (the wildcard tail). Non-nil `:cond` is
    ;; an already-lowered expression. `:body` is a flat vector of stmts.
    ;; Local uses `:cond-expr` to avoid shadowing the `cond` special
    ;; form inside `render-branch`.
    :c-if-chain
    ;; 2026-09-04 — bool nil-punning at control-flow. See :c-while
    ;; comment: `((cond) > 0)` degrades sentinel-nil (-1) to false,
    ;; leaves standard 0/1-producing conds (ptr-null, int compare,
    ;; already-Bool!) unchanged in effect.
    (let [render-branch (fn [branch first?]
                          (let [cond-expr  (:cond branch)
                                body       (:body branch)
                                body-lines (mapv emit-stmt body)
                                brace-body (str "{\n"
                                                (str/join "\n"
                                                          (map #(str "    " %) body-lines))
                                                "\n}")
                                cond-raw   (when cond-expr (emit-expr cond-expr))
                                cond-c     (cond
                                             (nil? cond-expr) nil
                                             (:cond-envelope? branch)
                                             (str "({ __typeof__(" cond-raw ") __cxc_bg = ("
                                                  cond-raw "); __cxc_bg.present && __cxc_bg.value; })")
                                             :else
                                             (str "((" cond-raw ")) > 0"))]
                            (cond
                              (and first? cond-expr)
                              (str "if (" cond-c ") " brace-body)

                              cond-expr
                              (str " else if (" cond-c ") " brace-body)

                              :else
                              (str " else " brace-body))))
          branches (:branches node)]
      (apply str
             (map-indexed (fn [i b] (render-branch b (zero? i)))
                          branches)))

    :raw-c    (:text node)
    :raw-stream-subscribe
    ;; Layer 5c Step 8: method-returned Stream subscribe. Emits as a
    ;; scoped block so the temporary CxcStream reference doesn't
    ;; leak into surrounding stmt namespace. The method call returns
    ;; a CxcStream *; -cxc_addSubscriber:self hooks the enclosing
    ;; atom to the returned stream. Layer 5c lifecycle: subscriber
    ;; owns the stream via cxc_own_resource, so the stream (and its
    ;; underlying timer, wired via the stream's teardown block)
    ;; lives exactly as long as the subscribing atom. When the
    ;; subscriber's -transitionToEnd fires, cxc_owned_resources
    ;; drops the stream → ARC dealloc → cxc_end → teardown cancels
    ;; the timer. No cross-atom retain of the source (Clock) needed
    ;; — it's a factory, not a lifetime anchor.
    (let [field    (:field node)
          selector (:selector node)
          args     (:args node)
          arg-names (:arg-names node)
          method-send (if (empty? args)
                        (str "[_" field " " selector "]")
                        (let [parts (str/split selector #":")]
                          (str "[_" field " "
                               (str/join " "
                                         (map (fn [p a] (str p ":" (emit-expr a)))
                                              parts args))
                               "]")))
          _ arg-names]
      (str "{ CxcStream *__cxc_ms = " method-send "; "
           "cxc_own_resource(self, __cxc_ms); "
           "[__cxc_ms cxc_addSubscriber:self]; }"))
    :invariant-check
    ;; Direct if/(box+throw|abort) — NSAssert is compiled out by
    ;; NS_BLOCK_ASSERTIONS in release builds; invariants must always
    ;; fire.
    ;;
    ;; Track A/4 (2026-08-26) — for typed propositions
    ;; (`|: cond as T(...)`), box the constructed record into
    ;; `_Cxc_R_<T>` and `@throw` NSException with
    ;; `userInfo[@\"reason\"]` set to the box. The L12c end-catch-wrap
    ;; catches this and (Track A/5) will fan the box out through the
    ;; companion dispatch instead of the current nil-reason placeholder.
    ;;
    ;; Bare `|:` (no `as T`) still calls abort() — Track A/6 will
    ;; convert bare failures to throw an Uncaught reason so the L12c
    ;; catch reaches them too. Init-time invariants still hard-abort
    ;; even under A/4 because there's no companion catch above them in
    ;; init (the throw would escape past main); if this matters, a
    ;; follow-on can add an init-time @try around cxc_runtimeBinding.
    (str "if (!(" (emit-expr (:cond node)) ")) {\n"
         (if (and (:failure-value node) (:reason-box-name node))
           (let [box  (:reason-box-name node)
                 fail (:failure-value node)]
             (str "    fprintf(stderr, \""
                  (escape-c-string (:label node)) "\\n\");\n"
                  "    " box " *__cxc_r = [[" box " alloc] init];\n"
                  "    __cxc_r->value = " (emit-expr fail) ";\n"
                  "    @throw [NSException exceptionWithName:@\"CxcReason\" "
                  "reason:@\"" (escape-c-string (:label node))
                  "\" userInfo:@{@\"reason\": __cxc_r}];\n"))
           (str (when-let [failure-value (:failure-value node)]
                  (str "    (void)(" (emit-expr failure-value) ");\n"))
                "    fprintf(stderr, \""
                (escape-c-string (:label node)) "\\n\");\n"
                "    abort();\n"))
         "}")
    :phase-guard
    ;; Early-exit if the atom isn't in the transition's source phase.
    ;; Pointer-equality against a canonicalized `k<Atom>_phase_<name>`
    ;; constant — O(1), no string compare.
    (str "if (_phase != " (:phase-const node) ") return;")
    :phase-guard-eq
    ;; Idempotency guard — early return if already at the target phase.
    ;; Used by -transitionToEnd to make the cascade one-shot.
    (str "if (_phase == " (:phase-const node) ") " (:ret node))
    :end-cascade-child
    ;; F35 L5 (2026-08-23) — synchronous, post-order teardown per
    ;; spec §1.11 line 1283. Each child fully tears down (recursively
    ;; through its own children) before the next child begins.
    ;; Direct call under Layer 4 single-main-queue; F32 upgrades to
    ;; `cxc_wait_for_processing` (dispatch_sync onto child's queue).
    ;; Pre-L5 this was `cxc_send_async(slot, ^{ ...transitionToEnd... })`,
    ;; which returned before the child finished — programs racing on
    ;; child-finish-before-parent-continues were undefined. Nil-guard
    ;; is explicit because ObjC nil-messaging swallows the send
    ;; silently on nil, but the cast + protocol conformance check
    ;; would still touch `slot`.
    (let [slot (str "_" (:slot-name node))]
      (str "if (" slot " != nil) { [(id<CXCAtom>)" slot " transitionToEnd]; }"))
    :owned-resources-clear
    ;; Layer 5c lifecycle: at -transitionToEnd, release everything the
    ;; atom acquired via cxc_own_resource (dispatch timers, method-
    ;; returned CxcStreams, etc). removeAllObjects triggers ARC dealloc
    ;; on each entry — CxcTimerHandle cancels its source, CxcStream
    ;; routes to cxc_end (fires teardown block, drops subscribers).
    ;; Nil-guarded: atoms that never spawned anything have a nil array
    ;; and skip the message entirely (removeAllObjects on nil is a
    ;; no-op under Cocoa nil-messaging).
    "[_cxc_owned_resources removeAllObjects];"
    :end-cascade-parent
    ;; Notify parent (weak back-ref) to nil the slot holding self.
    ;; Snapshot _parent to a strong local so `weak _parent` doesn't
    ;; race between the nil-check and the message send.
    ;;
    ;; F35 L6 (2026-08-23) — direct call, was `cxc_send_async(p, ^{
    ;; [(id<CXCAtom>)p nilSlot:sn]; })`. Teardown path no longer has
    ;; any "cleanup runs later" primitive: cleanup is on the caller's
    ;; stack. Under Layer 4 parent + self both live on main, so the
    ;; direct call is safe; F32 upgrades to `cxc_wait_for_processing
    ;; (p, ^{ [(id<CXCAtom>)p nilSlot:sn]; })`. L7's spec §1.11 line
    ;; 1284 auto-nil-visible-before-next-parent-guard guarantee falls
    ;; out: the direct call completes before `-transitionToEnd`
    ;; returns, so any queued-but-not-yet-dispatched message on
    ;; parent's queue sees the nil ivar when its arm-head `|:` proposition
    ;; evaluates.
    (str "{\n"
         "    __strong id p = _parent;\n"
         "    NSString *sn = _parent_slot_name;\n"
         "    if (p != nil && sn != nil) {\n"
         "        [(id<CXCAtom>)p nilSlot:sn];\n"
         "    }\n"
         "}")
    :main-bootstrap
    ;; Construct the Main atom into the file-scope static that survives
    ;; across the autoreleasepool boundary into dispatch_main(). The
    ;; static declaration is emitted by the top-level emit function
    ;; before main(); here we just do the assignment.
    ;;
    ;; F50 — when main has ctor-params (capabilities), each is
    ;; auto-constructed via lower-atom-ctor (already lowered into
    ;; `:cap-init` ObjC expressions) and passed as `initWith<name>:...`
    ;; parts. Zero-cap-args form falls back to plain `-init`.
    (if-let [caps (seq (:cap-args node))]
      (let [first-cap  (first caps)
            first-part (str "initWith" (:name first-cap) ":"
                            (emit-expr (:cap-init first-cap)))
            rest-parts (str/join " " (map (fn [c]
                                            (str (:name c) ":"
                                                 (emit-expr (:cap-init c))))
                                          (rest caps)))]
        (str "cxc_main_atom = [[" (:class-name node) " alloc] "
             first-part
             (when-not (str/blank? rest-parts) (str " " rest-parts))
             "];"))
      (str "cxc_main_atom = [[" (:class-name node) " alloc] init];"))
    :nil-slot-branch
    ;; One arm of parent's -nilSlot: switch — sets the named slot ivar
    ;; to nil when the incoming slotName matches. isEqualToString: is
    ;; used because the caller (child) copied the slot name; not
    ;; necessarily pointer-equal to any literal here.
    (str "if ([slotName isEqualToString:@\""
         (escape-c-string (:slot-name node)) "\"]) { _"
         (:slot-name node) " = nil; return; }")
    :sync-send-checked
    ;; Q1 (2026-08-26) — bare sync `|>` capture-and-rethrow. The
    ;; sync-send helper now returns the target's re-raised NSException
    ;; (nil on success). Discarding the return would silently swallow
    ;; the failure — sender's own F48 wrap would never see it. Emit
    ;; captures the return, and if non-nil re-throws so the sender's
    ;; F48 catches (preserving pre-Q1 behavior: bare `|>` failure
    ;; kills the sender's atom too). Sync sends with `or:` arms go
    ;; through `:sync-send-with-recovery` (different emit — dispatches
    ;; arms instead of re-throwing).
    (let [send-c (emit-expr (:send-call node))]
      (str "{\n"
           "    NSException *__cxc_bs = " send-c ";\n"
           "    if (__cxc_bs) @throw __cxc_bs;\n"
           "}"))
    :sync-send-with-recovery
    ;; L9 (2026-08-26) — sync `|>` send with an `or:` recovery chain.
    ;; Wraps the send-call in an @try; the @catch runs the first
    ;; matching arm body if the target's transition threw (F48
    ;; rollback path).
    ;;
    ;; Under Layer 4 single-main-queue, `cxc_wait_for_processing` runs
    ;; the block synchronously on the caller's frame; exceptions
    ;; propagate through and land here. F32 concurrent queues will
    ;; need a dedicated helper (`cxc_wait_for_processing_or_fail`) that
    ;; captures the failure across the dispatch_sync boundary; MVP
    ;; assumes Layer 4 semantics.
    ;;
    ;; Q1 (2026-08-26) — runtime routing complete: F48 re-raises after
    ;; cleanup; `cxc_wait_for_processing` captures on the sender's
    ;; frame and returns the NSException; this emit reads the return
    ;; value directly (no outer @try — the exception never crosses
    ;; a queue boundary under F32 which would be UB).
    ;;
    ;; Track B/3 arm dispatch shape:
    ;;   1. Extract `__cxc_reason` from `e.userInfo[@"reason"]` (Track
    ;;      A/4 boxed reasons travel there).
    ;;   2. Synthesize `_Cxc_R__Cxc_Uncaught` for non-typed catches so
    ;;      `or Uncaught(msg: let m):` arms see a uniform reason object
    ;;      (mirrors L12c catch-side behavior).
    ;;   3. For each arm:
    ;;        typed  →  if ([__cxc_reason isKindOfClass:[<box-name> class]]) { <body> }
    ;;        bind   →  { <var-decl> <body> }   (catchall)
    ;;        bare   →  { <body> }              (catchall)
    ;;      Emitted as an if / else-if chain in source order.
    ;;   4. Fallthrough: `@throw;` re-raises the caught NSException so
    ;;      unmatched failures propagate up to the enclosing F48 catch.
    ;;      Skipped when the chain contains a catchall (bare / bind).
    (let [send-c    (emit-expr (:send-call node))
          arms      (:arms node)
          catchall? (some #(let [p (:pattern %)]
                             (or (nil? p) (= :bind (:kind p))))
                          arms)
          arm-blocks
          (map-indexed
           (fn [i arm]
             (let [pat  (:pattern arm)
                   body (str/join "\n" (map emit-stmt (:body arm)))
                   blk  (indent-lines 4 (if (str/blank? body) "(void)0;" body))
                   head (if (zero? i) "if " "else if ")]
               (cond
                 ;; catchall: `else { ... }` (or `{ ... }` at head)
                 (or (nil? pat) (= :bind (:kind pat)))
                 (if (zero? i)
                   (str "{\n" blk "\n}")
                   (str "else {\n" blk "\n}"))

                 ;; typed: gate by isKindOfClass
                 :else
                 (str head "([__cxc_reason isKindOfClass:["
                      (:box-name arm) " class]]) {\n"
                      blk "\n}"))))
           arms)
          chain     (str/join " " arm-blocks)
          fallthrough (if catchall? "" " else { @throw __cxc_or_e; }")]
      (str "{\n"
           "    NSException *__cxc_or_e = " send-c ";\n"
           "    if (__cxc_or_e) {\n"
           "        id __cxc_reason = __cxc_or_e.userInfo[@\"reason\"];\n"
           "        if (__cxc_reason == nil) {\n"
           "            _Cxc_R__Cxc_Uncaught *__cxc_u = [[_Cxc_R__Cxc_Uncaught alloc] init];\n"
           "            __cxc_u->value = (_Cxc_Uncaught){.msg = (__cxc_or_e.reason ?: (__cxc_or_e.name ?: @\"unknown\"))};\n"
           "            __cxc_reason = __cxc_u;\n"
           "        }\n"
           (indent-lines 8 (str chain fallthrough))
           "\n    }\n"
           "}"))
    :end-catch-wrap
    ;; BEAM-style error containment (design §20). Wraps a transition-
    ;; branch body: any exception thrown by then:/do:/invariants
    ;; routes the atom to -transitionToEnd (parent sees slot nil, can
    ;; react). Selector `_cmd` is auto-provided by ObjC — names the
    ;; failing message in the log. Atom-local failure; the process
    ;; stays alive.
    ;;
    ;; L12c (2026-08-26) — before teardown, fire the companion
    ;; `<msg>_failed(reason:)` emit method if present. Runtime uses
    ;; `respondsToSelector:` so the dispatch is silent-by-default:
    ;; atoms whose companion has no subscribers never had that emit
    ;; method synthesized (per L12b's demand-driven augmentation), so
    ;; the branch is a no-op.
    ;;
    ;; Track A/5 (2026-08-26) — the reason payload is now the boxed
    ;; reason record carried on `e.userInfo[@\"reason\"]` (Track A/4
    ;; boxes typed `|:` failures into `_Cxc_R_<T>` and throws with
    ;; userInfo). Non-`|:` exceptions (Cocoa exceptions bubbling up
    ;; from then:/do: bodies) don't carry a reason box yet — Track
    ;; A/6 will land the Uncaught prelude record and box those on
    ;; the catch side. Until then, non-typed catches pass nil, which
    ;; subscribers see as a nil arm scrutinee (id-typed dispatch is
    ;; nil-safe: every `isKindOfClass:` on nil returns NO, so wildcard
    ;; fires or the arm chain no-ops).
    (let [body (str/join "\n" (map emit-stmt (:body node)))
          block (indent-lines 4 body)]
      (str "@try {\n"
           block
           "\n} @catch (NSException *e) {\n"
           "    NSLog(@\"[cxc atom fail] %@[%p] caught %@ during -%@ (phase=%@)\", "
           "NSStringFromClass([self class]), self, e.name, "
           "NSStringFromSelector(_cmd), _phase);\n"
           "    {\n"
           "        id __cxc_reason = e.userInfo[@\"reason\"];\n"
           ;; Track A/6 (2026-08-26) — non-typed catches (bare `|:`
           ;; today still aborts, but Cocoa exceptions bubbling up
           ;; from then:/do: bodies arrive with userInfo[@"reason"]
           ;; = nil) synthesize an Uncaught reason box from
           ;; `e.reason ?: e.name` so subscribers see a uniform,
           ;; always-present object instead of nil. Companion-reason
           ;; dispatch on `<: Uncaught(msg: let m):` gets the human-
           ;; readable failure text.
           "        if (__cxc_reason == nil) {\n"
           "            _Cxc_R__Cxc_Uncaught *__cxc_u = [[_Cxc_R__Cxc_Uncaught alloc] init];\n"
           "            __cxc_u->value = (_Cxc_Uncaught){.msg = (e.reason ?: (e.name ?: @\"unknown\"))};\n"
           "            __cxc_reason = __cxc_u;\n"
           "        }\n"
           "        NSString *__cxc_sel = NSStringFromSelector(_cmd);\n"
           "        NSRange __cxc_wr = [__cxc_sel rangeOfString:@\"With\"];\n"
           "        NSString *__cxc_base = (__cxc_wr.location == NSNotFound)\n"
           "            ? __cxc_sel\n"
           "            : [__cxc_sel substringToIndex:__cxc_wr.location];\n"
           "        NSString *__cxc_cs = [NSString stringWithFormat:@\"cxc_emit_%@_failedWithreason:\", __cxc_base];\n"
           "        SEL __cxc_comp = NSSelectorFromString(__cxc_cs);\n"
           "        if ([self respondsToSelector:__cxc_comp]) {\n"
           "            ((void (*)(id, SEL, id))objc_msgSend)(self, __cxc_comp, __cxc_reason);\n"
           "        }\n"
           "    }\n"
           "    [self transitionToEnd];\n"
           ;; Q1 (2026-08-26) — re-throw after cleanup so sync-send
           ;; callers see the failure. Cleanup (log + companion emit +
           ;; transitionToEnd) already fired; the re-raise gives the
           ;; sender's `cxc_wait_for_processing` a chance to capture
           ;; and dispatch its `or:` arms. Async senders' cxc_send_async
           ;; wrapper swallows-with-log so the process doesn't crash;
           ;; sync senders without `or:` re-raise into their own F48.
           "    @throw;\n"
           "}"))
    :end-catch-log-only
    ;; Same shape but for -transitionToEnd's own body: an exception
    ;; here MUST NOT re-invoke transitionToEnd (design §20 loop guard).
    ;; The idempotency check would swallow re-entry anyway, but keeping
    ;; the catch a pure log avoids ambiguity.
    (let [body (str/join "\n" (map emit-stmt (:body node)))
          block (indent-lines 4 body)]
      (str "@try {\n"
           block
           "\n} @catch (NSException *e) {\n"
           "    NSLog(@\"[cxc atom fail during -> end] %@[%p] caught %@ during -%@\", "
           "NSStringFromClass([self class]), self, e.name, "
           "NSStringFromSelector(_cmd));\n"
           "}"))
    :transition-branch
    ;; One arm of a first-match dispatch for a message-selector method.
    ;; Guard = `_phase == kA_phase_source` && arm-head `|:` propositions
    ;; (already AND-folded by lower). Body runs then:/phase-update/do:,
    ;; then early-returns so subsequent branches don't fire.
    ;;
    ;; L10.b (2026-08-25) — when `:source-phase-const` is nil, this is
    ;; a wildcard-source arm (`* <: msg -> *` reclassified because a
    ;; concrete arm on the same message exists). Emit without the
    ;; phase-eq guard; the arm fires in any phase not caught by an
    ;; earlier (concrete) branch. Branches are ordered concrete-first
    ;; per L10.b sort, so first-match dispatch honors the ratified
    ;; concrete-over-* precedence.
    (let [phase-const (:source-phase-const node)
          phase-eq    (when phase-const (str "_phase == " phase-const))
          guard       (cond
                        (and phase-eq (:guard node))
                        (str "(" phase-eq ") && (" (emit-expr (:guard node)) ")")
                        phase-eq                phase-eq
                        (:guard node)           (emit-expr (:guard node))
                        :else                   "1")
          body        (str/join "\n" (map emit-stmt (:body node)))
          arm-idx     (or (:arm-index node) 0)
          src-line    (:src-line node)
          extra       (str "arm=" arm-idx
                           (when src-line (str ",line=" src-line)))
          trace-line  (str "CXC_TRACE_EX(\"arm\", nil, self, "
                           "[NSStringFromSelector(_cmd) UTF8String], \""
                           extra "\");\n")
          ;; Evaluate guard into a temp so trace + `if` share one
          ;; evaluation and short-circuit ordering across arms is
          ;; preserved. Guard exprs are pure per spec, so the temp
          ;; is behavior-equivalent. Trace fires unconditionally
          ;; (regardless of result) so we can tell "guard evaluated
          ;; to false" from "an earlier arm returned first" in the
          ;; log.
          guard-eval-trace
          (str "CXC_TRACE_EX(\"guard_eval\", nil, self, "
               "[NSStringFromSelector(_cmd) UTF8String], "
               "__cxc_g ? \"" extra ",result=1\" : \""
               extra ",result=0\");")]
      (str "{ BOOL __cxc_g = (" guard ");\n"
           "#ifdef CXC_TRACE_ENABLED\n"
           "  " guard-eval-trace "\n"
           "#endif\n"
           "if (__cxc_g) {\n" (indent-lines 4 (if (str/blank? body)
                                                (str trace-line "return;")
                                                (str trace-line body "\nreturn;")))
           "\n} }"))
    :coverage-abort
    ;; L13 (2026-08-25) — runtime fallback for the transition-arm
    ;; coverage requirement (Option B). Fires when the receiver's
    ;; phase matches this dispatch group but no arm's `|:` guard held.
    ;; Diagnostic names the message + phase so ops can locate the
    ;; missing arm without instrumentation.
    (let [sel   (:msg-selector node)
          phase (:phase-const node)]
      (str "CXC_TRACE_EX(\"no_arm\", nil, self, \"" (escape-c-string sel)
           "\", \"phase=" (escape-c-string phase) "\");\n"
           "fprintf(stderr, \"cxc coverage: no arm's `|:` held for `"
           (escape-c-string sel) "` in phase " (escape-c-string phase)
           " on %s\\n\", "
           "[NSStringFromClass([self class]) UTF8String]);\n"
           "abort();"))
    :state-snapshot-init
    ;; F33 L2.b — atomic snapshot of `data:` fields at method entry.
    ;; Emits `<Atom>_State __cxc_snap = { ._f1 = _f1, ._f2 = _f2, ... };`.
    ;; L2.c toggles the `:ident` sf-branch to read through `__cxc_snap._<f>`
    ;; via a ctx `:snapshot-name` entry.
    ;;
    ;; F32 L4.e.α (2026-08-24) — under `!CXC_SINGLE_MAIN_QUEUE` the
    ;; snapshot sources from the committed `_state` slot instead of
    ;; ivars. `_cxc_atom_state_ptr` is nil-safe (returns NULL pre-
    ;; first-commit); the ternary falls back to a zero-init struct so
    ;; the first observation on a not-yet-seeded atom sees a well-
    ;; defined snapshot rather than dereferencing NULL. In practice
    ;; L4.d.β.α's initial-state-commit seed guarantees `_state` is
    ;; populated by the end of `cxc_stateInit`, so the NULL fallback
    ;; is defense-in-depth. Under the default flag the ivar-sourced
    ;; init stays (byte-identical). Substrate for L4.e.β retiring
    ;; per-field ivar writes at `:commit-next-state` (self-reads via
    ;; snapshot no longer depend on ivars).
    (let [cn     (:class-name node)
          fields (:data-fields node)
          inits  (->> fields
                      (map (fn [f]
                             (str "._" (:name f) " = _" (:name f))))
                      (str/join ", "))
          ;; state_snap trace, gated at runtime by cxc_trace_full (set
          ;; from CXC_TRACE_LEVEL=full env var) so default trace stays
          ;; lean. Per-field format follows the int/ptr dispatch used
          ;; by field_write / obs_return; unknown types render as `?`.
          per-field (->> fields
                         (map (fn [f]
                                (let [lv (str "__cxc_snap._" (:name f))
                                      [frag arg-vec] (fmt-value-expr (:c-type f) lv)]
                                  {:pair (str (:name f) "=" frag)
                                   :args arg-vec})))
                         (into []))
          fmt-str (str/join "," (map :pair per-field))
          args    (->> per-field (mapcat :args) (str/join ", "))
          snap-trace
          (str "#ifdef CXC_TRACE_ENABLED\n"
               "cxc_trace_init();\n"
               "if (cxc_trace_full) {\n"
               "    char __cxc_sn[1024];\n"
               "    snprintf(__cxc_sn, sizeof(__cxc_sn), \"" fmt-str "\""
               (when (seq args) (str ", " args))
               ");\n"
               "    CXC_TRACE_EX(\"state_snap\", nil, self, "
               "[NSStringFromSelector(_cmd) UTF8String], __cxc_sn);\n"
               "}\n"
               "#endif")]
      (str "#if !CXC_SINGLE_MAIN_QUEUE\n"
           cn "_State *__cxc_snap_src = ("
           cn "_State *)_cxc_atom_state_ptr(&_state);\n"
           cn "_State __cxc_snap = __cxc_snap_src ? *__cxc_snap_src : ("
           cn "_State){0};\n"
           "#else\n"
           cn "_State __cxc_snap = { " inits " };\n"
           "#endif\n"
           snap-trace))
    :next-state-init
    ;; F33 L3.a — next-state bag init as copy of the entry snapshot.
    ;; Emits `<Atom>_State __cxc_next = __cxc_snap;`. L3.b redirects
    ;; `then:` writes to `__cxc_next._<f>` and inserts a commit
    ;; sequence; L3.c reads invariants against the bag pre-commit.
    (let [cn (:class-name node)]
      (str cn "_State __cxc_next = __cxc_snap;"))
    :initial-state-commit
    ;; F32 L4.d.β.α (2026-08-24) — post-init seed of the concurrent-
    ;; mode `_state` slot from the freshly-populated ivars. Runs at
    ;; the end of `cxc_stateInit` after all defaults + on-enter
    ;; effects have landed on the raw ivars. Without this seed, the
    ;; first cross-atom reader under concurrent mode would see a nil
    ;; `_state` (never committed) and `_cxc_atom_state_ptr` would
    ;; return NULL — L4.d.β's guarded read path needs a populated
    ;; slot to be safe on the first observation. Redundant when a
    ;; transition fires during init (L4.c's dual-write already
    ;; committed `_state`); the reseed is idempotent and only
    ;; allocates one extra wrapper. Under the default preprocessor
    ;; flag the whole block guards out; zero cost.
    (let [cls-name (or (:class-name node) (:atom-name node))
          fields    (:data-fields node)
          inits     (->> fields
                         (map (fn [f]
                                (str "._" (:name f) " = _" (:name f))))
                         (str/join ", "))]
      (str "#if !CXC_SINGLE_MAIN_QUEUE\n"
           "{\n"
           "    " cls-name "_State __cxc_init = { " inits " };\n"
           "    _cxc_state_commit(&_state, &__cxc_init, sizeof("
           cls-name "_State));\n"
           "}\n"
           "#endif"))
    :commit-next-state
    ;; F33 L3.b — atomic commit of the next-state bag.
    ;;
    ;; F32 L4.e.β (2026-08-24) — terminal shape. Per-field ivar
    ;; writes (`_<f> = __cxc_next._<f>;`) and the state-slot commit
    ;; (`_cxc_state_commit(&_state, &__cxc_next, ...)`) are now
    ;; mutually-exclusive branches on `CXC_SINGLE_MAIN_QUEUE`.
    ;; Under the default preprocessor flag (`CXC_SINGLE_MAIN_QUEUE=1`)
    ;; only the per-field ivar writes fire — behavior is byte-
    ;; identical to F33 L3.b modulo the `#if …#endif` markers.
    ;; Under concurrent mode (`CXC_SINGLE_MAIN_QUEUE=0`) only the
    ;; state-commit fires; the ivars go stale but no reader touches
    ;; them (L4.d.β.β retired cross-atom ivar reads; L4.e.β.1/.2.α/.3
    ;; retired self, observable, and -checkInvariants ivar reads —
    ;; all now route via `_state` snapshots). Slot-setter fields
    ;; (subscription lifecycle) are excluded upstream by the lower
    ;; site's `field-needs-slot-setter?` filter — their writes go
    ;; through the setter directly and don't participate in the
    ;; state-bag commit.
    (let [fields (:data-fields node)
          ;; F32 L4.c originally read :atom-name here; that emits
          ;; `sizeof(main_State)` for the main atom's transitions —
          ;; wrong, since main's state struct is `CxcMain_State`.
          ;; Prefer :class-name (added by the lower site alongside
          ;; :atom-name), fall back to :atom-name for programs whose
          ;; transitions still surface only the atom name (all
          ;; non-main atoms have class-name == atom-name).
          cls-name  (or (:class-name node) (:atom-name node))
          ivar-commits (->> fields
                            (map (fn [f]
                                   (str (field-write-trace f)
                                        "_" (:name f) " = __cxc_next._" (:name f) ";")))
                            (str/join "\n"))]
      (str "#if CXC_SINGLE_MAIN_QUEUE\n"
           ivar-commits "\n"
           "#else\n"
           "_cxc_state_commit(&_state, &__cxc_next, sizeof("
           cls-name "_State));\n"
           "#endif"))
    :queue-init
    ;; F32 L1 (2026-08-24) — per-atom serial queue. Target selection is
    ;; preprocessor-guarded on CXC_SINGLE_MAIN_QUEUE (default 1, defined
    ;; in cxc-runtime-helpers). Under single-queue mode every atom's
    ;; queue targets main — Layer 4 behavior, deterministic single-
    ;; threaded execution. Under concurrent mode the CxcMain atom stays
    ;; main-targeted (dispatch_main() drives its blocks) while every
    ;; other atom targets the global concurrent pool at QOS_CLASS_DEFAULT.
    ;; Uses NSStringFromClass rather than referencing CxcMain directly
    ;; because CxcMain is only emitted when the program declares a main
    ;; atom; string compare is safe for programs that don't. The queue
    ;; itself is always DISPATCH_QUEUE_SERIAL — target changes affect
    ;; *where* blocks run, not whether they serialize (spec §1.11
    ;; per-atom serial invariant).
    (str "{\n"
         "    NSString *cxc_qlabel = [NSString stringWithFormat:@\"cxc.%@.%p\","
         " NSStringFromClass([self class]), self];\n"
         "    " (:ivar node)
         " = dispatch_queue_create([cxc_qlabel UTF8String], DISPATCH_QUEUE_SERIAL);\n"
         "#if CXC_SINGLE_MAIN_QUEUE\n"
         "    dispatch_set_target_queue(" (:ivar node)
         ", dispatch_get_main_queue());\n"
         "#else\n"
         "    dispatch_queue_t __cxc_qtarget = [NSStringFromClass([self class]) isEqualToString:@\"CxcMain\"]\n"
         "        ? dispatch_get_main_queue()\n"
         "        : dispatch_get_global_queue(QOS_CLASS_DEFAULT, 0);\n"
         "    dispatch_set_target_queue(" (:ivar node)
         ", __cxc_qtarget);\n"
         "#endif\n"
         "}")
    :mailbox-sem-init
    ;; Q2 (2026-08-26) — bounded(N) mailbox semaphore. Allocated once
    ;; per atom instance at capacity N in structuralSetup; consumed by
    ;; the bounded runtime helpers (cxc_send_async_bounded /
    ;; cxc_wait_for_processing_bounded). Only emitted when the def-atom
    ;; carries `:queue-config {:kind :bounded :capacity N}`; unbounded
    ;; atoms never allocate one.
    (str (:ivar node) " = dispatch_semaphore_create("
         (:capacity node) ");")
    :hashtable-init
    ;; NSHashTable with weak-object membership: subscribers can dealloc
    ;; freely, entries auto-disappear (design §18). Zero-cost when no
    ;; subscribers ever register.
    (str (:ivar node) " = [NSHashTable weakObjectsHashTable];")
    :stream-fanout
    ;; Emit-method body for a compiler-synthesized stream. Iterates the
    ;; subscriber hashtable and cxc_send_async's each with a block that
    ;; dispatches the compile-time-known subscriber-side selector
    ;; (`cxc_stream_<name>_from_<Source>[With<k>:]`). Sub is captured by
    ;; block per-iteration under ARC (retained for block lifetime, released
    ;; after execution — safe against sub dealloc between fanout + fire).
    ;; Payload args are captured by value into the block (primitives copy,
    ;; records copy, atom pointers strong-retain under ARC).
    (let [ivar (:ivar node)
          sel  (:selector node)
          keys (:arg-names node)
          ;; Layer 5b Step 6b: the fanout site injects the SOURCE as
          ;; the value for the leading `sender:` arg. Arg-name "sender"
          ;; is a marker; at emit we substitute `self` (the source atom
          ;; itself). Every other key names a param of the enclosing
          ;; emit-method and carries through as a plain identifier.
          arg-value (fn [k] (if (= "sender" k) "self" k))
          send-expr (if (empty? keys)
                      (str "[sub " sel "]")
                      (let [parts (str/split sel #":")]
                        (str "[sub "
                             (str/join " "
                                       (map (fn [p k] (str p ":" (arg-value k)))
                                            parts keys))
                             "]")))]
      (str "for (id sub in " ivar ") {\n"
           "    cxc_send_async(sub, \"" (escape-c-string sel) "\", ^{ " send-expr "; });\n"
           "}"))
    :stream-add-sub
    ;; Per-stream `-cxc_addSubscriberFor_<name>:` body. Direct
    ;; addObject under Layer 4 single-thread — all init runs on main
    ;; before dispatch_main() starts, so no concurrent mutation is
    ;; possible. The earlier design analysis suggested wrapping in
    ;; dispatch_sync(_own_queue, ^{...}) for Layer 4c multi-thread
    ;; future-proofing, but under Layer 4's queues-target-main model
    ;; that's a deadlock (main thread → dispatch_sync → queue targeting
    ;; main → waits for main thread). Layer 4c will re-add the sync
    ;; wrap once queues target concurrent globals.
    (str "[" (:ivar node) " addObject:sub];")
    :stream-remove-sub
    ;; Mirror of :stream-add-sub for slot-setter unsubscribe path
    ;; (Layer 4b nullable-slot dynamic subscription). NSHashTable's
    ;; removeObject: no-ops when the object isn't present, which
    ;; happens naturally at first assignment (nothing to remove yet).
    (str "[" (:ivar node) " removeObject:sub];")
    :slot-setter-body
    ;; Layer 4b slot setter for a nullable atom field that is a
    ;; subscription root. Sequence: early-return on identity match,
    ;; unsubscribe self from each stream on the OLD value (nil-message
    ;; -safe), assign the ivar, subscribe self to each stream on the
    ;; NEW value (also nil-message-safe). No explicit nil check needed
    ;; because Cocoa nil-messaging returns 0/nil.
    (let [ivar    (:ivar node)
          streams (:streams node)
          unsub-lines (->> streams
                           (map (fn [s]
                                  (str "[" ivar " cxc_removeSubscriberFor_" s ":self];")))
                           (str/join "\n    "))
          sub-lines   (->> streams
                           (map (fn [s]
                                  (str "[newValue cxc_addSubscriberFor_" s ":self];")))
                           (str/join "\n    "))]
      (str "if (" ivar " == newValue) return;\n"
           "    " unsub-lines "\n"
           "    " ivar " = newValue;\n"
           "    " sub-lines))
    :stream-add-sub-dispatch
    ;; Generic `-cxc_addSubscriberToStream:sub:` body. If-chain over the
    ;; source atom's declared streams; unknown names log for debug
    ;; visibility rather than silently drop. Matches design §18's
    ;; serialization/restore path (subscriber-atom-ID + stream-name +
    ;; selector-name tuples on restore).
    (let [branches
          (->> (:branches node)
               (map (fn [b]
                      (str "if ([streamName isEqualToString:@\""
                           (escape-c-string (:stream-name b))
                           "\"]) { [self " (:selector b) "sub]; return; }")))
               (str/join "\n"))]
      (str branches
           "\nNSLog(@\"cxc: unknown stream `%@` on %@\", "
           "streamName, [self class]);"))
    (throw (ex-info (str "emit-stmt: unknown node " (:node node))
                    {:node node}))))

(defn- indent-lines [n s]
  (let [pad (str/join (repeat n " "))]
    (->> (str/split-lines s)
         (map #(str pad %))
         (str/join "\n"))))

(defn- emit-body [n-spaces stmts]
  (indent-lines n-spaces (str/join "\n" (map emit-stmt stmts))))

;; ---------------------------------------------------------------------------
;; C struct emission (records → value-typed structs, no methods yet)
;; ---------------------------------------------------------------------------

(defn- emit-reason-box
  "Track A/2.a (2026-08-26) — one reason-box wrapper class per user
   record. Layout: `@interface _Cxc_R_<T> : NSObject { @public <T> value; }
   @end / @implementation _Cxc_R_<T> @end`. The `@public` ivar exposes
   the wrapped struct so receiver-side `<:` dispatch can destructure
   fields via `((_Cxc_R_<T> *)tmp)->value.<field>` without needing a
   custom accessor per field.

   The wrapper carries no methods and no init customization; alloc/init
   yields a zero-initialized `value`, which callers overwrite before
   throwing. Emitted after `:structs` so the wrapped `<T>` struct type
   is in scope."
  [rb]
  (str "@interface " (:name rb) " : NSObject { @public "
       (:struct-c-type rb) " value; } @end\n"
       "@implementation " (:name rb) " @end"))

(defn- emit-struct [s]
  ;; F37 L6 (2026-08-27) — a `:raw-c-decl` shape (used for the per-record
  ;; `_Cxc_DecodeOutcome_<R>` synthesis) carries the full typedef text
  ;; in `:text` so nested-struct / union / #define shapes we don't
  ;; generalize yet emit verbatim. Regular `:c-struct` fields still
  ;; render via the field-list path unchanged.
  (if (= :raw-c-decl (:node s))
    (:text s)
    (let [decls (->> (:fields s)
                     (map (fn [f] (str "    " (:c-type f) " " (:name f) ";")))
                     (str/join "\n"))]
      (str "typedef struct {\n" decls "\n} " (:name s) ";"))))

(defn- emit-c-enum
  "Value-only enum → C typedef enum. Pre-F16: bare `typedef enum { E_a,
   E_b } E;` with declaration-order integer tags. F16 L2: raw-value
   enums render `typedef enum : <raw-c-type> { E_a = <v>, E_b = <v> } E;`
   using the clang typed-enum extension. Underlying type is the
   smallest fitting unsigned integer from the parsed raw values."
  [e]
  (let [raw-vals   (:raw-values e)
        raw-c-type (:raw-c-type e)
        members    (if raw-vals
                     (->> (map vector (:cases e) raw-vals)
                          (map (fn [[c v]] (str "    " (:name e) "_" c " = " v)))
                          (str/join ",\n"))
                     (->> (:cases e)
                          (map (fn [c] (str "    " (:name e) "_" c)))
                          (str/join ",\n")))
        header     (if raw-vals
                     (str "typedef enum : " raw-c-type " {\n")
                     "typedef enum {\n")]
    (str header members "\n} " (:name e) ";")))

(defn- emit-c-tagged-enum
  "F16 L1 — tagged-union representation for an enum with payload cases.

   Emits three coupled declarations:
     1. `<Enum>_Tag` enum for the discriminator (one member per case
        named `<Enum>_Tag_<case>`; index-order tag values match the
        pre-F16 `<Enum>_<case>` scheme so cross-enum diagnostics stay
        stable).
     2. Per payload-carrying case, an inner struct
        `<Enum>_Payload_<case>` naming the payload fields. Named-only
        per §3.7.1 — the field names come from the parsed payload
        param list.
     3. The outer `<Enum>` struct: tag field + anonymous union
        containing one member per case (bare cases contribute nothing;
        payload cases contribute their `<Enum>_Payload_<case>` inner
        struct at member name `<case>`).

   Empty-union-member sanity: an enum with only bare cases wouldn't
   reach this path — `lower-enum` picks the plain `:c-enum` rep for
   value-only enums. When a mixed enum has at least one payload case,
   bare cases occupy the union without members (no data to store);
   they exist only as tag values."
  [e]
  (let [ename    (:name e)
        entries  (:entries e)
        tag-name (str ename "_Tag")
        tag-members (->> entries
                         (map (fn [ce] (str "    " tag-name "_" (:name ce))))
                         (str/join ",\n"))
        tag-typedef (str "typedef enum {\n" tag-members "\n} " tag-name ";")
        ;; A2 (2026-09-03) — ARC forbids `__strong` ObjC references
        ;; inside union members because the runtime can't decide which
        ;; tag's payload to retain/release when the outer struct is
        ;; copy-initialized. Reference-typed payload fields
        ;; (`NSString *`, `id`, `<Class> *`, `<Class> **`, etc.) get
        ;; `__unsafe_unretained` so ARC skips lifetime management on
        ;; those slots. MVP lifetime model: payloads must be backed by
        ;; a strong reference in the enclosing scope (string literals
        ;; are image-lifetime; other refs must be held elsewhere).
        objc-ref-c-type?
        (fn [ct]
          (or (= "id" ct)
              (and (string? ct) (str/includes? ct "*"))))
        annotate-c-type
        (fn [ct]
          (if (objc-ref-c-type? ct)
            (str "__unsafe_unretained " ct)
            ct))
        inner-structs
        (->> entries
             (filter #(seq (:payload %)))
             (map (fn [ce]
                    (let [fields (->> (:payload ce)
                                      (map (fn [f]
                                             (str "    " (annotate-c-type (:c-type f)) " "
                                                  (:name f) ";")))
                                      (str/join "\n"))]
                      (str "typedef struct {\n" fields "\n} "
                           ename "_Payload_" (:name ce) ";"))))
             (str/join "\n\n"))
        union-members
        (->> entries
             (filter #(seq (:payload %)))
             (map (fn [ce]
                    (str "        " ename "_Payload_" (:name ce)
                         " " (:name ce) ";")))
             (str/join "\n"))
        outer (str "typedef struct {\n"
                   "    " tag-name " tag;\n"
                   "    union {\n" union-members "\n    } data;\n"
                   "} " ename ";")]
    (str/join "\n\n"
              (remove str/blank? [tag-typedef inner-structs outer]))))

(defn- emit-any-enum
  "Dispatch on the enum node kind (value-only `:c-enum` vs tagged
   `:c-tagged-enum`, F16 L1)."
  [e]
  (case (:node e)
    :c-enum        (emit-c-enum e)
    :c-tagged-enum (emit-c-tagged-enum e)))

;; ---------------------------------------------------------------------------
;; C function emission (record methods → free functions)
;; ---------------------------------------------------------------------------

(defn- emit-param [p]
  (str (:c-type p) " " (:name p)))

(defn- emit-function-prototype
  "Emit a C prototype `<ret> <name>(<params>);` — no body. Used to
   forward-declare synthesized functions so nested-record encoding
   in F37 Landing 4 (and any future mutually-recursive synthesized
   fns) can dispatch across declaration order."
  [f]
  (let [params (str/join ", " (map emit-param (:params f)))]
    (str (:return-type f) " " (:name f) "(" params ");")))

(defn- emit-function [f]
  (let [params (str/join ", " (map emit-param (:params f)))
        body   (emit-body 4 (:body f))]
    (str (:return-type f) " " (:name f) "(" params ") {\n"
         body
         "\n}")))

;; ---------------------------------------------------------------------------
;; ObjC class emission — @interface + @implementation for a def-atom
;; ---------------------------------------------------------------------------

(defn- prop-attrs-string [attrs]
  (if (seq attrs)
    (str "(" (str/join ", " attrs) ")")
    ""))

(defn- emit-property [p]
  (let [attrs (prop-attrs-string (:attrs p))
        space (if (str/blank? attrs) "" " ")
        c-type (:c-type p)
        nm     (:name p)]
    (if (and (string? c-type) (.contains ^String c-type "(^)"))
      ;; Layer 6 Step 12 — ObjC block-typed property. Name goes INSIDE
      ;; the `^()`: `void (^cb)(void)`, not `void (^)(void) cb`. Same
      ;; goes for the ivar signature generated by @synthesize.
      (str "@property " attrs space
           (.replace ^String c-type "(^)" (str "(^" nm ")"))
           ";")
      (str "@property " attrs space c-type " " nm ";"))))

(defn- method-signature
  "ObjC instance-method signature. Zero-arg → `- (T)selector`; with args
   → `- (T)part1:(T1)name1 part2:(T2)name2`. Uses the selector-with-
   colons convention from cxc.lower/objc-method-selector."
  [m]
  (let [sel (:selector m)
        ps  (:params m)]
    (if (empty? ps)
      (str "- (" (:return-type m) ")" sel)
      (let [parts (str/split sel #":")             ; strips trailing empties
            body  (str/join " "
                            (map (fn [part p]
                                   (str part ":(" (:c-type p) ")" (:name p)))
                                 parts ps))]
        (str "- (" (:return-type m) ")" body)))))

(declare init-signature)

(defn- emit-interface [cls]
  (let [props     (->> (:properties cls)
                       (map emit-property)
                       (map #(str "  " %))
                       (str/join "\n"))
        ;; Custom -initWith... needs an @interface declaration too, else
        ;; call sites using the class through a bare `id` can't type-check
        ;; the selector. Bare `-init` inherits from NSObject and needs no
        ;; extra declaration; only emit when init-params are non-empty.
        init-decl (when (seq (:init-params cls))
                    (str "  " (init-signature (:init-params cls)) ";"))
        method-decls (->> (:methods cls)
                          (map #(str "  " (method-signature %) ";"))
                          (str/join "\n"))
        body-parts (remove str/blank? [props init-decl method-decls])
        body       (if (empty? body-parts) "" (str "\n" (str/join "\n" body-parts) "\n"))
        ;; F32 L4.b.β (2026-08-24) — per-atom `_Atomic id _state` ivar
        ;; declared under `!CXC_SINGLE_MAIN_QUEUE` so L4.c can wire
        ;; commits and L4.d can wire cross-atom reads without a
        ;; separate flip on the class shape. Ivars block is emitted
        ;; between the superclass and the @property/method
        ;; declarations (canonical ObjC placement). Under the default
        ;; preprocessor flag the whole block guards out; classes
        ;; declare zero extra ivars and existing emit is byte-
        ;; identical.
        ivars-block "\n{\n#if !CXC_SINGLE_MAIN_QUEUE\n    _Atomic id _state;\n#endif\n}"]
    (str "@interface " (:name cls) " : " (:superclass cls) ivars-block body "@end")))

(defn- init-signature
  "Build the ObjC init method signature. No params → `- (instancetype)init`.
   With params → `- (instancetype)initWith<first>:(<type>)<name> <name>:(<type>)<name> ...`.
   The `initWith` prefix + uppercase W satisfies ARC's init-family rule."
  [init-params]
  (if (empty? init-params)
    "- (instancetype)init"
    (let [first-p     (first init-params)
          rest-ps     (rest init-params)
          first-part  (str "initWith" (:name first-p) ":("
                           (:c-type first-p) ")" (:name first-p))
          rest-parts  (str/join " "
                                (map (fn [p]
                                       (str (:name p) ":(" (:c-type p) ")" (:name p)))
                                     rest-ps))]
      (str "- (instancetype)" first-part
           (when-not (str/blank? rest-parts) (str " " rest-parts))))))

(defn- emit-method-impl [m]
  (let [prelude (:prelude m)
        ;; Assign :arm-index by position within the dispatch group so
        ;; two arms on the same selector (guarded + fallthrough) can be
        ;; told apart in the trace. Non-branch statements pass through
        ;; unchanged; arm-index only lands on :transition-branch nodes.
        body    (loop [acc [] i 0 xs (:body m)]
                  (if (empty? xs)
                    acc
                    (let [x (first xs)]
                      (if (= :transition-branch (:node x))
                        (recur (conj acc (assoc x :arm-index i)) (inc i) (rest xs))
                        (recur (conj acc x) i (rest xs))))))
        ;; When the body dispatches via transition-branches, emit a
        ;; tail `CXC_TRACE_EX("no_arm", ...)` before the implicit
        ;; return so the trace records phase-mismatched or
        ;; guard-missed messages that would otherwise silently drop
        ;; (methods without `:coverage-abort` return silently when no
        ;; arm's `if(guard){...}` matches). Zero cost under !CXC_TRACE_ENABLED.
        dispatcher?  (some #(= :transition-branch (:node %)) body)
        tail-trace   (when dispatcher?
                       "    CXC_TRACE_EX(\"no_arm\", nil, self, [NSStringFromSelector(_cmd) UTF8String], \"tail\");")
        parts   (cond-> []
                  (seq prelude) (conj (emit-body 4 prelude))
                  (seq body)    (conj (emit-body 4 body))
                  tail-trace    (conj tail-trace))]
    (str (method-signature m) " {\n"
         (str/join "\n" parts)
         "\n}")))

(defn- emit-implementation [cls]
  (let [init-body     (:init-body cls)
        init-params   (:init-params cls)
        init-stmts    (if (seq init-body)
                        (str "\n" (emit-body 8 init-body))
                        "")
        atom-init-trace (str "\n        CXC_TRACE(\"atom_init\", nil, self, "
                             "\"" (:name cls) "\");")
        init-block    (str (init-signature init-params) " {\n"
                           "    self = [super init];\n"
                           "    if (self) {"
                           atom-init-trace
                           init-stmts
                           "\n    }\n"
                           "    return self;\n"
                           "}")
        method-impls  (str/join "\n\n" (map emit-method-impl (:methods cls)))
        body-blocks   (remove str/blank? [init-block method-impls])]
    (str "@implementation " (:name cls) "\n"
         (str/join "\n\n" body-blocks)
         "\n@end")))

(defn- emit-phase-constants
  "File-scope `static NSString * const k<Atom>_phase_<name> = @\"<name>\";`
   lines, one per phase. Canonicalized: two reads of the same constant
   compare pointer-equal, so `_phase == kFoo_phase_alive` is O(1) with
   no `isEqualToString:` dispatch."
  [cls]
  (when (seq (:phase-constants cls))
    (->> (:phase-constants cls)
         (map (fn [pc]
                (str "static NSString * const " (:name pc)
                     " = @\"" (escape-c-string (:value pc)) "\";")))
         (str/join "\n"))))

(defn- state-struct-field-line
  "F33 L2.a — one field's declaration inside `<Atom>_State`. Block-typed
   fields (`void (^)(void)`) need the field name spliced inside the
   `(^…)` — mirrors `emit-property`'s ObjC-block handling."
  [f]
  (let [c-type (:c-type f)
        nm     (:name f)]
    (if (and (string? c-type) (.contains ^String c-type "(^)"))
      (str "    " (.replace ^String c-type "(^)" (str "(^_" nm ")")) ";")
      (str "    " c-type " _" nm ";"))))

(defn- emit-state-struct
  "F33 L2.a — companion `typedef struct <Atom>_State { <c-type> _<name>; … } <Atom>_State;`
   for each atom's user `data:` fields. Reads/writes still target live
   ivars at L2.a; the type exists but nothing uses it yet — subsequent
   sub-landings (L2.b snapshot init, L2.c reads reroute) build on it.
   Emitted alongside phase constants + @interface at the top of the file.
   Returns nil when the atom has no data-fields — the trivial atoms
   (e.g. `def main` when it declares no fields) skip the emission."
  [cls]
  (when (seq (:data-fields cls))
    (let [fields (:data-fields cls)
          lines  (->> fields (map state-struct-field-line) (str/join "\n"))
          nm     (:name cls)]
      (str "typedef struct {\n" lines "\n} " nm "_State;"))))

(defn- emit-class-interface-block
  "State-struct typedef + phase constants + @interface for one class.
   Emitted in the interface pass at the top of the file so every
   class's selectors are visible before any @implementation body runs —
   matters for stream-fanout sites that message `id`-typed subscribers
   with the receiver-side `cxc_stream_<name>_from_<Source>` selector,
   which needs a visible declaration to pick a type signature.

   F33 L2.a (2026-08-23) — the `<Atom>_State` typedef precedes the
   phase constants + @interface. Dead code at L2.a; wiring lands in
   L2.b onward."
  [cls]
  (let [state-struct (emit-state-struct cls)
        phase-consts (emit-phase-constants cls)
        parts        (remove str/blank? [state-struct
                                         phase-consts
                                         (emit-interface cls)])]
    (str/join "\n\n" parts)))

(defn- emit-class [cls]
  (emit-implementation cls))

;; ---------------------------------------------------------------------------
;; Program emission
;; ---------------------------------------------------------------------------

(defn- emit-main
  "C main() function. Three shapes:
     - `:dispatch-main` with no async sends: run body inside
       @autoreleasepool, return 0. Fits programs with no message-
       triggered transitions (their work runs synchronously during
       init and completes before return).
     - `:dispatch-main` with async sends: run bootstrap (Main atom
       construction) inside @autoreleasepool, then call
       dispatch_main() which never returns. cxc_shutdown() from a
       `-> end` transition on the main atom is the termination path
       (design §19).
     - `:main-runloop`: run bootstrap inside @autoreleasepool, then
       call `[NSApp run]` which never returns. AppKit owns the main
       thread's CFRunLoop; atoms use dispatch_async on main to touch
       AppKit. `[NSApp terminate:]` from event handlers or window
       delegate is the termination path. Prototype A of the runtime-
       shape landing (docs/design/runtime-shape.md, 2026-09-26)."
  [main-node needs-dispatch-loop? runtime-shape]
  (let [body (emit-body 4 (:body main-node))
        dispatch-suffix (case runtime-shape
                          :main-runloop
                          (str "\n    [NSApplication sharedApplication];"
                               "\n    [NSApp run];"
                               "\n    return 0;   /* unreachable */")
                          ;; :dispatch-main (default)
                          (if needs-dispatch-loop?
                            "\n    dispatch_main();\n    return 0;   /* unreachable */"
                            ""))]
    (if (:autorelease? main-node)
      (str "int main(int argc, char *argv[]) {\n"
           "    @autoreleasepool {\n"
           (indent-lines 4 body)
           "\n    }"
           dispatch-suffix
           "\n}")
      (str "int main(int argc, char *argv[]) {\n"
           body
           dispatch-suffix
           "\n}"))))

;; ---------------------------------------------------------------------------
;; Track C/3 (2026-08-26) — runtime audit against ratified transport
;; defaults (spec §1.11 "Transport defaults (endpoint-decides)").
;;
;; Findings — the runtime primitives below implement the three defaults
;; ratified by Track C/1 correctly. Each finding is anchored at the
;; emitted-C line that provides evidence:
;;
;; 1. UNBOUNDED MAILBOX — CONFIRMED.
;;    `dispatch_queue_create(..., DISPATCH_QUEUE_SERIAL)` (:queue-init
;;    above) has no capacity limit; `dispatch_async` on the queue
;;    (`cxc_send_async` below) never blocks / drops. Every atom's
;;    mailbox is genuinely unbounded today.
;;
;; 2. SENDER SELF-THROTTLES — CONFIRMED.
;;    No receiver-driven backpressure primitive exists. Sender's only
;;    levers are (a) glyph choice — `:>` fire-and-forget vs `|>`
;;    sync-until-processing (`cxc_wait_for_processing`), and (b)
;;    upstream transformer atoms (throttle / debounce) in the source
;;    expression. The receiver has no way to signal "slow down" to the
;;    sender; matches spec intent.
;;
;; 3. PER-MAILBOX FIFO — CONFIRMED.
;;    `DISPATCH_QUEUE_SERIAL` guarantees FIFO dispatch by construction.
;;    Cross-atom ordering: under single-queue mode (default today) every
;;    atom's queue targets main, so blocks incidentally serialize to a
;;    single global order — stricter than spec, but not user-visible as
;;    a guarantee (F32 concurrent flip removes it). Under concurrent
;;    mode blocks from different atoms run on independent workers with
;;    no cross-atom ordering. Both modes satisfy the spec's "per-atom
;;    FIFO, no cross-atom" contract.
;;
;; C/2 opt-in (`def <Atom>(queue: bounded(N))`) is parsed and carried
;; on the def-atom node's `:queue-config` slot but NOT yet enforced at
;; runtime — `cxc_send_async` still calls `dispatch_async` unconditionally.
;; Enforcement (bounded-mailbox wrapper: per-queue pending counter +
;; discipline on overflow: block / drop / signal) is the deferred
;; follow-on landing.
;; ---------------------------------------------------------------------------

(def ^:private cxc-nil-sentinels
  ;; 2026-09-05 Path 1 slice 10 — the sentinel-lifting macros
  ;; (CXC_INT_NIL / CXC_INT_IS_NIL / …) are retired. Every nullable
  ;; primitive value now lives in a `_Cxc_Optional_<T>` envelope
  ;; (present + value); `<stdint.h>` remains part of the preamble
  ;; because the envelope typedefs and existing C code still depend
  ;; on the sized-int types.
  (str "#include <stdint.h>\n"
       "#include <math.h>\n"))

(def ^:private cxc-runtime-helpers
  ;; Header-only runtime (design §22). The CXCAtom protocol declares
  ;; the wire operations every atom class satisfies implicitly:
  ;;   - own_queue: the atom's serial dispatch queue
  ;;   - nilSlot: parent-side nested-slot auto-nil (§7.2)
  ;;   - transitionToEnd: cascade entry-point (§7.3)
  ;; Helpers cast id → id<CXCAtom> for calls that need those messages,
  ;; cheaper than KVC / objc_msgSend / dynamic lookup.
  ;;
  ;; F31/F35 Landing 1 — the sync-send primitive is `cxc_wait_for_processing`
  ;; (spec framing: `|>` is a processing barrier, not "send with reply").
  ;; Under Layer 4 (CXC_SINGLE_MAIN_QUEUE) every atom's queue targets
  ;; main, so the primitive is a direct call. Under F32 (concurrent
  ;; queues) it upgrades to `dispatch_sync` onto the target's queue plus
  ;; cycle-detection scaffolding — the emit site is the single hook that
  ;; substitutes when the runtime toggle flips. See
  ;; plans/g4-actor-integrity/f31-f35-concurrency.md L1.
  (str
       ;; Trace runtime — behind CXC_TRACE build flag (added via --trace
       ;; CLI). Zero cost when the flag is off (macros expand to
       ;; ((void)0), unused params elided). When on: every send/recv/arm
       ;; event writes a JSONL line to CXC_TRACE_FD (fd number in env)
       ;; or CXC_TRACE_PATH (file path), else stderr. Sink is swappable
       ;; via env var — no re-instrumentation to switch modes.
       "#ifdef CXC_TRACE_ENABLED\n"
       "#include <stdio.h>\n"
       "#include <sys/time.h>\n"
       "#include <stdatomic.h>\n"
       "static FILE *cxc_trace_fp = NULL;\n"
       "static _Atomic uint64_t cxc_trace_seq = 0;\n"
       "static int cxc_trace_full = 0;\n"
       "static inline void cxc_trace_init(void) {\n"
       "    if (cxc_trace_fp) return;\n"
       "    const char *fd_env = getenv(\"CXC_TRACE_FD\");\n"
       "    if (fd_env && *fd_env) {\n"
       "        int fd = atoi(fd_env);\n"
       "        if (fd > 0) cxc_trace_fp = fdopen(fd, \"w\");\n"
       "    }\n"
       "    if (!cxc_trace_fp) {\n"
       "        const char *path_env = getenv(\"CXC_TRACE_PATH\");\n"
       "        if (path_env && *path_env) cxc_trace_fp = fopen(path_env, \"w\");\n"
       "    }\n"
       "    if (!cxc_trace_fp) cxc_trace_fp = stderr;\n"
       "    const char *level = getenv(\"CXC_TRACE_LEVEL\");\n"
       "    if (level && strcmp(level, \"full\") == 0) cxc_trace_full = 1;\n"
       "}\n"
       "static inline double cxc_trace_now_ms(void) {\n"
       "    struct timeval tv; gettimeofday(&tv, NULL);\n"
       "    return tv.tv_sec * 1000.0 + tv.tv_usec / 1000.0;\n"
       "}\n"
       "static inline void cxc_trace_event(const char *kind, id sender, id target, const char *msg, const char *extra) {\n"
       "    cxc_trace_init();\n"
       "    uint64_t seq = atomic_fetch_add(&cxc_trace_seq, 1);\n"
       "    const char *scls = sender ? [NSStringFromClass([sender class]) UTF8String] : \"-\";\n"
       "    const char *tcls = target ? [NSStringFromClass([target class]) UTF8String] : \"-\";\n"
       "    fprintf(cxc_trace_fp,\n"
       "        \"{\\\"seq\\\":%llu,\\\"t\\\":%.3f,\\\"kind\\\":\\\"%s\\\",\\\"sender_cls\\\":\\\"%s\\\",\\\"sender\\\":%llu,\\\"target_cls\\\":\\\"%s\\\",\\\"target\\\":%llu,\\\"msg\\\":\\\"%s\\\"%s%s%s}\\n\",\n"
       "        (unsigned long long)seq, cxc_trace_now_ms(),\n"
       "        kind ? kind : \"-\", scls, (unsigned long long)(uintptr_t)sender,\n"
       "        tcls, (unsigned long long)(uintptr_t)target,\n"
       "        msg ? msg : \"-\",\n"
       "        extra && *extra ? \",\\\"extra\\\":\\\"\" : \"\",\n"
       "        extra && *extra ? extra : \"\",\n"
       "        extra && *extra ? \"\\\"\" : \"\");\n"
       "    fflush(cxc_trace_fp);\n"
       "}\n"
       "#define CXC_TRACE(kind, sender, target, msg) cxc_trace_event((kind), (sender), (target), (msg), \"\")\n"
       "#define CXC_TRACE_EX(kind, sender, target, msg, extra) cxc_trace_event((kind), (sender), (target), (msg), (extra))\n"
       "#else\n"
       "#define CXC_TRACE(kind, sender, target, msg) ((void)(sender), (void)(target), (void)(msg))\n"
       "#define CXC_TRACE_EX(kind, sender, target, msg, extra) ((void)(sender), (void)(target), (void)(msg))\n"
       "#endif\n\n"
       ;; 2026-09-16 — SeededPrng runtime state table. Park-Miller
       ;; minimal-standard LCG (a=16807, m=2^31-1). State lives here
       ;; as int64_t so multiplication stays inside int64 range
       ;; without UB (16807 * 2^31 fits in ~45 bits). Not thread-safe;
       ;; matches cxc's single-main-queue default. Cap of 128 concurrent
       ;; instances is a soft ceiling — game usage typically needs 1.
       "#define CXC_PRNG_MAX 128\n"
       "static int64_t cxc_prng_state[CXC_PRNG_MAX];\n"
       "static int64_t cxc_prng_count = 0;\n"
       "static inline int64_t cxc_prng_new(int64_t seed) {\n"
       "    if (cxc_prng_count >= CXC_PRNG_MAX) return -1;\n"
       "    int64_t h = cxc_prng_count++;\n"
       ;; Fold seed into [1, m-1]. seed=0 would fix state at 0
       ;; (LCG absorbing); remap to avoid the degenerate case.
       "    int64_t s = seed % 2147483646;\n"
       "    if (s < 0) s += 2147483646;\n"
       "    cxc_prng_state[h] = s + 1;\n"
       "    return h;\n"
       "}\n"
       "static inline int64_t cxc_prng_next(int64_t h) {\n"
       "    if (h < 0 || h >= cxc_prng_count) return 0;\n"
       "    int64_t s = cxc_prng_state[h];\n"
       "    s = (16807 * s) % 2147483647;\n"
       "    cxc_prng_state[h] = s;\n"
       "    return s;\n"
       "}\n"
       "static inline int64_t cxc_prng_below(int64_t h, int64_t n) {\n"
       "    if (n <= 0) return 0;\n"
       "    return cxc_prng_next(h) % n;\n"
       "}\n\n"
       "@protocol CXCAtom\n"
       "@property (nonatomic, strong) dispatch_queue_t own_queue;\n"
       ;; Layer 5c lifecycle cleanup — atoms carry an NSMutableArray of
       ;; resources (dispatch timers, CxcStreams) they must release on
       ;; -> end. Removing all objects triggers ARC dealloc on each,
       ;; and each resource cleans itself up in dealloc (timer cancel,
       ;; stream teardown). Retires the previous "unbalanced
       ;; CFBridgingRetain forever" leak strategy of Layer 5c initial.
       "@property (nonatomic, strong) NSMutableArray *cxc_owned_resources;\n"
       "- (void)nilSlot:(NSString *)slotName;\n"
       "- (void)transitionToEnd;\n"
       "@end\n\n"
       ;; CxcTimerHandle — wraps a (dispatch_source_t, dispatch_queue_t)
       ;; pair so ARC controls its lifetime. On dealloc, cancels the
       ;; source (idempotent if already canceled) and drops the queue.
       ;; Atoms holding one in cxc_owned_resources dispose of both by
       ;; removing the handle from the array.
       "@interface CxcTimerHandle : NSObject\n"
       "@property (nonatomic, strong) dispatch_source_t source;\n"
       "@property (nonatomic, strong) dispatch_queue_t queue;\n"
       "@end\n\n"
       "@implementation CxcTimerHandle\n"
       "- (void)dealloc {\n"
       "    if (_source) dispatch_source_cancel(_source);\n"
       "    _source = nil;\n"
       "    _queue = nil;\n"
       "}\n"
       "@end\n\n"
       ;; Q1 (2026-08-26) — async send swallows target's re-raised
       ;; NSException with a stderr log. F48's :end-catch-wrap now
       ;; re-throws after cleanup (companion emit + transitionToEnd
       ;; already fired); async callers have no frame to receive the
       ;; exception, so the swallow-with-log here preserves the
       ;; pre-Q1 "async fire-and-forget never crashes the process"
       ;; contract. The exception's userInfo[@"reason"] is already
       ;; delivered to subscribers via the companion emit fired by
       ;; F48 before the re-throw — the log is for oncall visibility.
       ;; 2026-09-21 — program-global `_cxc_send_group` tracks every
       ;; outstanding async send so the shutdown path can wait for
       ;; cascade quiescence before `exit(0)`. `dispatch_group_async`
       ;; enters the group at enqueue and leaves at block completion;
       ;; cxc_shutdown registers a `dispatch_group_notify` on main that
       ;; runs `exit(0)` once every enqueued block has drained. Under
       ;; single-queue mode this replaces the source-side settle-spin
       ;; workaround (Clock.every polling with a settle_ticks counter)
       ;; that atoms with async :> fanout previously needed at shutdown.
       "static dispatch_group_t _cxc_send_group;\n"
       "static dispatch_once_t _cxc_send_group_once;\n"
       "static inline dispatch_group_t cxc_send_group(void) {\n"
       "    dispatch_once(&_cxc_send_group_once, ^{\n"
       "        _cxc_send_group = dispatch_group_create();\n"
       "    });\n"
       "    return _cxc_send_group;\n"
       "}\n\n"
       "static inline void cxc_send_async(id target, const char *msg_name, void (^block)(void)) {\n"
       "    if (target == nil) return;\n"
       "    CXC_TRACE(\"send\", nil, target, msg_name);\n"
       "    dispatch_group_async(cxc_send_group(), [(id<CXCAtom>)target own_queue], ^{\n"
       "        CXC_TRACE(\"recv\", nil, target, msg_name);\n"
       "        @try { block(); }\n"
       "        @catch (NSException *e) {\n"
       "            NSLog(@\"[cxc async unhandled] %@[%p] %@ %@\", NSStringFromClass([target class]), target, e.name, e.reason ?: @\"\");\n"
       "        }\n"
       "    });\n"
       "}\n\n"
       ;; F31/F35 L1 — sync-send primitive. Under Layer 4 (single main
       ;; queue), direct call is correct because every atom's own_queue
       ;; targets main and the caller is on main. The debug-build
       ;; dispatch_assert_queue verifies the invariant (fires only if
       ;; the queue target ever silently changes). F32 flips the else
       ;; branch to `dispatch_sync` onto the target's queue.
       "#ifndef CXC_SINGLE_MAIN_QUEUE\n"
       "#define CXC_SINGLE_MAIN_QUEUE 1\n"
       "#endif\n\n"
       ;; F32 L3 (2026-08-24) — runtime `|>` cycle detector. Per-thread
       ;; NSMutableSet tracks the atoms this thread currently has a
       ;; sync-send outstanding on. Re-entry (`|> A.foo()` called
       ;; while A is already in the sending set on this thread) is a
       ;; deadlock under concurrent mode (dispatch_sync on a queue
       ;; already locked by this thread) and an unbounded recursion
       ;; under single-queue mode; either way, panic with a clear
       ;; message. Uses -threadDictionary (thread-local storage) so
       ;; transient pool worker threads pick up an empty set. Fires
       ;; on BOTH modes — belt-and-suspenders alongside the F31 L3
       ;; compile-time cycle warning; catches dynamic-dispatch cycles
       ;; the compile-time walk can't see.
       ;; Q1 (2026-08-26) — sync-send helper now RETURNS the target's
       ;; re-raised NSException (nil on success). F48's :end-catch-wrap
       ;; catches internally in the target, runs cleanup (companion
       ;; emit + transitionToEnd), then re-throws. The helper's outer
       ;; @try captures on the sender's frame — no exception crosses
       ;; the dispatch_sync queue boundary under F32 (which would be
       ;; undefined behavior). Sender's emit inspects the return: bare
       ;; `|>` re-throws (its own F48 catches); `|>` with `or:` arms
       ;; dispatches on `userInfo[@"reason"]`.
       "static inline NSException *cxc_wait_for_processing(id target, const char *msg_name, void (^block)(void)) {\n"
       "    if (target == nil) return nil;\n"
       "    CXC_TRACE(\"send_sync\", nil, target, msg_name);\n"
       "    NSMutableDictionary *__td = [[NSThread currentThread] threadDictionary];\n"
       "    NSMutableSet *__sending = __td[@\"_cxc_sending_to\"];\n"
       "    if (__sending == nil) {\n"
       "        __sending = [NSMutableSet set];\n"
       "        __td[@\"_cxc_sending_to\"] = __sending;\n"
       "    }\n"
       "    NSValue *__key = [NSValue valueWithNonretainedObject:target];\n"
       "    if ([__sending containsObject:__key]) {\n"
       "        fprintf(stderr, \"cxc: `|>` cycle: sync-send to atom `%s` while a prior sync-send to the same atom on this thread is still awaiting reply (spec 1.11)\\n\", [NSStringFromClass([target class]) UTF8String]);\n"
       "        abort();\n"
       "    }\n"
       "    [__sending addObject:__key];\n"
       "    __block NSException *__captured = nil;\n"
       "    void (^__wrapped)(void) = ^{\n"
       "        CXC_TRACE(\"recv_sync\", nil, target, msg_name);\n"
       "        @try { block(); }\n"
       "        @catch (NSException *e) { __captured = e; }\n"
       "    };\n"
       "    @try {\n"
       "#if CXC_SINGLE_MAIN_QUEUE\n"
       "#ifdef DEBUG\n"
       "        dispatch_assert_queue([(id<CXCAtom>)target own_queue]);\n"
       "#endif\n"
       "        __wrapped();\n"
       "#else\n"
       "        dispatch_sync([(id<CXCAtom>)target own_queue], __wrapped);\n"
       "#endif\n"
       "    } @finally {\n"
       "        [__sending removeObject:__key];\n"
       "    }\n"
       "    return __captured;\n"
       "}\n\n"
       ;; Q2 (2026-08-26) — bounded(N) mailbox variants. Semaphore-based
       ;; backpressure: sem is created at capacity N in the target's
       ;; -cxc_structuralSetup; every enqueued message decrements the
       ;; sem, every dispatched block signals it. Async uses TIME_NOW
       ;; (drop-newest — spec §1.11: `:>` fire-and-forget preserved,
       ;; new message silently dropped when mailbox full). Sync uses
       ;; TIME_FOREVER (block-until-space — spec §1.11: `|>` waits
       ;; for room, composes with existing sync-await-processing).
       ;; Callers pass the sem directly (not looked up via protocol
       ;; dispatch) — the emit site knows the target class statically
       ;; and threads the sem via direct-ivar access; zero cost for
       ;; unbounded targets (helper never invoked).
       "static inline void cxc_send_async_bounded(id target, dispatch_semaphore_t sem, const char *msg_name, void (^block)(void)) {\n"
       "    if (target == nil) return;\n"
       "    if (dispatch_semaphore_wait(sem, DISPATCH_TIME_NOW) != 0) {\n"
       "        CXC_TRACE(\"send_dropped\", nil, target, msg_name);\n"
       "#ifdef DEBUG\n"
       "        NSLog(@\"[cxc bounded drop] %@[%p] mailbox full — dropping :> message\", NSStringFromClass([target class]), target);\n"
       "#endif\n"
       "        return;\n"
       "    }\n"
       "    CXC_TRACE(\"send\", nil, target, msg_name);\n"
       "    dispatch_async([(id<CXCAtom>)target own_queue], ^{\n"
       "        CXC_TRACE(\"recv\", nil, target, msg_name);\n"
       "        @try { block(); }\n"
       "        @catch (NSException *e) {\n"
       "            NSLog(@\"[cxc async unhandled] %@[%p] %@ %@\", NSStringFromClass([target class]), target, e.name, e.reason ?: @\"\");\n"
       "        }\n"
       "        dispatch_semaphore_signal(sem);\n"
       "    });\n"
       "}\n\n"
       "static inline NSException *cxc_wait_for_processing_bounded(id target, dispatch_semaphore_t sem, const char *msg_name, void (^block)(void)) {\n"
       "    if (target == nil) return nil;\n"
       "    dispatch_semaphore_wait(sem, DISPATCH_TIME_FOREVER);\n"
       "    NSException *__result = cxc_wait_for_processing(target, msg_name, block);\n"
       "    dispatch_semaphore_signal(sem);\n"
       "    return __result;\n"
       "}\n\n"
       ;; F32 L2 (2026-08-24) — shutdown drain. Under future concurrent
       ;; mode (F32 L1 flips atom queues from main-target to the global
       ;; pool), `exit(0)` can race with in-flight blocks that a
       ;; departing atom's `-> end do:` fired via `cxc_send_async`. The
       ;; barrier waits for all currently-executing pool blocks to
       ;; complete before we let the process die. Guarded on
       ;; `!CXC_SINGLE_MAIN_QUEUE` because under Layer 4 single-queue
       ;; mode (current default) atoms live on their own serial queues
       ;; target-set to main — there's nothing on the global pool to
       ;; drain, and the barrier would be a no-op with cost. F31/F35
       ;; L5-L7 sync cascade already guarantees children complete
       ;; before main's -transitionToEnd returns, so single-queue mode
       ;; has no gap to close.
       ;; 2026-09-21 — quiescence-await shutdown. `dispatch_group_notify`
       ;; runs the exit block on main WHEN every outstanding cxc_send_async
       ;; enqueue has completed (see `cxc_send_group` above). If the group
       ;; is already empty, the notify block enqueues immediately and fires
       ;; once main returns to its runloop; otherwise it fires after the
       ;; last in-flight block drains. Callers (main atom's `-> end` body)
       ;; return normally from -transitionToEnd; the process stays alive
       ;; on dispatch_main() until notify runs exit(0).
       "static inline void cxc_shutdown(void) {\n"
       "#if !CXC_SINGLE_MAIN_QUEUE\n"
       "    dispatch_barrier_sync(dispatch_get_global_queue(QOS_CLASS_DEFAULT, 0), ^{});\n"
       "#endif\n"
       "    dispatch_group_notify(cxc_send_group(), dispatch_get_main_queue(), ^{\n"
       "        exit(0);\n"
       "    });\n"
       "}\n\n"
       ;; F32 L4.a (2026-08-24) — atomic-pointer-swap storage-flip
       ;; infrastructure, sub-landing 1 of N per the storage-flip
       ;; discipline (land fan-out sites as guarded dead code before
       ;; flipping storage reps). Introduces `_CxcAtomState` — an
       ;; ARC-managed wrapper carrying a heap-allocated
       ;; `<Atom>_State` struct pointer. Every commit under concurrent
       ;; mode (F32 L4.c) will allocate a fresh wrapper, populate it
       ;; from the transition's next-state bag, and atomically swap
       ;; the atom's `_state` slot; readers (L4.d) will atomic-load
       ;; the slot and dereference the wrapper's `state` pointer.
       ;; The wrapper's `-dealloc` frees the underlying malloc'd
       ;; buffer, so ARC handles cross-reader lifetime — the old
       ;; wrapper releases when no in-flight reader retains it.
       ;;
       ;; Guarded on `!CXC_SINGLE_MAIN_QUEUE` because Layer 4 single-
       ;; queue mode (current default) uses F33 L3's per-field ivar
       ;; commit, which is correct under main-serialized reads/writes.
       ;; Under default flag this whole block is preprocessor-guarded
       ;; out — zero runtime cost. Nothing references `_CxcAtomState`
       ;; yet; L4.b adds the per-atom `_state` ivar, L4.c wires
       ;; commits to populate it, L4.d flips cross-atom reads to
       ;; route through it.
       "#if !CXC_SINGLE_MAIN_QUEUE\n"
       ;; F32 L4.b.α (2026-08-24) — stdatomic pulled in under the
       ;; same guard so the `_cxc_state_load` / `_cxc_state_commit`
       ;; helpers below compile when concurrent mode is enabled. Under
       ;; the default preprocessor flag this whole block guards out;
       ;; the include only fires when the flip is opted in.
       "#include <stdatomic.h>\n"
       "@interface _CxcAtomState : NSObject\n"
       "@property (nonatomic, assign) void *state;\n"
       "@property (nonatomic, assign) size_t size;\n"
       "@end\n\n"
       "@implementation _CxcAtomState\n"
       "- (void)dealloc {\n"
       "    if (_state) { free(_state); _state = NULL; }\n"
       "}\n"
       "@end\n"
       ;; F32 L4.b.α (2026-08-24) — reader/writer runtime helpers for
       ;; the atomic-pointer-swap storage flip. `_cxc_state_load` does
       ;; an acquire-ordered atomic load and returns the referenced
       ;; wrapper (nil if never committed); readers dereference
       ;; `.state` to reach the underlying `<Atom>_State` struct.
       ;; `_cxc_state_commit` wraps a fresh copy of the caller-owned
       ;; `next` bag (memcpy'd into a malloc'd buffer so ARC's dealloc
       ;; can `free()` it) and does a release-ordered atomic store —
       ;; the old wrapper's refcount drops when no reader still
       ;; retains it, so ARC handles cross-reader lifetime. Both
       ;; helpers stay dead until L4.c wires the commit and L4.d
       ;; routes cross-atom reads. Under the default preprocessor
       ;; flag the whole block guards out; zero runtime cost.
       "static inline _CxcAtomState *_cxc_state_load(_Atomic id *slot) {\n"
       "    return (__bridge _CxcAtomState *)(void *)atomic_load_explicit(\n"
       "        (_Atomic(intptr_t) *)slot, memory_order_acquire);\n"
       "}\n\n"
       "static inline void _cxc_state_commit(_Atomic id *slot, const void *src, size_t size) {\n"
       "    _CxcAtomState *wrapper = [[_CxcAtomState alloc] init];\n"
       "    void *buf = malloc(size);\n"
       "    memcpy(buf, src, size);\n"
       "    wrapper.state = buf;\n"
       "    wrapper.size  = size;\n"
       "    id previous = (__bridge_transfer id)(void *)atomic_exchange_explicit(\n"
       "        (_Atomic(intptr_t) *)slot,\n"
       "        (intptr_t)(__bridge_retained void *)wrapper,\n"
       "        memory_order_release);\n"
       "    (void)previous; /* ARC releases on scope exit */\n"
       "}\n"
       ;; F32 L4.d.α (2026-08-24) — reader-side convenience wrapper.
       ;; `_cxc_state_load` returns the ARC-wrapper (or nil if the atom
       ;; has never committed); readers want the underlying `<Atom>_State
       ;; *` buffer. This helper does the wrapper→buffer projection with
       ;; a nil-safe guard so pre-first-commit reads return NULL instead
       ;; of dereferencing nil.wrapper.state (nil.state would return 0
       ;; via ObjC's nil-messaging, but expressing the guard explicitly
       ;; makes the read-path intent auditable at the C level). Callers
       ;; cast the returned `void *` to the atom's own `<Atom>_State *`.
       ;; Dead code until L4.d.β wires cross-atom `.field` reads to
       ;; route through this helper — self-reads stay on the L2
       ;; snapshot path.
       "static inline void *_cxc_atom_state_ptr(_Atomic id *slot) {\n"
       "    _CxcAtomState *w = _cxc_state_load(slot);\n"
       "    return w ? w.state : NULL;\n"
       "}\n"
       "#endif\n\n"
       ;; F39 L3 — `T!` runtime enforcement. Warning-grade per spec §3.6.1
       ;; line 3686: "logs a warning in development, configurable action
       ;; in release." The check itself is inlined at each observation
       ;; point (ctor-param bind, field write, method return) via a
       ;; plain `if (x == nil) cxc_nonnil_violation(...)` guard — no
       ;; dispatch, no allocation, one branch per site.
       "static inline void cxc_nonnil_violation(const char *site, const char *name) {\n"
       "    fprintf(stderr, \"cxc: `T!` violation at %s: `%s` is nil (spec §3.6.1)\\n\", site, name);\n"
       "}\n\n"
       ;; Own a resource for cleanup on the atom's -> end. Lazily
       ;; allocates the owner's resources array on first call —
       ;; atoms that never spawn timers/streams pay one pointer of
       ;; overhead (the nil ivar) and no allocation.
       "static inline void cxc_own_resource(id owner, id resource) {\n"
       "    if (owner == nil || resource == nil) return;\n"
       "    id<CXCAtom> a = (id<CXCAtom>)owner;\n"
       "    NSMutableArray *arr = a.cxc_owned_resources;\n"
       "    if (arr == nil) {\n"
       "        arr = [NSMutableArray array];\n"
       "        a.cxc_owned_resources = arr;\n"
       "    }\n"
       "    [arr addObject:resource];\n"
       "}\n\n"
       ;; Layer 5 Step 7 — CxcStream: first-class stream value.
       ;; Method-returning-stream calls (e.g. clock.every(rate) at
       ;; Step 10) allocate a CxcStream per call. Subscribers register
       ;; on the stream's own hashtable; the source (Clock, or a Layer
       ;; 5b transformer) invokes cxc_emit on the stream to fan out to
       ;; subscribers. Teardown block runs on end (Layer 5b cancels
       ;; timers, releases OS resources).
       ;;
       ;; Value passing: CxcStream * is an id-typed handle; passed
       ;; through data fields, let bindings, and receive-glyph source
       ;; expressions like any other atom reference.
       ;;
       ;; Layer 5 Step 7 minimum: only `-cxc_emit` (no payload) plus
       ;; per-stream own_queue for hop parity with Layer 4b atoms.
       ;; Typed-payload emit (`-cxc_emit_Withk:...`) lands at Step 8
       ;; once a real user of method-returned streams (Clock.every
       ;; at Step 10) forces the shape.
       "@interface CxcStream : NSObject\n"
       "@property (nonatomic, strong) dispatch_queue_t own_queue;\n"
       "@property (nonatomic, strong) NSHashTable *subscribers;\n"
       ;; Layer 5c Step 8: per-instance fanout selector + source
       ;; identity. Each CxcStream carries the receiver-side selector
       ;; name it dispatches to when emitting; a method-returned
       ;; stream (e.g. clock.every(rate)) uses the same encoding as
       ;; static streams (cxc_stream_<method>_from_<Source>Withsender:)
       ;; so subscribers implement one method regardless of source
       ;; kind. `source` fills the sender: arg at emit time — matches
       ;; Step 6b's fanout-side identity.
       "@property (nonatomic, copy) NSString *fanoutSelector;\n"
       "@property (nonatomic, weak) id source;\n"
       "@property (nonatomic, copy) void (^teardown)(void);\n"
       "- (instancetype)initWithSelector:(NSString *)selectorName source:(id)source;\n"
       "- (void)cxc_addSubscriber:(id)sub;\n"
       "- (void)cxc_removeSubscriber:(id)sub;\n"
       "- (void)cxc_emit;\n"
       "- (void)cxc_end;\n"
       "@end\n\n"
       "@implementation CxcStream\n"
       "- (instancetype)init {\n"
       "    self = [super init];\n"
       "    if (self) {\n"
       "        _own_queue = dispatch_queue_create(\"cxc.stream\", DISPATCH_QUEUE_SERIAL);\n"
       ;; F32 L5 (2026-08-24) — align stream own_queue with the L1 atom
       ;; own_queue guard. Under Layer 4 single-queue mode (default) the
       ;; stream's fan-out serializes on main, matching every atom's
       ;; behavior. Under future concurrent mode (CXC_SINGLE_MAIN_QUEUE=0)
       ;; the stream inherits libdispatch's default target (the default-
       ;; QoS global pool) — fan-out still serializes per-stream (queue
       ;; stays SERIAL) but no longer bottlenecks through main. Subscriber
       ;; delivery uses cxc_send_async into each subscriber's own_queue,
       ;; so this change only affects where the emit itself runs.
       "#if CXC_SINGLE_MAIN_QUEUE\n"
       "        dispatch_set_target_queue(_own_queue, dispatch_get_main_queue());\n"
       "#endif\n"
       "        _subscribers = [NSHashTable weakObjectsHashTable];\n"
       "    }\n"
       "    return self;\n"
       "}\n"
       "- (instancetype)initWithSelector:(NSString *)selectorName source:(id)source {\n"
       "    self = [self init];\n"
       "    if (self) {\n"
       "        _fanoutSelector = [selectorName copy];\n"
       "        _source = source;\n"
       "    }\n"
       "    return self;\n"
       "}\n"
       "- (void)cxc_addSubscriber:(id)sub {\n"
       "    if (sub != nil) [_subscribers addObject:sub];\n"
       "}\n"
       "- (void)cxc_removeSubscriber:(id)sub {\n"
       "    if (sub != nil) [_subscribers removeObject:sub];\n"
       "}\n"
       "- (void)cxc_emit {\n"
       "    SEL sel = _fanoutSelector ? NSSelectorFromString(_fanoutSelector)\n"
       "                              : @selector(cxc_stream_value);\n"
       "    __weak id src = _source;\n"
       "    for (id sub in _subscribers) {\n"
       ;; objc_msgSend cast — selector is dynamic, not declared on any
       ;; static receiver type at compile time; the NSObject fanout
       ;; category already declares it for the specific method streams
       ;; the program uses, but this generic path handles any.
       "        cxc_send_async(sub, _fanoutSelector ? [_fanoutSelector UTF8String] : \"cxc_stream_value\", ^{\n"
       "            ((void (*)(id, SEL, id))objc_msgSend)(sub, sel, src);\n"
       "        });\n"
       "    }\n"
       "}\n"
       "- (void)cxc_end {\n"
       "    if (_teardown) _teardown();\n"
       "    _teardown = nil;\n"
       "    [_subscribers removeAllObjects];\n"
       "}\n"
       ;; When an owning atom drops the stream from its
       ;; cxc_owned_resources, ARC calls dealloc — route through
       ;; cxc_end so any teardown block still fires (idempotent since
       ;; cxc_end nils _teardown after invoking it).
       "- (void)dealloc {\n"
       "    [self cxc_end];\n"
       "}\n"
       "@end"))

(defn- collect-fanout-selectors
  "Walk the emit AST for all :stream-fanout nodes; return a vector of
   unique {:selector :arg-names :arg-c-types} triples, in first-seen
   order. Feeds the NSObject category that declares every receiver-side
   fanout selector — needed because Apple clang treats a message send
   to an `id`-typed receiver with an unknown selector as a hard error
   (not a warning), so an unsubscribed stream would otherwise refuse
   to compile."
  [prog]
  (let [seen (java.util.LinkedHashSet.)
        out  (java.util.ArrayList.)]
    (walk/postwalk
     (fn [n]
       (when (and (map? n) (= :stream-fanout (:node n)))
         (let [key [(:selector n) (:arg-names n) (:arg-c-types n)]]
           (when-not (.contains seen key)
             (.add seen key)
             (.add out {:selector    (:selector n)
                        :arg-names   (:arg-names n)
                        :arg-c-types (:arg-c-types n)}))))
       n)
     (:classes prog))
    (vec out)))

(defn- emit-fanout-selector-category
  "One `@interface NSObject (CxcFanoutSelectors) ... @end` block
   declaring every receiver-side selector used by :stream-fanout in the
   program. Argument names come from :arg-names, types from :arg-c-types;
   zero-arg selectors emit as bare method names. Emitted ahead of any
   @implementation so every subscriber-facing selector is declared and
   `[sub selector]` message sends on `id` type-check even if no atom
   subscribes to that stream at build time."
  [prog]
  (let [entries (collect-fanout-selectors prog)]
    (when (seq entries)
      (let [decls (map (fn [{:keys [selector arg-names arg-c-types]}]
                         (if (empty? arg-names)
                           (str "- (void)" selector ";")
                           (let [parts (str/split selector #":")]
                             (str "- (void)"
                                  (str/join " "
                                            (map (fn [p n t]
                                                   (str p ":(" t ") " n))
                                                 parts arg-names arg-c-types))
                                  ";"))))
                       entries)]
        (str "@interface NSObject (CxcFanoutSelectors)\n"
             (str/join "\n" decls)
             "\n@end")))))

(defn emit
  "Emit a full .m source string from an :objc-program AST. Class output
   is split into an INTERFACES section (phase constants + @interface
   blocks) followed by an IMPLEMENTATIONS section — so every class's
   method declarations are visible before ANY @implementation body runs.
   Matters for stream fan-out: `[sub cxc_stream_beep_from_Emitter]` on
   an `id`-typed subscriber needs the subscriber-class's @interface to
   be in scope when the source's fanout body compiles."
  [prog]
  (let [includes    (str/join "\n"   (map #(str "#import " %) (:includes prog)))
        fanout-cat  (emit-fanout-selector-category prog)
        helpers     (when (:needs-runtime? prog) cxc-runtime-helpers)
        enums       (str/join "\n\n" (map emit-any-enum (:enums prog)))
        structs     (str/join "\n\n" (map emit-struct   (:structs prog)))
        ;; Track A/2.a (2026-08-26) — reason-box wrapper classes.
        ;; Slotted between structs and fn-prototypes so the wrapped
        ;; struct type is complete when the @interface's @public
        ;; `value` ivar is declared, and so downstream record
        ;; synthesized fns / class @interfaces see the wrapper types.
        reason-boxes (str/join "\n\n" (map emit-reason-box (:reason-boxes prog)))
        ;; F15 L2.c.iii.a — named-tuple typedef block. Populated by
        ;; lower/build when the parsed AST contains any :type-named-tuple
        ;; nodes (L2.c.iii.b will wire this up). Today every program
        ;; leaves this field unset, so the string is blank and no
        ;; typedef block appears in the emit — zero-cost forward-compat
        ;; hook. The typedef block is C-level structural definitions
        ;; that must precede any type reference; placing it after
        ;; structs mirrors the pattern.
        named-tuple-typedefs (or (:named-tuple-typedefs prog) "")
        ;; F37 Landing 4 — emit all synthesized-function prototypes
        ;; before any bodies so nested-record encoders can reference
        ;; each other regardless of declaration order in `:functions`.
        ;; Zero-cost for non-mutual cases (prototype + body pair) and
        ;; keeps the emit pipeline unaware of dependency ordering.
        fn-prototypes (when (seq (:functions prog))
                        (str "// forward function declarations\n"
                             (str/join "\n"
                                       (map emit-function-prototype (:functions prog)))))
        functions   (str/join "\n\n" (map emit-function (:functions prog)))
        ;; Forward @class declarations for every user/synthesized class
        ;; so cross-references in @interface method signatures (e.g. a
        ;; List container declaring `- (void)... :(Element *)e`) don't
        ;; hit "unknown type" errors from clang when their @interface
        ;; order happens to be Container-before-Element.
        forward-classes (when (seq (:classes prog))
                          (str "// forward declarations\n"
                               (str/join "\n"
                                         (map #(str "@class " (:name %) ";")
                                              (:classes prog)))))
        interfaces  (str/join "\n\n" (map emit-class-interface-block (:classes prog)))
        impls       (str/join "\n\n" (map emit-class    (:classes prog)))
        ;; File-scope static that main() assigns to. Lives across the
        ;; @autoreleasepool boundary into dispatch_main() so the atom
        ;; isn't released before the queue starts draining.
        main-static (when (some #(= "CxcMain" (:name %)) (:classes prog))
                      "static CxcMain *cxc_main_atom = nil;")
        main        (emit-main (:main prog) (:needs-dispatch-loop? prog)
                                (:runtime-shape prog :dispatch-main))]
    (str/join "\n\n"
              (remove str/blank? [includes
                                  cxc-nil-sentinels
                                  helpers
                                  enums
                                  ;; @class forward decls precede struct
                                  ;; typedefs so a record field carrying a
                                  ;; pointer-to-container-class (e.g.
                                  ;; `_Cxc_ListOfToolCall * tool_calls`)
                                  ;; compiles even when the container's
                                  ;; @interface lands later in the file.
                                  ;; Pointer types only need the class
                                  ;; name in scope — the full @interface
                                  ;; comes later in the INTERFACES block.
                                  forward-classes
                                  structs
                                  reason-boxes
                                  named-tuple-typedefs
                                  ;; 2026-09-18 — interfaces must precede
                                  ;; free-function bodies. Record observable
                                  ;; bodies (emitted as C functions of
                                  ;; `self`) can now dispatch container
                                  ;; methods (`hand.at(0)`); the receiver
                                  ;; type is a container class whose full
                                  ;; @interface must be visible at the
                                  ;; call site or clang can't type the
                                  ;; ObjC message send. Pre-fix order was
                                  ;; fn-prototypes → functions → interfaces.
                                  interfaces
                                  fn-prototypes functions
                                  fanout-cat impls
                                  main-static main]))))
