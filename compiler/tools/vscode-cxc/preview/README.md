# Preview

`preview.png` is a shiki-rendered proxy of what the extension paints in
VSCode (dark-plus theme). Shiki uses the same Oniguruma regex engine
VSCode uses, so if this looks right the extension will too.

## Regenerate

```sh
cd /tmp && mkdir -p cxc-preview && cd cxc-preview
npm init -y > /dev/null && npm install shiki
cp <repo>/compiler/tools/vscode-cxc/preview/render.mjs .
node render.mjs
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
  --headless=new --disable-gpu --no-first-run --hide-scrollbars \
  --force-device-scale-factor=2 --window-size=1400,2200 \
  --screenshot=out.png file:///tmp/cxc-preview/out.html
```

Copy `out.png` back over `preview.png` when it improves.

## What to eyeball

- Message-dispatch glyphs (`<:` `->` `:>` `|>` `=>` `:-` `||:` `|:`) all
  purple, same family as `def`/`then`/`case`.
- Phase names on arm headers (`alive`, `deploying`, `drawing`) blue.
- Message names on arm headers (`begin_draw`, `try_place`) yellow.
- Observation definitions (`lane(idx: Int) :- ...`) yellow head, purple
  `:-`, colored body.
- Enum access (`Ability.deploy_dmg_hero_2`) teal type + light-blue member.
- `self` distinct from ordinary idents.
- Comments dim green (least-attention grabbing).
