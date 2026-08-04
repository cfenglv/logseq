#!/usr/bin/env node

import assert from 'node:assert/strict'
import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import process from 'node:process'
import { createRequire } from 'node:module'
import { execFileSync, spawnSync } from 'node:child_process'
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
const fixtureRoot = path.join(
  repositoryRoot,
  'scripts/fixtures/selfhost-macos-updater'
)
const updaterHelperPath = path.join(
  repositoryRoot,
  'resources/selfhost-updater-version.mjs'
)
const resourcesPackagePath = path.join(repositoryRoot, 'resources/package.json')

const workflowSource = fs.readFileSync(workflowPath, 'utf8')
const resourcesPackage = JSON.parse(
  fs.readFileSync(resourcesPackagePath, 'utf8')
)
const packageRoot = process.env.LOGSEQ_UPDATER_TEST_PACKAGE_ROOT
  ? path.resolve(process.env.LOGSEQ_UPDATER_TEST_PACKAGE_ROOT)
  : path.join(repositoryRoot, 'static')
const dependencyRequire = createRequire(path.join(packageRoot, 'package.json'))
const updaterPackagePath = dependencyRequire.resolve(
  'electron-updater/package.json'
)
const updaterPackage = JSON.parse(fs.readFileSync(updaterPackagePath, 'utf8'))
const updaterRequire = createRequire(updaterPackagePath)
const builderUtilRuntimePackagePath = updaterRequire.resolve(
  'builder-util-runtime/package.json'
)
const builderUtilRuntimePackage = JSON.parse(
  fs.readFileSync(builderUtilRuntimePackagePath, 'utf8')
)
const { GitHubProvider } = dependencyRequire(
  'electron-updater/out/providers/GitHubProvider'
)
const { HttpError } = updaterRequire('builder-util-runtime')
const semver = dependencyRequire('semver')

const currentLegacyVersion = '2.0.1-selfhost.4'
const firstV2Version = '2.0.1-selfhost.5'
const nextV2Version = '2.0.1-selfhost.6'
const owner = 'logseq'
const repository = 'logseq'
// Byte-for-byte digests of the metadata published in release 2.0.1-selfhost.4.
const pinnedLegacyMetadataSha256 = {
  arm64: '2dd11f39538c801cf2356a40e753b8f6a9963641df6951e13ed3493b1c5ed705',
  x64: '7b35999d6cd7edcd54b08944bca4112abb39e6fc2f12b7d2f602a2c35cdb8ec0',
}
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

function pinnedLegacyMetadata(arch) {
  return fs.readFileSync(
    path.join(fixtureRoot, legacyMetadataName(arch)),
    'utf8'
  )
}

