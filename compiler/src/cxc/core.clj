(ns cxc.core
  "cxc compiler entry point.

   Pipeline (each stage a namespace, added as we build):
     cxc.lex      — tokenize .cxc source
     cxc.parse    — token stream → AST
     cxc.infer    — local flow-sensitive type/contract inference within atoms
     cxc.check    — contract discharge (static where possible, runtime otherwise)
     cxc.lower    — atom AST → ObjC AST
     cxc.emit     — ObjC AST → .m + .h source
     cxc.build    — invoke clang, produce binary

   Runtime dependencies at link time: libobjc2, libdispatch, libuv,
   cxc runtime library (scheduler, mailboxes, contract-check helpers).

   See design/spec/spec-cxc-v1.0.md for language semantics.
   See design/working-notes.md Phase 6 session note for compiler decisions.")

(defn -main
  [& args]
  (println "cxc compiler — walking skeleton. No pipeline stages implemented yet.")
  (when (seq args)
    (println "Args:" (pr-str args))))
