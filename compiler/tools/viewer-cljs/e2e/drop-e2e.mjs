// Coverage item 10: dropping a .trace.jsonl file rehydrates the viewer.
import { BASE, launch, PASS, FAIL, assert, sleep, RESEARCH } from './_harness.mjs';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const NAME = 'drop-e2e';
(async () => {
  const { browser, page } = await launch();
  try {
    // Boot with no params → "no trace or shape loaded" empty state.
    await page.goto(`${BASE}/trace-viewer.html`, { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('#drop');

    const tracePath = resolve(RESEARCH, 'sample-01-single-atom.trace.jsonl');
    const text = readFileSync(tracePath, 'utf8');

    // Use the file-picker input so we exercise the exact rehydration path.
    const chooser = await Promise.all([
      page.waitForEvent('filechooser'),
      page.locator('#drop').click(),
    ]);
    await chooser[0].setFiles(tracePath);
    await page.waitForSelector('.annotations', { timeout: 8000 });
    await sleep(300);

    const header = await page.locator('header .trace-name').first().innerText();
    assert(/sample-01-single-atom\.trace\.jsonl/i.test(header),
      `header shows loaded filename, got "${header}"`);
    const bytes = text.length;
    PASS(NAME, `rehydrated from ${bytes}B jsonl, header "${header}"`);
  } catch (e) {
    FAIL(NAME, e.message);
  } finally {
    await browser.close();
  }
})();
