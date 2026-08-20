import { spawnSync } from 'node:child_process'

const exactShaPattern = /^[0-9a-f]{40}$/

const git = (repoRoot, args, label) => {
  const result = spawnSync('git', args, {
    cwd: repoRoot,
    encoding: 'utf8',
    shell: false,
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  if (result.error) throw result.error
  if (result.signal) throw new Error(`${label} terminated by ${result.signal}`)
  if (result.status !== 0) {
    throw new Error(`${label} failed: ${result.stderr.trim()}`)
  }
  return result.stdout
}

const resolveHead = (repoRoot) => {
  const head = git(repoRoot, ['rev-parse', 'HEAD'], 'git rev-parse HEAD').trim()
  if (!exactShaPattern.test(head)) {
    throw new Error('git rev-parse HEAD must be an exact lowercase 40-hex SHA')
  }
  return head
}

const worktreeStatus = (repoRoot, { includeUntracked = true } = {}) =>
  git(
    repoRoot,
    [
      'status',
      '--porcelain=v1',
      includeUntracked ? '--untracked-files=all' : '--untracked-files=no',
      '--',
      '.',
      ':(exclude)static/package.json',
      ':(exclude)static/pnpm-lock.yaml',
    ],
    'git status'
  )

const resolveProvidedIdentity = (environment, name, head) => {
  const value = environment[name]
  if (value === undefined) return head
  if (!exactShaPattern.test(value)) {
    throw new Error(`${name} must be an exact lowercase 40-hex SHA`)
  }
  if (value !== head) {
    throw new Error(`${name} must equal the actual HEAD ${head}`)
  }
  return value
}

export const establishReleaseSourceIdentity = ({
  repoRoot,
  environment = process.env,
  allowDirty = false,
}) => {
  const head = resolveHead(repoRoot)
  const sourceSha = resolveProvidedIdentity(
    environment,
    'LOGSEQ_RELEASE_SOURCE_SHA',
    head
  )
  const revision = resolveProvidedIdentity(environment, 'LOGSEQ_REVISION', head)
  const status = worktreeStatus(repoRoot, { includeUntracked: !allowDirty })
  if (!allowDirty && status !== '') {
    throw new Error('release source worktree must be clean')
  }
  environment.LOGSEQ_RELEASE_SOURCE_SHA = sourceSha
  environment.LOGSEQ_REVISION = revision
  return Object.freeze({
    allowDirty,
    head,
    revision,
    sourceSha,
    worktreeStatus: status,
  })
}

export const assertReleaseSourceIdentityUnchanged = (
  identity,
  { repoRoot, environment = process.env, phase = 'release completion' }
) => {
  for (const [name, expected] of [
    ['LOGSEQ_RELEASE_SOURCE_SHA', identity.sourceSha],
    ['LOGSEQ_REVISION', identity.revision],
  ]) {
    const actual = environment[name]
    if (!exactShaPattern.test(actual ?? '') || actual !== expected) {
      throw new Error(`${name} changed during ${phase}`)
    }
  }
  const head = resolveHead(repoRoot)
  if (head !== identity.head) {
    throw new Error(`release source HEAD changed during ${phase}`)
  }
  const status = worktreeStatus(repoRoot, {
    includeUntracked: !identity.allowDirty,
  })
  if (status !== identity.worktreeStatus) {
    throw new Error(
      `release source worktree changed during ${phase}: ` +
        `baseline=${JSON.stringify(identity.worktreeStatus)} ` +
        `current=${JSON.stringify(status)}`
    )
  }
  return identity
}
