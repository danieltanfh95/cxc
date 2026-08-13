(ns cxc.emit
  "Emit ObjC/C source from the ObjC AST produced by cxc.lower.

   Output is a single `.m` string per :objc-program. Grown feature by
   feature alongside the lower pass."
  (:require [clojure.string :as str]))

(declare emit-expr emit-stmt emit-class)

;; ---------------------------------------------------------------------------
;; Expression emission
;; ---------------------------------------------------------------------------

(defn- escape-c-string [s]
  ;; Minimal escaping: backslash, double-quote, newline, tab, return.
  ;; Enough for hello-world; extend as needed.
  (-> s
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")
      (str/replace "\t" "\\t")
      (str/replace "\r" "\\r")))

(defn- emit-expr [node]
  (case (:node node)
    :str  (str "\"" (escape-c-string (:value node)) "\"")
    :int  (str (:value node))
    :float (str (:value node))
    :name (:sym node)
    :call (str (:fn node)
               "("
               (str/join ", " (map emit-expr (:args node)))
               ")")
    :objc-msg (str "[" (emit-expr (:recv node)) " "
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

(defn- emit-body [stmts]
  (indent-lines 4 (str/join "\n" (map emit-stmt stmts))))

;; ---------------------------------------------------------------------------
;; ObjC class emission (present for shape; unused in hello-world)
;; ---------------------------------------------------------------------------

(defn- emit-class [_cls]
  ;; Placeholder — grows with the lowering pass.
  "")

;; ---------------------------------------------------------------------------
;; Program emission
;; ---------------------------------------------------------------------------

(defn emit
  "Emit a full .m source string from an :objc-program AST."
  [prog]
  (let [includes (str/join "\n" (map #(str "#include " %) (:includes prog)))
        classes  (str/join "\n\n" (map emit-class (:classes prog)))
        main     (str "int main(int argc, char *argv[]) {\n"
                      (emit-body (get-in prog [:main :body]))
                      "\n}")]
    (str/join "\n\n"
              (remove str/blank? [includes classes main]))))
