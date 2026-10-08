// Coverage item 2: drag-pan on empty canvas vs. click-to-open on a block.
// Legacy threshold: drags over ~5px suppress the click (gap 52).
import { CARDS, launch, openViewer, PASS, FAIL, assert, sleep } from './_harness.mjs';

const NAME = 'shape-drag-pan-e2e';
(async () => {
  const { browser, page } = await launch();
  try {
    await openViewer(page, CARDS);

    const svg = page.locator('#shape-container svg').first();
    const vb0 = (await svg.getAttribute('viewBox')).split(/\s+/).map(Number);
    // Drag the canvas (empty area) to pan.
    const box = await page.locator('#shape-container').boundingBox();
    await page.mouse.move(box.x + 20, box.y + box.height - 20);
    await page.mouse.down();
    await page.mouse.move(box.x + 80, box.y + box.height - 20, { steps: 6 });
    await page.mouse.move(box.x + 140, box.y + box.height - 20, { steps: 6 });
    await page.mouse.up();
    await sleep(100);
    const vb1 = (await svg.getAttribute('viewBox')).split(/\s+/).map(Number);
    assert(vb1[0] !== vb0[0] || vb1[1] !== vb0[1],
      `empty-canvas drag should shift viewBox origin, before=${vb0.slice(0,2)} after=${vb1.slice(0,2)}`);

    // Click a leaf block (not a container, not an edge hit path).
    const leaf = page.locator('#shape-container svg .shape-block:not(.container)').first();
    const nameBefore = await page.evaluate(() => document.querySelector('#atom-detail .atom-detail-name')?.textContent || '');
    await leaf.scrollIntoViewIfNeeded();
    const lbox = await leaf.boundingBox();
    assert(lbox, 'leaf block has a bounding box');
    await page.mouse.click(lbox.x + lbox.width / 2, lbox.y + lbox.height / 2);
    await sleep(300);
    const nameAfter = await page.evaluate(() => document.querySelector('#atom-detail .atom-detail-name')?.textContent || '');
    assert(nameAfter.length > 0 && nameAfter !== nameBefore,
      `click on block opens detail, before="${nameBefore}" after="${nameAfter}"`);

    // Big drag over threshold on the same block: detail must not change identity.
    await page.mouse.move(lbox.x + lbox.width / 2, lbox.y + lbox.height / 2);
    await page.mouse.down();
    await page.mouse.move(lbox.x + lbox.width / 2 + 60, lbox.y + lbox.height / 2, { steps: 6 });
    await page.mouse.up();
    await sleep(200);
    const nameAfterDrag = await page.evaluate(() => document.querySelector('#atom-detail .atom-detail-name')?.textContent || '');
    assert(nameAfterDrag === nameAfter,
      `drag past threshold does not swap detail, stayed ${nameAfterDrag}`);

    PASS(NAME, `pan ${vb0[0].toFixed(0)}→${vb1[0].toFixed(0)}, click opens ${nameAfter}`);
  } catch (e) {
    FAIL(NAME, e.message);
  } finally {
    await browser.close();
  }
})();
