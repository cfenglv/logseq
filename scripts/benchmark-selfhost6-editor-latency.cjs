#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const {
  buildBenchmarkPlan,
  buildHashtagProbePlan,
  expectedEditorLengthAfter,
  parseArgs,
  summarizeMeasurements,
  summarizeIntegrity,
  validateWorkerIdentity,
  validateSyncCliIdentity,
  isSyncReady,
  isRendererVisible,
  traceBuildCommits,
} = require('./lib/selfhost6-editor-benchmark.cjs');

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function usage() {
  return [
    'Usage: node scripts/benchmark-selfhost6-editor-latency.cjs [options]',
    '',
    'The runner drives real Chromium key events through a localhost-only CDP endpoint.',
    'It refuses to run unless the synthetic repo, graph path, server URL, app version,',
    'and selected trace/sync modes all match the explicit allowlist.',
    '',
    'Options:',
    '  --port <n>                 CDP port (default: 9339)',
    '  --rounds <n>               Deterministic repetitions (default: 3)',
    '  --trace-mode <mode>        required, optional, or off (default: optional)',
    '  --sync-mode <mode>         required or off (default: required)',
    '  --output <path>            Content-free JSON output',
    '  --expected-repo <repo>     Required synthetic repo',
    '  --expected-graph-path <p>  Required isolated graph path',
    '  --expected-server-url <u>  Required loopback RTC URL',
    '  --expected-version <v>     Required app version',
    '  --sync-config <path>       Synthetic CLI config used for RTC health gates',
    '  --sync-cli <path>          CLI bundle built from the App source under test',
    '  --graph-name <name>        Synthetic graph name used for RTC health gates',
    '  --expected-worker-bundle-fragment <s>  Required worker command fragment',
    '  --expected-app-source-sha <sha>         Exact 40-hex source identity for this build',
    '  --instrumentation-patch-sha <sha256>   Optional trace instrumentation identity',
  ].join('\n');
}

function addListener(ws, event, handler) {
  if (typeof ws.addEventListener === 'function') ws.addEventListener(event, handler);
  else ws.on(event, (...args) => handler(event === 'message' ? { data: args[0].toString() } : args[0]));
}

function createClient(ws) {
  let nextId = 0;
  const pending = new Map();
  addListener(ws, 'message', ({ data }) => {
    const message = JSON.parse(data);
    const request = pending.get(message.id);
    if (!request) return;
    pending.delete(message.id);
    clearTimeout(request.timeout);
    if (message.error) request.reject(new Error(`${request.method}: ${message.error.message}`));
    else request.resolve(message.result);
  });
  return {
    send(method, params = {}, timeoutMs = 30_000) {
      const id = ++nextId;
      return new Promise((resolve, reject) => {
        const timeout = setTimeout(() => {
          pending.delete(id);
          reject(new Error(`CDP timeout: ${method}`));
        }, timeoutMs);
        pending.set(id, { method, reject, resolve, timeout });
        ws.send(JSON.stringify({ id, method, params }));
      });
    },
  };
}

async function evaluate(cdp, expression) {
  const response = await cdp.send('Runtime.evaluate', {
    expression,
    awaitPromise: true,
    returnByValue: true,
  });
  if (response.exceptionDetails) {
    const detail = response.exceptionDetails.exception?.description || response.exceptionDetails.text;
    throw new Error(detail || 'renderer evaluation failed');
  }
  return response.result?.value;
}

