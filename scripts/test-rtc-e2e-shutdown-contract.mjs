#!/usr/bin/env node

import assert from 'node:assert/strict'
import { spawn, spawnSync } from 'node:child_process'
import { EventEmitter } from 'node:events'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import * as shutdownModule from './rtc-e2e-shutdown.mjs'

const { createShutdownController } = shutdownModule

const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..'
)

const deferred = () => {
  let resolve
  const promise = new Promise((resolvePromise) => {
    resolve = resolvePromise
  })
  return { promise, resolve }
}

const errorTreeContains = (root, target, seen = new Set()) => {
  if (root === target) return true
  if (!root || typeof root !== 'object' || seen.has(root)) return false
  seen.add(root)
  if (errorTreeContains(root.cause, target, seen)) return true
  if (root.errors && Symbol.iterator in Object(root.errors)) {
    for (const error of root.errors) {
      if (errorTreeContains(error, target, seen)) return true
    }
  }
  return false
}

const runRunnerWithRequestedSignal = (signal) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'rtc-requested-signal-'))
  const bbShim = path.join(root, 'bb-shim.cjs')
  const preload = path.join(root, 'signal-preload.cjs')
  const ready = path.join(root, 'test.ready')
  const runner = path.join(repoRoot, 'scripts/run-rtc-e2e.mjs')
  fs.writeFileSync(
    bbShim,
    `const fs = require('node:fs');
const [task] = process.argv.slice(2);
if (task !== 'serve') fs.writeFileSync(process.env.RTC_SIGNAL_READY, 'ready\\n');
setInterval(() => {}, 1000);
`
  )
  fs.writeFileSync(
    preload,
    `const fs = require('node:fs');
const net = require('node:net');
const path = require('node:path');
net.createServer = () => {
  const server = {
    address: () => ({ address: '127.0.0.1', family: 'IPv4', port: 43132 }),
    close: (callback) => queueMicrotask(() => callback?.()),
    listen: (...args) => {
      const callback = args.at(-1);
      if (typeof callback === 'function') queueMicrotask(callback);
      return server;
    },
    once: () => server,
    unref: () => server,
  };
  return server;
};
global.fetch = async () => ({ status: 200 });
if (path.resolve(process.argv[1] ?? '') === path.resolve(process.env.RTC_RUNNER_PATH)) {
  const timer = setInterval(() => {
    if (!fs.existsSync(process.env.RTC_SIGNAL_READY)) return;
    clearInterval(timer);
    process.emit(process.env.RTC_REQUEST_SIGNAL);
  }, 5);
}
`
  )

  try {
    return spawnSync(process.execPath, [runner, 'rtc-extra-test'], {
      cwd: repoRoot,
      encoding: 'utf8',
      env: {
        ...process.env,
        LOGSEQ_RTC_E2E_BB_COMMAND: JSON.stringify([
          process.execPath,
          bbShim,
        ]),
        NODE_OPTIONS: `${process.env.NODE_OPTIONS ?? ''} --require ${preload}`.trim(),
        RTC_REQUEST_SIGNAL: signal,
        RTC_RUNNER_PATH: runner,
        RTC_SIGNAL_READY: ready,
      },
      timeout: 10_000,
    })
  } finally {
    fs.rmSync(root, { force: true, recursive: true })
  }
}

test('requested signals retain their conventional exit statuses', () => {
  for (const [signal, expectedStatus] of [
    ['SIGINT', 130],
    ['SIGTERM', 143],
  ]) {
    const result = runRunnerWithRequestedSignal(signal)
    assert.equal(
      result.status,
      expectedStatus,
      `${signal} status was not preserved:\n${result.stdout}${result.stderr}`
    )
    assert.equal(result.signal, null)
    assert.doesNotMatch(
      result.stderr,
      /terminated by SIGTERM/,
      `${signal} cleanup was misreported as a run failure`
    )
  }
})

