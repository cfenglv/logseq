#!/usr/bin/env node

import assert from 'node:assert/strict'
import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import process from 'node:process'
import { createRequire } from 'node:module'
import { execFileSync, spawnSync } from 'node:child_process'
import { fileURLToPath, pathToFileURL } from 'node:url'

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
const updaterVersionContract = await import(
  pathToFileURL(updaterHelperPath).href
)
const isSelfhostAutomaticUpdateAllowed =
  updaterVersionContract.isSelfhostAutomaticUpdateAllowed
const isSelfhostUpdateInfoAllowed =
  updaterVersionContract.isSelfhostUpdateInfoAllowed

const packageRoot = process.env.LOGSEQ_UPDATER_TEST_PACKAGE_ROOT
  ? path.resolve(process.env.LOGSEQ_UPDATER_TEST_PACKAGE_ROOT)
  : path.join(repositoryRoot, 'static')
const dependencyRequire = createRequire(path.join(packageRoot, 'package.json'))
const { GitHubProvider } = dependencyRequire(
  'electron-updater/out/providers/GitHubProvider'
)
const { AppUpdater } = dependencyRequire('electron-updater/out/AppUpdater')
const { HttpError } = dependencyRequire('builder-util-runtime')
const semver = dependencyRequire('semver')

const currentLegacyVersion = '2.0.1-selfhost.4'
const firstV2Version = '2.0.1-selfhost.5'
const nextV2Version = '2.0.1-selfhost.6'
const sameRevisionStable = '2.0.1-selfhost.6'
const nightlyEarly = '2.0.1-selfhost.6.nightly.20260728'
const nightlyLate = '2.0.1-selfhost.6.nightly.20260729'
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
  assert.doesNotMatch(
    nightlyJob,
    /tag_name:\s*nightly\s*$/m,
    'nightly releases need their dated SemVer tag so GitHubProvider can discover their version'
  )
  assert.match(
    nightlyJob,
    /tag_name:\s*\${{\s*steps\.[\w-]+\.outputs\.version\s*}}\s*$/m,
    'nightly release tag must come from the exact built VERSION output'
  )
  assert.match(
    nightlyJob,
    /prerelease:\s*(?:true|['"]true['"])\s*$/m,
    'every nightly publication must be an unconditional GitHub prerelease'
  )
  assert.doesNotMatch(
    nightlyJob,
    /prerelease:[^\n]*(?:is-pre-release|event_name|schedule|workflow_dispatch)/,
    'manual nightly publication must not be allowed to become a production/latest release'
  )

  const nightlyPublicationSource =
    workflowAndReferencedScripts('nightly-release')
  const stablePublicationSource = workflowAndReferencedScripts('release')
  for (const metadataName of [
    'selfhost-mac.yml',
    'selfhost.yml',
    'selfhost-linux.yml',
    'selfhost-linux-arm64.yml',
  ]) {
    const pattern = new RegExp(
      `(?:^|[^A-Za-z0-9_-])${metadataName.replaceAll('.', '\\.')}(?:$|[^A-Za-z0-9_.-])`,
      'm'
    )
    assert.match(
      nightlyPublicationSource,
      pattern,
      `nightly-release does not publish ${metadataName} for electron-updater's standard selfhost prerelease channel`
    )
    assert.doesNotMatch(
      stablePublicationSource,
      pattern,
      `stable release exposes ${metadataName}; exiting nightly must remain a manual install`
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

async function assertProviderVersionOrdering({
  arch,
  candidateVersion,
  currentVersion,
  expectedUpdate,
  expectedSemverUpdate = expectedUpdate,
  label,
}) {
  const nightlyClient = currentVersion.includes('.nightly.')
  const metadataName = nightlyClient
    ? 'selfhost-mac.yml'
    : expectedV2MetadataName(arch)
  const metadataByName = new Map([
    [
      metadataName,
      updaterMetadata(
        candidateVersion,
        `Logseq-darwin-${arch}-${candidateVersion}.zip`
      ),
    ],
  ])
  const { provider, requestedMetadataNames } = createProvider({
    allowPrerelease: nightlyClient,
    channel: nightlyClient ? 'selfhost' : undefined,
    currentVersion,
    latestVersion: candidateVersion,
    arch,
    metadataByName,
  })
  const info = await provider.getLatestVersion()

  assert.deepEqual(
    requestedMetadataNames,
    [metadataName],
    `${label}: GitHubProvider did not read the signed v2 metadata channel`
  )
  assert.equal(
    info.version,
    candidateVersion,
    `${label}: GitHubProvider changed the candidate version`
  )
  assert.equal(
    semver.gt(info.version, currentVersion),
    expectedSemverUpdate,
    `${label}: semver made the wrong update decision`
  )
  const appUpdater = new AppUpdater(null, { version: currentVersion })
  appUpdater.allowDowngrade = false
  appUpdater.isUpdateSupported = (candidate) =>
    isSelfhostUpdateInfoAllowed(currentVersion, candidate, 'darwin', arch)
  appUpdater.isUserWithinRollout = () => true
  assert.equal(
    await appUpdater.isUpdateAvailable(info),
    expectedUpdate,
    `${label}: electron-updater AppUpdater made the wrong update decision`
  )
}

function assertUpdaterHelperRejectsVersion(version, arch) {
  const result = spawnSync(
    process.execPath,
    [updaterHelperPath, 'macos-metadata-name', version, arch],
    {
      cwd: repositoryRoot,
      encoding: 'utf8',
    }
  )
  assert.notEqual(
    result.status,
    0,
    `updater helper accepted forbidden nightly version ${version}`
  )
}

const cases = []

function test(name, run) {
  cases.push({ name, run })
}

test('runtime dependency is electron-updater 6.8.3', () => {
  assert.equal(resourcesPackage.dependencies['electron-updater'], '6.8.3')
  assert.equal(
    typeof isSelfhostAutomaticUpdateAllowed,
    'function',
    'selfhost updater runtime must expose its stable/nightly automatic-update policy'
  )
  assert.equal(
    typeof isSelfhostUpdateInfoAllowed,
    'function',
    'selfhost updater runtime must expose its platform/architecture metadata policy'
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

test('obsolete and malformed nightly versions fail closed before provider routing', () => {
  for (const invalidVersion of [
    '2.0.1-selfhost.6-alpha.nightly.20260729',
    '2.0.1-selfhost.6.nightly.20260230',
    '2.0.1-selfhost.6.nightly',
    '2.0.1-selfhost.6.nightly.20260729.extra',
  ]) {
    assert.ok(
      semver.valid(invalidVersion),
      `${invalidVersion} should demonstrate why generic SemVer validation is insufficient`
    )
    for (const arch of ['x64', 'arm64']) {
      assertUpdaterHelperRejectsVersion(invalidVersion, arch)
    }
  }
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

  for (const transition of [
    {
      currentVersion: '2.0.1-selfhost.5.nightly.20260729',
      candidateVersion: sameRevisionStable,
      expectedSemverUpdate: true,
      expectedUpdate: false,
      label: 'lower-revision nightly requires manual exit to higher stable',
    },
    {
      currentVersion: firstV2Version,
      candidateVersion: nightlyEarly,
      expectedSemverUpdate: true,
      expectedUpdate: false,
      label: 'stable refuses a polluted production/latest nightly',
    },
    {
      currentVersion: nightlyEarly,
      candidateVersion: nightlyLate,
      expectedUpdate: true,
      label: 'nightly date increases',
    },
    {
      currentVersion: nightlyLate,
      candidateVersion: nightlyEarly,
      expectedUpdate: false,
      label: 'nightly date cannot move backwards',
    },
    {
      currentVersion: nightlyLate,
      candidateVersion: sameRevisionStable,
      expectedUpdate: false,
      label: 'same-revision stable cannot replace a newer nightly',
    },
  ]) {
    test(`${arch}: ${transition.label} through GitHubProvider and AppUpdater`, async () => {
      await assertProviderVersionOrdering({ arch, ...transition })
    })
  }
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
