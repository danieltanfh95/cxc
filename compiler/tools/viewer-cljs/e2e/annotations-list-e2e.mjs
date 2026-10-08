// Coverage item 4: annotations panel + status chip filtering + table toggle.
// Also exercises the ANN-GAP-* fixes: 7 TOTAL label, non-zero-only chips,
// boxed status headings, 7 clean rows.
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

    // ANN-GAP-01: only non-zero chips plus "all".
    const chips = await page.locator('.annotations .ann-filter').allInnerTexts();
    assert(chips.length === 3, `3 non-zero chips (all + 2 non-zero statuses), got ${chips.length}: ${JSON.stringify(chips)}`);
    assert(chips[0] === 'all', `first chip is "all", got "${chips[0]}"`);
    assert(chips.some(c => /addressed\s*\(2\)/.test(c)), 'addressed(2) chip present');
    assert(chips.some(c => /open\s*\(5\)/.test(c)), 'open(5) chip present');

    // ANN-GAP-04: boxed status headings "OPEN 5", "ADDRESSED 2".
    const headings = await page.locator('.ann-status-heading').allInnerTexts();
    assert(headings.length === 2 && /OPEN\s+5/.test(headings[0]) && /ADDRESSED\s+2/.test(headings[1]),
      `two uppercase headings, got ${JSON.stringify(headings)}`);

    // Click open filter -> only 5 rows shown.
    await page.locator('.annotations .ann-filter', { hasText: 'open' }).click();
    await sleep(100);
    const rowsOpen = await page.locator('.annotations .ann-row').count();
    assert(rowsOpen === 5, `open filter yields 5 rows, got ${rowsOpen}`);

    // Click addressed -> 2 rows.
    await page.locator('.annotations .ann-filter', { hasText: 'addressed' }).click();
    await sleep(100);
    const rowsAddr = await page.locator('.annotations .ann-row').count();
    assert(rowsAddr === 2, `addressed filter yields 2 rows, got ${rowsAddr}`);

    // Click all -> back to 7.
    await page.locator('.annotations .ann-filter', { hasText: 'all' }).click();
    await sleep(100);
    const rowsAll = await page.locator('.annotations .ann-row').count();
    assert(rowsAll === 7, `all filter restores 7 rows, got ${rowsAll}`);

    // Open the table view and verify sortable columns.
    await page.locator('.annotations .ann-table-toggle').click();
    await sleep(300);
    const tableHeaders = await page.locator('table thead th button, table thead th').allInnerTexts();
    assert(tableHeaders.length >= 5, `table has at least 5 header cells, got ${tableHeaders.length}: ${JSON.stringify(tableHeaders)}`);
    const tableRows = await page.locator('table tbody tr').count();
    assert(tableRows >= 7, `table lists all 7 annotations, got ${tableRows}`);

    PASS(NAME, `rows 7/5/2/7, chips ${chips.join('|')}, headings ${headings.join('|')}, table rows ${tableRows}`);
  } catch (e) {
    FAIL(NAME, e.message);
  } finally {
    await browser.close();
  }
})();
