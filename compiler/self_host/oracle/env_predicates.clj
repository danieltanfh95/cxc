;; Oracle for the cxc.env predicate port.
;;
;; Reads pipe-separated lines from stdin. First field is the
;; predicate name, second is the primary arg, third (lookup only)
;; is ignored, the env file path is passed via CXC_ENV_FILE env var.
;;
;; synth   -> cxc.env/synthesized-atom-kind? on a keyword
;; phantom -> cxc.env/phantom-atom-name? on a name string
;; lookup  -> cxc.env/env-lookup on a flat {name -> kind-keyword} env

(require 'cxc.env)
(require '[clojure.string :as str])

(let [env-path (System/getenv "CXC_ENV_FILE")
      env-map
      (if (and env-path (.exists (clojure.java.io/file env-path)))
        (let [content (slurp env-path)
              rows    (filter (complement str/blank?)
                              (str/split content #"\n"))]
          (into {}
                (map (fn [r]
                       (let [[nm k] (str/split r #"\|" 2)]
                         [nm (keyword k)]))
                     rows)))
        {})
      ctx {:env-kw env-map}]
  (doseq [ln (line-seq (java.io.BufferedReader. *in*))]
    (let [[kind a1 _a3] (str/split ln #"\|" 3)]
      (case kind
        "synth"   (println (str (cxc.env/synthesized-atom-kind? (keyword a1))))
        "phantom" (println (str (cxc.env/phantom-atom-name? a1)))
        "lookup"  (let [v (cxc.env/env-lookup ctx :env-kw a1)]
                    (println (if v (name v) "")))
        (println "ERROR")))))
