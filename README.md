# cxc

An atom-substrate language for the Apple ecosystem. Emits Objective-C; clang produces the binary.

**Public alpha.** Bootstrap compiler works end-to-end for the atom-substrate surface described in the v1.0 spec. Design is stable; the surface will shift as feedback lands. Do not depend on it for production.

License: [MIT](LICENSE).

## Try it

Requires macOS with `clang` (from Xcode command-line tools) and a Clojure CLI (`brew install clojure/tools/clojure`).

```
git clone https://github.com/danieltanfh/corvid
cd corvid/compiler
clojure -M:test           # 1053 tests, ~2 minutes. all green means the pipeline works.
```

To compile and run one program:

```
clojure -M -e "
(require '[cxc.build :as b])
(let [src (slurp \"examples/hello.cxc\")
      out \"/tmp/cxc-hello\"]
  (clojure.java.io/make-parents (str out \"/x\"))
  (let [r (b/compile-source-to-file src \"hello\" out)]
    (when (zero? (:status r))
      (println (:stdout (b/run-binary (:bin-path r)))))))
"
```

Prints `hello, world`. Every `.cxc` under [compiler/examples/](compiler/examples/) is compiled and run by the test suite — start there for what the language currently expresses. [compiler/examples/README.md](compiler/examples/README.md) is the coverage index.

## Writing Corvid

New to the language? Read [docs/writing-corvid/](docs/writing-corvid/).

- [Your first atom](docs/writing-corvid/01-your-first-atom.md) — a counter, end to end.
- [Language tour](docs/writing-corvid/02-language-tour.md) — syntax quick-reference.
- [Design patterns](docs/writing-corvid/03-design-patterns.md) — shapes worth reaching for.
- [Common mistakes](docs/writing-corvid/04-common-mistakes.md) — what to avoid.
- [Worked example](docs/writing-corvid/05-worked-example.md) — a task queue, annotated.

If you're an AI agent writing Corvid, the guide is also packaged as
a Claude Code skill at [.claude/skills/cxc-authoring/SKILL.md](.claude/skills/cxc-authoring/SKILL.md).

## Where things live

- [docs/writing-corvid/](docs/writing-corvid/) — authoring guide for humans and agents.
- [design/spec/spec-cxc-v1.0.md](design/spec/spec-cxc-v1.0.md) — the language spec (normative).
- [design/PATTERNS.md](design/PATTERNS.md) — onboarding by translation from other languages.
- [compiler/](compiler/) — the bootstrap compiler in Clojure. Reads `.cxc`, emits `.m`, drives clang.
- [compiler/resources/prelude/](compiler/resources/prelude/) — the stdlib capabilities (FileSystem, Http, Json, Subprocess, EnvVars, EnvMut, Random, Time, Hash, Console, Args, Clock).
- [design/examples/](design/examples/) — aspirational programs beyond current compiler coverage.

