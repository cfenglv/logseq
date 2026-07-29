#!/usr/bin/env node

import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import { createRequire } from 'node:module'
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const scriptDir = path.dirname(fileURLToPath(import.meta.url))
const repositoryRoot = path.resolve(scriptDir, '..')
const workflowPath = path.join(
  repositoryRoot,
  '.github/workflows/build-desktop-release.yml'
)
const artifactVerifierPath = path.join(
  repositoryRoot,
  'scripts/verify-desktop-release-assets.mjs'
)
const updaterHelperPath = path.join(
  repositoryRoot,
  'resources/selfhost-updater-version.mjs'
)
const resourcesPackagePath = path.join(repositoryRoot, 'resources/package.json')

const workflowSource = fs.readFileSync(workflowPath, 'utf8')
const artifactVerifierSource = fs.readFileSync(artifactVerifierPath, 'utf8')
const resourcesPackage = JSON.parse(
  fs.readFileSync(resourcesPackagePath, 'utf8')
)

const packageRoot = process.env.LOGSEQ_UPDATER_TEST_PACKAGE_ROOT
  ? path.resolve(process.env.LOGSEQ_UPDATER_TEST_PACKAGE_ROOT)
  : path.join(repositoryRoot, 'static')
const dependencyRequire = createRequire(path.join(packageRoot, 'package.json'))
const { GitHubProvider } = dependencyRequire(
  'electron-updater/out/providers/GitHubProvider'
)
const { HttpError } = dependencyRequire('builder-util-runtime')
const semver = dependencyRequire('semver')

const currentLegacyVersion = '2.0.1-selfhost.4'
const firstV2Version = '2.0.1-selfhost.5'
const nextV2Version = '2.0.1-selfhost.6'
const owner = 'logseq'
const repository = 'logseq'
const providerRuntimeOptions = {
  platform: 'darwin',
  executor: {
    request() {
      throw new Error('executor.request was not configured for this test')
    },
  },
}

function extractWorkflowJob(jobName) {
  const marker = `  ${jobName}:`
  const start = workflowSource.indexOf(marker)
  assert.notEqual(start, -1, `workflow job ${jobName} must exist`)

  const tail = workflowSource.slice(start + marker.length)
  const nextJobOffset = tail.search(/^  [a-zA-Z0-9_-]+:/m)
  return nextJobOffset === -1 ? tail : tail.slice(0, nextJobOffset)
}

function workflowJobForArch(arch) {
  return extractWorkflowJob(
    arch === 'x64' ? 'build-macos-x64' : 'build-macos-arm64'
  )
}

function runUpdaterHelper(command, version, arch) {
  return execFileSync(
    process.execPath,
    [updaterHelperPath, command, version, arch],
    {
      cwd: repositoryRoot,
      encoding: 'utf8',
    }
  ).trim()
}

function expectedV2MetadataName(arch) {
  return `selfhost-macos-v2-${arch}-mac.yml`
}

function legacyMetadataName(arch) {
  return `latest-${arch}-mac.yml`
}

function assertWorkflowUsesTestedHelper(arch) {
  const job = workflowJobForArch(arch)
  const invocation = job.match(
    new RegExp(
      String.raw`node\s+([^\s"']*selfhost-updater-version\.mjs)\s+macos-metadata-name\s+"\$\{\{\s*steps\.ref\.outputs\.version\s*\}\}"\s+${arch}`
    )
  )

  assert.ok(
    invocation,
    `${arch} workflow must invoke the macOS metadata-name helper`
  )

  const invokedPath = invocation[1]
  let invokedFilesystemPath
  if (invokedPath.startsWith('static/')) {
    invokedFilesystemPath = path.join(
      packageRoot,
      invokedPath.slice('static/'.length)
    )
  } else if (invokedPath.startsWith('release-gate-source/')) {
    invokedFilesystemPath = path.join(
      repositoryRoot,
      invokedPath.slice('release-gate-source/'.length)
    )
  } else {
    invokedFilesystemPath = path.join(repositoryRoot, invokedPath)
  }

  assert.ok(
    fs.existsSync(invokedFilesystemPath),
    `${arch} workflow helper ${invokedPath} must exist in a clean release workspace`
  )
  const output = execFileSync(
    process.execPath,
    [invokedFilesystemPath, 'macos-metadata-name', firstV2Version, arch],
    {
      cwd: repositoryRoot,
      encoding: 'utf8',
    }
  ).trim()
  assert.equal(
    output,
    expectedV2MetadataName(arch),
    `${arch} workflow helper ${invokedPath} exited successfully but emitted ${JSON.stringify(
      output
    )}`
  )
}

