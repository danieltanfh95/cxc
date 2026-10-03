# Parse-layer bootstrap — terminal subset design read

Design note, chapter 11. Scopes the parse-layer port the self-host
needs to walk real `:node`-keyed AST instead of the chapter-7 flat
tagged-record bridge. Hand-written recursive descent, no instaparse
analogue in cxc stdlib. Chapter 12 landing per the chapter-10/11
split discipline.

## Target

The Clojure parse layer at `compiler/src/cxc/parse.clj` is 4271 LOC,
covering the full cxc surface — def-atom bodies with transitions,
observations, dispatch arms, variant patterns, expression hierarchy
with precedence, type expressions, imports, plugins, extend blocks,
and 25+ error-recovery diagnostics. Porting the whole thing is a
multi-chapter effort.

**Terminal subset for chapter 12.** The minimum shape the slices
touching AST (`collect-shapes`, `emit-typedefs`, `enrich-with-
resolves-to`) consume. Scope:

- Top-level `def X:` headers only. No body-level transitions,
  observations, dispatch arms, or let-expressions.
- Field declarations inside the body (`name: Type` / `name: Type =
  default`). No fields with modifiers (`weak`, `unowned` deferred).
- Type expressions. `T`, `T!`, `List of T`, `Map of (K, V)`, `Set
  of T`. No named-tuples, no `T?` (universally banned anyway), no
  `Ptr of T` (FFI deferred).
- `case <name>` and `case <name>(payload...)` markers inside enum
  bodies (chapter-9 sub-case-2 landing covers container payloads;
  chapter-10 sub-case-1 landing rejects direct self-reference at
  parse — the port's parse MUST also reject).
- `module <name>` header and `from X import Y[, Z]` statements. No
  `import X as Y`, no bare `import X` (deferred).
- Diagnostics. One `:parse-error` node per error with `:line`,
  `:col`, `:message`. No multi-token recovery — stop at first
  error.

Out of scope. Expression parsing, transitions, observations,
dispatch arms, variant patterns, `extend` blocks, `def plugin:`,
`class function`, invariant chains, protocol signatures,
observations with alias chains.

## Token-to-node contract

Input. The flat token vector from the chapter-11 full-tokenize port
(slice 31), shape per lex.clj's docstring:

```
[:kind value line col]
  :kind    :ident, :int, :float, :string, :char, :kw, :glyph,
           :punct, :indent, :dedent, :newline, :eof
```

Output. A flat vector of top-level `:node`-keyed maps:

| `:node`           | Fields on the map                                      |
| ----------------- | ------------------------------------------------------ |
| `:module`         | `:name` (string)                                       |
| `:from-import`    | `:from` (string), `:names` (vec of `{:name :as}`)      |
| `:def-record`     | `:name`, `:fields` (vec of `{:name :type :default}`)   |
| `:def-atom`       | `:name`, `:fields`, `:ctor-params` (same shape)        |
| `:enum`           | `:name`, `:cases` (vec of string), `:case-entries`     |
| `:parse-error`    | `:line`, `:col`, `:message`                            |

Type nodes:

| `:node`           | Fields on the map                                      |
| ----------------- | ------------------------------------------------------ |
| `:type-ref`       | `:name` (string)                                       |
| `:type-nonnil`    | `:inner` (type-node)                                   |
| `:type-of`        | `:outer` (string), `:inner` (type-node or type-tuple)  |
| `:type-tuple`     | `:types` (vec of type-nodes)                           |

Kind inference. The port chooses between `:def-record`, `:def-atom`,
and `:enum` by structural detection on the body — matches
`def-atom->enum` / `def-atom->protocol` / `def-atom->record` /
`def-atom->class` dispatch in the Clojure parser:

- Body contains `case <ident>` markers → `:enum`
- Body contains `use <cap>:` or ctor params `()` → `:def-atom`
- Otherwise → `:def-record`

For the terminal subset the kind detection is simpler: the three
inputs the port exercises are records, enum shells (bare + payload
cases), and atom headers (fields only — no transitions / methods /
observations yet). Full atom-body parsing is chapter 13 or later.

## Error reporting shape

One `:parse-error` node per surface. Fields:

```
{:node :parse-error
 :line int        ;; 1-based line of the first unrecognised token
 :col int         ;; 1-based column
 :message string} ;; human-readable, matches Clojure fail!'s format
```

Match the Clojure `fail!` call shape for byte-level parity on the
error message. Oracle compares `(get err :message)` on both sides.

## Scope reads before chapter 12 lands

Before writing the parse port, verify.

1. **Token stream equivalence.** The chapter-11 full-tokenize port
   must produce byte-identical token streams to Clojure's
   `cxc.lex/tokenize` on the test corpus. Verify with a dedicated
   parity harness.
2. **Which existing port slices actually need parse-layer AST?**
   The chapter-7 flat tagged-record bridge carries three slices
   (collect-shapes, emit-typedefs). If the parse port's terminal
   subset doesn't produce the shapes those slices consume, the
   port is wasted work until a later landing. Verify by reading
   the Clojure `collect-shapes` input contract against the terminal
   subset's output shape.
3. **Composition slice candidates post-parse.** Any slice walking
   `:node`-keyed AST (e.g. `cxc.resolve/decls-in-file`) becomes a
   candidate for direct Clojure-fn invocation in the oracle, with
   the port driving the same walk on real AST. Enumerate at design-
   read time.

## Deferral triggers

The parse-layer port stays deferred to chapter 12 if any of these
hold at the end of chapter 11:

- Full tokenize deep port (slice 31) doesn't achieve byte parity
  with Clojure `cxc.lex/tokenize`.
- Terminal subset reveals a shape the existing slices don't
  consume (parse-layer-port-for-no-reader).
- The expression hierarchy or variant-pattern parsing creeps into
  the scope — those are chapter 13+ work.

## Chapter 11 landing deliverables

- This design note.
- Full lex::tokenize deep port (slice 31) with byte-parity harness.
- Updated README chapter 11 progress + idiom additions.

Chapter 12 picks up with the parse-layer bootstrap landing. Scope
subject to re-read after chapter 11 closes.
