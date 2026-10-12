// Scope-zoom "main blank" bug hunt against port 8787 with the `./compare/`
// paths the task spec called out. Reproduces the user's symptom when it
// manifests at that specific URL shape.
//
// Captures console output + screenshots at the two states (tier-1, after
// clicking CxcMain self-pill) so the comparison between 8787 and 8769 is
// concrete, not inferred.
import { launch, PASS, FAIL, assert, sleep } from './_harness.mjs';
import { writeFileSync, mkdirSync } from 'node:fs';

const NAME = 'scope-zoom-main-blank-port8787-e2e';
const OUT = '/tmp/scope-zoom-review/round1-opus';
// Explicit URL from the task spec; override the harness default.
const CARDS_URL_8787 = 'http://127.0.0.1:8787/trace-viewer.html?shape=./compare/cards.shape.json&trace=./compare/cards.trace.jsonl';
const CARDS_URL_8769 = 'http://127.0.0.1:8769/trace-viewer.html?trace=cards.trace.jsonl&shape=cards.shape.json';

async function readDom(page) {
  return await page.evaluate(() => {
    const svg = document.querySelector('#shape-container svg');
    const errBanner = document.querySelector('.error, [class*="error"]');
    const errText = errBanner ? errBanner.textContent.trim() : null;
    if (!svg) return { blocks: [], breadcrumb: null, viewBox: null, errText };
    const blocks = Array.from(svg.querySelectorAll('.shape-block')).map(b => ({
      id: b.dataset.nodeId || null,
      cls: b.getAttribute('class') || '',
    })).filter(x => x.id);
    const breadcrumb = document.querySelector('#fractal-breadcrumb');
    return {
      blocks,
      breadcrumb: breadcrumb ? breadcrumb.textContent.trim() : null,
      viewBox: svg.getAttribute('viewBox'),
      errText,
    };
  });
}

async function openAndSettle(page, url) {
  await page.goto(url, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.annotations', { timeout: 10000 });
  await page.waitForSelector('#shape-container svg .shape-block', { timeout: 20000 });
  await sleep(600);
}

async function clickMainSelfPill(page) {
  const result = await page.evaluate(() => {
    const svg = document.querySelector('#shape-container svg');
    if (!svg) return { ok: false, reason: 'no svg' };
    const blocks = Array.from(svg.querySelectorAll('.shape-block'));
    const names = blocks.map(b => b.dataset.nodeId);
    const self = blocks.find(b => b.dataset.nodeId === 'CxcMain' && !b.classList.contains('container'));
    if (!self) return { ok: false, names };
    self.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    return { ok: true };
  });
  await sleep(1500);
  return result;
}

async function trial(label, url) {
  const { browser, page } = await launch({ viewport: { width: 1600, height: 1000 }, deviceScaleFactor: 1 });
  const consoleLog = [];
  const errorLog = [];
  page.on('console', msg => { if (msg.type() === 'error' || msg.type() === 'warning') consoleLog.push({ type: msg.type(), text: msg.text() }); });
  page.on('pageerror', e => errorLog.push({ message: e.message, stack: e.stack }));
  try {
    await openAndSettle(page, url);
    await page.screenshot({ path: `${OUT}/09-${label}-tier1.png` });
    const tier1 = await readDom(page);
    writeFileSync(`${OUT}/09-${label}-tier1-dom.json`, JSON.stringify(tier1, null, 2));

    const click = await clickMainSelfPill(page);
    await page.screenshot({ path: `${OUT}/09-${label}-main-scope.png` });
    const scope = await readDom(page);
    writeFileSync(`${OUT}/09-${label}-main-scope-dom.json`, JSON.stringify({ click, scope, consoleLog, errorLog }, null, 2));

    return { label, tier1, scope, click, consoleLog, errorLog };
  } finally {
    await browser.close();
  }
}

(async () => {
  mkdirSync(OUT, { recursive: true });
  const results = {};
  try {
    results['8787'] = await trial('port8787', CARDS_URL_8787);
  } catch (e) { results['8787'] = { error: e.message }; }

  try {
    results['8769'] = await trial('port8769', CARDS_URL_8769);
  } catch (e) { results['8769'] = { error: e.message }; }

  writeFileSync(`${OUT}/09-cross-port-summary.json`, JSON.stringify(results, null, 2));

  // Report. We expect scope-of-main to be identical to tier-1 in both ports.
  // If 8787's scope canvas is empty while 8769's is fine, we have reproduced the user bug
  // and localized it to a port-specific condition (worker / fetch path / CORS).
  const port8787 = results['8787'];
  const port8769 = results['8769'];
  const summary = [];
  if (port8787 && port8787.scope) {
    const sIds = port8787.scope.blocks.map(b => b.id);
    summary.push(`8787 scope-of-main: ${sIds.length} blocks (${sIds.join(',') || 'NONE'}), err="${port8787.scope.errText || ''}"`);
  } else {
    summary.push(`8787: ERROR ${JSON.stringify(port8787 && port8787.error)}`);
  }
  if (port8769 && port8769.scope) {
    const sIds = port8769.scope.blocks.map(b => b.id);
    summary.push(`8769 scope-of-main: ${sIds.length} blocks (${sIds.join(',') || 'NONE'}), err="${port8769.scope.errText || ''}"`);
  } else {
    summary.push(`8769: ERROR ${JSON.stringify(port8769 && port8769.error)}`);
  }

  // Decide pass/fail: if the user bug reproduces (scope-of-main blank on 8787) we FAIL loudly.
  const empty8787 = port8787 && port8787.scope && port8787.scope.blocks.length === 0;
  if (empty8787) {
    FAIL(NAME, `MAIN-BLANK REPRODUCED on 8787. ${summary.join(' | ')}`);
    return;
  }
  PASS(NAME, summary.join(' | '));
})();
