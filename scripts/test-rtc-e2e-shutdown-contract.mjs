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
    timeoutMs: 50,
  })

  await assert.rejects(
    signalChild({ exitCode: null, pid: 0, signalCode: null }, 'SIGTERM'),
    /valid positive integer PID/
  )
  assert.equal(spawnCount, 0)

  const thrown = await signalChild(
    { exitCode: null, pid: 808, signalCode: null },
    'SIGTERM'
  ).catch((error) => error)
  assert.strictEqual(thrown, toolError)
})

test('windows cleanup bounds a hung taskkill helper', async () => {
  const helperSignals = []
  const spawnProcess = () => {
    const taskkill = new EventEmitter()
    taskkill.kill = (signal) => helperSignals.push(signal)
    return taskkill
  }
  const signalChild = shutdownModule.createWindowsProcessTreeSignaler({
    spawnProcess,
    timeoutMs: 5,
  })

  await assert.rejects(
    signalChild(
      { exitCode: null, pid: 909, signalCode: null },
      'SIGKILL'
    ),
    /taskkill\.exe did not exit within 5ms for PID 909 during SIGKILL/
  )
  assert.deepEqual(helperSignals, ['SIGKILL'])
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

test('runner preserves its primary run error and reports cleanup failure', () => {
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
    assert.notEqual(result.status, 0, `${result.stdout}${result.stderr}`)
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

test('win32 runner cleanup terminates the complete bb descendant process tree', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'rtc-win32-tree-'))
  const preload = path.join(root, 'win32-tree-preload.cjs')
  const bbShim = path.join(root, 'bb-tree-shim.cjs')
  const descendantPidPath = path.join(root, 'descendant.pid')
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

  const env = {
    ...process.env,
    LOGSEQ_RTC_E2E_BB_COMMAND: JSON.stringify([process.execPath, bbShim]),
    NODE_OPTIONS: `${process.env.NODE_OPTIONS ?? ''} --require ${preload}`.trim(),
    RTC_WIN32_DESCENDANT_PID: descendantPidPath,
    TEMP: os.tmpdir(),
    TMP: os.tmpdir(),
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
    descendantPid = Number(fs.readFileSync(descendantPidPath, 'utf8'))
    assert.equal(
      result.status,
      0,
      `win32 runner did not complete normally:\n${result.stdout}${result.stderr}`
    )
    assert.ok(Number.isInteger(descendantPid) && descendantPid > 1)
    assert.throws(
      () => process.kill(descendantPid, 0),
      (error) => error?.code === 'ESRCH',
      `win32 runner left descendant ${descendantPid} alive after shutdown`
    )
  } finally {
    if (Number.isInteger(descendantPid) && descendantPid > 1) {
      try {
        process.kill(descendantPid, 'SIGKILL')
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
