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

    // gap-01: each row carries an inline status swatch next to the id.
    const swatches = await page.locator('.ann-row .ann-status-swatch').count();
    assert(swatches === 7, `every ann-row shows a swatch, got ${swatches}/7`);

    // gap-02: section headings carry the status border color (open=accent, addressed=success).
    const borderColors = await page.$$eval('.ann-status-heading',
      els => els.map(el => [el.dataset.status, getComputedStyle(el).borderColor]));
    const open = borderColors.find(([s]) => s === 'open');
    const addressed = borderColors.find(([s]) => s === 'addressed');
    assert(open && addressed, `heading borders present, got ${JSON.stringify(borderColors)}`);
    assert(open[1] !== addressed[1], `open and addressed headings differ in color, got ${open[1]} vs ${addressed[1]}`);

    // gap-03: headings render as blocks spanning the panel column.
    const headingDisplay = await page.$eval('.ann-status-heading', el => getComputedStyle(el).display);
    assert(headingDisplay === 'block', `heading is display:block, got "${headingDisplay}"`);

    // gap-05: bottom controls present with the legacy labels.
    const annotateBtn = await page.locator('.annotations .annotation-controls button', { hasText: /annotate/i }).count();
    const exportBtn = await page.locator('.annotations .annotation-controls button', { hasText: /export/i }).count();
    assert(annotateBtn === 1 && exportBtn === 1, `+ annotate / export present, got ${annotateBtn}/${exportBtn}`);

    // gap-07: addressed rows (ann_06, ann_07) share one stripe color, distinct from open rows.
    const openStripe = await page.$eval('.ann-row[data-status="open"]', el => getComputedStyle(el).borderLeftColor);
    const addrStripe = await page.$eval('.ann-row[data-status="addressed"]', el => getComputedStyle(el).borderLeftColor);
    assert(openStripe !== addrStripe, `open vs addressed stripe colors differ, got ${openStripe} vs ${addrStripe}`);

    // gap-08: severity label (.ann-sev) uses normal weight, kind label (.ann-kind) is smaller.
    const sevStyle = await page.$eval('.ann-row .ann-sev', el => {
      const s = getComputedStyle(el); return [s.fontWeight, parseFloat(s.fontSize)];
    });
    const kindStyle = await page.$eval('.ann-row .ann-kind', el => {
      const s = getComputedStyle(el); return [s.fontWeight, parseFloat(s.fontSize)];
    });
    assert(Number(sevStyle[0]) < 500, `.ann-sev weight < 500, got ${sevStyle[0]}`);
    assert(kindStyle[1] < sevStyle[1], `.ann-kind smaller than .ann-sev, got ${kindStyle[1]} vs ${sevStyle[1]}`);

    // gap-11: three overview dividers present in the sidebar.
    const sidebarSections = await page.locator('#sidebar details > summary').count();
    assert(sidebarSections >= 3, `sidebar has >=3 collapsible sections, got ${sidebarSections}`);

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
