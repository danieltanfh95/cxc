# AST shape records for the self-host port

Design note, chapter 7 landing. Documents the data contract between
the Clojure parse layer (producer) and the cxc consumer ports for
the type-AST shapes `:type-ref` / `:type-nonnil` / `:type-named-tuple`
/ `:type-of`.

## Target shape

The Clojure parse layer emits type AST nodes as Clojure maps keyed by
`:node`. Four shapes are in scope for the self-host port:

| Clojure node          | Fields on the map                               |
| --------------------- | ----------------------------------------------- |
| `:type-ref`           | `:name` (String)                                |
| `:type-nonnil`        | `:inner` (another type-node)                    |
| `:type-named-tuple`   | `:fields` (vec of `{:name :type}` maps)         |
| `:type-of`            | `:outer` (String), `:inner` (type-node)         |

Nesting is unbounded in principle. A named-tuple field's `:type` can
itself be a named-tuple; a `:type-of` can wrap a `:type-nonnil` of a
`:type-ref`.

## cxc expressivity audit (2026-10-04)

Direct Corvid-shaped translations as a sealed sum type exercise two
compiler gaps that bite at the C emit boundary:

1. **Self-referential enum payload.** `def TypeNode: case ref(name:
   String!), case nonnil(inner: TypeNode!)` parses through cxc but
   clang rejects it with `unknown type name 'TypeNode'`. The struct
   typedef emits before any forward `@class` or forward `typedef`
   for `TypeNode`, so the embedded `inner` field has no type in
   scope.

2. **Mutual recursion via List-of-record.** `def NamedField: name:
   String!, type: TypeNode!` + `def TypeNode: case named_tuple(fields:
   List of NamedField)` emits before `_Cxc_ListOfNamedField` is
   declared, so clang fails with `unknown type name
   '_Cxc_ListOfNamedField'`. Related to [[corvid-container-scan-
   includes-records]] — the container-scan pass walks record envs but
   doesn't resolve the emit order for a type that CONTAINS the
   container WHILE the container's element type references back to
   it.

Non-recursive records with primitive and flat-record fields work
today (verified with a `TypeRef(name: String!)` smoke test).

## Decision

Use the flat tagged-record encoding at v1. One cxc record per AST
occurrence, discriminated by a `kind: TypeKind!` enum tag (no
payload — the data lives alongside the tag in the same record).
Nested structure gets flattened to a string field under a
self-describing serialization that the walker parses on demand.

### cxc shape

```
def TypeKind:
    case ref
    case nonnil
    case named_tuple
    case parametric

def TypeNode:
    kind:        TypeKind!
    # ref / parametric: the primitive or outer-container name
    name:        String!

    # nonnil-of-ref: the inner primitive's name (empty when the
    # inner is itself a nested shape — fall through to `inner_ser`)
    nn_inner:    String!

    # parametric: arity + each inner serialized to the string
    # encoding below
    of_arity:    Int!

    # named_tuple fields, serialized as `<f1>|<ser1>;<f2>|<ser2>;…`
    # where `<seri>` is `<name>` for :type-ref, `Nn_<name>` for the
    # non-nil-of-ref short form, or the full-serialized form for
    # deeper shapes. Width unbounded.
    tuple_fields_ser: String!

    # parametric inner args, same serialization as a tuple field's
    # `<ser>` joined by `;` (one entry when arity 1, N entries when
    # arity N).
    of_inners_ser:    String!
```

The case name `of` cannot be used — `of` is a reserved cxc keyword
(parser-level, surfaces as `parse error: expected :ident got :kw
"of"`). The enum case is spelled `parametric`; the wire-level
output tag stays `of:` to mirror the Clojure `:type-of` node's
convention.

Every field is non-nil, so the compile-time storage is predictable
and the port's observations/guards can read every field without a
`??` fallback. Fields unused for a given tag stay at their zero
value (empty string or 0).

### Serialization

String encoding uses the same shape chapter 5's named_tuple slice
already proved out:

```
<T>         → ser(:type-ref{:name T}) = T
<T>         → ser(:type-nonnil{:inner {:name T}}) = Nn_<T>
<T>         → ser(:type-named-tuple{:fields [{:name f :type Ti}…]})
             = Nt<count>_<f1>_<ser(T1)>_<f2>_<ser(T2)>_…
<T>         → ser(:type-of{:outer O :inner single-T})
             = Of1_<O>_<ser(T)>
<T>         → ser(:type-of{:outer O :inner :type-tuple{:types [T1 T2…]}})
             = Of<N>_<O>_<ser(T1)>_<ser(T2)>_…
```

This matches `cxc.named-tuple/mangle-type` exactly (compiler/src/
cxc/named_tuple.clj L64-L135) and so is already proven byte-stable at
the compiler level. The cxc walker ports can roundtrip through this
encoding without needing a Clojure-side mirror AST.

## Producer: Clojure parse layer

`compiler/src/cxc/parse.clj` emits the four shapes directly as maps:

```clojure
{:node :type-ref        :name T}
{:node :type-nonnil     :inner <type-node>}
{:node :type-named-tuple :fields [{:name f :type <type-node>} ...]}
{:node :type-of         :outer O :inner <type-node-or-type-tuple>}
```

No change to the producer. The self-host port's oracle shim takes
a serialized string on stdin, parses it into the matching map
shape, and feeds the Clojure consumer function.

## Consumer: cxc port

The port program takes a serialized string, parses it into a
`TypeNode` (or a list of them for walker-shaped functions), applies
the operation, and emits the result as a serialized string.

Pattern-match on `.kind` through `|:` guards or `<: tag` arms:

```
let t: TypeNode = ...
let result: String! = ||: t.kind.is_ref         : handle_ref(t)
                      ||: t.kind.is_nonnil      : handle_nonnil(t)
                      ||: t.kind.is_named_tuple : handle_named_tuple(t)
                      ||: t.kind.is_of          : handle_of(t)
                      ||: else                  : "UNKNOWN"
```

Each handler reads only the fields the shape uses, keeps the
others untouched.

## When to upgrade to a true sealed sum type

The flat tagged-record is a bridge, not an endpoint. Once the
compiler emits forward `@class` + forward struct typedefs for
mutually-recursive record / container pairs ([[corvid-container-scan-
includes-records]] follow-on), the self-host AST should migrate to a
proper sealed sum:

```
def TypeNode:
    case ref(name: String!)
    case nonnil(inner: TypeNode!)
    case named_tuple(fields: List of NamedField)
    case of(outer: String!, inner: TypeNode!)

def NamedField:
    name: String!
    type: TypeNode!
```

The migration is mechanical. Every consumer swaps `.kind` dispatch
for `<: tag` destructuring; the serialization layer stays as the
cross-boundary wire format for argv-driven slices.

## Scope implications for chapter-7 slices

- Slice 18 (`resolve-name-to-module`): no AST — pure four-way
  lookup. Lands independent of this design.
- Slice 19 (`module-scoped-ref-names`): walks a bounded node list,
  returns a string set. No AST records needed; the input is a flat
  list of `(name, kind)` pairs via argv.
- Slice 20 (`scan-string` with escapes): no AST — a lex-layer port.
- Slice 21 (`build-symbol-table`): consumes decls that CONTAIN
  type nodes, but the symbol table itself maps `name → module` with
  no type information. Lands without needing the AST records.
- Slice 22+ (`collect-shapes` / `emit-typedefs`): REQUIRE the full
  type-AST walker. Lands against the serialized encoding until the
  sealed-sum migration.
