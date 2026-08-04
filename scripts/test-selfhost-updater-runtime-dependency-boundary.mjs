#!/usr/bin/env node

import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import { createRequire } from 'node:module'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..'
)
const contractPath = path.join(
  repositoryRoot,
  'scripts/test-selfhost-macos-updater-release-contract.mjs'
)
const contractSource = fs.readFileSync(contractPath, 'utf8')
const resourcesPackage = JSON.parse(
  fs.readFileSync(path.join(repositoryRoot, 'resources/package.json'), 'utf8')
)
const resourcesLock = fs.readFileSync(
  path.join(repositoryRoot, 'resources/pnpm-lock.yaml'),
  'utf8'
)
const lockedUpdaterVersion = resourcesPackage.dependencies?.['electron-updater']
const lockedSemverVersion = resourcesPackage.dependencies?.semver
const lockedRuntimeVersion = resourcesLock.match(
  /^  builder-util-runtime@([^:]+):$/m
)?.[1]

for (const [name, version] of [
  ['electron-updater', lockedUpdaterVersion],
  ['semver', lockedSemverVersion],
  ['builder-util-runtime', lockedRuntimeVersion],
]) {
  assert.match(version ?? '', /^\d+\.\d+\.\d+$/, `${name} must be pinned`)
}
assert.equal(
  Object.hasOwn(resourcesPackage.dependencies ?? {}, 'builder-util-runtime'),
  false,
  'builder-util-runtime must remain a transitive dependency'
)
assert.match(
  resourcesLock,
  new RegExp(
    `^  electron-updater@${lockedUpdaterVersion.replaceAll('.', '\\.')}:\\n` +
      `    dependencies:\\n(?:      .+\\n)*?` +
      `      builder-util-runtime: ${lockedRuntimeVersion.replaceAll('.', '\\.')}$`,
    'm'
  ),
  'lockfile must bind builder-util-runtime to electron-updater'
)

const dependencyBlockStart = contractSource.indexOf('const packageRoot')
const dependencyBlockEnd = contractSource.indexOf(
  '\n\nconst currentLegacyVersion',
  dependencyBlockStart
)
assert.notEqual(dependencyBlockStart, -1, 'contract dependency block is missing')
assert.notEqual(dependencyBlockEnd, -1, 'contract dependency block end is missing')
const dependencyBlock = contractSource.slice(
  dependencyBlockStart,
  dependencyBlockEnd
)

