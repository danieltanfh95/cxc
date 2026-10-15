# Writing Corvid

Corvid ("cxc" on disk) is a small language: atoms, phases, messages,
records, observations. Once the vocabulary lands, the whole thing fits
in your head. What takes longer is the taste of knowing which shape a
piece of behaviour wants, and which shapes to reach for last.

## Read in this order

1. [01-your-first-atom.md](01-your-first-atom.md): a counter atom, end to end.
2. [02-language-tour.md](02-language-tour.md): syntax quick-reference. Skim, don't memorize.
3. [03-design-patterns.md](03-design-patterns.md): architectural patterns worth reaching for.
4. [04-common-mistakes.md](04-common-mistakes.md): the shapes that fight the compiler and the substrate.
5. [05-worked-example.md](05-worked-example.md): a small program end to end, annotated.

## Companion docs

- [docs/spec/spec-cxc-v1.0.md](../../docs/spec/spec-cxc-v1.0.md): the normative language spec.
- [docs/PATTERNS.md](../../docs/PATTERNS.md): "coming from Swift/Rust/Python, how do I write X here?" A translation guide, complementary to this one.
- [compiler/examples/](../../compiler/examples/): every `.cxc` file there compiles and runs under the test suite. Working code cxc currently accepts.
- [docs/examples/](../../docs/examples/): aspirational programs that depend on unlanded shapes (plugins, L7 bundles). See each example's `README.md` for the blocking landing.

## The one thing to internalise first

**Data before code.** Corvid's grammar is small enough that a design
without a data shape has nowhere to hide. Before you write a
transition, name the record. Before you write a guard, name the
field. A wrong shape shows up as wrong-looking code every time, and
a right shape lets the code write itself.
