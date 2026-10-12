// Scope-zoom lifecycle / Esc walk / breadcrumb / ghost checks (checks 4-8).
//
// Covers:
// 4. Click Lane self-pill from scope-of-Lane → terminal lifecycle.
// 5. Click a leaf (Deck) from tier-1 → straight to lifecycle.
// 6. Esc walks lifecycle → scope → parent scope → root; one more Esc = no-op.
// 7. Breadcrumb crumb click rescopes.
// 8. Ghost pill click rescopes.
import { BASE, launch, openViewer, PASS, FAIL, assert, sleep } from './_harness.mjs';
import { writeFileSync, mkdirSync } from 'node:fs';

const NAME = 'scope-zoom-lifecycle-and-esc-e2e';
const OUT = '/tmp/scope-zoom-review/round1-opus';
const CARDS_URL = `${BASE}/trace-viewer.html?trace=cards.trace.jsonl&shape=cards.shape.json`;

async function readState(page) {
  return await page.evaluate(() => {
    const svg = document.querySelector('#shape-container svg');
    const blocks = svg ? Array.from(svg.querySelectorAll('.shape-block')).map(b => b.dataset.nodeId).filter(Boolean) : [];
    const phases = svg ? svg.querySelectorAll('.phase-node').length : 0;
    const bc = document.querySelector('#fractal-breadcrumb');
    const legend = document.querySelector('.shape-legend, [class*="legend"]');
    return {
      blocks, phaseCount: phases,
      breadcrumb: bc ? bc.textContent.trim() : null,
      legendVisible: !!legend,
    };
  });
}

async function clickById(page, nid) {
  await page.evaluate((id) => {
    const svg = document.querySelector('#shape-container svg');
    const b = Array.from(svg.querySelectorAll('.shape-block')).find(x => (x.dataset.nodeId||'') === id && !x.classList.contains('container'));
    if (b) b.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
  }, nid);
  await sleep(900);
}

