// Coverage item 12: ?focus=atom:X opens detail; ?focus=range:A-B sets range focus state.
import { BASE, launch, PASS, FAIL, assert, sleep } from './_harness.mjs';

const NAME = 'url-focus-e2e';
(async () => {
  const { browser, page } = await launch();
  try {
    // atom focus: load sample-01 and ensure Counter detail opens.
    await page.goto(`${BASE}/trace-viewer.html?trace=sample-01-single-atom.trace.jsonl&shape=sample-01-single-atom.shape.json&focus=atom:Counter`, { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('#shape-container svg .shape-block', { timeout: 10000 });
    await sleep(600);
    const detailName = await page.evaluate(() => document.querySelector('#atom-detail .atom-detail-name')?.textContent?.trim() || '');
    assert(/counter/i.test(detailName), `focus=atom:Counter opens Counter detail, got "${detailName}"`);

    // range focus: load cards with ?focus=range:0-5 and assert the focus state carries the range.
    const { browser: b2, page: p2 } = await launch();
    await p2.goto(`${BASE}/trace-viewer.html?trace=cards.trace.jsonl&shape=cards.shape.json&focus=range:0-5`, { waitUntil: 'domcontentloaded' });
    await p2.waitForSelector('#shape-container svg .shape-block');
    await sleep(500);
    const focusLabel = await p2.evaluate(() => {
      const f = window.cxcViewerV2?.getState()?.focus;
      if (!f || f.kind !== 'range') return '';
      const t = f.target || {};
      return `${t.trace_seq_start}-${t.trace_seq_end}`;
    });
    assert(focusLabel === '0-5', `focus=range:0-5 sets range focus state, got "${focusLabel}"`);
    await b2.close();

    PASS(NAME, `atom focus → "${detailName}", range focus → "${focusLabel}"`);
  } catch (e) {
    FAIL(NAME, e.message);
  } finally {
    await browser.close();
  }
})();
