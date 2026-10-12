// Scope-zoom tier-1 main-click intent + expandable-atom routing (round1-opus
// review, check 9).
//
// Tier-1 flattens main as a peer pill. Clicking main at tier-1 goes straight
// to its lifecycle view (scope-of-main would just redraw the same atoms
// wrapped in a frame). Clicking an expandable NON-root atom (Player) must
// still route to :scope so sub-atoms become visible.
//
// Historical note: the original "main-blank" bug (shape-only vs trace-mode
// node-id mismatch in :expandable-atoms, fixed in 383e1a2) caused Player
// clicks to fall through to :phases unintentionally. The Player assertion
// below still protects against ownership staleness for non-main expandable
// atoms.
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

    // Main click: intentional path is tier-1 → main's lifecycle (crumb
    // ends with "lifecycle", phase graph on canvas).
    const mainToLifecycle = (after.breadcrumb || '').endsWith('lifecycle')
                             && after.phaseCount > 0;
    writeFileSync(`${OUT}/09c-main-click.json`, JSON.stringify({ tier1, after, mainToLifecycle }, null, 2));
    assert(mainToLifecycle,
      `main click should land on lifecycle at tier-1, got breadcrumb="${after.breadcrumb}" ` +
      `blocks=[${after.blocks.join(',')}] phaseNodes=${after.phaseCount}`);

    // Esc from main lifecycle returns to root (zoomed-atom nil, no breadcrumb).
    await page.keyboard.press('Escape');
    await sleep(400);
    const afterEsc = await readDom(page);
    assert(!afterEsc.breadcrumb,
      `Esc from main lifecycle returns to tier-1 (no crumb), got "${afterEsc.breadcrumb}"`);

    // Expandable non-root atom (Player) still routes to :scope — guards the
    // ownership-staleness regression that this test originally covered.
    const playerClicked = await page.evaluate(() => {
      const b = Array.from(document.querySelectorAll('.shape-block'))
        .find(e => (e.dataset.nodeName || '') === 'Player');
      if (!b) return false;
      b.dispatchEvent(new MouseEvent('click', {bubbles:true, cancelable:true, view:window}));
      return true;
    });
    assert(playerClicked, 'Player pill not found at tier-1');
    await sleep(600);
    const playerAfter = await readDom(page);
    writeFileSync(`${OUT}/09d-player-click.json`, JSON.stringify(playerAfter, null, 2));
    assert(!(playerAfter.breadcrumb || '').endsWith('lifecycle'),
      `Player click should enter scope, not lifecycle. breadcrumb="${playerAfter.breadcrumb}"`);
    assert(playerAfter.blocks.includes('Player'),
      `Player scope keeps Player on canvas, got blocks=[${playerAfter.blocks.join(',')}]`);

    PASS(NAME, `main → lifecycle (crumb "${after.breadcrumb}"); Player → scope (crumb "${playerAfter.breadcrumb}")`);
  } catch (e) {
    FAIL(NAME, e.message);
  } finally {
    await browser.close();
  }
})();
