// Coverage item 6: open an existing annotation → + reply → send → comment lands.
import { SAMPLE, launch, openViewer, PASS, FAIL, assert, sleep,
         snapshotSidecar, readSidecar } from './_harness.mjs';

const NAME = 'annotation-reply-e2e';
snapshotSidecar('sample-01-single-atom');
(async () => {
  const { browser, page } = await launch();
  try {
    const target = (readSidecar('sample-01-single-atom').annotations || [])
      .find(a => (a.status || 'open') === 'open');
    assert(target, 'at least one open annotation exists');

    await openViewer(page, SAMPLE);
    await page.waitForSelector('.annotations .ann-row');
    await page.locator(`.annotations .ann-row[data-ann-id="${target.id}"]`).click();
    await sleep(400);
    await page.waitForSelector('#atom-detail .ann-full-detail', { timeout: 5000 });

    await page.locator('#atom-detail .btn.reply-open').click();
    const marker = 'e2e-reply-' + Date.now();
    await page.locator('#atom-detail .ann-reply textarea').fill(marker);

    let postSeen = null;
    page.on('request', req => {
      if (req.method() === 'POST' && req.url().includes('/annotations')) {
        try { postSeen = JSON.parse(req.postData() || 'null'); } catch (_) {}
      }
    });
    await page.locator('#atom-detail .ann-reply .reply-actions .primary').click();
    await sleep(1500);

    const after = readSidecar('sample-01-single-atom');
    const updated = after.annotations.find(a => a.id === target.id);
    assert(updated, 'target annotation still present after reply');
    const comments = updated.comments || [];
    const last = comments[comments.length - 1];
    assert(last && last.text === marker, `last comment text matches, got ${JSON.stringify(last)?.slice(0,160)}`);
    assert(typeof last.by === 'string' && last.by.length > 0, `comment has non-empty "by" author, got by=${last?.by}`);

    assert(postSeen && postSeen.annotations, `wire format: POST body carries annotations array`);
    PASS(NAME, `${target.id} gained comment #${comments.length} by=${last.by}`);
  } catch (e) {
    FAIL(NAME, e.message);
  } finally {
    await browser.close();
  }
})();
