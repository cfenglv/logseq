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

function sha512Base64(filePath) {
  return createHash("sha512").update(fs.readFileSync(filePath)).digest("base64");
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

export function traditionalChannelFileName(releasePolicy, platform, arch) {
  assert.ok(["x64", "arm64"].includes(arch), "unsupported release metadata architecture");
  if (platform === "win32") return `${releasePolicy.forwardUpdateChannel}-${arch}.yml`;
  if (platform === "darwin") return `${releasePolicy.forwardUpdateChannel}-${arch}-mac.yml`;
  if (platform === "linux") {
    return `${releasePolicy.forwardUpdateChannel}-linux${arch === "x64" ? "" : `-${arch}`}.yml`;
  }
  throw new Error("unsupported release metadata platform");
}

export function traditionalReleaseAssetNames(releasePolicy, version) {
  const packages = [];
  for (const arch of ["arm64", "x64"]) {
    const mac = `Logseq-darwin-${arch}-${version}`;
    packages.push(`${mac}.dmg`, `${mac}.dmg.blockmap`, `${mac}.zip`, `${mac}.zip.blockmap`);
    const win = `Logseq-win-${arch}-${version}`;
    packages.push(`${win}-nsis.exe`, `${win}-nsis.exe.blockmap`, `${win}.zip`);
  }
  for (const label of ["arm64", "x86_64"]) {
    const linux = `Logseq-linux-${label}-${version}`;
    packages.push(`${linux}.AppImage`, `${linux}.zip`);
  }
  const descriptorArchives = packages.filter((name) =>
    name.endsWith(".dmg") || (name.endsWith(".zip") && name.includes("darwin-")) ||
    name.endsWith("-nsis.exe") || name.endsWith(".AppImage"));
  const metadata = [
    ["darwin", "arm64"],
    ["darwin", "x64"],
    ["win32", "arm64"],
    ["win32", "x64"],
    ["linux", "arm64"],
    ["linux", "x64"],
  ].map(([platform, arch]) => traditionalChannelFileName(releasePolicy, platform, arch));
  const names = [
    ...packages,
    ...descriptorArchives.map((name) => `${name}.selfhost6.json`),
    ...metadata,
    "VERSION",
    "SOURCE_REVISION",
    "SHA256SUMS.txt",
  ];
  assert.equal(names.length, 35, "traditional release inventory must contain thirty-five assets");
  assert.equal(new Set(names).size, names.length, "traditional release inventory contains duplicates");
  return names.sort();
}

function unquoteYamlScalar(value) {
  const trimmed = value.trim();
  if ((trimmed.startsWith("'") && trimmed.endsWith("'")) ||
      (trimmed.startsWith('"') && trimmed.endsWith('"'))) {
    return trimmed.slice(1, -1);
  }
  return trimmed;
}

function parseNativeMetadata(metadataText) {
  assert.doesNotMatch(metadataText, /^selfhostUpdateSignatures:/m,
    "native metadata already contains project signatures");
  const version = unquoteYamlScalar(metadataText.match(/^version:\s*(.+)$/m)?.[1] ?? "");
  const primary = unquoteYamlScalar(metadataText.match(/^path:\s*(.+)$/m)?.[1] ?? "");
  assert.ok(version, "native metadata version is missing");
  assert.ok(primary, "native metadata primary path is missing");
  const files = [...metadataText.matchAll(
    /^\s*-\s+url:\s*(.+)\n\s+sha512:\s*(.+)\n\s+size:\s*(\d+)$/gm,
  )].map((match) => ({
    name: decodeURIComponent(unquoteYamlScalar(match[1])),
    sha512: unquoteYamlScalar(match[2]),
    size: Number(match[3]),
  }));
  assert.ok(files.length > 0, "native metadata contains no update files");
  assert.ok(files.some(({ name }) => name === primary),
    "native metadata primary path is not present in files");
  return { version, primary, files };
}

export function finalizeTraditionalMetadata({
  metadataText,
  artifactDirectory,
  descriptors,
  expectedTargetVersion,
  platform,
  arch,
  releasePolicy,
  signingPolicy,
}) {
  const native = parseNativeMetadata(metadataText);
  assert.equal(native.version, expectedTargetVersion,
    "native metadata version differs from the release target");
  const descriptorsByAsset = new Map(descriptors.map((descriptor) => [descriptor.assetName, descriptor]));
  assert.equal(descriptorsByAsset.size, descriptors.length, "duplicate metadata descriptor asset");
  for (const file of native.files) {
    const artifactPath = path.join(artifactDirectory, file.name);
    assert.ok(fs.statSync(artifactPath).isFile(), `native metadata references missing ${file.name}`);
    assert.equal(fs.statSync(artifactPath).size, file.size,
      `native metadata size differs for ${file.name}`);
    assert.equal(sha512Base64(artifactPath), file.sha512,
      `native metadata SHA-512 differs for ${file.name}`);
    const descriptor = descriptorsByAsset.get(file.name);
    assert.ok(descriptor, `native metadata update file has no signed descriptor: ${file.name}`);
    assert.equal(descriptor.targetVersion, expectedTargetVersion);
    assert.equal(descriptor.platform, platform);
    assert.equal(descriptor.arch, arch);
    verifyArtifactDescriptor({ descriptor, artifactPath, releasePolicy, signingPolicy });
  }
  const primaryDescriptor = descriptorsByAsset.get(native.primary);
  assert.ok(primaryDescriptor, "native metadata primary update has no signed descriptor");
  const signatures = { [arch]: primaryDescriptor.signedMetadata };
  return `${metadataText.trimEnd()}\nselfhostUpdateSignatures: ${JSON.stringify(signatures)}\n`;
}

export function verifyTraditionalMetadata(options) {
  const signatureLine = /\nselfhostUpdateSignatures:\s*(\{.+\})\n?$/;
  assert.match(options.metadataText, signatureLine,
    "traditional metadata is missing the final project-signature field");
  const unsignedMetadata = options.metadataText.replace(signatureLine, "\n");
  const expected = finalizeTraditionalMetadata({
    ...options,
    metadataText: unsignedMetadata,
  });
  assert.equal(options.metadataText, expected,
    "traditional metadata signature differs from the signed primary update");
  return parseNativeMetadata(unsignedMetadata);
}

export function writeTraditionalMetadata({ metadata, outputPath }) {
  fs.mkdirSync(path.dirname(outputPath), { recursive: true });
  fs.writeFileSync(outputPath, metadata, { flag: "wx", mode: 0o600 });
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
  const assetName = path.basename(archivePath);
  assert.ok(assetName.startsWith("Logseq-"), "update archive must use its final Logseq asset name");
  assert.ok(assetName.includes(`-${targetVersion}.`) || assetName.includes(`-${targetVersion}-`),
    "update archive name must bind the target version");
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
    channelFile: releasePolicy.provider.kind === "github"
      ? traditionalChannelFileName(releasePolicy, platform, arch)
      : channelFileName(releasePolicy, platform, arch),
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
    releasePolicy.provider.kind === "github"
      ? traditionalChannelFileName(releasePolicy, descriptor.platform, descriptor.arch)
      : channelFileName(releasePolicy, descriptor.platform, descriptor.arch),
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
  assert.equal(expectedTargetVersion, releasePolicy.sourceVersion,
    "the fixed release line is only the one-time .6 to .7 bridge");
  const groups = new Map();
  for (const descriptor of descriptors) {
    assert.equal(
      descriptor.targetVersion,
      expectedTargetVersion,
      "descriptor target version differs from the explicit promotion target",
    );
    const artifactPath = path.join(artifactDirectory, descriptor.assetName);
    const verified = verifyArtifactDescriptor({ descriptor, artifactPath, releasePolicy, signingPolicy });
    const bridgeChannelFile = channelFileName(
      releasePolicy,
      descriptor.platform,
      descriptor.arch,
    );
    const entries = groups.get(bridgeChannelFile) ?? [];
    entries.push({ descriptor, verified });
    groups.set(bridgeChannelFile, entries);
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
