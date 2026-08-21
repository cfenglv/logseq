import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";

import {
  signUpdateMetadata,
  verifySignedUpdateMetadata,
} from "../../resources/updater/project-update-signature.mjs";

const descriptorKind = "selfhost6-signed-update-artifact-v1";
const channelSuffix = new Map([
  ["darwin/x64", "-mac"],
  ["darwin/arm64", "-mac"],
  ["win32/x64", ""],
  ["win32/arm64", ""],
  ["linux/x64", "-linux"],
  ["linux/arm64", "-linux-arm64"],
]);

function digestFile(filePath, algorithm) {
  return createHash(algorithm).update(fs.readFileSync(filePath)).digest("hex");
}

function extensionFor(filePath) {
  const lower = filePath.toLowerCase();
  if (lower.endsWith(".appimage")) return ".AppImage";
  if (lower.endsWith(".dmg")) return ".dmg";
  if (lower.endsWith(".zip")) return ".zip";
  if (lower.endsWith(".exe")) return ".exe";
  throw new Error("unsupported update archive extension");
}

function assertManifestIdentity(manifest, input, releasePolicy, signingPolicy) {
  const expected = {
    "target-source-full-sha": input.sourceFullSha,
    "target-version": input.targetVersion,
    "release-line-id": releasePolicy.releaseLineId,
    platform: input.platform,
    arch: input.arch,
    "bundle-identity": releasePolicy.bundleIdentity,
    "signing-key-identity": signingPolicy.keyId,
  };
  for (const [field, value] of Object.entries(expected)) {
    assert.equal(manifest[field], value, `target manifest ${field} differs from release input`);
  }
  assert.deepEqual(manifest["readable-activation-formats"], releasePolicy.readableActivationFormats);
  assert.deepEqual(manifest["readable-client-ops-formats"], releasePolicy.readableClientOpsFormats);
  assert.equal(manifest["activation-write-format"], releasePolicy.activationWriteFormat);
  assert.equal(manifest["client-ops-write-format"], releasePolicy.clientOpsWriteFormat);
}

export function channelFileName(releasePolicy, platform, arch) {
  const suffix = channelSuffix.get(`${platform}/${arch}`);
  assert.notEqual(suffix, undefined, "unsupported promotion platform/arch");
  return `${releasePolicy.forwardUpdateChannel}${suffix}.yml`;
}

export function prepareSignedArtifact({
  archivePath,
  targetManifestPath,
  sourceFullSha,
  targetVersion,
  platform,
  arch,
  releasePolicy,
  signingPolicy,
  privateKeyPem,
}) {
  assert.ok(fs.statSync(archivePath).isFile(), "update archive must be a file");
  const manifestBytes = fs.readFileSync(targetManifestPath);
  const manifest = JSON.parse(manifestBytes);
  assertManifestIdentity(
    manifest,
    { sourceFullSha, targetVersion, platform, arch },
    releasePolicy,
    signingPolicy,
  );

  const archiveSha256 = digestFile(archivePath, "sha256");
  const archiveSha512 = digestFile(archivePath, "sha512");
  const assetName = [
    "Logseq",
    platform,
    arch,
    targetVersion,
  ].join("-") + extensionFor(archivePath);
  const immutableObjectKey = [
    releasePolicy.releaseLineId,
    sourceFullSha,
    archiveSha256,
    platform,
    arch,
    assetName,
  ].join("/");
  const metadata = {
    "schema-version": 1,
    algorithm: signingPolicy.algorithm,
    "key-id": signingPolicy.keyId,
    "release-line-id": releasePolicy.releaseLineId,
    "target-source-full-sha": sourceFullSha,
    "target-version": targetVersion,
    platform,
    arch,
    "bundle-identity": releasePolicy.bundleIdentity,
    "immutable-object-key": immutableObjectKey,
    "archive-size": fs.statSync(archivePath).size,
    "archive-sha256": archiveSha256,
    "archive-sha512": archiveSha512,
    "target-build-manifest-sha256": createHash("sha256").update(manifestBytes).digest("hex"),
    "readable-activation-formats": manifest["readable-activation-formats"],
    "readable-client-ops-formats": manifest["readable-client-ops-formats"],
    "activation-write-format": manifest["activation-write-format"],
    "client-ops-write-format": manifest["client-ops-write-format"],
  };
  const signedMetadata = signUpdateMetadata({ metadata, policy: signingPolicy, privateKeyPem });
  verifySignedUpdateMetadata({ signedMetadata, policy: signingPolicy });

  return Object.freeze({
    schemaVersion: 1,
    kind: descriptorKind,
    provider: releasePolicy.provider,
    channel: releasePolicy.forwardUpdateChannel,
    channelFile: channelFileName(releasePolicy, platform, arch),
    sourceFullSha,
    targetVersion,
    platform,
    arch,
    archiveInputName: path.basename(archivePath),
    assetName,
    immutableObjectKey,
    signedMetadata,
  });
}

