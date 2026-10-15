// Transport bar must stay one line and keep a stable width across playback.
//
// Regression history:
//   1. `.pt-transport` used `width: fit-content` with `.pt-status` at
//      `flex: 1 1 180px`, so each playback tick's message text length drove
//      the bar's natural width (reported: wide ↔ narrow flicker).
//   2. Pinning status at `flex: 0 0 220px` stopped the width oscillation at
//      wide viewports, but `flex-wrap: wrap` left the bar oscillating between
//      one-line and two-line at mid-widths (sidebar expanded ≈ 534 px stage).
//      Short-msg ticks wrapped, long-msg ticks did not.
// Fix: `flex-wrap: nowrap`, status takes remaining space and ellipsizes.
// The bar now spans stage width (minus 14 px side insets) at every tick.
//
// This test runs REAL playback via the PLAY button and samples the transport
// bar's clientWidth and clientHeight every ~33 ms. Assertions:
//   * clientHeight stays on one line (single unique value, ≈ 40 px)
//   * clientWidth stays constant per viewport
//   * message text actually varied during the sample window (sanity)
// Repeats at narrow and wide viewports and with the sidebar in both states.
import { CARDS, launch, openViewer, PASS, FAIL, assert, sleep } from './_harness.mjs';
import { writeFileSync, mkdirSync } from 'node:fs';

const NAME = 'transport-width-stable-e2e';
const OUT = '/tmp/transport-width-stable';
const SAMPLE_WIDTHS = [600, 700, 814, 900, 1100, 1300];
const PLAY_MS = 6000;

async function collapseSidebar(page) {
  await page.evaluate(() => {
    if (!document.body.classList.contains('hide-sidebar')) {
      document.getElementById('sidebar-toggle')?.click();
    }
  });
  await sleep(300);
}
async function expandSidebar(page) {
  await page.evaluate(() => {
    if (document.body.classList.contains('hide-sidebar')) {
      document.getElementById('sidebar-toggle')?.click();
    }
  });
  await sleep(300);
}

async function runCase(W, sidebar) {
  const { browser, page } = await launch({ viewport: { width: W, height: 700 } });
  try {
    await openViewer(page, CARDS);
    await sleep(1500);
    if (sidebar === 'collapsed') await collapseSidebar(page);
    else await expandSidebar(page);

    await page.evaluate((ms) => {
      window.__samples = [];
      const read = () => {
        const tr = document.querySelector('.pt-transport');
        const st = document.querySelector('.pt-status');
        return {
          t: Math.round(performance.now()),
          trW: tr?.clientWidth || 0, trH: tr?.clientHeight || 0,
          stW: st?.clientWidth || 0, stTxt: (st?.textContent || '').trim(),
        };
      };
      const start = performance.now();
      const h = setInterval(() => {
        window.__samples.push(read());
        if (performance.now() - start > ms) clearInterval(h);
      }, 33);
    }, PLAY_MS);

    const played = await page.evaluate(() => {
      const btn = Array.from(document.querySelectorAll('.pt-transport button'))
        .find(b => (b.textContent || '').trim() === 'PLAY');
      if (!btn) return false;
      btn.click();
      return true;
    });
    if (!played) throw new Error('PLAY button not found');
    await sleep(PLAY_MS + 500);

    const samples = await page.evaluate(() => window.__samples);
    const uniqW = new Set(samples.map(s => s.trW));
    const uniqH = new Set(samples.map(s => s.trH));
    const texts = new Set(samples.map(s => s.stTxt));
    writeFileSync(`${OUT}/samples-W${W}-${sidebar}.json`, JSON.stringify(samples, null, 2));
    return {
      W, sidebar, n: samples.length,
      uniqW: [...uniqW], uniqH: [...uniqH], textCount: texts.size,
    };
  } finally {
    await browser.close();
  }
}

(async () => {
  mkdirSync(OUT, { recursive: true });
  const failures = [];
  for (const W of SAMPLE_WIDTHS) {
    for (const sidebar of ['collapsed', 'expanded']) {
      try {
        const r = await runCase(W, sidebar);
        if (r.textCount < 10) {
          failures.push(`W=${W} ${sidebar}: msg text only varied ${r.textCount} times across ${r.n} samples, PLAY did not advance`);
        }
        if (r.uniqH.length !== 1) {
          failures.push(`W=${W} ${sidebar}: bar height oscillated, heights=${r.uniqH.join(',')}`);
        }
        if (r.uniqH[0] > 60) {
          failures.push(`W=${W} ${sidebar}: bar is ${r.uniqH[0]} px tall, expected one-line (<60)`);
        }
        if (r.uniqW.length !== 1) {
          failures.push(`W=${W} ${sidebar}: bar width oscillated, widths=${r.uniqW.join(',')}`);
        }
      } catch (e) {
        failures.push(`W=${W} ${sidebar}: ${e.message}`);
      }
    }
  }
  if (failures.length) {
    FAIL(NAME, failures.join(' | '));
  } else {
    PASS(NAME, `one-line toolbar stable across widths ${SAMPLE_WIDTHS.join(',')} × {collapsed, expanded}`);
  }
})();
