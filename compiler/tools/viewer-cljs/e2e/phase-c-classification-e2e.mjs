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
    // (Player reads Lane.side etc), so stubs should appear on Lane's self-pill.
    await enterScope(page, 'Lane');
    const laneStubs = await page.$$eval('.cxc-obs-stub-label', labels => {
      const r = { external: 0, internal: 0, unused: 0, bare: 0 };
      for (const l of labels) {
        if (l.classList.contains('obs-external')) r.external++;
        else if (l.classList.contains('obs-internal')) r.internal++;
        else if (l.classList.contains('obs-unused')) r.unused++;
        else r.bare++;
      }
      return r;
    });
    // Cross-boundary classification: any obs with a reader outside Lane is
    // "external" from Lane's perspective. The sidecar's obs-class-index may
    // still mark some stubs :unused if no obs_calls row names them.
    const laneTotal = laneStubs.external + laneStubs.internal + laneStubs.unused + laneStubs.bare;
    assert(laneTotal > 0, `Lane scope renders at least one cross-boundary obs stub, got ${JSON.stringify(laneStubs)}`);
    assert(laneStubs.bare === 0, `every rendered stub is classified, got ${JSON.stringify(laneStubs)}`);

    // Legend swatches for the three classes also render.
    const swatches = await page.$$eval(
      '.legend-obs-external, .legend-obs-internal, .legend-obs-unused',
      els => els.length);
    assert(swatches === 3, `three legend swatches present, got ${swatches}`);

    await browser.close();
    PASS(NAME, `tier-1 stubs=0, Lane scope stubs=${JSON.stringify(laneStubs)}, tally ${nE}/${nI}/${nU}`);
  } catch (e) {
    FAIL(NAME, e.message);
  }
})();