test('windows cleanup uses bounded taskkill tree stages', async () => {
  assert.equal(
    typeof shutdownModule.createWindowsProcessTreeSignaler,
    'function',
    'Windows cleanup must expose a process-tree signaler'
  )

  const calls = []
  const spawnProcess = (command, args, options) => {
    const taskkill = new EventEmitter()
    taskkill.kill = (signal) => calls.push(['helper-kill', signal])
    calls.push(['spawn', command, args, options])
    queueMicrotask(() => taskkill.emit('exit', 0, null))
    return taskkill
  }
  const child = { exitCode: null, pid: 707, signalCode: null }
  const signalChild = shutdownModule.createWindowsProcessTreeSignaler({
    spawnProcess,
    timeoutMs: 50,
  })
  let waitCount = 0
  const controller = createShutdownController({
    children: new Set([child]),
    signalChild,
    waitForExit: async () => ++waitCount === 2,
  })

  await controller.shutdown()

  assert.deepEqual(
    calls.map((call) => call.slice(0, 3)),
    [
      ['spawn', 'taskkill.exe', ['/PID', '707', '/T']],
      ['spawn', 'taskkill.exe', ['/PID', '707', '/T', '/F']],
    ]
  )
  for (const [, , , options] of calls) {
    assert.deepEqual(options, {
      shell: false,
      stdio: 'ignore',
      windowsHide: true,
    })
  }
})

test('windows cleanup fails closed for invalid PIDs and tool errors', async () => {
  const toolError = new Error('TASKKILL_TOOL_SENTINEL')
  const directSignals = []
  let spawnCount = 0
  const spawnProcess = () => {
    spawnCount += 1
    const taskkill = new EventEmitter()
    taskkill.kill = () => true
    queueMicrotask(() => taskkill.emit('error', toolError))
    return taskkill
  }
  const signalChild = shutdownModule.createWindowsProcessTreeSignaler({
    spawnProcess,
    directFallbackTimeoutMs: 50,
    timeoutMs: 50,
  })

  await assert.rejects(
    signalChild({ exitCode: null, pid: 0, signalCode: null }, 'SIGTERM'),
    /valid positive integer PID/
  )
  assert.equal(spawnCount, 0)

  const child = new EventEmitter()
  Object.assign(child, {
    exitCode: null,
    kill: (signal) => {
      directSignals.push(signal)
      child.signalCode = signal
      queueMicrotask(() => child.emit('exit', null, signal))
      return true
    },
    pid: 808,
    signalCode: null,
  })
  const thrown = await signalChild(child, 'SIGTERM').catch((error) => error)
  assert.strictEqual(thrown, toolError)
  assert.deepEqual(directSignals, ['SIGTERM'])
})

test('windows helper failure escalates a TERM-ignoring direct child', async () => {
  const toolError = new Error('TASKKILL_ESCALATION_SENTINEL')
  const directSignals = []
  const spawnProcess = () => {
    const taskkill = new EventEmitter()
    taskkill.kill = () => true
    queueMicrotask(() => taskkill.emit('error', toolError))
    return taskkill
  }
  const child = new EventEmitter()
  Object.assign(child, {
    exitCode: null,
    kill: (signal) => {
      directSignals.push(signal)
      if (signal === 'SIGKILL') {
        child.signalCode = signal
        queueMicrotask(() => child.emit('exit', null, signal))
      }
      return true
    },
    pid: 818,
    signalCode: null,
  })
  const signalChild = shutdownModule.createWindowsProcessTreeSignaler({
    spawnProcess,
    directFallbackTimeoutMs: 5,
    timeoutMs: 50,
  })

  const thrown = await signalChild(child, 'SIGTERM').catch((error) => error)
  assert.strictEqual(thrown, toolError)
  assert.deepEqual(directSignals, ['SIGTERM', 'SIGKILL'])
})

test('windows taskkill nonzero remains a cleanup failure after fallback', async () => {
  const directSignals = []
  const spawnProcess = () => {
    const taskkill = new EventEmitter()
    taskkill.kill = () => true
    queueMicrotask(() => taskkill.emit('exit', 7, null))
    return taskkill
  }
  const child = new EventEmitter()
  Object.assign(child, {
    exitCode: null,
    kill: (signal) => {
      directSignals.push(signal)
      child.signalCode = signal
      queueMicrotask(() => child.emit('exit', null, signal))
      return true
    },
    pid: 823,
    signalCode: null,
  })
  const signalChild = shutdownModule.createWindowsProcessTreeSignaler({
    spawnProcess,
    directFallbackTimeoutMs: 50,
    timeoutMs: 50,
  })

  await assert.rejects(
    signalChild(child, 'SIGTERM'),
    /taskkill\.exe failed for PID 823 during SIGTERM \(code=7, signal=null\)/
  )
  assert.deepEqual(directSignals, ['SIGTERM'])
})

