// Coverage item 11: theme toggle flips body class and persists in localStorage.
import { CARDS, BASE, launch, openViewer, PASS, FAIL, assert, sleep } from './_harness.mjs';

const NAME = 'theme-e2e';
(async () => {
  const { browser, page } = await launch();
  try {
    await openViewer(page, CARDS);
    const theme = () => page.evaluate(() => ({
      hasDark: document.body.classList.contains('theme-dark'),
      stored: localStorage.getItem('cxc-theme'),
    }));
    const t0 = await theme();
    await page.locator('#theme-toggle').click();
    await sleep(200);
    const t1 = await theme();
    assert(t0.hasDark !== t1.hasDark,
      `body theme class flipped, before=${JSON.stringify(t0)} after=${JSON.stringify(t1)}`);
    assert(t1.stored !== null, `theme choice persisted (cxc-theme=${t1.stored})`);

    // Reload with the stored value — the init script sets it before load.
    const ctx = await browser.newContext({ viewport: { width: 1264, height: 900 } });
    await ctx.addInitScript(v => localStorage.setItem('cxc-theme', v), t1.stored);
    const p2 = await ctx.newPage();
    await p2.goto(CARDS);
    await p2.waitForSelector('.annotations');
    await sleep(200);
    const t2 = await p2.evaluate(() => document.body.classList.contains('theme-dark'));
    assert(t2 === t1.hasDark, `theme survives reload, after-reload=${t2}`);

    PASS(NAME, `flipped dark=${t0.hasDark}→${t1.hasDark}, persisted (${t1.stored})`);
  } catch (e) {
    FAIL(NAME, e.message);
  } finally {
    await browser.close();
  }
})();
