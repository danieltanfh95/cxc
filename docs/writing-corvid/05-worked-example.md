# Worked example

Tasks of different kinds (print, buffer, discard) flow into a worker
atom that dispatches on the kind.

## The whole program

```elixir
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

```text
hello
queued-1
queued-2
4
```

## Walking the shape

### Enum first

```elixir
def TaskKind:
    case print_it
    case buffer_it
    case discard_it
```

The supported kinds of behavior. Each new task kind adds a case
here and (usually) an arm on `Worker`. Discard is the fall-through,
so most future kinds only need an arm when they do something
distinct from discard.

### Record second

```elixir
def Task:
    kind:    TaskKind
    payload: String
```

Task carries **effect as data**. `Task.kind` routes Worker dispatch.

Compare to the anti-pattern shape:

```elixir
def Task:
    do_it: TaskAction
```

`TaskAction` would be some function-typed field. Corvid doesn't have
function types as first-class values on records for exactly this
reason. A data record is a poor container for behavior. Route
behavior through kind + guarded dispatch.

### Atom third

```elixir
def Worker:
    buffered: List of String = []
    processed: Int = 0

    total_processed :- processed

    -> idle
```

`total_processed` exposes `processed` under a public-facing name.

The `buffered: List of String = []` is a container field with a
default of empty list. FP-only mutation applies. Reach for
`.append`, never `.add`.

### Transitions

```elixir
idle <: submit(t: Task) -> idle
    |: t.kind == TaskKind.print_it
    then:
        print(t.payload)
        processed => processed + 1
```

Three arms match `submit` on different kinds. cxc tries
each in source order, and the first whose guard is true fires. Fall-through
(the third arm, unguarded) catches every kind not matched above:
`discard_it` today, any new kind tomorrow.

- `print(t.payload)`: a side effect. Runs during body execution.
- `processed => processed + 1`: a staged update. Commits at end of body.

The `=>` reads pre-image throughout the body. Written this way:

```elixir
then:
    processed => processed + 1
    print(processed)      # prints OLD processed
```

the print shows the pre-image value, not the incremented one. To
print the new value: `print(processed + 1)`, or hoist to a `let`.

### Flush arm

```elixir
idle <: flush -> idle
    then: buffered.for_each(s => print(s))
```

cxc has no `for x in xs:` loop, so iteration is method dispatch on
the container.

### Main

```elixir
def main:
    worker: Worker = Worker()

    -> running
        then:
            |> worker.submit(t: Task(kind: TaskKind.print_it,   payload: "hello"))
            ...
            print(worker.total_processed)
```

Observations are safe to read from outside because they compose purely.

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
- **Multi-arm expression dispatch (`||:`).** `preview :- ||: buffered.count == 0 : "empty" ||: else : buffered.at(0) ?? ""`.

## Try it yourself

The verified source is at [task-queue.cxc](task-queue.cxc). Compile
and run per the pipeline in the main [README.md](../../README.md),
pointing at `docs/writing-corvid/task-queue.cxc` instead of
`examples/hello.cxc`, then modify:

1. Add a new `TaskKind` case (`count_it`) that increments a
   counter but doesn't print or buffer. What arm structure does that
   need? (Answer: a new guarded arm. The fall-through would also
   work but would lump `count_it` in with `discard_it`.)
2. Move `Task` from a record to an atom (give it phases). What
   breaks? (Answer: everything. A task-as-atom would need its own
   mailbox. The `submit(t:)` payload type changes semantics from
   "a passed value" to "a reference to a live actor.")
3. Add a second Worker. Do the two share `buffered`? (Answer: no.
   Each atom has its own state.)