test('windows fallback failure retains the original helper error identity', async () => {
  const helperError = new Error('TASKKILL_ORIGINAL_IDENTITY_SENTINEL')
  const fallbackError = new Error('DIRECT_FALLBACK_IDENTITY_SENTINEL')
  const spawnProcess = () => {
    const taskkill = new EventEmitter()
    taskkill.kill = () => true
    queueMicrotask(() => taskkill.emit('error', helperError))
    return taskkill
  }
  const child = new EventEmitter()
  Object.assign(child, {
    exitCode: null,
    kill: () => {
      throw fallbackError
    },
    pid: 828,
    signalCode: null,
  })
  const signalChild = shutdownModule.createWindowsProcessTreeSignaler({
    spawnProcess,
    directFallbackTimeoutMs: 5,
    timeoutMs: 50,
  })

  const thrown = await signalChild(child, 'SIGTERM').catch((error) => error)
  assert.equal(errorTreeContains(thrown, helperError), true)
  assert.equal(errorTreeContains(thrown, fallbackError), true)
})

test('broken Windows tree helper cannot leave runner stdio open', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'rtc-broken-taskkill-'))
  const bbShim = path.join(root, 'bb-shim.cjs')
  const preload = path.join(root, 'broken-taskkill-preload.cjs')
  const serverPid = path.join(root, 'server.pid')
  fs.writeFileSync(
    bbShim,
    `const fs = require('node:fs');
const [task] = process.argv.slice(2);
if (task === 'serve') {
  fs.writeFileSync(process.env.RTC_BROKEN_TASKKILL_SERVER_PID, String(process.pid));
  setInterval(() => {}, 1000);
} else {
  process.exit(0);
}
`
  )
  fs.writeFileSync(
    preload,
    `const childProcess = require('node:child_process');
const { EventEmitter } = require('node:events');
const { syncBuiltinESMExports } = require('node:module');
const net = require('node:net');
const originalSpawn = childProcess.spawn;
Object.defineProperty(process, 'platform', {
  configurable: true,
  value: 'win32',
});
childProcess.spawn = (command, args, options) => {
  if (command !== 'taskkill.exe') return originalSpawn(command, args, options);
  const taskkill = new EventEmitter();
  taskkill.kill = () => true;
  queueMicrotask(() => {
    const error = new Error('BROKEN_TASKKILL_IDENTITY_SENTINEL');
    error.code = 'ENOENT';
    taskkill.emit('error', error);
  });
  return taskkill;
};
syncBuiltinESMExports();
net.createServer = () => {
  const server = {
    address: () => ({ address: '127.0.0.1', family: 'IPv4', port: 43133 }),
    close: (callback) => queueMicrotask(() => callback?.()),
    listen: (...args) => {
      const callback = args.at(-1);
      if (typeof callback === 'function') queueMicrotask(callback);
      return server;
    },
    once: () => server,
    unref: () => server,
  };
  return server;
};
global.fetch = async () => ({ status: 200 });
`
  )

  let pid
  let wrapper
  try {
    const startedAt = Date.now()
    wrapper = spawn(
      process.execPath,
      [path.join(repoRoot, 'scripts/run-rtc-e2e.mjs'), 'rtc-extra-test'],
      {
        cwd: repoRoot,
        encoding: 'utf8',
        env: {
          ...process.env,
          LOGSEQ_RTC_E2E_BB_COMMAND: JSON.stringify([
            process.execPath,
            bbShim,
          ]),
          NODE_OPTIONS: `${process.env.NODE_OPTIONS ?? ''} --require ${preload}`.trim(),
          RTC_BROKEN_TASKKILL_SERVER_PID: serverPid,
        },
        stdio: ['ignore', 'pipe', 'pipe'],
      }
    )
    let stdout = ''
    let stderr = ''
    wrapper.stdout.on('data', (chunk) => {
      stdout += chunk
    })
    wrapper.stderr.on('data', (chunk) => {
      stderr += chunk
    })
    const closed = new Promise((resolve, reject) => {
      wrapper.once('error', reject)
      wrapper.once('close', (status, signal) => resolve({ signal, status }))
    })
    let timeout
    const result = await Promise.race([
      closed,
      new Promise((resolve) => {
        timeout = setTimeout(() => resolve(null), 2_000)
      }),
    ]).finally(() => clearTimeout(timeout))
    const elapsedMs = Date.now() - startedAt
    if (fs.existsSync(serverPid)) pid = Number(fs.readFileSync(serverPid, 'utf8'))
    assert.ok(result, `runner stdio remained open after ${elapsedMs}ms:\n${stdout}${stderr}`)
    assert.equal(result.status, 1, `${stdout}${stderr}`)
    assert.match(stderr, /BROKEN_TASKKILL_IDENTITY_SENTINEL/)
  } finally {
    if (wrapper && wrapper.exitCode === null && wrapper.signalCode === null) {
      wrapper.kill('SIGKILL')
    }
    if (Number.isSafeInteger(pid) && pid > 0) {
      try {
        process.kill(pid, 'SIGKILL')
      } catch (error) {
        if (error?.code !== 'ESRCH') throw error
      }
    }
    fs.rmSync(root, { force: true, recursive: true })
  }
})

