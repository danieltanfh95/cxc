// C03: both URL loading and local selection report every rejected physical line.
import { BASE, launch, PASS, FAIL, assert } from './_harness.mjs';

const NAME = 'jsonl-malformed-e2e';
const artifact = 'jsonl-malformed.trace.jsonl';
const event = seq => ({ seq, t: seq, kind: 'arm', target: 1, target_cls: 'Hand', msg: 'append' });
const text = [JSON.stringify(event(0)), '', 'BROKEN', 'null', '42', '[]', 'true', '"scalar"',
  JSON.stringify(event(8)), '{"seq":'].join('\n');
const dropped = [
  { line: 3, reason: 'parse-error' }, { line: 4, reason: 'null' },
  { line: 5, reason: 'not-object' }, { line: 6, reason: 'not-object' },
  { line: 7, reason: 'not-object' }, { line: 8, reason: 'not-object' },
  { line: 10, reason: 'parse-error' },
];

async function verify(page) {
  await page.waitForFunction(() => window.cxcViewerV2?.getState()['dropped-lines']?.length === 7);
  const state = await page.evaluate(() => window.cxcViewerV2.getState());
  assert(JSON.stringify(state['dropped-lines']) === JSON.stringify(dropped), 'physical line numbers and reasons retained');
  assert(JSON.stringify(state.events.map(e => e.seq)) === '[0,8]', 'valid events kept in order');
  const banner = page.locator('.jsonl-dropped');
  assert(await banner.isVisible(), 'persistent loss banner is visible');
  assert(await banner.locator('summary').textContent() === `7 lines dropped from ${artifact}`, 'loss banner names artifact');
  await banner.locator('summary').click();
  assert(await banner.locator('li').count() === 7, 'expanded list shows all rejections');
  for (let i = 0; i < dropped.length; i++) {
    assert(await banner.locator('li').nth(i).textContent() === `line ${dropped[i].line}: ${dropped[i].reason}`, 'rejection text');
  }
  await page.locator('.filters summary').click();
  assert(await banner.isVisible(), 'unrelated interaction keeps loss banner');
}

(async () => {
  const { browser, page } = await launch();
  try {
    await page.route(`**/${artifact}`, route => route.fulfill({ contentType: 'application/x-ndjson', body: text }));
    await page.goto(`${BASE}/trace-viewer.html?trace=${artifact}`, { waitUntil: 'domcontentloaded' });
    await verify(page);
    await page.locator('#file-picker').setInputFiles({ name: artifact, mimeType: 'application/x-ndjson', buffer: Buffer.from(text) });
    await verify(page);
    const good = `${JSON.stringify(event(0))}\n${JSON.stringify(event(1))}`;
    await page.route('**/jsonl-good.trace.jsonl', route => route.fulfill({ contentType: 'application/x-ndjson', body: good }));
    await page.evaluate(() => window.cxcViewerV2.loadTrace('jsonl-good.trace.jsonl'));
    await page.waitForFunction(() => window.cxcViewerV2.getState().events.length === 2 && !window.cxcViewerV2.getState()['loading?']);
    assert(await page.locator('.jsonl-dropped').count() === 0, 'well-formed artifact clears loss banner');
    await PASS(NAME, 'URL/drop malformed lines reported; well-formed final line preserved');
  } catch (error) {
    await FAIL(NAME, error.message);
  } finally {
    await browser.close();
  }
})();