function assertReleaseDoesNotRequireLegacyExactDrGate(arch) {
  const job = workflowJobForArch(arch)
  assert.doesNotMatch(
    job,
    /verify-macos-updater-signature\.mjs/,
    `${arch} .5 release success must not require an exact-DR authorization check against the already-published .4 build`
  )
}

function publishesFrozenLegacyMetadata(arch) {
  const name = legacyMetadataName(arch)
  const job = workflowJobForArch(arch)
  return (
    job.includes(name) && artifactVerifierSource.includes(JSON.stringify(name))
  )
}

function assertDualChannelMetadataIsPublished(arch) {
  const legacyName = legacyMetadataName(arch)
  const v2Name = expectedV2MetadataName(arch)
  const job = workflowJobForArch(arch)

  assert.ok(
    job.includes(legacyName),
    `${arch} .5 release must publish frozen ${legacyName} for .4 clients`
  )
  assert.ok(
    artifactVerifierSource.includes(JSON.stringify(legacyName)),
    `release asset verification must require ${legacyName}`
  )
  assert.match(
    job,
    /selfhost-updater-version\.mjs\s+macos-metadata-name/,
    `${arch} .5 release must also publish its versioned-v2 channel metadata`
  )
  assert.equal(
    runUpdaterHelper('macos-metadata-name', firstV2Version, arch),
    v2Name,
    `${arch} v2 metadata name must remain independently derivable`
  )
}

function releaseFeed(versions) {
  return `<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
${versions
  .map(
    (version, index) => `<entry>
  <title>${version}</title>
  <link href="https://github.com/${owner}/${repository}/releases/tag/${version}"/>
  <content>Release ${version}</content>
  <updated>2026-07-${String(20 - index).padStart(2, '0')}T00:00:00Z</updated>
</entry>`
  )
  .join('\n')}
</feed>`
}

function updaterMetadata(version, assetName) {
  return [
    `version: ${version}`,
    `files:`,
    `  - url: ${assetName}`,
    `    sha512: dGVzdA==`,
    `    size: 4`,
    `path: ${assetName}`,
    `sha512: dGVzdA==`,
    `releaseDate: '2026-07-20T00:00:00.000Z'`,
    '',
  ].join('\n')
}

function createProvider({
  currentVersion,
  latestVersion,
  arch,
  metadataByName,
}) {
  const requestedMetadataNames = []
  const updater = {
    allowPrerelease: false,
    channel: runUpdaterHelper(
      'macos-metadata-name',
      currentVersion,
      arch
    ).replace(/-mac\.yml$/, ''),
    currentVersion: semver.parse(currentVersion),
    fullChangelog: false,
  }
  assert.ok(updater.currentVersion, `semver must parse ${currentVersion}`)

  const provider = new GitHubProvider(
    {
      provider: 'github',
      owner,
      repo: repository,
    },
    updater,
    {
      ...providerRuntimeOptions,
      executor: {
        async request(options) {
          const metadataName = path.posix.basename(options.path)
          requestedMetadataNames.push(metadataName)
          const metadata = metadataByName.get(metadataName)
          if (metadata == null) {
            throw new HttpError(404)
          }
          return metadata
        },
      },
    }
  )

  provider.httpRequest = async (url) => {
    const href = typeof url === 'string' ? url : url.href
    if (href.endsWith('.atom')) {
      return releaseFeed([latestVersion, currentVersion])
    }
    if (href.endsWith('/releases/latest')) {
      return JSON.stringify({
        tag_name: latestVersion,
        prerelease: false,
      })
    }
    throw new Error(`unexpected GitHubProvider HTTP request: ${href}`)
  }

  return { provider, requestedMetadataNames }
}

