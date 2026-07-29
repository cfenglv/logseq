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
const installSelfhostTrackPolicy = (updater, currentVersion) => {
  const defaultIsUpdateSupported =
    updater.isUpdateSupported.bind(updater)
  updater.isUpdateSupported = async (updateInfo) =>
    (await defaultIsUpdateSupported(updateInfo)) &&
    selfhostProjectUpdateAllowed(currentVersion, updateInfo.version)
}
const electronUpdaterAvailable = async (
  currentVersion,
  candidateVersion,
  allowPrerelease
) => {
  const updater = makeAppUpdater(currentVersion)
  updater.allowPrerelease = allowPrerelease
  updater.allowDowngrade = false
  updater.isUserWithinRollout = () => true
  installSelfhostTrackPolicy(updater, currentVersion)
  return updater.isUpdateAvailable({ version: candidateVersion })
}
for (const [currentVersion, candidateVersion, expected, allowPrerelease] of [
  [stable, nextStable, true, false],
  [earlyNightly, lateNightly, true, true],
  [nextStable, lateNightly, false, false],
]) {
  assert.equal(
    await electronUpdaterAvailable(
      currentVersion,
      candidateVersion,
      allowPrerelease
    ),
    expected,
    `electron-updater discovery compare ${currentVersion} -> ${candidateVersion}`
  )
}

const misconfiguredStableUpdater = makeAppUpdater(stable)
misconfiguredStableUpdater.allowPrerelease = false
misconfiguredStableUpdater.allowDowngrade = false
misconfiguredStableUpdater.autoDownload = true
misconfiguredStableUpdater.isUserWithinRollout = () => true
installSelfhostTrackPolicy(misconfiguredStableUpdater, stable)
misconfiguredStableUpdater.getUpdateInfoAndProvider = async () => ({
  info: {
    version: earlyNightly,
    files: [{ url: 'nightly.zip', sha512: 'YQ==' }],
  },
  provider: {},
})
let misconfiguredDownloadCount = 0
misconfiguredStableUpdater.downloadUpdate = () => {
  misconfiguredDownloadCount += 1
  return Promise.resolve([])
}
const misconfiguredResult =
  await misconfiguredStableUpdater.doCheckForUpdates()
assert.equal(misconfiguredResult.isUpdateAvailable, false)
assert.equal(
  misconfiguredDownloadCount,
  0,
  'cross-track metadata must never reach electron-updater downloadUpdate'
)

const minimumSystemUpdater = makeAppUpdater(stable)
minimumSystemUpdater.allowDowngrade = false
minimumSystemUpdater.isUserWithinRollout = () => true
installSelfhostTrackPolicy(minimumSystemUpdater, stable)
assert.equal(
  await minimumSystemUpdater.isUpdateAvailable({
    version: nextStable,
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
  /^2\.0\.1-selfhost\.5\.nightly\.\d{8}$/,
  'the release workflow version helper must emit the ordered selfhost nightly form'
)

console.log(
  '[selfhost-nightly-semver-contract] PASS AppUpdater rejects cross-track download metadata, preserves minimumSystemVersion, and allows only isolated stable/nightly tracks'
)
