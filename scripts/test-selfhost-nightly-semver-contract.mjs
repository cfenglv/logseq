#!/usr/bin/env node

import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import { createRequire } from 'node:module'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import semver from 'semver'
import {
  compareSelfhostProjectVersions,
  parseSelfhostProjectVersion,
  selfhostProjectUpdateAllowed,
  selfhostUpdateAssetContract,
  selfhostUpdateInfoAllowed,
} from '../resources/project-updater-signature.mjs'

const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..'
)
const stable = '2.0.1-selfhost.5'
const earlyNightly = '2.0.1-selfhost.5.nightly.20260728'
const lateNightly = '2.0.1-selfhost.5.nightly.20260729'
const nextStable = '2.0.1-selfhost.6'
const nextNightly = '2.0.1-selfhost.6.nightly.20260701'

for (const version of [
  stable,
  earlyNightly,
  lateNightly,
  nextStable,
  nextNightly,
]) {
  assert.equal(
    semver.valid(version),
    version,
    `${version} must be strict SemVer`
  )
  assert.doesNotThrow(() => parseSelfhostProjectVersion(version))
}

for (const [older, newer, label] of [
  [stable, earlyNightly, 'raw same-revision nightly precedence'],
  [earlyNightly, lateNightly, 'earlier to later same-revision nightly'],
  [lateNightly, nextStable, 'lower-revision nightly to higher stable'],
  [nextStable, nextNightly, 'next stable to its nightly'],
]) {
  assert.ok(semver.gt(newer, older), `semver must allow ${label}`)
  assert.ok(
    compareSelfhostProjectVersions(older, newer) < 0,
    `project runtime must agree with semver for ${label}`
  )
  assert.ok(
    compareSelfhostProjectVersions(newer, older) > 0,
    `project runtime must reject the reverse of ${label}`
  )
}
for (const [currentVersion, candidateVersion, expected, label] of [
  [stable, earlyNightly, false, 'stable clients cannot enter nightly'],
  [stable, nextStable, true, 'stable clients advance to stable'],
  [earlyNightly, lateNightly, true, 'nightly clients advance by date'],
  [lateNightly, nextNightly, true, 'nightly clients advance by revision'],
  [lateNightly, nextStable, false, 'nightly to stable requires manual install'],
]) {
  assert.equal(
    selfhostProjectUpdateAllowed(currentVersion, candidateVersion),
    expected,
    label
  )
}

for (const legacy of [
  '2.0.1-selfhost.5-alpha.nightly.20260729',
  '2.0.1-selfhost.5-nightly.20260729',
]) {
  assert.throws(
    () => parseSelfhostProjectVersion(legacy),
    /unsupported selfhost version/,
    `${legacy} must fail closed`
  )
}

