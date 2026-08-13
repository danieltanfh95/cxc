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
    :call            (str (:fn node)
                          "("
                          (str/join ", " (map emit-expr (:args node)))
                          ")")
    :objc-msg        (str "[" (emit-expr (:recv node)) " "
                          (:selector node)
                          (when (seq (:args node))
                            (str ": " (str/join " " (map emit-expr (:args node)))))
                          "]")
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

(defn- emit-interface [cls]
  (let [props (->> (:properties cls)
                   (map emit-property)
                   (map #(str "  " %))
                   (str/join "\n"))
        body  (if (str/blank? props) "" (str "\n" props "\n"))]
    (str "@interface " (:name cls) " : " (:superclass cls) body "@end")))

(defn- emit-implementation [cls]
  (let [init-body (:init-body cls)
        init-stmts (if (seq init-body)
                     (str "\n" (emit-body 8 init-body))
                     "")]
    (str "@implementation " (:name cls) "\n"
         "- (instancetype)init {\n"
         "    self = [super init];\n"
         "    if (self) {"
         init-stmts
         "\n    }\n"
         "    return self;\n"
         "}\n"
         "@end")))

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
  (let [includes (str/join "\n" (map #(str "#import " %) (:includes prog)))
        structs  (str/join "\n\n" (map emit-struct (:structs prog)))
        classes  (str/join "\n\n" (map emit-class (:classes prog)))
        main     (emit-main (:main prog))]
    (str/join "\n\n"
              (remove str/blank? [includes structs classes main]))))
