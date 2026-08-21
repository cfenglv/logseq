import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import http from "node:http";
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
  prepareSignedArtifact,
  stageSignedArtifact,
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
const { GenericProvider } = require(path.join(
  repoRoot,
  "static/node_modules/.pnpm/electron-updater@6.8.3/node_modules/electron-updater/out/providers/GenericProvider.js",
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
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "selfhost6-feed-"));
  const archivePath = path.join(directory, "candidate.zip");
  const targetManifestPath = path.join(directory, "TARGET_BUILD_MANIFEST.json");
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
  return { directory, descriptor, ...staged };
}

test("a content-addressed synthetic target becomes isolated channel metadata", () => {
  const item = fixture();
  const verified = verifyArtifactDescriptor({
    descriptor: item.descriptor,
    artifactPath: item.artifactPath,
    releasePolicy,
    signingPolicy,
  });
  assert.match(item.descriptor.assetName, /darwin-arm64-2\.0\.1-selfhost\.7\.zip$/);
  assert.ok(item.descriptor.immutableObjectKey.includes(verified["archive-sha256"]));
  assert.equal(item.descriptor.provider.baseUrl, releasePolicy.provider.baseUrl);

  const metadataByFile = buildPromotion({
    descriptors: [item.descriptor],
    artifactDirectory: item.directory,
    expectedTargetVersion: releasePolicy.syntheticForwardTargetVersion,
    releasePolicy,
    signingPolicy,
  });
  assert.deepEqual([...metadataByFile.keys()], [channelFileName(releasePolicy, "darwin", "arm64")]);
  const metadata = metadataByFile.values().next().value;
  assert.equal(metadata.version, releasePolicy.syntheticForwardTargetVersion);
  assert.deepEqual(Object.keys(metadata.selfhostUpdateSignatures), ["arm64"]);
  assert.equal(metadata.files[0].url, item.descriptor.assetName);
  writePromotion({ metadataByFile, outputDirectory: item.directory });
  assert.deepEqual(
    JSON.parse(fs.readFileSync(path.join(item.directory, item.descriptor.channelFile), "utf8")),
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

test("promotion rejects source .6 and post-signing artifact replacement", () => {
  const source = fixture(releasePolicy.sourceVersion);
  assert.throws(
    () => buildPromotion({
      descriptors: [source.descriptor],
      artifactDirectory: source.directory,
      expectedTargetVersion: releasePolicy.syntheticForwardTargetVersion,
      releasePolicy,
      signingPolicy,
    }),
    /differs from the explicit promotion target/,
  );

  const target = fixture();
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

test("electron-updater GenericProvider resolves the isolated channel and immutable bytes", async () => {
  const item = fixture();
  const metadataByFile = buildPromotion({
    descriptors: [item.descriptor],
    artifactDirectory: item.directory,
    expectedTargetVersion: releasePolicy.syntheticForwardTargetVersion,
    releasePolicy,
    signingPolicy,
  });
  writePromotion({ metadataByFile, outputDirectory: item.directory });
  const server = http.createServer((request, response) => {
    const requested = path.basename(new URL(request.url, "http://localhost").pathname);
    const filePath = path.join(item.directory, requested);
    if (!fs.existsSync(filePath)) {
      response.writeHead(404).end();
      return;
    }
    response.writeHead(200, { "content-type": "application/octet-stream" });
    fs.createReadStream(filePath).pipe(response);
  });
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  try {
    const { port } = server.address();
    const executor = {
      request(options) {
        return new Promise((resolve, reject) => {
          http.get(options, (response) => {
            const chunks = [];
            response.on("data", (chunk) => chunks.push(chunk));
            response.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
          }).on("error", reject);
        });
      },
    };
    const provider = new GenericProvider(
      { provider: "generic", url: `http://127.0.0.1:${port}` },
      { channel: releasePolicy.forwardUpdateChannel, isAddNoCacheQuery: false },
      { executor, platform: "darwin", isUseMultipleRangeRequest: false },
    );
    const info = await provider.getLatestVersion();
    assert.equal(info.version, releasePolicy.syntheticForwardTargetVersion);
    assert.deepEqual(Object.keys(info.selfhostUpdateSignatures), ["arm64"]);
    const resolved = provider.resolveFiles(info);
    assert.equal(resolved.length, 1);
    assert.equal(path.basename(resolved[0].url.pathname), item.descriptor.assetName);
    const archiveBytes = await new Promise((resolve, reject) => {
      http.get(resolved[0].url, (response) => {
        const chunks = [];
        response.on("data", (chunk) => chunks.push(chunk));
        response.on("end", () => resolve(Buffer.concat(chunks)));
      }).on("error", reject);
    });
    assert.deepEqual(archiveBytes, fs.readFileSync(item.artifactPath));
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});