export function verifyArtifactDescriptor({ descriptor, artifactPath, releasePolicy, signingPolicy }) {
  assert.equal(descriptor.schemaVersion, 1);
  assert.equal(descriptor.kind, descriptorKind);
  assert.deepEqual(descriptor.provider, releasePolicy.provider);
  assert.equal(descriptor.channel, releasePolicy.forwardUpdateChannel);
  assert.equal(
    descriptor.channelFile,
    channelFileName(releasePolicy, descriptor.platform, descriptor.arch),
  );
  assert.equal(path.basename(artifactPath), descriptor.assetName);
  const verified = verifySignedUpdateMetadata({
    signedMetadata: descriptor.signedMetadata,
    policy: signingPolicy,
  });
  assert.equal(verified["immutable-object-key"], descriptor.immutableObjectKey);
  assert.equal(verified["target-source-full-sha"], descriptor.sourceFullSha);
  assert.equal(verified["target-version"], descriptor.targetVersion);
  assert.equal(verified.platform, descriptor.platform);
  assert.equal(verified.arch, descriptor.arch);
  assert.equal(fs.statSync(artifactPath).size, verified["archive-size"]);
  assert.equal(digestFile(artifactPath, "sha256"), verified["archive-sha256"]);
  assert.equal(digestFile(artifactPath, "sha512"), verified["archive-sha512"]);
  return verified;
}

export function stageSignedArtifact({ descriptor, archivePath, outputDirectory }) {
  fs.mkdirSync(outputDirectory, { recursive: true });
  const artifactPath = path.join(outputDirectory, descriptor.assetName);
  assert.equal(fs.existsSync(artifactPath), false, "immutable staged artifact already exists");
  fs.copyFileSync(archivePath, artifactPath, fs.constants.COPYFILE_EXCL);
  const descriptorPath = path.join(outputDirectory, `${descriptor.assetName}.selfhost6.json`);
  fs.writeFileSync(descriptorPath, `${JSON.stringify(descriptor, null, 2)}\n`, { flag: "wx", mode: 0o600 });
  return { artifactPath, descriptorPath };
}

export function buildPromotion({
  descriptors,
  artifactDirectory,
  expectedTargetVersion,
  releasePolicy,
  signingPolicy,
}) {
  assert.ok(descriptors.length > 0, "at least one signed artifact descriptor is required");
  assert.ok(
    [releasePolicy.sourceVersion, releasePolicy.syntheticForwardTargetVersion]
      .includes(expectedTargetVersion),
    "promotion requires an explicit release-line target version",
  );
  const groups = new Map();
  for (const descriptor of descriptors) {
    assert.equal(
      descriptor.targetVersion,
      expectedTargetVersion,
      "descriptor target version differs from the explicit promotion target",
    );
    const artifactPath = path.join(artifactDirectory, descriptor.assetName);
    const verified = verifyArtifactDescriptor({ descriptor, artifactPath, releasePolicy, signingPolicy });
    const entries = groups.get(descriptor.channelFile) ?? [];
    entries.push({ descriptor, verified });
    groups.set(descriptor.channelFile, entries);
  }

  const output = new Map();
  for (const [fileName, entries] of groups) {
    const versions = new Set(entries.map(({ descriptor }) => descriptor.targetVersion));
    const sourceShas = new Set(entries.map(({ descriptor }) => descriptor.sourceFullSha));
    assert.equal(versions.size, 1, "one channel metadata file cannot mix target versions");
    assert.equal(sourceShas.size, 1, "one channel metadata file cannot mix source commits");
    assert.equal(
      new Set(entries.map(({ descriptor }) => descriptor.arch)).size,
      entries.length,
      "channel metadata cannot repeat an architecture",
    );
    const files = entries
      .sort((left, right) => left.descriptor.arch.localeCompare(right.descriptor.arch))
      .map(({ descriptor, verified }) => ({
        url: descriptor.assetName,
        sha512: Buffer.from(verified["archive-sha512"], "hex").toString("base64"),
        size: verified["archive-size"],
      }));
    const signedMetadataByArch = Object.fromEntries(
      entries.map(({ descriptor }) => [descriptor.arch, descriptor.signedMetadata]),
    );
    output.set(fileName, {
      version: entries[0].descriptor.targetVersion,
      files,
      selfhostUpdateSignatures: signedMetadataByArch,
    });
  }
  return output;
}

export function writePromotion({ metadataByFile, outputDirectory }) {
  fs.mkdirSync(outputDirectory, { recursive: true });
  for (const [fileName, metadata] of metadataByFile) {
    const target = path.join(outputDirectory, fileName);
    const temporary = `${target}.new`;
    fs.writeFileSync(temporary, `${JSON.stringify(metadata, null, 2)}\n`, { mode: 0o600 });
    fs.renameSync(temporary, target);
  }
}
