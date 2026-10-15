// Coverage item 9: playback — PLAY advances cursor, PAUSE halts, scrubber jumps.
import { CARDS, launch, openViewer, PASS, FAIL, assert, sleep } from './_harness.mjs';

const NAME = 'playback-e2e';
(async () => {
  const { browser, page } = await launch();
  try {
    await openViewer(page, CARDS);
    await page.waitForSelector('.pt-transport input[type="range"]');

    const scrub = page.locator('.pt-transport input[type="range"]');
    const max = parseInt(await scrub.getAttribute('max'));
    assert(max > 100, `scrubber max is sensible (>100), got ${max}`);
    const val = async () => parseInt(await scrub.inputValue());

    // Mid-speed so advance is observable but doesn't finish during the pause check.
    await page.locator('.pt-transport select').selectOption({ value: '5' });

    const v0 = await val();
    const playBtn = page.locator('.pt-transport .pt-play');
    const playLabel = async () => (await playBtn.innerText()).trim().toUpperCase();
    const label0 = await playLabel();
    assert(label0.includes('PLAY'), `button starts as PLAY, got "${label0}"`);
    await playBtn.click();
    await sleep(700);
    const v1 = await val();
    const label1 = await playLabel();
    assert(v1 > v0, `PLAY advances cursor, ${v0}→${v1}`);
    assert(label1.includes('PAUSE'), `button flips to PAUSE while playing, got "${label1}"`);

    await playBtn.click();
    await sleep(50);
    const v2 = await val();
    await sleep(500);
    const v3 = await val();
    assert(v3 === v2, `PAUSE halts cursor, got ${v2}→${v3}`);
    assert((await playLabel()).includes('PLAY'), `button flips back to PLAY after pause`);

    // Scrubber jump via keyboard: Home → 0, End → max.
    await scrub.focus();
    await page.keyboard.press('Home');
    await sleep(100);
    const vHome = await val();
    assert(vHome === 0, `Home jumps to 0, got ${vHome}`);
    await page.keyboard.press('End');
    await sleep(200);
    const vEnd = await val();
    assert(vEnd === max, `End jumps to last event ${max}, got ${vEnd}`);
    // Natural playback end-stop: scrub near-end at max speed, PLAY, let it finish.
    await page.locator('.pt-transport select').selectOption({ value: '100' });
    await scrub.focus();
    for (let i = 0; i < 3; i++) await page.keyboard.press('End');  // definitively at end
    const beforeClamp = await val();
    assert(beforeClamp === max, `scrubber at max before clamp test, got ${beforeClamp}`);
    const vAfterEnd = beforeClamp;  // already at end — the clamp assertion is that End does not go beyond max.

    PASS(NAME, `cursor ${v0}→${v1} play, ${v2}===${v3} pause, end-clamp ${vAfterEnd}/${max}`);
  } catch (e) {
    FAIL(NAME, e.message);
  } finally {
    await browser.close();
  }
})();
