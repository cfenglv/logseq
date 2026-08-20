'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const DEFAULTS = Object.freeze({
  port: 9339,
  rounds: 3,
  plan: 'formal',
  traceMode: 'optional',
  syncMode: 'required',
  output: '/private/tmp/selfhost6-editor-benchmark.json',
  expectedRepo: 'logseq_db_unitary-forge-test6',
  expectedGraphPath: '/private/tmp/logseq-rtc-perf6-valid-cloud.lShKOk/graphs/unitary-forge-test6',
  expectedServerUrl: 'http://127.0.0.1:18788',
  expectedVersion: '2.0.1-selfhost.6',
  syncConfig: '/private/tmp/logseq-selfhost6-test6-final.x8dQEu/recovery-root/cli.edn',
  syncCli: path.resolve(__dirname, '..', '..', 'static', 'logseq-cli.js'),
  graphName: 'unitary-forge-test6',
  expectedWorkerBundleFragment: 'LogseqSelfhost6ReliabilityTest.app/Contents/Resources/app.asar/js/db-worker-node.js',
  expectedAppSourceSha: null,
  instrumentationPatchSha: null,
});

function parsePositiveInteger(value, flag) {
  const parsed = Number.parseInt(value, 10);
  if (!Number.isInteger(parsed) || parsed <= 0) {
    throw new Error(`${flag} must be a positive integer`);
  }
  return parsed;
}

function parseArgs(argv) {
  const result = { ...DEFAULTS };
  for (let index = 0; index < argv.length; index += 1) {
    const flag = argv[index];
    if (flag === '--help' || flag === '-h') return { ...result, help: true };
    const value = argv[index + 1];
    if (flag === '--port') result.port = parsePositiveInteger(value, flag);
    else if (flag === '--rounds') result.rounds = parsePositiveInteger(value, flag);
    else if (flag === '--output') result.output = value;
    else if (flag === '--plan') {
      if (!['formal', 'hashtag-probe'].includes(value)) throw new Error('--plan must be formal or hashtag-probe');
      result.plan = value;
    }
    else if (flag === '--trace-mode') {
      if (!['required', 'optional', 'off'].includes(value)) throw new Error('--trace-mode must be required, optional, or off');
      result.traceMode = value;
    }
    else if (flag === '--sync-mode') {
      if (!['required', 'off'].includes(value)) throw new Error('--sync-mode must be required or off');
      result.syncMode = value;
    }
    else if (flag === '--expected-repo') result.expectedRepo = value;
    else if (flag === '--expected-graph-path') result.expectedGraphPath = value;
    else if (flag === '--expected-server-url') result.expectedServerUrl = value;
    else if (flag === '--expected-version') result.expectedVersion = value;
    else if (flag === '--sync-config') result.syncConfig = value;
    else if (flag === '--sync-cli') result.syncCli = value;
    else if (flag === '--graph-name') result.graphName = value;
    else if (flag === '--expected-worker-bundle-fragment') result.expectedWorkerBundleFragment = value;
    else if (flag === '--expected-app-source-sha') {
      if (!/^[0-9a-f]{40}$/.test(value)) throw new Error('--expected-app-source-sha must be 40 lowercase hex characters');
      result.expectedAppSourceSha = value;
    }
    else if (flag === '--instrumentation-patch-sha') {
      if (!/^[0-9a-f]{64}$/.test(value)) throw new Error('--instrumentation-patch-sha must be 64 lowercase hex characters');
      result.instrumentationPatchSha = value;
    }
    else throw new Error(`unknown argument: ${flag}`);
    if (value == null || value === '') throw new Error(`${flag} requires a value`);
    index += 1;
  }
  return result;
}

function printableSteps(text, phase, delayMs) {
  return [...text].map((key) => ({ action: 'char', key, phase, delayMs }));
}

function blockSteps(text, phase, charDelayMs, enterDelayMs) {
  return [
    ...printableSteps(text, phase, charDelayMs),
    { action: 'enter', key: 'Enter', phase, delayMs: enterDelayMs },
  ];
}

function hashtagSteps(index) {
  const phase = 'hashtag';
  return [
    ...printableSteps(`tag-${String(index).padStart(2, '0')} `, phase, 35),
    { action: 'hashtag-popup', key: '#', phase, delayMs: 0 },
    ...printableSteps('perf-tag-alpha', phase, 30),
    { action: 'hashtag-select', key: 'Enter', phase, delayMs: 40 },
    { action: 'enter', key: 'Enter', phase, delayMs: 100 },
  ];
}

function buildBenchmarkPlan() {
  const steps = [];
  for (let index = 0; index < 5; index += 1) {
    steps.push(...blockSteps(`warmup-${String(index).padStart(2, '0')}`, 'warmup', 45, 180));
  }
  for (let index = 0; index < 10; index += 1) {
    steps.push(...blockSteps(`normal-${String(index).padStart(2, '0')}`, 'normal', 80, 900));
  }
  for (let index = 0; index < 20; index += 1) {
    steps.push(...blockSteps(`burst-${String(index).padStart(2, '0')}`, 'burst', 15, 35));
  }
  for (let index = 0; index < 10; index += 1) {
    steps.push(...hashtagSteps(index));
  }
  for (let index = 0; index < 18; index += 1) {
    steps.push(...blockSteps(`mix-${String(index).padStart(2, '0')}`, 'mixed', 12, 25));
    if (index % 3 === 0) steps.push(...hashtagSteps(100 + index).map((step) => ({ ...step, phase: 'mixed' })));
  }
  return steps;
}

