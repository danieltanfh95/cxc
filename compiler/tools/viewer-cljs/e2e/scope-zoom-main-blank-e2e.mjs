// Scope-zoom "main blank" bug regression (round1-opus review, check 9).
//
// Clicking the main atom's self-pill at root scope must open scope-of-main,
// not the terminal lifecycle view.
//
// When the bug is live (shape-only vs trace-mode node-id mismatch in
// :expandable-atoms), the click falls through the painter-context cond at
// app.cljs:712 to the leaf :else arm, which sets :zoom-mode :phases.
// Breadcrumb then reads "← root / main / lifecycle" and the shape canvas is
// replaced by the phase graph.
//
// Root cause: compiler/tools/viewer-cljs/src/cxc_viewer/app.cljs:2215-2235
// (artifact-successor) recomputes ownership only on shape-changed?. When
// shape loads before events, ownership runs with events=[] → shape-only
// branch → :expandable-atoms contains "main" instead of trace-mode "CxcMain".
import { BASE, launch, openViewer, PASS, FAIL, assert, sleep } from './_harness.mjs';
import { writeFileSync, mkdirSync } from 'node:fs';

const NAME = 'scope-zoom-main-blank-e2e';
const OUT = '/tmp/scope-zoom-review/round1-opus';
const CARDS_URL = `${BASE}/trace-viewer.html?trace=cards.trace.jsonl&shape=cards.shape.json`;

async function readDom(page) {
  return await page.evaluate(() => {
    const svg = document.querySelector('#shape-container svg');
    const blocks = svg ? Array.from(svg.querySelectorAll('.shape-block')).map(b => b.dataset.nodeId).filter(Boolean) : [];
    const phases = svg ? svg.querySelectorAll('.phase-node').length : 0;
    const bc = document.querySelector('#fractal-breadcrumb');
    return { blocks, phaseCount: phases, breadcrumb: bc ? bc.textContent.trim() : null };
  });
}

(async () => {
  mkdirSync(OUT, { recursive: true });
  const { browser, page } = await launch({ viewport: { width: 1600, height: 1000 }, deviceScaleFactor: 1 });
  try {
    await openViewer(page, CARDS_URL);
    await sleep(1200);

    const tier1 = await readDom(page);
    writeFileSync(`${OUT}/09-tier1-baseline-dom.json`, JSON.stringify(tier1, null, 2));
    await page.screenshot({ path: `${OUT}/09a-tier1-baseline.png` });

    // Click CxcMain self-pill.
    const clickOk = await page.evaluate(() => {
      const svg = document.querySelector('#shape-container svg');
      const blocks = Array.from(svg.querySelectorAll('.shape-block'));
      const self = blocks.find(b => b.dataset.nodeId === 'CxcMain' && !b.classList.contains('container'));
      if (!self) return false;
      self.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
      return true;
    });
    assert(clickOk, 'CxcMain self-pill not found at tier-1');
    // Allow enough time for the scope OR lifecycle render to settle.
    await sleep(2500);
    const after = await readDom(page);
    writeFileSync(`${OUT}/09b-main-scope-dom.json`, JSON.stringify(after, null, 2));
    await page.screenshot({ path: `${OUT}/09b-main-scope.png` });

    // The intended outcome: breadcrumb "← root / main" (scope mode), the shape
    // canvas keeps atom blocks (Deck/Lane/Player/Market stay sealed inside the
    // CxcMain compound).
    //
    // The buggy outcome: breadcrumb "← root / main / lifecycle" (phases mode),
    // the shape canvas is replaced by phase-node elements.
    const inLifecycle = (after.breadcrumb || '').includes('lifecycle');
    const lostChildren = ['Deck','Lane','Player','Market'].filter(c => !after.blocks.includes(c));

    writeFileSync(`${OUT}/09c-tier1-vs-main-scope.json`, JSON.stringify({ tier1, after, inLifecycle, lostChildren }, null, 2));

    if (inLifecycle || lostChildren.length > 0) {
      FAIL(NAME,
        `MAIN-BLANK REPRODUCED. breadcrumb="${after.breadcrumb}" ` +
        `blocks=[${after.blocks.join(',')}] phaseNodes=${after.phaseCount} ` +
        `lostChildren=[${lostChildren.join(',')}]. ` +
        `Root cause: app.cljs:2215-2235 only re-owns on shape-changed?, so ` +
        `expandable-atoms keeps shape-only id "main" while live canvas uses ` +
        `trace-mode "CxcMain". selectNode (app.cljs:712) misses expandable? ` +
        `and switches to :phases.`);
      return;
    }
    PASS(NAME, `scope-of-main shows blocks=[${after.blocks.join(',')}], crumb="${after.breadcrumb}"`);
  } catch (e) {
    FAIL(NAME, e.message);
  } finally {
    await browser.close();
  }
})();
