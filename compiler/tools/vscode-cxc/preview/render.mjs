// Render cards.cxc through shiki with the local cxc tmLanguage grammar
// and a VSCode-shipped dark theme.
import { readFileSync, writeFileSync } from 'node:fs';
import { createHighlighter } from 'shiki';

const REPO = '/Users/danieltan/Projects/g-daniel/language/corvid';
const CARDS = '/Users/danieltan/Projects/g-daniel/cards/dota2/cxc/cards.cxc';
const GRAMMAR = JSON.parse(readFileSync(
  `${REPO}/compiler/tools/vscode-cxc/syntaxes/cxc.tmLanguage.json`, 'utf8'));
const SRC = readFileSync(CARDS, 'utf8').split('\n');

// Two slices, side-by-side: enum/record/atom top-level shape on the
// left, arm bodies with glyphs and observations on the right.
const shape  = SRC.slice(0, 90).join('\n');
const bodies = SRC.slice(125, 230).join('\n');

const hi = await createHighlighter({
  themes: ['dark-plus'],
  langs: [{ ...GRAMMAR, name: 'cxc', scopeName: 'source.cxc' }],
});
const shapeH  = hi.codeToHtml(shape,  { lang: 'cxc', theme: 'dark-plus' });
const bodiesH = hi.codeToHtml(bodies, { lang: 'cxc', theme: 'dark-plus' });

writeFileSync('/tmp/cxc-preview/out.html', `<!doctype html><meta charset="utf-8">
<style>
  html, body { margin: 0; background: #1e1e1e; color: #d4d4d4; font-family: 'JetBrains Mono', 'Fira Code', Menlo, monospace; }
  .wrap { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; padding: 16px; }
  .col h2 { font-size: 12px; letter-spacing: 0.06em; text-transform: uppercase; color: #8a8a8a; margin: 0 0 8px; font-family: system-ui, -apple-system, sans-serif; }
  pre.shiki { margin: 0; padding: 20px 24px; font-size: 14px; line-height: 1.6; border-radius: 8px; overflow: visible; }
</style>
<div class="wrap">
  <div class="col"><h2>enums · records · atom-header · lifecycle</h2>${shapeH}</div>
  <div class="col"><h2>arm bodies · glyphs · observations · aliases</h2>${bodiesH}</div>
</div>`);
console.log('wrote /tmp/cxc-preview/out.html');
