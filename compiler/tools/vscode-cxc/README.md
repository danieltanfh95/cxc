# cxc-syntax

TextMate grammar for Corvid `.cxc` source files. Local extension, not published.

## Install

The extension folder lives here (`compiler/tools/vscode-cxc/`). It is symlinked into `~/.vscode/extensions/local.cxc-syntax-0.1.0/`:

    ln -s "$(pwd)/compiler/tools/vscode-cxc" \
      ~/.vscode/extensions/local.cxc-syntax-0.1.0

After creating or updating the symlink, reload the VSCode window (`Cmd+Shift+P` → "Developer: Reload Window").

## What it highlights

- Comments, strings, numbers, `nil` / `true` / `false`.
- `def <Atom>(...)`, `def main:`, `def plugin:`.
- Phase declarations `-> phase`, `-> end`, `-> self`.
- Transition arm headers `<phase> <: <msg>(...) -> <phase>` and wildcard `*` variants.
- Observation declarations `name :- expr` and `name(params) :- expr`, `alias name :- expr`.
- Field declarations `name: Type` and typed params.
- Enum cases (`case foo`) and enum access (`Ability.deploy_dmg_hero_2`).
- Message-dispatch glyphs: `<:` `:>` `|>` `:-` `||:` `|:` `=>` `->` `??`.
- Method calls `.name(...)`.

## Iterate

Edit files in this folder, save, reload window. Grammar changes are picked up on reload.

## Not yet

- Semantic highlighting of atom names, self-field vs local ident, message-selector uniqueness.
- Interpolated strings.
- Tree-sitter grammar (higher fidelity, needs a real parser build).
