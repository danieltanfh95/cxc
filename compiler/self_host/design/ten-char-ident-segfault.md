# 10-char-ident lex segfault — compiler bug surfaced by chapter 14

Design note. Chapter 15 isolation of the chapter-14-flagged segfault
that bit the slice-33 standalone tokenize and the parse-layer
bootstrap when any input ident was ≥ 10 chars of mixed-content.

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

## Fix path

Not land here. Chapter-cadence rule applies — compiler landings
go in dedicated chapters. Surface this to the main thread as a
chapter-16 compiler landing candidate.

Compiler-side investigation starts in `compiler/src/cxc/lower.clj`
or `compiler/src/cxc/emit.clj`. The emit code for record-field
read on String! fields should be checked for ARC retain balance
when the record is a value-typed temporary returned from a
collection access (`List.at`). Hypothesis to verify: the generated
code for `tokens.at(idx)` returns a Token struct by value, and
accessing `.kind` extracts the String! pointer without a retain,
leaving the field underretained after the first access and
dangling for the second.

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

The ≤ 9-char-ident corpus discipline stays in force across all
slices (parse-layer bootstrap, slice 33) until the compiler fix
lands in chapter 16. Any downstream slice that would exercise
longer idents picks between (a) accepting the length cap, (b)
restructuring to avoid the double-field-read pattern.
