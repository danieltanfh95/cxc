(ns cxc.lower.phase-helpers
  "Accessors, classifiers, and main-phase diagnostics over an atom's
   transition list. No env access or type inference — only parsed AST.

   `initial-phase-for` / `collect-phase-names` / `phase-constant-name`
   drive the phase-state machinery scaffolding (declaration order fixes
   the initial phase; `k<Atom>_phase_<name>` constant naming).

   The classifier trio (`wildcard-on-enter?`, `message-triggered?`,
   `transition-trigger-kind`) plus the name accessors
   (`transition-msg-name`, `transition-display-name`) let lowerers ask
   'what shape is this transition?' without repeatedly walking the
   `:receive :msg :source` slot.

   `wildcards-by-target-phase` groups wildcards for on-enter chain
   emission (design §6 — wildcard defaults then specific override)."
  (:require [clojure.string :as str]
            [cxc.diagnostics :refer [fail-user!]]))

(defn initial-phase-for
  "First phase mentioned as a transition target — i.e. the phase the
   atom enters when init completes. cxc has no explicit initial-phase
   marker; declaration order fixes it. Atoms without any transitions
   default to `init` — every atom has a phase (spec §1.12), and `init`
   is a semantically-neutral choice for `data:`/`methods:`-only atoms."
  [transitions]
  (or (some :target transitions) "init"))

(defn collect-phase-names
  "All phase names appearing in transition sources or targets, plus the
   implicit `init` and `end` sentinels. Order-preserving dedup — the
   first mention fixes ordering, which matters for readability of the
   emitted `static NSString * const` block. `init` is always first,
   `end` always last. `self` (audit F24 — spec §1.11) is a target
   pseudo-phase meaning \"no phase change\" and is NOT a real phase."
  [transitions]
  (let [mentioned (concat (keep #(when (= :phase (get-in % [:source :kind]))
                                   (get-in % [:source :name])) transitions)
                          (map :target transitions))
        middle    (remove #{"init" "end" "self" "*"} (distinct mentioned))]
    (vec (concat ["init"] middle ["end"]))))

(defn phase-constant-name
  "Canonicalized ObjC constant name for a phase: `k<Atom>_phase_<name>`.
   Every read/write in emitted code references one of these — never a
   raw literal — so pointer-equality comparisons are safe (spec §5)."
  [atom-name phase-name]
  (str "k" atom-name "_phase_" phase-name))

(defn wildcard-on-enter?
  "A transition with implicit source and no message receipt —
   the `-> phase then/do: ...` form that fires on every entry."
  [t]
  (and (= :implicit (get-in t [:source :kind]))
       (nil? (:receive t))))

(defn message-triggered?
  "A transition with a receive glyph (`<:` async, `<|` sync). Contrasts
   with implicit-source wildcards (`-> phase ...`) which fire on every
   entry to their target phase."
  [t]
  (contains? #{"<:" "<|"} (get-in t [:receive :glyph])))

(defn- reachable-phases
  "Follow receive arms from the entry phase to a fixed point. Guards
   and message availability are conservatively treated as possible.
   Self-sends in on-enter/then bodies use these same receive arms;
   their message names are not direct phase jumps. `-> self` leaves
   the phase unchanged, and wildcard sources apply in every phase."
  [transitions initial]
  (loop [reachable #{initial}]
    (let [next-reachable
          (into reachable
                (keep (fn [t]
                        (when (and (message-triggered? t)
                                   (not= "self" (:target t))
                                   (or (= :wildcard (get-in t [:source :kind]))
                                       (and (= :phase (get-in t [:source :kind]))
                                            (contains? reachable
                                                       (get-in t [:source :name])))))
                          (:target t))))
                transitions)]
      (if (= reachable next-reachable)
        reachable
        (recur next-reachable)))))

(defn warn-unreachable-main-end!
  "Warn only for an explicitly declared, unreachable `end` in main.
   Other atoms may be driven externally. Do not use collect-phase-names
   here: it adds an implicit `end` sentinel even when none was declared."
  [main-node]
  (when (and (= :def-atom (:node main-node)) (= "main" (:name main-node)))
    (let [transitions (filter #(= :transition (:node %)) (:body main-node))
          ;; Handler-shaped arms may precede the first phase declaration
          ;; and are removed from the phase list during atom lowering.
          initial (or (some #(when (wildcard-on-enter? %) (:target %)) transitions)
                      (initial-phase-for transitions))]
      (when (and (some #(= "end" (:target %)) transitions)
                 (not (contains? (reachable-phases transitions initial) "end")))
        (let [declared (->> transitions
                             (mapcat #(vector (when (= :phase (get-in % [:source :kind]))
                                                (get-in % [:source :name]))
                                              (:target %)))
                             (keep identity)
                             (remove #{"self" "*"})
                             distinct)]
          (binding [*out* *err*]
            (println (str "warning: `def main` declares phases "
                          (str/join ", " declared)
                          ", but final phase `end` is unreachable from `" initial "`; "
                          "the program will not exit after `-> " initial "` completes. "
                          "Add a `then: self :> end` to `" initial
                          "` or a transition that terminates in `end` "
                          "(self-sends require a matching `<: end -> end` arm)."))))))))

(defn transition-trigger-kind
  "Classify a receive-glyph transition's source pattern:
   - :message       — bare ident like `bump`. Direct message-triggered
                      transition (Steps 4-7).
   - :stream        — dot expression like `source.beep`. Stream
                      subscription (Layer 4b Step 13); source atom's
                      static stream emit drives the transition.
   - :method-stream — call expression like `source.every(rate)`. The
                      source atom's method returns a CxcStream value
                      (Layer 5c Step 8); subscriber attaches at
                      cxc_runtimeBinding via -cxc_addSubscriber:."
  [t]
  (let [src (get-in t [:receive :msg :source])]
    (case (:node src)
      :ident :message
      :dot   :stream
      :call  (if (and (map? (:callee src))
                      (= :dot (get-in src [:callee :node])))
               :method-stream
               (fail-user! (str "receive-glyph source `:call` needs a "
                               "dot callee (`source.method(args)`); got "
                               (get-in src [:callee :node]))
                          src))
      (fail-user! (str "receive-glyph source pattern must be a bare ident "
                       "(message), dot expression (static stream), or "
                       "method call (returned stream); got " (:node src))
                  src))))

(defn transition-msg-name
  "Extract the message-name from a message-triggered receive-glyph
   transition. Only bare-ident sources call this; stream transitions
   go through transition-stream-descriptor."
  [t]
  (let [src (get-in t [:receive :msg :source])]
    (case (:node src)
      :ident (:name src)
      (fail-user! (str "expected bare-ident message source (got " (:node src) ")")
                  src))))

(defn transition-display-name
  "Human-readable identifier for a receive-glyph transition, used in
   diagnostics. Handles both message-triggered (`bump`) and stream-
   subscribed (`source.beep`) shapes without discriminating on kind."
  [t]
  (let [src (get-in t [:receive :msg :source])]
    (case (:node src)
      :ident (:name src)
      :dot   (str (get-in src [:target :name]) "." (:field src))
      (pr-str src))))

(defn wildcards-by-target-phase
  "Index wildcards (`-> phase then:/do:`) by their target phase. A
   phase may have zero, one, or (rare) multiple wildcards; multiples
   are concat'd in declaration order (design §6 leaves the count
   unspecified but doesn't forbid — declaration order is the natural
   choice)."
  [transitions]
  (reduce (fn [acc t] (update acc (:target t) (fnil conj []) t))
          {}
          (filter wildcard-on-enter? transitions)))
