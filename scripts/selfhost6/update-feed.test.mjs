import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

import { readReleasePolicy } from "../lib/selfhost6-release-identity.mjs";
import {
  buildPromotion,
  channelFileName,
  finalizeTraditionalMetadata,
  prepareSignedArtifact,
  stageSignedArtifact,
  traditionalChannelFileName,
  verifyArtifactDescriptor,
  writePromotion,
} from "../lib/selfhost6-update-feed.mjs";
import {
  algorithm,
  bundleIdentity,
  payloadDomain,
  releaseLineId,
  signingKeyIdentity,
} from "../../resources/updater/project-update-signature.mjs";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const require = createRequire(import.meta.url);
const { GitHubProvider } = require(path.join(
  repoRoot,
  "static/node_modules/.pnpm/electron-updater@6.8.3/node_modules/electron-updater/out/providers/GitHubProvider.js",
));
const { parseUpdateInfo } = require(path.join(
  repoRoot,
  "static/node_modules/.pnpm/electron-updater@6.8.3/node_modules/electron-updater/out/providers/Provider.js",
));
const releasePolicy = readReleasePolicy(
  path.join(repoRoot, "resources/updater/selfhost-release-policy.json"),
);
const { privateKey, publicKey } = generateKeyPairSync("ed25519");
const publicKeyBase64 = publicKey.export({ format: "der", type: "spki" }).subarray(-32).toString("base64");
const signingPolicy = Object.freeze({
  schemaVersion: 1,
  algorithm,
  payloadDomain,
  releaseLineId,
  bundleIdentity,
  keyId: signingKeyIdentity(publicKeyBase64),
  publicKeyBase64,
});
const privateKeyPem = privateKey.export({ format: "pem", type: "pkcs8" });
const sourceFullSha = "a".repeat(40);

function fixture(targetVersion = releasePolicy.syntheticForwardTargetVersion) {
  const inputDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "selfhost6-feed-"));
  const directory = path.join(inputDirectory, "staged");
  const archivePath = path.join(inputDirectory, `Logseq-darwin-arm64-${targetVersion}.zip`);
  const targetManifestPath = path.join(inputDirectory, "TARGET_BUILD_MANIFEST.json");
  fs.writeFileSync(archivePath, "physical candidate bytes");
  fs.writeFileSync(targetManifestPath, `${JSON.stringify({
    "schema-version": 1,
    "target-source-full-sha": sourceFullSha,
    "target-version": targetVersion,
    "release-line-id": releasePolicy.releaseLineId,
    platform: "darwin",
    arch: "arm64",
    "bundle-identity": releasePolicy.bundleIdentity,
    "signing-key-identity": signingPolicy.keyId,
    "readable-activation-formats": releasePolicy.readableActivationFormats,
    "readable-client-ops-formats": releasePolicy.readableClientOpsFormats,
    "activation-write-format": releasePolicy.activationWriteFormat,
    "client-ops-write-format": releasePolicy.clientOpsWriteFormat,
  }, null, 2)}\n`);
  const descriptor = prepareSignedArtifact({
    archivePath,
    targetManifestPath,
    sourceFullSha,
    targetVersion,
    platform: "darwin",
    arch: "arm64",
    releasePolicy,
    signingPolicy,
    privateKeyPem,
  });
  const staged = stageSignedArtifact({ descriptor, archivePath, outputDirectory: directory });
  return { directory, inputDirectory, descriptor, ...staged };
}