function sha256(value) {
  return crypto.createHash('sha256').update(value).digest('hex')
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

function workflowAndReferencedScripts(jobName) {
  const job = extractWorkflowJob(jobName)
  const referencedSources = [
    ...job.matchAll(/(?:^|[\s"'(])([\w./-]+\.mjs)(?=$|[\s"')])/gm),
  ]
    .map((match) => path.resolve(repositoryRoot, match[1]))
    .filter(
      (candidate) =>
        candidate.startsWith(`${repositoryRoot}${path.sep}`) &&
        fs.existsSync(candidate)
    )
    .map((candidate) => fs.readFileSync(candidate, 'utf8'))
  return [job, ...referencedSources].join('\n')
}

function assertNightlyPublicationIsolation() {
  const nightlyJob = extractWorkflowJob('nightly-release')
  assert.match(
    nightlyJob,
    /tag_name:\s*(?:nightly|['"]nightly['"]|\${{\s*steps\.[\w-]+\.outputs\.(?:version|tag)\s*}})\s*$/m,
    'nightly publication must stay on an isolated rolling/prerelease release instead of production/latest'
  )
  const prereleaseValue = nightlyJob.match(
    /prerelease:\s*([^\n]+)/
  )?.[1]?.trim()
  assert.ok(
    prereleaseValue,
    'nightly publication must declare its GitHub prerelease state'
  )
  const literalPrerelease =
    /^(?:true|['"]true['"])$/.test(prereleaseValue)
  const positiveSelfhostClause =
    /contains\(\s*[^,]*(?:version|tag)[^,]*,\s*['"]-selfhost\.['"]\s*\)/i
  const scopedSelfhostPrerelease =
    positiveSelfhostClause.test(prereleaseValue) &&
    !/&&|!\s*contains|\bnot\s*\(/i.test(prereleaseValue)
  assert.equal(
    literalPrerelease || scopedSelfhostPrerelease,
    true,
    'every selfhost nightly publication must be an unconditional GitHub prerelease'
  )

  const nightlyPublicationSource =
    workflowAndReferencedScripts('nightly-release')
  const stablePublicationSource = workflowAndReferencedScripts('release')
  assert.match(
    nightlyPublicationSource,
    /(?:releases\/download\/nightly|tag_name:\s*['"]?nightly['"]?)/,
    'nightly publication does not expose an isolated rolling nightly feed'
  )
  assert.doesNotMatch(
    stablePublicationSource,
    /(?:releases\/download\/nightly|tag_name:\s*['"]?nightly['"]?\s*$)/m,
    'stable publication is coupled to the rolling nightly feed'
  )
  for (const arch of ['x64', 'arm64']) {
    const literalMetadata = new RegExp(
      `(?:${arch}[^\\s"'/]*mac|mac[^\\s"'/]*${arch})[^\\s"']*\\.ya?ml`,
      'i'
    )
    const computedMetadata = new RegExp(
      `(?:metadata|channel)[^\\n]{0,180}${arch}`,
      'i'
    )
    assert.ok(
      literalMetadata.test(nightlyPublicationSource) ||
        computedMetadata.test(nightlyPublicationSource),
      `nightly-release does not keep macOS ${arch} metadata architecture-specific`
    )
  }
}

function fixtureArtifactNames() {
  return [
    `Logseq-darwin-arm64-${firstV2Version}.dmg`,
    `Logseq-darwin-arm64-${firstV2Version}.dmg.blockmap`,
    `Logseq-darwin-arm64-${firstV2Version}.zip`,
    `Logseq-darwin-arm64-${firstV2Version}.zip.blockmap`,
    `Logseq-darwin-x64-${firstV2Version}.dmg`,
    `Logseq-darwin-x64-${firstV2Version}.dmg.blockmap`,
    `Logseq-darwin-x64-${firstV2Version}.zip`,
    `Logseq-darwin-x64-${firstV2Version}.zip.blockmap`,
    `Logseq-linux-arm64-${firstV2Version}.AppImage`,
    `Logseq-linux-arm64-${firstV2Version}.zip`,
    `Logseq-linux-x86_64-${firstV2Version}.AppImage`,
    `Logseq-linux-x86_64-${firstV2Version}.zip`,
    `Logseq-win-arm64-${firstV2Version}-nsis.exe`,
    `Logseq-win-arm64-${firstV2Version}-nsis.exe.blockmap`,
    `Logseq-win-arm64-${firstV2Version}.zip`,
    `Logseq-win-x64-${firstV2Version}-nsis.exe`,
    `Logseq-win-x64-${firstV2Version}-nsis.exe.blockmap`,
    `Logseq-win-x64-${firstV2Version}.zip`,
  ]
}

function sha512(filePath) {
  return crypto
    .createHash('sha512')
    .update(fs.readFileSync(filePath))
    .digest('base64')
}

function updaterMetadataForFiles(version, releaseDir, artifactNames) {
  const entries = artifactNames.flatMap((name) => {
    const filePath = path.join(releaseDir, name)
    return [
      `  - url: ${name}`,
      `    sha512: ${sha512(filePath)}`,
      `    size: ${fs.statSync(filePath).size}`,
    ]
  })
  return [
    `version: ${version}`,
    'files:',
    ...entries,
    `path: ${artifactNames[0]}`,
    `sha512: ${sha512(path.join(releaseDir, artifactNames[0]))}`,
    `releaseDate: '2026-07-29T00:00:00.000Z'`,
    '',
  ].join('\n')
}

function createCompleteReleaseFixture() {
  const releaseDir = fs.mkdtempSync(
    path.join(os.tmpdir(), 'logseq-selfhost5-release-contract-')
  )
  for (const name of fixtureArtifactNames()) {
    fs.writeFileSync(path.join(releaseDir, name), `fixture:${name}\n`)
  }

  const metadataFiles = {
    [expectedV2MetadataName('arm64')]: [
      `Logseq-darwin-arm64-${firstV2Version}.zip`,
      `Logseq-darwin-arm64-${firstV2Version}.dmg`,
    ],
    [expectedV2MetadataName('x64')]: [
      `Logseq-darwin-x64-${firstV2Version}.zip`,
      `Logseq-darwin-x64-${firstV2Version}.dmg`,
    ],
    'latest-arm64.yml': [
      `Logseq-win-arm64-${firstV2Version}-nsis.exe`,
      `Logseq-win-arm64-${firstV2Version}.zip`,
    ],
    'latest-linux-arm64.yml': [
      `Logseq-linux-arm64-${firstV2Version}.AppImage`,
      `Logseq-linux-arm64-${firstV2Version}.zip`,
    ],
    'latest-linux.yml': [
      `Logseq-linux-x86_64-${firstV2Version}.AppImage`,
      `Logseq-linux-x86_64-${firstV2Version}.zip`,
    ],
    'latest-x64.yml': [
      `Logseq-win-x64-${firstV2Version}-nsis.exe`,
      `Logseq-win-x64-${firstV2Version}.zip`,
    ],
  }
  for (const [name, artifacts] of Object.entries(metadataFiles)) {
    fs.writeFileSync(
      path.join(releaseDir, name),
      updaterMetadataForFiles(firstV2Version, releaseDir, artifacts)
    )
  }
  for (const arch of ['x64', 'arm64']) {
    fs.writeFileSync(
      path.join(releaseDir, legacyMetadataName(arch)),
      pinnedLegacyMetadata(arch)
    )
  }
  fs.writeFileSync(path.join(releaseDir, 'VERSION'), `${firstV2Version}\n`)
  return releaseDir
}

function runArtifactVerifier(releaseDir) {
  return spawnSync(
    process.execPath,
    [artifactVerifierPath, '--dir', releaseDir, '--version', firstV2Version],
    {
      cwd: repositoryRoot,
      encoding: 'utf8',
    }
  )
}

function verifierOutput(result) {
  return `${result.stdout ?? ''}\n${result.stderr ?? ''}`.trim()
}

function assertVerifierAccepts(releaseDir) {
  const result = runArtifactVerifier(releaseDir)
  assert.equal(
    result.status,
    0,
    `release asset verifier rejected the complete dual-channel fixture:\n${verifierOutput(
      result
    )}`
  )
}

function assertVerifierRejects(releaseDir, description) {
  const result = runArtifactVerifier(releaseDir)
  assert.notEqual(
    result.status,
    0,
    `release asset verifier accepted ${description}`
  )
}

function withReleaseFixture(run) {
  const releaseDir = createCompleteReleaseFixture()
  try {
    return run(releaseDir)
  } finally {
    fs.rmSync(releaseDir, { recursive: true, force: true })
  }
}

function assertReleaseAssetVerifierContract() {
  withReleaseFixture((releaseDir) => {
    assert.equal(
      fs.readdirSync(releaseDir).length,
      27,
      'controlled .5 release fixture must contain its complete dual-channel asset set'
    )
    assertVerifierAccepts(releaseDir)
  })

  for (const arch of ['x64', 'arm64']) {
    withReleaseFixture((releaseDir) => {
      fs.rmSync(path.join(releaseDir, legacyMetadataName(arch)))
      assertVerifierRejects(
        releaseDir,
        `a release missing ${legacyMetadataName(arch)}`
      )
    })
    withReleaseFixture((releaseDir) => {
      fs.appendFileSync(
        path.join(releaseDir, legacyMetadataName(arch)),
        '# tampered\n'
      )
      assertVerifierRejects(
        releaseDir,
        `a release with tampered ${legacyMetadataName(arch)}`
      )
    })
  }
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
  allowPrerelease = false,
  channel,
  currentVersion,
  latestVersion,
  arch,
  metadataByName,
}) {
  const requestedMetadataNames = []
  const updater = {
    allowPrerelease,
    channel:
      channel ??
      runUpdaterHelper(
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
  const metadataByName = new Map([
    [legacyMetadataName(arch), pinnedLegacyMetadata(arch)],
  ])

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

test('runtime dependencies use the pinned electron-updater package boundary', () => {
  assert.equal(resourcesPackage.dependencies['electron-updater'], '6.8.3')
  assert.equal(
    resourcesPackage.dependencies['builder-util-runtime'],
    undefined,
    'builder-util-runtime must remain transitive instead of being hoisted into the app manifest'
  )
  assert.equal(updaterPackage.version, '6.8.3')
  assert.equal(
    updaterPackage.dependencies?.['builder-util-runtime'],
    '9.5.1',
    'electron-updater must retain the exact locked builder-util-runtime dependency'
  )
  assert.equal(
    builderUtilRuntimePackage.version,
    updaterPackage.dependencies['builder-util-runtime'],
    'the builder-util-runtime resolved from electron-updater must match its exact dependency version'
  )
})

test('published .4 legacy metadata fixtures have their pinned digests', () => {
  for (const arch of ['x64', 'arm64']) {
    assert.equal(
      sha256(pinnedLegacyMetadata(arch)),
      pinnedLegacyMetadataSha256[arch],
      `${legacyMetadataName(
        arch
      )} must remain byte-for-byte identical to the published .4 asset`
    )
  }
})

test('release asset verifier enforces both pinned legacy channel files', () => {
  assertReleaseAssetVerifierContract()
})

test('nightly publication cannot pollute GitHub production/latest discovery', () => {
  assertNightlyPublicationIsolation()
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
