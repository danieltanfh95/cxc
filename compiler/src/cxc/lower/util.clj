(ns cxc.lower.util
  "Pure AST/rendering utilities used by the lowering passes.

   No `ctx` or env dependencies — every fn here is a data-in / data-out
   helper on parsed nodes, strings, or numbers. Kept out of `cxc.lower`
   proper so that per-def / per-expr namespaces can `:refer` them
   without pulling in the rest of `cxc.lower`."
  (:require [clojure.string :as str]))

(defn c-string-lit
  "Render a Clojure string as a valid C double-quoted string literal.
   Escapes backslash and double-quote; other bytes pass through (the
   sites that use this feed short compiler-authored labels with
   ASCII-safe content — atom/field/param/method names)."
  [s]
  (str "\""
       (-> s
           (str/replace "\\" "\\\\")
           (str/replace "\"" "\\\""))
       "\""))

(defn nonnil-check-stmt
  "F39 L3 — inline `if ((expr-c) == nil) cxc_nonnil_violation(site, name);`
   guard as a :raw-c stmt. `expr-c` is the already-rendered C expression
   whose nil-ness is being checked (a bare identifier at every current
   observation point — ctor-param name, field ivar, or return-temp).
   `site` and `name` are compiler-authored labels."
  [expr-c site name]
  {:node :raw-c
   :text (str "if ((" expr-c ") == nil) cxc_nonnil_violation("
              (c-string-lit site) ", " (c-string-lit name) ");")})

(defn literal-int?
  [n]
  (and (map? n) (contains? #{:lit-int :int :num} (:node n))))

(defn literal-int-value
  [n]
  (:value n))

(defn field-ident?
  [n field-name]
  (and (map? n) (= :ident (:node n)) (= field-name (:name n))))

(defn neg-of-literal-int?
  "Match `-<literal-int>` — the parser lowers `-128` as `:neg` with
   the operand under `:expr`. Returns the negated value or nil."
  [n]
  (when (and (map? n) (= :neg (:node n)) (literal-int? (:expr n)))
    (- (literal-int-value (:expr n)))))

(defn int-constant-value
  "Extract a compile-time integer constant from `n` — either a bare
   integer literal or a `-<literal>` unary-neg. Returns nil if `n`
   isn't a recognized integer constant expression."
  [n]
  (cond
    (literal-int? n)         (literal-int-value n)
    (neg-of-literal-int? n)  (neg-of-literal-int? n)
    :else                    nil))

(defn capitalize-first
  [s]
  (if (str/blank? s) s (str (str/upper-case (subs s 0 1)) (subs s 1))))
