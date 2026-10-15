// Coverage item 7: click "addressed" on an open annotation → status transitions
// and sidecar POST carries the new status.
import { SAMPLE, launch, openViewer, PASS, FAIL, assert, sleep,
         snapshotSidecar, readSidecar } from './_harness.mjs';

const NAME = 'annotation-transition-e2e';
snapshotSidecar('sample-01-single-atom');
(async () => {
  const { browser, page } = await launch();
  try {
    const target = (readSidecar('sample-01-single-atom').annotations || [])
      .find(a => (a.status || 'open') === 'open');
    assert(target, 'at least one open annotation exists');

    await openViewer(page, SAMPLE);
    await page.waitForSelector('.annotations .ann-row');
    const openCountBefore = await page.locator('.annotations .ann-row[data-status="open"]').count();
    await page.locator(`.annotations .ann-row[data-ann-id="${target.id}"]`).click();
    await sleep(400);
    await page.waitForSelector('#atom-detail .btn.addressed');

    let postBody = null;
    page.on('request', req => {
      if (req.method() === 'POST' && req.url().includes('/annotations')) {
        try { postBody = JSON.parse(req.postData() || 'null'); } catch (_) {}
      }
    });
    await page.locator('#atom-detail .btn.addressed').click();
    // Resolution popup appears; fill required summary.
    const popup = page.locator('#ann-resolve-popup, [role="dialog"]');
    if (await popup.count() > 0) {
      const input = popup.locator('input[type="text"], textarea').first();
      if (await input.count() > 0) await input.fill('e2e-addressed-summary');
      const submit = popup.locator('button.primary, button[type="submit"]').first();
      if (await submit.count() > 0) await submit.click();
    }
    await sleep(1500);

    const after = readSidecar('sample-01-single-atom');
    const updated = after.annotations.find(a => a.id === target.id);
    assert(updated.status === 'addressed',
      `status transitioned to addressed, got ${updated.status}`);

    assert(postBody && postBody.annotations, 'wire POST body carries annotations array');
    const inPost = postBody.annotations.find(a => a.id === target.id);
    assert(inPost && inPost.status === 'addressed',
      `POST body says addressed for ${target.id}, got ${inPost?.status}`);

    // Sidebar count should drop by one for open.
    const openCountAfter = await page.locator('.annotations .ann-row[data-status="open"]').count();
    assert(openCountAfter === openCountBefore - 1,
      `open-rows ${openCountBefore} → ${openCountAfter}`);

    PASS(NAME, `${target.id} open→addressed, open ${openCountBefore}→${openCountAfter}`);
  } catch (e) {
    FAIL(NAME, e.message);
  } finally {
    await browser.close();
  }
})();
