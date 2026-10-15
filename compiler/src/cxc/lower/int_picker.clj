(ns cxc.lower.int-picker
  "F36 Landing 2a — invariant-driven representation picker (§3.1.2
   rule 3).

   For each bare `Int` `data:` field on an atom, walk the atom's
   invariants for chained-comparison predicates that constrain the
   field to a range that fits a sized atom (UInt8/UInt16/... or
   Int8/Int16/...). When such bounds exist for both `lo` AND `hi`,
   narrow the field's storage to the smallest fitting sized atom.

   Sized-atom fields (UInt8/UInt16/etc.) already pin their width per
   §3.1.2 rule 1 and are skipped by the picker. Only bare `Int`
   (Int64) is eligible.

   Safety: F49 Landing A's pre-store check runs on the wide source
   value before the narrowing assignment, so a source value that
   doesn't fit the narrowed storage aborts at construction (not
   silently truncates). Without F49 A, this narrowing would be a
   silent-corruption path.

   MVP form: only recognizes literal bounds (compile-time integer/
   float constants). Observable-form invariants (`value :- decode(raw)`
   with `<= value <=`) are v1.1+ picker work — this pass matches
   direct field references only."
  (:require [clojure.walk :as walk]
            [cxc.lower.util :refer [int-constant-value field-ident?]]
            [cxc.type-kw :refer [optional-type-name]]))

(defn- collect-bounds-for-field
  "Walk `pred` (after chained-comparison desugaring) collecting bounds on
   `field-name`. Returns `{:lo <n or nil> :hi <n or nil>}` — where each
   is the tightest constant discovered across all conjuncts. Predicates
   with a shape the picker doesn't recognize contribute nothing (the
   post-store checkInvariants still evaluates them)."
  [pred field-name]
  (let [acc (atom {:lo nil :hi nil})
        note-lo (fn [v] (swap! acc update :lo (fn [cur] (if cur (max cur v) v))))
        note-hi (fn [v] (swap! acc update :hi (fn [cur] (if cur (min cur v) v))))]
    (walk/postwalk
     (fn [n]
       (when (and (map? n) (= :binop (:node n)))
         (let [op (:op n) L (:left n) R (:right n)
               lv (int-constant-value L) rv (int-constant-value R)]
           (cond
             (and lv (field-ident? R field-name))
             (case op
               "<=" (note-lo lv)
               "<"  (note-lo (inc lv))
               ">=" (note-hi lv)
               ">"  (note-hi (dec lv))
               nil)
             (and rv (field-ident? L field-name))
             (case op
               "<=" (note-hi rv)
               "<"  (note-hi (dec rv))
               ">=" (note-lo rv)
               ">"  (note-lo (inc rv))
               nil)
             :else nil)))
       n)
     pred)
    @acc))

(def ^:private sized-int-atoms
  ;; Ordered narrowest → widest so `first` picks the smallest fit.
  ;; Each entry: {:name cxc-atom :lo <inclusive> :hi <inclusive>
  ;;              :c-type <C storage> :cxc-ty <cxc source-level name>}
  [{:name "UInt8"  :lo 0                   :hi 255
    :c-type "uint8_t"  :cxc-ty "UInt8"}
   {:name "Int8"   :lo -128                :hi 127
    :c-type "int8_t"   :cxc-ty "Int8"}
   {:name "UInt16" :lo 0                   :hi 65535
    :c-type "uint16_t" :cxc-ty "UInt16"}
   {:name "Int16"  :lo -32768              :hi 32767
    :c-type "int16_t"  :cxc-ty "Int16"}
   {:name "UInt32" :lo 0                   :hi 4294967295
    :c-type "uint32_t" :cxc-ty "UInt32"}
   {:name "Int32"  :lo -2147483648         :hi 2147483647
    :c-type "int32_t"  :cxc-ty "Int32"}])

(defn- pick-sized-atom
  "Given inclusive `lo`/`hi` bounds, return the entry from
   `sized-int-atoms` whose range fully contains [lo, hi], or nil if
   no narrower-than-Int64 atom fits."
  [lo hi]
  (when (and (integer? lo) (integer? hi) (<= lo hi))
    (first (filter #(and (<= (:lo %) lo) (<= hi (:hi %)))
                   sized-int-atoms))))

(def ^:private picker-eligible-cxc-tys
  ;; Only bare `Int` gets picked — sized atoms already pin their width
  ;; per §3.1.2 rule 1, and non-numeric types don't narrow.
  #{"Int"})

(defn narrow-fields-by-invariants
  "F36 Landing 2a — for each primitive-int-typed field, walk the atom's
   invariants for chained-comparison bounds on that field's name; if
   the union of bounds fits a sized atom, override the field's
   `:c-type` and `:cxc-ty` to the narrower storage.

   Only bare `Int` (Int64) fields are considered — sized-atom fields
   already pin their width per §3.1.2 rule 1. Fields with only one
   bound (or no bounds) keep Int64 storage — narrowing requires both
   lo AND hi.

   Post-store checkInvariants + F49-A pre-store check both still fire
   on the picked field; a violating source value aborts at
   construction without hitting the narrowing store."
  [fields invariants]
  (mapv
   (fn [f]
     (if-not (contains? picker-eligible-cxc-tys (:cxc-ty f))
       f
       (let [bounds  (reduce (fn [{:keys [lo hi]} p]
                               (let [{plo :lo phi :hi} (collect-bounds-for-field p (:name f))]
                                 {:lo (cond (nil? lo) plo
                                            (nil? plo) lo
                                            :else (max lo plo))
                                  :hi (cond (nil? hi) phi
                                            (nil? phi) hi
                                            :else (min hi phi))}))
                             {:lo nil :hi nil}
                             (map :predicate invariants))
             {:keys [lo hi]} bounds
             picked  (pick-sized-atom lo hi)]
         (if picked
           ;; F39 L5b-ii-c — preserve Optional-boxing after narrowing.
           ;; A bare-T value-typed field kept `:optional?` from the
           ;; storage flip; picker narrows the INNER cxc-type (Int64 →
           ;; UInt8 etc.), and the c-type re-wraps as
           ;; `_Cxc_Optional_<narrowed>`. `:scalar-c` stays the picker's
           ;; scalar for callers that pull the underlying rep.
           (let [narrowed-cxc (:cxc-ty picked)
                 narrowed-c   (:c-type picked)
                 opt?         (:optional? f)]
             (assoc f
                    :cxc-ty  narrowed-cxc
                    :c-type  (if opt? (optional-type-name narrowed-cxc)
                                 narrowed-c)
                    :scalar-c narrowed-c
                    :picker-narrowed-from "Int"
                    :picker-bounds [lo hi]))
           f))))
   fields))
