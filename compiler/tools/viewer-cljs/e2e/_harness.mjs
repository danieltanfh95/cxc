// Shared playwright harness for the viewer-v2 e2e suite.
//
// Resolves playwright from either a node_modules neighbour or the local
// codex-runtimes cache; both carry the same package. No deps.edn change needed.
//
// Each test file imports: launch(), SAMPLE, CARDS, PASS(), FAIL().
//
// SIDECAR_* helpers snapshot and restore annotation sidecars so a failing or
// interrupted test does not leak state into the next run.

import { createRequire } from 'node:module';
import { readFileSync, writeFileSync, existsSync, copyFileSync, unlinkSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve as presolve } from 'node:path';

const require = createRequire(import.meta.url);
const CANDIDATES = [
  '/Users/danieltan/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright',
  'playwright',
];
let pw = null, lastErr = null;
for (const spec of CANDIDATES) {
  try { pw = require(spec); break; } catch (e) { lastErr = e; }
}
if (!pw) { console.error('playwright import failed:', lastErr?.message); process.exit(2); }

export const PW   = pw;
export const BASE = process.env.VIEWER_BASE || 'http://127.0.0.1:8769';
export const SAMPLE = `${BASE}/trace-viewer.html?trace=sample-01-single-atom.trace.jsonl&shape=sample-01-single-atom.shape.json`;
export const CARDS  = `${BASE}/trace-viewer.html?trace=cards.trace.jsonl&shape=cards.shape.json`;

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = presolve(HERE, '../../../..');
export const RESEARCH = presolve(REPO, 'compiler/research');

// H2-04: launch owns every browser, including secondary/partially opened ones.
const browsers = new Set();

export async function launch(opts = {}) {
  const browser = await pw.chromium.launch({ headless: true, ...opts });
  browsers.add(browser);
  browser.once('disconnected', () => browsers.delete(browser));
  const context = await browser.newContext({
    viewport: opts.viewport || { width: 1264, height: 900 },
    deviceScaleFactor: opts.deviceScaleFactor || 1,
  });
  const page = await context.newPage();
  page.on('pageerror', e => console.error('[page-error]', e.message));
  return { browser, context, page };
}

export async function openViewer(page, url) {
  await page.goto(url, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.annotations', { timeout: 10000 });
  // Shape renders after an ELK pass; wait for a block.
  await page.waitForSelector('#shape-container svg .shape-block', { timeout: 10000 });
}

// H2-04: settle resource lifetimes before reporting the outcome.
async function cleanup() {
  const errors = [];
  const closed = await Promise.allSettled([...browsers].map(browser => browser.close()));
  for (const result of closed) if (result.status === 'rejected') errors.push(result.reason);
  for (const snap of snapshots) {
    try { restoreSidecar(snap); } catch (error) { errors.push(error); }
  }
  if (errors.length) throw new AggregateError(errors, errors.map(e => e.message).join('; '));
}

async function finish(name, reason, extra) {
  try { await cleanup(); } catch (error) { reason = [reason, 'cleanup: ' + error.message].filter(Boolean).join('; '); }
  if (reason !== null) { // H2-04: FAIL remains failure even with an empty message.
    console.error(`${name}: FAILED: ${reason}`);
    process.exitCode = 1;
  } else {
    console.log(`${name}: PASSED${extra ? ' · ' + extra : ''}`);
    process.exitCode ||= 0;
  }
}

export function PASS(name, extra) { return finish(name, null, extra); }
export function FAIL(name, reason) { return finish(name, String(reason)); }

// H2-05: a snapshot owns an exclusive backup of the original bytes/absence.
const snapshots = new Set();

export function sidecarPath(stem) {
  return presolve(RESEARCH, `${stem}.annotations.json`);
}
export function snapshotSidecar(stem) {
  const sp = sidecarPath(stem);
  const backup = `${sp}.e2ebak`;
  const existed = existsSync(sp);
  writeFileSync(backup, existed ? readFileSync(sp) : '', { flag: 'wx' });
  const snap = { sp, backup, existed, restored: false };
  snapshots.add(snap);
  return snap;
}
export function restoreSidecar(snap) {
  // H2-05: repeated cleanup is harmless; losing an owned backup is an error.
  if (snap.restored) return;
  const { sp, backup, existed } = snap;
  if (!existsSync(backup)) throw new Error(`missing sidecar backup: ${backup}`);
  if (existed) {
    copyFileSync(backup, sp);
  } else if (existsSync(sp)) {
    unlinkSync(sp);
  }
  unlinkSync(backup);
  snap.restored = true;
  snapshots.delete(snap);
}

// H2-04: interrupted and uncaught outcomes use the same resource owner.
for (const [signal, code] of [['SIGINT', 130], ['SIGTERM', 143]]) {
  process.once(signal, async () => {
    try { await cleanup(); process.exit(code); }
    catch (error) { console.error(`e2e cleanup: FAILED: ${error.message}`); process.exit(1); }
  });
}
process.once('uncaughtException', error => { void FAIL('e2e harness', error.message); });

// H2-05: last-resort synchronous rollback reports failure, never silently skips.
process.once('exit', () => {
  for (const snap of snapshots) {
    try { restoreSidecar(snap); }
    catch (error) { console.error(`e2e rollback: FAILED: ${error.message}`); process.exitCode = 1; }
  }
});

export function readSidecar(stem) {
  const sp = sidecarPath(stem);
  if (!existsSync(sp)) return null;
  return JSON.parse(readFileSync(sp, 'utf8'));
}

export const sleep = (ms) => new Promise(r => setTimeout(r, ms));

export function assert(cond, msg) { if (!cond) throw new Error('assert: ' + msg); }
