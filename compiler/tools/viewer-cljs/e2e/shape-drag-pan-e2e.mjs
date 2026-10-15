// Coverage item 2: drag-pan on empty canvas vs. click-to-drill on a block.
// Post scope-zoom reframe, a click on a leaf zooms into its lifecycle rather
// than opening the right-side detail pane. Drag past the threshold (~5px)
// still suppresses the zoom.
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

    // Click a leaf block (not a container). Expect the breadcrumb to appear
    // because the click triggers a scope or lifecycle zoom.
    const leaf = page.locator('#shape-container svg .shape-block:not(.container)').first();
    await leaf.scrollIntoViewIfNeeded();
    const lbox = await leaf.boundingBox();
    assert(lbox, 'leaf block has a bounding box');
    const crumbBefore = await page.evaluate(() =>
      document.querySelector('#fractal-breadcrumb')?.textContent?.trim() || '');
    assert(crumbBefore === '', `tier-1 has no breadcrumb, got "${crumbBefore}"`);
    await page.mouse.click(lbox.x + lbox.width / 2, lbox.y + lbox.height / 2);
    await sleep(400);
    const crumbAfter = await page.evaluate(() =>
      document.querySelector('#fractal-breadcrumb')?.textContent?.trim() || '');
    assert(crumbAfter.length > 0,
      `click on block drills into a scope, breadcrumb "${crumbAfter}"`);

    // Esc walks one step out per press: phases → scope → parent scope → root.
    // From a leaf click (Deck lifecycle) back to tier-1 is three steps.
    for (let i = 0; i < 4; i++) {
      await page.keyboard.press('Escape');
      await sleep(120);
      const c = await page.evaluate(() => document.querySelector('#fractal-breadcrumb')?.textContent?.trim() || '');
      if (c === '') break;
    }
    await sleep(200);
    const crumbReset = await page.evaluate(() =>
      document.querySelector('#fractal-breadcrumb')?.textContent?.trim() || '');
    assert(crumbReset === '', `Esc back to tier-1 clears the breadcrumb, got "${crumbReset}"`);

    // Big drag over threshold on a leaf block: tier-1 state must stay put.
    const freshLeaf = page.locator('#shape-container svg .shape-block:not(.container)').first();
    const flbox = await freshLeaf.boundingBox();
    await page.mouse.move(flbox.x + flbox.width / 2, flbox.y + flbox.height / 2);
    await page.mouse.down();
    await page.mouse.move(flbox.x + flbox.width / 2 + 60, flbox.y + flbox.height / 2, { steps: 6 });
    await page.mouse.up();
    await sleep(300);
    const crumbAfterDrag = await page.evaluate(() =>
      document.querySelector('#fractal-breadcrumb')?.textContent?.trim() || '');
    assert(crumbAfterDrag === '',
      `drag past threshold does not zoom, breadcrumb stayed "${crumbAfterDrag}"`);

    PASS(NAME, `pan ${vb0[0].toFixed(0)}→${vb1[0].toFixed(0)}, click drills to "${crumbAfter}"`);
  } catch (e) {
    FAIL(NAME, e.message);
  } finally {
    await browser.close();
  }
})();
