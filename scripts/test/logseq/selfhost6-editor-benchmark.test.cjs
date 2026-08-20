'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {
  DEFAULTS,
  buildBenchmarkPlan,
  buildHashtagProbePlan,
  expectedEditorLengthAfter,
  parseArgs,
  percentile,
  summarizeMeasurements,
  summarizeIntegrity,
  validateWorkerIdentity,
  validateSyncCliIdentity,
  isSyncReady,
  isRendererVisible,
  traceBuildCommits,
} = require('../../lib/selfhost6-editor-benchmark.cjs');

test('benchmark plan is deterministic and covers the required editor actions', () => {
  const first = buildBenchmarkPlan();
  const second = buildBenchmarkPlan();
  assert.deepEqual(first, second);
  assert.ok(first.length > 500);
  assert.deepEqual(new Set(first.map((step) => step.phase)), new Set(['warmup', 'normal', 'burst', 'hashtag', 'mixed']));
  assert.deepEqual(new Set(first.map((step) => step.action)), new Set(['char', 'enter', 'hashtag-popup', 'hashtag-select']));
  assert.equal(first.filter((step) => step.action === 'hashtag-popup').length, 16);
  const probe = buildHashtagProbePlan();
  assert.equal(probe.filter((step) => step.action === 'hashtag-popup').length, 3);
  assert.equal(probe.filter((step) => step.action === 'hashtag-query-popup').length, 3);
});

test('argument parser defaults to the synthetic Test6 allowlist', () => {
  assert.deepEqual(parseArgs([]), DEFAULTS);
  assert.equal(parseArgs(['--help']).help, true);
  assert.equal(parseArgs(['-h']).help, true);
  assert.equal(parseArgs(['--rounds', '5', '--port', '9444']).rounds, 5);
  assert.equal(parseArgs(['--rounds', '5', '--port', '9444']).port, 9444);
  assert.equal(parseArgs(['--plan', 'hashtag-probe']).plan, 'hashtag-probe');
  assert.equal(parseArgs(['--trace-mode', 'required']).traceMode, 'required');
  assert.equal(parseArgs(['--trace-mode', 'off']).traceMode, 'off');
  assert.equal(parseArgs(['--sync-mode', 'off']).syncMode, 'off');
  assert.equal(parseArgs(['--sync-cli', '/private/tmp/official-cli.js']).syncCli, '/private/tmp/official-cli.js');
  assert.throws(() => parseArgs(['--plan', 'invalid']), /formal or hashtag-probe/);
  assert.throws(() => parseArgs(['--trace-mode', 'invalid']), /required, optional, or off/);
  assert.throws(() => parseArgs(['--sync-mode', 'optional']), /required or off/);
  assert.throws(() => parseArgs(['--instrumentation-patch-sha', 'short']), /64 lowercase hex/);
  assert.throws(() => parseArgs(['--expected-app-source-sha', 'short']), /40 lowercase hex/);
  assert.throws(() => parseArgs(['--rounds', '0']), /positive integer/);
  assert.throws(() => parseArgs(['--unknown', 'x']), /unknown argument/);
});

test('sync CLI identity is bound to the App build under test', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'selfhost6-sync-cli-'));
  const cli = path.join(directory, 'logseq-cli.js');
  const source = 'a'.repeat(40);
  fs.writeFileSync(cli, `build revision ${source.slice(0, 10)}`);
  const identity = validateSyncCliIdentity(cli, source);
  assert.equal(identity.path, cli);
  assert.match(identity.sha256, /^[0-9a-f]{64}$/);
  assert.throws(() => validateSyncCliIdentity(cli, 'b'.repeat(40)), /build identity/);
});

test('percentile and summaries remain deterministic and count timeouts', () => {
  assert.equal(percentile([5, 1, 4, 3, 2], 0.5), 3);
  assert.equal(percentile([5, 1, 4, 3, 2], 0.95), 5);
  assert.equal(percentile([], 0.95), null);
  assert.deepEqual(summarizeMeasurements([
    { phase: 'normal', action: 'enter', duration: 10, timeout: false },
    { phase: 'normal', action: 'enter', duration: 30, timeout: false },
    { phase: 'normal', action: 'enter', duration: null, timeout: true },
  ]), {
    'normal:enter': { count: 3, completed: 2, timeouts: 1, p50Ms: 10, p95Ms: 30, maxMs: 30 },
  });
});