async function assertLegacyClientGetsNoErrorAndNoUpdate(arch) {
  const metadataByName = new Map()
  if (publishesFrozenLegacyMetadata(arch)) {
    metadataByName.set(
      legacyMetadataName(arch),
      updaterMetadata(
        currentLegacyVersion,
        `Logseq-darwin-${arch}-${currentLegacyVersion}.zip`
      )
    )
  }

  const { provider, requestedMetadataNames } = createProvider({
    currentVersion: currentLegacyVersion,
    latestVersion: firstV2Version,
    arch,
    metadataByName,
  })
  const info = await provider.getLatestVersion()

  assert.deepEqual(
    requestedMetadataNames,
    [legacyMetadataName(arch)],
    `${arch} .4 client must query its legacy channel at the .5 latest tag`
  )
  assert.equal(
    info.version,
    currentLegacyVersion,
    `${arch} .4 client must see frozen .4 metadata, not the uninstallable .5 build`
  )
  assert.equal(
    semver.gt(info.version, currentLegacyVersion),
    false,
    `${arch} .4 client must not be offered an update`
  )
}

async function assertV2ChannelCanEvolve(arch) {
  const metadataName = expectedV2MetadataName(arch)
  const metadataByName = new Map([
    [
      metadataName,
      updaterMetadata(
        nextV2Version,
        `Logseq-darwin-${arch}-${nextV2Version}.zip`
      ),
    ],
  ])
  const { provider, requestedMetadataNames } = createProvider({
    currentVersion: firstV2Version,
    latestVersion: nextV2Version,
    arch,
    metadataByName,
  })
  const info = await provider.getLatestVersion()

  assert.deepEqual(requestedMetadataNames, [metadataName])
  assert.equal(info.version, nextV2Version)
  assert.equal(semver.gt(info.version, firstV2Version), true)
}

const cases = []

function test(name, run) {
  cases.push({ name, run })
}

test('runtime dependency is electron-updater 6.8.3', () => {
  assert.equal(resourcesPackage.dependencies['electron-updater'], '6.8.3')
})

for (const arch of ['x64', 'arm64']) {
  test(`${arch}: checked-in helper emits the exact .5 metadata filename`, () => {
    assert.equal(
      runUpdaterHelper('macos-metadata-name', firstV2Version, arch),
      expectedV2MetadataName(arch)
    )
  })

  test(`${arch}: workflow invokes the helper whose stdout was tested`, () => {
    assertWorkflowUsesTestedHelper(arch)
  })

  test(`${arch}: .5 release has no exact-DR gate against published .4`, () => {
    assertReleaseDoesNotRequireLegacyExactDrGate(arch)
  })

  test(`${arch}: .5 release publishes and verifies both channel files`, () => {
    assertDualChannelMetadataIsPublished(arch)
  })

  test(`${arch}: actual GitHubProvider lets .4 query latest without error or update`, async () => {
    await assertLegacyClientGetsNoErrorAndNoUpdate(arch)
  })

  test(`${arch}: actual GitHubProvider lets the .5 v2 channel evolve`, async () => {
    await assertV2ChannelCanEvolve(arch)
  })
}

const failures = []
for (const testCase of cases) {
  try {
    await testCase.run()
    console.log(`PASS ${testCase.name}`)
  } catch (error) {
    failures.push({ name: testCase.name, error })
    const code = error?.code == null ? '' : ` [${error.code}]`
    console.error(`FAIL ${testCase.name}${code}`)
    console.error(`     ${error?.message ?? String(error)}`)
  }
}

console.log(
  `\n${cases.length - failures.length} passed, ${failures.length} failed, ${
    cases.length
  } total`
)
if (failures.length > 0) {
  process.exitCode = 1
}
