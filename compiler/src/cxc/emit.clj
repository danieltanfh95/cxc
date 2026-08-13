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
    ;; `^{ stmt; stmt; }` — a void^(void) ObjC block literal. Used to
    ;; wrap message-send bodies for cxc_send_async / cxc_send_sync so
    ;; the target's queue can defer execution (design §10). Blocks
    ;; capture surrounding variables by copy — atom pointers via
    ;; ARC-strong retain, primitives by value; safe by construction
    ;; because the block's target lifetime is the outer expression.
    (let [stmts (str/join "\n" (map emit-stmt (:stmts node)))
          indented (indent-lines 4 stmts)]
      (str "^{\n" indented "\n}"))
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

(defn- emit-interface [cls]
  (let [props     (->> (:properties cls)
                       (map emit-property)
                       (map #(str "  " %))
                       (str/join "\n"))
        method-decls (->> (:methods cls)
                          (map #(str "  " (method-signature %) ";"))
                          (str/join "\n"))
        body-parts (remove str/blank? [props method-decls])
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

(defn- emit-class [cls]
  (let [phase-consts (emit-phase-constants cls)
        parts        (remove str/blank? [phase-consts
                                         (emit-interface cls)
                                         (emit-implementation cls)])]
    (str/join "\n\n" parts)))

;; ---------------------------------------------------------------------------
;; Program emission
;; ---------------------------------------------------------------------------

(defn- emit-main [main-node]
  (let [body (emit-body 4 (:body main-node))]
    (if (:autorelease? main-node)
      (str "int main(int argc, char *argv[]) {\n"
           "    @autoreleasepool {\n"
           (indent-lines 4 body)
           "\n    }\n"
           "}")
      (str "int main(int argc, char *argv[]) {\n"
           body
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
       "}"))

(defn emit
  "Emit a full .m source string from an :objc-program AST."
  [prog]
  (let [includes  (str/join "\n"   (map #(str "#import " %) (:includes prog)))
        helpers   (when (:needs-runtime? prog) cxc-runtime-helpers)
        enums     (str/join "\n\n" (map emit-c-enum   (:enums prog)))
        structs   (str/join "\n\n" (map emit-struct   (:structs prog)))
        functions (str/join "\n\n" (map emit-function (:functions prog)))
        classes   (str/join "\n\n" (map emit-class    (:classes prog)))
        main      (emit-main (:main prog))]
    (str/join "\n\n"
              (remove str/blank? [includes helpers enums structs functions classes main]))))
