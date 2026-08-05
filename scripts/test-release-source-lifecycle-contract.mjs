#!/usr/bin/env node

import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import {
  assertReleaseSourceIdentityUnchanged,
  establishReleaseSourceIdentity,
} from './release-source-identity.mjs'

const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..'
)

const git = (root, args) => {
  const result = spawnSync('git', args, {
    cwd: root,
    encoding: 'utf8',
    shell: false,
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  if (result.error) throw result.error
  assert.equal(result.status, 0, result.stderr)
  return result.stdout.trim()
}

const withRepository = (body) => {
  const root = fs.mkdtempSync(
    path.join(os.tmpdir(), 'logseq-release-source-lifecycle-')
  )
  try {
    git(root, ['init'])
    git(root, ['config', 'user.name', 'Release Contract'])
    git(root, ['config', 'user.email', 'release-contract@example.invalid'])
    fs.writeFileSync(path.join(root, 'source.txt'), 'initial\n')
    git(root, ['add', 'source.txt'])
    git(root, ['commit', '-m', 'initial'])
    body(root)
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
}

test('bare desktop preflight derives and exports the exact clean HEAD', () => {
  withRepository((root) => {
    const environment = {}
    const head = git(root, ['rev-parse', 'HEAD'])
    const identity = establishReleaseSourceIdentity({
      environment,
      repoRoot: root,
    })
    assert.equal(identity.sourceSha, head)
    assert.equal(identity.revision, head)
    assert.equal(environment.LOGSEQ_RELEASE_SOURCE_SHA, head)
    assert.equal(environment.LOGSEQ_REVISION, head)
    assert.doesNotThrow(() =>
      assertReleaseSourceIdentityUnchanged(identity, {
        environment,
        phase: 'unchanged control',
        repoRoot: root,
      })
    )
  })
})

test('provided release identity values are raw exact lowercase SHAs', () => {
  withRepository((root) => {
    const head = git(root, ['rev-parse', 'HEAD'])
    for (const [name, environment] of [
      ['source leading space', { LOGSEQ_RELEASE_SOURCE_SHA: ` ${head}` }],
      ['source trailing space', { LOGSEQ_RELEASE_SOURCE_SHA: `${head} ` }],
      ['revision leading space', { LOGSEQ_REVISION: ` ${head}` }],
      ['revision trailing space', { LOGSEQ_REVISION: `${head} ` }],
      ['short source', { LOGSEQ_RELEASE_SOURCE_SHA: head.slice(0, 12) }],
      ['wrong source', { LOGSEQ_RELEASE_SOURCE_SHA: '8'.repeat(40) }],
      ['wrong revision', { LOGSEQ_REVISION: '9'.repeat(40) }],
    ]) {
      assert.throws(
        () => establishReleaseSourceIdentity({ environment, repoRoot: root }),
        /exact lowercase 40-hex|actual HEAD/i,
        name
      )
    }

    const sourceOnly = { LOGSEQ_RELEASE_SOURCE_SHA: head }
    establishReleaseSourceIdentity({ environment: sourceOnly, repoRoot: root })
    assert.equal(sourceOnly.LOGSEQ_REVISION, head)

    const revisionOnly = { LOGSEQ_REVISION: head }
    establishReleaseSourceIdentity({
      environment: revisionOnly,
      repoRoot: root,
    })
    assert.equal(revisionOnly.LOGSEQ_RELEASE_SOURCE_SHA, head)
  })
})

test('tracked build-time source changes fail the closing identity check', () => {
  withRepository((root) => {
    const environment = {}
    const identity = establishReleaseSourceIdentity({
      environment,
      repoRoot: root,
    })
    fs.writeFileSync(path.join(root, 'source.txt'), 'changed during build\n')
    assert.throws(
      () =>
        assertReleaseSourceIdentityUnchanged(identity, {
          environment,
          phase: 'before FULL PASS',
          repoRoot: root,
        }),
      /worktree changed.*before FULL PASS|before FULL PASS.*worktree changed/i
    )
  })
})

test('untracked build-time source changes fail the closing identity check', () => {
  withRepository((root) => {
    const environment = {}
    const identity = establishReleaseSourceIdentity({
      environment,
      repoRoot: root,
    })
    fs.writeFileSync(
      path.join(root, 'late-source.mjs'),
      'export default true;\n'
    )
    assert.throws(
      () =>
        assertReleaseSourceIdentityUnchanged(identity, {
          environment,
          phase: 'before FULL PASS',
          repoRoot: root,
        }),
      /worktree changed.*before FULL PASS|before FULL PASS.*worktree changed/i
    )
  })
})

test('a build-time HEAD change fails before stale artifacts are blamed', () => {
  withRepository((root) => {
    const environment = {}
    const identity = establishReleaseSourceIdentity({
      environment,
      repoRoot: root,
    })
    fs.writeFileSync(path.join(root, 'source.txt'), 'next commit\n')
    git(root, ['add', 'source.txt'])
    git(root, ['commit', '-m', 'change during build'])
    assert.throws(
      () =>
        assertReleaseSourceIdentityUnchanged(identity, {
          environment,
          phase: 'before runtime verification',
          repoRoot: root,
        }),
      /HEAD changed.*before runtime verification|before runtime verification.*HEAD changed/i
    )
  })
})

test('README and AGENTS expose the usable bare full preflight command', () => {
  for (const relativePath of ['README.md', 'AGENTS.md']) {
    assert.match(
      fs.readFileSync(path.join(repoRoot, relativePath), 'utf8'),
      /pnpm desktop:release-preflight(?:`|\s|$)/,
      `${relativePath} must expose the bare full preflight command`
    )
  }
})
