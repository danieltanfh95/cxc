// Coverage item 13: CAS 409 and schema 400 wire hardening.
// Intercepts POST /annotations and returns 409 then 400 to verify the viewer
// recovers (409) and surfaces an error without crashing (400).
import { SAMPLE, launch, openViewer, PASS, FAIL, assert, sleep,
         snapshotSidecar, readSidecar } from './_harness.mjs';

const NAME = 'cas-schema-e2e';
snapshotSidecar('sample-01-single-atom');
(async () => {
  const { browser, page } = await launch();
  try {
    const baseSidecar = readSidecar('sample-01-single-atom');
    let mode = null;                    // 'cas-conflict' | 'schema-reject' | null (pass-through)
    let served409 = 0, served400 = 0;

    await page.route('**/annotations?*', async (route) => {
      const req = route.request();
      if (req.method() !== 'POST') return route.fallback();
      if (mode === 'cas-conflict') {
        served409++;
        const fresh = { ...baseSidecar, rev: 999, annotations: baseSidecar.annotations };
        return route.fulfill({
          status: 409, contentType: 'application/json',
          body: JSON.stringify({ error: 'cas conflict', sidecar: fresh }),
        });
      }
      if (mode === 'schema-reject') {
        served400++;
        return route.fulfill({
          status: 400, contentType: 'application/json',
          body: JSON.stringify({ error: 'unknown status: bogus' }),
        });
      }
      return route.fallback();
    });

    await openViewer(page, SAMPLE);
    await page.waitForSelector('.annotations .ann-row');

    // --- 409 CAS conflict ---
    mode = 'cas-conflict';
    const leaf = page.locator('#shape-container svg .shape-block:not(.container)').first();
    const bbox = await leaf.boundingBox();
    await page.keyboard.down('Shift');
    await page.mouse.click(bbox.x + bbox.width / 2, bbox.y + bbox.height / 2);
    await page.keyboard.up('Shift');
    await page.waitForSelector('#annotate-popup');
    await page.locator('#annotate-note').fill('e2e-cas-note');
    await page.locator('#annotate-popup button.primary').click();
    await sleep(1500);
    assert(served409 >= 1, `409 CAS conflict served ≥1 time, got ${served409}`);
    const alive = await page.evaluate(() => !!document.querySelector('.annotations'));
    assert(alive, `viewer still mounted after 409`);

    // --- 400 schema-reject ---
    mode = 'schema-reject';
    // Re-open popup and send again — this time server rejects.
    await page.keyboard.down('Shift');
    await page.mouse.click(bbox.x + bbox.width / 2, bbox.y + bbox.height / 2);
    await page.keyboard.up('Shift');
    await page.waitForSelector('#annotate-popup');
    await page.locator('#annotate-note').fill('e2e-schema-note');
    await page.locator('#annotate-popup button.primary').click();
    await sleep(1200);
    assert(served400 >= 1, `400 schema-reject served ≥1 time, got ${served400}`);
    // The viewer should still be alive; a save-error message may be surfaced.
    const stillAlive = await page.evaluate(() => !!document.querySelector('.annotations'));
    assert(stillAlive, `viewer still mounted after 400`);

    PASS(NAME, `409×${served409}, 400×${served400}, viewer survived both`);
  } catch (e) {
    FAIL(NAME, e.message);
  } finally {
    await browser.close();
  }
})();
