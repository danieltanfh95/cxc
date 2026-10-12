// Scope-zoom tier-1 load (round1-opus review, check 1).
//
// On shape load, :collapsed-atoms seeds to every expandable atom except roots,
// so tier-1 renders CxcMain as a container that holds its direct children as
// sealed leaves. No chevrons, no obs edges, legend visible.
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
    // Main container present (CxcMain as the compound). Self-pill id ends in __self.
    const hasMainSelf = snap.atoms.some(a => a.id === 'CxcMain' && !a.cls.includes('ghost'));
    assert(hasMainSelf, `tier-1 should show main container with self-pill; atoms=${snap.atoms.map(a=>a.id).join(',')}`);
    // Ghost pills must not appear in tier-1 (we are at root scope).
    assert(snap.ghosts.length === 0, `tier-1 should have zero ghost pills, got ${snap.ghosts.join(',')}`);
    // No breadcrumb at root.
    assert(!snap.breadcrumb, `tier-1 should have no breadcrumb, got "${snap.breadcrumb}"`);
    // Legend visible at root.
    assert(snap.legendPresent, 'tier-1 should show legend');
    // No obs edges — the renderer strips them before layout.
    assert(snap.obsEdgeCount === 0, `tier-1 should hide obs edges, got ${snap.obsEdgeCount}`);
    // No chevrons after the chevron removal.
    assert(snap.chevronCount === 0, `tier-1 should have no chevrons, got ${snap.chevronCount}`);

    PASS(NAME, `atoms=${snap.atoms.length}, containers=[${snap.containers.join(',')}], ghosts=0`);
  } catch (e) {
    FAIL(NAME, e.message);
  } finally {
    await browser.close();
  }
})();
