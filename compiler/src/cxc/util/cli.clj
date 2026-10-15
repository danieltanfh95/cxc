(ns cxc.util.cli
  "Small shared CLI primitives. The flag grammar is:
   --name value  (string flag)
   --name        (boolean flag; next token stays available as a positional or
                  the next flag)
   anything-else (positional, accumulated under ::positional)"
  (:require [clojure.string :as str]))

(defn parse-flags
  "Parse argv-style flags into {kw value ... ::positional [...]}.
   Pulls value for every --name followed by a non-flag token. Trailing
   boolean flags land as true. Nil `v` (end of args) is handled."
  [args]
  (loop [out {} args args]
    (if-let [[f v & more] (seq args)]
      (cond
        (str/starts-with? f "--")
        (if (and v (not (str/starts-with? v "--")))
          (recur (assoc out (keyword (subs f 2)) v) more)
          (recur (assoc out (keyword (subs f 2)) true)
                 (if v (cons v more) more)))
        :else
        (recur (update out ::positional (fnil conj []) f)
               (if v (cons v more) more)))
      out)))