const resourceLock = fs.readFileSync(
  path.join(repoRoot, 'resources', 'pnpm-lock.yaml'),
  'utf8'
)
assert.match(
  resourceLock,
  /electron-updater@6\.8\.3:[\s\S]*?\n\s+semver: 7\.7\.4/,
  'the packaged electron-updater must use the same pinned SemVer comparator'
)
const workflow = fs.readFileSync(
  path.join(repoRoot, '.github', 'workflows', 'build-desktop-release.yml'),
  'utf8'
)
assert.match(
  workflow,
  /Update rolling Nightly Release[\s\S]*?tag_name: nightly[\s\S]*?prerelease: \$\{\{ contains\(needs\.release-assets-preflight\.outputs\.version, '-selfhost\.'\)/,
  'selfhost nightlies must remain on the isolated rolling GitHub prerelease'
)
assert.doesNotMatch(
  workflow,
  /Publish selfhost dated Nightly Release|prerelease: false[\s\S]*?dated nightly/,
  'selfhost nightlies must never become the production latest release'
)
const updaterConfig = fs.readFileSync(
  path.join(repoRoot, 'src', 'electron', 'electron', 'updater_config.cljs'),
  'utf8'
)
assert.match(
  updaterConfig,
  /selfhost-nightly-feed-url[\s\S]*?releases\/download\/nightly[\s\S]*?:feed-url/,
  'nightly clients must use the isolated GenericProvider feed'
)

const requireFromDesktop = createRequire(
  path.join(repoRoot, 'static', 'package.json')
)
const { AppUpdater } = requireFromDesktop('electron-updater')
const makeAppUpdater = (currentVersion) =>
  new AppUpdater(null, {
    version: currentVersion,
    name: 'Logseq',
    isPackaged: true,
    appUpdateConfigPath: '',
    userDataPath: '',
    baseCachePath: '',
    quit() {},
    relaunch() {},
    onQuit() {},
  })
const installSelfhostUpdateInfoPolicy = (
  updater,
  { arch, currentVersion, platform }
) => {
  const defaultIsUpdateSupported =
    updater.isUpdateSupported.bind(updater)
  updater.isUpdateSupported = async (updateInfo) =>
    (await defaultIsUpdateSupported(updateInfo)) &&
    selfhostUpdateInfoAllowed({
      arch,
      currentVersion,
      platform,
      updateInfo,
    })
}
const makeUpdateInfo = ({
  arch,
  currentVersion,
  candidateVersion,
  platform,
  assetArch = arch,
  assetPlatform = platform,
  assetVersion = candidateVersion,
  nightlyTag,
}) => {
  const { allowed, primary } = selfhostUpdateAssetContract({
    arch: assetArch,
    platform: assetPlatform,
    version: assetVersion,
  })
  const updateInfo = {
    version: candidateVersion,
    files: allowed.map((url) => ({ url, sha512: 'YQ==' })),
    path: primary,
  }
  if (parseSelfhostProjectVersion(currentVersion).nightlyDate === undefined) {
    updateInfo.tag = candidateVersion
  } else if (nightlyTag !== undefined) {
    updateInfo.tag = nightlyTag
  }
  return updateInfo
}
const runActualAppUpdaterCheck = async ({
  arch,
  currentVersion,
  platform,
  updateInfo,
}) => {
  const updater = makeAppUpdater(currentVersion)
  updater.logger = { error() {}, info() {}, warn() {} }
  updater.allowPrerelease =
    parseSelfhostProjectVersion(currentVersion).nightlyDate !== undefined
  updater.allowDowngrade = false
  updater.autoDownload = true
  updater.isUserWithinRollout = () => true
  installSelfhostUpdateInfoPolicy(updater, {
    arch,
    currentVersion,
    platform,
  })
  updater.getUpdateInfoAndProvider = async () => ({
    info: updateInfo,
    provider: {},
  })
  let downloadCount = 0
  updater.downloadUpdate = () => {
    downloadCount += 1
    return Promise.resolve([])
  }
  const result = await updater.doCheckForUpdates()
  return { downloadCount, result }
}

const updaterTargets = [
  ['darwin', 'x64'],
  ['darwin', 'arm64'],
  ['win32', 'x64'],
  ['win32', 'arm64'],
  ['linux', 'x64'],
  ['linux', 'arm64'],
]
const otherPlatform = {
  darwin: 'win32',
  win32: 'linux',
  linux: 'darwin',
}
let appUpdaterMetadataCases = 0
for (const [platform, arch] of updaterTargets) {
  const validStable = makeUpdateInfo({
    arch,
    currentVersion: stable,
    candidateVersion: nextStable,
    platform,
  })
  const validNightly = makeUpdateInfo({
    arch,
    currentVersion: earlyNightly,
    candidateVersion: lateNightly,
    platform,
  })
  const rollingNightly = makeUpdateInfo({
    arch,
    currentVersion: earlyNightly,
    candidateVersion: lateNightly,
    nightlyTag: 'nightly',
    platform,
  })
  const crossWiredFile = makeUpdateInfo({
    arch,
    assetArch: arch === 'x64' ? 'arm64' : 'x64',
    currentVersion: stable,
    candidateVersion: nextStable,
    platform,
  }).files[0]
  for (const [label, currentVersion, updateInfo, expectedDownloads] of [
    ['valid stable', stable, validStable, 1],
    ['valid nightly without tag', earlyNightly, validNightly, 1],
    ['valid rolling nightly tag', earlyNightly, rollingNightly, 1],
    [
      'wrong architecture',
      stable,
      makeUpdateInfo({
        arch,
        assetArch: arch === 'x64' ? 'arm64' : 'x64',
        currentVersion: stable,
        candidateVersion: nextStable,
        platform,
      }),
      0,
    ],
    [
      'correct primary with cross-wired extra file',
      stable,
      { ...validStable, files: [...validStable.files, crossWiredFile] },
      0,
    ],
    [
      'wrong platform',
      stable,
      makeUpdateInfo({
        arch,
        assetPlatform: otherPlatform[platform],
        currentVersion: stable,
        candidateVersion: nextStable,
        platform,
      }),
      0,
    ],
    [
      'wrong asset version',
      stable,
      makeUpdateInfo({
        arch,
        assetVersion: '2.0.1-selfhost.7',
        currentVersion: stable,
        candidateVersion: nextStable,
        platform,
      }),
      0,
    ],
    [
      'stable tag/version mismatch',
      stable,
      { ...validStable, tag: stable },
      0,
    ],
    [
      'legacy path does not name the primary installer',
      stable,
      { ...validStable, path: validStable.files[1].url },
      0,
    ],
    [
      'stable to nightly cross-track metadata',
      stable,
      makeUpdateInfo({
        arch,
        currentVersion: stable,
        candidateVersion: earlyNightly,
        platform,
      }),
      0,
    ],
    [
      'nightly candidate version tag instead of rolling tag',
      earlyNightly,
      { ...validNightly, tag: lateNightly },
      0,
    ],
  ]) {
    const { downloadCount, result } = await runActualAppUpdaterCheck({
      arch,
      currentVersion,
      platform,
      updateInfo,
    })
    assert.equal(
      downloadCount,
      expectedDownloads,
      `${platform}/${arch} ${label} download count`
    )
    assert.equal(
      result.isUpdateAvailable,
      expectedDownloads === 1,
      `${platform}/${arch} ${label} availability`
    )
    appUpdaterMetadataCases += 1
  }
}
assert.equal(appUpdaterMetadataCases, 66)

const minimumSystemUpdater = makeAppUpdater(stable)
minimumSystemUpdater.allowDowngrade = false
minimumSystemUpdater.isUserWithinRollout = () => true
installSelfhostUpdateInfoPolicy(minimumSystemUpdater, {
  arch: 'arm64',
  currentVersion: stable,
  platform: 'darwin',
})
assert.equal(
  await minimumSystemUpdater.isUpdateAvailable({
    ...makeUpdateInfo({
      arch: 'arm64',
      currentVersion: stable,
      candidateVersion: nextStable,
      platform: 'darwin',
    }),
    minimumSystemVersion: '9999.0.0',
  }),
  false,
  'the selfhost track wrapper must preserve the default minimumSystemVersion rejection'
)

const generatedNightly = execFileSync(
  process.execPath,
  [path.join(repoRoot, 'scripts', 'get-pkg-version.js'), 'nightly'],
  { encoding: 'utf8' }
).trim()
assert.match(
  generatedNightly,
  new RegExp(`^${nextStable.replaceAll('.', '\\.')}\\.nightly\\.\\d{8}$`),
  'the release workflow version helper must emit the ordered selfhost nightly form'
)

console.log(
  `[selfhost-nightly-semver-contract] PASS AppUpdater metadata cases=${appUpdaterMetadataCases}; wrong track/tag/platform/arch/version never downloads, valid stable/nightly downloads once, minimumSystemVersion is preserved`
)
