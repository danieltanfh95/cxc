# 10-char-ident lex segfault — compiler bug surfaced by chapter 14

Design note. Chapter 15 isolation + chapter 16 compiler fix of the
chapter-14-flagged segfault that bit the slice-33 standalone
tokenize and the parse-layer bootstrap when any input ident was
≥ 10 chars of mixed-content.

## Status

- **Chapter 15.** Isolated, probable cause identified. Workaround at
  the port layer: ≤ 9-char idents in corpus.
- **Chapter 16.** Compiler fix landed in
  `compiler/src/cxc/lower/list_container.clj`. Workaround lifted;
  natural identifiers restored in parse-layer bootstrap corpus.
  Cards.m behavioral output byte-identical before and after.

## Minimal reproduction

```cxc
def Token:
    kind:  String!
    value: String!

def main:
    use args:    Args
    use console: Console

    tokens:   List of Token = []
    out_buf:  String!       = ""

    cur :- tokens.at(0) ?? Token(kind: "x", value: "")

    -> start
        then:
            tokens => tokens.append(Token(kind: "ident", value: args.get_or(1, "")))
            self :> step

    -> emit
        then:
            out_buf => cur.kind + cur.value
            self :> finish

    -> end
        then: console.write(out_buf)

    start <: step -> emit
    emit  <: finish -> end
```

Run with `./bin "aaaaaaaaaa"` → prints `identaaaaaaaaaa`, exit 0.
Run with `./bin "bbbbbbbbbb"` → segfault, exit 139.

Shortest reproducer in `/tmp/sf5.cxc` on the investigation host.

## Observed character-content dependency

| Argument       | Length | Exit  | Notes                                      |
| -------------- | ------ | ----- | ------------------------------------------ |
| `aaaaaaaaaa`   | 10     | 0     | works                                      |
| `cccccccccc`   | 10     | 0     | works                                      |
| `dddddddddd`   | 10     | 0     | works                                      |
| `bbbbbbbbbb`   | 10     | 139   | crashes                                    |
| `XXXXXXXXXX`   | 10     | 139   | crashes                                    |
| `abcdefghij`   | 10     | 139   | crashes                                    |
| `aaaaaaaaa`    | 9      | 0     | works at 9 chars for any content           |

The trigger is not pure length — it is char-content-dependent.
The 6-bit NSTaggedPointerString encoding carries at most 11 chars
when every char is in the frequency-ranked 6-bit charset (which
includes `a`, `c`, `d` among others). Chars outside that set
force a 7-bit or 8-bit encoding whose char cap is lower, so the
NSString allocator switches from tagged-pointer to heap-allocated
NSString at exactly the point the content exceeds the charset-
dependent cap.

## Bisection

The chapter-11 slice-31 (`lex_tokenize_full.cxc`) on the same
input list does NOT segfault. The chapter-13 slice-33 ALREADY
had the bug (verified by compiling the ch13-tree src and
running). Chapter 14's lex expansion did not introduce the bug;
it was latent since chapter 13.

Minimal differences between slice-31 (works) and slice-33 (crashes):

- slice-33 emits via a separate `emitting` phase that reads tokens
  back from the accumulator list and concatenates record-fields.
- slice-31 emits as it scans; the token value never crosses a
  List-of-Token boundary before being written.

The crash isolates to `out_buf => cur.kind + cur.value` where:

- `cur` is an atom-level observation `cur :- tokens.at(idx) ?? Token(…)`
- Both `.kind` and `.value` are String! fields of the Token record
- The staged `=>` assigns the concat result to a String! atom field

Replacing EITHER side with a String literal lifts the crash:

- `out_buf => cur.kind + "literal"` → OK
- `out_buf => "literal" + cur.value` → OK
- `out_buf => cur.value` → OK (no concat)

The crash only fires when both operands of `+` are record-field
reads via the SAME observation, and the resulting string content
forces heap-allocated NSString encoding.

## Probable cause

Record struct copies through `List.at` returning a value-typed
Token do not retain the heap-allocated String field properly.
When the token is read twice (once for `.kind`, once for `.value`)
via re-evaluation of the observation, the second read dereferences
a stale pointer whose underlying NSString was already released by
the first read's ARC balance.