test('windows cleanup bounds a hung taskkill helper', async () => {
  const helperSignals = []
  const directSignals = []
  const spawnProcess = () => {
    const taskkill = new EventEmitter()
    taskkill.kill = (signal) => helperSignals.push(signal)
    return taskkill
  }
  const signalChild = shutdownModule.createWindowsProcessTreeSignaler({
    spawnProcess,
    directFallbackTimeoutMs: 50,
    timeoutMs: 5,
  })
  const child = new EventEmitter()
  Object.assign(child, {
    exitCode: null,
    kill: (signal) => {
      directSignals.push(signal)
      child.signalCode = signal
      queueMicrotask(() => child.emit('exit', null, signal))
      return true
    },
    pid: 909,
    signalCode: null,
  })

  await assert.rejects(
    signalChild(child, 'SIGKILL'),
    /taskkill\.exe did not exit within 5ms for PID 909 during SIGKILL/
  )
  assert.deepEqual(helperSignals, ['SIGKILL'])
  assert.deepEqual(directSignals, ['SIGKILL'])
})

test('concurrent shutdown callers share cleanup through SIGKILL', async () => {
  const child = { exitCode: null, pid: 101, signalCode: null }
  const signals = []
  const controller = createShutdownController({
    children: new Set([child]),
    signalChild: (_child, signal) => signals.push(signal),
    waitForExit: async (_child, _timeout) => signals.at(-1) === 'SIGKILL',
  })
  const first = controller.shutdown()
  const second = controller.shutdown()
  assert.strictEqual(first, second)
  await Promise.all([first, second])
  assert.deepEqual(signals, ['SIGTERM', 'SIGKILL'])
})

test('shutdown fails closed when a child survives SIGKILL', async () => {
  const child = { exitCode: null, pid: 202, signalCode: null }
  const controller = createShutdownController({
    children: new Set([child]),
    signalChild: () => {},
    waitForExit: async () => false,
  })
  await assert.rejects(controller.shutdown(), /survived SIGKILL/)
})

test('one signal failure cannot settle shutdown before every child cleanup', async () => {
  const signalFailure = new Error('SIGNAL_FAILURE_SENTINEL')
  const badChild = { exitCode: null, pid: 301, signalCode: null }
  const slowChild = { exitCode: null, pid: 302, signalCode: null }
  const slowExit = deferred()
  const events = []
  const controller = createShutdownController({
    children: new Set([badChild, slowChild]),
    signalChild: (child, signal) => {
      events.push(['signal', child.pid, signal])
      if (child === badChild) throw signalFailure
    },
    waitForExit: async (child) => {
      events.push(['wait', child.pid])
      return child === slowChild ? slowExit.promise : false
    },
  })

  const shutdown = controller.shutdown()
  const settledBeforeSlowChild = await Promise.race([
    shutdown.then(
      () => true,
      () => true
    ),
    new Promise((resolve) => setTimeout(() => resolve(false), 25)),
  ])
  slowExit.resolve(true)
  const thrown = await shutdown.catch((error) => error)

  assert.equal(
    settledBeforeSlowChild,
    false,
    `shutdown settled before slow cleanup: ${JSON.stringify(events)}`
  )
  assert.deepEqual(events, [
    ['signal', 301, 'SIGTERM'],
    ['signal', 302, 'SIGTERM'],
    ['wait', 302],
  ])
  assert.equal(errorTreeContains(thrown, signalFailure), true)
})

test('shutdown reports every independent child cleanup failure', async () => {
  const firstFailure = new Error('FIRST_CLEANUP_SENTINEL')
  const secondFailure = new Error('SECOND_CLEANUP_SENTINEL')
  const firstChild = { exitCode: null, pid: 401, signalCode: null }
  const secondChild = { exitCode: null, pid: 402, signalCode: null }
  const signaled = []
  const controller = createShutdownController({
    children: new Set([firstChild, secondChild]),
    signalChild: (child) => {
      signaled.push(child.pid)
      throw child === firstChild ? firstFailure : secondFailure
    },
    waitForExit: async () => true,
  })

  const thrown = await controller.shutdown().catch((error) => error)
  assert.deepEqual(signaled, [401, 402])
  assert.equal(errorTreeContains(thrown, firstFailure), true)
  assert.equal(errorTreeContains(thrown, secondFailure), true)
})