test("a content-addressed .7 target becomes one-time bridge metadata", () => {
  const item = fixture(releasePolicy.sourceVersion);
  const verified = verifyArtifactDescriptor({
    descriptor: item.descriptor,
    artifactPath: item.artifactPath,
    releasePolicy,
    signingPolicy,
  });
  assert.match(item.descriptor.assetName, /darwin-arm64-2\.0\.1-selfhost\.7\.zip$/);
  assert.ok(item.descriptor.immutableObjectKey.includes(verified["archive-sha256"]));
  assert.deepEqual(item.descriptor.provider, {
    kind: "github",
    owner: "cfenglv",
    repo: "logseq",
    remoteMutation: "version-release-only",
  });

  const metadataByFile = buildPromotion({
    descriptors: [item.descriptor],
    artifactDirectory: item.directory,
    expectedTargetVersion: releasePolicy.sourceVersion,
    releasePolicy,
    signingPolicy,
  });
  assert.deepEqual([...metadataByFile.keys()], [channelFileName(releasePolicy, "darwin", "arm64")]);
  const metadata = metadataByFile.values().next().value;
  assert.equal(metadata.version, releasePolicy.sourceVersion);
  assert.deepEqual(Object.keys(metadata.selfhostUpdateSignatures), ["arm64"]);
  assert.equal(metadata.files[0].url, item.descriptor.assetName);
  writePromotion({ metadataByFile, outputDirectory: item.directory });
  assert.deepEqual(
    JSON.parse(fs.readFileSync(path.join(
      item.directory,
      channelFileName(releasePolicy, item.descriptor.platform, item.descriptor.arch),
    ), "utf8")),
    metadata,
  );
});

test("win32 arm64 uses the same shared Windows update channel as win32 x64", () => {
  assert.equal(
    channelFileName(releasePolicy, "win32", "x64"),
    "selfhost-official-architecture-v1.yml",
  );
  assert.equal(
    channelFileName(releasePolicy, "win32", "arm64"),
    "selfhost-official-architecture-v1.yml",
  );
});

test("traditional GitHub metadata uses isolated release-local channel names", () => {
  assert.equal(traditionalChannelFileName(releasePolicy, "win32", "x64"),
    "selfhost-official-architecture-v1-x64.yml");
  assert.equal(traditionalChannelFileName(releasePolicy, "win32", "arm64"),
    "selfhost-official-architecture-v1-arm64.yml");
  assert.equal(traditionalChannelFileName(releasePolicy, "darwin", "x64"),
    "selfhost-official-architecture-v1-x64-mac.yml");
  assert.equal(traditionalChannelFileName(releasePolicy, "darwin", "arm64"),
    "selfhost-official-architecture-v1-arm64-mac.yml");
  assert.equal(traditionalChannelFileName(releasePolicy, "linux", "x64"),
    "selfhost-official-architecture-v1-linux.yml");
  assert.equal(traditionalChannelFileName(releasePolicy, "linux", "arm64"),
    "selfhost-official-architecture-v1-linux-arm64.yml");
});

test("native electron-builder metadata keeps its fields and gains the existing signature", () => {
  const item = fixture(releasePolicy.sourceVersion);
  const verified = verifyArtifactDescriptor({
    descriptor: item.descriptor,
    artifactPath: item.artifactPath,
    releasePolicy,
    signingPolicy,
  });
  const native = [
    `version: ${releasePolicy.sourceVersion}`,
    "files:",
    `  - url: ${item.descriptor.assetName}`,
    `    sha512: ${Buffer.from(verified["archive-sha512"], "hex").toString("base64")}`,
    `    size: ${verified["archive-size"]}`,
    `path: ${item.descriptor.assetName}`,
    `sha512: ${Buffer.from(verified["archive-sha512"], "hex").toString("base64")}`,
    "releaseDate: '2026-08-31T00:00:00.000Z'",
    "",
  ].join("\n");
  const finalized = finalizeTraditionalMetadata({
    metadataText: native,
    artifactDirectory: item.directory,
    descriptors: [item.descriptor],
    expectedTargetVersion: releasePolicy.sourceVersion,
    platform: "darwin",
    arch: "arm64",
    releasePolicy,
    signingPolicy,
  });
  assert.ok(finalized.startsWith(native.trimEnd()));
  const parsed = parseUpdateInfo(
    finalized,
    traditionalChannelFileName(releasePolicy, "darwin", "arm64"),
    new URL("https://example.invalid/update.yml"),
  );
  assert.equal(parsed.version, releasePolicy.sourceVersion);
  assert.equal(parsed.files[0].url, item.descriptor.assetName);
  assert.deepEqual(parsed.selfhostUpdateSignatures.arm64, item.descriptor.signedMetadata);
  assert.throws(() => finalizeTraditionalMetadata({
    metadataText: native.replace(`size: ${verified["archive-size"]}`, "size: 1"),
    artifactDirectory: item.directory,
    descriptors: [item.descriptor],
    expectedTargetVersion: releasePolicy.sourceVersion,
    platform: "darwin",
    arch: "arm64",
    releasePolicy,
    signingPolicy,
  }), /size differs/);
});

