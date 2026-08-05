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
  assert.equal((barrier.match(/b\/new-block\b/g) ?? []).length, 2)
  assert.doesNotMatch(barrier, /new-block-safe!|\btry\b|\bcatch\b|\brecur\b/)
})

test('stress writes retain their separate tolerant retry path', () => {
  const source = read('clj-e2e/test/logseq/e2e/rtc_extra_part2_test.clj')
  const start = source.indexOf('(defn- new-block-safe!')
  const end = source.indexOf('(defn- save-block-safe!', start)
  assert.notEqual(start, -1)
  assert.notEqual(end, -1)
  assert.match(
    source.slice(start, end),
    /\bloop\b[\s\S]*\bcatch\b[\s\S]*\brecur\b/
  )
})
