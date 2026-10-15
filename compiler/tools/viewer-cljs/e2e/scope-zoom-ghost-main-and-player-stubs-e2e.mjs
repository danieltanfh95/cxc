// Three invariants from the scope-zoom visual pass:
//
// 1. Ghost pill of main keeps the sepia (gold) identity across scope
//    boundaries. The ghost base rule paints paper + dashed; main needs an
//    override so the viewer still reads "that's main, elsewhere".
//
// 2. Scope-of-Player stubs reflect Player's boundary, not PlayableUnit's.
//    Lane→PlayableUnit obs calls (both outside Player's subtree) must not
//    land stubs on PlayableUnit's ghost pill. Only obs where exactly one
//    end sits inside the subtree cross the boundary.
//
// 3. Cross-boundary obs with a visible reader render as an edge, not as
//    a duplicate stub. Player→Lane.side draws the "side ×N" edge; the
//    stub on Lane's ghost is suppressed.
//
// Fixture: port 8769 shape has obs_calls [Lane→PlayableUnit.reflect_bonus,
// Lane→PlayableUnit.total_attack, Player→Lane.side].
import { launch, openViewer, PASS, FAIL, assert, sleep } from './_harness.mjs';

const NAME = 'scope-zoom-ghost-main-and-player-stubs-e2e';
const SEPIA_RGB = 'rgb(201, 164, 123)';
const BASE = process.env.VIEWER_BASE || 'http://127.0.0.1:8769';
const URL = `${BASE}/trace-viewer.html?trace=cards.trace.jsonl&shape=cards.shape.json`;

async function enterScope(page, atomName) {
  const clicked = await page.evaluate((nm) => {
    const b = Array.from(document.querySelectorAll('.shape-block'))
      .find(e => (e.dataset.nodeName || '') === nm);
    if (!b) return false;
    b.dispatchEvent(new MouseEvent('click', {bubbles:true, cancelable:true, view:window}));
    return true;
  }, atomName);
  if (!clicked) throw new Error(`atom "${atomName}" not found on current view`);
  await sleep(500);
}

(async () => {
  const { browser, page } = await launch();
  try {
    await openViewer(page, URL);
    const reopen = await page.$('#shape-legend-reopen');
    if (reopen) await reopen.click();
    await page.waitForSelector('#shape-legend', { timeout: 2000 });
    await page.evaluate(() => {
      const row = Array.from(document.querySelectorAll('#shape-legend .legend-layer-row'))
        .find(r => r.textContent.toLowerCase().includes('observation'));
      if (!row) throw new Error('observations row not found');
      const cb = row.querySelector('input[type=checkbox]');
      if (!cb.checked) cb.click();
    });
    await sleep(300);

    await enterScope(page, 'Player');
    const player = await page.evaluate(() => {
      const ghostMain = document.querySelector('.shape-block.ghost.main');
      const fill = ghostMain ? getComputedStyle(ghostMain).fill : null;
      const dash = ghostMain ? getComputedStyle(ghostMain).strokeDasharray : null;
      const stubs = Array.from(document.querySelectorAll('.cxc-obs-stub-label')).map(l => l.textContent.trim());
      const obsEdges = Array.from(document.querySelectorAll('.shape-obs-edge-label')).map(l => l.textContent.trim());
      return { fill, dash, hasGhostMain: !!ghostMain, stubs, obsEdges };
    });

    assert(player.hasGhostMain, 'scope-of-Player renders a ghost pill for main');
    assert(player.fill === SEPIA_RGB,
      `ghost of main keeps sepia fill, got "${player.fill}"`);
    assert(player.dash && player.dash !== 'none',
      `ghost of main stays dashed, got "${player.dash}"`);
    assert(!player.stubs.includes('total_attack'),
      `scope-of-Player must not pin Lane↔PlayableUnit obs onto PlayableUnit ghost, got stubs=${JSON.stringify(player.stubs)}`);
    assert(!player.stubs.includes('reflect_bonus'),
      `scope-of-Player must not pin reflect_bonus (Lane↔PlayableUnit) stub, got stubs=${JSON.stringify(player.stubs)}`);
    const sideEdge = player.obsEdges.some(s => s.toLowerCase().startsWith('side'));
    assert(sideEdge,
      `scope-of-Player renders Player→Lane.side as an obs edge, got labels ${JSON.stringify(player.obsEdges)}`);
    const sideStub = player.stubs.some(s => s.startsWith('side'));
    assert(!sideStub,
      `scope-of-Player suppresses the side(…) stub when the edge is drawn, got stubs=${JSON.stringify(player.stubs)}`);

    PASS(NAME, `ghost-main fill=sepia, Player edges=${JSON.stringify(player.obsEdges)}, stubs=${JSON.stringify(player.stubs)}`);
  } catch (e) {
    FAIL(NAME, e.message);
  } finally {
    await browser.close();
  }
})();
