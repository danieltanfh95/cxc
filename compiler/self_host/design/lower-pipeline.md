# The lower pipeline, from slice rows to `cards.m`

Chapter 37 roadmap for chapters 38 and later. It maps every environment `cxc.lower/lower` builds before its first lowering call to the slice that reproduces it, lists the passes still to port in `lower.clj` order with proposed slices and rough sizes, and proposes how the emit port reads the ObjC AST the lowering slices will print.

## Where the port stands

`lower/lower` (about 650 lines in `lower.clj`, line 9867 onward) runs three kinds of step. Environment builders turn the node stream into maps, inference passes type the observables and the stream payloads inside those maps, and lowering calls turn the maps and the nodes into the `:objc-program` map that `cxc.emit/emit` prints. Slices 39 to 42 reproduce the builders and the first inference round. Every slice prints sorted S-expression rows, reads the rows of the slices before it, and never rebuilds what an earlier slice printed.

| Step in `lower/lower` | Slice | Mode |
| --- | --- | --- |
| `resolve/ensure-references-enriched`, `env/attribute-modules-to-decls`, `env/module-map-from-attribution` | 39 | both |
| runtime-shape scan and conflict check, `:def-plugin` filter, `def main` check, non-protocol `class` check | 39 | both, `def main` in lower only |
| `build-protocol-env`, `sync-send-edges`, `build-enum-env` | 39 | both |
| `build-atom-env`, CxcStream seed, prelude tree-shake | 39 | both |
| `build-record-env`, `validate-no-agent-fields-in-records!` | 40 | both |
| `infer-observables-in-record-env` (defer true) | 41 | both |
| `infer-observables-in-atom-env` (defer true) | 41 | both |
| `infer-streams-in-atom-env` | 41 | both (chapter 37) |
| `augment-atom-env-with-companions` | 42 | both |
| `resolve/build-symbol-table`, `resolve/verify-parity!` | 40 | envs only |
| `named-tuple/collect-shapes` | 40 | envs only |
| `infer-protocol-defaults` | none yet | |
| `synthesize-container-entries` | 42 | envs only |
| `infer-observables-in-record-env` and `infer-observables-in-atom-env` (defer false) | none yet | |
| `infer-streams-in-atom-env`, `augment-atom-env-with-companions` (second round) | none yet | |
| `build-extension-env`, `compose-record-env-with-extensions`, `compose-atom-env-with-extensions` | none yet | |
| `infer-observables-in-record-env` (third round, defer true) | none yet | |
| `validate-explicit-conformances!`, `inherit-defaults-into-env` (records, atoms) | none yet | |
| `seed-class-fn-index-from-f37-decode` | none yet | |
| `mk-ctx`, then `lower-enum` | none yet | |

Lower mode stops each slice at the first step after its own that it cannot reproduce in lower's order. The symbol-table parity check sits between the augment and the container synthesis in lower, and no slice runs it in lower mode, so the containers are checked in envs mode only, the slice-40 precedent.

## Every environment key and the slice that prints it

### atom env, one entry per atom

| Key | Producer | Slice and row |
| --- | --- | --- |
| `:fields`, `:field-index` | `build-atom-env` | 39, `(atom ... (fields (f ...)*) ...)` |
| `:ctor-params` | `build-atom-env` | 39, `(ctor-params (c ...)*)` |
| `:methods`, `:method-index` | `build-atom-env` (handler methods) | 39, `(methods (method ...)*)` |
| `:observables` | `build-atom-env` | 39, `(observables <name>*)`, bodies read from the stream |
| `:observables-typed`, `:observable-index` | atom inference pass | 41, `(atom-obs ...)` then `(obs <atom> <name> <type-kw> "<c-type>")` |
| `:invariants` | `build-atom-env` | 39, `(invariants ...)` |
| `:transitions` | `build-atom-env` with the swallow arms | 39, `(transitions (transition ...)*)` |
| `:message-index`, `:companion-index` | `build-atom-env` | 39 |
| `:use-caps`, `:subscription-map`, `:slot-names`, `:on-nil-entries`, `:extras`, `:class-fns`, `:queue-config` | `build-atom-env` | 39 |
| `:stream-index`, `:stream-order` | `build-atom-env`, then the augment | 39, plus 42 `(companion <target> <msg> ...)` |
| `:stream-payload-index` | stream pass, then the augment | 41 `(spi <atom> <stream> ...)`, plus 42 |
| the key set itself | tree-shake and CxcStream seed | 39, `(keep <name>)` |
| container entries (`:kind`, `:element-type`, `:key-type`, `:val-type`, container `:methods`, `count`, fan-in) | `synthesize-container-entries` | 42, `(container ...)` |
| composed `:methods`, `:conforms`, `:invariants` after extensions | `compose-atom-env-with-extensions` | slice 44 (proposed) |
| inherited default methods | `inherit-defaults-into-env` | slice 44 (proposed) |

