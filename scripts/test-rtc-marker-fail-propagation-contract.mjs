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

test('RTC barrier markers use the direct fail-propagating client API path', () => {
  const source = read('clj-e2e/test/logseq/e2e/rtc_extra_part2_test.clj')
  const helperStart = source.indexOf('(defn- append-barrier-marker!')
  const start = source.indexOf('(defn- sync-by-barrier!')
  const end = source.indexOf('(defn- seed-long-nested-page!', start)
  assert.notEqual(helperStart, -1)
  assert.notEqual(start, -1)
  assert.notEqual(end, -1)
  const helper = source.slice(helperStart, start)
  const barrier = source.slice(start, end)
  assert.match(helper, /ls-api-call!\s+:editor\.appendBlockInPage\s+title/)
  assert.doesNotMatch(helper, /editor-wrapper|textarea|new-block|\btry\b|\bcatch\b|\brecur\b/)
  assert.equal((barrier.match(/append-barrier-marker!/g) ?? []).length, 2)
  assert.doesNotMatch(barrier, /b\/new-block\b|editor-wrapper|textarea/)
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
