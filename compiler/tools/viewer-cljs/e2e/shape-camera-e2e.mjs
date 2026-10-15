// Coverage item 1: shape camera (zoom cluster + reset view).
import { CARDS, launch, openViewer, PASS, FAIL, assert } from './_harness.mjs';

const NAME = 'shape-camera-e2e';
(async () => {
  const { browser, page } = await launch();
  try {
    await openViewer(page, CARDS);
    const svg = () => page.locator('#shape-container svg').first();
    const level = () => page.locator('#shape-zoom-level').innerText();
    const vb = async () => (await svg().getAttribute('viewBox')).split(/\s+/).map(Number);

    const z0 = parseInt(await level());
    assert(Number.isFinite(z0) && z0 > 0, `initial zoom should be finite percent, got ${z0}`);

    await page.locator('#shape-zoom button[aria-label="Zoom in"]').click();
    const z1 = parseInt(await level());
    assert(z1 > z0, `zoom-in must increase, got ${z0} -> ${z1}`);

    await page.locator('#shape-zoom button[aria-label="Zoom out"]').click();
    const z2 = parseInt(await level());
    assert(z2 < z1, `zoom-out must decrease, got ${z1} -> ${z2}`);

    await page.locator('#shape-zoom button[aria-label="Reset zoom"]').click();
    const z3 = parseInt(await level());
    assert(z3 === z0, `reset zoom returns to default, got ${z3} vs initial ${z0}`);

    const before = await vb();
    await page.locator('#shape-zoom button[aria-label="Reset view"]').click();
    const after = await vb();
    assert(after.every(Number.isFinite) && after.length === 4,
      `reset view produces finite 4-tuple viewBox, got ${JSON.stringify(after)}`);
    // Reset view fits whole graph: width spans >400 for cards fixture (8 atoms).
    assert(after[2] > 400, `reset view width should fit full graph, got ${after[2]}`);

    PASS(NAME, `zoom ${z0}→${z1}→${z2}→${z3}, viewBox ${before[2].toFixed(0)}→${after[2].toFixed(0)}`);
  } catch (e) {
    FAIL(NAME, e.message);
  } finally {
    await browser.close();
  }
})();
