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

export async function launch(opts = {}) {
  const browser = await pw.chromium.launch({ headless: true, ...opts });
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

export function PASS(name, extra) {
  const tail = extra ? ' · ' + extra : '';
  console.log(`${name}: PASSED${tail}`);
  process.exit(0);
}

export function FAIL(name, reason) {
  console.error(`${name}: FAILED: ${reason}`);
  process.exit(1);
}

// Sidecar snapshot / restore. Call BEFORE edits, RESTORE in a try/finally.
export function sidecarPath(stem) {
  return presolve(RESEARCH, `${stem}.annotations.json`);
}
export function snapshotSidecar(stem) {
  const sp = sidecarPath(stem);
  const backup = `${sp}.e2ebak`;
  const existed = existsSync(sp);
  if (existed) copyFileSync(sp, backup);
  const snap = { sp, backup, existed };
  // Guarantee restore even when PASS/FAIL exits early.
  const cleanup = () => { try { restoreSidecar(snap); } catch (_) {} };
  process.once('exit', cleanup);
  process.once('SIGINT', () => { cleanup(); process.exit(130); });
  process.once('SIGTERM', () => { cleanup(); process.exit(143); });
  return snap;
}
export function restoreSidecar({ sp, backup, existed }) {
  if (existed && existsSync(backup)) {
    copyFileSync(backup, sp);
    unlinkSync(backup);
  } else if (!existed && existsSync(sp)) {
    unlinkSync(sp);
  }
}

export function readSidecar(stem) {
  const sp = sidecarPath(stem);
  if (!existsSync(sp)) return null;
  return JSON.parse(readFileSync(sp, 'utf8'));
}

export const sleep = (ms) => new Promise(r => setTimeout(r, ms));

export function assert(cond, msg) { if (!cond) throw new Error('assert: ' + msg); }
