(ns cxc.parse
  "Parser for cxc. Hand-rolled recursive-descent over the token stream
   produced by cxc.lex. Grown incrementally against design/examples/*.cxc.

   AST shape: Clojure maps with :node key naming the node type. Every node
   carries :line for diagnostics. Parser is state-monadic in style —
   functions take [tokens pos] and return [node new-pos] or throw ex-info
   on parse error.

   Coverage target: parse design/examples/game-toys.cxc end-to-end.
   Current coverage: module header, from-import, record, method signatures
   and bodies (partial — expressions still limited)."
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
(defn- t-col   [tok] (nth tok 3))

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

(declare parse-primary parse-args-parens parse-msg-args-parens parse-list-literal parse-emit-message looks-like-msg-payload-parens? parse-do-block-expr parse-if-expr)

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

(defn- parse-binop [tokens pos min-prec]
  (loop [[lhs p] (parse-unary tokens pos)]
    (let [t (peek-tok tokens p)
          v (when t (t-value t))
          prec (glyph-precedence v)]
      (if (and prec (>= prec min-prec) (binary-glyph? tokens p))
        (let [[rhs p2] (parse-binop tokens (inc p) (inc prec))]
          (recur [{:node :binop :op v :left lhs :right rhs} p2]))
        [lhs p]))))

;; ---------------------------------------------------------------------------
;; F69 L4 — `<:` expression-position dispatch (spec §3.7).
;;
;; Grammar:
;;   <scrutinee_expr>:
;;       <: pattern: body_expr
;;       <: pattern: body_expr
;;       <: _: body_expr
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
     _                          → wildcard
     name                       → case with no payload
     name(binding, binding...)  → case with payload bindings
     <int-lit>                  → int literal (L4c4)
     true / false               → bool literal (L4c4)

   Deferred to a follow-on: string / char literals (need NSString
   compare), leading-dot `.name(...)` (§3.7.2 target-typing)."
  [tokens pos]
  (cond
    ;; wildcard `_`
    (and (at? tokens pos :ident) (= "_" (t-value (peek-tok tokens pos))))
    [{:kind :wildcard} (inc pos)]

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
                ;; K5 L1 — `if` at expression position (spec §3.8.1).
                ;; Requires an `else` branch per open-Q1 ratified
                ;; decision (missing-else at expression position is a
                ;; compile error). Also supports `else if` chains via
                ;; right-recursion into another parse-if-expr.
                ;; `if let` at expression position is deferred (F39 L7
                ;; ships the statement form); reject with a targeted
                ;; hint here so parse-statement's own `if let` branch
                ;; is unambiguously the only landing site today.
                "if"    (parse-if-expr tokens pos)
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
      (fail! tokens pos "expected an expression"))))

(declare parse-statement)

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

(defn- parse-if-expr
  "K5 L1 — `if` at expression position (spec §3.8.1). Grammar:
     if <cond>: <then-body> (else if <cond>: <then-body>)* else: <else-body>
   `<body>` is a single expression (multi-stmt bodies wrap the branch
   in an explicit `do:` block). `else` is required per open-Q1
   ratified decision — missing-else at expression position is a
   compile error. `else if` desugars right-associatively into a
   nested :if-expr.
   Reject `if let ...` at expression position — F39 L7 ships the
   statement form; expression-position `if let` is deferred."
  [tokens pos]
  (let [[_ p]  (expect tokens pos :kw "if")
        _      (when (at? tokens p :kw "let")
                 (fail! tokens p
                        (str "K5 L1: `if let` at expression position is "
                             "not yet supported — F39 L7 ships the "
                             "statement form only. Rewrite as an outer "
                             "`if let` statement whose body binds the "
                             "value.")))
        [cnd p]      (parse-expr tokens p)
        [_ p]        (expect tokens p :punct ":")
        [then-e p]   (parse-expr tokens p)
        p            (skip-newlines tokens p)
        _            (when-not (at? tokens p :kw "else")
                       (fail! tokens p
                              (str "K5 L1: `if <cond>: <then>` at expression "
                                   "position requires an `else` branch "
                                   "(open-Q1 ratified — missing-else at "
                                   "expression position is a compile error).")))
        [_ p]        (expect tokens p :kw "else")
        ;; `else if …` chain: right-recurse into another parse-if-expr,
        ;; producing a nested :if-expr as the else-branch. Otherwise
        ;; expect `:` and parse a single expression body. Local names
        ;; avoid `cond` — shadowing the Clojure macro is a footgun.
        [else-e p]   (if (at? tokens p :kw "if")
                       (parse-if-expr tokens p)
                       (let [[_ p] (expect tokens p :punct ":")]
                         (parse-expr tokens p)))]
    [{:node :if-expr :cond cnd :then then-e :else else-e} p]))

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
   2026-08-20)."
  [tokens pos]
  (let [[_ p] (expect tokens pos :punct "(")]
    (if (at? tokens p :punct ")")
      [[] (inc p)]
      (loop [args [] p p]
        (let [[name p2] (expect tokens p :ident)
              [_ p3]    (expect tokens p2 :punct ":")
              [ty p4]   (parse-type tokens p3)]
          (cond
            (at? tokens p4 :punct ",")
            (recur (conj args {:name name :type ty}) (inc p4))

            (at? tokens p4 :punct ")")
            [(conj args {:name name :type ty}) (inc p4)]

            :else (fail! tokens p4 "expected `,` or `)` in message shape")))))))

(defn- looks-like-msg-payload-parens?
  "At `pos` = `(`. Distinguish an I5 typed-params payload
   (`(name: Type, ...)`) from a value-args call
   (`(name: value, ...)` or `(positional, ...)`).

   Heuristic: (1) first arg has `<name>:` prefix, (2) value
   after `:` is a bare uppercase-starting ident, and (3) the
   token after that Type ident is NOT `(` (which would mean a
   constructor call like `Box(x: 1)` — a value, not a Type
   annotation).

   Types in cxc conventionally use PascalCase (`Int`, `String`,
   `Player`, `Duration`); values use camelCase or literals.
   Empty parens `()` are treated as a call. Constructor calls
   with uppercase names (`Box(item: OldBox(x: 1))`) correctly
   fall through to the call branch because the inner uppercase
   ident is followed by `(`.

   Note: this is only invoked from the general postfix parser —
   receive-position parsing (`parse-msg-pattern`) has its own
   F67 L3 typed-params branch and doesn't rely on this."
  [tokens pos]
  (let [p (inc pos)]  ; skip `(`
    (cond
      (at? tokens p :punct ")") false

      (and (at? tokens p :ident)
           (at? tokens (inc p) :punct ":"))
      (let [after-colon (+ p 2)
            t           (peek-tok tokens after-colon)]
        (and t
             (= :ident (t-kind t))
             (let [v            (t-value t)
                   after-type   (peek-tok tokens (inc after-colon))]
               (and (not (empty? v))
                    (Character/isUpperCase (first v))
                    ;; NOT followed by `(` — else it's a
                    ;; constructor call being passed as a value.
                    (not (and after-type
                              (= :punct (t-kind after-type))
                              (= "(" (t-value after-type))))))))

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

(defn- warn-function-deprecated!
  "K8 — the `function` method opener is retired at v1.0 (spec §1201,
   §2330); spec canonical is `def`. Prints a per-occurrence
   deprecation warning to *err* naming file/line so users can migrate.
   Non-fatal — parser still accepts the keyword. A `--strict-
   deprecations` flag to promote to error is a follow-on."
  [tokens pos]
  (let [t (peek-tok tokens pos)]
    (binding [*out* *err*]
      (println (str "warning: `function` method opener is deprecated at v1.0 "
                    "(spec §1201, §2330) — use `def` instead. At line "
                    (t-line t) ", col " (t-col t) ".")))))

(defn- parse-function-decl
  "Method declaration in two accepted forms:
     function name(params) -> RetType: <block>   ; legacy — audit K8
     def name(params) -> RetType: <block>        ; spec §2.5 canonical

   The `function` keyword is retired at v1.0 (spec §1201/§2330); we
   still parse it during the grace period but emit a per-occurrence
   deprecation warning via `warn-function-deprecated!`. The `def`
   form is distinguished from the retired nested-atom slot form
   (`def name: TypeName`, F34 L3) by the paren immediately after
   the name — this method-decl path only fires when parens are
   present."
  [tokens pos]
  (let [[_ p]      (cond
                     (at? tokens pos :kw "function")
                     (do (warn-function-deprecated! tokens pos)
                         (expect tokens pos :kw "function"))
                     (at? tokens pos :kw "def")      (expect tokens pos :kw "def")
                     :else (fail! tokens pos "expected `def` or `function` for method"))
        [name p]   (expect tokens p :ident)
        [params p] (parse-param-list-parens tokens p)
        [ret p]    (if (at? tokens p :glyph "->")
                     (parse-type tokens (inc p))
                     [nil p])
        [_ p]      (expect tokens p :punct ":")
        [_ p]      (expect tokens p :newline)
        [body p]   (parse-block tokens p)]
    [{:node :function :name name :params params :return-type ret :body body} p]))

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

(declare parse-statement)

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
   method bodies, function bodies)."
  [tokens pos]
  (let [[_ p]    (expect tokens pos :kw "let")
        [nm p]   (expect tokens p :ident)
        [ty p]   (if (at? tokens p :punct ":")
                   (parse-type tokens (inc p))
                   [nil p])
        [_ p]    (expect tokens p :glyph "=")
        [rhs p]  (parse-expr tokens p)]
    [{:node :let-stmt :name nm :type ty :expr rhs} p]))

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
                  "a `when:` guard on a transition, or an expression-position `<:` "
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
    (let [[e p] (parse-expr tokens (inc pos))]
      (cond
        (and (= :call (:node e))
             (= :dot (:node (:callee e))))
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

        (= :ident (:node e))
        [{:node :emit
          :async? false
          :target nil
          :msg    (:name e)
          :args   []} p]

        :else
        (fail! tokens pos
               (str "prefix `|>` expected a call expression on the right-hand side "
                    "(F70 I6: `|> receiver.method(args)`), or legacy bare form "
                    "(`|> msg(args)` — retired in Stage 3)"))))

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
                "        <: _: <body>\n\n"
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
                    "pattern as a self-transition on an atom with a "
                    "`when:` guard: the loop body becomes `then: field "
                    "=> ...`, the exit condition becomes `when: <cond>` "
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
;; Sections (data:, methods:, use:, invariants:, then:, do:, let:, cases:)
;;   Each section header is `<name>:` followed by :newline :indent … :dedent
;;   Section body content differs per section — dispatch here.
;; ---------------------------------------------------------------------------

(defn- section-header?
  "True if pos looks like `<kw|ident>:` where the keyword names a section."
  [tokens pos section-names]
  (and (or (at? tokens pos :kw) (at? tokens pos :ident))
       (section-names (t-value (peek-tok tokens pos)))
       (at? tokens (inc pos) :punct ":")))

(defn- parse-deprecated-section
  "deprecated: \"<message>\"
   Layer 6 Step 11 — inline body-form deprecation marker. Emits a
   `:deprecated` node the compiler attaches to the enclosing def
   (record or atom). Requires a non-empty string literal — the
   message that use-site warnings will carry."
  [tokens pos]
  (let [[_ p]   (expect tokens pos :kw "deprecated")
        [_ p]   (expect tokens p :punct ":")
        [msg p] (expect tokens p :string)
        _       (when (str/blank? msg)
                  (fail! tokens p "deprecated: message must be non-empty"))
        [_ p]   (expect tokens p :newline)]
    [{:node :deprecated :message msg} p]))

(defn- parse-use-section
  "use:
       cap: SomeCapability
       other_cap: OtherCapability"
  [tokens pos]
  (let [[_ p] (expect tokens pos :kw "use")
        [_ p] (expect tokens p :punct ":")
        [_ p] (expect tokens p :newline)
        [_ p] (expect tokens p :indent)]
    (loop [caps [] p p]
      (cond
        (at? tokens p :dedent) [{:node :use-section :caps caps} (inc p)]
        (at? tokens p :newline) (recur caps (inc p))
        :else (let [[name p2] (expect tokens p :ident)
                    [_ p3]    (expect tokens p2 :punct ":")
                    [ty p4]   (parse-type tokens p3)
                    p5        (skip-newlines tokens p4)]
                (recur (conj caps {:name name :type ty}) p5))))))

(defn- parse-invariants-section
  "invariants:
       <expr>
       <expr>
   Each line is a predicate expression (chained comparisons ok — parsed
   by the general binop path since <, <=, >, >= all have equal precedence
   and left-associate). Downstream contract-check will interpret chained
   forms specially."
  [tokens pos]
  (let [[_ p] (expect tokens pos :kw "invariants")
        [_ p] (expect tokens p :punct ":")
        [_ p] (expect tokens p :newline)
        [_ p] (expect tokens p :indent)]
    (loop [preds [] p p]
      (cond
        (at? tokens p :dedent) [{:node :invariants-section :predicates preds} (inc p)]
        (at? tokens p :newline) (recur preds (inc p))
        :else (let [[e p2] (parse-expr tokens p)
                    p3     (skip-newlines tokens p2)]
                (recur (conj preds e) p3))))))

;; ---------------------------------------------------------------------------
;; Observables:
;;   Zero-arg:   <name> :< <expr>
;;   Parametric: <name>(<param-list>) :< <expr>       # F67 L2
;;   Declaration-flavored assignment (§1.11). The colon-less-than glyph.
;;   Parametric observables (Proposal G L2) replace reader methods: any
;;   pure computation over self's fields with args now goes here rather
;;   than in a `methods:` section.
;; ---------------------------------------------------------------------------

(defn- parse-observable [tokens pos]
  (let [[name p]   (expect tokens pos :ident)
        [params p] (if (at? tokens p :punct "(")
                     (parse-param-list-parens tokens p)
                     [[] p])
        [_ p]      (expect tokens p :glyph ":<")
        [expr p]   (parse-expr tokens p)]
    [{:node :observable :name name :params params :expr expr} p]))

;; ---------------------------------------------------------------------------
;; Transitions
;;
;;   Full shape:
;;     [<source-phase>] [<receive-glyph> <msg>[{args}]] [when: <expr>]
;;         -> <target-phase>
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
          [params p]   (parse-param-list-parens tokens p)
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

(defn- reject-legacy-let-type-annotation!
  "F23 — the pre-spec `let: k: Int = 2` shape (with `<Type> = <expr>`
   after the binding-name colon) is retired. Spec §1.11 form is
   `let: k: <expr>` with the type inferred from the expression."
  [tokens pos]
  (fail! tokens pos
         (str "`let:` binding uses `name: expression` (spec §1.11) — the "
              "type is inferred from the RHS. Remove the `Type = ` between "
              "the binding-name colon and the expression: `let: k: 2` "
              "instead of `let: k: Int = 2`.")))

(defn- parse-let-binding-rhs
  "After `<name> :`, parse the binding's RHS. Spec form is a bare
   expression; reject the legacy `<Type> = <expr>` shape with a
   targeted migration diagnostic."
  [tokens pos]
  (let [[rhs p] (parse-expr tokens pos)]
    ;; If parse-expr stopped short of `=`, it means the user gave us
    ;; a legacy `Type = expr` form (parse-expr grabbed `Type` as an
    ;; ident, then couldn't consume `=`). Detect and reject.
    (if (at? tokens p :glyph "=")
      (reject-legacy-let-type-annotation! tokens pos)
      [rhs p])))

(defn- parse-let-clause-inline
  "let: <name>: <expr>       (spec §1.11 — type inferred from RHS)"
  [tokens pos]
  (let [[_ p]     (expect tokens pos :kw "let")
        [_ p]     (expect tokens p :punct ":")
        [name p]  (expect tokens p :ident)
        [_ p]     (expect tokens p :punct ":")
        [rhs p]   (parse-let-binding-rhs tokens p)]
    [{:node :let-clause :bindings [{:name name :expr rhs}]} p]))

(defn- parse-let-clause-block
  "let:  (indented block of `<name>: <expr>` lines)"
  [tokens pos]
  (let [[_ p] (expect tokens pos :kw "let")
        [_ p] (expect tokens p :punct ":")
        [_ p] (expect tokens p :newline)
        [_ p] (expect tokens p :indent)]
    (loop [bindings [] p p]
      (cond
        (at? tokens p :dedent) [{:node :let-clause :bindings bindings} (inc p)]
        (at? tokens p :newline) (recur bindings (inc p))
        :else (let [[name p2] (expect tokens p :ident)
                    [_ p3]    (expect tokens p2 :punct ":")
                    [rhs p4]  (parse-let-binding-rhs tokens p3)
                    p5        (skip-newlines tokens p4)]
                (recur (conj bindings {:name name :expr rhs}) p5))))))

(defn- parse-let-clause [tokens pos]
  ;; F18 L1' Landing 1 (2026-08-23) — grace-period deprecation warning
  ;; for the transition `let:` sub-block. The statement-form `let
  ;; <name> = <expr>` (F18 L1) subsumes the sub-block: usable inside
  ;; any `then:` clause with the same visibility scope, no separate
  ;; grammar to maintain. Follow-up landing hard-retires the sub-block
  ;; (parse-let-clause → reject-let-clause!, lower.clj `:let-clause`
  ;; branches deleted, examples migrated).
  (binding [*out* *err*]
    (println (str "warning: transition `let:` sub-block is deprecated —"
                  " use statement-form `let <name> = <expr>` inside the"
                  " `then:` clause instead (F18 L1'; spec §2.5).")))
  (if (at? tokens (+ pos 2) :newline)
    (parse-let-clause-block tokens pos)
    (parse-let-clause-inline tokens pos)))

(defn- parse-when-clause
  "when: <expr>   — additional guard when appearing in transition body."
  [tokens pos]
  (let [[_ p] (expect tokens pos :kw "when")
        [_ p] (expect tokens p :punct ":")
        [e p] (parse-expr tokens p)]
    [{:node :when-clause :expr e} p]))

(defn- body-clause-head?
  "True if pos is at `then:` | `do:` | `let:` | `when:`. `do:` is
   retired at v1.0 (F70 I3) but still recognized here so
   parse-transition-body can dispatch to a targeted retirement
   diagnostic instead of a generic parse error."
  [tokens pos]
  (and (at? tokens pos :kw)
       (#{"then" "do" "let" "when"} (t-value (peek-tok tokens pos)))
       (at? tokens (inc pos) :punct ":")))

;; Audit F22: spec §10 requires transition sub-blocks in the order
;; `when, let, then`, each at most once (`then:` exempt post-F70 I3).
;; `clause-order` is the canonical index used to detect out-of-order
;; and duplicate clauses. The `:do-clause` slot retired 2026-08-20
;; when F70 I3 Stage 3 hard-rejected `do:` at parse time; the parser
;; can no longer produce a `:do-clause` AST node.
(def ^:private clause-order
  {:when-clause 0 :let-clause 1 :then-clause 2})

(defn- clause-kw->src [kw]
  ({:when-clause "when" :let-clause "let"
    :then-clause "then"} kw))

(defn- enforce-clause-order-and-multiplicity!
  "Audit F22: reject repeated clauses and out-of-order clauses in a
   transition body. Message spec: `when, let, then`.

   F70 I3 exception — `:then-clause` may appear more than once because
   the unified `then:` merges field updates and side-effect statements
   in source order; multiple then-blocks fold at lowering. `when:` and
   `let:` remain once-only."
  [clauses tokens pos]
  (loop [seen #{} prev-idx -1 [c & rest] clauses]
    (when c
      (let [kw  (:node c)
            idx (clause-order kw)]
        (when (and (contains? seen kw) (not= kw :then-clause))
          (fail! tokens pos
                 (str "duplicate `" (clause-kw->src kw)
                      ":` clause in transition body (each of when/let "
                      "may appear at most once — spec §10; F70 I3 relaxes "
                      "`then:` to allow multiple occurrences)")))
        (when (< idx prev-idx)
          (fail! tokens pos
                 (str "`" (clause-kw->src kw) ":` clause out of order — "
                      "spec §10 requires transition sub-blocks in the order "
                      "`when, let, then, do`")))
        (recur (conj seen kw) idx rest)))))

(defn- parse-transition-body
  "After the `-> target` head, an optional body:
     - if next token is a body-clause keyword on same line → inline body
     - if next is :newline :indent → indented body with multiple clauses
     - else → no body (transition ends)

   Audit F22: after collecting clauses, verify they appear in
   canonical order (`when, let, then, do`) with each kind at most
   once."
  [tokens pos]
  (cond
    (body-clause-head? tokens pos)
    ;; Inline single clause (rest of transition is on the same line).
    (let [[clause p]
          (case (t-value (peek-tok tokens pos))
            "then" (parse-then-clause tokens pos)
            "do"   (reject-do-clause! tokens pos)   ; F70 I3 Stage 3 — hard retirement (2026-08-19)
            "let"  (parse-let-clause tokens pos)
            "when" (parse-when-clause tokens pos)
            (fail! tokens pos "unexpected clause"))]
      [[clause] p])

    (and (at? tokens pos :newline) (at? tokens (inc pos) :indent))
    (let [p (+ pos 2)]
      (loop [clauses [] p p]
        (cond
          (at? tokens p :dedent)
          (do (enforce-clause-order-and-multiplicity! clauses tokens p)
              [clauses (inc p)])
          (at? tokens p :newline) (recur clauses (inc p))
          (body-clause-head? tokens p)
          (let [[clause p2]
                (case (t-value (peek-tok tokens p))
                  "then" (parse-then-clause tokens p)
                  "do"   (reject-do-clause! tokens p) ; F70 I3 Stage 3 — hard retirement (2026-08-19)
                  "let"  (parse-let-clause tokens p)
                  "when" (parse-when-clause tokens p)
                  (fail! tokens p "unexpected clause"))]
            (recur (conj clauses clause) (skip-newlines tokens p2)))
          :else
          (do (enforce-clause-order-and-multiplicity! clauses tokens p)
              [clauses p]))))

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
        p (skip-newlines tokens p)              ; allow newline before when:/->
        [guard p]  (if (and (at? tokens p :kw "when")
                            (at? tokens (inc p) :punct ":"))
                     (let [[e p2] (parse-expr tokens (+ p 2))]
                       [e p2])
                     [nil p])
        p (skip-newlines tokens p)              ; allow newline before ->
        [_ p]      (expect tokens p :glyph "->")
        [target p] (cond
                     ;; F67 L3 — `*` target = wildcard (no state change).
                     ;; Semantically equivalent to `self` for phase-machine
                     ;; purposes; kept separate so we can enforce
                     ;; `* <: msg(args) -> *` = handler-shape at lowering.
                     (at? tokens p :glyph "*") ["*" (inc p)]
                     (at? tokens p :kw "self") ["self" (inc p)]
                     (at? tokens p :ident)     [(t-value (peek-tok tokens p)) (inc p)]
                     :else (fail! tokens p "expected target phase, `self`, or `*` after `->`"))
        [body p]   (parse-transition-body tokens p)]
    [{:node :transition
      :source source
      :receive receive
      :when guard
      :target target
      :body body} p]))

;; ---------------------------------------------------------------------------
;; def-atom top-level form
;;
;;   def <Name>[(ctor-params)]:
;;       (use: | data: | invariants: | methods: | observable | transition)+
;;
;;   Determines what to parse by look-ahead:
;;     - `use:`, `data:`, `invariants:`, `methods:` → section
;;     - `<ident> :<` or `<ident>(...) :<`           → observable (zero-arg or parametric)
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

(defn- looks-like-observable? [tokens pos]
  (and (at? tokens pos :ident)
       (or (at? tokens (inc pos) :glyph ":<")
           ;; F67 L2 — parametric observable: `<name>(params) :<`
           (and (at? tokens (inc pos) :punct "(")
                (when-let [after-close (scan-past-parens tokens (inc pos))]
                  (at? tokens after-close :glyph ":<"))))))

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
   and from observable (`<ident> :<` / `<ident>(...) :<`) by requiring
   `:punct \":\"` at position 1 (observables use `:glyph \":<\"`,
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

(defn- looks-like-custom-section?
  "F8 L1-b — `<ident>: <newline>` where `<ident>` is registered in
   `cxc.section/registry` (via a prior `:section-header` alias
   declaration). Consulted after the closed switch of built-in section
   headers has failed to match, before the generic body-item dispatch."
  [tokens pos]
  (and (at? tokens pos :ident)
       (at? tokens (inc pos) :punct ":")
       (at? tokens (+ pos 2) :newline)
       (some? (section/lookup (t-value (peek-tok tokens pos))))))

(defn- parse-custom-section
  "F8 L1-b — parse a body of field declarations under a registered
   custom section header. MVP scope: primitive-composition aliases
   imply `contains: fields`, so we consume a run of field decls to
   `:dedent` and emit a `:custom-section` AST node carrying the
   registered spec.

   Behavioral sections with different `contains:` (methods, statements)
   will need dispatch on the spec's contains: field at a later landing;
   L1-b covers only the field-shape case."
  [tokens pos]
  (let [[nm p]  (expect tokens pos :ident)
        [_ p]   (expect tokens p :punct ":")
        [_ p]   (expect tokens p :newline)
        [_ p]   (expect tokens p :indent)
        spec    (section/lookup nm)]
    (loop [fields [] p p]
      (cond
        (at? tokens p :dedent)
        [{:node :custom-section
          :name nm
          :spec spec
          :fields fields}
         (inc p)]

        (at? tokens p :newline)
        (recur fields (inc p))

        (looks-like-field-decl? tokens p)
        (let [[f p2] (parse-field tokens p)]
          (recur (conj fields f) (skip-newlines tokens p2)))

        :else
        (fail! tokens p
               (str "F8 L1-b: custom section `" nm "` body accepts "
                    "field declarations only (MVP scope). Got "
                    "unexpected token."))))))

(defn- fold-top-level-fields
  "F67 L5 — after parsing an atom/record body containing individual
   `:field` nodes (Proposal D top-level surface), gather them into a
   single synthetic `:data-section` at the position of the first field
   so downstream AST consumers stay shape-stable. If a legacy
   `:data-section` is also present, fold the top-level fields into it
   at the section's position (preserving declaration order via the
   section-first, top-level-fields-second convention).

   Body positions of individual `:field` nodes are removed; the
   synthesized/merged section takes their combined position slot.

   F8 L1-c (2026-08-23): `:custom-section` nodes carrying primitive-
   composition specs (from user `alias ...: <primitives>` decls) are
   flattened here too — their fields lift up to top-level fields
   before the fold. The composed spec is preserved on the surviving
   `:data-section` via the `:section-classes` slot for downstream
   consumers that care about origin. Behavioral-section treatment
   (Computed, Invariants generators) is F8 L3."
  [body]
  (let [;; F8 L1-c — hoist :custom-section fields to top-level :field
        ;; nodes; collect the composed specs separately for annotation.
        custom-sections (filterv #(= :custom-section (:node %)) body)
        hoisted-fields  (mapcat :fields custom-sections)
        section-specs   (mapv (fn [s] {:name (:name s) :spec (:spec s)})
                              custom-sections)
        body            (into [] (concat (remove #(= :custom-section (:node %))
                                                 body)
                                          hoisted-fields))
        fields   (filterv #(= :field (:node %)) body)
        legacy   (first (filter #(= :data-section (:node %)) body))]
    (cond
      (empty? fields)
      body

      legacy
      (let [merged  (-> legacy
                        (assoc :fields
                               (into (or (:fields legacy) []) fields))
                        (cond-> (seq section-specs)
                          (update :section-classes
                                  (fnil into []) section-specs)))
            without-fields (remove #(= :field (:node %)) body)]
        (mapv (fn [n] (if (= :data-section (:node n)) merged n))
              without-fields))

      :else
      (let [first-field-idx (some (fn [[i n]]
                                    (when (= :field (:node n)) i))
                                  (map-indexed vector body))
            without-fields (remove #(= :field (:node %)) body)
            synthetic       (cond-> {:node :data-section :fields fields}
                              (seq section-specs)
                              (assoc :section-classes section-specs))]
        (into (subvec (vec without-fields) 0 first-field-idx)
              (into [synthetic]
                    (subvec (vec without-fields) first-field-idx)))))))

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

(declare def-atom->record def-atom->protocol def-atom->enum
         def-atom-body-has-transition? def-atom-body-has-use?
         def-atom-body-has-accepts? def-atom-body-has-case?
         def-atom-body-has-atom-ctor-field-default?
         parse-accepts-section parse-accepts-signature)

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

(defn- parse-def-atom [tokens pos]
  (let [[_ p]      (expect tokens pos :kw "def")
        [name p]   (expect tokens p :ident)
        [ctor p]   (if (at? tokens p :punct "(")
                     (parse-param-list-parens tokens p)
                     [[] p])
        [_ p]      (expect tokens p :punct ":")
        [_ p]      (expect tokens p :newline)
        [_ p]      (expect tokens p :indent)]
    (loop [body [] p p]
      (cond
        (at? tokens p :dedent)
        (let [folded  (fold-top-level-fields body)
              raw-def {:node :def-atom :name name :ctor-params ctor
                       :body folded}
              ;; F68 L1/L2/L5 (Proposal H) — structural detection dispatch.
              ;; Order matters: protocol (accepts:) > enum (case) > class
              ;; (transition, use:, or ctor params) > record (fields +
              ;; observables only, no ctor params). See
              ;; compiler/design/plans/archive/proposal-h-full-unification-under-
              ;; def.md § "Structural detection".
              ;;
              ;; Ctor params are a class indicator: records auto-derive
              ;; their ctor from the field list, so an explicit ctor-param
              ;; list at the def-header is itself an intent signal
              ;; (preserves pre-F68 atom syntax of `def X(a: T):`).
              reshaped
              (cond
                (def-atom-body-has-accepts? folded)
                (def-atom->protocol raw-def tokens pos)

                (def-atom-body-has-case? folded)
                (def-atom->enum raw-def tokens pos)

                (or (def-atom-body-has-transition? folded)
                    (def-atom-body-has-use? folded)
                    (def-atom-body-has-atom-ctor-field-default? folded)
                    (seq ctor))
                ;; F69 L2 — agent-shape def (transitions / use: / ctor
                ;; params / atom-ctor default). Stamp the inferred kind
                ;; so downstream consumers can query uniformly.
                (assoc raw-def :inferred-kind :agent)

                :else
                (def-atom->record raw-def tokens pos))]
          [reshaped (inc p)])

        (at? tokens p :newline)
        (recur body (inc p))

        (at? tokens p :kw "use")
        (let [[s p2] (parse-use-section tokens p)]
          (recur (conj body s) (skip-newlines tokens p2)))

        (at? tokens p :kw "deprecated")
        (let [[s p2] (parse-deprecated-section tokens p)]
          (recur (conj body s) (skip-newlines tokens p2)))

        (at? tokens p :kw "invariants")
        (let [[s p2] (parse-invariants-section tokens p)]
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

        ;; F67 L4 — `function` keyword inside atom body retires with the
        ;; `methods:` section (Proposal G class-body collapse). Free
        ;; functions still live at module scope.
        (at? tokens p :kw "function")
        (fail! tokens p
               (str "F67 L4: `function` keyword inside `def " name "` body is "
                    "retired (Proposal G class-body collapse). Migrate the "
                    "method by category:\n\n"
                    "  - reader → observable: `name(args) :< <expr>`\n"
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
                    "(args) :< <expr>`\n"
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

        ;; `state:` section retired at v1.0 (spec §1.11, §1.259). All
        ;; fields are `data:` (per-instance; updated only through
        ;; transitions). Targeted diagnostic beats the generic
        ;; "expected …" fall-through so the migration path is visible.
        (and (at? tokens p :ident)
             (= "state" (t-value (peek-tok tokens p)))
             (at? tokens (inc p) :punct ":"))
        (fail! tokens p
               (str "`state:` section is retired at v1.0. All fields are "
                    "`data:` (per-instance, updated only through "
                    "transitions). See spec §1.11."))

        ;; F68 L1 (Proposal H) — `accepts:` section / per-entry sugar
        ;; inside a `def` body marks the declaration as a protocol at
        ;; structural-detect time (see reshape at end of body loop).
        (and (at? tokens p :kw "accepts") (at? tokens (inc p) :punct ":"))
        (let [[s p2] (parse-accepts-section tokens p)]
          (recur (conj body s) (skip-newlines tokens p2)))

        (and (at? tokens p :kw "accepts") (at? tokens (inc p) :ident))
        (let [[_ p2]  (expect tokens p :kw "accepts")
              [e p3]  (parse-accepts-signature tokens p2)]
          (recur (conj body {:node :accepts-section :entries [e]})
                 (skip-newlines tokens p3)))

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

        ;; F8 L1-b — custom section-header dispatch. Consults
        ;; `cxc.section/registry` (populated at parse time by
        ;; `:section-header` alias declarations) so users can define
        ;; new section-header words like `alias exposed_data: runtime
        ;; immutable identity expose` and then use `exposed_data:` in
        ;; a class body. Must precede `looks-like-transition?` because
        ;; the transition detector greedy-matches any bare `:ident`.
        (looks-like-custom-section? tokens p)
        (let [[s p2] (parse-custom-section tokens p)]
          (recur (conj body s) (skip-newlines tokens p2)))

        (looks-like-transition? tokens p)
        (let [[t p2] (parse-transition tokens p)]
          (recur (conj body t) (skip-newlines tokens p2)))

        :else
        (fail! tokens p "expected use:, field decl (`name: Type [= default]`), invariants:, deprecated:, on_nil:, observable (`name[(args)] :< expr`), accepts:, or transition inside def-atom body")))))

;; ---------------------------------------------------------------------------
;; F68 L1 (Proposal H) — structural detection helpers for `def` reshape.
;;
;; A `def Foo:` body is inspected after parse. If it structurally matches
;; a record or protocol shape, the parsed :def-atom node is reshaped into
;; the appropriate existing AST kind so downstream env builders keep
;; working unchanged. See compiler/design/plans/archive/proposal-h-full-unification-
;; under-def.md.
;; ---------------------------------------------------------------------------

(defn- def-atom-body-has-transition? [body]
  (boolean (some #(= :transition (:node %)) body)))

(defn- def-atom-body-has-use? [body]
  (boolean (some #(= :use-section (:node %)) body)))

(defn- def-atom-body-has-accepts? [body]
  (boolean (some #(= :accepts-section (:node %)) body)))

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
                "`use:`) — records use field-derived auto-ctors and "
                "cannot carry an explicit ctor-param list. Either "
                "add a transition (to promote to a class), remove the "
                "ctor params (to keep as a record), or list fields "
                "with defaults inside the body.")))
  {:node          :record
   :name          (:name def-atom)
   :sections      (:body def-atom)
   :inferred-kind :structural})

(defn- def-atom->protocol
  "Reshape a parsed :def-atom into a :class :kind :protocol node. Flattens
   all :accepts-section entries into a single :accepts vector; other body
   sections pass through as `:body`. Rejects at parse if the `def` header
   carried ctor params (protocols declare no state)."
  [def-atom tokens pos-for-diag]
  (when (seq (:ctor-params def-atom))
    (fail! tokens pos-for-diag
           (str "F68 L1: `def " (:name def-atom) "(...)` with ctor "
                "params contains `accepts:` entries — protocols declare "
                "signatures only and carry no state, so ctor params are "
                "not permitted. Drop the ctor params to keep as a "
                "protocol, or remove `accepts:` to promote to a class.")))
  (let [body           (:body def-atom)
        accepts-secs   (filter #(= :accepts-section (:node %)) body)
        other-sections (remove #(= :accepts-section (:node %)) body)
        accepts-entries (vec (mapcat :entries accepts-secs))]
    {:node          :class
     :name          (:name def-atom)
     :kind          :protocol
     :conforms      []
     :accepts       accepts-entries
     :body          (vec other-sections)
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
                  "transitions, `use:`, `accepts:`). Enums are a closed "
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
    ;; Overriding via `metadata: raw_type:` is a follow-on landing.
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
;; F9 protocol/accepts + F9 L3 extend surfaces. Post F68 L6b (Proposal H):
;; `class` / `protocol` / `record` / `enum` sugar keywords and their
;; supporting parsers (parse-class, parse-record, parse-enum,
;; parse-class-section, fold-class-body, parse-metadata-section,
;; legal-kinds) are all deleted; the sole class/protocol/record/enum
;; surface is `def X:` with structural detection in parse-def-atom.
;; parse-accepts-section + parse-accepts-signature + parse-conforms-line
;; + parse-header-parens-conformances remain — accepts:/conforms: are
;; still valid body constructs, just inside `def` (accepts:) or `extend`
;; (both) blocks now.
;; ---------------------------------------------------------------------------

(defn- parse-accepts-signature
  "Parse one `accepts:` entry — a method signature with no body.
     [def|function] name(params) -> ReturnType
   L2 MVP: no contract clauses (`requires`/`ensures`) and no effect
   annotations (`async`/`io`). Continuation-indented lines after the
   signature fail-nyi with a pointer at a follow-on landing."
  [tokens pos]
  (let [[_ p]      (cond
                     (at? tokens pos :kw "function")
                     (do (warn-function-deprecated! tokens pos)
                         (expect tokens pos :kw "function"))
                     (at? tokens pos :kw "def")      (expect tokens pos :kw "def")
                     :else                            [nil pos])
        [name p]   (expect tokens p :ident)
        [params p] (parse-param-list-parens tokens p)
        [ret p]    (if (at? tokens p :glyph "->")
                     (parse-type tokens (inc p))
                     [nil p])
        [_ p]      (expect tokens p :newline)
        ;; Reject continuation lines (requires/ensures/async/io).
        _          (when (at? tokens p :indent)
                     (fail! tokens p
                            (str "contract clauses (`requires`/`ensures`) and "
                                 "effect annotations (`async`/`io`) on `accepts:` "
                                 "entries are not yet implemented. F9 L2 ships the "
                                 "signature surface; contract inheritance lands "
                                 "with F49 protocol-inherited-contracts. See "
                                 "plans/g3-structural-canonical/f9-class-protocol-extension.md L2.")))]
    [{:node :accepts-entry :name name :params params :return-type ret} p]))

(defn- parse-accepts-section
  "`accepts:` block on `kind: protocol` classes.
     accepts:
         foo(Int) -> Int
         bar(String) -> Bool
   Also supports the per-entry sugar at class-body level:
     accepts foo(Int) -> Int
   handled inside parse-class-section."
  [tokens pos]
  (let [[_ p] (expect tokens pos :kw "accepts")
        [_ p] (expect tokens p :punct ":")
        [_ p] (expect tokens p :newline)
        [_ p] (expect tokens p :indent)]
    (loop [entries [] p p]
      (cond
        (at? tokens p :dedent)
        [{:node :accepts-section :entries entries} (inc p)]

        (at? tokens p :newline)
        (recur entries (inc p))

        :else
        (let [[e p2] (parse-accepts-signature tokens p)]
          (recur (conj entries e) (skip-newlines tokens p2)))))))

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
;; methods:, invariants:, and conforms:. `data:`, `state:`, `use:`,
;; `cases:`, `metadata:` (any subkey), and `deprecated:` are rejected
;; at parse with a targeted §3.3.2 pointer. Method entries carry the
;; `replace` modifier via `:replace?` (parsed by shared parse-methods-section).
;;
;; Extension target-name resolution is deferred to lower (L4 composition
;; consumes extension-env; unknown-target diagnostics fire there when the
;; full symbol table is available).
;; ---------------------------------------------------------------------------

(defn- parse-extend-section
  "Dispatch one section inside an `extend Foo:` body. Strict —
   rejects sections spec §3.3.2 forbids (data/state/use/cases/metadata/
   deprecated). Accepts observables (F67 L4), wildcard self-transitions
   (F67 L4), invariants:, conforms:. `methods:` retires (F67 L4).

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
                    "prefix an observable (`replace name[(args)] :< expr`) "
                    "or a wildcard self-transition (`replace * <: msg(args) "
                    "-> * then: <body>`). See §3.3.2 rule 4."))))

    (at? tokens pos :kw "invariants") (parse-invariants-section tokens pos)

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
           (str "`use:` is not permitted inside `extend " target
                "` — extensions cannot add capabilities (§3.3.2). "
                "Declare `use:` at the original class definition."))

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
                 (str "expected observable (`name[(args)] :< expr`), "
                      "transition (`* <: msg(args) -> * …`), invariants:, "
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

(defn- parse-from-import
  "`from X import a[, b, c[ as d]]` at top level.

   F52 L2 (2026-08-23) — each name may be aliased with `as <ident>`:
     from foo import a           → {:name \"a\" :as nil}
     from foo import a as b      → {:name \"a\" :as \"b\"}
     from foo import a, b as c   → mixed alias/plain

   `:names` is a vector of `{:name :as}` maps post-L2. Pre-L2 the
   shape was `[string...]`; downstream readers migrate to the new
   shape via a `name-of` helper (or accept both)."
  [tokens pos]
  (let [[_ p]      (expect tokens pos :kw "from")
        [dotted p] (parse-dotted tokens p)
        [_ p]      (expect tokens p :kw "import")]
    (loop [names [] p p]
      (let [[n p2]    (expect tokens p :ident)
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
          :else (fail! tokens p3 "expected `,` or newline in from-import"))))))

(defn- parse-import
  "F52 L1 — `import X` and `import X as Y` at top level.
     import foo.bar          → {:node :import :from \"foo.bar\" :as nil}
     import foo.bar as baz   → {:node :import :from \"foo.bar\" :as \"baz\"}

   Cross-references the F51 L2 dep-graph builder — `import X` counts
   as a dependency on `X` from the current module, same as `from X
   import ...`. Namespace-scoped binding (`X.member` resolution) waits
   on F51 L3."
  [tokens pos]
  (let [[_ p]       (expect tokens pos :kw "import")
        [dotted p]  (parse-dotted tokens p)
        [alias p]   (if (at? tokens p :kw "as")
                      (let [[nm p2] (expect tokens (inc p) :ident)]
                        [nm p2])
                      [nil p])
        [_ p]       (expect tokens p :newline)]
    [{:node :import :from dotted :as alias} p]))

;; ---------------------------------------------------------------------------
;; F7 L1 — Alias declaration
;;
;; MVP surface: `alias <name>: <type-expr>` at top level. Registers a
;; type-position substitution: any `:type-ref {:name "<name>" ...}` in
;; the post-parse AST gets replaced with the aliased type expression.
;;
;; Grammar-shape expansion (section-header aliases, delimited-pattern
;; aliases, operator aliases) comes in follow-on sub-landings of L1
;; and L2. Bootstrap grammar per §4.16.1 stays hard-coded.
;;
;; AST shape:
;;   {:node :alias
;;    :name "<user-visible-name>"
;;    :position :type          ;; MVP: only :type positions
;;    :body    <type-expr AST>}
;;
;; Registry lives in `cxc.alias`; population + expansion runs post-parse
;; before lower.
;; ---------------------------------------------------------------------------

(defn- parse-alias-param-list
  "F7 L1-c — parametric alias parameter list. After the alias name,
   optional `of P` or `of (P1, P2, ...)` before the `:` separator.
   Uses the `of` qualifier per spec §26 (retires `<T>`). Returns
   `[params next-pos]`; params is the vector of parameter-name strings
   (empty when the alias is not parametric)."
  [tokens pos]
  (if-not (at? tokens pos :kw "of")
    [[] pos]
    (let [p (inc pos)]
      (cond
        ;; `of (P1, P2, ...)`
        (at? tokens p :punct "(")
        (loop [params [] p (inc p)]
          (let [[nm p1] (expect tokens p :ident)
                params  (conj params nm)]
            (cond
              (at? tokens p1 :punct ",") (recur params (inc p1))
              (at? tokens p1 :punct ")") [params (inc p1)]
              :else (fail! tokens p1
                           "expected `,` or `)` in alias parameter list"))))
        ;; `of P` — single parameter, bare identifier
        (at? tokens p :ident)
        (let [[nm p1] (expect tokens p :ident)]
          [[nm] p1])
        :else
        (fail! tokens p
               "expected parameter identifier or `(` after `of` in alias declaration")))))

(defn- section-primitive-body?
  "F7 L1-b — peek at the alias body to distinguish section-header form
   (spec §4.10.2: `alias data: runtime immutable identity`) from
   type-expression form (L1-a/L1-c). Returns true iff every non-newline
   token from `pos` to newline is an `:ident` whose value is a known
   section primitive (via `cxc.section/primitive`)."
  [tokens pos]
  (loop [p pos, saw-any? false]
    (let [t (peek-tok tokens p)]
      (cond
        (nil? t) false
        (= :newline (t-kind t)) saw-any?
        (and (= :ident (t-kind t)) (section/primitive (t-value t)))
        (recur (inc p) true)
        :else false))))

(defn- parse-section-header-alias-body
  "F7 L1-b — consume a run of `:ident` tokens (each a known primitive)
   up to newline. Returns [primitive-keywords next-pos]."
  [tokens pos]
  (loop [p pos, prims []]
    (let [t (peek-tok tokens p)]
      (cond
        (= :newline (t-kind t))
        [prims p]

        (and (= :ident (t-kind t)) (section/primitive (t-value t)))
        (recur (inc p) (conj prims (keyword (str/lower-case (t-value t)))))

        :else
        (fail! tokens p
               (str "F7 L1-b: section-header alias body must be a space-"
                    "separated list of known primitives (spec §4.10.2). "
                    "Got unexpected token in body."))))))

(defn- parse-alias-decl [tokens pos]
  (let [[_ p]         (expect tokens pos :kw "alias")
        [nm p]        (expect tokens p :ident)
        [params p]    (parse-alias-param-list tokens p)
        [_ p]         (expect tokens p :punct ":")]
    (if (and (empty? params) (section-primitive-body? tokens p))
      ;; L1-b section-header form — `alias name: runtime immutable identity`.
      ;; Register into cxc.section/registry immediately so subsequent
      ;; section headers within the same source can consult the registry
      ;; (F8 L1-b parser hook).
      (let [[prims p] (parse-section-header-alias-body tokens p)
            [_ p]     (expect tokens p :newline)
            node      {:node :alias :name nm :position :section-header
                       :primitives prims}]
        (section/register-section-header-alias! node)
        [node p])
      ;; L1-a/L1-c type-position form — `alias name: <type-expr>` or
      ;; `alias name of P: <type-expr>`
      (let [[body p]  (parse-type tokens p)
            [_ p]     (expect tokens p :newline)]
        [{:node :alias :name nm :position :type
          :params params :body body} p]))))

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
      (at? tokens p :kw "alias")   (parse-alias-decl tokens p)
      (at? tokens p :kw "def")     (parse-def-atom tokens p)

      ;; F9 L3 — `extend Foo:` at top level. Extension body is
      ;; strict per §3.3.2: methods:/invariants:/conforms: only.
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
      ;; FFI is a capability atom (Extern) provisioned to stdlib shims
      ;; via `use: extern: Extern`; foreign symbols are declared in
      ;; per-target prelude `foreign library "..."` blocks (§1.14).
      ;; Both are deferred to Phase D (see compiler/design/spec-drift-audit.md).
      (and (at? tokens p :ident)
           (= "extern" (t-value (peek-tok tokens p))))
      (fail! tokens p
             (str "`extern` as a top-level section keyword is retired at v1.0. "
                  "FFI moves to prelude `foreign library` blocks + the "
                  "`Extern` capability atom. See spec §1.12 and §1.14."))

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