function writeJson(filePath, value) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true })
  fs.writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`)
}

function writeModule(packageRoot, packageJson, source) {
  writeJson(path.join(packageRoot, 'package.json'), packageJson)
  fs.writeFileSync(path.join(packageRoot, packageJson.main), source)
}

function writeRuntimeDecoy(packageRoot, markerPath) {
  writeModule(
    packageRoot,
    {
      main: 'index.js',
      name: 'builder-util-runtime',
      version: lockedRuntimeVersion,
    },
    [
      "const fs = require('node:fs')",
      `fs.writeFileSync(${JSON.stringify(markerPath)}, 'loaded\\n')`,
      'class HttpError extends Error {}',
      `HttpError.runtimeVersion = ${JSON.stringify(lockedRuntimeVersion)}`,
      'exports.HttpError = HttpError',
      '',
    ].join('\n')
  )
}

function createStrictPnpmFixture(
  arch,
  { runtimeVersion = lockedRuntimeVersion, updaterVersion = lockedUpdaterVersion } = {}
) {
  const root = fs.mkdtempSync(
    path.join(os.tmpdir(), `logseq-updater-runtime-${arch}-`)
  )
  const packageRoot = path.join(root, 'static')
  const nodeModules = path.join(packageRoot, 'node_modules')
  const virtualStore = path.join(nodeModules, '.pnpm')
  const updaterStoreRoot = path.join(
    virtualStore,
    `electron-updater@${lockedUpdaterVersion}`,
    'node_modules'
  )
  const updaterRoot = path.join(updaterStoreRoot, 'electron-updater')
  const runtimeRoot = path.join(
    virtualStore,
    `builder-util-runtime@${lockedRuntimeVersion}`,
    'node_modules',
    'builder-util-runtime'
  )
  const semverRoot = path.join(
    virtualStore,
    `semver@${lockedSemverVersion}`,
    'node_modules',
    'semver'
  )
  const privateRuntimeLink = path.join(
    updaterStoreRoot,
    'builder-util-runtime'
  )

  writeJson(path.join(packageRoot, 'package.json'), {
    dependencies: {
      'electron-updater': lockedUpdaterVersion,
      semver: lockedSemverVersion,
    },
    name: `strict-updater-contract-${arch}`,
    private: true,
  })
  writeModule(
    updaterRoot,
    {
      dependencies: { 'builder-util-runtime': lockedRuntimeVersion },
      main: 'index.js',
      name: 'electron-updater',
      version: updaterVersion,
    },
    'exports.fixture = true\n'
  )
  fs.mkdirSync(path.join(updaterRoot, 'out', 'providers'), { recursive: true })
  fs.writeFileSync(
    path.join(updaterRoot, 'out', 'providers', 'GitHubProvider.js'),
    'exports.GitHubProvider = class GitHubProvider {}\n'
  )
  writeModule(
    runtimeRoot,
    {
      main: 'index.js',
      name: 'builder-util-runtime',
      version: runtimeVersion,
    },
    [
      'class HttpError extends Error {}',
      `HttpError.runtimeVersion = ${JSON.stringify(runtimeVersion)}`,
      'exports.HttpError = HttpError',
      '',
    ].join('\n')
  )
  writeModule(
    semverRoot,
    { main: 'index.js', name: 'semver', version: lockedSemverVersion },
    'exports.fixture = "semver"\n'
  )

  fs.mkdirSync(nodeModules, { recursive: true })
  fs.symlinkSync(updaterRoot, path.join(nodeModules, 'electron-updater'), 'dir')
  fs.symlinkSync(semverRoot, path.join(nodeModules, 'semver'), 'dir')
  fs.symlinkSync(runtimeRoot, privateRuntimeLink, 'dir')

  return {
    arch,
    dispose: () => fs.rmSync(root, { force: true, recursive: true }),
    nodeModules,
    packageRoot,
    privateRuntimeLink,
    root,
    runtimeRoot,
    updaterRoot,
  }
}

function addTopLevelRuntimeDecoy(fixture) {
  const markerPath = path.join(fixture.root, 'top-level-decoy-loaded')
  const decoyRoot = path.join(
    fixture.root,
    'decoys',
    'top-level',
    'builder-util-runtime'
  )
  writeRuntimeDecoy(decoyRoot, markerPath)
  fs.symlinkSync(
    decoyRoot,
    path.join(fixture.nodeModules, 'builder-util-runtime'),
    'dir'
  )
  return { decoyRoot, markerPath }
}

function pointPrivateRuntimeAtWrongPhysicalTarget(fixture) {
  const markerPath = path.join(fixture.root, 'wrong-private-target-loaded')
  const decoyRoot = path.join(
    fixture.root,
    'decoys',
    'wrong-private-target',
    'builder-util-runtime'
  )
  writeRuntimeDecoy(decoyRoot, markerPath)
  fs.unlinkSync(fixture.privateRuntimeLink)
  fs.symlinkSync(decoyRoot, fixture.privateRuntimeLink, 'dir')
  return { decoyRoot, markerPath }
}

function assertContractRejectsPhysicalDecoy(fixture, markerPath, scenario) {
  const result = runContractDependencyBlock(fixture)
  assert.notEqual(result.status, 0, `${fixture.arch} accepted ${scenario}`)
  assert.equal(result.stdout, '', `${fixture.arch} continued after ${scenario}`)
  assert.match(result.stderr, /builder-util-runtime/i)
  assert.match(
    result.stderr,
    /private(?: dependency)? edge|realpath|boundary/i,
    `${fixture.arch} did not diagnose the dependency boundary for ${scenario}`
  )
  assert.equal(
    fs.existsSync(markerPath),
    false,
    `${fixture.arch} loaded the ${scenario} decoy before failing closed`
  )
}

function resolveLockedRuntimeFromUpdater(fixture) {
  const packageJson = JSON.parse(
    fs.readFileSync(path.join(fixture.packageRoot, 'package.json'), 'utf8')
  )
  const packageRequire = createRequire(
    path.join(fixture.packageRoot, 'package.json')
  )
  const updaterPackagePath = packageRequire.resolve(
    'electron-updater/package.json'
  )
  const updaterPackage = JSON.parse(
    fs.readFileSync(updaterPackagePath, 'utf8')
  )
  assert.equal(
    updaterPackage.version,
    packageJson.dependencies['electron-updater'],
    'electron-updater version mismatch at locked parent boundary'
  )
  const updaterRequire = createRequire(updaterPackagePath)
  const runtimePackagePath = updaterRequire.resolve(
    'builder-util-runtime/package.json'
  )
  const runtimePackage = JSON.parse(
    fs.readFileSync(runtimePackagePath, 'utf8')
  )
  assert.equal(
    runtimePackage.version,
    updaterPackage.dependencies['builder-util-runtime'],
    'builder-util-runtime version mismatch at locked updater boundary'
  )
  return {
    HttpError: updaterRequire('builder-util-runtime').HttpError,
    runtimePackagePath,
    updaterPackagePath,
  }
}

function runContractDependencyBlock(fixture) {
  const runnerSource = [
    'import fs from "node:fs"',
    'import path from "node:path"',
    'import process from "node:process"',
    'import { createRequire } from "node:module"',
    'const repositoryRoot = process.cwd()',
    'const resourcesPackagePath = path.join(process.env.LOGSEQ_UPDATER_TEST_PACKAGE_ROOT, "package.json")',
    'const resourcesPackage = JSON.parse(fs.readFileSync(resourcesPackagePath, "utf8"))',
    dependencyBlock,
    'process.stdout.write(JSON.stringify({',
    '  providerType: typeof GitHubProvider,',
    '  runtimeType: typeof HttpError,',
    '  runtimeVersion: HttpError.runtimeVersion,',
    '  semverFixture: semver.fixture,',
    '}))',
    '',
  ].join('\n')
  return spawnSync(
    process.execPath,
    ['--input-type=module', '--eval', runnerSource],
    {
      encoding: 'utf8',
      env: {
        ...process.env,
        LOGSEQ_UPDATER_TEST_PACKAGE_ROOT: fixture.packageRoot,
      },
    }
  )
}

function topLevelPackageNames(fixture) {
  return fs
    .readdirSync(fixture.nodeModules)
    .filter((entry) => !entry.startsWith('.'))
    .sort()
}

test('contract does not load transitive runtime from the package root', () => {
  assert.doesNotMatch(
    dependencyBlock,
    /dependencyRequire\(\s*['"]builder-util-runtime['"]\s*\)/,
    'contract must create a require boundary from locked electron-updater'
  )
})

for (const arch of ['x64', 'arm64']) {
  test(`${arch} strict pnpm layout resolves runtime from locked updater boundary`, () => {
    const fixture = createStrictPnpmFixture(arch)
    try {
      assert.deepEqual(topLevelPackageNames(fixture), [
        'electron-updater',
        'semver',
      ])
      const packageRequire = createRequire(
        path.join(fixture.packageRoot, 'package.json')
      )
      assert.throws(
        () => packageRequire.resolve('builder-util-runtime'),
        (error) => error?.code === 'MODULE_NOT_FOUND'
      )
      const fixtureControl = resolveLockedRuntimeFromUpdater(fixture)
      assert.equal(fixtureControl.HttpError.runtimeVersion, lockedRuntimeVersion)
      const result = runContractDependencyBlock(fixture)
      assert.equal(
        result.status,
        0,
        `${arch} contract depended on a transitive hoist:\n${result.stdout}\n${result.stderr}`
      )
      assert.deepEqual(JSON.parse(result.stdout), {
        providerType: 'function',
        runtimeType: 'function',
        runtimeVersion: lockedRuntimeVersion,
        semverFixture: 'semver',
      })
    } finally {
      fixture.dispose()
    }
  })

  test(`${arch} broken updater-private runtime chain fails closed`, () => {
    const fixture = createStrictPnpmFixture(arch)
    try {
      fs.unlinkSync(fixture.privateRuntimeLink)
      assert.throws(
        () => resolveLockedRuntimeFromUpdater(fixture),
        (error) => error?.code === 'MODULE_NOT_FOUND'
      )
      const result = runContractDependencyBlock(fixture)
      assert.notEqual(result.status, 0)
      assert.equal(result.stdout, '')
      assert.match(result.stderr, /builder-util-runtime/i)
      assert.match(result.stderr, /(?:MODULE_NOT_FOUND|resolve|missing|not found)/i)
    } finally {
      fixture.dispose()
    }
  })

  test(`${arch} broken private edge cannot fall back to exact-version top-level runtime`, () => {
    const fixture = createStrictPnpmFixture(arch)
    try {
      fs.unlinkSync(fixture.privateRuntimeLink)
      const { markerPath } = addTopLevelRuntimeDecoy(fixture)
      assert.deepEqual(topLevelPackageNames(fixture), [
        'builder-util-runtime',
        'electron-updater',
        'semver',
      ])
      const updaterRequire = createRequire(
        path.join(fixture.updaterRoot, 'package.json')
      )
      assert.equal(
        updaterRequire('builder-util-runtime').HttpError.runtimeVersion,
        lockedRuntimeVersion,
        'negative control must reproduce updater require falling back to the exact-version top-level decoy'
      )
      assert.equal(fs.readFileSync(markerPath, 'utf8'), 'loaded\n')
      fs.unlinkSync(markerPath)
      assertContractRejectsPhysicalDecoy(
        fixture,
        markerPath,
        'top-level fallback after a broken private edge'
      )
    } finally {
      fixture.dispose()
    }
  })

  test(`${arch} private edge rejects same-version wrong physical target`, () => {
    const fixture = createStrictPnpmFixture(arch)
    try {
      const { decoyRoot, markerPath } =
        pointPrivateRuntimeAtWrongPhysicalTarget(fixture)
      assert.equal(
        fs.realpathSync(fixture.privateRuntimeLink),
        fs.realpathSync(decoyRoot),
        'negative control must point the private edge at the wrong target'
      )
      assert.equal(
        JSON.parse(
          fs.readFileSync(path.join(decoyRoot, 'package.json'), 'utf8')
        ).version,
        lockedRuntimeVersion,
        'wrong physical target must keep the exact locked version'
      )
      const updaterRequire = createRequire(
        path.join(fixture.updaterRoot, 'package.json')
      )
      assert.equal(
        updaterRequire('builder-util-runtime').HttpError.runtimeVersion,
        lockedRuntimeVersion,
        'negative control must expose a loadable private edge to the wrong physical target'
      )
      assert.equal(fs.readFileSync(markerPath, 'utf8'), 'loaded\n')
      fs.unlinkSync(markerPath)
      assertContractRejectsPhysicalDecoy(
        fixture,
        markerPath,
        'same-version wrong physical target'
      )
    } finally {
      fixture.dispose()
    }
  })

  for (const mismatch of [
    {
      expected: [/electron-updater/i, /version|lock|mismatch/i],
      fixtureOptions: { updaterVersion: '0.0.0' },
      name: 'mismatched updater parent',
    },
    {
      expected: [/builder-util-runtime/i, /version|lock|mismatch/i],
      fixtureOptions: { runtimeVersion: '0.0.0' },
      name: 'mismatched private runtime',
    },
  ]) {
    test(`${arch} ${mismatch.name} fails closed`, () => {
      const fixture = createStrictPnpmFixture(arch, mismatch.fixtureOptions)
      try {
        assert.throws(
          () => resolveLockedRuntimeFromUpdater(fixture),
          /version mismatch at locked/i
        )
        const result = runContractDependencyBlock(fixture)
        assert.notEqual(result.status, 0, `${arch} accepted ${mismatch.name}`)
        assert.equal(result.stdout, '')
        for (const pattern of mismatch.expected) {
          assert.match(result.stderr, pattern)
        }
      } finally {
        fixture.dispose()
      }
    })
  }
}

test('x64 and arm64 fixtures expose identical strict dependency layouts', () => {
  const fixtures = ['x64', 'arm64'].map(createStrictPnpmFixture)
  try {
    const manifests = fixtures.map((fixture) => ({
      direct: topLevelPackageNames(fixture),
      privateRuntime: path.relative(
        fixture.packageRoot,
        fixture.privateRuntimeLink
      ),
      updater: path.relative(fixture.packageRoot, fixture.updaterRoot),
    }))
    assert.deepEqual(manifests[0], manifests[1])
  } finally {
    for (const fixture of fixtures) fixture.dispose()
  }
})
