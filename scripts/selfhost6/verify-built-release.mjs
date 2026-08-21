#!/usr/bin/env node

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { readReleasePolicy } from "../lib/selfhost6-release-identity.mjs";
import { verifyArtifactDescriptor } from "../lib/selfhost6-update-feed.mjs";
import { loadProjectSigningPolicy } from "../../resources/updater/project-update-signature.mjs";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const fullShaPattern = /^[0-9a-f]{40}$/;
const expectedTargets = new Set([
  "darwin/arm64",
  "darwin/x64",
  "linux/arm64",
  "linux/x64",
  "win32/arm64",
  "win32/x64",
]);

function parseArgs(argv) {
  const result = {};
  for (let index = 0; index < argv.length; index += 2) {
    const flag = argv[index];
    const value = argv[index + 1];
    assert.ok(value, `${flag} requires a value`);
    if (flag === "--artifact-directory") result.artifactDirectory = path.resolve(value);
    else if (flag === "--source-full-sha") result.sourceFullSha = value;
    else if (flag === "--target-version") result.targetVersion = value;
    else if (flag === "--output") result.output = path.resolve(value);
    else throw new Error(`unknown argument: ${flag}`);
  }
  assert.ok(result.artifactDirectory, "--artifact-directory is required");
  assert.match(result.sourceFullSha ?? "", fullShaPattern, "--source-full-sha must be a full SHA");
  assert.ok(result.targetVersion, "--target-version is required");
  assert.ok(result.output, "--output is required");
  return result;
}

function readJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, "utf8"));
}

function sha256(filePath) {
  return createHash("sha256").update(fs.readFileSync(filePath)).digest("hex");
}

const options = parseArgs(process.argv.slice(2));
const releasePolicy = readReleasePolicy(
  path.join(repoRoot, "resources/updater/selfhost-release-policy.json"),
);
const signingPolicy = loadProjectSigningPolicy();
const withdrawnArchiveShas = new Set(releasePolicy.withdrawnArchiveSha256Denylist);
assert.equal(options.targetVersion, releasePolicy.sourceVersion);
const topLevel = fs.readdirSync(options.artifactDirectory, { withFileTypes: true });
const topLevelFiles = topLevel.filter((entry) => entry.isFile()).map(({ name }) => name).sort();
const topLevelDirectories = topLevel
  .filter((entry) => entry.isDirectory())
  .map(({ name }) => name)
  .sort();
assert.deepEqual(topLevelDirectories, ["qualification-receipts"]);
assert.equal(topLevelFiles.length, 16,
  "formal release set must contain eight platform archives and eight descriptors");
assert.equal(topLevelFiles.some((name) => name.endsWith(".yml")), false);

const descriptorNames = topLevelFiles.filter((name) => name.endsWith(".selfhost6.json"));
assert.equal(descriptorNames.length, 8);
const targets = new Set();
const names = new Set();
const archiveShas = new Set();
const immutableObjectKeys = new Set();
const assets = [];

for (const descriptorName of descriptorNames) {
  const descriptorPath = path.join(options.artifactDirectory, descriptorName);
  const descriptor = readJson(descriptorPath);
  const artifactPath = path.join(options.artifactDirectory, descriptor.assetName);
  assert.ok(fs.statSync(artifactPath).isFile(), `missing release archive ${descriptor.assetName}`);
  assert.equal(descriptorName, `${descriptor.assetName}.selfhost6.json`);
  assert.equal(descriptor.sourceFullSha, options.sourceFullSha);
  assert.equal(descriptor.targetVersion, options.targetVersion);
  const target = `${descriptor.platform}/${descriptor.arch}`;
  assert.ok(expectedTargets.has(target), `unexpected release target ${target}`);
  targets.add(target);

  const verified = verifyArtifactDescriptor({
    descriptor,
    artifactPath,
    releasePolicy,
    signingPolicy,
  });
  assert.equal(names.has(descriptor.assetName), false, "duplicate release asset name");
  assert.equal(archiveShas.has(verified["archive-sha256"]), false, "duplicate release archive SHA");
  assert.equal(
    immutableObjectKeys.has(descriptor.immutableObjectKey),
    false,
    "duplicate immutable object key",
  );
  names.add(descriptor.assetName);
  archiveShas.add(verified["archive-sha256"]);
  immutableObjectKeys.add(descriptor.immutableObjectKey);
  assert.equal(
    withdrawnArchiveShas.has(verified["archive-sha256"]),
    false,
    "release archive must not reuse withdrawn .6 bytes",
  );
  const qualificationName = `${descriptor.platform}-${descriptor.arch}.json`;
  const qualificationPath = path.join(
    options.artifactDirectory,
    "qualification-receipts",
    qualificationName,
  );
  const qualification = readJson(qualificationPath);
  assert.equal(qualification.kind, "selfhost6.phase0.platform-sqlite-swap.v1");
  assert.equal(qualification.result, "pass");
  assert.equal(qualification.sourceFullSha, options.sourceFullSha);
  assert.equal(qualification.platform, descriptor.platform);
  assert.equal(qualification.arch, descriptor.arch);
  assert.equal(qualification.authorityCommitRenameCount, 1);
  assert.ok(Object.values(qualification.oracles).every((value) => value === true));

  assets.push({
    name: descriptor.assetName,
    descriptorName,
    platform: descriptor.platform,
    arch: descriptor.arch,
    size: verified["archive-size"],
    archiveSha256: verified["archive-sha256"],
    archiveSha512: verified["archive-sha512"],
    descriptorSha256: sha256(descriptorPath),
    targetBuildManifestSha256: verified["target-build-manifest-sha256"],
    immutableObjectKey: descriptor.immutableObjectKey,
    channelFile: descriptor.channelFile,
    qualificationReceipt: {
      name: qualificationName,
      sha256: sha256(qualificationPath),
    },
  });
}

assert.deepEqual(targets, expectedTargets);
assert.deepEqual(
  new Set(topLevelFiles),
  new Set(assets.flatMap(({ name, descriptorName }) => [name, descriptorName])),
);
const qualificationFiles = fs
  .readdirSync(path.join(options.artifactDirectory, "qualification-receipts"))
  .sort();
assert.deepEqual(
  new Set(qualificationFiles),
  new Set(assets.map(({ qualificationReceipt }) => qualificationReceipt.name)),
);

const receipt = {
  schemaVersion: 1,
  kind: "selfhost6.formal-release-build-verification.v1",
  status: "built-assets-verified-awaiting-product-qualification",
  workflowRunId: process.env.GITHUB_RUN_ID ?? null,
  workflowRevisionFullSha: process.env.GITHUB_SHA ?? null,
  sourceFullSha: options.sourceFullSha,
  productVersion: options.targetVersion,
  releaseLineId: releasePolicy.releaseLineId,
  protectedSigningKeyId: signingPolicy.keyId,
  platformTargets: [...targets].sort(),
  assets: assets.sort((left, right) => left.name.localeCompare(right.name)),
  channelMetadataPublished: false,
  githubReleaseCreated: false,
  productionWorkerTrafficChanged: false,
};
fs.mkdirSync(path.dirname(options.output), { recursive: true });
fs.writeFileSync(options.output, `${JSON.stringify(receipt, null, 2)}\n`, { flag: "wx" });
process.stdout.write(`${JSON.stringify({ status: receipt.status, assets: assets.length })}\n`);
