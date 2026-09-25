# Writing Corvid

An opinionated authoring guide. Not a spec; for the normative surface, see
[design/spec/spec-cxc-v1.0.md](../../design/spec/spec-cxc-v1.0.md).

Corvid ("cxc" on disk) is a small language: atoms, phases, messages,
records, observations. Once the vocabulary lands, the whole thing fits
in your head. What takes longer is the *taste*: knowing which shape a
piece of behaviour wants, and which shapes to reach for last. This
guide is that taste, written down.

## Read in this order

1. [01-your-first-atom.md](01-your-first-atom.md): a counter atom, end to end.
2. [02-language-tour.md](02-language-tour.md): syntax quick-reference. Skim, don't memorize.
3. [03-design-patterns.md](03-design-patterns.md): architectural patterns worth reaching for.
4. [04-common-mistakes.md](04-common-mistakes.md): the shapes that fight the compiler and the substrate.
5. [05-worked-example.md](05-worked-example.md): a small program end to end, annotated.

For quick lookup after the first read, the tour and the mistakes file
are the ones you'll come back to.

## Companion docs

- [design/spec/spec-cxc-v1.0.md](../../design/spec/spec-cxc-v1.0.md): the normative language spec. When this guide and the spec disagree, the spec wins.
- [design/PATTERNS.md](../../design/PATTERNS.md): "coming from Swift/Rust/Python, how do I write X here?" A translation guide, complementary to this one.
- [compiler/examples/](../../compiler/examples/): every `.cxc` file there compiles and runs under the test suite. Working code the compiler currently accepts.
- [design/examples/](../../design/examples/): aspirational programs that may exceed current compiler coverage.

## The one thing to internalise first

**Data before code.** Corvid's grammar is small enough that a design
without a data shape has nowhere to hide. Before you write a
transition, name the record. Before you write a guard, name the
field. When the shape is wrong the code always looks wrong; when the
shape is right the code writes itself.

If a doc in this guide feels dense, that's usually because it's
naming shapes rather than describing behaviour. Read it that way.
