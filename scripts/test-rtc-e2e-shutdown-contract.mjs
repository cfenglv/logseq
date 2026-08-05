#!/usr/bin/env node

import assert from 'node:assert/strict'
import test from 'node:test'
import { createShutdownController } from './rtc-e2e-shutdown.mjs'

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
