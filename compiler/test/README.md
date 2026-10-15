# Compiler tests

Run the complete suite from `compiler/` with `clj -X:test` or
`clojure -M:test`. Both use the existing Cognitect test-runner alias.

## Trace viewer annotation browser tests

`cxc.trace-viewer-annotation-test` covers loading annotations and the draft
create, edit, delete, and submit flow with Etaoin, plus annotation CLI
tests. Run this namespace from `compiler/`:

```sh
clj -X:test :nses '[cxc.trace-viewer-annotation-test]'
# Equivalent main-style invocation:
clojure -M:test -n cxc.trace-viewer-annotation-test
```

Install chromedriver on PATH, matching your Chrome version. On macOS:

```sh
brew install chromedriver
xattr -d com.apple.quarantine "$(command -v chromedriver)"
```

Chrome.app at `/Applications/Google Chrome.app` is selected when
present, otherwise Etaoin uses its normal Chrome discovery. Tests run
headless unless `HEADED=1` is set. Browser cases print `SKIP` with
setup instructions when chromedriver is missing, and CLI cases still
run. Install the driver to exercise the full browser flow.

Each browser test starts its own CLI server process on a port selected by
`ServerSocket(0)` and waits for `GET /annotations` before opening Chrome.
Tests use separate temporary fixture copies and remove them in teardown. The fixtures are tracked snapshots. Local `compiler/research/`
files are never modified or required.

CLI tests use `cxc` from PATH, or `clojure -M -m cxc.core` from `compiler/`
when absent. The current in-repo `bin/cxc` wrapper also uses this fallback:
it rewrites nested subcommands and flag values into paths. Fixing that
launcher is outside these tests' scope.
