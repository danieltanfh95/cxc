(ns cxc.lower.args-resolve
  "F40 Landing 1 — call-site arg validation + defaulting (spec §3.8.5).

   `resolve-args-against-params` matches a call's named/positional args
   against a params vector, folds each param's declared default in when
   no arg matches, and produces a positional-normalized
   [{:name :value} ...] vector for downstream lowering.

   Errors reported at fail-user!:
     - duplicate named args
     - unknown named args (arg name not in params)
     - surplus positional args (more positionals than params)
     - same param supplied positionally AND by name
     - no value AND no default"
  (:require [clojure.string :as str]
            [cxc.diagnostics :refer [fail-user!]]))

(set! *warn-on-reflection* true)

(defn- quoted-list [xs] (str/join ", " (map #(str "`" % "`") xs)))
(defn- plural [xs] (if (> (count xs) 1) "s" ""))

(defn resolve-args-against-params
  "Match named/positional call args against declared params in decl
   order. Fall back to each param's declared default; fail-nyi if
   neither is available. Returns a vector of {:name :value} pairs in
   declaration order.

   F40 Landing 1 — call-site validation (spec §3.8.5):
   - duplicate named args              → fail
   - unknown named args                → fail
   - surplus positional args           → fail
   - same param supplied twice
     (once positionally, once named)   → fail
   - missing arg without default       → fail-nyi (pre-existing)"
  [call-site-label params args]
  (let [param-names   (mapv :name params)
        param-set     (set param-names)
        named-args    (filterv #(= :named (:kind %)) args)
        positional    (filterv #(= :positional (:kind %)) args)
        _ (let [dupes (->> (map :name named-args)
                           frequencies
                           (keep (fn [[n c]] (when (> c 1) n)))
                           sort)]
            (when (seq dupes)
              (fail-user! (str "duplicate named arg" (plural dupes)
                               " on " call-site-label ": "
                               (quoted-list dupes))
                          {:site call-site-label :duplicates dupes})))
        _ (let [unknown (->> named-args
                             (map :name)
                             (remove param-set)
                             distinct
                             sort)]
            (when (seq unknown)
              (fail-user! (str "unknown named arg" (plural unknown)
                               " on " call-site-label ": "
                               (quoted-list unknown)
                               " (declared params: "
                               (quoted-list param-names)
                               ")")
                          {:site call-site-label :unknown unknown
                           :declared param-names})))
        _ (when (> (count positional) (count params))
            (fail-user! (str "too many positional args on " call-site-label
                             ": got " (count positional) ", expected at most "
                             (count params))
                        {:site call-site-label
                         :positional-count (count positional)
                         :param-count (count params)}))
        _ (let [by-name-set (set (map :name named-args))
                collisions  (->> (map-indexed (fn [i _] (get param-names i))
                                              positional)
                                 (filter by-name-set)
                                 distinct
                                 sort)]
            (when (seq collisions)
              (fail-user! (str "arg for param" (plural collisions)
                               " " (quoted-list collisions)
                               " supplied both positionally and by name on "
                               call-site-label)
                          {:site call-site-label :collisions collisions})))
        by-name (into {} (map (juxt :name :value) named-args))]
    (mapv (fn [i p]
            (let [v (or (get by-name (:name p))
                        (:value (get positional i))
                        (:default p))]
              (when-not v
                (fail-user! (str "no value provided for param `" (:name p)
                                 "` on " call-site-label
                                 " (no arg and no default)")
                            {:param (:name p) :site call-site-label}))
              {:name (:name p) :value v}))
          (range)
          params)))
