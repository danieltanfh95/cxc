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
  (:require [cxc.lower.util :refer [int-constant-value field-ident?]]
            [cxc.type-kw :refer [optional-type-name]]
            [cxc.util.walk :refer [tree-nodes]]))

(defn- tighten-lo [cur v] (if cur (max cur v) v))
(defn- tighten-hi [cur v] (if cur (min cur v) v))

(defn- merge-binop-bound
  "Fold one `:binop` node into the running `{:lo :hi}` accumulator.
   Only shapes matching `(const <op> field)` or `(field <op> const)`
   contribute; everything else is a no-op. Mirrors the historical
   case ladder exactly."
  [{:keys [lo hi] :as acc} n field-name]
  (let [op (:op n) L (:left n) R (:right n)
        lv (int-constant-value L) rv (int-constant-value R)]
    (cond
      (and lv (field-ident? R field-name))
      (case op
        "<=" (assoc acc :lo (tighten-lo lo lv))
        "<"  (assoc acc :lo (tighten-lo lo (inc lv)))
        ">=" (assoc acc :hi (tighten-hi hi lv))
        ">"  (assoc acc :hi (tighten-hi hi (dec lv)))
        acc)
      (and rv (field-ident? L field-name))
      (case op
        "<=" (assoc acc :hi (tighten-hi hi rv))
        "<"  (assoc acc :hi (tighten-hi hi (dec rv)))
        ">=" (assoc acc :lo (tighten-lo lo rv))
        ">"  (assoc acc :lo (tighten-lo lo (inc rv)))
        acc)
      :else acc)))

(defn- collect-bounds-for-field
  "Walk `pred` (after chained-comparison desugaring) collecting bounds on
   `field-name`. Returns `{:lo <n or nil> :hi <n or nil>}` — where each
   is the tightest constant discovered across all conjuncts. Predicates
   with a shape the picker doesn't recognize contribute nothing (the
   post-store checkInvariants still evaluates them).

   Note: current implementation treats every `:binop` under `pred` as a
   conjunct — a comparison appearing beneath `or` or `not` is still
   intersected into the bounds. Cycle 37 Codex M1 flags the semantic
   defect; the mechanical conversion from `postwalk` + atom to
   `tree-nodes` + `reduce` here preserves prior behavior pending a
   logical-entailment landing."
  [pred field-name]
  (reduce (fn [acc n]
            (if (and (map? n) (= :binop (:node n)))
              (merge-binop-bound acc n field-name)
              acc))
          {:lo nil :hi nil}
          (tree-nodes pred)))

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