For taggedPointer NSStrings this does not fire because the
pointer IS the value — no indirection to a release-reclaimed heap
object. For heap-allocated NSStrings the indirection is live,
and the double-field-read on the same observation evaluation
scheme either over-releases or reads post-dealloc memory.

This matches cxc's value-type record contract (spec §3.2): records
copy by value, but the compiler emit for record fields that are
String! must retain each field across copies. The bug is on the
emit layer, not on the value-type contract itself.

## Fix landed in chapter 16

Fix location: `compiler/src/cxc/lower/list_container.clj`
function `list-at-body`. The old emit was:

```c
Token __v;
[(NSValue *)[_elements objectAtIndex:i] getValue:&__v size:sizeof(Token)];
return (_Cxc_Optional_Token){.present = 1, .value = __v};
```

The `__v` declaration under ARC gives `Token`'s `__strong NSString *`
fields auto-nil-init + auto-release-at-scope-end. The `getValue:`
memcpy writes raw bytes into the field pointers, bypassing ARC's
retain. At scope end, ARC releases pointers it never retained,
over-releasing the actual NSString refcount. For
NSTaggedPointerString values (short mixed-content strings) release
is a no-op (tagged pointers aren't refcounted) so the bug is
latent. For heap-allocated NSString (longer or non-charset
content) the over-release eventually frees the backing allocation
and the next field access dereferences stale memory.

The new emit synthesizes a shadow struct with
`__unsafe_unretained` fields matching the record's reference-typed
fields, memcpy's the bytes INTO the shadow, then does field-by-
field ARC-visible stores into a zero-initialized Token:

```c
struct __cxc_raw {
    NSString * __unsafe_unretained name;
    _Cxc_Optional_CardType type;
    int64_t attack;
    …
} __raw;
[nsvalue getValue:&__raw size:sizeof(__raw)];
Card __v = {0};
__v.name = __raw.name;
__v.type = __raw.type;
__v.attack = __raw.attack;
…
return (_Cxc_Optional_Card){.present = 1, .value = __v};
```

Shadow's `__unsafe_unretained` fields = no ARC destructor on
scope exit. The field-by-field assigns to `__v` are ARC-visible
stores (because `Card`'s fields default to `__strong`), so each
reference-typed field is properly retained. Primitive fields
pass through unchanged.

The shadow is only emitted when the record has at least one
reference-typed field (field `:c-type` contains `*`). Records
of pure-primitive fields keep the simpler old emit, byte-
identical to pre-fix.

## Scope impact

Cards.m (the baseline-shaded parity target) now contains the new
emit for its `List of Card` reader because `Card` has an
`NSString * name` field. The generated `.m` sha changes
(`d28f973d…` → `738b928d…`), but cards' observable output is
byte-identical before and after the fix. The sha change is
inside the chapter-16 directive's explicit scope: "the fix is
EXPECTED to change the identified pattern (which is container-
returned record-field reads). Cards is one such program."

Workarounds tried, none worked at the port layer:

- `let k = cur.kind; let v = cur.value; k + v` — still crashes.
  Let-locals are bound against the observation which still
  evaluates against the live Token.
- Split the two reads across two transitions (one assigns
  `out_buf => cur.kind`, next assigns `out_buf => out_buf +
  cur.value`) — still crashes. The second transition's `cur.value`
  read exhibits the same crash.
- Materialize the Token into an atom-level field (`stored: Token
  = Token(…)` then `stored => tokens.at(0)` then
  `out_buf => stored.kind + stored.value`) — did not compile at
  the `Token = Token(…)` default-value syntax; not pursued.

The ONLY confirmed port-layer workaround is to avoid the
record-field-read-twice-of-heap-NSString pattern entirely. The
corpus-length cap (≤ 9 chars of mixed content) achieves this by
keeping every scanned ident value inside NSTaggedPointerString's
inline storage.

## Chapter 14 workaround lifetime

Chapter 16 landed the compiler fix; the ≤ 9-char-ident workaround
is now LIFTED. Parse-layer bootstrap corpus restored to natural
identifiers (`DefaultsInt`, `DefaultsStr`, …). Any future slice
can use natural identifiers.
