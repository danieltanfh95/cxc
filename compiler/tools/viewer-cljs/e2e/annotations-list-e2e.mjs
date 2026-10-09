// Coverage item 4: annotations panel header, section grouping, table toggle.
// Legacy groups by status via section headings (OPEN N / ADDRESSED N) — no
// panel chip row. Status filtering by checkbox lives in the table view.
import { SAMPLE, launch, openViewer, PASS, FAIL, assert, sleep } from './_harness.mjs';

const NAME = 'annotations-list-e2e';
(async () => {
  const { browser, page } = await launch();
  try {
    await openViewer(page, SAMPLE);
    await page.waitForSelector('.annotations .ann-row');

    const headerCount = (await page.locator('.annotations .ann-count').first().innerText()).trim();
    assert(/\(7\s*total\)/i.test(headerCount), `header shows "(7 TOTAL)", got "${headerCount}"`);

    const rows0 = await page.locator('.annotations .ann-row').count();
    assert(rows0 === 7, `all 7 annotations visible initially, got ${rows0}`);

    const panelChips = await page.locator('.annotations > .ann-filters').count();
    assert(panelChips === 0, `panel has no chip row (legacy parity), got ${panelChips}`);

    const headings = await page.locator('.ann-status-heading').allInnerTexts();
    assert(headings.length === 2 && /OPEN\s+5/.test(headings[0]) && /ADDRESSED\s+2/.test(headings[1]),
      `two uppercase headings, got ${JSON.stringify(headings)}`);

    await page.locator('.annotations .ann-table-toggle').click();
    await sleep(300);
    const tableHeaders = await page.locator('table thead th button, table thead th').allInnerTexts();
    assert(tableHeaders.length >= 5, `table has at least 5 header cells, got ${tableHeaders.length}: ${JSON.stringify(tableHeaders)}`);
    const tableRows = await page.locator('table tbody tr').count();
    assert(tableRows >= 7, `table lists all 7 annotations, got ${tableRows}`);

    PASS(NAME, `7 rows, no panel chips, headings ${headings.join('|')}, table rows ${tableRows}`);
  } catch (e) {
    FAIL(NAME, e.message);
  } finally {
    await browser.close();
  }
})();