test('requested signal cannot mask existing run and cleanup failures', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'rtc-dual-error-'))
  const preload = path.join(root, 'dual-error-preload.cjs')
  const eventsPath = path.join(root, 'events.log')
  fs.writeFileSync(
    preload,
    `const fs = require('node:fs');
const childProcess = require('node:child_process');
const net = require('node:net');
const { EventEmitter } = require('node:events');
const { syncBuiltinESMExports } = require('node:module');
let nextPid = 501;
class FakeChild extends EventEmitter {
  constructor(args) {
    super();
    this.args = args;
    this.exitCode = null;
    this.pid = nextPid++;
    this.signalCode = null;
  }
  kill(signal) {
    fs.appendFileSync(process.env.RTC_DUAL_ERROR_EVENTS,
      'cleanup-child:' + this.pid + ':' + signal + '\\n');
    throw new Error('CLEANUP_SIGNAL_SENTINEL');
  }
}
childProcess.spawn = (command, args, options) => {
  fs.appendFileSync(process.env.RTC_DUAL_ERROR_EVENTS,
    'spawn:' + command + ':' + args.join(' ') + ':' + options.detached + '\\n');
  const child = new FakeChild(args);
  if (args[0] !== 'serve') {
    queueMicrotask(() => {
      child.exitCode = 23;
      child.emit('exit', 23, null);
      process.emit('SIGTERM');
    });
  }
  return child;
};
syncBuiltinESMExports();
process.kill = (pid, signal) => {
  fs.appendFileSync(process.env.RTC_DUAL_ERROR_EVENTS,
    'cleanup-signal:' + pid + ':' + signal + '\\n');
  throw new Error('CLEANUP_SIGNAL_SENTINEL');
};
net.createServer = () => {
  const server = {
    address: () => ({ address: '127.0.0.1', family: 'IPv4', port: 43131 }),
    close: (callback) => queueMicrotask(() => callback?.()),
    listen: (...args) => {
      const callback = args.at(-1);
      if (typeof callback === 'function') queueMicrotask(callback);
      return server;
    },
    once: () => server,
    unref: () => server,
  };
  return server;
};
global.fetch = async () => ({ status: 200 });
`
  )
  const env = {
    ...process.env,
    NODE_OPTIONS: `${process.env.NODE_OPTIONS ?? ''} --require ${preload}`.trim(),
    RTC_DUAL_ERROR_EVENTS: eventsPath,
  }
  delete env.NODE_TEST_CONTEXT

  try {
    const result = spawnSync(
      process.execPath,
      [path.join(repoRoot, 'scripts/run-rtc-e2e.mjs'), 'rtc-extra-test'],
      {
        cwd: repoRoot,
        encoding: 'utf8',
        env,
        timeout: 10_000,
      }
    )
    const events = fs.existsSync(eventsPath)
      ? fs.readFileSync(eventsPath, 'utf8')
      : ''
    const expectedCommand = process.platform === 'win32' ? 'bb.exe' : 'bb'
    const expectedDetached = process.platform !== 'win32'
    assert.equal(result.status, 1, `${result.stdout}${result.stderr}`)
    assert.match(
      events,
      new RegExp(`spawn:${expectedCommand}:serve --port 43131:${expectedDetached}`)
    )
    assert.match(
      events,
      new RegExp(
        `spawn:${expectedCommand}:rtc-extra-test --port 43131:${expectedDetached}`
      )
    )
    assert.match(
      events,
      process.platform === 'win32'
        ? /cleanup-child:501:SIGTERM/
        : /cleanup-signal:-501:SIGTERM/
    )
    assert.match(
      result.stderr,
      new RegExp(
        `${expectedCommand.replace('.', '\\.')} rtc-extra-test --port 43131 exited with 23`
      ),
      `primary run error was lost:\n${result.stderr}`
    )
    assert.match(
      result.stderr,
      /CLEANUP_SIGNAL_SENTINEL/,
      `cleanup error was lost:\n${result.stderr}`
    )
  } finally {
    fs.rmSync(root, { force: true, recursive: true })
  }
})

