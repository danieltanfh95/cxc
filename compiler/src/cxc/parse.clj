(ns cxc.parse
  "Parser for cxc. Hand-rolled recursive-descent over the token stream
   produced by cxc.lex. Grown incrementally against design/examples/*.cxc.

   AST shape: Clojure maps with :node key naming the node type. Every node
   carries :line for diagnostics. Parser is state-monadic in style —
   functions take [tokens pos] and return [node new-pos] or throw ex-info
   on parse error.

   Coverage: full v1.0 surface — records, def-atom actors, enums, F9
   extensions, F16/F18 expression flow, F31-F35 concurrent-actor
   semantics, F52 imports and module let-bindings, F70 messaging.
   Retired forms (`module` header, `switch`, `function`, brace-payload,
   `use:` section, binary postfix `|>`) fire targeted rejection
   diagnostics."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [cxc.lex :as lex]
            [cxc.section :as section]))

;; ---------------------------------------------------------------------------
;; Chained-comparison desugaring
;;
;; The general binop parser left-associates comparison ops, producing
;; `((a <= b) <= c)`. That's incorrect C semantics for chained bounds
;; like `-max <= y <= max`. We rewrite the left-associated tree into a
;; `:chained-cmp` node carrying the flat operand + op vectors:
;;
;;   {:node :chained-cmp
;;    :operands [a b c ...]
;;    :ops      [cmp1 cmp2 ...]}
;;
;; Lower/emit render this as a GNU statement-expression that binds
;; each *interior* operand (operands[1..n-2]) to a `__typeof__` local,
;; then evaluates `operands[0] op0 t1 && t1 op1 t2 && ...` against
;; the bound locals. Result: every interior operand — including the
;; middle of a 3-operand chain — is evaluated exactly once at
;; runtime, so `-max <= counter.next() <= max` no longer duplicates
;; the call. (F27 semantic-once-only landing.)
;; ---------------------------------------------------------------------------

(def ^:private cmp-ops #{"<" "<=" ">" ">="})

(defn- chained? [n]
  (and (map? n)
       (= :binop (:node n))
       (contains? cmp-ops (:op n))
       (map? (:left n))
       (or (and (= :binop (get-in n [:left :node]))
                (contains? cmp-ops (get-in n [:left :op])))
           (= :chained-cmp (get-in n [:left :node])))))

(defn- desugar-chained-node [n]
  (cond
    (not (chained? n)) n

    ;; Left operand is an already-desugared :chained-cmp: extend it
    ;; with the new (op, rhs) pair. Postwalk visits children first,
    ;; so a 3+-operand chain (((a<b)<c)<d) is rebuilt into a single
    ;; :chained-cmp node in one pass.
    (= :chained-cmp (get-in n [:left :node]))
    (let [inner (:left n)]
      {:node :chained-cmp
       :operands (conj (:operands inner) (:right n))
       :ops      (conj (:ops inner)      (:op    n))})

    ;; Left operand is a bare binop cmp: fold into the base :chained-
    ;; cmp shape with three operands and two ops.
    :else
    (let [inner (:left n)]
      {:node :chained-cmp
       :operands [(:left inner) (:right inner) (:right n)]
       :ops      [(:op inner)   (:op n)]})))

(defn- desugar-chained-comparisons
  "Bottom-up rewrite: every left-associated cmp chain becomes a single
   `:chained-cmp` node. Recursively applied via postwalk so inner
   chains rewrite before outer ones — needed for 3+ operand chains."
  [ast]
  (walk/postwalk desugar-chained-node ast))

;; ---------------------------------------------------------------------------
;; Token stream primitives
;; ---------------------------------------------------------------------------

(defn- t-kind  [tok] (nth tok 0))
(defn- t-value [tok] (nth tok 1))
(defn- t-line  [tok] (nth tok 2))

(defn- peek-tok [tokens pos]
  (nth tokens pos nil))

(defn- at?
  "True if token at pos matches kind (and optionally value)."
  ([tokens pos kind]
   (when-let [t (peek-tok tokens pos)]
     (= kind (t-kind t))))
  ([tokens pos kind value]
   (when-let [t (peek-tok tokens pos)]
     (and (= kind (t-kind t)) (= value (t-value t))))))

(defn- fail! [tokens pos msg]
  (let [t (peek-tok tokens pos)]
    (throw (ex-info (str "parse error: " msg
                         (when t
                           (str " (got " (t-kind t) " "
                                (pr-str (t-value t)) " at line "
                                (t-line t) ")")))
                    {:pos pos :token t}))))

(defn- expect
  "Consume a token of the given kind (+ optional value). Return [value new-pos]."
  ([tokens pos kind]
   (if (at? tokens pos kind)
     (let [t (peek-tok tokens pos)]
       [(t-value t) (inc pos)])
     (fail! tokens pos (str "expected " kind))))
  ([tokens pos kind value]
   (if (at? tokens pos kind value)
     [value (inc pos)]
     (fail! tokens pos (str "expected " kind " " (pr-str value))))))

(defn- skip-newlines [tokens pos]
  (loop [p pos]
    (if (at? tokens p :newline)
      (recur (inc p))
      p)))

;; ---------------------------------------------------------------------------
;; Type expressions — smallest useful subset
;;   type := ident               ("Float", "Int", "Vector2")
;;         | ident 'of' type     ("List of Enemy", "Stream of Bytes")
;;         | type '?'            (nullable — Optional<T>)
;; ---------------------------------------------------------------------------

(declare parse-expr)

(defn- parse-type [tokens pos]
  (let [[inner p]
        (cond
          ;; F15 Landing 1 — paren-tuple type at start position. Two
          ;; shapes disambiguated by peeking after the `(`:
          ;;   (x: Int, y: Int)  — named tuple / shape (spec §3.2)
          ;;   (Int, String)     — anonymous tuple (spec §3.2)
          ;; Mixed forms are grammar errors: every field named, or none.
          (at? tokens pos :punct "(")
          (let [after-lp (inc pos)
                ;; Named tuple iff `ident :` follows the `(`.
                named? (and (at? tokens after-lp :ident)
                            (at? tokens (inc after-lp) :punct ":"))]
            (if named?
              ;; (ident : type, ident : type, ...)
              (loop [fields [] p after-lp]
                (let [[nm p1] (expect tokens p :ident)
                      _       (when-not (at? tokens p1 :punct ":")
                                (fail! tokens p1
                                       "expected `:` in named-tuple field decl"))
                      [ty p2] (parse-type tokens (inc p1))
                      fields  (conj fields {:name nm :type ty})]
                  (cond
                    (at? tokens p2 :punct ",")
                    (recur fields (inc p2))
                    (at? tokens p2 :punct ")")
                    [{:node :type-named-tuple :fields fields} (inc p2)]
                    :else
                    (fail! tokens p2
                           "expected `,` or `)` in named-tuple type"))))
              ;; (type, type, ...) — anonymous tuple; error on mixed
              ;; ident+colon form we didn't recognize as named.
              (loop [ts [] p after-lp]
                (let [[t p1] (parse-type tokens p)
                      ts (conj ts t)]
                  (cond
                    (at? tokens p1 :punct ",")
                    (recur ts (inc p1))
                    (at? tokens p1 :punct ")")
                    (if (= 1 (count ts))
                      ;; `(T)` = paren-grouped single type; no tuple.
                      [t (inc p1)]
                      [{:node :type-tuple :types ts} (inc p1)])
                    :else
                    (fail! tokens p1
                           "expected `,` or `)` in tuple type"))))))

          :else
          (let [[name p]  (expect tokens pos :ident)
                [inner p] (cond
                            ;; `T of ( K , V , ... )` — Layer 6 Step 8.
                            ;; Paren-tuple as the `of` operand carries
                            ;; multiple type params (Map of (K, V), Pair
                            ;; of (A, B) etc). The :inner slot becomes a
                            ;; :type-tuple node whose :types is the
                            ;; ordered vector of sub-types.
                            (and (at? tokens p :kw "of")
                                 (at? tokens (inc p) :punct "("))
                            (loop [ts [] p (inc (inc p))]
                              (let [[t p2] (parse-type tokens p)
                                    ts (conj ts t)]
                                (cond
                                  (at? tokens p2 :punct ",")
                                  (recur ts (inc p2))
                                  (at? tokens p2 :punct ")")
                                  [{:node :type-of :outer name
                                    :inner {:node :type-tuple :types ts}}
                                   (inc p2)]
                                  :else
                                  (fail! tokens p2
                                         "expected `,` or `)` in type-parameter list"))))

                            (at? tokens p :kw "of")
                            (let [[inner-t p2] (parse-type tokens (inc p))]
                              [{:node :type-of :outer name :inner inner-t} p2])

                            :else
                            [{:node :type-ref :name name} p])]
            [inner p]))
        ;; Nullability suffixes (spec §3.6.1, unified rule 2026-08-20;
        ;; L5a landed same day):
        ;;   bare `T` — nil-allowed (default at every position; value-
        ;;              typed bare `T` becomes Optional-boxed at C
        ;;              level in F39 L5b)
        ;;   `T!`     — non-nil contract on every shape (runtime
        ;;              enforced at three observation points per F39 L3)
        ;;   `T?`     — BANNED universally. Parser still produces
        ;;              `:type-nullable` so lower-time can reject with
        ;;              a targeted `:cxc-f39-nullable-suffix-banned`
        ;;              diagnostic that fires on every inner shape.
        ;; Nesting like `T?!` / `T!?` is a grammar error.
        [wrapped p] (cond
                      (at? tokens p :punct "?")
                      (let [w [{:node :type-nullable :inner inner} (inc p)]]
                        (when (or (at? tokens (inc p) :punct "?")
                                  (at? tokens (inc p) :punct "!"))
                          (fail! tokens (inc p)
                                 "cannot stack `?`/`!` on the same type (spec §3.6.1)"))
                        w)
                      (at? tokens p :punct "!")
                      (let [w [{:node :type-nonnil :inner inner} (inc p)]]
                        (when (or (at? tokens (inc p) :punct "?")
                                  (at? tokens (inc p) :punct "!"))
                          (fail! tokens (inc p)
                                 "cannot stack `?`/`!` on the same type (spec §3.6.1)"))
                        w)
                      :else [inner p])]
    [wrapped p]))

;; ---------------------------------------------------------------------------
;; Expressions — pratt-style precedence climbing (partial coverage)
;;   Precedence (low → high):
;;     0 => (arrow — field-update RHS, handled at statement level)
;;     1 or
;;     2 and
;;     3 == != <= >= < > is in
;;     4 + -
;;     5 * / %
;;     6 unary - not
;;     7 postfix .field, (args), [i], {msg-args}
;;     8 primary: literal, ident, self, nil, true, false, (expr), lambda
;;
;; Lambdas: (params) => expr        or        x => expr
;; Method args: foo(x: 1, y: 2)     or        foo{x: Int}    (message shape)
;; ---------------------------------------------------------------------------

(def ^:private glyph-precedence
  ;; F39 L6 (2026-08-21) — `??` shares precedence with `or` (both
  ;; produce a value from a "left or fallback" shape). Users who mix
  ;; `??` with arithmetic/comparison parenthesize; same-precedence
  ;; siblings are unusual in practice.
  {"or" 1 "??" 1 "and" 2
   "==" 3 "!=" 3 "<=" 3 ">=" 3 "<" 3 ">" 3 "is" 3 "in" 3
   "+" 4 "-" 4
   "*" 5 "/" 5 "%" 5})

(defn- binary-glyph? [tokens pos]
  (when-let [t (peek-tok tokens pos)]
    (let [k (t-kind t) v (t-value t)]
      (or (and (= k :glyph) (glyph-precedence v))
          (and (= k :kw) (glyph-precedence v))))))

(declare parse-primary parse-args-parens parse-msg-args-parens parse-list-literal parse-emit-message looks-like-msg-payload-parens? parse-do-block-expr)

(defn- parse-postfix
  "Chain of .field, (args)/(msg-args), :> send suffixes on a primary
   expression. The `(...)` branch is content-dispatched: parens
   holding typed-params (`(name: Type, ...)`) parse as I5 msg-shape
   payload; parens holding value args parse as a call. `:>` produces
   an :emit expression node so async sends can appear in lambda
   bodies and other expression positions.

   Retired postfix branches (F70 Stage 3 hard-rejects, 2026-08-20):
   - `{...}` payload braces → parens-payload with a migration
     diagnostic naming the content-dispatch rule (I5).
   - Binary postfix `expr |> msg(args)` → prefix form
     `|> expr.msg(args)` with a migration diagnostic (I6)."
  [tokens pos base]
  (loop [p pos node base]
    (cond
      (at? tokens p :punct ".")
      ;; F37 Landing 2 — spec-committed method names like `as` / `from`
      ;; are reserved words (for module `import X as Y` / `from X import`
      ;; syntax). Accept either `:ident` or `:kw` after `.` so method
      ;; calls like `p.as()` / `Point.from(bytes)` reach lower/emit.
      ;; Ambiguity with statement-position reserved words doesn't arise
      ;; here: we're mid-expression, `.` demanded a name-token.
      (let [tk-after (peek-tok tokens (inc p))
            k        (t-kind tk-after)
            _        (when-not (or (= k :ident) (= k :kw))
                       (fail! tokens (inc p) "expected an identifier or method name after `.`"))
            fname    (t-value tk-after)
            p2       (+ p 2)]
        (recur p2 {:node :dot :target node :field fname}))

      (at? tokens p :punct "(")
      (if (looks-like-msg-payload-parens? tokens p)
        (let [[margs p2] (parse-msg-args-parens tokens p)]
          (recur p2 {:node :msg-shape :target node :fields margs}))
        (let [[args p2] (parse-args-parens tokens p)]
          (recur p2 {:node :call :callee node :args args})))

      (at? tokens p :punct "[")
      (let [[idx p2] (parse-expr tokens (inc p))
            [_ p3]   (expect tokens p2 :punct "]")]
        (recur p3 {:node :subscript :target node :index idx}))

      ;; F70 I5 Stage 3 (2026-08-19) — brace-payload is retired.
      ;; Use parens `(name: Type)` instead of `{name: Type}` — the
      ;; parser content-dispatches: parens containing typed params
      ;; parse as :msg-shape payload, parens containing value args
      ;; parse as :call. See spec §1.11 and plans/archive/f70-surface-facelift.md.
      (at? tokens p :punct "{")
      (fail! tokens p
             (str "message-payload braces `{name: Type, ...}` are "
                  "retired (F70 I5, 2026-08-19). Use parens "
                  "`(name: Type, ...)` instead — the parser "
                  "distinguishes payload-parens (contents start "
                  "with `name: Type`) from call-parens (contents "
                  "start with a value expression) by content, so "
                  "there is no ambiguity at the receive site."))

      (at? tokens p :glyph ":>")
      (let [[m p2] (parse-emit-message tokens (inc p))]
        [{:node :emit :async? true :target node :msg (:msg m) :args (:args m)} p2])

      ;; F70 I6 Stage 3 (2026-08-19) — binary postfix `<expr> |>
      ;; <msg>(args)` is retired. Use prefix form `|> <expr>.<msg>(args)`
      ;; at statement position (parse-statement handles the prefix
      ;; form; expression-position sync send is not a supported use).
      (at? tokens p :glyph "|>")
      (fail! tokens p
             (str "binary postfix `receiver |> msg(args)` is retired "
                  "(F70 I6, 2026-08-19). Use prefix form "
                  "`|> receiver.msg(args)` as a statement — the "
                  "receiver dots into the message, matching call "
                  "notation. Prefix normalizes send-site scans and "
                  "removes the source/receiver-order ambiguity."))

      :else [node p])))

(defn- parse-unary [tokens pos]
  (cond
    (at? tokens pos :glyph "-")
    (let [[inner p] (parse-unary tokens (inc pos))]
      [{:node :neg :expr inner} p])

    (at? tokens pos :glyph "+")
    ;; unary `+` is a no-op — return the inner expr unchanged
    (parse-unary tokens (inc pos))

    (at? tokens pos :kw "not")
    (let [[inner p] (parse-unary tokens (inc pos))]
      [{:node :not :expr inner} p])

    :else
    (let [[prim p] (parse-primary tokens pos)]
      (parse-postfix tokens p prim))))

(declare parse-dispatch-payload-binding)

(defn- variant-pattern-start?
  "A3 (2026-09-03) — detect the RHS shape of `<lhs> is <pat>` where
   `<pat>` is a tagged-variant case marker:
     .<case>[(<bindings>)]        — leading-dot shorthand
     <Enum>.<case>[(<bindings>)]  — explicit enum name
   Only fires at RHS of `is`; other binops still take an ordinary
   expression on the right."
  [tokens pos]
  (or (and (at? tokens pos :punct ".")
           (at? tokens (inc pos) :ident))
      (and (at? tokens pos :ident)
           (at? tokens (inc pos) :punct ".")
           (at? tokens (+ pos 2) :ident))))

(defn- parse-variant-pattern-rhs
  "Parse the case-marker RHS of an `is` proposition. Returns
     [{:node :variant-pattern
       :enum-name STR-or-nil        ; nil for leading-dot form
       :case-name STR
       :leading-dot? BOOL
       :bindings [{:field <str-or-nil> :as <str>} ...]}
      new-pos]
   Bindings use `parse-dispatch-payload-binding` (same shape as
   `<:` dispatch patterns) — either `let x` (positional) or
   `field: let x` (named)."
  [tokens pos]
  (let [leading-dot? (at? tokens pos :punct ".")
        [enum-name p]
        (if leading-dot?
          [nil (inc pos)]
          (let [nm (t-value (peek-tok tokens pos))
                _  (when-not (at? tokens (inc pos) :punct ".")
                     (fail! tokens (inc pos)
                            (str "expected `.` after `" nm "` in `is` case pattern")))]
            [nm (+ pos 2)]))
        _         (when-not (at? tokens p :ident)
                    (fail! tokens p
                           "expected a case-name identifier in `is` pattern"))
        case-name (t-value (peek-tok tokens p))
        p         (inc p)
        [bindings p]
        (if (at? tokens p :punct "(")
          (loop [bindings [] p (inc p)]
            (cond
              (at? tokens p :punct ")")
              [bindings (inc p)]

              (at? tokens p :punct ",")
              (recur bindings (inc p))

              :else
              (let [[b p2] (parse-dispatch-payload-binding tokens p)]
                (recur (conj bindings b) p2))))
          [[] p])]
    [{:node         :variant-pattern
      :enum-name    enum-name
      :case-name    case-name
      :leading-dot? leading-dot?
      :bindings     bindings}
     p]))

(defn- parse-binop [tokens pos min-prec]
  (loop [[lhs p] (parse-unary tokens pos)]
    (let [t (peek-tok tokens p)
          v (when t (t-value t))
          prec (glyph-precedence v)]
      (if (and prec (>= prec min-prec) (binary-glyph? tokens p))
        (let [op v
              rhs-pos (inc p)
              ;; A3 (2026-09-03) — `is` may take a variant-pattern RHS:
              ;;   x is .case[(let f)]  or  x is Enum.case[(let f)]
              ;; Bindings scope to the enclosing arm body (transition-
              ;; branch lowerer prepends the payload extractors).
              [rhs p2] (if (and (= "is" op)
                                (variant-pattern-start? tokens rhs-pos))
                         (parse-variant-pattern-rhs tokens rhs-pos)
                         (parse-binop tokens rhs-pos (inc prec)))]
          (recur [{:node :binop :op op :left lhs :right rhs} p2]))
        [lhs p]))))

;; ---------------------------------------------------------------------------
;; F69 L4 — `<:` expression-position dispatch (spec §3.7).
;;
;; Grammar:
;;   <scrutinee_expr>:
;;       <: pattern: body_expr
;;       <: pattern: body_expr
;;       <: *: body_expr
;;
;; Pattern subset (L4a/b — minimal):
;;   name                    → case with no payload
;;   name(let x)             → case with single positional payload bind
;;   name(field: let x)      → case with named-field payload bind
;;   _                       → wildcard
;;
;; Deferred to L4c: literal patterns, guards (`if <cond>`), leading-dot
;; shorthand, structural patterns, comma-separated multi-pattern arms.
;; Deferred to L4d: agent-scrutinee subscription lowering.
;;
;; AST shape:
;;   {:node :dispatch
;;    :scrutinee <expr>
;;    :arms [{:pattern <pat> :body <expr-or-block>} ...]}
;;   where <pat> = {:kind :case :name "n" :bindings [{:field <str-or-nil> :as "x"}]}
;;               | {:kind :wildcard}
;; ---------------------------------------------------------------------------

(declare parse-expr parse-block)

(defn- looks-like-dispatch-tail?
  "After a scrutinee expression at pos, look for the exact 4-token
   signature of a dispatch block header: `:` `\\n` INDENT `<:`. Only
   this shape opts into dispatch wrapping — any deviation means the
   trailing `:` belongs to something else (or nothing)."
  [tokens p]
  (and (at? tokens p :punct ":")
       (at? tokens (inc p) :newline)
       (at? tokens (+ p 2) :indent)
       (at? tokens (+ p 3) :glyph "<:")))

(defn- parse-dispatch-payload-binding
  "Parse one payload binding entry inside `<: name(...)`:
     `let x`             → positional  → {:field nil :as \"x\"}
     `field: let x`      → named       → {:field \"field\" :as \"x\"}
   Rejects bare idents / other shapes — dispatch patterns require
   the `let` prefix so the reader sees discrimination-with-name-bind
   as distinct from ordinary function-call argument passing."
  [tokens pos]
  (cond
    ;; named form: `field: let x`
    (and (at? tokens pos :ident)
         (at? tokens (inc pos) :punct ":")
         (at? tokens (+ pos 2) :kw "let")
         (at? tokens (+ pos 3) :ident))
    (let [field (t-value (peek-tok tokens pos))
          as-nm (t-value (peek-tok tokens (+ pos 3)))]
      [{:field field :as as-nm} (+ pos 4)])

    ;; positional form: `let x`
    (and (at? tokens pos :kw "let")
         (at? tokens (inc pos) :ident))
    (let [as-nm (t-value (peek-tok tokens (inc pos)))]
      [{:field nil :as as-nm} (+ pos 2)])

    :else
    (fail! tokens pos
           (str "F69 L4: expected `let <name>` or `<field>: let <name>` inside "
                "a `<:` case pattern payload. Positional bindings without `let` "
                "are not supported — spec §3.7.1 requires discrimination-with-"
                "name-binding to be explicit."))))

(defn- parse-dispatch-pattern
  "Parse one L4a/b/c pattern:
     *                          → wildcard (2026-08-26: was `_`)
     name                       → case with no payload
     name(binding, binding...)  → case with payload bindings
     <int-lit>                  → int literal (L4c4)
     true / false               → bool literal (L4c4)

   Deferred to a follow-on: string / char literals (need NSString
   compare), leading-dot `.name(...)` (§3.7.2 target-typing)."
  [tokens pos]
  (cond
    ;; wildcard `*` (2026-08-26 unification: retired `_` in pattern
    ;; position — CXC isn't FP, we don't owe `_` a slot; `*` is the
    ;; language's uniform wildcard symbol).
    (at? tokens pos :glyph "*")
    [{:kind :wildcard} (inc pos)]

    ;; Retired form: `_` in case-pattern position. Migration hint.
    (and (at? tokens pos :ident) (= "_" (t-value (peek-tok tokens pos))))
    (fail! tokens pos
           (str "wildcard case-pattern is now `*` (was `_` pre-2026-08-26). "
                "Rewrite `<: _:` as `<: *:` and constructor `Foo(field: _)` "
                "as `Foo(field: *)`. `_` retired in pattern position."))

    ;; L4c4 — integer literal pattern
    (at? tokens pos :int)
    [{:kind :literal :lit-type :int :value (t-value (peek-tok tokens pos))}
     (inc pos)]

    ;; L4c4 — bool literal pattern
    (and (at? tokens pos :kw) (#{"true" "false"} (t-value (peek-tok tokens pos))))
    [{:kind :literal :lit-type :bool
      :value (= "true" (t-value (peek-tok tokens pos)))}
     (inc pos)]

    ;; F69 L4c5 — leading-dot shorthand `<: .name(...):` per spec §3.7.2.
    ;; Inside a `<:` block the scrutinee's type is the inferred target,
    ;; so `.name` is unambiguous — parse as a case pattern named
    ;; `name` (identical to bare `name`; the dot is documentary).
    (and (at? tokens pos :punct ".") (at? tokens (inc pos) :ident))
    (let [name (t-value (peek-tok tokens (inc pos)))
          p    (+ pos 2)]
      (cond
        (at? tokens p :punct "(")
        (loop [bindings [] p (inc p)]
          (cond
            (at? tokens p :punct ")")
            [{:kind :case :name name :bindings bindings :leading-dot? true} (inc p)]

            (at? tokens p :punct ",")
            (recur bindings (inc p))

            :else
            (let [[b p2] (parse-dispatch-payload-binding tokens p)]
              (recur (conj bindings b) p2))))

        :else
        [{:kind :case :name name :bindings [] :leading-dot? true} p]))

    ;; case pattern — starts with an ident
    (at? tokens pos :ident)
    (let [name    (t-value (peek-tok tokens pos))
          p       (inc pos)]
      (cond
        (at? tokens p :punct "(")
        (loop [bindings [] p (inc p)]
          (cond
            (at? tokens p :punct ")")
            [{:kind :case :name name :bindings bindings} (inc p)]

            (at? tokens p :punct ",")
            (recur bindings (inc p))

            :else
            (let [[b p2] (parse-dispatch-payload-binding tokens p)]
              (recur (conj bindings b) p2))))

        :else
        [{:kind :case :name name :bindings []} p]))

    :else
    (fail! tokens pos
           (str "F69 L4: expected a case pattern (name or `name(...)`), "
                "literal (int / bool), or wildcard `_` at start of `<:` "
                "dispatch arm."))))

(defn- parse-dispatch-arm-body
  "Parse the body of a `<: pattern:` arm. Two shapes:
     - inline single expression on the same line: `<: pat: <expr>`
     - indented block on subsequent lines: `<: pat:\\n    <stmts>`
   Returns [body-node new-pos] where body-node is either an :expr-stmt-
   wrapped expression or a :block node."
  [tokens pos]
  (let [[_ p] (expect tokens pos :punct ":")]
    (cond
      ;; indented block form
      (and (at? tokens p :newline)
           (at? tokens (inc p) :indent))
      (let [[block p2] (parse-block tokens (inc p))]
        [block p2])

      ;; inline expression form
      :else
      (let [[e p2] (parse-expr tokens p)]
        [e p2]))))

(defn- parse-dispatch-arm
  "Parse one `<: pattern[, pattern...] [when: <guard>]: body` arm.

   F69 L4c2 — comma-separated multi-pattern arms per spec §3.7:
   `<: red, orange, yellow: <body>` runs the same body when the
   scrutinee matches any listed pattern. `:patterns` is always a
   non-empty vector; single-pattern arms have length 1. The legacy
   `:pattern` field is preserved for downstream code and holds the
   first pattern.

   F69 L4c3 — optional `when: <guard-expr>` clause per spec §3.7
   patterns list: an arm fires only when its pattern list matches AND
   the guard evaluates truthy. Reuses the class-body-transition
   `when:` guard keyword (spec §4) — one guard keyword across the
   language, not two. Guard AND-composes with the pattern condition
   at lower time; guarded arms do NOT count toward exhaustiveness
   coverage (their guard might be false at runtime)."
  [tokens pos]
  (let [[_ p]        (expect tokens pos :glyph "<:")
        [pattern p]  (parse-dispatch-pattern tokens p)]
    (loop [patterns [pattern] p p]
      (cond
        (at? tokens p :punct ",")
        (let [[nxt p2] (parse-dispatch-pattern tokens (inc p))]
          (recur (conj patterns nxt) p2))

        :else
        (let [[guard p]  (if (and (at? tokens p :kw "when")
                                  (at? tokens (inc p) :punct ":"))
                           (parse-expr tokens (+ p 2))
                           [nil p])
              [body p2]  (parse-dispatch-arm-body tokens p)]
          [{:pattern  (first patterns)
            :patterns patterns
            :guard    guard
            :body     body}
           p2])))))

(defn- parse-dispatch-block
  "Parse a dispatch block: consumes `:` `\\n` INDENT (arm | newline)* DEDENT.
   `scrutinee` is the already-parsed expression that heads the block;
   `pos` is at the `:` token that opens the block."
  [scrutinee tokens pos]
  (let [[_ p] (expect tokens pos :punct ":")
        [_ p] (expect tokens p :newline)
        [_ p] (expect tokens p :indent)]
    (loop [arms [] p p]
      (cond
        (at? tokens p :dedent)
        [{:node :dispatch :scrutinee scrutinee :arms arms} (inc p)]

        (at? tokens p :newline)
        (recur arms (inc p))

        (at? tokens p :glyph "<:")
        (let [[arm p2] (parse-dispatch-arm tokens p)
              p3       (skip-newlines tokens p2)]
          (recur (conj arms arm) p3))

        :else
        (fail! tokens p
               (str "F69 L4: expected `<: <pattern>: <body>` arm or dedent "
                    "inside a dispatch block."))))))

(declare parse-multi-arm-dispatch)

(defn- parse-expr [tokens pos]
  (let [[e p] (parse-binop tokens pos 1)]
    (if (looks-like-dispatch-tail? tokens p)
      (parse-dispatch-block e tokens p)
      [e p])))

(defn- parse-primary [tokens pos]
  (let [t (peek-tok tokens pos)]
    (case (t-kind t)
      :int    [{:node :lit-int    :value (t-value t)} (inc pos)]
      :float  [{:node :lit-float  :value (t-value t)} (inc pos)]
      :string [{:node :lit-string :value (t-value t)} (inc pos)]
      :char   [{:node :lit-char   :value (t-value t)} (inc pos)]
      :kw     (case (t-value t)
                "self"  [{:node :self}    (inc pos)]
                "nil"   [{:node :lit-nil} (inc pos)]
                "true"  [{:node :lit-bool :value true}  (inc pos)]
                "false" [{:node :lit-bool :value false} (inc pos)]
                ;; F18 L2 — `do:` at expression position produces a
                ;; value-yielding block (spec §3.8.2). Two shapes:
                ;;   Inline:    do: <expr-or-stmt>
                ;;   Indented:  do:\n    <stmts>\n
                ;; Value = value of the final expression; if the final
                ;; statement is not an :expr-stmt (e.g. bare let), the
                ;; block has type Unit and emits `((void)0)` per the
                ;; open-Q3 ratified decision (decisions-frozen §F18).
                ;; Distinct from the retired transition `do:` sub-block
                ;; (F70 I3 Stage 3, 2026-08-19).
                "do"    (parse-do-block-expr tokens pos)
                ;; R6 (2026-08-31, decisions-frozen-2026-08-31-glyphs.md)
                ;; retired expression-position `if <cond>: <then> else:
                ;; <else>` categorically. Use `||: <cond> : <then> ||:
                ;; else : <else>` — multi-arm dispatch (`||:`) is the sole
                ;; expression-position branching form at v1.0. `if let`
                ;; remains as statement-form only (F39 L7).
                "if"
                (if (at? tokens (inc pos) :kw "let")
                  (fail! tokens pos
                         (str "expression-position `if let ...` is not "
                              "supported. `if let` ships at statement "
                              "position only (F39 L7). Rewrite as an outer "
                              "`if let` statement."))
                  (fail! tokens pos
                         (str "expression-position `if <cond>: <then> "
                              "else: <else>` retired 2026-08-31 (R6). "
                              "Rewrite as `||: <cond> : <then> ||: else "
                              ": <else>` — multi-arm dispatch is the sole "
                              "expression-position branching form at v1.0. "
                              "For if/else chains use `||: c1 : b1 ||: c2 "
                              ": b2 ||: else : b3`. See "
                              "decisions-frozen-2026-08-31-glyphs.md §R6.")))
                ;; 2d-iii (2026-09-02) — `raise <TypeName>(<args>)` throws
                ;; a `_Cxc_R_<TypeName>` boxed reason record. TypeName is
                ;; the identifier of a declared record (verified in lower).
                ;; Args are the record's ctor args — positional or named,
                ;; same shape as a plain `T(...)` record construction.
                "raise"
                (let [p (inc pos)
                      _ (when-not (at? tokens p :ident)
                          (fail! tokens p
                                 "raise: expected a record TypeName after `raise`"))
                      type-name (t-value (peek-tok tokens p))
                      _ (when-not (at? tokens (inc p) :punct "(")
                          (fail! tokens (inc p)
                                 "raise: expected `(` after TypeName — raise requires a ctor-call form (`raise T(field: value, ...)`)"))
                      [args p2] (parse-args-parens tokens (inc p))]
                  [{:node :raise
                    :type-name type-name
                    :args args
                    :line (:line t) :col (:col t)}
                   p2])
                (fail! tokens pos (str "unexpected keyword " (t-value t) " in expression")))
      :ident  ;; ident, maybe followed by lambda arrow => in parse-lambda-or-ident
      (let [ident-node {:node :ident :name (t-value t)}]
        (if (at? tokens (inc pos) :glyph "=>")
          (let [[body p] (parse-expr tokens (+ pos 2))]
            [{:node :lambda :params [{:name (t-value t)}] :body body} p])
          [ident-node (inc pos)]))
      :punct  (case (t-value t)
                "(" (cond
                      ;; Layer 6 Step 12 — zero-arg lambda `() => body`.
                      ;; Distinct from `(expr)` grouping via the `)` `=>`
                      ;; lookahead. Currently the only escaping-block
                      ;; grammar path — `Block` at MVP is zero-arg void
                      ;; return, so a zero-param lambda is what matches.
                      (and (at? tokens (inc pos) :punct ")")
                           (at? tokens (+ pos 2) :glyph "=>"))
                      (let [[body p] (parse-expr tokens (+ pos 3))]
                        [{:node :lambda :params [] :body body} p])

                      :else
                      (let [[e p] (parse-expr tokens (inc pos))
                            [_ p2] (expect tokens p :punct ")")]
                        [e p2]))
                "[" (let [[items p] (parse-list-literal tokens pos)]
                      [items p])
                (fail! tokens pos "unexpected punctuation in expression"))
      :glyph  (case (t-value t)
                ;; R6 (2026-08-31, decisions-frozen-2026-08-31-glyphs.md) —
                ;; expression-position multi-arm dispatch: `||: <guard>: <body>`
                ;; chain. First truthy guard's body is the value. `else` is
                ;; the sole catch-all marker. See parse-multi-arm-dispatch.
                "||:" (parse-multi-arm-dispatch tokens pos)
                ;; A6 (2026-09-03) — expression-position sync emit:
                ;; `|> receiver.method(args)` as an expression (value
                ;; is void; usable inside lambda bodies where the
                ;; caller wants side-effecting emits). Same lowering
                ;; as the statement form minus the recovery-arm chain
                ;; (arms are a statement-level construct).
                "|>" (let [[e p] (parse-expr tokens (inc pos))]
                       (cond
                         (and (= :call (:node e))
                              (= :dot  (:node (:callee e))))
                         [{:node :emit
                           :async? false
                           :target (:target (:callee e))
                           :msg    (:field  (:callee e))
                           :args   (:args e)} p]

                         (and (= :call (:node e))
                              (= :ident (:node (:callee e))))
                         [{:node :emit
                           :async? false
                           :target nil
                           :msg    (:name (:callee e))
                           :args   (:args e)} p]

                         :else
                         (fail! tokens pos
                                (str "expression-position `|>` expected "
                                     "`|> receiver.method(args)`"))))
                (fail! tokens pos (str "unexpected glyph `" (t-value t) "` in expression")))
      (fail! tokens pos "expected an expression"))))

(declare parse-statement)

(defn- parse-multi-arm-arm
  "Parse one `||: <guard>: <body>` arm. `<guard>` is either the `else`
   keyword (catch-all) or an ordinary expression. `<body>` is one
   expression on the same line."
  [tokens pos]
  (let [[_ p]     (expect tokens pos :glyph "||:")
        [guard p] (if (at? tokens p :kw "else")
                    [{:node :else-marker} (inc p)]
                    (parse-expr tokens p))
        [_ p]     (expect tokens p :punct ":")
        [body p]  (parse-expr tokens p)]
    [{:guard guard :body body} p]))

(defn- parse-multi-arm-dispatch
  "R6 (2026-08-31) — expression-position multi-arm dispatch. Chain of
   `||: <guard>: <body>` arms; first truthy guard's body is the
   expression value. `else` is the sole catch-all guard marker.

   Two forms supported:
     (a) Inline chain: `||: g1 : b1 ||: g2 : b2 ||: else : bN`
     (b) Block chain:  first arm inline, subsequent arms after NEWLINE
                       + optional INDENT at same or deeper indentation.
                       If an INDENT was opened by the first-arm
                       continuation, the closing DEDENT is consumed
                       here.

   At least one arm required. Emits
   `{:node :multi-arm-dispatch :arms [{:guard g :body b} ...]}`
   where `g` is either an expression AST node or `{:node :else-marker}`
   for the `else` arm."
  [tokens pos]
  (loop [arms         []
         p            pos
         opened-indent? false]
    (let [after-newline (if (at? tokens p :newline) (inc p) p)
          opened?       (or opened-indent?
                            (and (not= after-newline p)
                                 (at? tokens after-newline :indent)))
          after-indent  (if (and (not opened-indent?)
                                 (not= after-newline p)
                                 (at? tokens after-newline :indent))
                          (inc after-newline)
                          after-newline)]
      (if (at? tokens after-indent :glyph "||:")
        (let [[arm p2] (parse-multi-arm-arm tokens after-indent)]
          (recur (conj arms arm) p2 opened?))
        (if (seq arms)
          (let [;; If we opened an INDENT for the chain, consume the
                ;; matching DEDENT (peek past optional NEWLINE first).
                p-final
                (if opened-indent?
                  (let [q (if (at? tokens p :newline) (inc p) p)]
                    (if (at? tokens q :dedent)
                      (inc q)
                      (fail! tokens q
                             "expected DEDENT after multi-arm `||:` chain")))
                  p)]
            [{:node :multi-arm-dispatch :arms arms} p-final])
          (fail! tokens pos
                 "expected `||: <guard>: <body>` arm to start multi-arm dispatch chain"))))))

(defn- parse-do-block-expr
  "F18 L2 — `do:` at expression position. Called from parse-primary
   when it sees the `do` keyword. Consumes the `:` and either:
     (a) an inline single statement (same line), or
     (b) a NEWLINE INDENT stmt* DEDENT block.
   Emits `{:node :do-block-expr :stmts [...]}`. Semantics:
     - the final `:expr-stmt`'s expr is the block's value
     - if the final statement is not an :expr-stmt (bare let, void
       call, etc.), the block has type Unit
     - empty body is legal (Unit) per open-Q3 ratified decision.
   `return`/`break`/`continue` in body escape the enclosing function/
   loop as usual (spec §3.8.2, ratified open-Q2)."
  [tokens pos]
  (let [[_ p]  (expect tokens pos :kw "do")
        [_ p]  (expect tokens p :punct ":")]
    (cond
      ;; Indented body: NEWLINE INDENT stmt* DEDENT
      (and (at? tokens p :newline)
           (at? tokens (inc p) :indent))
      (let [[_ p]  (expect tokens p :newline)
            [_ p]  (expect tokens p :indent)]
        (loop [stmts [] p p]
          (cond
            (at? tokens p :dedent)
            [{:node :do-block-expr :stmts stmts} (inc p)]

            (at? tokens p :newline)
            (recur stmts (inc p))

            :else
            (let [[s p2] (parse-statement tokens p)
                  p3     (skip-newlines tokens p2)]
              (recur (conj stmts s) p3)))))

      ;; Inline single-stmt body: `do: <stmt>`.
      :else
      (let [[s p] (parse-statement tokens p)]
        [{:node :do-block-expr :stmts [s]} p]))))

(defn- parse-list-literal [tokens pos]
  ;; assumes at "["
  (let [[_ p] (expect tokens pos :punct "[")]
    (if (at? tokens p :punct "]")
      [{:node :list :items []} (inc p)]
      (loop [items [] p p]
        (let [[e p2] (parse-expr tokens p)]
          (cond
            (at? tokens p2 :punct ",")
            (recur (conj items e) (inc p2))

            (at? tokens p2 :punct "]")
            [{:node :list :items (conj items e)} (inc p2)]

            :else (fail! tokens p2 "expected `,` or `]` in list literal")))))))

(defn- parse-args-parens
  "Parse (a: 1, b: 2, c) — positional or named args."
  [tokens pos]
  (let [[_ p] (expect tokens pos :punct "(")]
    (if (at? tokens p :punct ")")
      [[] (inc p)]
      (loop [args [] p p]
        (let [[arg p2]
              (if (and (at? tokens p :ident) (at? tokens (inc p) :punct ":"))
                (let [name (t-value (peek-tok tokens p))
                      [val p2] (parse-expr tokens (+ p 2))]
                  [{:kind :named :name name :value val} p2])
                (let [[val p2] (parse-expr tokens p)]
                  [{:kind :positional :value val} p2]))]
          (cond
            (at? tokens p2 :punct ",")
            (recur (conj args arg) (inc p2))

            (at? tokens p2 :punct ")")
            [(conj args arg) (inc p2)]

            :else (fail! tokens p2 "expected `,` or `)` in arg list")))))))

;; `parse-msg-args-braces` retired 2026-08-20 (F70 I5 Stage 3 hard
;; retirement). The `{...}` postfix branch in `parse-postfix` now
;; hard-rejects; callers migrated to `parse-msg-args-parens`.

(defn- parse-msg-args-parens
  "Parse (name: Type, name2: Type) — I5 message payload shape.
   Distinguished from a value-args call by parse-postfix's
   content-dispatch (see `looks-like-msg-payload-parens?`).
   Replaces the retired brace-payload form (F70 I5 Stage 3,
   2026-08-20).

   Track A/1 (2026-08-26) — a payload entry may omit its type
   (`name:` bareword) to leave the binding untyped; downstream
   defaults such fields to `id`/`Any`. Used for `<msg>_failed
   (reason:)` companion subscription where the subscriber unpacks
   the reason via a `<:` case-pattern block instead of declaring
   a type up front."
  [tokens pos]
  (let [[_ p] (expect tokens pos :punct "(")]
    (if (at? tokens p :punct ")")
      [[] (inc p)]
      (loop [args [] p p]
        (let [[name p2] (expect tokens p :ident)
              [_ p3]    (expect tokens p2 :punct ":")
              bareword? (or (at? tokens p3 :punct ",")
                            (at? tokens p3 :punct ")"))
              [ty p4]   (if bareword?
                          ;; Track A/1 bareword payload — synthesize
                          ;; `Any` so downstream sees a normal type-ref.
                          [{:node :type-ref :name "Any"} p3]
                          (parse-type tokens p3))
              entry     (cond-> {:name name :type ty}
                          bareword? (assoc :bareword? true))]
          (cond
            (at? tokens p4 :punct ",")
            (recur (conj args entry) (inc p4))

            (at? tokens p4 :punct ")")
            [(conj args entry) (inc p4)]

            :else (fail! tokens p4 "expected `,` or `)` in message shape")))))))

(defn- looks-like-msg-payload-parens?
  "At `pos` = `(`. Distinguish an I5 typed-params payload
   (`(name: Type, ...)`) from a value-args call
   (`(name: value, ...)` or `(positional, ...)`).

   Heuristic: (1) first arg has `<name>:` prefix, (2) value
   after `:` is a bare uppercase-starting ident, and (3) the
   token after that Type ident is NOT `(` or `.` — either
   would mean the uppercase ident is the head of a value
   expression, not a Type annotation (`OldBox(x: 1)` is a
   constructor call; `Direction.down` is an enum-case access
   per F16 L3, `Foo.bar()` is a static method call).

   Types in cxc conventionally use PascalCase (`Int`, `String`,
   `Player`, `Duration`); values use camelCase or literals.
   Empty parens `()` are treated as a call. Constructor calls
   with uppercase names (`Box(item: OldBox(x: 1))`) correctly
   fall through to the call branch because the inner uppercase
   ident is followed by `(`; enum-case values fall through
   because the uppercase ident is followed by `.`.

   Note: this is only invoked from the general postfix parser —
   receive-position parsing (`parse-msg-pattern`) has its own
   F67 L3 typed-params branch and doesn't rely on this."
  [tokens pos]
  (let [p (inc pos)]  ; skip `(`
    (cond
      (at? tokens p :punct ")") false

      (and (at? tokens p :ident)
           (at? tokens (inc p) :punct ":"))
      (let [after-colon (+ p 2)]
        (cond
          ;; Track A/1 (2026-08-26) — bareword payload entry
          ;; `(name:)` or `(name:, ...)`: an ident followed by
          ;; `:` then `,`/`)` cannot be a value-args call (the
          ;; value would be missing), so route to the typed-
          ;; params payload path. Downstream defaults such
          ;; fields to `id`/`Any`.
          (or (at? tokens after-colon :punct ")")
              (at? tokens after-colon :punct ","))
          true

          :else
          (let [t          (peek-tok tokens after-colon)
                after-type (peek-tok tokens (inc after-colon))]
            (and t
                 (= :ident (t-kind t))
                 (let [v (t-value t)]
                   (and (seq v)
                        (Character/isUpperCase (first v))
                        ;; F16 L3 — NOT followed by `(` (constructor
                        ;; call being passed as a value) or `.`
                        ;; (dotted access on the ident, e.g. enum
                        ;; case `Direction.down` or static member
                        ;; `Foo.bar()` — all value expressions).
                        (not (and after-type
                                  (= :punct (t-kind after-type))
                                  (contains? #{"(" "."} (t-value after-type))))))))))

      :else false)))

;; ---------------------------------------------------------------------------
;; Field declarations, function/method declarations
;; ---------------------------------------------------------------------------

(def ^:private known-primitive-value-type-names
  "Type names that are known to be value/primitive at parse time. Used
   by F13 L1 to reject `weak`/`unowned` on obvious primitives without
   waiting for env resolution. Not exhaustive — records/enums are
   caught at lower-time by a type-kind check."
  #{"Int" "Int8" "Int16" "Int32" "Int64"
    "UInt" "UInt8" "UInt16" "UInt32" "UInt64"
    "Float" "Double" "Bool" "Char" "Byte" "Bytes" "Unit" "Never"})

(declare parse-foreign-signature)

(defn- parse-field
  "name: [<modifier>] Type [= default]

   F13 L1 — optional `weak` / `unowned` reference modifier appears
   between `:` and the type (spec §6.14). `weak` implies nil-
   observability so an accompanying `?` suffix is a grammar error;
   both modifiers are structurally rejected on known-primitive
   value types at parse time (records/enums caught at lower-time)."
  [tokens pos]
  (let [[fname p]     (expect tokens pos :ident)
        [_ p]         (expect tokens p :punct ":")
        [modifier p]  (cond
                        (at? tokens p :kw "weak")    [:weak    (inc p)]
                        (at? tokens p :kw "unowned") [:unowned (inc p)]
                        :else                        [nil      p])
        modifier-pos  (dec p)
        modifier-nm   (when modifier (clojure.core/name modifier))
        [ty p]        (parse-type tokens p)
        ;; FL-11 (2026-09-02) — function-type extension at field position.
        ;; When the field type is followed by `->`, parse the return type
        ;; and wrap into a `:foreign-fn-type` node — same shape the
        ;; foreign-signature parser produces. Enables plugin authors to
        ;; write `_write: (Int32, Ptr of Byte, SizeT) -> SsizeT =
        ;; plugin.get("write")` as a symbolic alias for the resolved
        ;; foreign symbol. Right-associative to mirror foreign-fn-type.
        [ty p]        (if (at? tokens p :glyph "->")
                        (let [[right p2] (parse-foreign-signature tokens (inc p))]
                          [{:node :foreign-fn-type :args ty :return right} p2])
                        [ty p])
        _             (when modifier
                        (cond
                          ;; F39 Landing 5a (2026-08-20) — the pre-L5a
                          ;; `weak T?` / `unowned T?` intercept fired
                          ;; before the unified `:cxc-f39-nullable-
                          ;; suffix-banned` diagnostic could reach the
                          ;; user. Retired: the unified diagnostic
                          ;; fires at lower-time for any `T?` position,
                          ;; carrying the current `?`-banned message.
                          ;; The `weak`/`unowned` invalidity is subsumed
                          ;; (dropping `?` yields the modifier + bare
                          ;; `T`, which is the correct form).

                          (= :type-nonnil (:node ty))
                          (fail! tokens modifier-pos
                                 (str "F13 L1: `" modifier-nm " "
                                      (or (:name (:inner ty)) "T") "!` is invalid — "
                                      "`!` is the non-nil contract, but a `weak` "
                                      "reference is observably nil once its target "
                                      "deallocates. `unowned` names non-owning "
                                      "strong references (undefined behavior on "
                                      "dangle), which is orthogonal to the `!` "
                                      "contract. Drop the `!` (spec §6.14)."))

                          (and (= :type-ref (:node ty))
                               (contains? known-primitive-value-type-names
                                          (:name ty)))
                          (fail! tokens modifier-pos
                                 (str "F13 L1: `" modifier-nm " " (:name ty)
                                      "` is invalid — reference modifiers apply "
                                      "to reference types (atoms, `String`); `"
                                      (:name ty) "` is a value/primitive type "
                                      "(spec §6.14). Drop the modifier."))

                          (= :type-of (:node ty))
                          (fail! tokens modifier-pos
                                 (str "F13 L1: `" modifier-nm " " (:outer ty)
                                      " of ...` is invalid — collections don't "
                                      "propagate reference modifiers to their "
                                      "elements. Purpose-built weak-element "
                                      "containers land as a follow-on (spec §6.14)."))

                          (or (= :type-tuple (:node ty))
                              (= :type-named-tuple (:node ty)))
                          (fail! tokens modifier-pos
                                 (str "F13 L1: `" modifier-nm "` on a tuple "
                                      "type is invalid — reference modifiers apply "
                                      "only to single reference types (atoms, "
                                      "`String`), not to compound types "
                                      "(spec §6.14)."))))
        [dflt p]      (if (at? tokens p :glyph "=")
                        (let [[e p2] (parse-expr tokens (inc p))]
                          [e p2])
                        [nil p])
        base-field    {:node :field :name fname :type ty :default dflt}]
    [(cond-> base-field
       modifier (assoc :modifier modifier)) p]))

(defn- parse-param
  "For function params & atom constructor params: name: Type [= default]."
  [tokens pos]
  (cond
    (at? tokens pos :kw "self")
    [{:node :param :name "self" :type nil :default nil} (inc pos)]

    :else (parse-field tokens pos)))

;; ---------------------------------------------------------------------------
;; Track A/1 (2026-08-26) — receive-payload params accept a bareword form.
;;
;; At receive position (F67 L3 typed-params receive; specifically the target
;; of a `<msg>_failed(reason:)` companion subscription), a payload param may
;; omit its type declaration entirely:
;;
;;   * <: player.take_damage_failed(reason:) -> self
;;       then:
;;           <: reason:
;;               <: MyError(msg: let m): print(m)
;;               <: *: print("other")
;;
;; A bareword `name:` produces `{:node :field :name X :type nil}`. Downstream
;; (`augment-atom-env-with-companions`) already treats a nil-typed subscriber
;; reason as `Any`, which resolves to `id` at the wire — so the case-pattern
;; block in the body dispatches on an id-typed scrutinee. Existing typed form
;; `reason: T` continues to parse via the ordinary `parse-field` path; Track
;; A/3 handles the retirement of the subscriber-declared type slot.
;;
;; Scope: bareword accepted ONLY at receive position. Atom-constructor and
;; function params still require `name: Type` via `parse-field`.
;; ---------------------------------------------------------------------------

(defn- parse-receive-payload-param
  "Parse one receive-payload param. Accepts:
     name: Type [= default]   — typed form (routes to parse-field)
     name:                    — bareword form (Track A/1) — synthesizes
                                a `{:node :type-ref :name \"Any\"}` type
                                so downstream consumers see a normal
                                typed payload; the `Any` string resolves
                                to `id` at the wire.

   Track A/3 (2026-08-26) — bareword-form params carry `:bareword? true`
   so downstream (companion-demand collection) can distinguish them
   from a user who explicitly declared `reason: Any` and reject the
   typed `reason: T` form on `<msg>_failed` subscribers with a clean
   migration diagnostic."
  [tokens pos]
  (cond
    ;; Bareword: `name:` followed by `,`/`)` — no type token.
    (and (at? tokens pos :ident)
         (at? tokens (inc pos) :punct ":")
         (or (at? tokens (+ pos 2) :punct ",")
             (at? tokens (+ pos 2) :punct ")")))
    (let [fname (t-value (peek-tok tokens pos))]
      [{:node      :field
        :name      fname
        :type      {:node :type-ref :name "Any"}
        :bareword? true
        :default   nil}
       (+ pos 2)])

    :else (parse-field tokens pos)))

(defn- parse-receive-payload-param-list
  "Like `parse-param-list-parens`, but each entry may be a bareword
   `name:` (Track A/1) in addition to the typed `name: Type` form."
  [tokens pos]
  (let [[_ p] (expect tokens pos :punct "(")]
    (if (at? tokens p :punct ")")
      [[] (inc p)]
      (loop [ps [] p p]
        (let [[param p2] (parse-receive-payload-param tokens p)]
          (cond
            (at? tokens p2 :punct ",")
            (recur (conj ps param) (inc p2))

            (at? tokens p2 :punct ")")
            [(conj ps param) (inc p2)]

            :else (fail! tokens p2
                         "expected `,` or `)` in receive-payload param list")))))))

(defn- parse-param-list-parens [tokens pos]
  (let [[_ p] (expect tokens pos :punct "(")]
    (if (at? tokens p :punct ")")
      [[] (inc p)]
      (loop [ps [] p p]
        (let [[param p2] (parse-param tokens p)]
          (cond
            (at? tokens p2 :punct ",")
            (recur (conj ps param) (inc p2))

            (at? tokens p2 :punct ")")
            [(conj ps param) (inc p2)]

            :else (fail! tokens p2 "expected `,` or `)` in param list")))))))

(declare parse-block)

(defn- parse-class-function-decl
  "F37 L6b (2026-08-24) — spec §3.4.6 static-fn shape:
     class function name(params) -> RetType: <block>

   Distinguished from instance methods (`name :- expr`) by the
   `class function` prefix. Return type is required per spec (§3.4.6
   `class function from(source: Bytes) -> DecodeOutcome<Self>`) but
   parsed as optional here to match `parse-function-decl`'s
   convention — enforcement lands at lower-time.

   `class` is an :ident (not reserved word — F68 L6b freed the token
   for identifier use); `function` remains reserved. Parser detects
   the pair at atom-body dispatch."
  [tokens pos]
  (let [[cls-val p] (expect tokens pos :ident)
        _           (when-not (= "class" cls-val)
                      (fail! tokens pos
                             "expected `class` at start of class-function decl"))
        [_ p]      (expect tokens p :kw "function")
        [name p]   (expect tokens p :ident)
        [params p] (parse-param-list-parens tokens p)
        [ret p]    (if (at? tokens p :glyph "->")
                     (parse-type tokens (inc p))
                     [nil p])
        [_ p]      (expect tokens p :punct ":")
        [_ p]      (expect tokens p :newline)
        [body p]   (parse-block tokens p)]
    [{:node :class-function :name name :params params
      :return-type ret :body body} p]))

(defn- at-def-method?
  "Look-ahead for the spec-canonical `def name(...)` method form
   inside a def-body or methods: section — distinguishes from the
   retired F34 nested-slot form `def name: TypeName` (paren vs
   colon after the identifier)."
  [tokens pos]
  (and (at? tokens pos :kw "def")
       (at? tokens (inc pos) :ident)
       (at? tokens (+ pos 2) :punct "(")))

;; ---------------------------------------------------------------------------
;; Statements & blocks
;;   A statement (in method bodies etc.) is currently one of:
;;     return <expr>
;;     <expr> (bare expression statement)
;;
;;   A block is delimited by :indent … :dedent, and contains one-or-more
;;   statements terminated by :newline.
;; ---------------------------------------------------------------------------

(defn- parse-emit-message
  "Parse `<msg>[(args)]` following a send glyph."
  [tokens pos]
  (let [[name p] (expect tokens pos :ident)
        [args p] (if (at? tokens p :punct "(")
                   (parse-args-parens tokens p)
                   [[] p])]
    [{:msg name :args args} p]))

(declare parse-statement parse-or-arms)

(defn- parse-while-statement
  "F17 L1 (spec §3.8.3, §4.20.3) — statement-position `while` loop.
     while <cond>: <body>
   Body forms:
     - inline:  `while <cond>: <single-stmt>`
     - block:   `while <cond>:\\n    <stmts...>` (indented)
   Loop-value return (`while` as expression yielding `Bool`) is L3.
   L1 lands the statement form only."
  [tokens pos]
  (let [[_ p]    (expect tokens pos :kw "while")
        [cnd p]  (parse-expr tokens p)
        [_ p]    (expect tokens p :punct ":")
        [body p] (if (at? tokens p :newline)
                   (let [[_ p]  (expect tokens p :newline)
                         [_ p]  (expect tokens p :indent)]
                     (loop [stmts [] p p]
                       (cond
                         (at? tokens p :dedent)
                         [{:node :block :stmts stmts} (inc p)]
                         (at? tokens p :newline)
                         (recur stmts (inc p))
                         :else
                         (let [[s p2] (parse-statement tokens p)
                               p3     (skip-newlines tokens p2)]
                           (recur (conj stmts s) p3)))))
                   (let [[s p2] (parse-statement tokens p)]
                     [{:node :block :stmts [s]} p2]))]
    [{:node :while-stmt :cond cnd :body body} p]))

(defn- parse-let-statement
  "F18 L1 (spec §2.5) — local `let` binding at statement position.
     let <ident> = <expr>
     let <ident> : <type> = <expr>
   Distinct from the transition `let:` sub-block (which uses
   `let: <name>: <expr>` — see `parse-let-clause`). This form is
   usable anywhere a statement is accepted (do:/then:-clause bodies,
   method bodies, function bodies).

   A4 (2026-09-03) — accepts an optional `or T(fields): fallback`
   arm chain following the RHS (NEWLINE + `or` at same indent), so
   a let can bind either the try's value or an arm body's value
   without a separate try:/catch: construct. Grammar reuses
   `parse-or-arms`; lower wraps the RHS in @try/@catch that
   captures the appropriate value into the binding."
  [tokens pos]
  (let [[_ p]     (expect tokens pos :kw "let")
        [nm p]    (expect tokens p :ident)
        [ty p]    (if (at? tokens p :punct ":")
                    (parse-type tokens (inc p))
                    [nil p])
        [_ p]     (expect tokens p :glyph "=")
        [rhs p]   (parse-expr tokens p)
        [arms p2] (parse-or-arms tokens p)
        node      (cond-> {:node :let-stmt :name nm :type ty :expr rhs}
                    (seq arms) (assoc :arms arms))]
    [node p2]))

(defn- parse-if-let-statement
  "F39 L7 (spec §3.6.1 lines 3693-3705) — scoped unwrap statement.
     if let <ident> [: <type>] = <expr>[, let <ident> [: <type>] = <expr>]* :
         <block>
     [else:
         <block>]
   Body forms:
     - block: `if let x = maybe:\\n    <stmts>` (indented)
     - inline: `if let x = maybe: <single-stmt>` (same line)
   Each binding wraps the true-branch scope with a nil-guard on the RHS
   and binds the unwrapped local (raw `T` for value-Optional, non-nil
   pointer for reference). Guards short-circuit left-to-right: later
   bindings' RHS aren't evaluated when an earlier one is nil.

   Expression form (`if let` yielding a value) is deferred to K5. L7
   ships statement form only; K5 promotion is free — the AST already
   holds the true / else blocks."
  [tokens pos]
  (let [[_ p]    (expect tokens pos :kw "if")
        parse-one-binding
        (fn [p]
          (let [[_ p]  (expect tokens p :kw "let")
                [nm p] (expect tokens p :ident)
                [ty p] (if (and (at? tokens p :punct ":")
                                (not (at? tokens (inc p) :newline))
                                (not (at? tokens (inc p) :glyph "=")))
                         (parse-type tokens (inc p))
                         [nil p])
                [_ p]  (expect tokens p :glyph "=")
                [rhs p] (parse-expr tokens p)]
            [{:name nm :type ty :expr rhs} p]))
        [bindings p]
        (loop [bindings [] p p]
          (let [[b p2] (parse-one-binding p)
                bs     (conj bindings b)]
            (if (at? tokens p2 :punct ",")
              (recur bs (inc p2))
              [bs p2])))
        [_ p]    (expect tokens p :punct ":")
        [then p] (if (at? tokens p :newline)
                   (let [[_ p] (expect tokens p :newline)
                         [_ p] (expect tokens p :indent)]
                     (loop [stmts [] p p]
                       (cond
                         (at? tokens p :dedent)
                         [{:node :block :stmts stmts} (inc p)]
                         (at? tokens p :newline)
                         (recur stmts (inc p))
                         :else
                         (let [[s p2] (parse-statement tokens p)
                               p3     (skip-newlines tokens p2)]
                           (recur (conj stmts s) p3)))))
                   (let [[s p2] (parse-statement tokens p)]
                     [{:node :block :stmts [s]} p2]))
        p        (skip-newlines tokens p)
        [else-blk p]
        (if (and (at? tokens p :kw "else")
                 (at? tokens (inc p) :punct ":"))
          (let [p (+ p 2)]
            (if (at? tokens p :newline)
              (let [[_ p] (expect tokens p :newline)
                    [_ p] (expect tokens p :indent)]
                (loop [stmts [] p p]
                  (cond
                    (at? tokens p :dedent)
                    [{:node :block :stmts stmts} (inc p)]
                    (at? tokens p :newline)
                    (recur stmts (inc p))
                    :else
                    (let [[s p2] (parse-statement tokens p)
                          p3     (skip-newlines tokens p2)]
                      (recur (conj stmts s) p3)))))
              (let [[s p2] (parse-statement tokens p)]
                [{:node :block :stmts [s]} p2])))
          [nil p])]
    [{:node :if-let :bindings bindings :then then :else else-blk} p]))

(defn- parse-or-arm-pattern
  "Parse the pattern portion of one `or ...:` arm — the tokens between
   the `or` keyword and the trailing `:`. Returns [pattern new-pos].
   Three shapes:
     `or:`                bare      → nil
     `or <ident>:`        bind      → {:kind :bind :name S}
     `or T(bindings):`    typed     → {:kind :case :name T
                                        :bindings [{:field <str-or-nil>
                                                    :as    <str>} ...]}
   Distinguisher between bind and typed: presence of `(` immediately
   after the ident. An empty-parens `or T():` is a no-payload type match
   (matches Track A's `<: T():` shape). Parens-less `or T:` is treated
   as a bind — the parser does not do capitalization-based dispatch."
  [tokens pos]
  (cond
    ;; bare `or:`
    (at? tokens pos :punct ":")
    [nil pos]

    ;; `or <ident>(...):` — typed match, optionally destructuring
    (and (at? tokens pos :ident)
         (at? tokens (inc pos) :punct "("))
    (let [name (t-value (peek-tok tokens pos))]
      (loop [bindings [] p (+ pos 2)]
        (cond
          (at? tokens p :punct ")")
          [{:kind :case :name name :bindings bindings} (inc p)]

          (at? tokens p :punct ",")
          (recur bindings (inc p))

          :else
          (let [[b p2] (parse-dispatch-payload-binding tokens p)]
            (recur (conj bindings b) p2)))))

    ;; `or <ident>:` — bind
    (at? tokens pos :ident)
    (let [name (t-value (peek-tok tokens pos))]
      [{:kind :bind :name name} (inc pos)])

    :else
    (fail! tokens pos
           (str "Track B/1: expected `or:` (bare), `or <ident>:` (bind), "
                "or `or T(<bindings>):` (typed match) after the `or` keyword "
                "in a sync-send recovery arm."))))

(defn- parse-or-arms
  "Chain-parse `or` arms following a `|>` sync send. The first arm
   follows the sync send's expression, separated by NEWLINE; subsequent
   arms follow their predecessor's block DEDENT with no leading
   NEWLINE. Peeks past an optional NEWLINE for `or` — if found, consumes
   one arm and loops. Returns [arms new-pos] where `arms` is a possibly-
   empty vector of `{:pattern p :body stmts}`. The returned pos includes
   any NEWLINEs consumed so downstream parsers see the same shape they
   did before the chain."
  [tokens pos]
  (loop [arms [] p pos]
    (let [after-newline (if (at? tokens p :newline) (inc p) p)]
      (if (at? tokens after-newline :kw "or")
        (let [p            (inc after-newline)
              [pattern p2] (parse-or-arm-pattern tokens p)
              [_ p3]       (expect tokens p2 :punct ":")
              _            (when-not (and (at? tokens p3 :newline)
                                          (at? tokens (inc p3) :indent))
                             (fail! tokens p3
                                    (str "Track B/1: `or` arm requires an indented "
                                         "block body — inline single-expression bodies "
                                         "are not supported for recovery arms.")))
              [block p4]   (parse-block tokens (inc p3))]
          (recur (conj arms {:pattern pattern :body (:stmts block)}) p4))
        [arms p]))))

(defn- parse-statement
  "One statement:
     return <expr>
     let <name> [: <type>] = <expr>           — F18 L1 local binding
     if let <name> [: <type>] = <expr>[, ...] :
         <block>
     [else: <block>]                          — F39 L7 scoped unwrap
     :> <msg>[(args)]                        — untargeted async emit
     |> <msg>[(args)]                        — untargeted sync emit
     <expr> :> <msg>[(args)]                 — targeted async send
     <expr> |> <msg>[(args)]                 — targeted sync send
     for <var> in <expr>: <body>             — sugar → <expr>.for_each(<var> => <body>)
     <expr>                                  — bare expression"
  [tokens pos]
  (cond
    (at? tokens pos :kw "return")
    (let [[e p] (parse-expr tokens (inc pos))]
      [{:node :return :expr e} p])

    ;; F39 L7 — `if let <ident> [: T] = <expr> [, ...]:  <block> [else: <block>]`
    ;; scoped unwrap statement. Bare `if <expr>: <block>` (without `let`)
    ;; is deferred to K5 (`if` as expression) — at L7 the parser accepts
    ;; ONLY the `if let` shape, giving a targeted diagnostic for other
    ;; `if` shapes (kept as a single reject site rather than proliferated
    ;; through the dispatch).
    (at? tokens pos :kw "if")
    (if (at? tokens (inc pos) :kw "let")
      (parse-if-let-statement tokens pos)
      (fail! tokens pos
             (str "F39 L7: only `if let <ident> = <expr>: <block>` is supported at "
                  "statement position today. Bare `if <cond>: <block>` (K5 statement "
                  "form) is not yet landed. Rewrite via `if let` (nil-guarded unwrap), "
                  "an own-line `|:` proposition on a transition arm, or an expression-position `<:` "
                  "dispatch block for pattern-matched branching.")))

    ;; F18 L1 — a `let` at statement position followed by `<ident> [: T] =`
    ;; is the local-binding form. Distinct from the transition `let:` sub-
    ;; block (which starts `let :`). Disambiguation: local binding always
    ;; has `ident` after `let`; sub-block always has `:` after `let`.
    (and (at? tokens pos :kw "let")
         (at? tokens (inc pos) :ident))
    (parse-let-statement tokens pos)

    (at? tokens pos :glyph ":>")
    ;; `:>` is always self-broadcast — no receiver. Keep the bespoke
    ;; parse-emit-message shape (bare-prefix `:> msg[(args)]`).
    (let [[m p] (parse-emit-message tokens (inc pos))]
      [{:node :emit :async? true :target nil :msg (:msg m) :args (:args m)} p])

    (at? tokens pos :glyph "|>")
    ;; F70 I6: prefix `|>` accepts a call-expression RHS.
    ;;   New form: |> receiver.method(args)     — I6 canonical
    ;;   Legacy:   |> msg(args)                 — Stage 1 accepts (Stage 3 retires)
    ;;   Legacy:   |> msg                       — Stage 1 accepts (Stage 3 retires)
    ;;
    ;; L9 (2026-08-26) — optional `or:` continuation attaches a
    ;; failure-recovery block. Shape:
    ;;   |> receiver.method(args)
    ;;   or:
    ;;       <recovery-body>
    ;; The recovery body fires when the sync send's block throws (F48
    ;; @try/@catch catches).
    ;;
    ;; Track B/1 (2026-08-26): `or:` now accepts three arm shapes and
    ;; chains multiple arms in dispatch order:
    ;;   or:                   bare  → catches any thrown reason
    ;;   or <ident>:           bind  → binds caught exception object
    ;;   or T():   or T(fld: let x): typed → matches by reason record
    ;;                                       type; empty parens are the
    ;;                                       no-payload marker
    ;; Distinguisher between bind (`or e:`) and typed (`or T(...):`) is
    ;; the trailing `(` — a bare ident with no parens is a bind.
    ;;
    ;; Parser produces `:arms` on the emit node — an ordered list of
    ;; `{:pattern p :body stmts}` maps where `p` is nil (bare) /
    ;; `{:kind :bind :name S}` / `{:kind :case :name T :bindings [...]}`.
    ;; Lower currently only accepts the single-bare-arm case (MVP);
    ;; multi-arm and non-bare dispatch is the Track B/2+B/3 landing.
    (let [[e p] (parse-expr tokens (inc pos))
          emit-node
          (cond
            (and (= :call (:node e))
                 (= :dot (:node (:callee e))))
            {:node :emit
             :async? false
             :target (:target (:callee e))
             :msg    (:field  (:callee e))
             :args   (:args e)}

            (and (= :call (:node e))
                 (= :ident (:node (:callee e))))
            {:node :emit
             :async? false
             :target nil
             :msg    (:name (:callee e))
             :args   (:args e)}

            (= :ident (:node e))
            {:node :emit
             :async? false
             :target nil
             :msg    (:name e)
             :args   []}

            :else
            (fail! tokens pos
                   (str "prefix `|>` expected a call expression on the right-hand side "
                        "(F70 I6: `|> receiver.method(args)`), or legacy bare form "
                        "(`|> msg(args)` — retired in Stage 3)")))
          ;; B/1 chain: greedily collect one or more `or ...:` arms.
          ;; Each arm starts with NEWLINE `or` followed by an optional
          ;; pattern spec, then `:` NEWLINE INDENT block DEDENT. Arms
          ;; are sibling at the emit's indent level; the loop stops when
          ;; the next token is not `NEWLINE or`.
          [arms p2] (parse-or-arms tokens p)
          decorated (if (seq arms) (assoc emit-node :arms arms) emit-node)]
      [decorated p2])

    ;; F20 REJECTED 2026-08-18 — `for e in xs: body` sugar retires.
    ;; Spec §3.7.1 line 3885 rationale for no-destructuring:
    ;; "Iteration is method dispatch, not language pattern." The
    ;; `for` sugar was the C-family surface syntax over the same
    ;; dispatch; retiring it aligns with F17 L2 (loop:) and F45
    ;; (escaping closures) — retire mechanisms that duplicate the
    ;; substrate. `for` / `in` remain reserved as diagnostic anchors.
    (at? tokens pos :kw "for")
    (fail! tokens pos
           (str "F20 REJECTED: `for e in xs: body` is not supported — "
                "iteration is method dispatch, not language sugar "
                "(spec §3.7.1 rationale 3). Write "
                "`xs.for_each(e => body)` instead. For fold / map / "
                "filter / sorted, use the corresponding method with "
                "an inline lambda. See plans/archive/f20-for-as-alias.md."))

    ;; F69 L5 — `switch <expr>:` is retired at v1.0 (spec §3.7,
    ;; F69 atom unification 2026-08-18). Pattern-matched dispatch is
    ;; expression-position `<:` receive-glyph blocks. `switch` is
    ;; no longer a reserved lexer keyword, so it now surfaces as an
    ;; ident; check it early with a targeted rewrite hint before
    ;; falling through to the generic expression parser (which would
    ;; produce a confusing follow-on error).
    (and (at? tokens pos :ident)
         (= "switch" (t-value (peek-tok tokens pos))))
    (fail! tokens pos
           (str "F69 L5: `switch <expr>: <case ...>` is retired at v1.0. "
                "Pattern-matched dispatch is expression-position `<:` "
                "receive-glyph blocks (spec §3.7 rewrite). Write:\n\n"
                "    <expr>:\n"
                "        <: pattern: <body>\n"
                "        <: *: <body>\n\n"
                "See plans/archive/f69-atom-unification.md."))

    ;; F17 L1 — statement-position `while <cond>: <body>`. `loop` is L2.
    (at? tokens pos :kw "while")
    (parse-while-statement tokens pos)

    ;; F17 L1 — `break` / `continue` as statements. Legal only inside
    ;; a while-body; enforcement happens at lower-time (the ctx tracks
    ;; loop-depth then). At parse we produce the node uniformly.
    ;; Value-form `break <expr>` is L2 (loop-with-value); L1 rejects.
    (at? tokens pos :kw "break")
    (let [p (inc pos)]
      (if (or (at? tokens p :newline)
              (at? tokens p :dedent))
        [{:node :break-stmt} p]
        (fail! tokens p
               (str "F17 L1: `break <value>` is not supported — "
                    "value-yielding loops (`loop:` + `break <value>`) "
                    "were rejected 2026-08-18 as redundant with actor "
                    "state transitions. Model the accumulate-until-done "
                    "pattern as a self-transition on an atom with an "
                    "own-line `|:` proposition: the loop body becomes "
                    "`then: field => ...`, the exit condition becomes "
                    "`|: <cond>` at the arm head "
                    "on a transition to the done phase, and the final "
                    "value is read via an observable. See "
                    "plans/archive/f17-loop-primitives.md §\"Why not `loop:`\"."))))

    (at? tokens pos :kw "continue")
    [{:node :continue-stmt} (inc pos)]

    :else
    (let [[e p] (parse-expr tokens pos)]
      (cond
        ;; targeted send parsed as postfix — already a statement
        (= :emit (:node e))     [e p]
        ;; F69 L4 — `<:` dispatch parsed at expression position is
        ;; also its own statement kind; hand off directly rather than
        ;; wrap in :expr-stmt (lower-stmt has a `:dispatch` branch).
        (= :dispatch (:node e)) [e p]
        :else                   [{:node :expr-stmt :expr e} p]))))

(defn- parse-block
  "Parse an indented block: :indent (stmt :newline)+ :dedent."
  [tokens pos]
  (let [[_ p] (expect tokens pos :indent)]
    (loop [stmts [] p p]
      (cond
        (at? tokens p :dedent)
        [{:node :block :stmts stmts} (inc p)]

        (at? tokens p :newline)
        (recur stmts (inc p))

        :else
        (let [[stmt p2] (parse-statement tokens p)
              p3 (skip-newlines tokens p2)]
          (recur (conj stmts stmt) p3))))))

;; ---------------------------------------------------------------------------
;; Sections (data:, methods:, then:, do:, let:, cases:)
;;   Each section header is `<name>:` followed by :newline :indent … :dedent
;;   Section body content differs per section — dispatch here.
;; ---------------------------------------------------------------------------

(def ^:private nsobject-reserved-use-names
  "User-facing capability binding names that would collide with
   NSObject-synthesized instance state at emit time. The compiler
   generates `_<name>` as the ivar for each `use <name>: T`; ivar
   names that overlap NSObject's own synthesized backing storage
   (e.g. `_hash` for the built-in `-hash` selector) produce clang
   errors deep in the emitted `.m`. Reject at parse-time with an
   actionable diagnostic so users don't have to decode the clang
   error. Suggested rename in the error text.

   Grows conservatively — kept to well-known NSObject-family
   collisions rather than every reserved word."
  #{"hash" "description" "debugDescription" "class" "superclass"})

(defn- parse-use-line
  "Parse one direct capability declaration at def-body position:
     use cap: SomeCapability

   Each line initially emits the downstream-stable `:use-section` shape
   with one cap. `fold-direct-use-lines` coalesces repeated declarations
   into a single node before the completed def AST leaves the parser."
  [tokens pos]
  (let [[_ p] (expect tokens pos :kw "use")
        _     (when (at? tokens p :punct ":")
                (fail! tokens p
                       (str "`use:` section header is retired at v1.0. "
                            "Declare each capability directly at def-body "
                            "position: `use <name>: <Type>`.")))
        [name p] (expect tokens p :ident)
        _        (when (contains? nsobject-reserved-use-names name)
                   (fail! tokens (dec p)
                          (str "`use " name ": ...` — `" name
                               "` collides with an NSObject-synthesized "
                               "ivar / method (backing storage `_" name
                               "` overlaps NSObject's own). Choose a "
                               "different local name (e.g. `" name
                               "er`, `" name "_cap`).")))
        [_ p]    (expect tokens p :punct ":")
        [ty p]   (parse-type tokens p)]
    [{:node :use-section :caps [{:name name :type ty}]} p]))

(defn- proposition-line-start?
  "True when `pos` starts a logical source line. Layout tokens are the
   reliable signal here: the lexer normalizes indentation before emitting
   the first real token on a line, so a valid `|:` follows NEWLINE, INDENT,
   or the DEDENT that closes a multiline predecessor. This deliberately
   rejects crammed forms such as
   `foo :- bar |: pred = expr`."
  [tokens pos]
  (or (zero? pos)
      (when-let [prev (peek-tok tokens (dec pos))]
        (contains? #{:newline :indent :dedent} (t-kind prev)))))

(defn- parse-proposition-predicate
  "Parse the predicate portion of a proposition. Most predicates use the
   ordinary expression grammar. The observation-scoped type-bound spelling
   (`|: T: Bound`) is normalized as an expression-shaped `:type-bound` node
   so every proposition still has exactly one `:predicate` value."
  [tokens pos]
  (if (and (at? tokens pos :ident)
           (at? tokens (inc pos) :punct ":"))
    (let [[parameter p] (expect tokens pos :ident)
          [_ p]         (expect tokens p :punct ":")
          [bound p]     (parse-type tokens p)]
      [{:node :type-bound
        :parameter {:node :ident :name parameter}
        :bound bound}
       p])
    (parse-expr tokens pos)))

(defn- proposition-error-args->map
  "Normalize `ErrorType(field: value, ...)` args to the proposition AST's
   field-name -> expression map. Proposition error constructors require
   named, non-duplicate fields; positional args would lose the field/value
   contract promised by the surface syntax."
  [tokens pos args]
  (loop [remaining args result (array-map)]
    (if-let [arg (first remaining)]
      (do
        (when-not (= :named (:kind arg))
          (fail! tokens pos
                 "proposition error arguments must use `field: value` named form"))
        (when (contains? result (:name arg))
          (fail! tokens pos
                 (str "duplicate proposition error argument `" (:name arg) "`")))
        (recur (next remaining) (assoc result (:name arg) (:value arg))))
      result)))

(defn- parse-proposition-as-clause
  "Parse `as ErrorType(field: value, ...)`, returning the normalized type,
   arg map, and next position."
  [tokens pos]
  (let [[_ p]       (expect tokens pos :kw "as")
        [err-type p] (parse-type tokens p)
        args-pos     p
        [args p]     (parse-args-parens tokens p)]
    [err-type (proposition-error-args->map tokens args-pos args) p]))

(defn- parse-proposition
  "Parse one own-line proposition:

       |: <predicate> [as ErrorType(field: value, ...)]

   The `as` clause may remain inline or be placed in one continuation
   indentation level, matching the multiline examples in the ratified
   surface spec. Emits one normalized AST shape at every placement."
  [tokens pos]
  (when-not (proposition-line-start? tokens pos)
    (fail! tokens pos
           "`|:` proposition must occupy its own line; it cannot be crammed onto a signature or statement"))
  (let [[_ p]         (expect tokens pos :glyph "|:")
        [predicate p] (parse-proposition-predicate tokens p)
        continuation-as?
        (and (at? tokens p :newline)
             (at? tokens (inc p) :indent)
             (at? tokens (+ p 2) :kw "as"))
        as-pos         (cond
                         (at? tokens p :kw "as") p
                         continuation-as? (+ p 2)
                         :else nil)
        [err-type err-args p]
        (if as-pos
          (parse-proposition-as-clause tokens as-pos)
          [nil nil p])
        ;; A continuation `as` opens an indentation level solely for that
        ;; clause. Its closing DEDENT may be preceded by a NEWLINE when the
        ;; constructor fits on one physical line; multiline parens suppress
        ;; that NEWLINE in the lexer. Accept both token shapes.
        p (if continuation-as?
            (let [p (if (at? tokens p :newline) (inc p) p)
                  [_ p] (expect tokens p :dedent)]
              p)
            p)]
    (when-not (or continuation-as?
                  (at? tokens p :newline)
                  (at? tokens p :dedent)
                  (at? tokens p :eof))
      (fail! tokens p
             "`|:` proposition must occupy its own line; unexpected trailing content"))
    [{:node :proposition
      :predicate predicate
      :as-error-type err-type
      :as-error-args err-args}
     p]))

;; ---------------------------------------------------------------------------
;; Observables:
;;   Zero-arg:   <name> :- <expr>
;;   Parametric: <name>(<param-list>) :- <expr>       # F67 L2
;;   Declaration-flavored assignment (§1.11). The colon-less-than glyph.
;;   Parametric observables (Proposal G L2) replace reader methods: any
;;   pure computation over self's fields with args now goes here rather
;;   than in a `methods:` section.
;; ---------------------------------------------------------------------------

(def ^:private observable-name-kw-whitelist
  ;; F37 L8 — spec-committed method names that tokenize as reserved
  ;; words (`import X as Y` / `from X import`) also participate in the
  ;; user-callable surface. Accept them at observable declaration
  ;; position so a user's `as() :- bytes-expr` on a record shadows the
  ;; synthesized F37 `.as() -> Bytes` default per spec §3.3.2 rule 6.
  ;; Mirrors the dot-branch's `:ident` / `:kw` acceptance for method
  ;; calls (`p.as()`).
  #{"as" "from"})

(defn- signature-return-type-start?
  "True when `pos` begins a user type name. Corvid type names are
   TitleCase; this disambiguates `op() :- ReturnType` from a bodyful
   observable whose entire expression is a bare value identifier such as
   `op() :- cached`."
  [tokens pos]
  (when (at? tokens pos :ident)
    (let [nm (t-value (peek-tok tokens pos))]
      (and (seq nm) (Character/isUpperCase (first nm))))))

(defn- parse-observable [tokens pos]
  (let [tk         (peek-tok tokens pos)
        k          (t-kind tk)
        ;; F37 L8 — accept whitelisted reserved words (`as`, `from`) as
        ;; observable names to support user overrides of spec-committed
        ;; method surfaces. Reject other :kw tokens with the pre-L8
        ;; diagnostic shape via `expect`.
        [name p]   (if (and (= :kw k)
                            (contains? observable-name-kw-whitelist (t-value tk)))
                     [(t-value tk) (inc pos)]
                     (expect tokens pos :ident))
        has-param-list? (at? tokens p :punct "(")
        [params p] (if has-param-list?
                     (parse-param-list-parens tokens p)
                     [[] p])
        [_ p]      (expect tokens p :glyph ":-")
        ;; The existing inferred form (`name :- expr`) remains unchanged.
        ;; The explicit form is `name :- ReturnType = expr`. Observation-
        ;; scoped propositions occupy an indented body between the declared
        ;; return type and `= expr`, per §Propositions:
        ;;
        ;;   name :- ReturnType
        ;;       |: predicate
        ;;   = expression
        [candidate-type type-end]
        (if (signature-return-type-start? tokens p)
          (parse-type tokens p)
          [nil p])
        signature-only?
        (and has-param-list?
             candidate-type
             (or (and (at? tokens type-end :newline)
                      ;; A1 — a following INDENT means this is a
                      ;; bodyful multi-line method, not a bare
                      ;; signature declaration.
                      (not (at? tokens (inc type-end) :indent)))
                 (at? tokens type-end :dedent)
                 (at? tokens type-end :eof)))
        proposition-body?
        (and (not signature-only?)
             candidate-type
             (at? tokens type-end :newline)
             (at? tokens (inc type-end) :indent)
             (let [body-start (skip-newlines tokens (+ type-end 2))]
               (at? tokens body-start :glyph "|:")))
        ;; A1 (2026-09-03) — Multi-line method body:
        ;;   sig(params) :- ReturnType
        ;;       <stmt>
        ;;       <stmt>
        ;;       <final-expr>
        ;; Body evaluates as a `:do-block-expr` (last-expr-is-value).
        ;; Distinct from `proposition-body?` (leads with `|:`) and
        ;; `signature-only?` (no INDENT). Note: does NOT accept `=`
        ;; between ReturnType and body — that form remains
        ;; `explicit-inline?`.
        bodyful-block?
        (and (not signature-only?)
             (not proposition-body?)
             candidate-type
             (at? tokens type-end :newline)
             (at? tokens (inc type-end) :indent))
        explicit-inline? (and candidate-type
                              (at? tokens type-end :glyph "="))
        [return-type propositions expr p]
        (cond
          signature-only?
          [candidate-type [] nil type-end]

          proposition-body?
          (let [[_ body-pos] (expect tokens type-end :newline)
                [_ body-pos] (expect tokens body-pos :indent)
                [props after-body]
                (loop [props [] p body-pos]
                  (let [p (skip-newlines tokens p)]
                    (cond
                      (at? tokens p :dedent)
                      [props (inc p)]

                      (at? tokens p :glyph "|:")
                      (let [[prop p2] (parse-proposition tokens p)]
                        (recur (conj props prop) p2))

                      :else
                      (fail! tokens p
                             "expected an own-line `|:` proposition inside observation body"))))
                [_ after-body] (expect tokens after-body :glyph "=")
                [body-expr after-body] (parse-expr tokens after-body)]
            [candidate-type props body-expr after-body])

          explicit-inline?
          (let [[_ p] (expect tokens type-end :glyph "=")
                [body-expr p] (parse-expr tokens p)]
            [candidate-type [] body-expr p])

          bodyful-block?
          ;; A1 — parse the INDENT block as a sequence of statements;
          ;; wrap as `:do-block-expr` (last-expr-is-value semantics).
          (let [[_ p]  (expect tokens type-end :newline)
                [_ p]  (expect tokens p :indent)]
            (loop [stmts [] p p]
              (cond
                (at? tokens p :dedent)
                [candidate-type [] {:node :do-block-expr :stmts stmts} (inc p)]

                (at? tokens p :newline)
                (recur stmts (inc p))

                :else
                (let [[s p2] (parse-statement tokens p)
                      p3     (skip-newlines tokens p2)]
                  (recur (conj stmts s) p3)))))

          :else
          (let [[body-expr p] (parse-expr tokens p)]
            [nil [] body-expr p]))
        node (if signature-only?
               {:node :observation-signature
                :name name
                :params params
                :return-type return-type}
               (cond-> {:node :observable :name name :params params :expr expr}
                 return-type (assoc :return-type return-type)
                 (seq propositions) (assoc :propositions propositions)))]
    [node p]))

;; ---------------------------------------------------------------------------
;; Transitions
;;
;;   Full shape:
;;     [<source-phase>] [<receive-glyph> <msg>[{args}]]
;;         -> <target-phase>
;;         [|: <arm-proposition>]*
;;         [inline-body | indented-body]
;;
;;   Inline body:  same line as `->`; one of `then: X => Y` / `do: STMT` / `let: NAME: T = E`
;;   Indented body: NEWLINE INDENT (then:|do:|let: block)+ DEDENT
;;
;;   For simplicity of this first pass, body clauses accept either an inline
;;   single item or an indented multi-item block.
;; ---------------------------------------------------------------------------

(declare parse-transition-body)

(defn- looks-like-typed-params?
  "At `pos` = `(`. Distinguish an F67 L3 typed-params receive pattern
   (`msg(a: T, b: U)` — the handler-collapse form) from a message-shape
   expression's parens (`clock.every(rate){t: Instant}`). Typed-params
   shape: `()` or `(ident : ...)`. Everything else = expression."
  [tokens pos]
  (let [p (inc pos)]
    (or (at? tokens p :punct ")")
        (and (at? tokens p :ident)
             (at? tokens (inc p) :punct ":")))))

(defn- parse-msg-pattern
  "Parse a receive-glyph source pattern. Two shapes:

     1. Payload destructure (from streams/observables):
          tick{dt: Duration}
          input.pressed{key: Key}
          clock.every(rate){_: Instant}
          active_enemies.died{e: Enemy}
        parse-expr turns `X{...}` into a :msg-shape postfix.

     2. F67 L3 — typed-params receive (handler-collapse form):
          log(msg: String)
          fire(target: Enemy, dmg: Int)
        Distinguished by parens holding typed params (not expressions).
        Under `* <: msg(args) -> *`, this lowers to a handler-shaped
        method entry (spec §1265 semantics) — see lower-def-atom."
  [tokens pos]
  (cond
    ;; F67 L3 — typed-params form: `<ident>(<params>)`.
    ;; F70 I5 — also populate `:payload` so non-handler-shape
    ;; transitions (which read `:payload` for message-shape
    ;; collection) see the fields. Handler-shape transitions
    ;; (wildcard source + target) continue to read `:params`.
    ;; The two names are semantic aliases: same list, different
    ;; downstream consumers.
    (and (at? tokens pos :ident)
         (at? tokens (inc pos) :punct "(")
         (looks-like-typed-params? tokens (inc pos)))
    (let [[name p]     (expect tokens pos :ident)
          [params p]   (parse-receive-payload-param-list tokens p)
          as-payload   (mapv #(select-keys % [:name :type]) params)]
      [{:source  {:node :ident :name name}
        :params  params
        :payload as-payload}
       p])

    :else
    (let [[e p] (parse-expr tokens pos)]
      (if (= :msg-shape (:node e))
        [{:source (:target e) :payload (:fields e) :params nil} p]
        [{:source e :payload [] :params nil} p]))))

(defn- parse-receive-signature
  "Parse a signature-only async receive declaration at def-body position:

       <: name(args)

   It deliberately has neither a source phase nor an `->` target. Concrete
   implementations continue to use ordinary wildcard/concrete transitions."
  [tokens pos]
  (let [[_ p]      (expect tokens pos :glyph "<:")
        [name p]   (expect tokens p :ident)
        [params p] (parse-param-list-parens tokens p)]
    (when-not (or (at? tokens p :newline)
                  (at? tokens p :dedent)
                  (at? tokens p :eof))
      (fail! tokens p
             (str "signature-only async receive `<: " name "(...)` must "
                  "end after its parameter list; omit the source phase and "
                  "`->` target")))
    [{:node :receive-signature :name name :params params} p]))

(defn- parse-source-and-receive
  "Look at the head of a transition. Returns [source receive p] where:
     source  = {:kind :phase :name X}
             | {:kind :self}
             | {:kind :wildcard}     ; F67 L3 — `*` = from any phase
             | {:kind :implicit}
     receive = {:glyph <: :msg {...}} | nil

   Allows newline between source and receive-glyph — the two- and three-line
   transition header shapes both parse to the same AST."
  [tokens pos]
  (cond
    (at? tokens pos :glyph "->")
    [{:kind :implicit} nil pos]

    :else
    (let [source (cond
                   (at? tokens pos :glyph "*")   {:kind :wildcard}
                   (at? tokens pos :kw "self")   {:kind :self}
                   (at? tokens pos :ident)       {:kind :phase :name (t-value (peek-tok tokens pos))}
                   :else (fail! tokens pos "expected source-phase, self, `*`, or `->` at start of transition"))
          p (skip-newlines tokens (inc pos))]    ; allow newline after source
      (cond
        (at? tokens p :glyph "<:")
        (let [[msg p2] (parse-msg-pattern tokens (inc p))]
          [source {:glyph "<:" :msg msg} p2])

        (at? tokens p :glyph "<|")
        (let [[msg p2] (parse-msg-pattern tokens (inc p))]
          [source {:glyph "<|" :msg msg} p2])

        :else [source nil p]))))

(defn- parse-then-clause-inline
  "then: <entry>  — one field update (`<field> => <expr>`) OR one
   statement (F70 I3 extension — was field-update-only). The AST
   carries both `:updates` and `:stmts` bags so downstream (F33
   commit + post-commit fan-out) can treat them independently."
  [tokens pos]
  (let [[_ p]   (expect tokens pos :kw "then")
        [_ p]   (expect tokens p :punct ":")]
    (cond
      ;; `|:` is legal in a then body, but never crammed after `then:`.
      ;; Route this shape through the proposition parser for the own-line
      ;; diagnostic rather than the generic expression error.
      (at? tokens p :glyph "|:")
      (parse-proposition tokens p)

      ;; field update: `<ident> => <expr>`
      (and (at? tokens p :ident)
           (at? tokens (inc p) :glyph "=>"))
      (let [[name p2] (expect tokens p :ident)
            [_ p3]    (expect tokens p2 :glyph "=>")
            [rhs p4]  (parse-expr tokens p3)]
        [{:node :then-clause :updates [{:field name :expr rhs}] :stmts []} p4])

      ;; I3 — statement entry (send, print, method call, etc.)
      :else
      (let [[stmt p2] (parse-statement tokens p)]
        [{:node :then-clause :updates [] :stmts [stmt]} p2]))))

(defn- parse-then-clause-block
  "then:  (indented block of entries). Each entry is a field
   update (`<field> => <expr>`) OR an arbitrary statement (I3).
   Split at parse time into `:updates` and `:stmts` bags:
     - :updates commit atomically at transition end (F33)
     - :stmts fire in source order after commit, all reading
       the F33 snapshot (same discipline as retired `do:`)."
  [tokens pos]
  (let [[_ p] (expect tokens pos :kw "then")
        [_ p] (expect tokens p :punct ":")
        [_ p] (expect tokens p :newline)
        [_ p] (expect tokens p :indent)]
    (loop [ups [] stmts [] p p]
      (cond
        (at? tokens p :dedent)
        [{:node :then-clause :updates ups :stmts stmts} (inc p)]

        (at? tokens p :newline) (recur ups stmts (inc p))

        ;; Post-statement assertion. It stays in the ordered statement bag
        ;; so its eventual L2b lowering can fire exactly where written.
        (at? tokens p :glyph "|:")
        (let [[prop p2] (parse-proposition tokens p)
              p3        (skip-newlines tokens p2)]
          (recur ups (conj stmts prop) p3))

        ;; field update: `<ident> => <expr>`
        (and (at? tokens p :ident)
             (at? tokens (inc p) :glyph "=>"))
        (let [[name p2] (expect tokens p :ident)
              [_ p3]    (expect tokens p2 :glyph "=>")
              [rhs p4]  (parse-expr tokens p3)
              p5        (skip-newlines tokens p4)]
          (recur (conj ups {:field name :expr rhs}) stmts p5))

        ;; I3 — statement entry
        :else
        (let [[stmt p2] (parse-statement tokens p)
              p3        (skip-newlines tokens p2)]
          (recur ups (conj stmts stmt) p3))))))

(defn- parse-then-clause [tokens pos]
  ;; peek past `then:` — if next is :newline, it's a block form; else inline.
  (if (at? tokens (+ pos 2) :newline)
    (parse-then-clause-block tokens pos)
    (parse-then-clause-inline tokens pos)))

(defn- reject-do-clause!
  "F70 I3 Stage 3 (2026-08-19) — the `do:` transition sub-block is
   retired. Side-effect statements fold into `then:` alongside field
   updates; the unified `then:` clause accepts both `field => expr`
   updates (F33 atomic commit) and side-effect statements (`:>`,
   prefix `|>`, calls) in source order. Multiple `then:` clauses per
   body are legal and merge at lowering (F22 exemption)."
  [tokens pos]
  (fail! tokens pos
         (str "`do:` transition sub-block is retired (F70 I3, "
              "2026-08-19). Rename the clause header from `do:` to "
              "`then:` — the unified `then:` clause accepts both "
              "field updates (`field => expr`) and side-effect "
              "statements (`:>`, prefix `|> receiver.msg(args)`, "
              "calls, prints) in source order. Multiple `then:` "
              "blocks per transition body are legal (F22 exemption "
              "for `then:`). See spec §1.11 and plans/archive/f70-surface-facelift.md.")))

(defn- reject-let-clause!
  "F18 L1' Landing 2 (2026-08-24) — the transition `let:` sub-block is
   retired. The statement-form `let <name> = <expr>` (F18 L1) subsumes
   it: usable anywhere inside a `then:` clause, same visibility scope,
   no separate grammar to maintain. L1' Landing 1 (2026-08-23) shipped
   a grace-period deprecation warning; L1' Landing 2 hard-rejects.
   Mirrors `reject-do-clause!` shape (F70 I3)."
  [tokens pos]
  (fail! tokens pos
         (str "`let:` transition sub-block is retired (F18 L1' Landing 2, "
              "2026-08-24). Use statement-form `let <name> = <expr>` "
              "inside the `then:` clause instead — the unified `then:` "
              "clause accepts field updates, side-effect statements, and "
              "`let` bindings in source order. Migration: rewrite "
              "`let: k: 5\\n        then: n => n + k` as "
              "`then:\\n            let k = 5\\n            n => n + k`. "
              "See spec §2.5 and plans/g2-expression-flow/f18-block-as-expression.md.")))

(defn- body-clause-head?
  "True if pos is at `then:` | `do:` | `let:`. `do:` (F70 I3) and
   `let:` (F18 L1' Landing 2) are retired at v1.0 but still
   recognized here so `parse-transition-body` can dispatch to
   targeted retirement diagnostics instead of a generic parse error."
  [tokens pos]
  (and (at? tokens pos :kw)
       (#{"then" "do" "let"} (t-value (peek-tok tokens pos)))
       (at? tokens (inc pos) :punct ":")))

(defn- parse-transition-body
  "After the `-> target` head, an optional body:
     - if next token is a body-clause keyword on same line → inline body
     - if next is :newline :indent → indented body with multiple clauses
     - else → no body (transition ends)

   Arm-selector propositions are own-line entries at the body head, before
   any `then:` clause. Both propositions and `then:` clauses may repeat."
  [tokens pos]
  (cond
    (body-clause-head? tokens pos)
    ;; Inline single clause (rest of transition is on the same line).
    (let [[clause p]
          (case (t-value (peek-tok tokens pos))
            "then" (parse-then-clause tokens pos)
            "do"   (reject-do-clause! tokens pos)   ; F70 I3 Stage 3 — hard retirement (2026-08-19)
            "let"  (reject-let-clause! tokens pos)  ; F18 L1' Landing 2 — hard retirement (2026-08-24)
            (fail! tokens pos "unexpected clause"))]
      [[clause] p])

    (and (at? tokens pos :newline) (at? tokens (inc pos) :indent))
    (let [p (+ pos 2)]
      (loop [clauses [] p p]
        (cond
          (at? tokens p :dedent)
          [clauses (inc p)]
          (at? tokens p :newline) (recur clauses (inc p))
          (at? tokens p :glyph "|:")
          (do
            (when (some #(= :then-clause (:node %)) clauses)
              (fail! tokens p
                     "arm-head `|:` proposition must appear before `then:` in a transition body"))
            (let [[prop p2] (parse-proposition tokens p)]
              (recur (conj clauses prop) (skip-newlines tokens p2))))
          (body-clause-head? tokens p)
          (let [[clause p2]
                (case (t-value (peek-tok tokens p))
                  "then" (parse-then-clause tokens p)
                  "do"   (reject-do-clause! tokens p) ; F70 I3 Stage 3 — hard retirement (2026-08-19)
                  "let"  (reject-let-clause! tokens p)  ; F18 L1' Landing 2 — hard retirement (2026-08-24)
                  (fail! tokens p "unexpected clause"))]
            (recur (conj clauses clause) (skip-newlines tokens p2)))
          :else
          [clauses p])))

    :else [[] pos]))

(defn- parse-transition
  "Full transition. See grammar in section header.

   The header may be split across two lines: source+receive on line 1,
   `-> target` on line 2 (per game-toys.cxc convention). We skip a
   :newline between components if the next non-newline token is what
   we're expecting, so both single-line and two-line shapes parse the
   same AST."
  [tokens pos]
  (let [[source receive p] (parse-source-and-receive tokens pos)
        p (skip-newlines tokens p)              ; allow newline before ->
        [_ p]      (expect tokens p :glyph "->")
        [target p] (cond
                     ;; `self` target = no phase change (stay in
                     ;; whatever phase matched at source). 2026-08-26
                     ;; wildcard unification retired `-> *` since target
                     ;; `*` wasn't wildcard-semantics — it was self-
                     ;; reference. Handler-shape (`* <: msg(args) -> self`)
                     ;; detection at lower now checks target = "self".
                     (at? tokens p :kw "self") ["self" (inc p)]

                     ;; Retired form: `-> *` target. Migration hint.
                     (at? tokens p :glyph "*")
                     (fail! tokens p
                            (str "transition target `*` is retired 2026-08-26 "
                                 "(target-`*` meant self-reference, not "
                                 "wildcard — unifying `*` as wildcard-only). "
                                 "Rewrite `-> *` as `-> self`."))

                     (at? tokens p :ident)     [(t-value (peek-tok tokens p)) (inc p)]
                     :else (fail! tokens p "expected target phase or `self` after `->`"))
        [body p]   (parse-transition-body tokens p)]
    [{:node :transition
      :source source
      :receive receive
      :target target
      :body body} p]))

;; ---------------------------------------------------------------------------
;; def-atom top-level form
;;
;;   def <Name>[(ctor-params)]:
;;       (use <name>: <Type> | field | |: proposition | observable | transition)+
;;
;;   Determines what to parse by look-ahead:
;;     - `use <name>: <Type>`                        → normalized use section
;;     - `<ident>: <Type>`                           → direct field
;;     - `|:`                                        → proposition
;;     - `<ident> :-` or `<ident>(...) :-`           → observable (zero-arg or parametric)
;;     - `<ident|self> <:|<|` OR `<ident> ->` OR `->` → transition
;; ---------------------------------------------------------------------------

(defn- scan-past-parens
  "Given the token at `pos` is an open `(`, return the position just
   after its matching close `)`. Balances nested parens. Returns nil if
   no match is found before EOF."
  [tokens pos]
  (loop [p (inc pos) depth 1]
    (cond
      (>= p (count tokens)) nil
      (at? tokens p :punct "(") (recur (inc p) (inc depth))
      (at? tokens p :punct ")") (if (= depth 1) (inc p) (recur (inc p) (dec depth)))
      :else                     (recur (inc p) depth))))

(defn- observable-name-token? [tokens pos]
  (or (at? tokens pos :ident)
      (and (= :kw (t-kind (peek-tok tokens pos)))
           (contains? observable-name-kw-whitelist (t-value (peek-tok tokens pos))))))

(defn- looks-like-observable? [tokens pos]
  (and (observable-name-token? tokens pos)
       (or (at? tokens (inc pos) :glyph ":-")
           ;; F67 L2 — parametric observable: `<name>(params) :-`
           (and (at? tokens (inc pos) :punct "(")
                (when-let [after-close (scan-past-parens tokens (inc pos))]
                  (at? tokens after-close :glyph ":-"))))))

;; F14 / F67 L3 — the legacy `-> handler(args):` sugar (F14) is retired
;; under Proposal G's class-body collapse. The migration target is the
;; explicit wildcard-transition form:
;;
;;   -> foo(msg: T):    →    * <: foo(msg: T) -> *
;;       stmt                     then: stmt
;;
;; Both forms lower to the same handler-shaped method entry (spec §1265
;; semantics); the collapse removes the parallel opener glyph. This
;; predicate + parser fire the migration diagnostic — see
;; transition->handler-decl in lower.clj for the target lowering.
(defn- looks-like-handler-decl? [tokens pos]
  (and (at? tokens pos :glyph "->")
       (at? tokens (inc pos) :ident)
       (at? tokens (+ pos 2) :punct "(")))

(defn- parse-handler-decl [tokens pos]
  (let [[_ _]        (expect tokens pos :glyph "->")
        name-tok     (peek-tok tokens (inc pos))
        handler-name (t-value name-tok)]
    (fail! tokens pos
           (str "F67 L3: `-> " handler-name "(args):` handler-decl sugar is "
                "retired (Proposal G class-body collapse). Migrate to the "
                "explicit handler-shape transition:\n\n"
                "    * <: " handler-name "(args) -> *\n"
                "        then: <body>\n\n"
                "Semantics unchanged (spec §1265) — same method-table entry, "
                "same external invocation surface. The collapse removes the "
                "parallel opener glyph so the class body has one shape per "
                "kind of declaration."))))

(defn- looks-like-field-decl?
  "F67 L5 — top-level field declaration at atom/record body:
     `<lowercase-ident> : <TypeRef> [= <expr>]`
   Distinguished from transition source-phase (`<ident> <:` / `<ident> ->`)
   and from observable (`<ident> :-` / `<ident>(...) :-`) by requiring
   `:punct \":\"` at position 1 (observables use `:glyph \":-\"`,
   transitions use `:glyph`). Section keywords are `:kw`, not `:ident`,
   so they never match here.

   The extra `NOT :newline at pos+2` guard rejects retired-section
   headers with lowercase-ident-looking names (e.g. `state:` which
   would otherwise slip through since `state` lexes as `:ident`, not
   `:kw`). Field decls always have a type token after the colon."
  [tokens pos]
  (and (at? tokens pos :ident)
       (at? tokens (inc pos) :punct ":")
       (not (at? tokens (+ pos 2) :newline))))

;; ---------------------------------------------------------------------------
;; F8 L3a — `computed:` behavioral section (MVP). Read-only derived fields.
;;
;; A `computed:` section body accepts field-shaped decls `name: T = expr`
;; (or a bare `name = expr` — type inferred). Each entry is rewritten to a
;; zero-arg observable `name() :- expr` at parse time — the field_getter
;; generator described in the F8 plan §Landing 3a. Users get field-style
;; ergonomics (declarative + typed) but the compiler treats it as a getter
;; so cross-atom reads always recompute from the current state (spec
;; §4.10.4.1 Computed contract).
;;
;; MVP scope: only zero-param computed fields. Parametric readers keep
;; using the top-level `name(args) :- expr` observable form. Setter
;; generation (`@compile_error_setter`) is naturally covered by the
;; existing "no setter on observable" behavior — a user write to a
;; computed field lowers as an assignment to an observable name, which
;; the ordinary observable-name resolver already rejects.
;; ---------------------------------------------------------------------------

(defn- fold-top-level-fields
  "F67 L5 — after parsing an atom/record body containing individual
   `:field` nodes (Proposal D top-level surface), gather them into a
   single synthetic `:data-section` at the position of the first field
   so downstream AST consumers stay shape-stable. If a legacy
   `:data-section` is also present, fold the top-level fields into it
   at the section's position (preserving declaration order via the
   section-first, top-level-fields-second convention).

   Body positions of individual `:field` nodes are removed; the
   synthesized/merged section takes their combined position slot."
  [body]
  (let [fields   (filterv #(= :field (:node %)) body)
        legacy   (first (filter #(= :data-section (:node %)) body))]
    (cond
      (empty? fields)
      body

      legacy
      (let [merged  (assoc legacy
                           :fields
                           (into (or (:fields legacy) []) fields))
            without-fields (remove #(= :field (:node %)) body)]
        (mapv (fn [n] (if (= :data-section (:node n)) merged n))
              without-fields))

      :else
      (let [first-field-idx (some (fn [[i n]]
                                    (when (= :field (:node n)) i))
                                  (map-indexed vector body))
            without-fields (remove #(= :field (:node %)) body)
            synthetic       {:node :data-section :fields fields}]
        (into (subvec (vec without-fields) 0 first-field-idx)
              (into [synthetic]
                    (subvec (vec without-fields) first-field-idx)))))))

(defn- fold-direct-use-lines
  "Coalesce repeated direct `use <name>: <Type>` lines into one
   downstream-stable `:use-section` node at the first declaration's
   position. Lowering and resolution intentionally continue reading the
   first section's `:caps` vector, exactly as they did before L4."
  [body]
  (let [use-sections (filterv #(= :use-section (:node %)) body)]
    (if (< (count use-sections) 2)
      body
      (let [merged (assoc (first use-sections)
                          :caps (vec (mapcat :caps use-sections)))]
        (loop [remaining body
               folded    []
               emitted?  false]
          (if-let [item (first remaining)]
            (if (= :use-section (:node item))
              (if emitted?
                (recur (rest remaining) folded true)
                (recur (rest remaining) (conj folded merged) true))
              (recur (rest remaining) (conj folded item) emitted?))
            folded))))))

(defn- looks-like-transition? [tokens pos]
  ;; Called after observable / field / section checks have failed. At
  ;; atom-body top level, the remaining possibilities are transitions
  ;; and errors. A transition starts with `->` (implicit source),
  ;; `self`, `*` (F67 L3 wildcard source), or a source-phase identifier.
  ;; Since the observable + field cases are already excluded, any bare
  ;; ident is treated as a transition-source.
  (or (at? tokens pos :glyph "->")
      (at? tokens pos :glyph "*")
      (at? tokens pos :kw "self")
      (at? tokens pos :ident)))

(declare def-atom->record def-atom-signatures->protocol def-atom->enum
         def-atom-body-has-transition? def-atom-body-has-use?
         def-atom-body-has-signature? def-atom-body-has-case?
         def-atom-body-has-atom-ctor-field-default?)

(defn- parse-on-nil-section
  "F39 L8 (spec §4.10.9) — `on_nil:` section body:
     on_nil:
         method_a: <const-expr>
         method_b: <const-expr>
   Each entry names a method and a constant value returned at call sites
   whose receiver is nil. Const-shape validation and method-existence
   checks happen at lower-time (`build-atom-env` collects `:on-nil-entries`
   and validates against the composed method table). Parser accepts any
   expression on the RHS — invalid shapes fail with the standard
   `validate-default-is-const!` diagnostic downstream."
  [tokens pos]
  (let [[_ p]  (expect tokens pos :ident)     ; "on_nil"
        [_ p]  (expect tokens p :punct ":")
        [_ p]  (expect tokens p :newline)
        [_ p]  (expect tokens p :indent)]
    (loop [entries [] p p]
      (cond
        (at? tokens p :dedent)
        [{:node :on-nil-section :entries entries} (inc p)]

        (at? tokens p :newline)
        (recur entries (inc p))

        (and (at? tokens p :ident)
             (at? tokens (inc p) :punct ":"))
        (let [[nm p] (expect tokens p :ident)
              [_ p]  (expect tokens p :punct ":")
              [v p]  (parse-expr tokens p)]
          (recur (conj entries {:method nm :value v})
                 (skip-newlines tokens p)))

        :else
        (fail! tokens p
               (str "expected `<method_name>: <const-expr>` inside `on_nil:` "
                    "section, or dedent to close the section"))))))

(defn- parse-queue-config
  "Track C/2 (2026-08-26) — parse a `queue: <discipline>` declaration
   at the head of a def-atom's ctor-params. Supported disciplines:
     bounded(N)  → {:kind :bounded :capacity N}
     unbounded   → {:kind :unbounded}    (explicit form of the default)
   Returns [config-map new-pos] positioned immediately after the
   discipline expression, or nil if the tokens don't start `queue:`."
  [tokens pos]
  (when (and (at? tokens pos :ident)
             (= "queue" (t-value (peek-tok tokens pos)))
             (at? tokens (inc pos) :punct ":"))
    (let [p (+ pos 2)]
      (cond
        (and (at? tokens p :ident)
             (= "unbounded" (t-value (peek-tok tokens p))))
        [{:kind :unbounded} (inc p)]

        (and (at? tokens p :ident)
             (= "bounded" (t-value (peek-tok tokens p)))
             (at? tokens (inc p) :punct "("))
        (let [p (+ p 2)]
          (when-not (at? tokens p :int)
            (fail! tokens p
                   (str "Track C/2: `queue: bounded(...)` requires an "
                        "integer literal capacity, e.g. `bounded(100)`.")))
          (let [n  (t-value (peek-tok tokens p))
                _  (when-not (pos? n)
                     (fail! tokens p
                            (str "Track C/2: `queue: bounded(" n
                                 ")` requires a positive capacity.")))
                [_ p] (expect tokens (inc p) :punct ")")]
            [{:kind :bounded :capacity n} p]))

        :else
        (fail! tokens p
               (str "Track C/2: `queue:` expects `bounded(N)` or "
                    "`unbounded` (positional discipline expression), "
                    "got " (pr-str (t-value (peek-tok tokens p)))))))))

(defn- parse-def-atom-ctor-tail
  "Parse the remainder of a def-atom's `(...)` header after an optional
   `queue:` prefix has been consumed. Returns [ctor-params new-pos]
   where ctor-params is a possibly-empty vector matching what
   `parse-param-list-parens` produced pre-C/2. Callers position `pos`
   just after the `queue: <discipline>` expression, at either `,` (more
   params to follow) or `)` (queue is the only entry)."
  [tokens pos]
  (cond
    (at? tokens pos :punct ")")
    [[] (inc pos)]

    (at? tokens pos :punct ",")
    (loop [ps [] p (inc pos)]
      (let [[param p2] (parse-param tokens p)]
        (cond
          (at? tokens p2 :punct ",")
          (recur (conj ps param) (inc p2))

          (at? tokens p2 :punct ")")
          [(conj ps param) (inc p2)]

          :else (fail! tokens p2 "expected `,` or `)` in param list"))))

    :else
    (fail! tokens pos
           (str "Track C/2: after `queue: <discipline>` expected `,` "
                "(followed by more ctor params) or `)` (queue is the "
                "only entry)."))))

;; ---------------------------------------------------------------------------
;; R7 (2026-08-31) — nested `foreign "libname":` blocks inside `def plugin:`.
;; Each block owns one native library / framework; each entry inside the
;; block declares one importable symbol / selector / class-selector / type
;; alias. B5 (plugin symbol resolver) consumes these AST nodes.
;; ---------------------------------------------------------------------------

(defn- parse-foreign-signature
  "Right-associative function-type parser for foreign entry signatures.
   Handles `T`, `() -> Ret` (FL-9 zero-arg), `(args) -> Ret`, and
   `(args) -> (args2) -> Ret` shapes by recursion on `->`. Terminals
   delegate to `parse-type`. FL-9 (2026-09-02): the special-case
   `()` at arg position (empty parens) produces a synthetic empty
   `:type-tuple` so `foreign-signature-arg-types` sees a zero-length
   arg list — matches `fork() -> Int32` idiom used across plugin drafts."
  [tokens pos]
  (let [[left p]
        (if (and (at? tokens pos :punct "(")
                 (at? tokens (inc pos) :punct ")"))
          [{:node :type-tuple :types []} (+ pos 2)]
          (parse-type tokens pos))]
    (if (at? tokens p :glyph "->")
      (let [[right p2] (parse-foreign-signature tokens (inc p))]
        [{:node :foreign-fn-type :args left :return right} p2])
      [left p])))

(def ^:private ffi-default-nonnil-primitives
  "2026-09-04 — primitive type names that default to `!` (non-null) at
   FFI-signature positions. Rationale: sentinel-lifted primitives
   (Int64=INT64_MIN, Bool=-1, Float/Double=NaN) at a foreign
   boundary conflate the wire-format C value with cxc's nil marker.
   Foreign symbols rarely, if ever, mean the sentinel value as a
   legitimate wire-format return; declaring them nullable would
   sprinkle sentinel-propagation guards on every FFI call site. The
   pragmatic default is non-null; a user who genuinely needs a
   nullable-primitive FFI position writes it inside a wrapper method
   that returns the nil-lifted cxc type explicitly.

   UInt/String/CString/Bytes/Any/Ptr/user types unaffected — they
   have no sentinel-lifting to lose."
  #{"Int" "Int8" "Int16" "Int32" "Int64"
    "Float" "Double" "Bool"})

(defn- wrap-ffi-primitives-nonnil
  "Rewrite bare `:type-ref` naming an FFI-default-nonnil primitive to
   `:type-nonnil {:inner <same>}`. Recurses into nested
   `:foreign-fn-type` / `:type-tuple` shapes so every position in the
   signature (arg tuple, return type, curried inner functions) picks
   up the default. Leaves `:type-nonnil` (user wrote `!`) and
   non-primitive refs untouched."
  [ty-node]
  (case (:node ty-node)
    :type-ref
    (if (contains? ffi-default-nonnil-primitives (:name ty-node))
      {:node :type-nonnil :inner ty-node}
      ty-node)

    :type-tuple
    (update ty-node :types #(mapv wrap-ffi-primitives-nonnil %))

    :type-named-tuple
    ;; Named-tuple args like `(fd: Int32, s: NSURLSession)` — fields
    ;; carry :type refs. Wrap each field's :type recursively so
    ;; `Int32` becomes `Int32!` at the FFI position.
    (update ty-node :fields
            (fn [fields]
              (mapv #(update % :type wrap-ffi-primitives-nonnil) fields)))

    :foreign-fn-type
    (-> ty-node
        (update :args   wrap-ffi-primitives-nonnil)
        (update :return wrap-ffi-primitives-nonnil))

    ty-node))

(defn- parse-foreign-entry
  "Parse one entry inside a `foreign \"libname\":` block. Entry kinds:
     symbol         \"symname\"  -> <signature>
     selector       \"cls\" \"sel:\" -> <signature>
     class_selector \"cls\" \"sel:\" -> <signature>
     type           \"objc-name\" as <IdentAlias>
   Returns [{:kind K :name-strs [...] :signature <sig> :alias <ident>?} pos]."
  [tokens pos]
  (let [t     (peek-tok tokens pos)
        _     (when-not (= :ident (t-kind t))
                (fail! tokens pos
                       (str "R7 foreign entry expects a kind keyword "
                            "(`symbol` / `selector` / `class_selector` / "
                            "`type`), got " (pr-str (t-value t)))))
        kind  (t-value t)
        p     (inc pos)
        _     (when-not (contains? #{"symbol" "selector" "class_selector" "type"} kind)
                (fail! tokens pos
                       (str "R7 foreign entry kind must be one of "
                            "`symbol` / `selector` / `class_selector` / `type`, "
                            "got `" kind "`.")))]
    (case kind
      "symbol"
      (let [[sym p]  (expect tokens p :string)
            [_ p]    (expect tokens p :glyph "->")
            [sig p]  (parse-foreign-signature tokens p)]
        [{:kind :symbol :name-strs [sym]
          :signature (wrap-ffi-primitives-nonnil sig)} p])

      ("selector" "class_selector")
      (let [[cls p] (expect tokens p :string)
            [sel p] (expect tokens p :string)
            [_ p]   (expect tokens p :glyph "->")
            [sig p] (parse-foreign-signature tokens p)]
        [{:kind (keyword kind) :name-strs [cls sel]
          :signature (wrap-ffi-primitives-nonnil sig)} p])

      "type"
      ;; FL-12 (2026-09-02) — `as <Alias>` is optional. When absent,
      ;; the alias is the ObjC type name verbatim. Matches every
      ;; plugin draft's shape (`type "NSURLSession"` alone). The
      ;; explicit `as` clause remains for renaming (e.g., `type
      ;; "NSURLRequest" as Request`).
      (let [[objc-nm p] (expect tokens p :string)
            [alias p]   (if (at? tokens p :kw "as")
                          (expect tokens (inc p) :ident)
                          [objc-nm p])]
        [{:kind :type :name-strs [objc-nm] :alias alias} p]))))

(defn- parse-foreign-block
  "Parse a `foreign \"libname\":` block: header line + indented entries.
   Called from parse-def-atom body when `pos` is at the `foreign` ident
   (validated by caller). Only legal inside a `def plugin:` body under
   R7; the caller enforces the plugin-body scope."
  [tokens pos]
  (let [[_ p]       (expect tokens pos :ident)   ; "foreign"
        [libname p] (expect tokens p :string)
        [_ p]       (expect tokens p :punct ":")
        [_ p]       (expect tokens p :newline)
        [_ p]       (expect tokens p :indent)]
    (loop [entries [] p p]
      (cond
        (at? tokens p :dedent)
        [{:node :foreign-block :library libname :entries entries} (inc p)]

        (at? tokens p :newline)
        (recur entries (inc p))

        :else
        (let [[entry p2] (parse-foreign-entry tokens p)]
          (recur (conj entries entry) (skip-newlines tokens p2)))))))

(defn- parse-def-atom [tokens pos]
  (let [[_ p]      (expect tokens pos :kw "def")
        [name p]   (expect tokens p :ident)
        ;; Track C/2 (2026-08-26) — `(queue: <discipline>[, ...])` at
        ;; def-header position declares the atom's mailbox transport.
        ;; `queue:` must be first when present; ordinary ctor params
        ;; follow after a `,`.
        [queue-cfg ctor p]
        (cond
          (not (at? tokens p :punct "("))
          [nil [] p]

          :else
          (let [after-lp    (inc p)
                queue-parse (parse-queue-config tokens after-lp)]
            (if queue-parse
              (let [[cfg qp]   queue-parse
                    [ctor pp2] (parse-def-atom-ctor-tail tokens qp)]
                [cfg ctor pp2])
              (let [[ctor pp2] (parse-param-list-parens tokens p)]
                [nil ctor pp2]))))
        [_ p]      (expect tokens p :punct ":")
        [_ p]      (expect tokens p :newline)
        [_ p]      (expect tokens p :indent)]
    (loop [body [] p p]
      (cond
        (at? tokens p :dedent)
        (let [folded  (-> body
                          fold-direct-use-lines
                          fold-top-level-fields)
              raw-def (cond-> {:node :def-atom :name name :ctor-params ctor
                              :body folded}
                        queue-cfg (assoc :queue-config queue-cfg))
              ;; R7 (2026-08-31, decisions-frozen-2026-08-31-plugin-shape) —
              ;; `def plugin:` at module top-level is the plugin manifest
              ;; marker (reserved name). Body admits fields + nested
              ;; `foreign "..."` sections (PR-3 fields, PR-4 foreign).
              ;; No ctor params, no transitions, no observables, no `use`
              ;; declarations; targeted diagnostic on any of those.
              ;; Emits `:def-plugin` AST node — F75 manifest resolver
              ;; consumes this.
              plugin-marker? (= "plugin" name)
              ;; PR-4 scope check: `foreign "..."` blocks are only
              ;; legal inside `def plugin:` bodies (R7 grammatical
              ;; enforcement + R11 plugin-only wave). Reject earlier
              ;; than the reshape switch so the diagnostic points at
              ;; the missing marker, not a misleading downstream kind.
              _              (when (and (not plugin-marker?)
                                        (some #(= :foreign-block (:node %)) folded))
                               (fail! tokens pos
                                      (str "R7: `foreign \"...\"` blocks are only "
                                           "legal inside a `def plugin:` module manifest. "
                                           "Add a `def plugin:` marker at module top-level "
                                           "(spec §7.2.1, R7 + R11 plugin-only wave 2026-09-01) "
                                           "or move the block into an actual plugin module.")))
              reshaped
              (cond
                plugin-marker?
                (do
                  (when (seq ctor)
                    (fail! tokens pos
                           (str "R7: `def plugin:` manifest cannot take ctor params — "
                                "manifest is data, not a constructor. Delete the "
                                "`(...)` after `plugin`.")))
                  (when queue-cfg
                    (fail! tokens pos
                           (str "R7: `def plugin:` manifest cannot declare a "
                                "`queue:` — no mailbox on a manifest record.")))
                  ;; PR-4 (2026-09-02) — body admits fields + nested
                  ;; `foreign "..."` sections. Reject anything else with
                  ;; a targeted R7 diagnostic.
                  (let [data-fields    (vec (mapcat (fn [n]
                                                      (when (= :data-section (:node n))
                                                        (:fields n)))
                                                    folded))
                        foreign-blocks (vec (filter #(= :foreign-block (:node %)) folded))
                        allowed?       #(contains? #{:data-section :foreign-block} (:node %))
                        non-allowed    (first (remove allowed? folded))]
                    (when non-allowed
                      (fail! tokens pos
                             (str "R7: `def plugin:` body admits only fields "
                                  "(name/version/compat/requires + defaults) and "
                                  "nested `foreign \"libname\":` sections. "
                                  "Got node kind `" (:node non-allowed) "`.")))
                    {:node :def-plugin :name "plugin"
                     :fields data-fields
                     :foreign-blocks foreign-blocks}))

                ;; F68 L1/L2/L5 (Proposal H) — structural detection dispatch.
                ;; Order matters: protocol (signature-only members) > enum
                ;; (case) > class
                ;; (transition, direct use, or ctor params) > record (fields +
                ;; observables only, no ctor params).

                (def-atom-body-has-signature? folded)
                (def-atom-signatures->protocol raw-def tokens pos)

                (def-atom-body-has-case? folded)
                (def-atom->enum raw-def tokens pos)

                (or (def-atom-body-has-transition? folded)
                    (def-atom-body-has-use? folded)
                    (def-atom-body-has-atom-ctor-field-default? folded)
                    (seq ctor)
                    ;; Track C/2 (2026-08-26) — presence of a `queue:`
                    ;; declaration is itself an agent-intent signal
                    ;; (records / protocols / enums have no mailbox).
                    queue-cfg)
                ;; F69 L2 — agent-shape def (transitions / direct use / ctor
                ;; params / atom-ctor default). Stamp the inferred kind
                ;; so downstream consumers can query uniformly.
                (assoc raw-def :inferred-kind :agent)

                :else
                (def-atom->record raw-def tokens pos))
              _ (when (and queue-cfg
                           (not= :agent (:inferred-kind reshaped)))
                  (fail! tokens pos
                         (str "Track C/2: `queue: <discipline>` is meaningful "
                              "only on agent atoms (those with transitions, "
                              "direct `use:`, or ctor params). `def " name
                              "` has no mailbox — its inferred kind is "
                              (pr-str (:inferred-kind reshaped))
                              ". Remove the `queue:` declaration or promote "
                              "to an agent shape.")))]
          [reshaped (inc p)])

        (at? tokens p :newline)
        (recur body (inc p))

        ;; Annotation grammar retired 2026-08-30 (annotation-grammar-
        ;; retirement wave). `@name(...)` no longer parses anywhere;
        ;; targeted diagnostic points at the replacement surfaces so
        ;; pre-retirement code lands on a migration hint instead of a
        ;; generic "unexpected character" error.
        (at? tokens p :annotation-prefix)
        (fail! tokens p
               (str "annotation grammar (`@name(...)`) is retired at v1.0 "
                    "(annotation-grammar-retirement 2026-08-30). "
                    "Plugin manifest → `def plugin:` record marker with reserved "
                    "name at module top-level (R2/R7 2026-08-31 + R11 plugin-only "
                    "wave 2026-09-01; see spec §7.2.1). "
                    "Metadata (kind) → body-shape inference (§3.3). "
                    "Deprecation → comment / user convention."))

        ;; L2b — direct def-body proposition (class-level invariant).
        ;; `invariants:` is hard-retired; this is the sole invariant
        ;; parse entry point at def-body position.
        (at? tokens p :glyph "|:")
        (let [[prop p2] (parse-proposition tokens p)]
          (recur (conj body prop) (skip-newlines tokens p2)))

        ;; L2b retirement diagnostic — targeted fix-it for `invariants:`
        ;; section header (retired 2026-08-25, commit `5e90cff`). Reserved
        ;; keyword `invariants` in `lex.clj` exists solely to anchor this
        ;; diagnostic; without it the fall-through emits a generic
        ;; "expected …" list that doesn't name the migration path.
        (at? tokens p :kw "invariants")
        (fail! tokens p
               (str "`invariants:` section header is retired at v1.0 "
                    "(four-position `|:` unification, ratified 2026-08-25). "
                    "Migrate each invariant to a def-body `|:` proposition "
                    "on its own line at def-body top level:\n\n"
                    "  def " name ":\n"
                    "      <field decls>\n"
                    "      |: <proposition>          ← was `invariants:` entry\n"
                    "      |: <another proposition>\n\n"
                    "See spec §1.11 retirement mapping table and §3.4.2."))

        ;; L5 — a leading `<:` with no source phase and no `->` target is
        ;; an async receive signature. Its presence participates in the
        ;; body-shape protocol inference at the end of the loop.
        (at? tokens p :glyph "<:")
        (let [[sig p2] (parse-receive-signature tokens p)]
          (recur (conj body sig) (skip-newlines tokens p2)))

        ;; R7 PR-4 (2026-09-02) — nested `foreign "libname":` block.
        ;; Only legal inside a `def plugin:` body; the plugin-reshape
        ;; branch enforces that. Parsed here (regardless of enclosing
        ;; def kind) so we can produce a targeted R7 rejection message
        ;; at reshape time rather than a generic "expected …" fall-
        ;; through. `foreign` is a contextual ident, not a reserved
        ;; keyword — outside plugin bodies, other identifiers named
        ;; `foreign` still parse as usual.
        (and (at? tokens p :ident)
             (= "foreign" (t-value (peek-tok tokens p)))
             (at? tokens (inc p) :string)
             (at? tokens (+ p 2) :punct ":"))
        (let [[blk p2] (parse-foreign-block tokens p)]
          (recur (conj body blk) (skip-newlines tokens p2)))

        ;; F8 L3b.ii.β — behavioral construct dispatch via the registry.
        ;; For `:kw` tokens whose value maps to a `:kind :behavioral`
        ;; registry entry, invoke the registered parser directly
        ;; instead of falling through to a hardcoded arm. Behavior-
        ;; equivalent for the direct `use <name>: <Type>` surface
        ;; (via `requiring-resolve`);
        ;; `:invariants` is parser-less in the registry because the
        ;; own-line `|:` arm above produces propositions directly, while
        ;; `:deprecated` routes through the top-level annotation parser.
        ;; Hardcoded arms below stay as a safety fallback until L3b.iii
        ;; retires them once both parse sites go registry-first.
        (and (at? tokens p :kw)
             (section/behavioral-parser (t-value (peek-tok tokens p))))
        (let [parser (section/behavioral-parser (t-value (peek-tok tokens p)))
              [s p2] (parser tokens p)]
          (recur (conj body s) (skip-newlines tokens p2)))

        (at? tokens p :kw "use")
        (let [[s p2] (parse-use-line tokens p)]
          (recur (conj body s) (skip-newlines tokens p2)))

        ;; F67 L4c — `replace` on a plain (non-extension) def-atom body
        ;; has no shadow target; the modifier is only meaningful inside
        ;; `extend` per §3.3.2 rule 4.
        (at? tokens p :kw "replace")
        (fail! tokens p
               (str "`replace` modifier inside `def " name "` body is only "
                    "meaningful in an `extend` context — a class's "
                    "own declarations can't shadow themselves (§3.3.2 rule 4). "
                    "Drop the `replace` prefix, or move the declaration "
                    "into an `extend` block if the intent was to "
                    "shadow another declaration."))

        ;; F37 L6b (2026-08-24) — `class function name(...): body` is
        ;; the spec §3.4.6 static-fn shape (used by `Duration.seconds`,
        ;; F37 L6's `class function from(source: Bytes) -> ...`, etc.).
        ;; Parsed as `:class-function`; lower fail-nyi's with an F37
        ;; L6 pointer until the synthesis + dispatch lands. Must
        ;; precede the F67 L4 `function` rejection arm below so the
        ;; `class` prefix disambiguates before `function` is seen.
        (and (at? tokens p :ident)
             (= "class" (t-value (peek-tok tokens p)))
             (at? tokens (inc p) :kw "function"))
        (let [[cf p2] (parse-class-function-decl tokens p)]
          (recur (conj body cf) (skip-newlines tokens p2)))

        ;; F67 L4 — `function` keyword inside atom body retires with the
        ;; `methods:` section (Proposal G class-body collapse). Free
        ;; functions still live at module scope.
        (at? tokens p :kw "function")
        (fail! tokens p
               (str "F67 L4: `function` keyword inside `def " name "` body is "
                    "retired (Proposal G class-body collapse). Migrate the "
                    "method by category:\n\n"
                    "  - reader → observable: `name(args) :- <expr>`\n"
                    "  - void   → transition: `* <: name(args) -> * then: <body>`\n"
                    "  - writer → transition: `* <: name(args) -> * then: <update>`\n\n"
                    "Utility functions unrelated to `self` belong at module "
                    "scope (`def name(args) -> T: body`). See spec §1.11."))

        ;; F67 L4 — spec-canonical method form `def name(args) -> T: body`
        ;; inside a class body retires with `methods:` (Proposal G collapse).
        (at-def-method? tokens p)
        (fail! tokens p
               (str "F67 L4: `def " (t-value (peek-tok tokens (inc p)))
                    "(args)` method form inside `def " name "` body is "
                    "retired (Proposal G class-body collapse). Migrate the "
                    "method by category:\n\n"
                    "  - reader → observable: `" (t-value (peek-tok tokens (inc p)))
                    "(args) :- <expr>`\n"
                    "  - void   → transition: `* <: " (t-value (peek-tok tokens (inc p)))
                    "(args) -> * then: <body>`\n"
                    "  - writer → transition: `* <: " (t-value (peek-tok tokens (inc p)))
                    "(args) -> * then: <update>`\n\n"
                    "Utility functions unrelated to `self` belong at module "
                    "scope. See spec §1.11."))

        ;; F34 L3 — nested-atom slot `def <name>: <TypeName>[(args)]`
        ;; at atom-body position is retired. The spec-canonical shape
        ;; is a `data:` field (§1.11 line 960): `<name>: <TypeName> =
        ;; <TypeName>([args])`. Emit an actionable fix-it pointing at
        ;; the rewrite.
        (and (at? tokens p :kw "def")
             (at? tokens (inc p) :ident)
             (at? tokens (+ p 2) :punct ":"))
        (let [nm  (t-value (peek-tok tokens (inc p)))
              ty-tok (peek-tok tokens (+ p 3))
              ty-nm  (if (and ty-tok (= :ident (t-kind ty-tok)))
                       (t-value ty-tok)
                       "TypeName")]
          (fail! tokens p
                 (str "F34 L3: `def " nm ": " ty-nm "` nested-atom slot form "
                      "inside `def " name "` body is retired. Migrate to the "
                      "spec-canonical `data:` field shape (§1.11):\n\n"
                      "  " nm ": " ty-nm " = " ty-nm "()\n\n"
                      "If the original carried ctor args (`def " nm ": " ty-nm
                      "(args...)`), preserve them on the RHS ctor call:\n\n"
                      "  " nm ": " ty-nm " = " ty-nm "(args...)")))

        ;; `state:` section retired at v1.0 (spec §1.11 class-body-collapse
        ;; F67 L5). All fields sit at def-body top level with no wrapping
        ;; section header; mutation is only through transitions. Targeted
        ;; diagnostic beats the generic "expected …" fall-through so the
        ;; migration path is visible.
        (and (at? tokens p :ident)
             (= "state" (t-value (peek-tok tokens p)))
             (at? tokens (inc p) :punct ":"))
        (fail! tokens p
               (str "`state:` section header is retired at v1.0 "
                    "(class-body-collapse F67 L5). Migrate each field to "
                    "def-body top level with no wrapping header:\n\n"
                    "  def " name ":\n"
                    "      <field>: <Type> [= <default>]     ← was inside `state:`\n"
                    "      <another field>: <Type>\n\n"
                    "Mutation still happens only through transitions. "
                    "See spec §1.11."))

        ;; L5 / D3 atomic retirement — retain the reserved token solely
        ;; to give both retired spellings one actionable hard error.
        (at? tokens p :kw "accepts")
        (fail! tokens p
               (str "`accepts:` and `accepts name(...)` are retired at "
                    "v1.0. Declare protocol members directly in `def " name
                    "` using `<: name(args)` for async receives or "
                    "`name(args) :- ReturnType` for sync observations."))

        ;; F68 L2 (Proposal H) — `case <ident>` prefix marks an enum
        ;; case. Presence of any `:enum-case` node in the body triggers
        ;; the enum reshape at end-of-body-loop.
        ;;
        ;; F16 L1 (2026-08-23) — optional parenthesized parameter list
        ;; carries payload fields (`case completed(receipt: Receipt)`).
        ;; Named-only per §3.7.1 (no positional destructuring). Defaults
        ;; on payload params are rejected — the case *value* is the
        ;; payload; a default would confuse construction semantics.
        ;;
        ;; F16 L2 (2026-08-23) — optional `= <int-literal>` suffix marks
        ;; a raw-value case (spec §3.3). Raw values and payloads are
        ;; mutually exclusive on a single case (mixing them is a shape
        ;; conflict). Enum-level reshape validates all-or-none raw
        ;; values across cases.
        (at? tokens p :kw "case")
        (let [[_ p2]     (expect tokens p :kw "case")
              [nm p3]    (expect tokens p2 :ident)
              [payload p4] (if (at? tokens p3 :punct "(")
                             (parse-param-list-parens tokens p3)
                             [[] p3])
              _          (doseq [pp payload]
                           (when (:default pp)
                             (fail! tokens p3
                                    (str "F16 L1: default on enum payload field `"
                                         (:name pp) "` in case `" nm
                                         "` — payload fields don't carry defaults "
                                         "(the case *value* is the payload; a default "
                                         "would confuse construction semantics). "
                                         "Drop the `= <default>`."))))
              [raw-val p5] (if (at? tokens p4 :glyph "=")
                             (let [after-eq (inc p4)]
                               (when (seq payload)
                                 (fail! tokens after-eq
                                        (str "F16 L2: case `" nm "` has both a "
                                             "payload `(...)` and a raw value `= ...` "
                                             "— these are mutually exclusive shapes "
                                             "per §3.3. Payload enums carry associated "
                                             "values; raw-value enums attach fixed "
                                             "integer values to bare cases. Pick one.")))
                               (when-not (at? tokens after-eq :int)
                                 (fail! tokens after-eq
                                        (str "F16 L2: raw value on case `" nm
                                             "` must be an integer literal (v1.0 "
                                             "scope — string/float raw values are "
                                             "deferred to a follow-on landing).")))
                               [(t-value (peek-tok tokens after-eq)) (inc after-eq)])
                             [nil p4])]
          (recur (conj body (cond-> {:node :enum-case :case-name nm :payload payload}
                              raw-val (assoc :raw-value raw-val)))
                 (skip-newlines tokens p5)))

        (looks-like-observable? tokens p)
        (let [[o p2] (parse-observable tokens p)]
          (recur (conj body o) (skip-newlines tokens p2)))

        (looks-like-handler-decl? tokens p)
        (let [[h p2] (parse-handler-decl tokens p)]
          (recur (conj body h) (skip-newlines tokens p2)))

        ;; F39 L8 (spec §4.10.9) — `on_nil:` section carries per-method
        ;; nil-receiver fallback constants:
        ;;   on_nil:
        ;;       method_a: <const-expr>
        ;;       method_b: <const-expr>
        ;; Each `method_name: value` pair binds the fallback returned at
        ;; a call site when the receiver of `method_name` is nil.
        ;; Validation (target existence, value type-check, const-shape)
        ;; happens at lower-time in atom-env build.
        (and (at? tokens p :ident)
             (= "on_nil" (t-value (peek-tok tokens p)))
             (at? tokens (inc p) :punct ":"))
        (let [[s p2] (parse-on-nil-section tokens p)]
          (recur (conj body s) (skip-newlines tokens p2)))

        ;; F67 L5 — top-level field declaration (replaces `data:` section
        ;; per Proposal D). Must precede `looks-like-transition?` because
        ;; a bare ident matches both; the `:punct ":"` guard picks fields
        ;; and leaves phase-source idents (`phase <:`, `phase ->`) to the
        ;; transition branch.
        (looks-like-field-decl? tokens p)
        (let [[f p2] (parse-field tokens p)]
          (recur (conj body f) (skip-newlines tokens p2)))

        (looks-like-transition? tokens p)
        (let [[t p2] (parse-transition tokens p)]
          (recur (conj body t) (skip-newlines tokens p2)))

        :else
        (fail! tokens p "expected direct use (`use name: Type`), field decl (`name: Type [= default]`), own-line `|:` proposition, on_nil:, observable/signature (`name[(args)] :- expr-or-type`), async signature (`<: name(args)`), or transition inside def-atom body")))))

;; ---------------------------------------------------------------------------
;; F68 L1 (Proposal H) — structural detection helpers for `def` reshape.
;;
;; A `def Foo:` body is inspected after parse. If it structurally matches
;; a record or protocol shape, the parsed :def-atom node is reshaped into
;; the appropriate existing AST kind so downstream env builders keep
;; working unchanged.
;; ---------------------------------------------------------------------------

(defn- def-atom-body-has-transition? [body]
  (boolean (some #(= :transition (:node %)) body)))

(defn- def-atom-body-has-use? [body]
  (boolean (some #(= :use-section (:node %)) body)))

(def ^:private protocol-signature-node-kinds
  #{:observation-signature :receive-signature})

(defn- def-atom-body-has-signature? [body]
  (boolean (some #(contains? protocol-signature-node-kinds (:node %)) body)))

(defn- def-atom-body-has-case? [body]
  (boolean (some #(= :enum-case (:node %)) body)))

(defn- field-has-ident-callee-ctor-default?
  "F34 L2 signal: a top-level field whose default is a `Type()` call
   with a bare-`:ident` callee whose name starts with an uppercase
   letter (type-name convention). Syntactic — we can't check atom-env
   at parse time, but this matches the post-F34 replacement shape for
   `def slot: Type` nested slots. Lowercase-first callees stay
   record-shaped so `f: Int = foo()` (invalid) reshapes to record and
   trips F40's const-check with the expected diagnostic."
  [field-node]
  (let [d (:default field-node)]
    (and (map? d)
         (= :call (:node d))
         (map? (:callee d))
         (= :ident (:node (:callee d)))
         (let [nm (:name (:callee d))]
           (and (string? nm)
                (pos? (count nm))
                (Character/isUpperCase (first nm)))))))

(defn- def-atom-body-has-atom-ctor-field-default?
  "Fields are folded into a synthetic `:data-section` by
   `fold-top-level-fields` before this check runs; peek inside the
   section's `:fields` collection."
  [body]
  (let [dsec (first (filter #(= :data-section (:node %)) body))]
    (boolean (some field-has-ident-callee-ctor-default? (:fields dsec)))))

(defn- def-atom->record
  "Reshape a parsed :def-atom into a :record node. Rejects at parse if
   the `def` header carried ctor params — records use field-derived
   auto-ctors, no explicit ctor-param list. Body-item shape matches
   what `parse-record` produces (sections stay as-is).

   F69 L2 (2026-08-18): stamps `:inferred-kind :structural` for the
   unified body-shape lookup — reader-consumers use kind-of/def-kind
   instead of ad-hoc env membership."
  [def-atom tokens pos-for-diag]
  (when (seq (:ctor-params def-atom))
    (fail! tokens pos-for-diag
           (str "F68 L1: `def " (:name def-atom) "(...)` with ctor "
                "params has a record-shaped body (no transitions, no "
                "direct `use`) — records use field-derived auto-ctors and "
                "cannot carry an explicit ctor-param list. Either "
                "add a transition (to promote to a class), remove the "
                "ctor params (to keep as a record), or list fields "
                "with defaults inside the body.")))
  {:node          :record
   :name          (:name def-atom)
   :sections      (:body def-atom)
   :inferred-kind :structural})

(defn- def-atom-signatures->protocol
  "Reshape a def containing signature-only members into the downstream
   protocol class shape. Signature nodes stay in `:body`; lower.clj derives
   the internal accepts/witness entries from them. Bodyful observables are
   retained as optional default implementations. State, transitions, and
   other class body items cannot mix with protocol signatures."
  [def-atom tokens pos-for-diag]
  (when (seq (:ctor-params def-atom))
    (fail! tokens pos-for-diag
           (str "F68 L1: `def " (:name def-atom) "(...)` with ctor "
                "params contains signature-only members — protocols declare "
                "signatures only and carry no state, so ctor params are "
                "not permitted. Drop the ctor params to keep as a "
                "protocol, or remove the signature-only members to promote "
                "to a concrete def.")))
  (let [body    (:body def-atom)
        invalid (first (remove #(contains? #{:observation-signature
                                             :receive-signature
                                             :observable}
                                           (:node %))
                               body))]
    (when invalid
      (fail! tokens pos-for-diag
             (str "signature-only protocol `" (:name def-atom) "` mixes "
                  "protocol declarations with state, transitions, or other "
                  "concrete body items. A protocol body may contain only "
                  "`<: name(args)`, `name(args) :- ReturnType`, and "
                  "bodyful observable defaults.")))
    {:node          :class
     :name          (:name def-atom)
     :kind          :protocol
     :conforms      []
     :body          (vec body)
     :inferred-kind :protocol}))

(defn- def-atom->enum
  "F68 L2 — reshape a parsed :def-atom into a :enum node when the body
   contains `case <ident>` prefix markers. Matches the AST shape
   `parse-enum` produces (`{:node :enum :name X :cases [str…]}`).
   Rejects at parse if the body carries non-case items (fields,
   observables, transitions) or ctor params — cases are exclusive."
  [def-atom tokens pos-for-diag]
  (when (seq (:ctor-params def-atom))
    (fail! tokens pos-for-diag
           (str "F68 L2: `def " (:name def-atom) "(...)` with ctor "
                "params contains `case` markers — enums declare a "
                "closed set of cases and carry no ctor state. Drop "
                "the ctor params to keep as an enum, or remove the "
                "`case` markers to promote to a class/atom.")))
  (let [body   (:body def-atom)
        cases  (filterv #(= :enum-case (:node %)) body)
        others (remove #(= :enum-case (:node %)) body)]
    (when (seq others)
      (fail! tokens pos-for-diag
             (str "F68 L2: `def " (:name def-atom) ":` mixes `case` "
                  "markers with other body items (fields, observables, "
                  "transitions, direct `use`, protocol signatures). Enums are a closed "
                  "set of cases with no per-instance state. Move the "
                  "non-case items to a separate declaration, or drop "
                  "the `case` markers if this was intended as a class.")))
    ;; F16 L1 — preserve payload per case. Bare cases carry `[]`.
    ;; Every case entry: {:name string :payload [{:name :type} ...]}.
    ;; `:cases` (vector of names) retained for legacy readers; new
    ;; readers (lower env builder) should consult `:case-entries`.
    ;;
    ;; F16 L2 — if any case carries `:raw-value`, all cases must.
    ;; The raw-type is the smallest fitting unsigned integer type.
    ;; A raw-type override surface (if ever needed) awaits a design;
    ;; the prior `@metadata(raw_type: ...)` proposal retired 2026-08-30.
    (let [with-raw    (filterv :raw-value cases)
          without-raw (remove :raw-value cases)]
      (when (and (seq with-raw) (seq without-raw))
        (fail! tokens pos-for-diag
               (str "F16 L2: enum `" (:name def-atom) "` mixes cases with "
                    "explicit raw values (`case " (:case-name (first with-raw))
                    " = " (:raw-value (first with-raw)) "`) and cases without "
                    "(`case " (:case-name (first without-raw)) "`). Either "
                    "assign raw values to every case or drop them from all "
                    "cases (spec §3.3 raw-value enums are all-or-none)."))))
    (when (some :raw-value cases)
      (when (some (comp seq :payload) cases)
        (fail! tokens pos-for-diag
               (str "F16 L2: enum `" (:name def-atom) "` combines raw-value "
                    "cases with payload cases. These shapes are distinct per "
                    "§3.3 — raw-value enums attach fixed integer values; "
                    "payload enums carry associated values. Pick one shape."))))
    (let [raw-values (when (some :raw-value cases)
                       (mapv :raw-value cases))
          raw-type   (when raw-values
                       (let [mn (apply min raw-values)
                             mx (apply max raw-values)]
                         (cond
                           (and (<= 0 mn) (<= mx 255))         "UInt8"
                           (and (<= 0 mn) (<= mx 65535))       "UInt16"
                           (and (<= 0 mn) (<= mx 4294967295))  "UInt32"
                           :else                                "UInt64")))]
      (cond-> {:node          :enum
               :name          (:name def-atom)
               :cases         (mapv :case-name cases)
               :case-entries  (mapv (fn [c]
                                      (cond-> {:name    (:case-name c)
                                                :payload (or (:payload c) [])}
                                        (:raw-value c) (assoc :raw-value (:raw-value c))))
                                    cases)
               :inferred-kind :tagged-variant}
        raw-values (assoc :raw-values raw-values)
        raw-type   (assoc :raw-type   raw-type)))))

;; ---------------------------------------------------------------------------
;; F9 protocol signatures + F9 L3 extend surfaces. Post F68 L6b
;; (Proposal H):
;; `class` / `protocol` / `record` / `enum` sugar keywords and their
;; supporting parsers (parse-class, parse-record, parse-enum,
;; parse-class-section, fold-class-body, parse-metadata-section,
;; legal-kinds) are all deleted; the sole class/protocol/record/enum
;; surface is `def X:` with structural detection in parse-def-atom.
;; Protocol members now parse directly in `def` bodies as `<: name(args)`
;; and `name(args) :- ReturnType`. `accepts:` and `accepts name(...)` are
;; hard-retired; conformance parsing below remains for `extend` blocks.
;; ---------------------------------------------------------------------------

(defn- parse-conforms-line
  "Body-form conformance declaration:
     conforms: <TypeName>
   Multiple `conforms:` lines legal per §3.4.5 rule 5 (union semantics)."
  [tokens pos]
  (let [[_ p]  (expect tokens pos :ident)                      ; \"conforms\"
        [_ p]  (expect tokens p :punct ":")
        [ty p] (parse-type tokens p)
        [_ p]  (expect tokens p :newline)]
    [{:node :conforms-line :type ty} p]))

(defn- parse-header-parens-conformances
  "Parse `(P1, P2, ...)` after a class/protocol name. Empty parens `()` is
   a hard error (nothing conforms to nothing — this is not the Unit type
   at type-position, per §3.4.5 rule not-said-but-implied)."
  [tokens pos]
  (let [[_ p] (expect tokens pos :punct "(")]
    (when (at? tokens p :punct ")")
      (fail! tokens p
             "empty conformance list `()` — omit the parens if no protocols."))
    (loop [confs [] p p]
      (let [[ty p2] (parse-type tokens p)]
        (cond
          (at? tokens p2 :punct ",")
          (recur (conj confs ty) (inc p2))
          (at? tokens p2 :punct ")")
          [(conj confs ty) (inc p2)]
          :else
          (fail! tokens p2
                 "expected `,` or `)` in conformance list"))))))


;; ---------------------------------------------------------------------------
;; F9 Landing 3 — `extend Foo:` top-level form.
;;
;;   extend Foo:                    → :extend-class :target Foo
;;       methods:
;;           function bar(self): ...
;;
;;   extend Foo(NewProtocol):       → header-parens add to :conforms
;;       ...
;;   extend Foo:
;;       conforms: NewProtocol            → body-form adds to :conforms
;;
;; Per §3.3.2 "What extensions cannot add", extension bodies accept only
;; own-line `|:` propositions and conforms:. `data:`, `state:`, direct `use`,
;; `cases:`, and `deprecated:` are rejected at parse with a targeted
;; §3.3.2 pointer. The hard-retired `metadata:` header has no registry
;; section backend and reaches the ordinary extension-body diagnostic.
;; Method entries carry the `replace` modifier via `:replace?` (parsed by
;; shared parse-methods-section).
;;
;; Extension target-name resolution is deferred to lower (L4 composition
;; consumes extension-env; unknown-target diagnostics fire there when the
;; full symbol table is available).
;; ---------------------------------------------------------------------------

(defn- parse-extend-section
  "Dispatch one section inside an `extend Foo:` body. Strict —
   rejects constructs spec §3.3.2 forbids (data/state/use/cases/metadata/
   deprecated). Accepts observables (F67 L4), wildcard self-transitions
   (F67 L4), own-line `|:` propositions, conforms:. `methods:` retires
   (F67 L4).

   F67 L4c — a `replace` keyword prefix marks the following observable
   or wildcard-transition as intentionally shadowing an existing
   declaration on the target (§3.3.2 rule 4). The marker propagates
   to the parsed node via `:replace? true`; composition consumes it
   in `pick-winner-with-warnings!`. `replace` is legal only in
   extension bodies — non-extension class bodies reject it in
   `parse-class-section`."
  [tokens pos target]
  (cond
    ;; F67 L4c — `replace` keyword prefixes an observable or transition.
    (at? tokens pos :kw "replace")
    (let [p (inc pos)]
      (cond
        (looks-like-observable? tokens p)
        (let [[obs p2] (parse-observable tokens p)]
          [(assoc obs :replace? true) p2])

        (at? tokens p :glyph "*")
        (let [[tr p2] (parse-transition tokens p)]
          [(assoc tr :replace? true) p2])

        :else
        (fail! tokens p
               (str "`replace` inside `extend " target "` must "
                    "prefix an observable (`replace name[(args)] :- expr`) "
                    "or a wildcard self-transition (`replace * <: msg(args) "
                    "-> * then: <body>`). See §3.3.2 rule 4."))))

    ;; L2b — extension-added invariants use the same normalized
    ;; proposition node as def-body invariants. The legacy section
    ;; header has no registry or hardcoded fallback.
    (at? tokens pos :glyph "|:")
    (parse-proposition tokens pos)

    ;; L2b retirement diagnostic — same targeted fix-it as def-body,
    ;; adjusted for extend-body context. Extensions can add propositions
    ;; (they compose additively per section-registry :allowed-in-extend?)
    ;; but must use `|:`, not the retired header.
    (at? tokens pos :kw "invariants")
    (fail! tokens pos
           (str "`invariants:` section header is retired at v1.0 "
                "(four-position `|:` unification, ratified 2026-08-25). "
                "Migrate each invariant to a `|:` proposition on its own "
                "line at extend-body top level:\n\n"
                "  extend " target ":\n"
                "      |: <proposition>          ← was `invariants:` entry\n\n"
                "Extension propositions compose additively with the def's "
                "existing propositions. See spec §1.11 and §3.4.2."))

    ;; F8 L3b.iii — behavioral construct dispatch via registry, gated
    ;; by `:allowed-in-extend?` per §3.3.2. Direct `use` and
    ;; `deprecated:` remain forbidden here; `:invariants` is parser-less
    ;; and enters through the `|:` arm above.
    (and (at? tokens pos :kw)
         (section/allowed-in-extend? (t-value (peek-tok tokens pos)))
         (section/behavioral-parser (t-value (peek-tok tokens pos))))
    (let [parser (section/behavioral-parser (t-value (peek-tok tokens pos)))]
      (parser tokens pos))

    (and (at? tokens pos :ident) (= "conforms" (t-value (peek-tok tokens pos)))
         (at? tokens (inc pos) :punct ":"))
    (parse-conforms-line tokens pos)

    ;; F67 L4 — observable on extension (parametric or zero-arg).
    (looks-like-observable? tokens pos)
    (parse-observable tokens pos)

    ;; F67 L4 — wildcard self-transition (`* <: msg(args) -> *`) on
    ;; extension. Extensions can't add explicit-phase transitions
    ;; (state-machine structure invariance §3.3.2), so we accept the
    ;; transition here and defer the wildcard-only check to lower.
    (at? tokens pos :glyph "*")
    (parse-transition tokens pos)

    ;; F67 L5 — under Proposal D, fields live at class-body top level
    ;; (no `data:` header). Extensions still can't add state; the
    ;; message points at the new surface for consistency.
    (looks-like-field-decl? tokens pos)
    (fail! tokens pos
           (str "field declaration `" (t-value (peek-tok tokens pos))
                ": …` is not permitted inside `extend " target
                "` — extensions cannot add state (§3.3.2). Add fields "
                "at the original class definition."))

    (at? tokens pos :kw "use")
    (fail! tokens pos
           (str "`use <name>: <Type>` is not permitted inside `extend " target
                "` — extensions cannot add capabilities (§3.3.2). "
                "Declare the direct `use` line at the original class definition."))

    (at? tokens pos :kw "deprecated")
    (fail! tokens pos
           (str "`deprecated:` is not permitted inside `extend "
                target "` — deprecation belongs on the original definition."))

    (and (at? tokens pos :ident) (= "state" (t-value (peek-tok tokens pos)))
         (at? tokens (inc pos) :punct ":"))
    (fail! tokens pos
           (str "`state:` section is retired at v1.0 — and extensions "
                "cannot add fields anyway (§3.3.2, §1.11)."))

    :else nil))

(defn- parse-extend-class
  "Parse a top-level `extend Foo:` form. Header-parens conformances and
   body-form `conforms:` lines stack additively (§3.4.5 rule 5). Rejects
   nested `extend` blocks (top-level form only, per plan Q3.3)."
  [tokens pos]
  (let [[_ p]   (expect tokens pos :kw "extend")
        [target p] (expect tokens p :ident)
        [header-conforms p] (if (at? tokens p :punct "(")
                              (parse-header-parens-conformances tokens p)
                              [[] p])
        [_ p]   (expect tokens p :punct ":")
        [_ p]   (expect tokens p :newline)
        [_ p]   (expect tokens p :indent)]
    (loop [body [] p p]
      (cond
        (at? tokens p :dedent)
        (let [conforms-lines (filter #(= :conforms-line (:node %)) body)
              other-sections (remove #(= :conforms-line (:node %)) body)
              body-conforms  (mapv :type conforms-lines)]
          [{:node     :extend-class
            :target   target
            :conforms (into (vec header-conforms) body-conforms)
            :body     (vec other-sections)}
           (inc p)])

        (at? tokens p :newline)
        (recur body (inc p))

        (at? tokens p :kw "extend")
        (fail! tokens p
               (str "nested `extend` blocks are not permitted — extension "
                    "is a top-level form only. See F9 plan Q3.3."))

        :else
        (if-let [[sec p2] (parse-extend-section tokens p target)]
          (recur (conj body sec) (skip-newlines tokens p2))
          (fail! tokens p
                 (str "expected observable (`name[(args)] :- expr`), "
                      "transition (`* <: msg(args) -> * …`), own-line `|:`, "
                      "conforms:, or dedent in `extend " target "` body")))))))

;; ---------------------------------------------------------------------------
;; Module header  — module a.b.c
;; from-import   — from a.b.c import X, Y, Z
;; ---------------------------------------------------------------------------

(defn- parse-dotted [tokens pos]
  (let [[first p] (expect tokens pos :ident)]
    (loop [parts [first] p p]
      (if (and (at? tokens p :punct ".") (at? tokens (inc p) :ident))
        (recur (conj parts (t-value (peek-tok tokens (inc p)))) (+ p 2))
        [(str/join "." parts) p]))))

(defn- parse-module-decl-internal
  "Internal-only — accepts `module <name>` and produces a `:module`
   node. NOT reachable from `parse` (user source); reserved for
   `parse-internal-with-module-nodes` which the multi-file build
   uses to attribute per-file provenance to the resolver."
  [tokens pos]
  (let [[_ p]   (expect tokens pos :kw "module")
        [dotted p] (parse-dotted tokens p)
        [_ p]   (expect tokens p :newline)]
    [{:node :module :name dotted} p]))

(defn- reject-module-decl!
  "F73 (2026-08-20) — the `module com.example.foo` header is retired.
   Module identity is the file path relative to the source root
   (Python/Go style); the resolver walks the tree at Layer 6. An
   explicit declaration adds a second source of truth that can drift
   from the path, buys nothing the resolver can't infer, and — under
   `def main` — was already ignored by the lowerer."
  [tokens pos]
  (fail! tokens pos
         (str "`module <name>` declaration is retired (2026-08-20). "
              "Module identity is the file path relative to the source "
              "root — the compiler walks the tree; no explicit "
              "declaration is needed. Delete this line. If you were "
              "using the declared name for imports elsewhere, rely on "
              "the file path instead (e.g. `from com.example.foo import "
              "X` where `com/example/foo.cxc` is the file).")))

(defn- foreign-lang-head?
  "F52 L5 — recognize `import <lang> \"header\"` / `from <lang>
   \"header\" import ...` head. `<lang>` is a bare ident whose value
   is `objc` or `c`, followed by a string literal (the header path).
   Distinguishing feature vs regular imports: regular import is
   `<lang>` followed by `.` (dotted) or `as`/newline (bare module
   name); foreign import is `<lang>` followed by a string literal."
  [tokens pos]
  (and (at? tokens pos :ident)
       (contains? #{"objc" "c"} (t-value (peek-tok tokens pos)))
       (at? tokens (inc pos) :string)))

(defn- parse-from-import
  "`from X import a[, b, c[ as d]]` at top level.

   F52 L2 (2026-08-23) — each name may be aliased with `as <ident>`:
     from foo import a           → {:name \"a\" :as nil}
     from foo import a as b      → {:name \"a\" :as \"b\"}
     from foo import a, b as c   → mixed alias/plain

   `:names` is a vector of `{:name :as}` maps post-L2. Pre-L2 the
   shape was `[string...]`; downstream readers migrate to the new
   shape via a `name-of` helper (or accept both).

   F52 L5 (2026-08-23) — foreign-import variant:
     from objc \"Foundation/NSString.h\" import NSString, NSData
     from c    \"stdio.h\"               import printf
   Names in a foreign-import list register as opaque external
   symbols (per spec §7 note on external interop); real header
   validation is F54's job. Distinguishing token: string literal
   after the lang ident (regular imports would have `.` or `import`
   there)."
  [tokens pos]
  (let [[_ p]      (expect tokens pos :kw "from")]
    (if (foreign-lang-head? tokens p)
      (let [lang        (keyword (t-value (peek-tok tokens p)))
            header      (t-value (peek-tok tokens (inc p)))
            p           (+ p 2)
            [_ p]       (expect tokens p :kw "import")]
        (loop [names [] p p]
          (let [[n p2]     (expect tokens p :ident)
                [alias p3] (if (at? tokens p2 :kw "as")
                             (let [[a p4] (expect tokens (inc p2) :ident)]
                               [a p4])
                             [nil p2])
                entry      {:name n :as alias}]
            (cond
              (at? tokens p3 :punct ",")
              (recur (conj names entry) (inc p3))
              (at? tokens p3 :newline)
              [{:node :from-foreign-import :lang lang :header header
                :names (conj names entry)} (inc p3)]
              :else (fail! tokens p3 "expected `,` or newline in from-import")))))
      (let [[dotted p] (parse-dotted tokens p)
            [_ p]      (expect tokens p :kw "import")]
        (loop [names [] p p]
          (let [[n p2]     (expect tokens p :ident)
                [alias p3] (if (at? tokens p2 :kw "as")
                             (let [[a p4] (expect tokens (inc p2) :ident)]
                               [a p4])
                             [nil p2])
                entry      {:name n :as alias}]
            (cond
              (at? tokens p3 :punct ",")
              (recur (conj names entry) (inc p3))
              (at? tokens p3 :newline)
              [{:node :from-import :from dotted :names (conj names entry)} (inc p3)]
              :else (fail! tokens p3 "expected `,` or newline in from-import"))))))))

(defn- parse-import
  "F52 L1 — `import X` and `import X as Y` at top level.
     import foo.bar          → {:node :import :from \"foo.bar\" :as nil}
     import foo.bar as baz   → {:node :import :from \"foo.bar\" :as \"baz\"}

   Cross-references the F51 L2 dep-graph builder — `import X` counts
   as a dependency on `X` from the current module, same as `from X
   import ...`. Namespace-scoped binding (`X.member` resolution) waits
   on F51 L3.

   F52 L5 (2026-08-23) — foreign-import variant:
     import objc \"Foundation/NSString.h\"
     import c    \"stdio.h\"
   Produces `{:node :foreign-import :lang :objc|:c :header \"...\"}`.
   Header validity is F54's job — L5 is parse-only. No dep-graph
   entry (foreign modules aren't part of the user's module set)."
  [tokens pos]
  (let [[_ p]  (expect tokens pos :kw "import")]
    (if (foreign-lang-head? tokens p)
      (let [lang   (keyword (t-value (peek-tok tokens p)))
            header (t-value (peek-tok tokens (inc p)))
            p      (+ p 2)
            [_ p]  (expect tokens p :newline)]
        [{:node :foreign-import :lang lang :header header} p])
      (let [[dotted p] (parse-dotted tokens p)
            [alias p]  (if (at? tokens p :kw "as")
                         (let [[nm p2] (expect tokens (inc p) :ident)]
                           [nm p2])
                         [nil p])
            [_ p]      (expect tokens p :newline)]
        [{:node :import :from dotted :as alias} p]))))

;; ---------------------------------------------------------------------------
;; Annotation grammar retired 2026-08-30 (annotation-grammar-retirement
;; wave). `parse-annotation` + `parse-annotated-def` deleted; the L3a
;; `@name(args)` grammar has no parser entry point at v1.0. The
;; `:annotation-prefix` lex token is also retired (see cxc.lex); `@`
;; at any position throws a targeted retirement diagnostic. Prior
;; consumers:
;;
;; - Q10-D `@plugin(...)` on `module <name>` → reversed; F73 (module
;;   header retirement) stands unconditionally. Plugin manifest lives
;;   as `def plugin:` block (see spec §7.2).
;; - L3c `@metadata(...)` → retired; kind info via body-shape
;;   inference (§3.3). F53 never end-to-end consumed the pipe (drift).
;; - L3b `@deprecated(...)` → retired; K3 walker deleted (cxc.build).
;;   Deprecation expressed as comment/user convention at v1.0.
;;
;; See decisions-frozen-2026-08-30.md for the retirement rationale.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Top-level dispatch
;; ---------------------------------------------------------------------------

(defn- parse-top [tokens pos]
  (let [p (skip-newlines tokens pos)]
    (cond
      (at? tokens p :eof)          [nil p]
      (at? tokens p :kw "module")  (reject-module-decl! tokens p)
      (at? tokens p :kw "from")    (parse-from-import tokens p)
      (at? tokens p :kw "import")  (parse-import tokens p)
      (at? tokens p :kw "let")
      (fail! tokens p
             (str "module-scope `let` retired 2026-08-31 (R3, "
                  "decisions-frozen-2026-08-31.md §R3). Module-scope "
                  "constants live in a `def` record with compile-time-"
                  "constant field defaults, e.g. `def Constants:\\n"
                  "    PROTOCOL_VERSION: UInt16! = 13` — consumers "
                  "reference as `Constants.PROTOCOL_VERSION`. For A7 "
                  "`project.cxc` manifests, drop the `let project = ` "
                  "prefix and leave the bare `Project(...)` expression."))
      (at? tokens p :kw "def")     (parse-def-atom tokens p)

      ;; F9 L3 / L2b — `extend Foo:` at top level. Extension body is
      ;; strict per §3.3.2: observables/transitions, `|:`, conforms.
      (at? tokens p :kw "extend")   (parse-extend-class tokens p)

      ;; F9 L1 — spec §3.4.1 removed `sealed protocol`. Enumerations
      ;; cover the closed-sum-type case with better ergonomics.
      (and (at? tokens p :ident) (= "sealed" (t-value (peek-tok tokens p)))
           (at? tokens (inc p) :kw "protocol"))
      (fail! tokens p
             (str "`sealed protocol` is retired at v1.0 (spec §3.4.1). "
                  "For closed sum types use `enum` with associated values; "
                  "for open protocols (any type may conform), drop `sealed`."))
      ;; `extern <Name>:` section retired at v1.0 (spec §1.12, §2335).
      ;; FFI post-R11 (2026-09-01) is the `Plugin` capability atom
      ;; provisioned to shim atoms via `use plugin: Plugin` inside
      ;; modules that carry a `def plugin:` marker; foreign symbols
      ;; are declared as nested `foreign "..."` sections inside the
      ;; enclosing `def plugin:` body (§1.12, §1.14, §7.2.1, R7+R11).
      ;; `Extern` and `foreign library "..."` module-scope grammar
      ;; retired. Parser landing for `def plugin:` + nested `foreign`
      ;; is future (F74/F75).
      (and (at? tokens p :ident)
           (= "extern" (t-value (peek-tok tokens p))))
      (fail! tokens p
             (str "`extern` as a top-level section keyword is retired at v1.0. "
                  "FFI is a `Plugin` capability atom (R11 2026-09-01) provisioned "
                  "to shims inside `def plugin:` modules; foreign symbols nest "
                  "inside the plugin body (`foreign \"libname\":` sections). "
                  "See spec §1.12, §1.14, §7.2.1."))

      ;; F69 L3 — `record` / `enum` / `class` as declaration-position
      ;; sugar retired at v1.0 (spec §3.3, F69 atom unification 2026-08-18).
      ;; All classes declare via `def X:`; the compiler infers the
      ;; body-shape (structural / tagged-variant / agent) from the body
      ;; contents. The lexer stopped reserving these names as keywords
      ;; at F68 L6b (Proposal H); this diagnostic gives a targeted
      ;; rewrite hint before the parser falls through to the generic
      ;; "unexpected identifier" error.
      (and (at? tokens p :ident)
           (#{"record" "enum" "class"} (t-value (peek-tok tokens p)))
           (at? tokens (inc p) :ident))
      (let [keyword-form (t-value (peek-tok tokens p))
            name-form    (t-value (peek-tok tokens (inc p)))]
        (fail! tokens p
               (str "F69 L3: `" keyword-form " " name-form ":` sugar retired at v1.0. "
                    "Write `def " name-form ":` — the body-shape (fields / cases / "
                    "transitions) tells the compiler what kind of def it is (spec §3.3, "
                    "F69 atom unification 2026-08-18). See "
                    "plans/archive/f69-atom-unification.md.")))

      :else (fail! tokens p "expected top-level form"))))

(defn- parse* [source allow-module?]
  (let [tokens (lex/tokenize source)]
    (loop [nodes [] pos 0]
      (let [pos (skip-newlines tokens pos)]
        (if (at? tokens pos :eof)
          (desugar-chained-comparisons nodes)
          (let [;; Only when explicitly allowed (multi-file build injection):
                ;; route `module <name>` to the internal parser instead of the
                ;; F73 rejection at the top-level dispatch.
                [node pos']
                (if (and allow-module?
                         (at? tokens pos :kw "module"))
                  (parse-module-decl-internal tokens pos)
                  (parse-top tokens pos))]
            (recur (conj nodes node) pos')))))))

(defn parse-expression
  "Parse a cxc source string as a single top-level expression, returning
   the expression AST node. Used by consumers that read constrained
   expression-only source (A7 `project.cxc` manifests being the primary
   caller). Skips leading blank lines / comments; the source must contain
   exactly one expression followed by EOF (trailing blank lines OK).
   Throws with a targeted diagnostic on multi-expression source or on
   any statement-form content."
  [source]
  (let [tokens (lex/tokenize source)
        p0     (skip-newlines tokens 0)
        _      (when (at? tokens p0 :eof)
                 (throw (ex-info "parse-expression: source is empty"
                                 {:kind :cxc-parse-expression-empty})))
        [expr p] (parse-expr tokens p0)
        p        (skip-newlines tokens p)
        _        (when-not (at? tokens p :eof)
                   (throw (ex-info
                           (str "parse-expression: expected single expression "
                                "followed by end-of-file; extra tokens starting "
                                "at " (pr-str (peek-tok tokens p)))
                           {:kind :cxc-parse-expression-trailing-tokens})))]
    expr))

(defn parse
  "Parse a cxc source string into a sequence of top-level AST nodes.
   User-facing: rejects `module <name>` headers (F73, 2026-08-20)."
  [source]
  (parse* source false))

(defn parse-with-build-modules
  "Internal — used by the multi-file build (`compile-directory-to-file`)
   to re-parse a concatenation of per-file sources with synthesized
   `module <derived>` lines prepended for provenance attribution.
   Not for user source; the per-file first-pass parse used by
   `compile-directory-to-file` still uses `parse` and rejects any
   user-authored `module` line."
  [source]
  (parse* source true))