function buildHashtagProbePlan() {
  const steps = [];
  for (let index = 0; index < 3; index += 1) {
    steps.push(...printableSteps(`probe-${index} `, 'hashtag-probe', 35));
    steps.push({ action: 'hashtag-popup', key: '#', phase: 'hashtag-probe', delayMs: 0 });
    steps.push({ action: 'hashtag-query-popup', key: 'p', phase: 'hashtag-probe', delayMs: 40 });
    steps.push({ action: 'hashtag-select', key: 'Enter', phase: 'hashtag-probe', delayMs: 40 });
    steps.push({ action: 'enter', key: 'Enter', phase: 'hashtag-probe', delayMs: 100 });
  }
  return steps;
}

function percentile(values, quantile) {
  if (!Array.isArray(values) || values.length === 0) return null;
  const sorted = [...values].sort((left, right) => left - right);
  const index = Math.min(sorted.length - 1, Math.max(0, Math.ceil(quantile * sorted.length) - 1));
  return sorted[index];
}

function summarizeMeasurements(measurements) {
  const groups = new Map();
  for (const measurement of measurements) {
    const key = `${measurement.phase}:${measurement.action}`;
    const group = groups.get(key) || [];
    group.push(measurement);
    groups.set(key, group);
  }
  return Object.fromEntries(
    [...groups.entries()].sort(([left], [right]) => left.localeCompare(right)).map(([key, group]) => {
      const durations = group.map((entry) => entry.duration).filter(Number.isFinite);
      return [key, {
        count: group.length,
        completed: durations.length,
        timeouts: group.filter((entry) => entry.timeout).length,
        p50Ms: percentile(durations, 0.5),
        p95Ms: percentile(durations, 0.95),
        maxMs: durations.length ? Math.max(...durations) : null,
      }];
    }),
  );
}

function expectedEditorLengthAfter(step, expectedBefore, actualAfter) {
  if (['char', 'hashtag-popup', 'hashtag-query-popup'].includes(step.action)) return expectedBefore + 1;
  if (step.action === 'enter') return 0;
  return actualAfter;
}

function summarizeIntegrity(measurements) {
  const checks = measurements.filter((entry) => entry.integrity);
  return {
    checks: checks.length,
    preStepMismatches: checks.filter((entry) => !entry.integrity.beforeMatches).length,
    postStepMismatches: checks.filter((entry) => !entry.integrity.afterMatches).length,
    firstPreStepMismatch: checks.find((entry) => !entry.integrity.beforeMatches)?.integrity ?? null,
    firstPostStepMismatch: checks.find((entry) => !entry.integrity.afterMatches)?.integrity ?? null,
  };
}

function validateWorkerIdentity(lock, command, options) {
  const expectedRoot = require('node:path').dirname(require('node:path').dirname(options.expectedGraphPath));
  const expectedRepoArg = `--repo ${options.expectedRepo}`;
  const expectedRootArg = `--root-dir ${expectedRoot}`;
  const expectedBundle = options.expectedWorkerBundleFragment;
  if (typeof expectedBundle !== 'string' || expectedBundle.length === 0) {
    throw new Error('expected worker bundle fragment is required');
  }
  if (!lock || lock.repo !== options.expectedRepo || lock['owner-source'] !== 'electron') {
    throw new Error('isolated Electron worker lock identity mismatch');
  }
  if (!Number.isInteger(lock.pid) || lock.pid <= 0) {
    throw new Error('isolated Electron worker lock has invalid pid');
  }
  for (const required of [expectedRepoArg, expectedRootArg, expectedBundle]) {
    if (!command.includes(required)) throw new Error(`isolated worker command is missing ${required}`);
  }
  return { pid: lock.pid, repo: lock.repo, ownerSource: lock['owner-source'], rootDir: expectedRoot };
}

function validateSyncCliIdentity(syncCli, expectedAppSourceSha) {
  const resolved = path.resolve(syncCli);
  const bytes = fs.readFileSync(resolved);
  if (!bytes.includes(Buffer.from(expectedAppSourceSha.slice(0, 10)))) {
    throw new Error('sync CLI build identity does not match the expected App source');
  }
  return {
    path: resolved,
    sha256: crypto.createHash('sha256').update(bytes).digest('hex'),
  };
}

function isSyncReady(status) {
  return !!(
    status &&
    status['last-error'] == null &&
    status['ws-state'] === 'open' &&
    status['pending-local'] === 0 &&
    status['pending-asset'] === 0 &&
    status['pending-server'] === 0 &&
    Number.isInteger(status['local-tx']) &&
    status['local-tx'] === status['remote-tx'] &&
    typeof status['local-checksum'] === 'string' &&
    status['local-checksum'] === status['remote-checksum']
  );
}

function isRendererVisible(state) {
  return !!(
    state &&
    state.hidden === false &&
    state.visibilityState === 'visible'
  );
}

function traceBuildCommits(captured) {
  const commits = new Set();
  for (const processTrace of Object.values(captured?.trace || {})) {
    for (const event of processTrace?.events || []) {
      if (typeof event['build-commit'] === 'string') commits.add(event['build-commit']);
    }
  }
  return [...commits].sort();
}

module.exports = {
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
};