test('win32 runner cleanup terminates the complete bb descendant process tree', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'rtc-win32-tree-'))
  const preload = path.join(root, 'win32-tree-preload.cjs')
  const bbShim = path.join(root, 'bb-tree-shim.cjs')
  const treeKillShim = path.join(root, 'taskkill.exe')
  const treeKillAlias = path.join(root, 'taskkill')
  const treeKillLog = path.join(root, 'tree-kill.log')
  const serverPidPath = path.join(root, 'server.pid')
  const descendantPidPath = path.join(root, 'descendant.pid')
  let runner
  let runnerExit
  let serverPid
  let descendantPid

  fs.writeFileSync(
    preload,
    `const fs = require('node:fs');
const net = require('node:net');
Object.defineProperty(process, 'platform', {
  configurable: true,
  value: 'win32',
});
net.createServer = () => {
  const server = {
    address: () => ({ address: '127.0.0.1', family: 'IPv4', port: 43132 }),
    close: (callback) => queueMicrotask(() => callback?.()),
    listen: (...args) => {
      const callback = args.at(-1);
      if (typeof callback === 'function') queueMicrotask(callback);
      return server;
    },
    once: () => server,
    unref: () => server,
  };
  return server;
};
global.fetch = async () => {
  while (!fs.existsSync(process.env.RTC_WIN32_DESCENDANT_PID)) {
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  return { status: 200 };
};
`
  )
  fs.writeFileSync(
    bbShim,
    `const fs = require('node:fs');
const { spawn } = require('node:child_process');
const [task] = process.argv.slice(2);
if (task === 'serve') {
  fs.writeFileSync(process.env.RTC_WIN32_SERVER_PID, String(process.pid));
  const descendant = spawn(
    process.execPath,
    ['-e', 'setInterval(() => {}, 1000)'],
    { detached: true, stdio: 'ignore' }
  );
  descendant.unref();
  fs.writeFileSync(process.env.RTC_WIN32_DESCENDANT_PID, String(descendant.pid));
  setInterval(() => {}, 1000);
} else {
  process.exit(0);
}
`
  )
  const treeKillSource = `#!/usr/bin/env node
const fs = require('node:fs');
const args = process.argv.slice(2);
fs.appendFileSync(process.env.RTC_WIN32_TREE_KILL_LOG, args.join(' ') + '\\n');
const pidIndex = args.findIndex((arg) => arg.toUpperCase() === '/PID');
const requestedPid = Number(args[pidIndex + 1]);
const readPid = (target) =>
  fs.existsSync(target) ? Number(fs.readFileSync(target, 'utf8')) : undefined;
const serverPid = readPid(process.env.RTC_WIN32_SERVER_PID);
const descendantPid = readPid(process.env.RTC_WIN32_DESCENDANT_PID);
const targets = requestedPid === serverPid
  ? [descendantPid, serverPid]
  : [requestedPid];
for (const pid of targets) {
  if (!Number.isInteger(pid) || pid <= 1) continue;
  try {
    process.kill(pid, 'SIGKILL');
  } catch (error) {
    if (error?.code !== 'ESRCH') throw error;
  }
}
`
  fs.writeFileSync(treeKillShim, treeKillSource)
  fs.writeFileSync(treeKillAlias, treeKillSource)
  fs.chmodSync(treeKillShim, 0o755)
  fs.chmodSync(treeKillAlias, 0o755)

  const env = {
    ...process.env,
    LOGSEQ_RTC_E2E_BB_COMMAND: JSON.stringify([process.execPath, bbShim]),
    NODE_OPTIONS: `${process.env.NODE_OPTIONS ?? ''} --require ${preload}`.trim(),
    PATH: `${root}${path.delimiter}${process.env.PATH ?? ''}`,
    RTC_WIN32_DESCENDANT_PID: descendantPidPath,
    RTC_WIN32_SERVER_PID: serverPidPath,
    RTC_WIN32_TREE_KILL_LOG: treeKillLog,
    TEMP: os.tmpdir(),
    TMP: os.tmpdir(),
  }
  delete env.NODE_TEST_CONTEXT

  try {
    runner = spawn(
      process.execPath,
      [path.join(repoRoot, 'scripts/run-rtc-e2e.mjs'), 'rtc-extra-test'],
      {
        cwd: repoRoot,
        env,
        stdio: 'ignore',
      }
    )
    runnerExit = new Promise((resolve, reject) => {
      runner.once('error', reject)
      runner.once('exit', (code, signal) => resolve({ code, signal }))
    })
    let timeout
    const result = await Promise.race([
      runnerExit,
      new Promise((resolve) => {
        timeout = setTimeout(() => resolve({ timedOut: true }), 3_000)
      }),
    ]).finally(() => clearTimeout(timeout))
    serverPid = fs.existsSync(serverPidPath)
      ? Number(fs.readFileSync(serverPidPath, 'utf8'))
      : undefined
    descendantPid = Number(fs.readFileSync(descendantPidPath, 'utf8'))
    assert.equal(
      result.timedOut,
      undefined,
      `win32 runner did not settle within 3s (tree adapter calls: ${
        fs.existsSync(treeKillLog) ? fs.readFileSync(treeKillLog, 'utf8') : 'none'
      })`
    )
    assert.equal(
      result.code,
      0,
      `win32 runner did not complete normally: ${JSON.stringify(result)}`
    )
    assert.ok(Number.isInteger(serverPid) && serverPid > 1)
    assert.ok(Number.isInteger(descendantPid) && descendantPid > 1)
    assert.throws(
      () => process.kill(descendantPid, 0),
      (error) => error?.code === 'ESRCH',
      `win32 runner left descendant ${descendantPid} alive after shutdown`
    )
  } finally {
    if (runner && runner.exitCode === null && runner.signalCode === null) {
      runner.kill('SIGKILL')
      await Promise.race([
        runnerExit?.catch(() => undefined),
        new Promise((resolve) => setTimeout(resolve, 500)),
      ])
    }
    for (const [knownPid, pidPath] of [
      [serverPid, serverPidPath],
      [descendantPid, descendantPidPath],
    ]) {
      const pid = Number.isInteger(knownPid)
        ? knownPid
        : fs.existsSync(pidPath)
          ? Number(fs.readFileSync(pidPath, 'utf8'))
          : undefined
      if (!Number.isInteger(pid) || pid <= 1) continue
      try {
        process.kill(pid, 'SIGKILL')
      } catch (error) {
        if (error?.code !== 'ESRCH') throw error
      }
    }
    fs.rmSync(root, { force: true, recursive: true })
  }
})

