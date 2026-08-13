(ns cxc.emit-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [cxc.emit :as emit]))

(defn- hello-program-ast []
  {:node     :objc-program
   :includes ["<stdio.h>"]
   :classes  []
   :main     {:body [{:node :call :fn "printf"
                      :args [{:node :str :value "%s\n"}
                             {:node :str :value "hello, world"}]}
                     {:node :return :value {:node :int :value 0}}]}})

(deftest hello-emit-shape
  (let [s (emit/emit (hello-program-ast))]
    (is (str/includes? s "#include <stdio.h>"))
    (is (str/includes? s "int main(int argc, char *argv[]) {"))
    (is (str/includes? s "printf(\"%s\\n\", \"hello, world\");"))
    (is (str/includes? s "return 0;"))
    (is (str/ends-with? s "}"))))

(deftest c-string-escaping
  (testing "backslash, quotes, newline are C-escaped"
    (let [ast (assoc-in (hello-program-ast)
                        [:main :body 0 :args 1 :value]
                        "line1\nline2\t\"quoted\\slash")
          s   (emit/emit ast)]
      (is (str/includes? s "\"line1\\nline2\\t\\\"quoted\\\\slash\"")))))
