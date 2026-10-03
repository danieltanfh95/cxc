;; Oracle for the cxc.diagnostics shape-only port.
;;
;; Each line selects one of the three throw helpers. The oracle
;; invokes the helper, catches the thrown ex-info, and prints
;; `<kind>|<message>` extracted from the exception. The cxc port
;; builds the same string without actually raising.

(require 'cxc.diagnostics)
(require '[clojure.string :as str])

(doseq [ln (line-seq (java.io.BufferedReader. *in*))]
  (let [parts  (str/split ln #"\|" 3)
        helper (nth parts 0 "")]
    (try
      (case helper
        "nyi"     (cxc.diagnostics/fail-nyi! (nth parts 1 "") nil)
        "user"    (cxc.diagnostics/fail-user! (nth parts 1 "") nil)
        "compile" (cxc.diagnostics/fail-compile!
                   (keyword (nth parts 1 ""))
                   (nth parts 2 "")
                   nil)
        (println "UNKNOWN"))
      (catch Exception e
        (let [data (ex-data e)]
          (println (str (name (:kind data)) "|" (.getMessage e))))))))
