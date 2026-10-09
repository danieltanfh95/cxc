// Coverage item 5: shift-click → annotate popup → send → new sidecar row.
// Snapshots and restores sample-01 sidecar so re-runs leave it as found.
import { SAMPLE, launch, openViewer, PASS, FAIL, assert, sleep,
         snapshotSidecar, restoreSidecar, readSidecar } from './_harness.mjs';

const NAME = 'annotation-create-e2e';
const snap = snapshotSidecar('sample-01-single-atom');
(async () => {
  const { browser, page } = await launch();
  try {
    const before = readSidecar('sample-01-single-atom');
    const nBefore = (before?.annotations || []).length;

    await openViewer(page, SAMPLE);
    await page.waitForSelector('.annotations .ann-row');

    const leaf = page.locator('#shape-container svg .shape-block:not(.container)').first();
    const bbox = await leaf.boundingBox();
    await page.keyboard.down('Shift');
    await page.mouse.click(bbox.x + bbox.width / 2, bbox.y + bbox.height / 2);
    await page.keyboard.up('Shift');
    await page.waitForSelector('#annotate-popup', { timeout: 5000 });

    const marker = 'e2e-create-' + Date.now();
    await page.locator('#annotate-note').fill(marker);
    // Intercept the POST so we can confirm the sidecar wire format.
    let postSeen = null;
    page.on('request', req => {
      if (req.method() === 'POST' && req.url().includes('/annotations')) {
        try { postSeen = JSON.parse(req.postData() || 'null'); } catch (_) {}
      }
    });
    await page.locator('#annotate-popup button.primary', { hasText: 'send' }).click();
    await sleep(1200);

    assert(postSeen && postSeen.annotations, `sidecar POST issued with annotations array, got ${JSON.stringify(postSeen)?.slice(0,120)}`);
    assert(postSeen.annotations.some(a => a.note === marker),
      `POST body contains the new note, got ${postSeen.annotations.map(a => a.note).join('|').slice(0,160)}`);

    const after = readSidecar('sample-01-single-atom');
    const nAfter = (after?.annotations || []).length;
    assert(nAfter === nBefore + 1, `sidecar grew by one, ${nBefore}→${nAfter}`);
    assert(after.annotations.some(a => a.note === marker),
      `new annotation persisted with note "${marker}"`);

    PASS(NAME, `created id=${after.annotations.find(a => a.note === marker)?.id}, sidecar ${nBefore}→${nAfter}`);
  } catch (e) {
    FAIL(NAME, e.message);
  } finally {
    await browser.close();
    restoreSidecar(snap);
  }
})();
// H2-04: the shared harness owns interrupted/uncaught cleanup after browser close.
