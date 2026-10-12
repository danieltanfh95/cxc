// Diagnose which branch of selectNode fires when clicking the main atom's
// self-pill at root scope. Expected branch: expandable? → :scope. Actual
// branch (per user report): :else → :phases, which renders the lifecycle
// strip and makes the shape canvas look empty.
//
// Reads window.cxc_viewer state before/after the click to localize the fault.
import { launch, PASS, FAIL, assert, sleep } from './_harness.mjs';
import { writeFileSync, mkdirSync } from 'node:fs';

const NAME = 'scope-zoom-main-click-diagnose-e2e';
const OUT = '/tmp/scope-zoom-review/round1-opus';
const URLS = [
  { label: '8769', url: 'http://127.0.0.1:8769/trace-viewer.html?trace=cards.trace.jsonl&shape=cards.shape.json' },
  { label: '8787', url: 'http://127.0.0.1:8787/trace-viewer.html?shape=./compare/cards.shape.json&trace=./compare/cards.trace.jsonl' },
];

async function dumpState(page) {
  return await page.evaluate(() => {
    // Try common exposure points. The CLJS app may expose state via replicant
    // registry or via a window attr. Check both.
    const out = { reasons: [] };
    const svg = document.querySelector('#shape-container svg');
    out.svgHasBlocks = svg ? svg.querySelectorAll('.shape-block').length : -1;
    out.breadcrumbText = (() => { const bc = document.querySelector('#fractal-breadcrumb'); return bc ? bc.textContent.trim() : null; })();
    out.phaseEls = svg ? svg.querySelectorAll('[data-phase], .phase, .phase-node, .phase-pill').length : -1;
    const dropZoneLabel = document.querySelector('#drop-zone, .drop');
    out.showsLifecycle = !!document.querySelector('.phase-graph, [class*="phase"]');
    return out;
  });
}

async function openAndSettle(page, url) {
  await page.goto(url, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.annotations', { timeout: 10000 });
  await page.waitForSelector('#shape-container svg .shape-block', { timeout: 20000 });
  await sleep(1500);
}

async function trial(label, url) {
  const { browser, page } = await launch({ viewport: { width: 1600, height: 1000 }, deviceScaleFactor: 1 });
  try {
    await openAndSettle(page, url);
    // Snapshot the tier-1 state.
    const t1 = await dumpState(page);
    // Enumerate CxcMain blocks and click the self-pill.
    const info = await page.evaluate(() => {
      const svg = document.querySelector('#shape-container svg');
      const blocks = Array.from(svg.querySelectorAll('.shape-block'));
      const mainBlocks = blocks.filter(b => (b.dataset.nodeId || '') === 'CxcMain').map(b => ({
        tag: b.tagName,
        cls: b.getAttribute('class'),
        isContainer: b.classList.contains('container'),
        bbox: (() => { try { const r = b.getBoundingClientRect(); return { x: r.x, y: r.y, w: r.width, h: r.height }; } catch (e) { return null; } })(),
      }));
      return { allIds: blocks.map(b => b.dataset.nodeId), mainBlocks };
    });

    // Click the non-container CxcMain block (the self-pill).
    const clickResult = await page.evaluate(() => {
      const svg = document.querySelector('#shape-container svg');
      const blocks = Array.from(svg.querySelectorAll('.shape-block'));
      const self = blocks.find(b => b.dataset.nodeId === 'CxcMain' && !b.classList.contains('container'));
      if (!self) return { ok: false };
      self.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
      return { ok: true };
    });
    await sleep(1500);
    const t2 = await dumpState(page);
    await page.screenshot({ path: `${OUT}/09-diag-${label}-after-click.png` });

    return { label, info, clickResult, t1, t2 };
  } finally {
    await browser.close();
  }
}

(async () => {
  mkdirSync(OUT, { recursive: true });
  const out = {};
  for (const { label, url } of URLS) {
    try { out[label] = await trial(label, url); }
    catch (e) { out[label] = { error: e.message }; }
  }
  writeFileSync(`${OUT}/09-diag-summary.json`, JSON.stringify(out, null, 2));

  const summarize = (r) => r && r.t2 ? `blocks=${r.t2.svgHasBlocks} crumb="${r.t2.breadcrumbText}"` : 'ERR';
  PASS(NAME, `8769=${summarize(out['8769'])} | 8787=${summarize(out['8787'])}`);
})();