The `:conforms` key of an atom is absent before extension composition.

### record env, one entry per record

| Key | Producer | Slice and row |
| --- | --- | --- |
| `:fields`, `:field-index`, `:observables`, `:variant`, `:methods` (empty) | `build-record-env` | 40, `(record <name> <variant> (fields ...) (observables ...))` |
| `:observables-typed`, `:observable-index` | record inference pass | 41, `(record-obs ...)` then `(obs ...)` |
| second and third round types | defer-false pass, post-compose pass | slice 43 and 44 (proposed) |
| composed `:observables`, `:conforms`, `:variant` | `compose-record-env-with-extensions` | slice 44 (proposed) |
| inherited methods, `:method-index` | `inherit-defaults-into-env` | slice 44 (proposed) |
| `:class-fn-index` | `seed-class-fn-index-from-f37-decode` | slice 44 (proposed) |

### the other maps

| Map | Producer | Slice and row |
| --- | --- | --- |
| enum env (`:cases`, `:case-index`, `:case-entries`, `:payload-index`, `:tagged?`, `:raw-values`, `:raw-type`) | `build-enum-env` | 39, `(enum ...)` |
| protocol env (`:accepts`, `:accepts-index`, `:conforms`, `:defaults`) | `build-protocol-env` | 39, `(protocol ...)` |
| protocol defaults with `:cxc-return-ty` filled | `infer-protocol-defaults` | slice 43 (proposed) |
| `d->m` (`*decl->module*`) | `env/attribute-modules-to-decls` | 39, `(decl <name> <module>)` |
| module map | `env/module-map-from-attribution` | 39, `(module <module> <name>+)` |
| runtime shape and plugin shapes | the scan at the head of lower | 39, `(plugin-shape ...)`, `(runtime-shape ...)` |
| sync-send edges | `program-scan/sync-send-edges` | 39, `(edge ...)` |
| symbol table | `resolve/build-symbol-table` | 37 (table mode) and the parity row of 40 |
| named-tuple env (`ctx-nt-shapes`) | `named-tuple/collect-shapes` | 40, `(nt-shapes ...)` then `(shape <mangled>)` |
| extension env | `build-extension-env` | slice 44 (proposed) |
| ctx | `mk-ctx` over the maps above | no row, each lowering slice assembles it from the rows |

## The second inference round, slice 43

Every remaining typing step runs the slice-41 typer again. It cannot be copied into a new slice, so the proposal is to keep one typer binary and drive it with more rows rather than a new slice file. The slice-41 port grows input-driven rounds selected by argv, each reading the rows of the round before it.

- Protocol defaults. `infer-protocol-defaults` types each default observable with no declared return under the full ctx, which carries the module map and the named-tuple env. With the module map the typer reaches enum-case access, enum payload calls, and `Record.from`, the branches slice 41 leaves out, and errors other than `:not-yet-implemented` propagate.
- The defer-false passes. The record pass and the atom pass run again over the container rows of slice 42. The typer learns container method lookup (`:cxc-return-ty`, `:return-optional?`, the `count` observable, and the `min_by`, `max_by`, and `first` list sugars) and keeps every type the first round found.
- The stream pass and the augment run again on the retyped envs. The augment is slice 42 run a second time on the new rows.

Rough size. About 25 new fields and 40 arms in the typer slice, plus a second invocation of slice 42. The typer stays under a hundred fields if the rounds share the pass registers, which the chapter-37 stream pass already does.

## Composition and conformance, slice 44