test('content-free editor length oracle detects delayed rollback', () => {
  assert.equal(expectedEditorLengthAfter({ action: 'char' }, 4, 5), 5);
  assert.equal(expectedEditorLengthAfter({ action: 'hashtag-popup' }, 5, 6), 6);
  assert.equal(expectedEditorLengthAfter({ action: 'enter' }, 6, 0), 0);
  assert.equal(expectedEditorLengthAfter({ action: 'hashtag-select' }, 6, 3), 3);
  assert.deepEqual(summarizeIntegrity([
    { integrity: { beforeMatches: true, afterMatches: true } },
    { integrity: { beforeMatches: false, afterMatches: false, phase: 'burst', action: 'char' } },
  ]), {
    checks: 2,
    preStepMismatches: 1,
    postStepMismatches: 1,
    firstPreStepMismatch: { beforeMatches: false, afterMatches: false, phase: 'burst', action: 'char' },
    firstPostStepMismatch: { beforeMatches: false, afterMatches: false, phase: 'burst', action: 'char' },
  });
});

test('worker identity requires the isolated root, repo, owner, and dedicated bundle', () => {
  const options = { ...DEFAULTS };
  const lock = { repo: DEFAULTS.expectedRepo, pid: 123, 'owner-source': 'electron' };
  const command = [
    '/private/tmp/LogseqSelfhost6ReliabilityTest.app/Contents/Resources/app.asar/js/db-worker-node.js',
    `--repo ${DEFAULTS.expectedRepo}`,
    '--root-dir /private/tmp/logseq-rtc-perf6-valid-cloud.lShKOk',
  ].join(' ');
  assert.deepEqual(validateWorkerIdentity(lock, command, options), {
    pid: 123,
    repo: DEFAULTS.expectedRepo,
    ownerSource: 'electron',
    rootDir: '/private/tmp/logseq-rtc-perf6-valid-cloud.lShKOk',
  });
  assert.throws(() => validateWorkerIdentity({ ...lock, 'owner-source': 'cli' }, command, options), /lock identity/);
  assert.throws(() => validateWorkerIdentity(lock, command.replace('/private/tmp/logseq-rtc-perf6-valid-cloud.lShKOk', '/Users/example/logseq'), options), /missing --root-dir/);
  assert.deepEqual(
    validateWorkerIdentity(lock, command.replace(
      'LogseqSelfhost6ReliabilityTest.app',
      'LogseqOfficialBaseControl.app',
    ), { ...options, expectedWorkerBundleFragment: 'LogseqOfficialBaseControl.app/Contents/Resources/app.asar/js/db-worker-node.js' }),
    {
      pid: 123,
      repo: DEFAULTS.expectedRepo,
      ownerSource: 'electron',
      rootDir: '/private/tmp/logseq-rtc-perf6-valid-cloud.lShKOk',
    },
  );
});

test('RTC health gate requires open, empty queues, and equal authoritative basis', () => {
  const ready = {
    'last-error': null,
    'ws-state': 'open',
    'pending-local': 0,
    'pending-asset': 0,
    'pending-server': 0,
    'local-tx': 42,
    'remote-tx': 42,
    'local-checksum': '0123456789abcdef',
    'remote-checksum': '0123456789abcdef',
  };
  assert.equal(isSyncReady(ready), true);
  for (const mutation of [
    { 'ws-state': 'stopped' },
    { 'pending-local': 1 },
    { 'remote-tx': 41 },
    { 'remote-checksum': 'fedcba9876543210' },
    { 'last-error': { code: 'synthetic' } },
  ]) {
    assert.equal(isSyncReady({ ...ready, ...mutation }), false);
  }
});

test('renderer visibility gate rejects background or locked-page samples', () => {
  assert.equal(isRendererVisible({ hidden: false, visibilityState: 'visible' }), true);
  assert.equal(isRendererVisible({ hidden: true, visibilityState: 'hidden' }), false);
  assert.equal(isRendererVisible({ hidden: false, visibilityState: 'prerender' }), false);
});

test('trace build identity is collected across renderer and worker envelopes', () => {
  assert.deepEqual(traceBuildCommits({
    trace: {
      renderer: { events: [{ 'build-commit': 'a'.repeat(40) }, { kind: 'span' }] },
      worker: { events: [{ 'build-commit': 'a'.repeat(40) }] },
    },
  }), ['a'.repeat(40)]);
  assert.deepEqual(traceBuildCommits({ trace: null }), []);
});
