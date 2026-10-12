// Phase C: observation stubs appear in scope views for obs read across the
// scope boundary. Also verifies the legend tally cells render.
//
// Post scope-zoom reframe (ddd6bfe...): stubs are gone from tier-1 by design.
// Only draw when an atom in the current scope's subtree exports an obs that
// is read by at least one atom OUTSIDE the subtree. Legend tally still
// reports every declared observation.
import { CARDS, launch, openViewer, PASS, FAIL, assert, sleep } from './_harness.mjs';

const NAME = 'phase-c-classification-e2e';

async function enterScope(page, atomName) {
  const clicked = await page.evaluate((nm) => {
    const b = Array.from(document.querySelectorAll('.shape-block'))
      .find(e => (e.dataset.nodeName || '') === nm);
    if (!b) return false;
    b.dispatchEvent(new MouseEvent('click', {bubbles:true, cancelable:true, view:window}));
    return true;
  }, atomName);
  if (!clicked) throw new Error(`atom "${atomName}" not found on tier-1`);
  await sleep(500);
}

(async () => {
  try {
    const { browser, page } = await launch({ viewport: { width: 1600, height: 1100 } });
    await openViewer(page, CARDS);

    // Open the legend (collapsed by default) then enable observations layer.
    const reopen = await page.$('#shape-legend-reopen');
    if (reopen) await reopen.click();
    await page.waitForSelector('#shape-legend', { timeout: 2000 });
    await page.evaluate(() => {
      const legend = document.querySelector('#shape-legend');
      const row = Array.from(legend.querySelectorAll('.legend-layer-row'))
        .find(r => r.textContent.toLowerCase().includes('observation'));
      if (!row) throw new Error('observations row not found');
      const cb = row.querySelector('input[type=checkbox]');
      if (!cb.checked) cb.click();
    });
    await sleep(400);

    const tally = await page.$$eval('.legend-obs-tally-cell', nodes =>
      nodes.map(n => n.textContent.trim())
    );
    assert(tally.length === 3, `tally cells: expected 3, got ${tally.length} (${tally.join(' | ')})`);
    const ext = tally.find(s => s.includes('external'));
    const intl = tally.find(s => s.includes('internal'));
    const un = tally.find(s => s.includes('unused'));
    assert(ext && intl && un, `tally labels present: ${tally.join(' | ')}`);

    const nE = parseInt(ext.match(/\d+/)[0], 10);
    const nI = parseInt(intl.match(/\d+/)[0], 10);
    const nU = parseInt(un.match(/\d+/)[0], 10);
    assert(nE + nI + nU === 23,
      `cards.cxc total observations: expected 23, tally sum ${nE + nI + nU}`);

    // Tier-1: no stubs by design.
    const tier1Stubs = await page.$$eval('.cxc-obs-stub-label', ls => ls.length);
    assert(tier1Stubs === 0, `tier-1 should have no obs stubs, got ${tier1Stubs}`);

    // Drill into Lane scope. Lane exports obs read from outside its subtree
    // (Player reads Lane.side). The reader (Player) renders as a ghost pill in
    // Lane scope, so the obs edge draws between Lane and the ghost — the stub
    // is suppressed to avoid double-rendering the same read.
    await enterScope(page, 'Lane');
    const laneObs = await page.evaluate(() => {
      const stubs = Array.from(document.querySelectorAll('.cxc-obs-stub-label')).length;
      const edges = Array.from(document.querySelectorAll('.shape-obs-edge-label')).map(l => l.textContent.trim());
      return { stubs, edges };
    });
    assert(laneObs.stubs === 0,
      `Lane scope suppresses stubs when the reader is a visible ghost; got ${laneObs.stubs}`);
    const hasSide = laneObs.edges.some(l => l.toLowerCase().startsWith('side'));
    assert(hasSide,
      `Lane scope shows cross-boundary obs as an edge, got labels ${JSON.stringify(laneObs.edges)}`);

    // Legend swatches for the three classes also render.
    const swatches = await page.$$eval(
      '.legend-obs-external, .legend-obs-internal, .legend-obs-unused',
      els => els.length);
    assert(swatches === 3, `three legend swatches present, got ${swatches}`);

    await browser.close();
    PASS(NAME, `tier-1 stubs=0, Lane scope stubs=0 (edge-rendered: ${laneObs.edges.join('|')}), tally ${nE}/${nI}/${nU}`);
  } catch (e) {
    FAIL(NAME, e.message);
  }
})();
