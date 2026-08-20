import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const shaPattern = /^[0-9a-f]{40}$/;
const supportedTargets = new Set([
  "darwin/arm64",
  "darwin/x64",
  "win32/x64",
  "win32/arm64",
  "linux/x64",
  "linux/arm64",
]);

export function readReleasePolicy(policyPath) {
  const policy = JSON.parse(fs.readFileSync(policyPath, "utf8"));
  assert.equal(policy.schemaVersion, 1);
  assert.equal(policy.releaseLineId, "selfhost-official-architecture-v1");
  assert.equal(policy.sourceVersion, "2.0.1-selfhost.6");
  assert.equal(policy.syntheticForwardTargetVersion, "2.0.1-selfhost.7");
  assert.equal(policy.bundleIdentity, "com.logseq.logseq");
  assert.equal(policy.forwardUpdateChannel, policy.releaseLineId);
  assert.deepEqual(policy.provider, {
    kind: "generic",
    baseUrl: "https://github.com/cfenglv/logseq/releases/download/selfhost-official-architecture-v1",
    remoteMutation: "promotion-only",
  });
  assert.equal(policy.firstInstall, "controlled-manual-artifact");
  assert.equal(policy.allowDowngrade, false);
  assert.equal(policy.sameVersionUpdate, false);
  assert.equal(policy.legacySharedLatestMutation, false);
  assert.deepEqual(policy.legacyChannelsAccepted, []);
  assert.equal(policy.withdrawnArchiveSha256Denylist.length, 8);
  assert.equal(new Set(policy.withdrawnArchiveSha256Denylist).size, 8);
  assert.ok(policy.withdrawnArchiveSha256Denylist.every((digest) => /^[0-9a-f]{64}$/.test(digest)));
  assert.deepEqual(policy.readableActivationFormats, ["selfhost-activation-v1"]);
  assert.deepEqual(policy.readableClientOpsFormats, ["official-client-ops-sqlite-v2+selfhost-upload-v1"]);
  return policy;
}

export function buildTargetManifest({
  policy,
  sourceFullSha,
  targetVersion,
  platform,
  arch,
  signingKeyIdentity,
}) {
  assert.match(sourceFullSha, shaPattern, "source full SHA must be 40 lowercase hex characters");
  assert.ok(
    [policy.sourceVersion, policy.syntheticForwardTargetVersion].includes(targetVersion),
    "target version must be the reissued .6 or its synthetic .7 fixture",
  );
  assert.ok(supportedTargets.has(`${platform}/${arch}`), "unsupported platform/arch");
  assert.equal(typeof signingKeyIdentity, "string");
  assert.ok(signingKeyIdentity.length > 0, "signing key identity is required");
  return {
    "schema-version": 1,
    "target-source-full-sha": sourceFullSha,
    "target-version": targetVersion,
    "release-line-id": policy.releaseLineId,
    platform,
    arch,
    "bundle-identity": policy.bundleIdentity,
    "signing-key-identity": signingKeyIdentity,
    "readable-activation-formats": policy.readableActivationFormats,
    "readable-client-ops-formats": policy.readableClientOpsFormats,
    "activation-write-format": policy.activationWriteFormat,
    "client-ops-write-format": policy.clientOpsWriteFormat,
  };
}

export function writeTargetManifest(outputPath, manifest) {
  const resolved = path.resolve(outputPath);
  const systemTempRoot = `${path.resolve(os.tmpdir())}${path.sep}`;
  const allowedRepoSuffix = `${path.sep}static${path.sep}updater${path.sep}TARGET_BUILD_MANIFEST.json`;
  assert.ok(
    resolved.startsWith(systemTempRoot) || resolved.startsWith("/private/tmp/") || resolved.endsWith(allowedRepoSuffix),
    "target manifest output must be under a temporary directory or static/updater/TARGET_BUILD_MANIFEST.json",
  );
  fs.mkdirSync(path.dirname(resolved), { recursive: true });
  fs.writeFileSync(resolved, `${JSON.stringify(manifest, null, 2)}\n`, { mode: 0o600 });
}
