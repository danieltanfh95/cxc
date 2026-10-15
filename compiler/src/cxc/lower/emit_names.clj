(ns cxc.lower.emit-names
  "Emitted-name canonicalizers reused across the atom / record / reason
   lowering paths. Each wraps `->c-safe` with the compiler-reserved
   prefix or per-shape rule.

   `class-name-for-atom` picks `CxcMain` for `main` and mangles every
   other atom; `reason-box-name` and `synth-reason-box` build the
   `_Cxc_R_<record>` wrapper class used for `<msg>_failed(reason:)`
   companion emission on record-typed reasons."
  (:require [cxc.mangle :refer [->c-safe]]))

(defn class-name-for-atom
  "Emitted ObjC class name. Regular atoms use their cxc name (mangled
   at emit boundary via `->c-safe` — prelude atoms like `Clock` become
   `_Cxc_Clock`; user atoms stay unchanged in `:main`). Main gets
   `CxcMain` — `main` collides with C's main() symbol."
  [atom-name]
  (if (= "main" atom-name) "CxcMain" (->c-safe atom-name)))

(defn reason-box-name
  "Wrapper class name for a record. Prefix `_Cxc_R_` is compile-time
   reserved — leading underscore is off-limits to user identifiers.
   Takes the record's C-safe (mangled) name so prelude records produce
   `_Cxc_R__Cxc_Position`, user records `_Cxc_R_Counter`.
   F56 L2+L3 (2026-08-27): callers pass the mangled form via
   `->c-safe`."
  [record-c-name]
  (str "_Cxc_R_" record-c-name))

(defn synth-reason-box
  "Build a `:reason-box` node describing the wrapper class for one
   record. Emit consumes it as an `@interface X : NSObject { @public
   T value; } @end / @implementation X @end` pair."
  [record-node]
  (let [rec-c (->c-safe (:name record-node))]
    {:node          :reason-box
     :name          (reason-box-name rec-c)
     :struct-c-type rec-c}))