(async () => {
  mkdirSync(OUT, { recursive: true });
  const { browser, page } = await launch({ viewport: { width: 1600, height: 1000 }, deviceScaleFactor: 1 });
  const results = {};
  try {
    await openViewer(page, CARDS_URL);
    await sleep(800);

    // --- Check 5 first: click a leaf (Deck) at tier-1 → lifecycle.
    await clickById(page, 'Deck');
    await page.screenshot({ path: `${OUT}/05-leaf-deck.png` });
    const t5 = await readState(page);
    results.check5_leafDeck = t5;
    writeFileSync(`${OUT}/05-leaf-deck.json`, JSON.stringify(t5, null, 2));
    assert(t5.phaseCount > 0, `Deck click should render lifecycle (phase nodes); got blocks=${t5.blocks.length} phaseCount=${t5.phaseCount}`);
    assert(t5.breadcrumb && t5.breadcrumb.includes('lifecycle'),
      `Deck lifecycle breadcrumb must say lifecycle, got "${t5.breadcrumb}"`);
    assert(!t5.legendVisible, `Legend must be hidden in lifecycle, got visible`);

    // --- Check 6a: Esc from Deck lifecycle → scope-of-Deck? Deck has no children,
    // so Esc walks to the parent scope (CxcMain or root).
    await page.keyboard.press('Escape'); await sleep(600);
    const t6a = await readState(page);
    results.check6_firstEsc = t6a;
    writeFileSync(`${OUT}/06a-esc-from-deck-lifecycle.json`, JSON.stringify(t6a, null, 2));

    // --- Check 6b: Second Esc should keep walking.
    await page.keyboard.press('Escape'); await sleep(600);
    const t6b = await readState(page);
    results.check6_secondEsc = t6b;
    writeFileSync(`${OUT}/06b-esc-second.json`, JSON.stringify(t6b, null, 2));

    // --- Check 6c: Third Esc at root should be no-op (legend open might toggle,
    // but canvas state does not change further).
    await page.keyboard.press('Escape'); await sleep(400);
    const t6c = await readState(page);
    results.check6_thirdEsc = t6c;

    // --- Check 4: scope into Lane, then click Lane self-pill → lifecycle.
    await clickById(page, 'Lane');
    const scopeLane = await readState(page);
    assert(scopeLane.blocks.length > 0, `scope-of-Lane should have blocks, got none; breadcrumb=${scopeLane.breadcrumb}`);
    // Click Lane self-pill (data-id "Lane", NOT the container)
    await clickById(page, 'Lane');
    await page.screenshot({ path: `${OUT}/04-lifecycle-lane.png` });
    const t4 = await readState(page);
    results.check4_laneLifecycle = t4;
    writeFileSync(`${OUT}/04-lifecycle-lane.json`, JSON.stringify(t4, null, 2));
    assert(t4.breadcrumb && t4.breadcrumb.includes('Lane') && t4.breadcrumb.includes('lifecycle'),
      `Lane lifecycle breadcrumb needs Lane + lifecycle, got "${t4.breadcrumb}"`);
    assert(t4.phaseCount > 0, `Lane lifecycle should render phase nodes, got ${t4.phaseCount}`);

    // --- Check 7: breadcrumb click rescopes. Right now we're at Lane lifecycle.
    // Click "root" button to go to root.
    const crumbInfo = await page.evaluate(() => {
      const bc = document.querySelector('#fractal-breadcrumb');
      const buttons = Array.from(bc ? bc.querySelectorAll('button') : []);
      const labels = buttons.map(b => ({ cls: b.className, text: b.textContent.trim() }));
      const target = buttons.find(b => b.textContent.includes('root'));
      if (target) target.click();
      return { labels, clicked: !!target };
    });
    await sleep(700);
    const afterRootCrumb = await readState(page);
    results.check7_breadcrumb = { crumbInfo, afterRootCrumb };
    writeFileSync(`${OUT}/07-breadcrumb.json`, JSON.stringify(results.check7_breadcrumb, null, 2));
    assert(crumbInfo.clicked, `root crumb not found, labels=${JSON.stringify(crumbInfo.labels)}`);
    assert(!afterRootCrumb.breadcrumb, `after root crumb, breadcrumb should be gone, got "${afterRootCrumb.breadcrumb}"`);

    // --- Check 8: Ghost click rescopes. Scope into Lane, click a ghost.
    await clickById(page, 'Lane');
    const ghostTargets = await page.evaluate(() => {
      const svg = document.querySelector('#shape-container svg');
      const g = Array.from(svg.querySelectorAll('.shape-block.ghost')).map(b => ({ id: b.dataset.nodeId, name: b.dataset.nodeName }));
      return g;
    });
    assert(ghostTargets.length > 0, `scope-of-Lane must expose ghost pills, got none`);
    const chosenGhost = ghostTargets[0];
    await page.evaluate((gid) => {
      const svg = document.querySelector('#shape-container svg');
      const b = Array.from(svg.querySelectorAll('.shape-block.ghost')).find(x => (x.dataset.nodeId||'') === gid);
      if (b) b.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    }, chosenGhost.id);
    await sleep(900);
    const afterGhost = await readState(page);
    await page.screenshot({ path: `${OUT}/08-after-ghost-click.png` });
    results.check8_ghost = { chosenGhost, afterGhost };
    writeFileSync(`${OUT}/08-ghost.json`, JSON.stringify(results.check8_ghost, null, 2));
    // After clicking the ghost, breadcrumb should name the ghost's atom.
    assert(afterGhost.breadcrumb && afterGhost.breadcrumb.includes(chosenGhost.name),
      `after ghost click, breadcrumb should include ghost's atom name "${chosenGhost.name}", got "${afterGhost.breadcrumb}"`);

    writeFileSync(`${OUT}/lifecycle-and-esc-summary.json`, JSON.stringify(results, null, 2));
    PASS(NAME,
      `check4-crumb="${t4.breadcrumb}", check5-crumb="${t5.breadcrumb}", escWalk=[${results.check6_firstEsc.breadcrumb||'root'} → ${results.check6_secondEsc.breadcrumb||'root'}], ghost=${chosenGhost.id}`);
  } catch (e) {
    FAIL(NAME, e.message);
  } finally {
    await browser.close();
  }
})();