const waitForPath = async (targetPath, timeoutMs, label) => {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (fs.existsSync(targetPath)) return
    await new Promise((resolve) => setTimeout(resolve, 10))
  }
  throw new Error(`timed out waiting for ${label}`)
}

const killFixtureProcess = (pid) => {
  if (!Number.isInteger(pid) || pid <= 1) return
  try {
    process.kill(pid, 'SIGKILL')
  } catch (error) {
    if (error?.code !== 'ESRCH') throw error
  }
}

const runSignalScenario = async ({
  cleanupFailure = false,
  serverTermDelayMs = 0,
  signal,
  taskMode = 'active',
}) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'rtc-runner-signal-'))
  const preload = path.join(root, 'signal-preload.cjs')
  const bbShim = path.join(root, 'signal-bb-shim.cjs')
  const serverPidPath = path.join(root, 'server.pid')
  const serverReadyPath = path.join(root, 'server.ready')
  const serverCleanupPath = path.join(root, 'server.cleanup')
  const taskPidPath = path.join(root, 'task.pid')
  const taskReadyPath = path.join(root, 'task.ready')
  let output = ''
  let runner

  fs.writeFileSync(
    preload,
    `const fs = require('node:fs');
const net = require('node:net');
const originalKill = process.kill.bind(process);
net.createServer = () => {
  const server = {
    address: () => ({ address: '127.0.0.1', family: 'IPv4', port: 43133 }),
    close: (callback) => queueMicrotask(() => callback?.()),
    listen: (...args) => {
      const callback = args.at(-1);
      if (typeof callback === 'function') queueMicrotask(callback);
      return server;
    },
    once: () => server,
    unref: () => server,
  };
  return server;
};
global.fetch = async () => {
  while (!fs.existsSync(process.env.RTC_SIGNAL_SERVER_READY)) {
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  return { status: 200 };
};
if (
  process.env.RTC_SIGNAL_CLEANUP_FAILURE === '1' &&
  process.argv[1]?.endsWith('run-rtc-e2e.mjs')
) {
  process.kill = (pid, signal) => {
    originalKill(Math.abs(pid), signal);
    throw new Error('SIGNAL_CLEANUP_FAILURE_SENTINEL');
  };
}
`
  )
  fs.writeFileSync(
    bbShim,
    `const fs = require('node:fs');
const [task] = process.argv.slice(2);
if (task === 'serve') {
  fs.writeFileSync(process.env.RTC_SIGNAL_SERVER_PID, String(process.pid));
  fs.writeFileSync(process.env.RTC_SIGNAL_SERVER_READY, 'ready\\n');
  process.on('SIGTERM', () => {
    fs.writeFileSync(process.env.RTC_SIGNAL_SERVER_CLEANUP, 'started\\n');
    setTimeout(
      () => process.exit(0),
      Number(process.env.RTC_SIGNAL_SERVER_TERM_DELAY)
    );
  });
  setInterval(() => {}, 1000);
} else {
  fs.writeFileSync(process.env.RTC_SIGNAL_TASK_PID, String(process.pid));
  fs.writeFileSync(process.env.RTC_SIGNAL_TASK_READY, 'ready\\n');
  if (process.env.RTC_SIGNAL_TASK_MODE === 'run-failure') {
    setTimeout(() => process.exit(23), 100);
  } else {
    setInterval(() => {}, 1000);
  }
}
`
  )

  const env = {
    ...process.env,
    LOGSEQ_RTC_E2E_BB_COMMAND: JSON.stringify([process.execPath, bbShim]),
    NODE_OPTIONS: `${process.env.NODE_OPTIONS ?? ''} --require ${preload}`.trim(),
    RTC_SIGNAL_CLEANUP_FAILURE: cleanupFailure ? '1' : '0',
    RTC_SIGNAL_SERVER_CLEANUP: serverCleanupPath,
    RTC_SIGNAL_SERVER_PID: serverPidPath,
    RTC_SIGNAL_SERVER_READY: serverReadyPath,
    RTC_SIGNAL_SERVER_TERM_DELAY: String(serverTermDelayMs),
    RTC_SIGNAL_TASK_MODE: taskMode,
    RTC_SIGNAL_TASK_PID: taskPidPath,
    RTC_SIGNAL_TASK_READY: taskReadyPath,
  }
  delete env.NODE_TEST_CONTEXT

  try {
    runner = spawn(
      process.execPath,
      [path.join(repoRoot, 'scripts/run-rtc-e2e.mjs'), 'rtc-extra-test'],
      {
        cwd: repoRoot,
        env,
        shell: false,
        stdio: ['ignore', 'pipe', 'pipe'],
      }
    )
    runner.stdout.on('data', (chunk) => {
      output += chunk
    })
    runner.stderr.on('data', (chunk) => {
      output += chunk
    })
    const exit = new Promise((resolve, reject) => {
      runner.once('error', reject)
      runner.once('exit', (code, exitSignal) => resolve({ code, signal: exitSignal }))
    })

    try {
      await waitForPath(serverReadyPath, 5_000, 'active RTC server')
      await waitForPath(taskReadyPath, 5_000, 'active RTC task')
    } catch (error) {
      error.message += `:\n${output}`
      throw error
    }
    if (taskMode === 'run-failure') {
      await waitForPath(serverCleanupPath, 5_000, 'cleanup after task failure')
    }
    runner.kill(signal)

    let timer
    const result = await Promise.race([
      exit,
      new Promise((_, reject) => {
        timer = setTimeout(
          () => reject(new Error(`runner did not exit after ${signal}:\n${output}`)),
          8_000
        )
      }),
    ]).finally(() => clearTimeout(timer))
    return { ...result, output }
  } finally {
    if (runner && runner.exitCode === null && runner.signalCode === null) {
      runner.kill('SIGKILL')
    }
    for (const pidPath of [serverPidPath, taskPidPath]) {
      if (fs.existsSync(pidPath)) {
        killFixtureProcess(Number(fs.readFileSync(pidPath, 'utf8')))
      }
    }
    fs.rmSync(root, { force: true, recursive: true })
  }
}

