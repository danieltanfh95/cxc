// Cross-port smoke test for main-click behavior. Tier-1 flattens main as a
// peer pill; clicking main lands in lifecycle (phases) on both ports. The
// test catches the port-specific failure mode where one port returns a stale
// shape or an error banner that keeps the lifecycle view from loading at all.
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
    if (!svg) return { blocks: [], breadcrumb: null, viewBox: null, errText, phaseCount: 0 };
    const blocks = Array.from(svg.querySelectorAll('.shape-block')).map(b => ({
      id: b.dataset.nodeId || null,
      cls: b.getAttribute('class') || '',
    })).filter(x => x.id);
    const breadcrumb = document.querySelector('#fractal-breadcrumb');
    const phaseCount = svg.querySelectorAll('.phase-node').length;
    return {
      blocks,
      breadcrumb: breadcrumb ? breadcrumb.textContent.trim() : null,
      viewBox: svg.getAttribute('viewBox'),
      errText,
      phaseCount,
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

  // Both ports should land clicking-main on main's lifecycle view — crumb ends
  // with "lifecycle" AND the phase graph renders at least one phase node.
  const port8787 = results['8787'];
  const port8769 = results['8769'];
  const summarise = (label, r) => {
    if (!r || !r.scope) return `${label}: ERROR ${JSON.stringify(r && r.error)}`;
    const crumb = r.scope.breadcrumb || '';
    return `${label}: crumb="${crumb}" phases=${r.scope.phaseCount} err="${r.scope.errText || ''}"`;
  };
  const summary = [summarise('8787', port8787), summarise('8769', port8769)];
  const brokenOn = (label, r) => {
    if (!r || !r.scope) return `${label} crashed: ${(r && r.error) || 'no scope'}`;
    const crumb = r.scope.breadcrumb || '';
    if (!crumb.endsWith('lifecycle')) return `${label} did not enter lifecycle, crumb="${crumb}"`;
    if (r.scope.phaseCount === 0) return `${label} lifecycle has 0 phase nodes`;
    if (r.scope.errText) return `${label} shows error banner "${r.scope.errText}"`;
    return null;
  };
  const broken = [brokenOn('8787', port8787), brokenOn('8769', port8769)].filter(Boolean);
  if (broken.length) {
    FAIL(NAME, `${broken.join(' ; ')}. ${summary.join(' | ')}`);
    return;
  }
  PASS(NAME, summary.join(' | '));
})();
