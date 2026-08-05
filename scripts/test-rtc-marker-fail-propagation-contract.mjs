#!/usr/bin/env node

import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..'
)
const read = (relativePath) =>
  fs.readFileSync(path.join(repoRoot, relativePath), 'utf8')

test('RTC barrier markers use the direct fail-propagating block path', () => {
  const source = read('clj-e2e/test/logseq/e2e/rtc_extra_part2_test.clj')
  const start = source.indexOf('(defn- sync-by-barrier!')
  const end = source.indexOf('(defn- seed-long-nested-page!', start)
  assert.notEqual(start, -1)
  assert.notEqual(end, -1)
  const barrier = source.slice(start, end)
  assert.equal((barrier.match(/b\/new-block-strict!/g) ?? []).length, 2)
  assert.doesNotMatch(barrier, /new-block-safe!|\btry\b|\bcatch\b|\brecur\b/)
})

test('strict marker primitive contains no retry or exception swallowing', () => {
  const source = read('clj-e2e/src/logseq/e2e/block.clj')
  const start = source.indexOf('(defn new-block-strict!')
  const end = source.indexOf('(defn new-block\n', start)
  assert.notEqual(start, -1)
  assert.notEqual(end, -1)
  const strictPrimitive = source.slice(start, end)
  assert.doesNotMatch(
    strictPrimitive,
    /\btry\b|\bcatch\b|\brecur\b|open-last-block\b(?!-strict)/
  )
})
