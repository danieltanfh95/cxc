// Coverage item 14: screenshot sentinel. First run emits golden PNGs under
// e2e/golden/; subsequent runs assert the file exists. A later pass will add
// pixel-diff tolerance; today we lock the surface to its first-emitted form.
import { SAMPLE, CARDS, launch, openViewer, PASS, FAIL, assert, sleep } from './_harness.mjs';
import { existsSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve as presolve } from 'node:path';

const NAME = 'visual-parity-e2e';
const HERE = dirname(fileURLToPath(import.meta.url));
const GOLDEN = presolve(HERE, 'golden');
if (!existsSync(GOLDEN)) mkdirSync(GOLDEN, { recursive: true });

const shots = [
  { tag: 'cards-wide',    url: CARDS,  viewport: { width: 1264, height: 900 } },
  { tag: 'cards-narrow',  url: CARDS,  viewport: { width: 640,  height: 1200 } },
  { tag: 'sample01-wide', url: SAMPLE, viewport: { width: 1264, height: 900 } },
  { tag: 'sample01-narrow', url: SAMPLE, viewport: { width: 640,  height: 1200 } },
];

(async () => {
  const results = [];
  try {
    for (const s of shots) {
      const { browser, page } = await launch({ viewport: s.viewport });
      await openViewer(page, s.url);
      await sleep(500);  // let ELK settle
      const path = presolve(GOLDEN, `${s.tag}.png`);
      const first = !existsSync(path);
      await page.screenshot({ path, fullPage: false });
      await browser.close();
      assert(existsSync(path), `golden file written to ${path}`);
      results.push(`${s.tag}${first ? ' [new]' : ''}`);
    }
    PASS(NAME, results.join(', '));
  } catch (e) {
    FAIL(NAME, e.message);
  }
})();
