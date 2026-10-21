import assert from 'node:assert/strict'
import { execFileSync, spawnSync } from 'node:child_process'
import { createHash, generateKeyPairSync } from 'node:crypto'
import fs from 'node:fs'
import { createRequire } from 'node:module'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath, pathToFileURL } from 'node:url'

import { bridgeImmutableAssetNames } from '../lib/selfhost6-existing-release-promotion.mjs'
import { getBridgeArchiveReader } from './verify-offline-bridge.mjs'

const { parse } = createRequire(import.meta.url)('yaml')
const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const workflow = parse(
  fs.readFileSync(
    path.join(repo, '.github/workflows/build-desktop-release.yml'),
    'utf8'
  )
)
const finalSha = 'b'.repeat(40)

test('the bridge finishing job signs real CLI outputs and verifies the exact five pairs before upload', async (context) => {
  const job = workflow.jobs['selfhost-bridge-signing']
  assert.ok(
    job,
    'native bridge builds need a signing, pairing and archive owner'
  )
  assert.deepEqual(job.needs, [
    'selfhost-bridge-build-inputs',
    'selfhost-bridge-build-macos',
    'selfhost-bridge-build-linux',
    'selfhost-bridge-build-windows',
  ])
  assert.equal(job.environment, 'selfhost-release-signing')
  assert.deepEqual(job.permissions, { actions: 'read', contents: 'read' })
  assert.doesNotMatch(
    JSON.stringify(job),
    /contents:write|gh release|promote-existing-release|bridge-published/
  )

  const root = fs.realpathSync(
    fs.mkdtempSync(path.join(os.tmpdir(), 'bridge-workflow-entry-'))
  )
  context.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const write = (name, payload) => {
    fs.mkdirSync(path.dirname(name), { recursive: true })
    fs.writeFileSync(name, payload)
  }
  const json = (name, value) =>
    write(name, JSON.stringify(value, null, 2) + '\n')
  const modules = [
    'scripts/lib/selfhost6-release-identity.mjs',
    'scripts/lib/selfhost6-update-feed.mjs',
    'scripts/lib/selfhost6-existing-release-promotion.mjs',
    'scripts/lib/windows-archive-reader.mjs',
    'scripts/selfhost6/prepare-update-artifact.mjs',
    'scripts/selfhost6/verify-offline-bridge.mjs',
    'resources/updater/project-update-signature.mjs',
    'resources/updater/target-build-manifest.mjs',
    'resources/updater/project-signing-policy.json',
    'resources/updater/selfhost-release-policy.json',
  ]
  const bridgeRoot = path.join(root, 'bridge-source')
  for (const target of [root, bridgeRoot])
    for (const name of modules)
      write(path.join(target, name), fs.readFileSync(path.join(repo, name)))
  fs.symlinkSync(path.join(repo, 'static'), path.join(root, 'static'), 'dir')
  const { privateKey, publicKey } = generateKeyPairSync('ed25519')
  const key = privateKey
    .export({ format: 'der', type: 'pkcs8' })
    .toString('base64')
  const raw = publicKey.export({ format: 'der', type: 'spki' }).subarray(-32)
  const signing = JSON.parse(fs.readFileSync(path.join(root, modules.at(-2))))
  signing.publicKeyBase64 = raw.toString('base64')
  signing.keyId = 'ed25519:' + createHash('sha256').update(raw).digest('hex')
  for (const target of [root, bridgeRoot])
    json(path.join(target, modules.at(-2)), signing)
  const identity = await import(pathToFileURL(path.join(root, modules[0])))
  const policy = identity.readReleasePolicy(path.join(root, modules.at(-1)))
  const bridgePolicy = {
    ...policy,
    sourceVersion: '2.0.1-selfhost.7',
    syntheticForwardTargetVersion: '2.0.1-selfhost.8',
  }
  json(path.join(bridgeRoot, modules.at(-1)), bridgePolicy)
  // The independent bridge reader pins its own version; only these two fixture
  // constants differ. The signing CLI and workflow shell remain verbatim.
  write(
    path.join(bridgeRoot, modules[0]),
    fs
      .readFileSync(path.join(root, modules[0]), 'utf8')
      .replace(
        'assert.equal(policy.sourceVersion, "2.0.2-selfhost.1")',
        'assert.equal(policy.sourceVersion, "2.0.1-selfhost.7")'
      )
      .replace(
        'assert.equal(policy.syntheticForwardTargetVersion, "2.0.2-selfhost.2")',
        'assert.equal(policy.syntheticForwardTargetVersion, "2.0.1-selfhost.8")'
      )
  )
  execFileSync('git', ['init', '--quiet', bridgeRoot])
  execFileSync(
    'git',
    [
      '-c',
      'user.name=Build Fixture',
      '-c',
      'user.email=build@example.invalid',
      '-c',
      'commit.gpgsign=false',
      'commit',
      '--allow-empty',
      '--quiet',
      '-m',
      'test: bridge fixture',
    ],
    { cwd: bridgeRoot }
  )
  const actualBridgeSha = execFileSync('git', ['rev-parse', 'HEAD'], {
    cwd: bridgeRoot,
    encoding: 'utf8',
  }).trim()
  const env = {
    PATH: process.env.PATH,
    NODE_PATH: process.env.NODE_PATH,
    TMPDIR: process.env.TMPDIR,
    BRIDGE_SOURCE_FULL_SHA: actualBridgeSha,
    FINAL_SOURCE_FULL_SHA: finalSha,
    SELFHOST6_TARGET_VERSION: policy.sourceVersion,
  }
  const invoke = (script, args) => {
    const result = spawnSync(process.execPath, [script, ...args], {
      cwd: root,
      encoding: 'utf8',
      env: { ...env, LOGSEQ_PROJECT_UPDATE_SIGNING_KEY_PKCS8_BASE64: key },
    })
    assert.equal(result.status, 0, result.stderr)
  }
  const reader = await getBridgeArchiveReader()
  for (const [index, name] of bridgeImmutableAssetNames(
    bridgePolicy.sourceVersion
  ).entries()) {
    const platform = name.includes('darwin')
      ? 'darwin'
      : name.includes('linux')
      ? 'linux'
      : 'win32'
    const arch = name.includes('arm64') ? 'arm64' : 'x64'
    const finalName = name.replace(
      bridgePolicy.sourceVersion,
      policy.sourceVersion
    )
    const payload = path.join(root, 'published-release-assets', finalName)
    write(payload, `synthetic final ${platform}/${arch}\n`)
    const contents = path.join(root, `contents-${index}`),
      manifest = path.join(contents, 'TARGET_BUILD_MANIFEST.json')
    json(
      manifest,
      identity.buildTargetManifest({
        policy,
        sourceFullSha: finalSha,
        targetVersion: policy.sourceVersion,
        platform,
        arch,
        signingKeyIdentity: signing.keyId,
      })
    )
    invoke(path.join(root, 'scripts/selfhost6/prepare-update-artifact.mjs'), [
      '--archive',
      payload,
      '--target-manifest',
      manifest,
      '--source-full-sha',
      finalSha,
      '--target-version',
      policy.sourceVersion,
      '--platform',
      platform,
      '--arch',
      arch,
      '--output-directory',
      path.join(root, 'signed-final'),
    ])
    fs.copyFileSync(
      path.join(root, 'signed-final', finalName + '.selfhost6.json'),
      payload + '.selfhost6.json'
    )
    fs.mkdirSync(path.join(contents, 'payload'))
    for (const suffix of ['', '.selfhost6.json'])
      fs.copyFileSync(
        payload + suffix,
        path.join(contents, 'payload', finalName + suffix)
      )
    json(
      manifest,
      identity.buildTargetManifest({
        policy: bridgePolicy,
        sourceFullSha: actualBridgeSha,
        targetVersion: bridgePolicy.sourceVersion,
        platform,
        arch,
        signingKeyIdentity: signing.keyId,
      })
    )
    const input = path.join(
      root,
      'unsigned-bridge',
      `selfhost-offline-bridge-input-${platform}-${arch}-${actualBridgeSha}-${finalSha}`
    )
    fs.mkdirSync(input, { recursive: true })
    // ZIP containers exercise signing/pairing contracts, not native installer execution.
    execFileSync(reader, ['a', '-tzip', path.join(input, name), '.'], {
      cwd: contents,
      stdio: 'pipe',
    })
    fs.copyFileSync(manifest, path.join(input, 'TARGET_BUILD_MANIFEST.json'))
  }
  const step = (name) => {
    const value = job.steps.find((item) => item.name === name)
    assert.ok(value, name)
    return value
  }
  const run = (name) => {
    const current = step(name)
    const stepEnv = Object.fromEntries(
      Object.entries(current.env ?? {}).map(([name, value]) => {
        assert.equal(
          value,
          '${{ secrets.LOGSEQ_PROJECT_UPDATE_SIGNING_KEY_PKCS8_BASE64 }}'
        )
        return [name, key]
      })
    )
    return spawnSync('bash', ['-e', '-o', 'pipefail', '-c', current.run], {
      cwd: root,
      encoding: 'utf8',
      timeout: 30000,
      env: { ...env, ...stepEnv },
    })
  }
  const signed = run('Sign the five native bridge descriptors')
  assert.equal(signed.status, 0, signed.stderr)
  const verified = run('Verify exact bridge and final payload pairs')
  assert.equal(verified.status, 0, verified.stderr)
  assert.deepEqual(JSON.parse(verified.stdout), {
    status: 'offline-bridge-set-verified',
  })
  const upload = step('Archive the signed and paired bridge set')
  assert.equal(upload.uses, 'actions/upload-artifact@v4')
  assert.equal(upload.with.path, 'bridge-release-assets')
  assert.equal(
    upload.with.name,
    'selfhost-offline-bridge-${{ env.BRIDGE_SOURCE_FULL_SHA }}-${{ env.FINAL_SOURCE_FULL_SHA }}'
  )
  assert.equal(upload.with['if-no-files-found'], 'error')
  assert.equal(fs.readdirSync(path.join(root, upload.with.path)).length, 10)
  assert.ok(
    job.steps.indexOf(step('Verify exact bridge and final payload pairs')) <
      job.steps.indexOf(upload)
  )
  const final = path.join(
    root,
    'published-release-assets',
    fs
      .readdirSync(path.join(root, 'published-release-assets'))
      .find((name) => !name.endsWith('.selfhost6.json'))
  )
  fs.appendFileSync(final, 'changed')
  const wrongPair = run('Verify exact bridge and final payload pairs')
  assert.notEqual(
    wrongPair.status,
    0,
    'changed final bytes must stop before artifact upload'
  )
  assert.match(wrongPair.stderr, /at verifyArtifactDescriptor/)
})
