// Coverage item 3: atom detail opens on click and closes on Escape.
import { CARDS, launch, openViewer, PASS, FAIL, assert, sleep } from './_harness.mjs';

const NAME = 'atom-detail-e2e';
(async () => {
  const { browser, page } = await launch();
  try {
    await openViewer(page, CARDS);
    const leaf = page.locator('#shape-container svg .shape-block:not(.container)').first();
    const bbox = await leaf.boundingBox();
    await page.mouse.click(bbox.x + bbox.width / 2, bbox.y + bbox.height / 2);
    await sleep(300);

    const detailName = await page.locator('#atom-detail .atom-detail-name').first().innerText();
    assert(detailName.length > 0, `detail name present, got "${detailName}"`);
    const hasPhase = await page.evaluate(() =>
      !!document.querySelector('#atom-detail .phase-graph-container, #atom-detail svg'));
    assert(hasPhase, 'detail should include phase graph container or SVG');

    await page.keyboard.press('Escape');
    await sleep(200);
    const stillOpen = await page.evaluate(() =>
      !!document.querySelector('#atom-detail .atom-detail-name'));
    assert(!stillOpen, 'Escape should close the detail pane');

    PASS(NAME, `opened ${detailName}, closed on Esc`);
  } catch (e) {
    FAIL(NAME, e.message);
  } finally {
    await browser.close();
  }
})();
