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
const updaterRequire = createRequire(path.join(
  repoRoot, "static/node_modules/.pnpm/electron-updater@6.8.3/node_modules/electron-updater/out/AppUpdater.js",
));
const { AppUpdater } = updaterRequire("./AppUpdater.js");
const { parse } = updaterRequire("semver");
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

function fixture(targetVersion = releasePolicy.syntheticForwardTargetVersion, platform = "darwin", arch = "arm64") {
  const inputDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "selfhost6-feed-"));
  const directory = path.join(inputDirectory, "staged");
  const label = platform === "linux" && arch === "x64" ? "x86_64" : arch;
  const archiveName = platform === "win32" ? `Logseq-win-${arch}-${targetVersion}-nsis.exe`
    : `Logseq-${platform}-${label}-${targetVersion}.${platform === "linux" ? "AppImage" : "zip"}`;
  const archivePath = path.join(inputDirectory, archiveName);
  const targetManifestPath = path.join(inputDirectory, "TARGET_BUILD_MANIFEST.json");
  fs.writeFileSync(archivePath, "physical candidate bytes");
  fs.writeFileSync(targetManifestPath, `${JSON.stringify({
    "schema-version": 1,
    "target-source-full-sha": sourceFullSha,
    "target-version": targetVersion,
    "release-line-id": releasePolicy.releaseLineId,
    platform,
    arch,
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
    platform,
    arch,
    releasePolicy,
    signingPolicy,
    privateKeyPem,
  });
  const staged = stageSignedArtifact({ descriptor, archivePath, outputDirectory: directory });
  return { directory, inputDirectory, descriptor, ...staged };
}

test("a content-addressed source target binds metadata to immutable bytes", () => {
  const item = fixture(releasePolicy.sourceVersion);
  const verified = verifyArtifactDescriptor({
    descriptor: item.descriptor,
    artifactPath: item.artifactPath,
    releasePolicy,
    signingPolicy,
  });
  assert.equal(item.descriptor.assetName, `Logseq-darwin-arm64-${releasePolicy.sourceVersion}.zip`);
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
    "latest.yml",
  );
  assert.equal(
    channelFileName(releasePolicy, "win32", "arm64"),
    "latest.yml",
  );
});

test("traditional GitHub metadata uses standard latest architecture channels", () => {
  assert.equal(releasePolicy.forwardUpdateChannel, "latest");
  assert.notEqual(releasePolicy.forwardUpdateChannel, releasePolicy.releaseLineId);
  assert.equal(traditionalChannelFileName(releasePolicy, "win32", "x64"),
    "latest-x64.yml");
  assert.equal(traditionalChannelFileName(releasePolicy, "win32", "arm64"),
    "latest-arm64.yml");
  assert.equal(traditionalChannelFileName(releasePolicy, "darwin", "x64"),
    "latest-x64-mac.yml");
  assert.equal(traditionalChannelFileName(releasePolicy, "darwin", "arm64"),
    "latest-arm64-mac.yml");
  assert.equal(traditionalChannelFileName(releasePolicy, "linux", "x64"),
    "latest-linux.yml");
  assert.equal(traditionalChannelFileName(releasePolicy, "linux", "arm64"),
    "latest-linux-arm64.yml");
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

test("electron-updater GitHubProvider resolves six latest channels and exact signed bytes", async (context) => {
  const previousArch = process.env.TEST_UPDATER_ARCH;
  try {
    for (const [platform, arch, channelFile] of [
      ["darwin", "arm64", "latest-arm64-mac.yml"], ["darwin", "x64", "latest-x64-mac.yml"],
      ["win32", "arm64", "latest-arm64.yml"], ["win32", "x64", "latest-x64.yml"],
      ["linux", "arm64", "latest-linux-arm64.yml"], ["linux", "x64", "latest-linux.yml"],
    ]) {
      process.env.TEST_UPDATER_ARCH = arch;
      const item = fixture(releasePolicy.syntheticForwardTargetVersion, platform, arch);
      context.after(() => fs.rmSync(item.inputDirectory, { recursive: true, force: true }));
      const verified = verifyArtifactDescriptor({ descriptor: item.descriptor,
        artifactPath: item.artifactPath, releasePolicy, signingPolicy });
      const tag = item.descriptor.targetVersion;
      const native = [
        `version: ${tag}`, "files:", `  - url: ${item.descriptor.assetName}`,
        `    sha512: ${Buffer.from(verified["archive-sha512"], "hex").toString("base64")}`,
        `    size: ${verified["archive-size"]}`, `path: ${item.descriptor.assetName}`,
        `sha512: ${Buffer.from(verified["archive-sha512"], "hex").toString("base64")}`,
        "releaseDate: '2026-08-31T00:00:00.000Z'", "",
      ].join("\n");
      const metadata = finalizeTraditionalMetadata({ metadataText: native,
        artifactDirectory: item.directory, descriptors: [item.descriptor],
        expectedTargetVersion: tag, platform, arch, releasePolicy, signingPolicy });
      assert.equal(traditionalChannelFileName(releasePolicy, platform, arch), channelFile);
      const requests = [];
      const feed = `<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>${tag}</title><link href="https://github.com/example/release-fixture/releases/tag/${tag}"/><content>fixture</content></entry></feed>`;
      const executor = {
        async request(options) {
          const requestPath = options.path;
          requests.push(requestPath);
          if (requestPath.endsWith("/releases.atom")) return feed;
          if (requestPath.endsWith("/releases/latest")) return JSON.stringify({ tag_name: tag });
          if (requestPath.endsWith(`/${channelFile}`)) return metadata;
          if (requestPath.endsWith(`/${item.descriptor.assetName}`))
            return fs.readFileSync(item.artifactPath).toString("utf8");
          throw new Error(`unexpected GitHubProvider request path: ${requestPath}`);
        },
      };
      const provider = new GitHubProvider(
        { provider: "github", owner: "example", repo: "release-fixture" },
        { channel: platform === "linux" ? "latest" : `latest-${arch}`,
          allowPrerelease: false, currentVersion: releasePolicy.sourceVersion, fullChangelog: false },
        { executor, platform, isUseMultipleRangeRequest: false },
      );
      const info = await provider.getLatestVersion();
      assert.equal(info.version, tag);
      assert.equal(info.tag, tag);
      assert.deepEqual(Object.keys(info.selfhostUpdateSignatures), [arch]);
      assert.deepEqual(info.selfhostUpdateSignatures[arch], item.descriptor.signedMetadata);
      assert.ok(requests.some(requestPath => requestPath.endsWith(`/${channelFile}`)));
      const resolved = provider.resolveFiles(info);
      assert.equal(resolved.length, 1);
      assert.equal(path.basename(resolved[0].url.pathname), item.descriptor.assetName);
      assert.deepEqual(Buffer.from(await executor.request({ path: resolved[0].url.pathname })),
        fs.readFileSync(item.artifactPath));
    }
  } finally {
    if (previousArch === undefined) delete process.env.TEST_UPDATER_ARCH;
    else process.env.TEST_UPDATER_ARCH = previousArch;
  }
});

test("native AppUpdater accepts newer versions and rejects invalid versions or downgrades", async () => {
  const updater = {
    currentVersion: parse("2.0.2-selfhost.1"),
    allowDowngrade: false,
    isUpdateSupported: () => true,
    isUserWithinRollout: () => true,
  };
  assert.equal(await AppUpdater.prototype.isUpdateAvailable.call(updater, {
    version: releasePolicy.sourceVersion,
  }), true);
  updater.currentVersion = parse(releasePolicy.sourceVersion);
  assert.equal(await AppUpdater.prototype.isUpdateAvailable.call(updater, {
    version: releasePolicy.sourceVersion,
  }), false);
  assert.equal(await AppUpdater.prototype.isUpdateAvailable.call(updater, {
    version: releasePolicy.syntheticForwardTargetVersion,
  }), true);
  for (const version of ["2.0.3-selfhost.1", "2.1.0-selfhost.1"])
    assert.equal(await AppUpdater.prototype.isUpdateAvailable.call(updater, { version }), true);
  assert.equal(await AppUpdater.prototype.isUpdateAvailable.call(updater, {
    version: "2.0.2-selfhost.1",
  }), false);
  await assert.rejects(AppUpdater.prototype.isUpdateAvailable.call(updater, {
    version: "invalid",
  }), { code: "ERR_UPDATER_INVALID_VERSION" });
});
