// Coverage item 3: clicking an atom drills into its view (lifecycle for a
// leaf, scope for an expandable one). Escape walks one step out.
//
// Previously this test opened the right-side detail pane on click; after the
// scope-zoom reframe (ddd6bfe...383e1a2) the pane is dormant whenever a
// zoom is active, so the equivalent assertion is on the breadcrumb and the
// terminal phase graph.
import { CARDS, launch, openViewer, PASS, FAIL, assert, sleep } from './_harness.mjs';

const NAME = 'atom-detail-e2e';
(async () => {
  const { browser, page } = await launch();
  try {
    await openViewer(page, CARDS);
    // Pick the first non-main sealed atom. Clicking main is a special case:
    // tier-1 is already main's implicit interior, so main click jumps straight
    // to lifecycle and Esc goes back to root — exercised by the
    // scope-zoom-main-blank-* tests, not here.
    const leaf = page.locator('#shape-container svg .shape-block:not(.container):not([data-node-name="main"])').first();
    const bbox = await leaf.boundingBox();
    const leafName = await leaf.getAttribute('data-node-name');
    await page.mouse.click(bbox.x + bbox.width / 2, bbox.y + bbox.height / 2);
    await sleep(400);

    const crumb = await page.evaluate(() =>
      document.querySelector('#fractal-breadcrumb')?.textContent?.trim() || '');
    assert(crumb.length > 0, `breadcrumb visible, got "${crumb}"`);
    assert(crumb.endsWith('lifecycle'),
      `leaf click lands on lifecycle, breadcrumb "${crumb}"`);
    const hasPhase = await page.evaluate(() =>
      !!document.querySelector('#shape-container svg'));
    assert(hasPhase, 'terminal view shows a phase graph SVG');

    await page.keyboard.press('Escape');
    await sleep(300);
    const crumbAfterEsc = await page.evaluate(() =>
      document.querySelector('#fractal-breadcrumb')?.textContent?.trim() || '');
    // Esc from :phases on a non-root leaf → :scope on the same leaf.
    // Breadcrumb drops the "lifecycle" tail.
    assert(crumbAfterEsc.length > 0 && !crumbAfterEsc.endsWith('lifecycle'),
      `Esc exits lifecycle, breadcrumb now "${crumbAfterEsc}"`);

    PASS(NAME, `clicked ${leafName}, lifecycle "${crumb}", esc → "${crumbAfterEsc}"`);
  } catch (e) {
    FAIL(NAME, e.message);
  } finally {
    await browser.close();
  }
})();
