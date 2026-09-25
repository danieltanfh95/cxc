# Worked example

A small program end to end, annotated. This one models a **task
queue**: tasks with different kinds (print, buffer, discard) flow
into a worker atom that dispatches on the kind.

The example is deliberately small enough to fit in one screen, and
deliberately big enough to exercise records, enums, atoms, phases,
guarded arms, observations, sub-atoms, sync and async sends,
container methods, and the effect-as-data pattern from
[03-design-patterns.md](03-design-patterns.md).

## The whole program

```
def TaskKind:
    case print_it
    case buffer_it
    case discard_it

def Task:
    kind:    TaskKind
    payload: String

def Worker:
    buffered: List of String = []
    processed: Int = 0

    total_processed :- processed

    -> idle

    idle <: submit(t: Task) -> idle
        |: t.kind == TaskKind.print_it
        then:
            print(t.payload)
            processed => processed + 1

    idle <: submit(t: Task) -> idle
        |: t.kind == TaskKind.buffer_it
        then:
            buffered => buffered.append(t.payload)
            processed => processed + 1

    # Fall-through arm: vanilla case for TaskKind.discard_it and
    # any future kind that lands here without its own arm.
    idle <: submit(t: Task) -> idle
        then: processed => processed + 1

    idle <: flush -> idle
        then: buffered.for_each(s => print(s))

def main:
    worker: Worker = Worker()

    -> running
        then:
            |> worker.submit(t: Task(kind: TaskKind.print_it,   payload: "hello"))
            |> worker.submit(t: Task(kind: TaskKind.buffer_it,  payload: "queued-1"))
            |> worker.submit(t: Task(kind: TaskKind.buffer_it,  payload: "queued-2"))
            |> worker.submit(t: Task(kind: TaskKind.discard_it, payload: "gone"))
            |> worker.flush()
            print(worker.total_processed)
```

Running it prints:

```
hello
queued-1
queued-2
4
```

## Walking the shape

### Enum first

```
def TaskKind:
    case print_it
    case buffer_it
    case discard_it
```

The kinds of behavior we support. When a new task kind is needed,
we'll add a case here and (usually) an arm on `Worker`. Discard is
the fall-through, so most future kinds will only need an arm if
they do something distinct from discard.

### Record second

```
def Task:
    kind:    TaskKind
    payload: String
```

A task is a data value. Not an atom: no phases, no mailbox. It
carries the kind (which routes dispatch) and the payload (which the
behavior consumes). This is the **effect-as-data** shape: the task
type is a lightweight envelope, and its `.kind` field is what the
worker dispatches on.

Compare to the shape we could have written:

```
# NOT what we did (anti-pattern).
def Task:
    do_it: TaskAction
```

...where `TaskAction` would be some function-typed field. Corvid
doesn't have function types as first-class values on records for
exactly this reason: a data record is a poor container for behavior.
Route behavior through kind + guarded dispatch.

### Atom third

```
def Worker:
    buffered: List of String = []
    processed: Int = 0

    total_processed :- processed

    -> idle
```

`Worker` has two data fields (`buffered` and `processed`), one
observation (`total_processed`), and an initial phase (`idle`). The
observation exposes `processed` under a public-facing name.

The `buffered: List of String = []` is a container field with a
default of empty list. Note the FP-only mutation: we'll `.append`,
never `.add`.

### Transitions

```
idle <: submit(t: Task) -> idle
    |: t.kind == TaskKind.print_it
    then:
        print(t.payload)
        processed => processed + 1
```

Read: while in `idle`, on `submit(t:)` where `t.kind` is `print_it`,
transition back to `idle`, then print the payload and stage a
processed++.

Three arms match `submit` on different kinds. The compiler tries
each in source order; the first whose guard is true fires. Fall-through
(the third arm, unguarded) catches every kind not matched above:
`discard_it` today, any new kind tomorrow.

Note the two `then:` statements on the first arm:
- `print(t.payload)`: a side effect. Runs during body execution.
- `processed => processed + 1`: a staged update. Commits at end of body.

The `=>` reads pre-image throughout the body. If we wrote:

```
then:
    processed => processed + 1
    print(processed)      # prints OLD processed
```

the print would show the value *before* the increment. To print the
new: `print(processed + 1)`, or hoist to a `let`.

### Flush arm

```
idle <: flush -> idle
    then: buffered.for_each(s => print(s))
```

A separate message on the same source phase. Iterates the buffered
list via `.for_each` (there's no `for x in xs:` loop; iteration is
method dispatch on the container).

### Main

```
def main:
    worker: Worker = Worker()

    -> running
        then:
            |> worker.submit(t: Task(kind: TaskKind.print_it,   payload: "hello"))
            ...
            print(worker.total_processed)
```

`main` is itself an atom. `worker: Worker = Worker()` declares a
sub-atom field, self-constructed in main's init. Each `|>
worker.submit(...)` is a synchronous send: main blocks on the arm
completing before continuing.

The final `print(worker.total_processed)` reads worker's observation
directly. Observations are safe to read from outside; they compose
purely.

## What this example exercises

| Feature | Where |
|---|---|
| Enum | `def TaskKind:` |
| Record | `def Task:` |
| Atom + initial phase | `def Worker:` + `-> idle` |
| Field with default | `buffered: List of String = []` |
| Observation | `total_processed :- processed` |
| Guarded arms on same `<:` | three `submit` arms |
| Fall-through arm | third `submit` arm |
| `then:` with staging + side effect | first `submit` arm |
| Container FP mutation | `buffered => buffered.append(...)` |
| Container iteration | `buffered.for_each(...)` |
| Sub-atom in `main` | `worker: Worker = Worker()` |
| Sync send `|>` | every `worker.submit(...)` |
| Sub-atom observation read | `worker.total_processed` |
| Effect-as-data | `Task.kind` + guarded arms |

## What it doesn't exercise

- **Async send / self-send.** The task queue is synchronous. For an async example see [compiler/examples/stream-typed-payload.cxc](../../compiler/examples/stream-typed-payload.cxc).
- **Capabilities (`use`).** See [compiler/examples/cross-atom-send.cxc](../../compiler/examples/cross-atom-send.cxc) or [compiler/examples/use-cap.cxc](../../compiler/examples/use-cap.cxc).
- **Typed raise + recovery.** See the prelude cap examples in [compiler/resources/prelude/](../../compiler/resources/prelude/).
- **Multi-arm expression dispatch (`||:`).** Adding a computed field would show it: `preview :- ||: buffered.count == 0 : "empty" ||: else : buffered.at(0) ?? ""`.

## Try it yourself

The verified source is at [task-queue.cxc](task-queue.cxc). Compile
and run per the pipeline in the main
[README.md](../../README.md). Point at
`docs/writing-corvid/task-queue.cxc` instead of
`examples/hello.cxc`. Then modify:

1. Add a new `TaskKind` case (`count_it`) that increments a
   counter but doesn't print or buffer. What arm structure does that
   need? (Answer: a new guarded arm; the fall-through would also
   work but would lump `count_it` in with `discard_it`.)
2. Move `Task` from a record to an atom (give it phases). What
   breaks? (Answer: everything. A task-as-atom would need its own
   mailbox, and the `submit(t:)` payload type changes semantics from
   "a value we pass" to "a reference to a live actor.")
3. Add a second Worker. Do the two share `buffered`? (Answer: no.
   Each atom has its own state.)

These are the kind of shape-questions Corvid is designed to make
easy to answer. When the shape is right, the modification is small.
