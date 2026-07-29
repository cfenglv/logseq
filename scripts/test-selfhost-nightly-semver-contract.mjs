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
  [stable, earlyNightly, 'stable to same-revision nightly'],
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
  /Publish selfhost dated Nightly Release[\s\S]*?tag_name: \$\{\{ needs\.release-assets-preflight\.outputs\.version \}\}[\s\S]*?prerelease: false/,
  'selfhost nightlies must use their strict version tag and remain GitHub production releases so electron-updater can discover them'
)
const updaterConfig = fs.readFileSync(
  path.join(repoRoot, 'src', 'electron', 'electron', 'updater_config.cljs'),
  'utf8'
)
assert.match(
  updaterConfig,
  /:allow-prerelease\? \(when \(selfhost-version\? version\) false\)/,
  "selfhost clients must use GitHub's production-release discovery path"
)

const requireFromDesktop = createRequire(
  path.join(repoRoot, 'static', 'package.json')
)
const { AppUpdater } = requireFromDesktop('electron-updater')
const electronUpdaterAvailable = async (currentVersion, candidateVersion) => {
  const updater = new AppUpdater(null, {
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
  updater.allowPrerelease = false
  updater.allowDowngrade = false
  updater.isUpdateSupported = () => true
  updater.isUserWithinRollout = () => true
  return updater.isUpdateAvailable({ version: candidateVersion })
}
for (const [currentVersion, candidateVersion, expected] of [
  [stable, earlyNightly, true],
  [earlyNightly, lateNightly, true],
  [lateNightly, stable, false],
  [lateNightly, nextStable, true],
  [nextStable, lateNightly, false],
]) {
  assert.equal(
    await electronUpdaterAvailable(currentVersion, candidateVersion),
    expected,
    `electron-updater discovery compare ${currentVersion} -> ${candidateVersion}`
  )
}

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
  '[selfhost-nightly-semver-contract] PASS electron-updater semver discovery and project runtime ordering agree'
)