async function connect(port) {
  const deadline = Date.now() + 30_000;
  let lastError;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/json/list`);
      const targets = await response.json();
      for (const target of targets) {
        if (target.type !== 'page' || !target.webSocketDebuggerUrl) continue;
        const ws = new WebSocket(target.webSocketDebuggerUrl);
        await new Promise((resolve, reject) => {
          addListener(ws, 'open', resolve);
          addListener(ws, 'error', reject);
        });
        const cdp = createClient(ws);
        await cdp.send('Runtime.enable');
        const rendererReady = await evaluate(cdp,
          '!!(globalThis.logseq?.api && typeof globalThis.logseq.api.get_current_graph === "function")');
        if (rendererReady) return { cdp, target, ws };
        ws.close();
      }
    } catch (error) {
      lastError = error;
    }
    await sleep(250);
  }
  throw new Error(`benchmark renderer unavailable: ${lastError?.message || 'no target'}`);
}

function measurementSource() {
  return `(() => {
    const previous = globalThis.__SELFHOST6_AUTO_BENCH__;
    if (previous?.cleanup) previous.cleanup();
    const state = {
      startedAt: performance.now(),
      token: 0,
      armed: null,
      measurements: [],
      longTasks: [],
      events: [],
      observers: [],
    };
    const visible = (element) => !!(element?.isConnected && element.getClientRects().length);
    const popup = () => document.querySelector('[data-editor-popup-ref="page-search-hashtag"]');
    const activeEditor = () => {
      const element = document.activeElement;
      return element?.id?.startsWith('edit-block-') ? element : null;
    };
    const finish = (armed, duration, timeout = false) => {
      if (!armed || armed.finished) return;
      armed.finished = true;
      state.measurements.push({
        token: armed.token,
        phase: armed.phase,
        action: armed.action,
        duration,
        timeout,
        diagnostic: armed.diagnostic || null,
      });
    };
    const poll = (armed, predicate, startedAt, timeoutMs) => {
      const tick = () => {
        if (predicate()) finish(armed, performance.now() - startedAt, false);
        else if (performance.now() - startedAt >= timeoutMs) {
          if (armed.action === 'hashtag-popup' || armed.action === 'hashtag-query-popup') {
            const editor = activeEditor();
            const nodes = [...document.querySelectorAll('[data-editor-popup-ref]')];
            armed.diagnostic = {
              activeEditorPresent: !!editor,
              editorValueLength: editor?.value?.length ?? null,
              editorEndsWithHash: editor?.value?.endsWith('#') ?? null,
              popupNodeCount: nodes.length,
              visiblePopupNodeCount: nodes.filter(visible).length,
            };
          }
          finish(armed, null, true);
        }
        else requestAnimationFrame(tick);
      };
      requestAnimationFrame(tick);
    };
    const onKeyDown = (event) => {
      const armed = state.armed;
      if (!armed || armed.finished || armed.started) return;
      armed.started = true;
      const startedAt = performance.now();
      const beforeEditor = activeEditor();
      const beforeId = beforeEditor?.id || null;
      const beforeValue = beforeEditor?.value;
      if (armed.action === 'char') {
        const onInput = () => {
          beforeEditor?.removeEventListener('input', onInput);
          finish(armed, performance.now() - startedAt, false);
        };
        beforeEditor?.addEventListener('input', onInput, { once: true });
        setTimeout(() => {
          beforeEditor?.removeEventListener('input', onInput);
          if (!armed.finished) finish(armed, null, true);
        }, 2000);
      } else if (armed.action === 'enter') {
        poll(armed, () => {
          const current = activeEditor();
          return !!(current && current.id !== beforeId);
        }, startedAt, 5000);
      } else if (armed.action === 'hashtag-popup' || armed.action === 'hashtag-query-popup') {
        poll(armed, () => visible(popup()), startedAt, 3000);
      } else if (armed.action === 'hashtag-select') {
        poll(armed, () => !visible(popup()), startedAt, 3000);
      } else {
        finish(armed, beforeValue === beforeEditor?.value ? 0 : null, false);
      }
    };
    document.addEventListener('keydown', onKeyDown, true);
    for (const type of ['longtask', 'event']) {
      try {
        const observer = new PerformanceObserver((list) => {
          for (const entry of list.getEntries()) {
            if (entry.startTime < state.startedAt) continue;
            const target = type === 'longtask' ? state.longTasks : state.events;
            if (target.length >= 4096) continue;
            target.push(type === 'longtask'
              ? { name: entry.name, startTime: entry.startTime, duration: entry.duration }
              : { name: entry.name, startTime: entry.startTime, duration: entry.duration, interactionId: entry.interactionId });
          }
        });
        observer.observe(type === 'longtask'
          ? { type, buffered: true }
          : { type, buffered: true, durationThreshold: 16 });
        state.observers.push(observer);
      } catch (_) {}
    }
    state.arm = (phase, action) => {
      const armed = { token: ++state.token, phase, action, started: false, finished: false };
      state.armed = armed;
      setTimeout(() => {
        if (armed.started || armed.finished) return;
        const editor = activeEditor();
        armed.diagnostic = {
          keydownObserved: false,
          activeEditorPresent: !!editor,
          editorValueLength: editor?.value?.length ?? null,
          popupNodeCount: document.querySelectorAll('[data-editor-popup-ref]').length,
        };
        finish(armed, null, true);
      }, 2000);
      return armed.token;
    };
    state.take = (token) => {
      const index = state.measurements.findIndex((entry) => entry.token === token);
      if (index < 0) return null;
      return state.measurements.splice(index, 1)[0];
    };
    state.cleanup = () => {
      document.removeEventListener('keydown', onKeyDown, true);
      for (const observer of state.observers) observer.disconnect();
    };
    globalThis.__SELFHOST6_AUTO_BENCH__ = state;
    return { installed: true, observers: state.observers.length };
  })()`;
}

async function preflight(cdp, options) {
  const actual = await evaluate(cdp, `(async () => {
    const graph = await globalThis.logseq?.api?.get_current_graph?.();
    const appInfo = await globalThis.logseq?.api?.get_app_info?.();
    return {
      traceApi: !!globalThis.__LOGSEQ_SELFHOST_PERF__,
      repo: graph?.url ?? null,
      graphPath: graph?.path ?? null,
      version: appInfo?.version ?? null,
      serverUrl: localStorage.getItem('sync-server-url'),
      hidden: document.hidden,
      visibilityState: document.visibilityState,
    };
  })()`);
  const expected = {
    repo: options.expectedRepo,
    version: options.expectedVersion,
  };
  if (options.syncMode === 'required') expected.serverUrl = options.expectedServerUrl;
  for (const [key, value] of Object.entries(expected)) {
    if (actual[key] !== value) throw new Error(`preflight ${key} mismatch: expected ${value}, got ${actual[key]}`);
  }
  if (options.traceMode === 'required' && !actual.traceApi) {
    throw new Error('preflight trace API is required but unavailable');
  }
  if (!isRendererVisible(actual)) {
    throw new Error(`preflight renderer must be visible: ${actual.visibilityState}`);
  }
  return actual;
}

function preflightWorker(options) {
  const lockPath = path.join(options.expectedGraphPath, 'db-worker.lock');
  const lock = JSON.parse(fs.readFileSync(lockPath, 'utf8'));
  process.kill(lock.pid, 0);
  const command = execFileSync('ps', ['-p', String(lock.pid), '-o', 'command='], { encoding: 'utf8' }).trim();
  return validateWorkerIdentity(lock, command, options);
}

function syncCommand(options, command) {
  const rootDir = path.dirname(path.dirname(options.expectedGraphPath));
  const stdout = execFileSync(process.execPath, [
    path.resolve(options.syncCli),
    '--root-dir', rootDir,
    '--config', options.syncConfig,
    '--graph', options.graphName,
    '--output', 'json',
    'sync', command,
  ], { encoding: 'utf8', maxBuffer: 4 * 1024 * 1024 });
  const envelope = JSON.parse(stdout.trim());
  if (envelope.status !== 'ok') throw new Error(`sync ${command} failed`);
  return envelope.data;
}

async function ensureSyncReady(options, { allowStart = false } = {}) {
  let status = syncCommand(options, 'status');
  if (!isSyncReady(status) && allowStart && status?.['ws-state'] === 'stopped') {
    status = syncCommand(options, 'start');
  }
  const deadline = Date.now() + 60_000;
  let previousSignature = null;
  let stableSamples = 0;
  while (Date.now() < deadline) {
    if (isSyncReady(status)) {
      const signature = JSON.stringify([
        status['local-tx'], status['remote-tx'],
        status['local-checksum'], status['remote-checksum'],
      ]);
      stableSamples = signature === previousSignature ? stableSamples + 1 : 1;
      previousSignature = signature;
      if (stableSamples >= 2) return {
        wsState: status['ws-state'],
        localTx: status['local-tx'],
        remoteTx: status['remote-tx'],
        checksum: status['local-checksum'],
        pendingLocal: status['pending-local'],
        pendingAsset: status['pending-asset'],
        pendingServer: status['pending-server'],
      };
    } else {
      stableSamples = 0;
      previousSignature = null;
      if (status?.['last-error']) throw new Error(`RTC health gate failed: ${status['last-error'].code || 'unknown'}`);
    }
    await sleep(500);
    status = syncCommand(options, 'status');
  }
  throw new Error(`RTC did not become stably ready: ${JSON.stringify(status)}`);
}

async function prepareFixtures(cdp, roundCount) {
  return evaluate(cdp, `(async () => {
    const api = globalThis.logseq.api;
    await api.create_page('perf-tag-alpha', null, { redirect: false });
    const runId = Date.now().toString(36);
    const fixtures = [];
    for (let round = 1; round <= ${roundCount}; round += 1) {
      const pageName = 'selfhost6-perf-' + runId + '-' + round;
      await api.create_page(pageName, null, { redirect: false });
      const block = await api.append_block_in_page(pageName, '', {});
      if (!block?.uuid) throw new Error('failed to create benchmark block');
      fixtures.push({ pageName, blockUuid: block.uuid });
    }
    return fixtures;
  })()`);
}

async function focusFixture(cdp, fixture) {
  return evaluate(cdp, `(async () => {
    const api = globalThis.logseq.api;
    const pageName = ${JSON.stringify(fixture.pageName)};
    const blockUuid = ${JSON.stringify(fixture.blockUuid)};
    api.push_state('page', { name: pageName }, {});
    await new Promise((resolve) => setTimeout(resolve, 700));
    api.edit_block(blockUuid, {});
    const deadline = Date.now() + 5000;
    while (Date.now() < deadline) {
      const active = document.activeElement;
      if (active?.id === 'edit-block-' + blockUuid) return { ready: true };
      await new Promise((resolve) => setTimeout(resolve, 25));
    }
    throw new Error('benchmark editor did not receive focus');
  })()`);
}

function keyParams(key, type) {
  if (key === 'Enter') return { type, key, code: 'Enter', windowsVirtualKeyCode: 13 };
  if (key === 'Escape') return { type, key, code: 'Escape', windowsVirtualKeyCode: 27 };
  if (key === '#') {
    const insertsText = type === 'char';
    return { type, key, code: 'Digit3', text: insertsText ? '#' : undefined,
      unmodifiedText: insertsText ? '3' : undefined, windowsVirtualKeyCode: 51, modifiers: 8 };
  }
  const lower = key.toLowerCase();
  const code = /[a-z]/.test(lower) ? `Key${lower.toUpperCase()}`
    : /[0-9]/.test(key) ? `Digit${key}`
      : key === ' ' ? 'Space' : key === '-' ? 'Minus' : '';
  const virtualKey = key === ' ' ? 32 : key.length === 1 ? key.toUpperCase().charCodeAt(0) : 0;
  return { type, key, code, text: type === 'keyDown' ? key : undefined,
    unmodifiedText: type === 'keyDown' ? key : undefined, windowsVirtualKeyCode: virtualKey };
}

async function waitMeasurement(cdp, token, timeoutMs = 6000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const measurement = await evaluate(cdp, `globalThis.__SELFHOST6_AUTO_BENCH__.take(${token})`);
    if (measurement) return measurement;
    await sleep(5);
  }
  const diagnostic = await evaluate(cdp, `(() => {
    const state = globalThis.__SELFHOST6_AUTO_BENCH__;
    return {
      hidden: document.hidden,
      visibilityState: document.visibilityState,
      armed: state?.armed ?? null,
    };
  })()`);
  if (!isRendererVisible(diagnostic)) {
    throw new Error(`benchmark renderer became hidden during measurement ${token}`);
  }
  throw new Error(`measurement ${token} did not settle: ${JSON.stringify(diagnostic.armed)}`);
}

async function activeEditorLength(cdp) {
  return evaluate(cdp, `(() => {
    const editor = document.activeElement;
    return editor?.id?.startsWith('edit-block-') ? editor.value.length : null;
  })()`);
}

async function runStep(cdp, step, expectedBefore) {
  const actualBefore = await activeEditorLength(cdp);
  const token = await evaluate(cdp,
    `globalThis.__SELFHOST6_AUTO_BENCH__.arm(${JSON.stringify(step.phase)}, ${JSON.stringify(step.action)})`);
  if (step.key === '#') {
    await cdp.send('Input.dispatchKeyEvent', keyParams(step.key, 'rawKeyDown'));
    // Chromium's synthetic `char` event does not reliably produce the same
    // beforeinput/input sequence as native text insertion in Electron. Keep
    // the real keydown (which arms Logseq's keyboard path), then let Chromium
    // insert the printable character so the editor observes a genuine input.
    await cdp.send('Input.insertText', { text: '#' });
    await cdp.send('Input.dispatchKeyEvent', keyParams(step.key, 'keyUp'));
  } else {
    await cdp.send('Input.dispatchKeyEvent', keyParams(step.key, 'keyDown'));
    await cdp.send('Input.dispatchKeyEvent', keyParams(step.key, 'keyUp'));
  }
  const measurement = await waitMeasurement(cdp, token);
  if (step.delayMs) await sleep(step.delayMs);
  const actualAfter = await activeEditorLength(cdp);
  const expectedAfter = expectedEditorLengthAfter(step, expectedBefore, actualAfter);
  measurement.integrity = {
    phase: step.phase,
    action: step.action,
    expectedBefore,
    actualBefore,
    beforeMatches: actualBefore === expectedBefore,
    expectedAfter,
    actualAfter,
    afterMatches: actualAfter === expectedAfter,
  };
  return { measurement, expectedAfter };
}

async function collectTrace(cdp, traceEnabled) {
  return evaluate(cdp, `(async () => {
    const api = globalThis.__LOGSEQ_SELFHOST_PERF__;
    const state = globalThis.__SELFHOST6_AUTO_BENCH__;
    const traceEnabled = ${JSON.stringify(traceEnabled)};
    const stats = traceEnabled ? await api.stats('all') : null;
    const trace = traceEnabled ? await api.drain('formal') : null;
    state.cleanup();
    if (traceEnabled) api.stop();
    return {
      enabled: traceEnabled,
      stats,
      trace,
      observations: { longTasks: state.longTasks, events: state.events },
    };
  })()`);
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.help) {
    console.log(usage());
    return;
  }
  if (!options.expectedAppSourceSha) {
    throw new Error('--expected-app-source-sha is required for a benchmark receipt');
  }
  if (options.traceMode === 'required' && !options.instrumentationPatchSha) {
    throw new Error('--instrumentation-patch-sha is required when trace mode is required');
  }
  const syncCliIdentity = options.syncMode === 'required'
    ? validateSyncCliIdentity(options.syncCli, options.expectedAppSourceSha)
    : null;
  const output = path.resolve(options.output);
  if (!output.startsWith('/private/tmp/')) throw new Error('output must stay under /private/tmp');
  const { cdp, target, ws } = await connect(options.port);
  try {
    const workerIdentity = preflightWorker(options);
    const identity = await preflight(cdp, options);
    const traceEnabled = options.traceMode !== 'off' && identity.traceApi;
    if (traceEnabled && !options.instrumentationPatchSha) {
      throw new Error('--instrumentation-patch-sha is required whenever trace capture is enabled');
    }
    const syncBefore = options.syncMode === 'required'
      ? await ensureSyncReady(options, { allowStart: true })
      : null;
    const fixtures = await prepareFixtures(cdp, options.rounds);
    const syncAfterFixtureSetup = options.syncMode === 'required'
      ? await ensureSyncReady(options)
      : null;
    const installed = await evaluate(cdp, measurementSource());
    if (traceEnabled) {
      await evaluate(cdp, `(async () => {
        const api = globalThis.__LOGSEQ_SELFHOST_PERF__;
        api.reset();
        await api.start();
      })()`);
    }
    const plan = options.plan === 'hashtag-probe' ? buildHashtagProbePlan() : buildBenchmarkPlan();
    const rounds = [];
    for (let round = 1; round <= options.rounds; round += 1) {
      await focusFixture(cdp, fixtures[round - 1]);
      const measurements = [];
      let expectedEditorLength = await activeEditorLength(cdp);
      for (const step of plan) {
        const result = await runStep(cdp, step, expectedEditorLength);
        measurements.push(result.measurement);
        expectedEditorLength = result.expectedAfter;
      }
      const syncAfter = options.syncMode === 'required'
        ? await ensureSyncReady(options)
        : null;
      rounds.push({
        round,
        summary: summarizeMeasurements(measurements),
        integrity: summarizeIntegrity(measurements),
        measurements,
        syncAfter,
      });
      await sleep(10_000);
    }
    const captured = await collectTrace(cdp, traceEnabled);
    const capturedTraceBuildCommits = traceBuildCommits(captured);
    if (traceEnabled && (
      capturedTraceBuildCommits.length !== 1 ||
      capturedTraceBuildCommits[0] !== options.expectedAppSourceSha
    )) {
      throw new Error(`trace build identity mismatch: ${JSON.stringify(capturedTraceBuildCommits)}`);
    }
    const result = {
      meta: {
        benchmarkVersion: 2,
        traceSchemaVersion: traceEnabled ? 1 : null,
        traceMode: options.traceMode,
        traceEnabled,
        syncMode: options.syncMode,
        instrumentationPatchSha: options.instrumentationPatchSha,
        expectedAppSourceSha: options.expectedAppSourceSha,
        capturedTraceBuildCommits,
        targetTitle: target.title,
        repo: identity.repo,
        reportedGraphPath: identity.graphPath,
        runtimeGraphPath: options.expectedGraphPath,
        workerIdentity,
        appVersion: identity.version,
        serverUrl: identity.serverUrl,
        syncCliIdentity,
        rounds: options.rounds,
        inputPlanSteps: plan.length,
        inputPlan: options.plan,
        observers: installed.observers,
        syncBefore,
        syncAfterFixtureSetup,
      },
      rounds,
      captured,
    };
    fs.writeFileSync(output, `${JSON.stringify(result, null, 2)}\n`, { mode: 0o600 });
    console.log(JSON.stringify({ status: 'ok', output, rounds: rounds.length, stepsPerRound: plan.length }));
  } finally {
    ws.close();
  }
}

if (require.main === module) {
  main().catch((error) => {
    console.error(error.stack || error.message);
    process.exitCode = 1;
  });
}
