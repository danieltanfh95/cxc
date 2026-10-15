// Scope-zoom tier-1 load (round1-opus review, check 1).
//
// On shape load, :collapsed-atoms seeds to every expandable atom except roots.
// Tier-1 flattens main's compound frame so main sits as a peer pill alongside
// its direct children (Deck/Lane/Player/Market). Obs edges between visible
// atoms render (e.g. Player→Lane.side). No chevrons, no ghost pills, legend
// visible, no breadcrumb.
import { BASE, launch, openViewer, PASS, FAIL, assert, sleep } from './_harness.mjs';
import { writeFileSync, mkdirSync } from 'node:fs';

const NAME = 'scope-zoom-tier1-e2e';
const OUT = '/tmp/scope-zoom-review/round1-opus';
const CARDS_URL = `${BASE}/trace-viewer.html?trace=cards.trace.jsonl&shape=cards.shape.json`;

(async () => {
  mkdirSync(OUT, { recursive: true });
  const { browser, page } = await launch({ viewport: { width: 1600, height: 1000 }, deviceScaleFactor: 1 });
  try {
    await openViewer(page, CARDS_URL);
    await sleep(600);

    await page.screenshot({ path: `${OUT}/01-tier1-load.png`, fullPage: false });

    const snap = await page.evaluate(() => {
      const svg = document.querySelector('#shape-container svg');
      const blocks = Array.from(svg.querySelectorAll('.shape-block'));
      const atoms = blocks.map(b => ({
        id: b.dataset.nodeId || null,
        name: b.dataset.nodeName || null,
        cls: b.getAttribute('class') || '',
        label: (b.querySelector ? (b.querySelector('.shape-block-label')||{}).textContent : '') || '',
        tag: b.tagName,
      })).filter(x => x.id);
      const containers = atoms.filter(a => a.cls.split(/\s+/).includes('container'));
      const ghosts = atoms.filter(a => a.cls.split(/\s+/).includes('ghost'));
      const chevrons = Array.from(svg.querySelectorAll('[class*="chevron"]'));
      const obsEdges = Array.from(svg.querySelectorAll('.shape-obs-edge, .shape-obs-edge-label'));
      const breadcrumb = document.querySelector('#fractal-breadcrumb');
      const legend = document.querySelector('.shape-legend, .legend-card, [class*="legend"]');
      return {
        atoms, containers: containers.map(c => c.id), ghosts: ghosts.map(g => g.id),
        chevronCount: chevrons.length,
        obsEdgeCount: obsEdges.length,
        breadcrumb: breadcrumb ? breadcrumb.textContent.trim() : null,
        legendPresent: !!legend,
      };
    });

    writeFileSync(`${OUT}/01-tier1-dom.json`, JSON.stringify(snap, null, 2));

    // The main atom in cards is CxcMain, which owns Deck, Lane, Player, Market.
    // Tier-1 should surface those four as sealed children + main's self-pill.
    const idSet = new Set(snap.atoms.map(a => a.id));
    const expected = ['Deck', 'Lane', 'Player', 'Market'];
    for (const e of expected) {
      assert(idSet.has(e), `tier-1 missing expected sealed child "${e}", have ${[...idSet].join(',')}`);
    }
    // Main renders as a peer pill at tier-1 — no compound-container frame.
    const mainPills = snap.atoms.filter(a => a.id === 'CxcMain' && !a.cls.includes('ghost'));
    assert(mainPills.length === 1,
      `tier-1 shows main as a single peer pill, got ${mainPills.length}: ${mainPills.map(m => m.cls).join(' | ')}`);
    assert(!mainPills[0].cls.split(/\s+/).includes('container'),
      `tier-1 main pill should NOT have .container class, got "${mainPills[0].cls}"`);
    // Ghost pills must not appear in tier-1 (we are at root scope).
    assert(snap.ghosts.length === 0, `tier-1 should have zero ghost pills, got ${snap.ghosts.join(',')}`);
    // No breadcrumb at root.
    assert(!snap.breadcrumb, `tier-1 should have no breadcrumb, got "${snap.breadcrumb}"`);
    // Legend visible at root.
    assert(snap.legendPresent, 'tier-1 should show legend');
    // Obs edges between visible top-level atoms do render (Player→Lane.side).
    assert(snap.obsEdgeCount > 0,
      `tier-1 should show cross-atom obs edges between visible atoms, got ${snap.obsEdgeCount}`);
    // No chevrons after the chevron removal.
    assert(snap.chevronCount === 0, `tier-1 should have no chevrons, got ${snap.chevronCount}`);

    PASS(NAME, `atoms=${snap.atoms.length}, containers=[${snap.containers.join(',')}], ghosts=0`);
  } catch (e) {
    FAIL(NAME, e.message);
  } finally {
    await browser.close();
  }
})();