test("bridge rejects synthetic .8 and post-signing artifact replacement", () => {
  const target = fixture();
  assert.throws(
    () => buildPromotion({
      descriptors: [target.descriptor],
      artifactDirectory: target.directory,
      expectedTargetVersion: releasePolicy.syntheticForwardTargetVersion,
      releasePolicy,
      signingPolicy,
    }),
    /only the one-time \.6 to \.7 bridge/,
  );

  fs.appendFileSync(target.artifactPath, "replacement");
  assert.throws(
    () => verifyArtifactDescriptor({
      descriptor: target.descriptor,
      artifactPath: target.artifactPath,
      releasePolicy,
      signingPolicy,
    }),
    /Expected values to be strictly equal/,
  );
});

test("electron-updater GitHubProvider resolves latest Release metadata and immutable bytes", async () => {
  const item = fixture();
  const verified = verifyArtifactDescriptor({
    descriptor: item.descriptor,
    artifactPath: item.artifactPath,
    releasePolicy,
    signingPolicy,
  });
  const native = [
    `version: ${releasePolicy.syntheticForwardTargetVersion}`,
    "files:",
    `  - url: ${item.descriptor.assetName}`,
    `    sha512: ${Buffer.from(verified["archive-sha512"], "hex").toString("base64")}`,
    `    size: ${verified["archive-size"]}`,
    `path: ${item.descriptor.assetName}`,
    `sha512: ${Buffer.from(verified["archive-sha512"], "hex").toString("base64")}`,
    "releaseDate: '2026-08-31T00:00:00.000Z'",
    "",
  ].join("\n");
  const metadata = finalizeTraditionalMetadata({
    metadataText: native,
    artifactDirectory: item.directory,
    descriptors: [item.descriptor],
    expectedTargetVersion: item.descriptor.targetVersion,
    platform: "darwin",
    arch: "arm64",
    releasePolicy,
    signingPolicy,
  });
  const tag = releasePolicy.syntheticForwardTargetVersion;
  const channelFile = traditionalChannelFileName(releasePolicy, "darwin", "arm64");
  const feed = `<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>${tag}</title><link href="https://github.com/cfenglv/logseq/releases/tag/${tag}"/><content>fixture</content></entry></feed>`;
  const executor = {
    async request(options) {
      const requestPath = options.path;
      if (requestPath.endsWith("/releases.atom")) return feed;
      if (requestPath.endsWith("/releases/latest")) return JSON.stringify({ tag_name: tag });
      if (requestPath.endsWith(`/${channelFile}`)) return metadata;
      if (requestPath.endsWith(`/${item.descriptor.assetName}`)) {
        return fs.readFileSync(item.artifactPath).toString("utf8");
      }
      throw new Error(`unexpected GitHubProvider request path: ${requestPath}`);
    },
  };
  const provider = new GitHubProvider(
    { provider: "github", owner: "cfenglv", repo: "logseq" },
    {
      channel: `${releasePolicy.forwardUpdateChannel}-arm64`,
      allowPrerelease: false,
      currentVersion: releasePolicy.sourceVersion,
      fullChangelog: false,
    },
    { executor, platform: "darwin", isUseMultipleRangeRequest: false },
  );
  const info = await provider.getLatestVersion();
  assert.equal(info.version, releasePolicy.syntheticForwardTargetVersion);
  assert.equal(info.tag, releasePolicy.syntheticForwardTargetVersion);
  assert.deepEqual(Object.keys(info.selfhostUpdateSignatures), ["arm64"]);
  const resolved = provider.resolveFiles(info);
  assert.equal(resolved.length, 1);
  assert.equal(path.basename(resolved[0].url.pathname), item.descriptor.assetName);
  const archiveBytes = Buffer.from(await executor.request({ path: resolved[0].url.pathname }));
  assert.deepEqual(archiveBytes, fs.readFileSync(item.artifactPath));
});