`build-extension-env`, the two compose steps (the shadowing warnings go to stderr and are not ported), the third record pass (typer round again), `validate-explicit-conformances!` with `signature-mismatch-detail`, `inherit-defaults-into-env` with the structural conformance search and the diamond message, and `seed-class-fn-index-from-f37-decode` with `record-decode-eligible?`. About 700 lines of Clojure, no new inference, one new slice of about 60 fields. Few sweep files use `extend class`, so the hand corpus carries most of it.

## The lowering passes

Each lowering slice prints ObjC AST rows, one row per top-level program part, in the order `lower` builds them. The rows use the same S-expression shape as every slice so far, the node keyword as the head and every field in a fixed order, strings with pr-str escapes.

| Proposed slice | Clojure | Lines | Output rows |
| --- | --- | --- | --- |
| 45 enums and structs | `lower-enum` (`cxc.lower.enum`), `lower-enum-payload-ctor` shapes, `collect-optional-types`, `synth-optional-typedefs`, `lower-record-struct`, `synthesize-decode-outcome-struct`, the Uncaught struct, `synth-reason-box` | about 400 | `(c-enum ...)`, `(c-struct ...)`, `(reason-box ...)` |
| 46 record functions | `record-method-functions`, `record-observable-functions` (`lower-record-observable-fn`), `synthesized-record-functions` (equals, hash) | about 500 plus the expression lowering it calls | `(c-function ...)` |
| 47 F37 bytes | `cxc.lower.f37-bytes` (as-bytes and from-bytes synthesis, eligibility) | about 1500 | `(c-function ...)` |
| 48 to 51 expressions and statements | `lower-expr`, `lower-method-call`, `lower-stmt`, `lower-let-stmt`, `lower-if-let-stmt`, `lower-dispatch-*`, `lower-or-arm`, `lower-send`, the Optional machinery | about 5000 | statement and expression nodes inside the method rows |
| 52 and 53 atoms | `lower-atom`, `lower-transition-branch`, `lower-receive-glyph-transitions`, `lower-on-enter-body`, `lower-observable-method`, `lower-atom-method`, `lower-invariant-check`, `lower-atom-ctor`, selectors and stream subscription | about 2500 | `(objc-class ...)` with its ivars, properties, and methods |
| 54 containers | `synthesize-list-container-class`, `synthesize-map-container-class`, `synthesize-set-container-class` | about 700 | `(objc-class ...)` |
| 55 program assembly | includes, `collect-foreign-externs`, the runtime and dispatch-loop flags, the unreachable-end warning, `main-bootstrap` or `main-atom-body`, the lowered named-tuple typedefs | about 300 | `(objc-program-head ...)`, `(main ...)` |

The expression and statement lowering is the bulk and is recursive in the way the typer was, so it is the frame-stack idiom of chapter 36 again, one frame per open node, now building the lowered node's rendering in a register instead of a type keyword. The node shapes the emit reads are listed by `cxc.emit/emit-expr` and `emit-stmt`, and the lowering slices print exactly those, so the emit port never needs the cxc AST.

## How emit reads the rows

`cxc.emit/emit` (about 3000 lines) walks the `:objc-program` map in a fixed section order. The emit port is a set of slices over the same rows.

- One emit slice per section family, reading the rows of the lowering slice that made them and printing C text, the enums and structs first, then reason boxes, functions, classes, and the main function, the order `emit` uses.
- The harness concatenates the section outputs in that order. The oracle runs the real `cxc.emit/emit` and cuts its output at the same section boundaries, so a diff names the section that drifted.
- The `.m` text is large, so an emit slice prints one row per C declaration or method and the harness joins them, keeping each emit slice's own output small enough for the line-based diff.
- `linux?` gates in the emit (the record box shim, the dispatch property attributes, the `??` temporary) are read from an argv flag, and the parity runs force `os.name` to `Mac OS X` on the oracle side, so the port reproduces the macOS bytes that `cards.m` carries.

## Chapter estimate

At the pace of chapters 33 to 37 (one slice or one slice and an extension per chapter), the remaining work is slices 43 and 44 (two chapters), the lowering slices 45 to 55 (about eleven chapters, the expression and atom slices likely two each), and the emit slices (about four chapters), then the cards.m assembly and the byte comparison (one chapter). That is about eighteen chapters to a `cards.m` diff, with the expression lowering the largest risk.
