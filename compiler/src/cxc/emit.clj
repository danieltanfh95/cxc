(ns cxc.emit
  "Emit ObjC/C source from the ObjC AST produced by cxc.lower.

   Output is a single `.m` string per :objc-program. Grown feature by
   feature alongside the lower pass."
  (:require [clojure.string :as str]))

(declare emit-expr emit-stmt emit-class emit-struct indent-lines)

;; ---------------------------------------------------------------------------
;; Expression emission
;; ---------------------------------------------------------------------------

(defn- escape-c-string [s]
  ;; Minimal escaping: backslash, double-quote, newline, tab, return.
  ;; Enough for the current subset; extend as needed.
  (-> s
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")
      (str/replace "\t" "\\t")
      (str/replace "\r" "\\r")))

(defn- emit-expr [node]
  (case (:node node)
    :str             (str "\"" (escape-c-string (:value node)) "\"")
    :objc-str        (str "@\"" (escape-c-string (:value node)) "\"")
    :int             (str (:value node))
    :float           (str (:value node))
    :nil             "nil"
    :name            (:sym node)
    :binop           (str "(" (emit-expr (:left node))
                          " " (:op node) " "
                          (emit-expr (:right node)) ")")
    :field           (str (emit-expr (:target node)) "." (:field node))
    :struct-lit      (str "((" (:type node) "){"
                          (str/join ", "
                                    (map (fn [f]
                                           (str "." (:name f) " = "
                                                (emit-expr (:value f))))
                                         (:fields node)))
                          "})")
    :objc-alloc-init (str "[[" (:class node) " alloc] init]")
    :objc-init-with  (let [parts (:args node)
                           first-part (str "initWith" (:name (first parts)) ":"
                                           (emit-expr (:value (first parts))))
                           rest-parts (str/join " "
                                                (map (fn [p]
                                                       (str (:name p) ":" (emit-expr (:value p))))
                                                     (rest parts)))]
                       (str "[[" (:class node) " alloc] "
                            first-part
                            (when-not (str/blank? rest-parts) (str " " rest-parts))
                            "]"))
    :call            (str (:fn node)
                          "("
                          (str/join ", " (map emit-expr (:args node)))
                          ")")
    :objc-block-literal
    ;; `^{ stmt; stmt; }` (or `^(T1 a, T2 b) { ... }` when :params
    ;; present) — an ObjC block literal. Used both to wrap message-
    ;; send bodies for cxc_send_async / cxc_send_sync (void^(void))
    ;; and to pass callbacks into container methods like
    ;; `-cxc_for_each:` (void^(id) — Layer 5 Step 5). Blocks capture
    ;; surrounding variables by copy — atom pointers via ARC-strong
    ;; retain, primitives by value; safe by construction because
    ;; the block's target lifetime is the outer expression.
    (let [stmts    (str/join "\n" (map emit-stmt (:stmts node)))
          indented (indent-lines 4 stmts)
          params   (:params node)
          sig      (if (seq params)
                     (str "^("
                          (str/join ", "
                                    (map (fn [p] (str (:c-type p) " " (:name p)))
                                         params))
                          ")")
                     "^")]
      (str sig "{\n" indented "\n}"))
    :objc-cast       (str "(" (:c-type node) ")" (emit-expr (:expr node)))
    :objc-num-lit    (case (:kind node)
                       :int   (str "@" (:value node))
                       :float (str "@(" (:value node) ")")
                       (str "@(" (emit-expr (:expr node)) ")"))
    :objc-array-literal
    ;; ObjC boxed-array literal `@[<items>]`. Items are already emitted
    ;; in their boxed form (NSNumber for primitives, id for references).
    ;; Used by List of T initializers to feed the container class'
    ;; -initWithElements:(NSArray *) constructor.
    (str "@[" (str/join ", " (map emit-expr (:items node))) "]")
    :objc-msg        (let [sel (:selector node)
                           args (:args node)]
                       ;; ObjC selectors either have zero colons (bare name,
                       ;; zero args) or one colon per arg baked into the
                       ;; selector string. Interleave selector parts with args.
                       (if (empty? args)
                         (str "[" (emit-expr (:recv node)) " " sel "]")
                         ;; str/split strips trailing empties, so the parts
                         ;; vector already lines up 1:1 with args.
                         (let [parts (str/split sel #":")]
                           (str "[" (emit-expr (:recv node)) " "
                                (str/join " "
                                          (map (fn [p a] (str p ":" (emit-expr a)))
                                               parts args))
                                "]"))))
    (throw (ex-info (str "emit-expr: unknown node " (:node node))
                    {:node node}))))

;; ---------------------------------------------------------------------------
;; Statement emission
;; ---------------------------------------------------------------------------

(defn- emit-stmt [node]
  (case (:node node)
    :call     (str (emit-expr node) ";")
    :objc-msg (str (emit-expr node) ";")
    :return   (str "return " (emit-expr (:value node)) ";")
    :assign   (str (emit-expr (:lhs node)) " = " (emit-expr (:rhs node)) ";")
    :raw-c    (:text node)
    :invariant-check
    ;; Direct if/abort — NSAssert is compiled out by NS_BLOCK_ASSERTIONS
    ;; in release builds; invariants must always fire.
    (str "if (!(" (emit-expr (:cond node)) ")) {\n"
         "    fprintf(stderr, \"" (escape-c-string (:label node)) "\\n\");\n"
         "    abort();\n"
         "}")
    :phase-guard
    ;; Early-exit if the atom isn't in the transition's source phase.
    ;; Pointer-equality against a canonicalized `k<Atom>_phase_<name>`
    ;; constant — O(1), no string compare.
    (str "if (_phase != " (:phase-const node) ") return;")
    :phase-guard-eq
    ;; Idempotency guard — early return if already at the target phase.
    ;; Used by -transitionToEnd to make the cascade one-shot.
    (str "if (_phase == " (:phase-const node) ") " (:ret node))
    :end-cascade-child
    ;; Cascade end to a nested-slot child: async send onto child's
    ;; own_queue with a block that runs the child's own -transitionToEnd.
    ;; Uses the CXCAtom protocol cast so `id`-typed calls compile
    ;; without needing the concrete child type at this call site.
    (let [slot (str "_" (:slot-name node))]
      (str "cxc_send_async(" slot ", ^{ [(id<CXCAtom>)" slot " transitionToEnd]; });"))
    :end-cascade-parent
    ;; Notify parent (weak back-ref) to nil the slot holding self.
    ;; Snapshot _parent + _parent_slot_name to strong locals so the
    ;; block captures stable references (weak _parent could nil at any
    ;; time; the runtime nil-guard in cxc_send_async covers that too).
    (str "{\n"
         "    __strong id p = _parent;\n"
         "    NSString *sn = _parent_slot_name;\n"
         "    if (p != nil && sn != nil) {\n"
         "        cxc_send_async(p, ^{ [(id<CXCAtom>)p nilSlot:sn]; });\n"
         "    }\n"
         "}")
    :main-bootstrap
    ;; Construct the Main atom into the file-scope static that survives
    ;; across the autoreleasepool boundary into dispatch_main(). The
    ;; static declaration is emitted by the top-level emit function
    ;; before main(); here we just do the assignment.
    (str "cxc_main_atom = [[" (:class-name node) " alloc] init];")
    :nil-slot-branch
    ;; One arm of parent's -nilSlot: switch — sets the named slot ivar
    ;; to nil when the incoming slotName matches. isEqualToString: is
    ;; used because the caller (child) copied the slot name; not
    ;; necessarily pointer-equal to any literal here.
    (str "if ([slotName isEqualToString:@\""
         (escape-c-string (:slot-name node)) "\"]) { _"
         (:slot-name node) " = nil; return; }")
    :end-catch-wrap
    ;; BEAM-style error containment (design §20). Wraps a transition-
    ;; branch body: any exception thrown by then:/do:/invariants
    ;; routes the atom to -transitionToEnd (parent sees slot nil, can
    ;; react). Selector `_cmd` is auto-provided by ObjC — names the
    ;; failing message in the log. Atom-local failure; the process
    ;; stays alive.
    (let [body (str/join "\n" (map emit-stmt (:body node)))
          block (indent-lines 4 body)]
      (str "@try {\n"
           block
           "\n} @catch (NSException *e) {\n"
           "    NSLog(@\"[cxc atom fail] %@[%p] caught %@ during -%@ (phase=%@)\", "
           "NSStringFromClass([self class]), self, e.name, "
           "NSStringFromSelector(_cmd), _phase);\n"
           "    [self transitionToEnd];\n"
           "}"))
    :end-catch-log-only
    ;; Same shape but for -transitionToEnd's own body: an exception
    ;; here MUST NOT re-invoke transitionToEnd (design §20 loop guard).
    ;; The idempotency check would swallow re-entry anyway, but keeping
    ;; the catch a pure log avoids ambiguity.
    (let [body (str/join "\n" (map emit-stmt (:body node)))
          block (indent-lines 4 body)]
      (str "@try {\n"
           block
           "\n} @catch (NSException *e) {\n"
           "    NSLog(@\"[cxc atom fail during -> end] %@[%p] caught %@ during -%@\", "
           "NSStringFromClass([self class]), self, e.name, "
           "NSStringFromSelector(_cmd));\n"
           "}"))
    :transition-branch
    ;; One arm of a first-match dispatch for a message-selector method.
    ;; Guard = `_phase == kA_phase_source` && header/body when: expressions
    ;; (already AND-folded by lower). Body runs then:/phase-update/do:,
    ;; then early-returns so subsequent branches don't fire.
    (let [phase-eq (str "_phase == " (:source-phase-const node))
          guard    (if-let [g (:guard node)]
                     (str "(" phase-eq ") && (" (emit-expr g) ")")
                     phase-eq)
          body     (str/join "\n" (map emit-stmt (:body node)))
          block    (indent-lines 4 (if (str/blank? body) "return;"
                                     (str body "\nreturn;")))]
      (str "if (" guard ") {\n" block "\n}"))
    :queue-init
    ;; Layer 4: per-atom serial queue targeting the main queue. All work
    ;; stays single-threaded; the primitive matches what Layer 4c will
    ;; need — retargeting queues off-main is a one-line change per atom.
    (str "{\n"
         "    NSString *cxc_qlabel = [NSString stringWithFormat:@\"cxc.%@.%p\","
         " NSStringFromClass([self class]), self];\n"
         "    " (:ivar node)
         " = dispatch_queue_create([cxc_qlabel UTF8String], DISPATCH_QUEUE_SERIAL);\n"
         "    dispatch_set_target_queue(" (:ivar node)
         ", dispatch_get_main_queue());\n"
         "}")
    :hashtable-init
    ;; NSHashTable with weak-object membership: subscribers can dealloc
    ;; freely, entries auto-disappear (design §18). Zero-cost when no
    ;; subscribers ever register.
    (str (:ivar node) " = [NSHashTable weakObjectsHashTable];")
    :stream-fanout
    ;; Emit-method body for a compiler-synthesized stream. Iterates the
    ;; subscriber hashtable and cxc_send_async's each with a block that
    ;; dispatches the compile-time-known subscriber-side selector
    ;; (`cxc_stream_<name>_from_<Source>[With<k>:]`). Sub is captured by
    ;; block per-iteration under ARC (retained for block lifetime, released
    ;; after execution — safe against sub dealloc between fanout + fire).
    ;; Payload args are captured by value into the block (primitives copy,
    ;; records copy, atom pointers strong-retain under ARC).
    (let [ivar (:ivar node)
          sel  (:selector node)
          keys (:arg-names node)
          ;; Build the message-send text. Zero-arg selector has no colons;
          ;; multi-arg interleaves selector parts with argument names.
          send-expr (if (empty? keys)
                      (str "[sub " sel "]")
                      (let [parts (str/split sel #":")]
                        (str "[sub "
                             (str/join " "
                                       (map (fn [p k] (str p ":" k))
                                            parts keys))
                             "]")))]
      (str "for (id sub in " ivar ") {\n"
           "    cxc_send_async(sub, ^{ " send-expr "; });\n"
           "}"))
    :stream-add-sub
    ;; Per-stream `-cxc_addSubscriberFor_<name>:` body. Direct
    ;; addObject under Layer 4 single-thread — all init runs on main
    ;; before dispatch_main() starts, so no concurrent mutation is
    ;; possible. The earlier design analysis suggested wrapping in
    ;; dispatch_sync(_own_queue, ^{...}) for Layer 4c multi-thread
    ;; future-proofing, but under Layer 4's queues-target-main model
    ;; that's a deadlock (main thread → dispatch_sync → queue targeting
    ;; main → waits for main thread). Layer 4c will re-add the sync
    ;; wrap once queues target concurrent globals.
    (str "[" (:ivar node) " addObject:sub];")
    :stream-remove-sub
    ;; Mirror of :stream-add-sub for slot-setter unsubscribe path
    ;; (Layer 4b nullable-slot dynamic subscription). NSHashTable's
    ;; removeObject: no-ops when the object isn't present, which
    ;; happens naturally at first assignment (nothing to remove yet).
    (str "[" (:ivar node) " removeObject:sub];")
    :slot-setter-body
    ;; Layer 4b slot setter for a nullable atom field that is a
    ;; subscription root. Sequence: early-return on identity match,
    ;; unsubscribe self from each stream on the OLD value (nil-message
    ;; -safe), assign the ivar, subscribe self to each stream on the
    ;; NEW value (also nil-message-safe). No explicit nil check needed
    ;; because Cocoa nil-messaging returns 0/nil.
    (let [ivar    (:ivar node)
          streams (:streams node)
          unsub-lines (->> streams
                           (map (fn [s]
                                  (str "[" ivar " cxc_removeSubscriberFor_" s ":self];")))
                           (str/join "\n    "))
          sub-lines   (->> streams
                           (map (fn [s]
                                  (str "[newValue cxc_addSubscriberFor_" s ":self];")))
                           (str/join "\n    "))]
      (str "if (" ivar " == newValue) return;\n"
           "    " unsub-lines "\n"
           "    " ivar " = newValue;\n"
           "    " sub-lines))
    :stream-add-sub-dispatch
    ;; Generic `-cxc_addSubscriberToStream:sub:` body. If-chain over the
    ;; source atom's declared streams; unknown names log for debug
    ;; visibility rather than silently drop. Matches design §18's
    ;; serialization/restore path (subscriber-atom-ID + stream-name +
    ;; selector-name tuples on restore).
    (let [branches
          (->> (:branches node)
               (map (fn [b]
                      (str "if ([streamName isEqualToString:@\""
                           (escape-c-string (:stream-name b))
                           "\"]) { [self " (:selector b) "sub]; return; }")))
               (str/join "\n"))]
      (str branches
           "\nNSLog(@\"cxc: unknown stream `%@` on %@\", "
           "streamName, [self class]);"))
    (throw (ex-info (str "emit-stmt: unknown node " (:node node))
                    {:node node}))))

(defn- indent-lines [n s]
  (let [pad (str/join (repeat n " "))]
    (->> (str/split-lines s)
         (map #(str pad %))
         (str/join "\n"))))

(defn- emit-body [n-spaces stmts]
  (indent-lines n-spaces (str/join "\n" (map emit-stmt stmts))))

;; ---------------------------------------------------------------------------
;; C struct emission (records → value-typed structs, no methods yet)
;; ---------------------------------------------------------------------------

(defn- emit-struct [s]
  (let [decls (->> (:fields s)
                   (map (fn [f] (str "    " (:c-type f) " " (:name f) ";")))
                   (str/join "\n"))]
    (str "typedef struct {\n" decls "\n} " (:name s) ";")))

(defn- emit-c-enum [e]
  (let [members (->> (:cases e)
                     (map (fn [c] (str "    " (:name e) "_" c)))
                     (str/join ",\n"))]
    (str "typedef enum {\n" members "\n} " (:name e) ";")))

;; ---------------------------------------------------------------------------
;; C function emission (record methods → free functions)
;; ---------------------------------------------------------------------------

(defn- emit-param [p]
  (str (:c-type p) " " (:name p)))

(defn- emit-function [f]
  (let [params (str/join ", " (map emit-param (:params f)))
        body   (emit-body 4 (:body f))]
    (str (:return-type f) " " (:name f) "(" params ") {\n"
         body
         "\n}")))

;; ---------------------------------------------------------------------------
;; ObjC class emission — @interface + @implementation for a def-atom
;; ---------------------------------------------------------------------------

(defn- prop-attrs-string [attrs]
  (if (seq attrs)
    (str "(" (str/join ", " attrs) ")")
    ""))

(defn- emit-property [p]
  (let [attrs (prop-attrs-string (:attrs p))
        space (if (str/blank? attrs) "" " ")]
    (str "@property " attrs space (:c-type p) " " (:name p) ";")))

(defn- method-signature
  "ObjC instance-method signature. Zero-arg → `- (T)selector`; with args
   → `- (T)part1:(T1)name1 part2:(T2)name2`. Uses the selector-with-
   colons convention from cxc.lower/objc-method-selector."
  [m]
  (let [sel (:selector m)
        ps  (:params m)]
    (if (empty? ps)
      (str "- (" (:return-type m) ")" sel)
      (let [parts (str/split sel #":")             ; strips trailing empties
            body  (str/join " "
                            (map (fn [part p]
                                   (str part ":(" (:c-type p) ")" (:name p)))
                                 parts ps))]
        (str "- (" (:return-type m) ")" body)))))

(declare init-signature)

(defn- emit-interface [cls]
  (let [props     (->> (:properties cls)
                       (map emit-property)
                       (map #(str "  " %))
                       (str/join "\n"))
        ;; Custom -initWith... needs an @interface declaration too, else
        ;; call sites using the class through a bare `id` can't type-check
        ;; the selector. Bare `-init` inherits from NSObject and needs no
        ;; extra declaration; only emit when init-params are non-empty.
        init-decl (when (seq (:init-params cls))
                    (str "  " (init-signature (:init-params cls)) ";"))
        method-decls (->> (:methods cls)
                          (map #(str "  " (method-signature %) ";"))
                          (str/join "\n"))
        body-parts (remove str/blank? [props init-decl method-decls])
        body       (if (empty? body-parts) "" (str "\n" (str/join "\n" body-parts) "\n"))]
    (str "@interface " (:name cls) " : " (:superclass cls) body "@end")))

(defn- init-signature
  "Build the ObjC init method signature. No params → `- (instancetype)init`.
   With params → `- (instancetype)initWith<first>:(<type>)<name> <name>:(<type>)<name> ...`.
   The `initWith` prefix + uppercase W satisfies ARC's init-family rule."
  [init-params]
  (if (empty? init-params)
    "- (instancetype)init"
    (let [first-p     (first init-params)
          rest-ps     (rest init-params)
          first-part  (str "initWith" (:name first-p) ":("
                           (:c-type first-p) ")" (:name first-p))
          rest-parts  (str/join " "
                                (map (fn [p]
                                       (str (:name p) ":(" (:c-type p) ")" (:name p)))
                                     rest-ps))]
      (str "- (instancetype)" first-part
           (when-not (str/blank? rest-parts) (str " " rest-parts))))))

(defn- emit-method-impl [m]
  (str (method-signature m) " {\n"
       (emit-body 4 (:body m))
       "\n}"))

(defn- emit-implementation [cls]
  (let [init-body     (:init-body cls)
        init-params   (:init-params cls)
        init-stmts    (if (seq init-body)
                        (str "\n" (emit-body 8 init-body))
                        "")
        init-block    (str (init-signature init-params) " {\n"
                           "    self = [super init];\n"
                           "    if (self) {"
                           init-stmts
                           "\n    }\n"
                           "    return self;\n"
                           "}")
        method-impls  (str/join "\n\n" (map emit-method-impl (:methods cls)))
        body-blocks   (remove str/blank? [init-block method-impls])]
    (str "@implementation " (:name cls) "\n"
         (str/join "\n\n" body-blocks)
         "\n@end")))

(defn- emit-phase-constants
  "File-scope `static NSString * const k<Atom>_phase_<name> = @\"<name>\";`
   lines, one per phase. Canonicalized: two reads of the same constant
   compare pointer-equal, so `_phase == kFoo_phase_alive` is O(1) with
   no `isEqualToString:` dispatch."
  [cls]
  (when (seq (:phase-constants cls))
    (->> (:phase-constants cls)
         (map (fn [pc]
                (str "static NSString * const " (:name pc)
                     " = @\"" (escape-c-string (:value pc)) "\";")))
         (str/join "\n"))))

(defn- emit-class-interface-block
  "Phase constants + @interface for one class. Emitted in the interface
   pass at the top of the file so every class's selectors are visible
   before any @implementation body runs — matters for stream-fanout
   sites that message `id`-typed subscribers with the receiver-side
   `cxc_stream_<name>_from_<Source>` selector, which needs a visible
   declaration to pick a type signature."
  [cls]
  (let [phase-consts (emit-phase-constants cls)
        parts        (remove str/blank? [phase-consts
                                         (emit-interface cls)])]
    (str/join "\n\n" parts)))

(defn- emit-class [cls]
  (emit-implementation cls))

;; ---------------------------------------------------------------------------
;; Program emission
;; ---------------------------------------------------------------------------

(defn- emit-main
  "C main() function. Two shapes:
     - No dispatch loop: run body inside @autoreleasepool, return 0.
       Fits programs with no message-triggered transitions (their work
       runs synchronously during init and completes before return).
     - Dispatch loop: run bootstrap (Main atom construction) inside
       @autoreleasepool, then call dispatch_main() which never returns.
       cxc_shutdown() from a `-> end` transition on the main atom is
       the termination path (design §19)."
  [main-node needs-dispatch-loop?]
  (let [body (emit-body 4 (:body main-node))
        dispatch-suffix (if needs-dispatch-loop?
                          "\n    dispatch_main();\n    return 0;   /* unreachable */"
                          "")]
    (if (:autorelease? main-node)
      (str "int main(int argc, char *argv[]) {\n"
           "    @autoreleasepool {\n"
           (indent-lines 4 body)
           "\n    }"
           dispatch-suffix
           "\n}")
      (str "int main(int argc, char *argv[]) {\n"
           body
           dispatch-suffix
           "\n}"))))

(def ^:private cxc-runtime-helpers
  ;; Header-only runtime (design §22). The CXCAtom protocol declares
  ;; the wire operations every atom class satisfies implicitly:
  ;;   - own_queue: the atom's serial dispatch queue
  ;;   - nilSlot: parent-side nested-slot auto-nil (§7.2)
  ;;   - transitionToEnd: cascade entry-point (§7.3)
  ;; Helpers cast id → id<CXCAtom> for calls that need those messages,
  ;; cheaper than KVC / objc_msgSend / dynamic lookup. `cxc_send_sync`
  ;; under Layer 4 single-thread is a nil-guarded direct call; Layer
  ;; 4c retargets to dispatch_sync when queue targets differ.
  (str "@protocol CXCAtom\n"
       "@property (nonatomic, strong) dispatch_queue_t own_queue;\n"
       "- (void)nilSlot:(NSString *)slotName;\n"
       "- (void)transitionToEnd;\n"
       "@end\n\n"
       "static inline void cxc_send_async(id target, void (^block)(void)) {\n"
       "    if (target == nil) return;\n"
       "    dispatch_async([(id<CXCAtom>)target own_queue], block);\n"
       "}\n\n"
       "static inline void cxc_send_sync(id target, void (^block)(void)) {\n"
       "    if (target == nil) return;\n"
       "    block();\n"
       "}\n\n"
       "static inline void cxc_shutdown(void) {\n"
       "    exit(0);\n"
       "}\n\n"
       ;; Layer 5 Step 7 — CxcStream: first-class stream value.
       ;; Method-returning-stream calls (e.g. clock.every(rate) at
       ;; Step 10) allocate a CxcStream per call. Subscribers register
       ;; on the stream's own hashtable; the source (Clock, or a Layer
       ;; 5b transformer) invokes cxc_emit on the stream to fan out to
       ;; subscribers. Teardown block runs on end (Layer 5b cancels
       ;; timers, releases OS resources).
       ;;
       ;; Value passing: CxcStream * is an id-typed handle; passed
       ;; through data fields, let bindings, and receive-glyph source
       ;; expressions like any other atom reference.
       ;;
       ;; Layer 5 Step 7 minimum: only `-cxc_emit` (no payload) plus
       ;; per-stream own_queue for hop parity with Layer 4b atoms.
       ;; Typed-payload emit (`-cxc_emit_Withk:...`) lands at Step 8
       ;; once a real user of method-returned streams (Clock.every
       ;; at Step 10) forces the shape.
       "@interface CxcStream : NSObject\n"
       "@property (nonatomic, strong) dispatch_queue_t own_queue;\n"
       "@property (nonatomic, strong) NSHashTable *subscribers;\n"
       "@property (nonatomic, copy) void (^teardown)(void);\n"
       "- (void)cxc_addSubscriber:(id)sub;\n"
       "- (void)cxc_removeSubscriber:(id)sub;\n"
       "- (void)cxc_emit;\n"
       "- (void)cxc_end;\n"
       "@end\n\n"
       "@implementation CxcStream\n"
       "- (instancetype)init {\n"
       "    self = [super init];\n"
       "    if (self) {\n"
       "        _own_queue = dispatch_queue_create(\"cxc.stream\", DISPATCH_QUEUE_SERIAL);\n"
       "        dispatch_set_target_queue(_own_queue, dispatch_get_main_queue());\n"
       "        _subscribers = [NSHashTable weakObjectsHashTable];\n"
       "    }\n"
       "    return self;\n"
       "}\n"
       "- (void)cxc_addSubscriber:(id)sub {\n"
       "    if (sub != nil) [_subscribers addObject:sub];\n"
       "}\n"
       "- (void)cxc_removeSubscriber:(id)sub {\n"
       "    if (sub != nil) [_subscribers removeObject:sub];\n"
       "}\n"
       "- (void)cxc_emit {\n"
       "    for (id sub in _subscribers) {\n"
       "        cxc_send_async(sub, ^{ [sub performSelector:@selector(cxc_stream_value)]; });\n"
       "    }\n"
       "}\n"
       "- (void)cxc_end {\n"
       "    if (_teardown) _teardown();\n"
       "    _teardown = nil;\n"
       "    [_subscribers removeAllObjects];\n"
       "}\n"
       "@end"))

(defn emit
  "Emit a full .m source string from an :objc-program AST. Class output
   is split into an INTERFACES section (phase constants + @interface
   blocks) followed by an IMPLEMENTATIONS section — so every class's
   method declarations are visible before ANY @implementation body runs.
   Matters for stream fan-out: `[sub cxc_stream_beep_from_Emitter]` on
   an `id`-typed subscriber needs the subscriber-class's @interface to
   be in scope when the source's fanout body compiles."
  [prog]
  (let [includes    (str/join "\n"   (map #(str "#import " %) (:includes prog)))
        helpers     (when (:needs-runtime? prog) cxc-runtime-helpers)
        enums       (str/join "\n\n" (map emit-c-enum   (:enums prog)))
        structs     (str/join "\n\n" (map emit-struct   (:structs prog)))
        functions   (str/join "\n\n" (map emit-function (:functions prog)))
        interfaces  (str/join "\n\n" (map emit-class-interface-block (:classes prog)))
        impls       (str/join "\n\n" (map emit-class    (:classes prog)))
        ;; File-scope static that main() assigns to. Lives across the
        ;; @autoreleasepool boundary into dispatch_main() so the atom
        ;; isn't released before the queue starts draining.
        main-static (when (some #(= "CxcMain" (:name %)) (:classes prog))
                      "static CxcMain *cxc_main_atom = nil;")
        main        (emit-main (:main prog) (:needs-dispatch-loop? prog))]
    (str/join "\n\n"
              (remove str/blank? [includes helpers enums structs functions
                                  interfaces impls
                                  main-static main]))))
