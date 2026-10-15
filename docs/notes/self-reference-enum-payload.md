# Self-reference enum payload, compiler landing design read

Self-reference enum payloads account for the remaining half of the
recursive-user-types emit gap documented in
`docs/notes/ast-shape-records.md`. Sub-case 2 (mutual recursion
through `List of NamedField` and friends) landed in the chapter 9
landing. Sub-case 1 is self-reference via `case nonnil(inner:
Self!)`, where the enum case's payload field is the enum itself.

## Target shape

The failing case, lifted from the chapter-7 design note.

```elixir
def TypeNode:
    case ref(name: String!)
    case nonnil(inner: TypeNode!)
```

Parse accepts this shape. Lower builds the enum-env entry. Emit
produces an outer struct of the shape:

```objc
typedef enum { TypeNode_Tag_ref, TypeNode_Tag_nonnil } TypeNode_Tag;

typedef struct {
    NSString * name;
} TypeNode_Payload_ref;

typedef struct {
    TypeNode inner;       // ← struct contains itself. Illegal in C.
} TypeNode_Payload_nonnil;

typedef struct {
    TypeNode_Tag tag;
    union {
        TypeNode_Payload_ref ref;
        TypeNode_Payload_nonnil nonnil;
    } data;
} TypeNode;
```

Clang rejects the `TypeNode inner` line with `field has incomplete
type 'TypeNode'`. The struct can't contain itself by value, C
requires every value-member's type to be complete at the point of
declaration, and `TypeNode` isn't complete until the outer struct's
closing brace.

The gap is NOT emit ordering (chapter 9's sub-case 2 fix). No
reordering of typedef blocks helps, the inner struct and the outer
struct are intrinsically forward-referential.

## Design options

### (a) Pointer-to-self

Lower self-reference payload fields to `TypeNode *` (pointer),
allocate the inner value on the heap at construction, dereference
at access.

```text
typedef struct {
    TypeNode * inner;
} TypeNode_Payload_nonnil;
```

Clang accepts: forward-declared struct's pointer type is complete
even when the struct itself isn't.

Semantics shift. Self-reference payloads become reference-shaped
(shared state via pointer) rather than value-shaped (copy at
assignment). Three downstream call-sites need updating.

**Construction**. `.nonnil(inner: tn)` currently emits
`(TypeNode){.tag = TypeNode_Tag_nonnil, .data.nonnil = { .inner = tn }}`.
Under pointer semantics it emits
`({ TypeNode * __p = (TypeNode *)malloc(sizeof(TypeNode)); *__p = tn; (TypeNode){.tag = TypeNode_Tag_nonnil, .data.nonnil = { .inner = __p }}; })`.
Lifetime: the heap allocation is never freed. For an immutable
value-type enum this is a leak but not a correctness issue, the
inner value's lifetime is logically tied to the outer value's
lifetime, and C has no destructor hook to collect it.

**Copy**. Shallow copy inherits the pointer. Both copies share the
inner value. Mutation of `*inner` through one copy visible from
the other. This CONTRADICTS the value-type contract users have for
enums under spec §3.2. Deep copy (malloc + copy inner recursively)
preserves value semantics but needs a per-enum emit of a `_Cxc_<Enum>_copy`
function that chains through all payload cases including recursive
ones. Terminating recursion is manageable (payload-free cases halt
descent).

**Compare**. Structural equality for enum values compares tags +
payloads. The tag compare is scalar. Payload compare recurses by
case. For pointer-typed payload fields, compare must dereference
and recurse on the pointed-to value (not the pointer). Need a
per-enum emit of a `_Cxc_<Enum>_equals` function. Same
termination as copy.

### (b) Separated payload struct typedef with forward typedef

Would require `TypeNode inner` to be legal with a forward typedef.
It isn't, forward typedef makes the TYPE NAME legal but doesn't
make the VALUE storage legal. C requires the struct to be complete
at the point where a value field of that type is declared.

Option (b) collapses into option (a).

### (c) Opt out at v1.0

Forbid self-reference enum payloads at the parse layer with a
clean diagnostic. Users model recursive data via a workaround:

- `case nonnil(inner_name: String!)` plus a resolve layer.
- A dedicated boxed record `TypeNodeBox { body: TypeNode! }` and
  enum carrying `TypeNodeBox` instead of `TypeNode` directly. The
  record itself can carry a value field, but a List/Array of
  record is a reference type by MVP shape, so a self-reference
  via a List-container of a wrapper is legal (sub-case 2 covers
  it).
- The flat tagged-record encoding the chapter-7 design note
  specifies, which is used by the self-host port today.

Users who need value-shaped self-reference work with boxed-record
layers. cxc stays simple.

## Comparison matrix

| Dimension                     | (a) Pointer-to-self             | (c) Opt out                   |
| ----------------------------- | ------------------------------- | ----------------------------- |
| Compiler change scope         | 4 call sites, 2 emit helpers    | 1 parse diagnostic            |
| Semantic contract impact      | Value → reference               | None (shape rejected)         |
| Lifetime                      | Leak unless freed               | n/a                           |
| Copy semantics                | Breaks value-type invariant     | n/a                           |
| Compare semantics             | Needs per-enum fn               | n/a                           |
| cards.m parity impact         | Low (no self-ref in cards.cxc)  | Zero                          |
| Self-host port impact         | Replaces flat-tagged-record     | Flat-tagged-record stays      |
| v1.0 spec stance (§3.2)       | VIOLATES value-type contract    | STABILIZES by exclusion       |

## Decision

**Option (c), opt out at v1.0.**

1. **Value-type contract matters more than this shape.** Spec §3.2
   defines records and enums as value types. Users rely on
   `let a = enum.foo; let b = a; mutate(a)` leaving b unchanged.
   Option (a) breaks this contract for every self-ref payload. The
   fix (deep copy via emitted helper) adds compiler surface area
   and complicates equality, Corvid's Clojure-philosophy stance
   (stability, don't break user code) argues for keeping the
   contract.

2. **The self-host port doesn't need it.** Chapter 7's flat
   tagged-record encoding at `ast-shape-records.md` works
   today for the AST shape the port needs. Sub-case 2 landed in
   chapter 9 and covered the List-of-record half of the recursive-
   type gap. The remaining half (self-ref) was the chapter-7
   discovery. The chapter-7 note itself prescribed the flat
   encoding as the bridge. Keeping that bridge is the lower-risk
   path to a working self-host.

3. **The workaround set is non-empty.** Users who genuinely need
   recursive value types model via boxed-record layers (option c
   paragraph 3). The pattern is explicit and visible. The
   semantics stay value-shaped end-to-end.

## Landing scope

Parse-layer diagnostic firing on an enum payload whose `:type` is
a `:type-ref` or `:type-nonnil` or `:type-nullable` that resolves
to the enum itself. Message points at the three workarounds. One
test in `cxc.parse-test` covering the direct self-ref form.

The sub-case-1 landing stays small and discipline-preserving. It
turns the current silent-emit-failure into an explicit v1.0 scope
boundary.

If a future landing turns records-and-enums into reference types or
adds a general heap-boxing primitive, the diagnostic retires at that
time.
