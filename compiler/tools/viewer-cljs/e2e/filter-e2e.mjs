// Coverage item 8: FILTERS panel — kind toggle + msg substring repaints topology.
import { CARDS, launch, openViewer, PASS, FAIL, assert, sleep } from './_harness.mjs';

const NAME = 'filter-e2e';
(async () => {
  const { browser, page } = await launch();
  try {
    await openViewer(page, CARDS);
    const filters = page.locator('#sidebar details.filters');
    if (!(await filters.evaluate(d => d.open))) await filters.locator('summary').click();
    await sleep(200);

    const evtCount = () => page.evaluate(() => {
      const stats = Array.from(document.querySelectorAll('#sidebar details.overview li, #sidebar .stat-row, #sidebar .stats-row'));
      for (const row of stats) {
        const t = row.textContent.trim();
        const m = t.match(/^events\s+(\d+)/);
        if (m) return parseInt(m[1]);
      }
      // Fallback: parse from the header "— N events".
      const h = document.querySelector('header .trace-name')?.textContent || '';
      const m = h.match(/(\d+)\s+events/);
      return m ? parseInt(m[1]) : -1;
    });
    const edgeCount = () => page.locator('#shape-container svg .shape-edge').count();

    const n0 = await evtCount(), e0 = await edgeCount();
    assert(n0 > 0 && e0 > 0, `baseline event+edge counts > 0, got n=${n0} e=${e0}`);

    // Toggle "send" kind off.
    await page.locator('#sidebar label[data-kind="send"] input').click();
    await sleep(400);
    const e1 = await edgeCount();
    assert(e1 <= e0, `kind filter should not increase edges, got ${e0}→${e1}`);
    // Re-enable it.
    await page.locator('#sidebar label[data-kind="send"] input').click();
    await sleep(300);

    // Msg-contains filter: unique CARDS substring in the cards trace.
    await page.locator('#sidebar .filter-input').fill('CARDS');
    await sleep(500);
    const e2 = await edgeCount();
    assert(e2 < e0, `msg filter narrows edges, got ${e0}→${e2}`);
    await page.locator('#sidebar .filter-input').fill('');
    await sleep(300);
    const e3 = await edgeCount();
    assert(e3 === e0, `clearing msg filter restores edges, got ${e0}→${e3}`);

    PASS(NAME, `edges ${e0}→kind-off ${e1}→msg-filter ${e2}→reset ${e3}`);
  } catch (e) {
    FAIL(NAME, e.message);
  } finally {
    await browser.close();
  }
})();