for (const [signal, expectedExitCode] of [
  ['SIGINT', 130],
  ['SIGTERM', 143],
]) {
  test(`runner exits ${expectedExitCode} when ${signal} initiates active child cleanup`, async () => {
    const result = await runSignalScenario({ signal })
    assert.equal(result.signal, null, result.output)
    assert.equal(result.code, expectedExitCode, result.output)
    assert.doesNotMatch(
      result.output,
      /terminated by SIGTERM/,
      `shutdown-generated child termination was reported as a run failure:\n${result.output}`
    )
  })
}

test('a run failure that predates SIGTERM remains the primary failure', async () => {
  const result = await runSignalScenario({
    serverTermDelayMs: 500,
    signal: 'SIGTERM',
    taskMode: 'run-failure',
  })
  assert.equal(result.signal, null, result.output)
  assert.equal(result.code, 1, result.output)
  assert.match(result.output, /rtc-extra-test --port 43133 exited with 23/)
})

test('a cleanup failure during signal shutdown remains observable', async () => {
  const result = await runSignalScenario({
    cleanupFailure: true,
    signal: 'SIGTERM',
  })
  assert.equal(result.signal, null, result.output)
  assert.equal(result.code, 1, result.output)
  assert.match(result.output, /\[rtc-e2e\] cleanup failed:/)
  assert.match(result.output, /SIGNAL_CLEANUP_FAILURE_SENTINEL/)
})
