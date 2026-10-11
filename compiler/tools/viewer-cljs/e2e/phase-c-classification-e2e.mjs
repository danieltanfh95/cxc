// Phase C: observation stubs are classified external / internal / unused.
// Verifies the CSS class per stub, the legend tally counts, and that the three
// swatches render.
import { CARDS, launch, openViewer, PASS, FAIL, assert, sleep } from './_harness.mjs';

const NAME = 'phase-c-classification-e2e';

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

    const counts = await page.$$eval('.cxc-obs-stub-label', labels => {
      const r = { external: 0, internal: 0, unused: 0, bare: 0 };
      for (const l of labels) {
        if (l.classList.contains('obs-external')) r.external++;
        else if (l.classList.contains('obs-internal')) r.internal++;
        else if (l.classList.contains('obs-unused')) r.unused++;
        else r.bare++;
      }
      return r;
    });
    assert(counts.external > 0, `external stubs > 0 (got ${JSON.stringify(counts)})`);
    assert(counts.internal > 0, `internal stubs > 0 (got ${JSON.stringify(counts)})`);
    assert(counts.bare === 0,   `every stub classified (got ${JSON.stringify(counts)})`);

    // Tally sum matches total declared observations on cards.cxc (23 as of
    // Phase C). The split 3/20/0 is what today's walker produces; a stricter
    // test would freeze those numbers, but corpus changes shift them.
    const nE = parseInt(ext.match(/\d+/)[0], 10);
    const nI = parseInt(intl.match(/\d+/)[0], 10);
    const nU = parseInt(un.match(/\d+/)[0], 10);
    assert(nE === counts.external && nI === counts.internal && nU === counts.unused,
           `tally agrees with stub counts: tally ${nE}/${nI}/${nU} vs stubs ${counts.external}/${counts.internal}/${counts.unused}`);
    assert(nE + nI + nU === 23,
           `cards.cxc total observations: expected 23, tally sum ${nE + nI + nU}`);

    // Legend swatches for the three classes also render.
    const swatches = await page.$$eval(
      '.legend-obs-external, .legend-obs-internal, .legend-obs-unused',
      els => els.length);
    assert(swatches === 3, `three legend swatches present, got ${swatches}`);

    await browser.close();
    PASS(NAME, `stubs ${counts.external}/${counts.internal}/${counts.unused}, tally ${nE}/${nI}/${nU}`);
  } catch (e) {
    FAIL(NAME, e.message);
  }
})();
