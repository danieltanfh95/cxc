// Localize the "scope-of-main → lifecycle" bug.
//
// Hypothesis: expandable-atoms (populated by artifact-successor via
// CxcShapeCanvas.ownership) uses atom-name ids ("main", "Lane"), while the
// clicked self-pill at the main atom carries the trace node id ("CxcMain" in
// trace mode). The selectNode cond checks (contains? expandable-atoms nid),
// which misses, falls to :else, and switches to :phases → lifecycle.
//
// Verify by calling CxcShapeCanvas.ownership(shape, events) directly in the
// page with the loaded shape+events, and comparing the returned expandable
// set against the clicked node id.
import { launch, PASS, FAIL, assert, sleep } from './_harness.mjs';
import { writeFileSync, mkdirSync } from 'node:fs';

const NAME = 'scope-zoom-expandable-mismatch-e2e';
const OUT = '/tmp/scope-zoom-review/round1-opus';
const CARDS_URL_8769 = 'http://127.0.0.1:8769/trace-viewer.html?trace=cards.trace.jsonl&shape=cards.shape.json';
const CARDS_URL_8787 = 'http://127.0.0.1:8787/trace-viewer.html?shape=./compare/cards.shape.json&trace=./compare/cards.trace.jsonl';

async function openAndSettle(page, url) {
  await page.goto(url, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.annotations', { timeout: 10000 });
  await page.waitForSelector('#shape-container svg .shape-block', { timeout: 20000 });
  await sleep(1500);
}

async function trial(label, url, shapeUrl, traceUrl) {
  const { browser, page } = await launch({ viewport: { width: 1600, height: 1000 }, deviceScaleFactor: 1 });
  try {
    await openAndSettle(page, url);
    // Introspect ownership called with (shape, events)
    const own = await page.evaluate(async ([su, tu]) => {
      async function fetchJson(u) { const r = await fetch(u); return r.json(); }
      async function fetchJsonl(u) { const r = await fetch(u); const text = await r.text(); return text.split('\n').filter(Boolean).map(l => JSON.parse(l)); }
      const shape = await fetchJson(su);
      const events = await fetchJsonl(tu);
      // Also compute ownership shape-only (events=[])
      const ownTrace = window.CxcShapeCanvas.ownership(shape, events);
      const ownShapeOnly = window.CxcShapeCanvas.ownership(shape, []);
      // Inspect the live canvas blocks
      const svg = document.querySelector('#shape-container svg');
      const blocks = svg ? Array.from(svg.querySelectorAll('.shape-block')).map(b => b.dataset.nodeId).filter(Boolean) : [];
      return { ownTrace, ownShapeOnly, blocks };
    }, [shapeUrl, traceUrl]);

    // Now click CxcMain self-pill and observe mode change
    const info = await page.evaluate(() => {
      const svg = document.querySelector('#shape-container svg');
      const blocks = Array.from(svg.querySelectorAll('.shape-block'));
      const self = blocks.find(b => b.dataset.nodeId === 'CxcMain' && !b.classList.contains('container'));
      if (!self) return { ok: false };
      self.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
      return { ok: true };
    });
    await sleep(1500);
    const after = await page.evaluate(() => {
      const svg = document.querySelector('#shape-container svg');
      const blocks = svg ? Array.from(svg.querySelectorAll('.shape-block')).map(b => b.dataset.nodeId).filter(Boolean) : [];
      const bc = document.querySelector('#fractal-breadcrumb');
      return { blocks, breadcrumb: bc ? bc.textContent.trim() : null };
    });

    return { label, own, click: info, after };
  } finally {
    await browser.close();
  }
}

(async () => {
  mkdirSync(OUT, { recursive: true });
  const out = {};
  out['8769'] = await trial('8769', CARDS_URL_8769, '/cards.shape.json', '/cards.trace.jsonl');
  out['8787'] = await trial('8787', CARDS_URL_8787, '/compare/cards.shape.json', '/compare/cards.trace.jsonl');
  writeFileSync(`${OUT}/09-expandable-mismatch.json`, JSON.stringify(out, null, 2));

  // Compare: ownership trace-mode expandable vs clicked node id.
  const summary = Object.entries(out).map(([port, r]) => {
    const ownTrace = r.own.ownTrace;
    const ownShape = r.own.ownShapeOnly;
    const clicked = 'CxcMain';
    const inTrace = ownTrace.expandable.includes(clicked);
    const inShape = ownShape.expandable.includes(clicked);
    return `${port}: trace.expandable=[${ownTrace.expandable.join(',')}] shapeOnly.expandable=[${ownShape.expandable.join(',')}] clickedInTrace=${inTrace} clickedInShapeOnly=${inShape} afterCrumb="${r.after.breadcrumb}"`;
  }).join('\n');
  console.log('EXPANDABLE SUMMARY:\n' + summary);

  PASS(NAME, 'see 09-expandable-mismatch.json');
})();
