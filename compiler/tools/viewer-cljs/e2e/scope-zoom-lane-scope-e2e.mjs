// Scope-zoom Lane -> LaneSide scope drill (round1-opus review, checks 2 and 3).
//
// Click Lane -> scope-of-Lane renders Lane container with Lane self-pill +
// LaneSide (sealed) + ghost pills for atoms Lane talks to across the scope
// boundary. Breadcrumb "← root / Lane". Legend still visible.
//
// Click LaneSide -> scope-of-LaneSide. Breadcrumb "← root / Lane / LaneSide".
import { BASE, launch, openViewer, PASS, FAIL, assert, sleep } from './_harness.mjs';
import { writeFileSync, mkdirSync } from 'node:fs';

const NAME = 'scope-zoom-lane-scope-e2e';
const OUT = '/tmp/scope-zoom-review/round1-opus';
const CARDS_URL = `${BASE}/trace-viewer.html?trace=cards.trace.jsonl&shape=cards.shape.json`;

async function readBlocks(page) {
  return await page.evaluate(() => {
    const svg = document.querySelector('#shape-container svg');
    if (!svg) return { blocks: [], breadcrumb: null, legendPresent: false };
    const blocks = Array.from(svg.querySelectorAll('.shape-block')).map(b => ({
      id: b.dataset.nodeId || null,
      name: b.dataset.nodeName || null,
      cls: b.getAttribute('class') || '',
    })).filter(x => x.id);
    const breadcrumb = document.querySelector('#fractal-breadcrumb');
    const legend = document.querySelector('.shape-legend, .legend-card, [class*="legend"]');
    return {
      blocks,
      breadcrumb: breadcrumb ? breadcrumb.textContent.trim() : null,
      legendPresent: !!legend,
    };
  });
}

async function clickBlockRect(page, nodeId) {
  const result = await page.evaluate((id) => {
    const svg = document.querySelector('#shape-container svg');
    if (!svg) return { ok: false, reason: 'no svg' };
    const blocks = Array.from(svg.querySelectorAll('.shape-block'));
    const match = blocks.find(b => (b.dataset.nodeId || '') === id);
    if (!match) return { ok: false, reason: 'no block with id ' + id, available: blocks.map(b => b.dataset.nodeId) };
    // The sealed/leaf rects have the click handler attached. Fire a click.
    match.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    return { ok: true };
  }, nodeId);
  await sleep(600);
  // Wait for the shape to repaint.
  await page.waitForFunction(() => document.querySelector('#shape-container svg .shape-block'), { timeout: 10000 });
  await sleep(300);
  return result;
}

(async () => {
  mkdirSync(OUT, { recursive: true });
  const { browser, page } = await launch({ viewport: { width: 1600, height: 1000 }, deviceScaleFactor: 1 });
  try {
    await openViewer(page, CARDS_URL);
    await sleep(500);

    // --- Check 2: click Lane -> scope-of-Lane
    const clickLane = await clickBlockRect(page, 'Lane');
    assert(clickLane.ok, `could not click Lane: ${JSON.stringify(clickLane)}`);
    await page.screenshot({ path: `${OUT}/02-scope-lane.png` });
    const lane = await readBlocks(page);
    writeFileSync(`${OUT}/02-scope-lane-dom.json`, JSON.stringify(lane, null, 2));

    const laneIds = new Set(lane.blocks.map(b => b.id));
    assert(laneIds.has('Lane'), `scope-of-Lane should include Lane container, blocks=${[...laneIds].join(',')}`);
    assert(laneIds.has('LaneSide'), `scope-of-Lane should include LaneSide (direct child), blocks=${[...laneIds].join(',')}`);
    const ghosts = lane.blocks.filter(b => b.cls.split(/\s+/).includes('ghost'));
    assert(ghosts.length >= 1, `scope-of-Lane should have ghost pills for outside atoms, got 0`);
    // Must have breadcrumb "← root / Lane"
    assert(lane.breadcrumb && lane.breadcrumb.includes('Lane'),
      `scope-of-Lane breadcrumb missing "Lane", got "${lane.breadcrumb}"`);
    assert(lane.breadcrumb && lane.breadcrumb.includes('root'),
      `scope-of-Lane breadcrumb missing "root", got "${lane.breadcrumb}"`);
    assert(lane.legendPresent, 'scope-of-Lane should still show legend');

    // --- Check 3: click LaneSide -> scope-of-LaneSide
    const clickLaneSide = await clickBlockRect(page, 'LaneSide');
    assert(clickLaneSide.ok, `could not click LaneSide: ${JSON.stringify(clickLaneSide)}`);
    await page.screenshot({ path: `${OUT}/03-scope-laneside.png` });
    const laneSide = await readBlocks(page);
    writeFileSync(`${OUT}/03-scope-laneside-dom.json`, JSON.stringify(laneSide, null, 2));

    assert(laneSide.breadcrumb && laneSide.breadcrumb.includes('LaneSide'),
      `scope-of-LaneSide breadcrumb missing "LaneSide", got "${laneSide.breadcrumb}"`);
    assert(laneSide.breadcrumb && laneSide.breadcrumb.includes('Lane'),
      `scope-of-LaneSide breadcrumb missing "Lane", got "${laneSide.breadcrumb}"`);
    // LaneSide owns Hand in cards.cxc.
    const lsIds = new Set(laneSide.blocks.map(b => b.id));
    assert(lsIds.has('LaneSide'), `scope-of-LaneSide should include LaneSide container`);

    PASS(NAME,
      `Lane scope blocks=[${[...laneIds].join(',')}] ghosts=${ghosts.length}; ` +
      `LaneSide crumb="${laneSide.breadcrumb}"`);
  } catch (e) {
    FAIL(NAME, e.message);
  } finally {
    await browser.close();
  }
})();
