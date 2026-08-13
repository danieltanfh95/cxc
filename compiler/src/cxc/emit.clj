(ns cxc.emit
  "Emit ObjC/C source from the ObjC AST produced by cxc.lower.

   Output is a single `.m` string per :objc-program. Grown feature by
   feature alongside the lower pass."
  (:require [clojure.string :as str]))

(declare emit-expr emit-stmt emit-class emit-struct)

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

(defn- emit-expr [node]
  (case (:node node)
    :str             (str "\"" (escape-c-string (:value node)) "\"")
    :int             (str (:value node))
    :float           (str (:value node))
    :nil             "nil"
    :name            (:sym node)
    :binop           (str "(" (emit-expr (:left node))
                          " " (:op node) " "
                          (emit-expr (:right node)) ")")
    :field           (str (emit-expr (:target node)) "." (:field node))
    :struct-lit      (str "((" (:type node) "){"
                          (str/join ", "
                                    (map (fn [f]
                                           (str "." (:name f) " = "
                                                (emit-expr (:value f))))
                                         (:fields node)))
                          "})")
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
    :call            (str (:fn node)
                          "("
                          (str/join ", " (map emit-expr (:args node)))
                          ")")
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
    (throw (ex-info (str "emit-expr: unknown node " (:node node))
                    {:node node}))))

;; ---------------------------------------------------------------------------
;; Statement emission
;; ---------------------------------------------------------------------------

(defn- emit-stmt [node]
  (case (:node node)
    :call   (str (emit-expr node) ";")
    :return (str "return " (emit-expr (:value node)) ";")
    :assign (str (emit-expr (:lhs node)) " = " (emit-expr (:rhs node)) ";")
    :invariant-check
    ;; Direct if/abort — NSAssert is compiled out by NS_BLOCK_ASSERTIONS
    ;; in release builds; invariants must always fire.
    (str "if (!(" (emit-expr (:cond node)) ")) {\n"
         "    fprintf(stderr, \"" (escape-c-string (:label node)) "\\n\");\n"
         "    abort();\n"
         "}")
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

(defn- emit-struct [s]
  (let [decls (->> (:fields s)
                   (map (fn [f] (str "    " (:c-type f) " " (:name f) ";")))
                   (str/join "\n"))]
    (str "typedef struct {\n" decls "\n} " (:name s) ";")))

(defn- emit-c-enum [e]
  (let [members (->> (:cases e)
                     (map (fn [c] (str "    " (:name e) "_" c)))
                     (str/join ",\n"))]
    (str "typedef enum {\n" members "\n} " (:name e) ";")))

;; ---------------------------------------------------------------------------
;; C function emission (record methods → free functions)
;; ---------------------------------------------------------------------------

(defn- emit-param [p]
  (str (:c-type p) " " (:name p)))

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
        space (if (str/blank? attrs) "" " ")]
    (str "@property " attrs space (:c-type p) " " (:name p) ";")))

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

(defn- emit-interface [cls]
  (let [props     (->> (:properties cls)
                       (map emit-property)
                       (map #(str "  " %))
                       (str/join "\n"))
        method-decls (->> (:methods cls)
                          (map #(str "  " (method-signature %) ";"))
                          (str/join "\n"))
        body-parts (remove str/blank? [props method-decls])
        body       (if (empty? body-parts) "" (str "\n" (str/join "\n" body-parts) "\n"))]
    (str "@interface " (:name cls) " : " (:superclass cls) body "@end")))

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
  (str (method-signature m) " {\n"
       (emit-body 4 (:body m))
       "\n}"))

(defn- emit-implementation [cls]
  (let [init-body     (:init-body cls)
        init-params   (:init-params cls)
        init-stmts    (if (seq init-body)
                        (str "\n" (emit-body 8 init-body))
                        "")
        init-block    (str (init-signature init-params) " {\n"
                           "    self = [super init];\n"
                           "    if (self) {"
                           init-stmts
                           "\n    }\n"
                           "    return self;\n"
                           "}")
        method-impls  (str/join "\n\n" (map emit-method-impl (:methods cls)))
        body-blocks   (remove str/blank? [init-block method-impls])]
    (str "@implementation " (:name cls) "\n"
         (str/join "\n\n" body-blocks)
         "\n@end")))

(defn- emit-class [cls]
  (str (emit-interface cls) "\n\n" (emit-implementation cls)))

;; ---------------------------------------------------------------------------
;; Program emission
;; ---------------------------------------------------------------------------

(defn- emit-main [main-node]
  (let [body (emit-body 4 (:body main-node))]
    (if (:autorelease? main-node)
      (str "int main(int argc, char *argv[]) {\n"
           "    @autoreleasepool {\n"
           (indent-lines 4 body)
           "\n    }\n"
           "}")
      (str "int main(int argc, char *argv[]) {\n"
           body
           "\n}"))))

(defn emit
  "Emit a full .m source string from an :objc-program AST."
  [prog]
  (let [includes  (str/join "\n"   (map #(str "#import " %) (:includes prog)))
        enums     (str/join "\n\n" (map emit-c-enum   (:enums prog)))
        structs   (str/join "\n\n" (map emit-struct   (:structs prog)))
        functions (str/join "\n\n" (map emit-function (:functions prog)))
        classes   (str/join "\n\n" (map emit-class    (:classes prog)))
        main      (emit-main (:main prog))]
    (str/join "\n\n"
              (remove str/blank? [includes enums structs functions classes main]))))
