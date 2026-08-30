#!/usr/bin/env node

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { readReleasePolicy } from "../lib/selfhost6-release-identity.mjs";
import { traditionalReleaseAssetNames } from "../lib/selfhost6-update-feed.mjs";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const fullShaPattern = /^[0-9a-f]{40}$/;
const sha256Pattern = /^sha256:[0-9a-f]{64}$/;
const releasePolicy = readReleasePolicy(
  path.join(repoRoot, "resources/updater/selfhost-release-policy.json"),
);

function sha256Bytes(bytes) {
  return `sha256:${createHash("sha256").update(bytes).digest("hex")}`;
}

function parseArgs(argv) {
  const result = {};
  for (let index = 0; index < argv.length; index += 2) {
    const flag = argv[index];
    const value = argv[index + 1];
    assert.ok(value, `${flag} requires a value`);
    if (flag === "--phase") result.phase = value;
    else if (flag === "--release-json") result.releaseJson = path.resolve(value);
    else if (flag === "--assets-json") result.assetsJson = path.resolve(value);
    else if (flag === "--tag-refs-json") result.tagRefsJson = path.resolve(value);
    else if (flag === "--release-list-json") result.releaseListJson = path.resolve(value);
    else if (flag === "--latest-release-json") result.latestReleaseJson = path.resolve(value);
    else if (flag === "--delete-list-output") result.deleteListOutput = path.resolve(value);
    else if (flag === "--artifact-directory") result.artifactDirectory = path.resolve(value);
    else if (flag === "--release-id") result.releaseId = value;
    else if (flag === "--source-full-sha") result.sourceFullSha = value;
    else if (flag === "--replacement-source-full-sha") result.replacementSourceFullSha = value;
    else if (flag === "--target-version") result.targetVersion = value;
    else if (flag === "--expected-inventory-sha256") result.expectedInventorySha256 = value;
    else throw new Error(`unknown argument: ${flag}`);
  }
  assert.ok(["before", "after", "published"].includes(result.phase),
    "--phase must be before, after, or published");
  for (const field of [
    "releaseJson",
    "assetsJson",
    "tagRefsJson",
    "releaseListJson",
    "releaseId",
    "sourceFullSha",
    "targetVersion",
  ]) {
    assert.ok(result[field], `${field} is required`);
  }
  if (result.phase === "before") {
    assert.match(result.expectedInventorySha256 ?? "", sha256Pattern,
      "expected Draft inventory SHA-256 is required");
    assert.ok(result.artifactDirectory, "artifactDirectory is required before replacement");
    assert.match(result.replacementSourceFullSha ?? "", fullShaPattern,
      "replacement Draft target source must be a full SHA");
    assert.ok(process.env.SELFHOST_DRAFT_ASSET_INVENTORY_JSON,
      "SELFHOST_DRAFT_ASSET_INVENTORY_JSON is required");
    assert.ok(result.deleteListOutput, "deleteListOutput is required before replacement");
  } else {
    assert.ok(result.artifactDirectory, "artifactDirectory is required after staging");
  }
  if (result.phase === "published") {
    assert.ok(result.latestReleaseJson, "latestReleaseJson is required after publication");
  }
  return result;
}

function normalizedRemoteAssets(assets) {
  assert.ok(Array.isArray(assets), "Draft assets response must be an array");
  const normalized = assets.map((asset) => {
    assert.ok(Number.isSafeInteger(asset.id) && asset.id > 0, "Draft asset id is invalid");
    assert.ok(typeof asset.name === "string" && asset.name.length > 0,
      "Draft asset name is invalid");
    assert.ok(Number.isSafeInteger(asset.size) && asset.size > 0,
      `Draft asset size is invalid for ${asset.name}`);
    assert.match(asset.digest ?? "", sha256Pattern,
      `Draft asset digest is invalid for ${asset.name}`);
    return { id: asset.id, name: asset.name, size: asset.size, digest: asset.digest };
  }).sort((left, right) => left.name.localeCompare(right.name));
  assert.equal(new Set(normalized.map(({ id }) => id)).size, normalized.length,
    "Draft asset ids must be unique");
  assert.equal(new Set(normalized.map(({ name }) => name)).size, normalized.length,
    "Draft asset names must be unique");
  return normalized;
}

function normalizedExpectedAssets(assets, targetVersion) {
  const normalized = normalizedRemoteAssets(assets);
  if (normalized.length === 16) {
    assert.ok(normalized.every(({ name }) => name.startsWith("Logseq-")),
      "legacy Draft contains a non-formal asset");
    assert.equal(normalized.filter(({ name }) => name.endsWith(".selfhost6.json")).length, 8,
      "legacy Draft must contain eight artifact descriptors");
  } else {
    assert.deepEqual(
      new Set(normalized.map(({ name }) => name)),
      new Set(traditionalReleaseAssetNames(releasePolicy, targetVersion)),
      "frozen Draft differs from the traditional release inventory",
    );
  }
  return normalized;
}

function normalizedTraditionalRemoteAssets(assets, targetVersion) {
  const normalized = normalizedRemoteAssets(assets);
  assert.deepEqual(
    new Set(normalized.map(({ name }) => name)),
    new Set(traditionalReleaseAssetNames(releasePolicy, targetVersion)),
    "Draft differs from the traditional release inventory",
  );
  return normalized;
}

function normalizedLocalAssets(directory, targetVersion) {
  const names = fs.readdirSync(directory, { withFileTypes: true })
    .filter((entry) => entry.isFile())
    .map(({ name }) => name)
    .sort((left, right) => left.localeCompare(right));
  assert.deepEqual(
    new Set(names),
    new Set(traditionalReleaseAssetNames(releasePolicy, targetVersion)),
    "local Draft input differs from the traditional release inventory",
  );
  return names.map((name) => {
    const bytes = fs.readFileSync(path.join(directory, name));
    assert.ok(bytes.length > 0, `Draft asset is empty: ${name}`);
    return { name, size: bytes.length, digest: sha256Bytes(bytes) };
  });
}

function verifyReleaseEnvelope({ release, releases, tagRefs, releaseId, targetVersion }) {
  const expectedReleaseId = Number(releaseId);
  assert.match(releaseId, /^[1-9][0-9]*$/, "Draft release id must be a positive decimal integer");
  assert.ok(Number.isSafeInteger(expectedReleaseId), "Draft release id is invalid");
  assert.equal(release.id, expectedReleaseId, "Draft release id changed");
  assert.equal(release.tag_name, targetVersion, "Draft tag name changed");
  assert.ok(Array.isArray(releases), "release list response must be an array");
  assert.ok(releases.length < 100, "release list exceeded the bounded uniqueness gate");
  const matchingReleases = releases.filter(({ tag_name: tagName }) => tagName === targetVersion);
  assert.equal(matchingReleases.length, 1, "version Draft must be unique");
  assert.equal(matchingReleases[0].id, expectedReleaseId, "unique version Draft id changed");
  assert.equal(release.draft, true, "version Release must remain a Draft");
  assert.equal(release.prerelease, false, "version Draft must not be a prerelease");
  assert.equal(release.published_at, null, "version Draft must remain unpublished");
  assert.ok(Array.isArray(tagRefs), "matching tag refs response must be an array");
  assert.equal(tagRefs.some(({ ref }) => ref === `refs/tags/${targetVersion}`), false,
    "version Draft unexpectedly has a tag ref");
}

function verifyReleaseIdentity(options) {
  verifyReleaseEnvelope(options);
  assert.match(options.sourceFullSha, fullShaPattern, "Draft target source must be a full SHA");
  assert.equal(options.release.target_commitish, options.sourceFullSha,
    "Draft target source changed");
  assert.equal(options.release.name, `Logseq ${options.targetVersion}`, "Draft name changed");
  assert.equal(options.release.body, `Source commit ${options.sourceFullSha}.`, "Draft body changed");
}

export function draftInventorySha256(assets) {
  const normalized = normalizedExpectedAssets(assets, releasePolicy.sourceVersion);
  return sha256Bytes(Buffer.from(`${JSON.stringify(normalized)}\n`));
}

export function verifyDraftBeforeReplacement(options) {
  verifyReleaseEnvelope(options);
  assert.match(options.sourceFullSha, fullShaPattern, "frozen Draft target source must be a full SHA");
  assert.match(options.replacementSourceFullSha, fullShaPattern,
    "replacement Draft target source must be a full SHA");
  assert.ok([options.sourceFullSha, options.replacementSourceFullSha]
    .includes(options.release.target_commitish), "Draft target source is neither frozen nor replacement source");
  assert.equal(draftInventorySha256(options.expectedAssets), options.expectedInventorySha256,
    "frozen Draft asset inventory secret is inconsistent");

  const frozen = normalizedExpectedAssets(options.expectedAssets, options.targetVersion);
  const replacement = normalizedLocalAssets(options.artifactDirectory, options.targetVersion);
  const frozenByName = new Map(frozen.map((asset) => [asset.name, asset]));
  const replacementByName = new Map(replacement.map((asset) => [asset.name, asset]));
  for (const { id, name, size, digest } of normalizedRemoteAssets(options.assets)) {
    const frozenAsset = frozenByName.get(name);
    const replacementAsset = replacementByName.get(name);
    const isFrozen = frozenAsset?.id === id && frozenAsset.size === size && frozenAsset.digest === digest;
    const isReplacement = replacementAsset?.size === size && replacementAsset.digest === digest;
    assert.ok(isFrozen || isReplacement,
      `Draft contains bytes outside the frozen/replacement boundary: ${name}`);
  }
  return { status: "draft-prewrite-verified" };
}

export function obsoleteDraftAssetIds({ assets, artifactDirectory, targetVersion }) {
  const replacementNames = new Set(
    normalizedLocalAssets(artifactDirectory, targetVersion).map(({ name }) => name),
  );
  return normalizedRemoteAssets(assets)
    .filter(({ name }) => !replacementNames.has(name))
    .map(({ id }) => id)
    .sort((left, right) => left - right);
}

export function verifyDraftAfterStaging(options) {
  verifyReleaseIdentity(options);
  const remote = normalizedTraditionalRemoteAssets(options.assets, options.targetVersion)
    .map(({ name, size, digest }) => ({ name, size, digest }));
  const local = normalizedLocalAssets(options.artifactDirectory, options.targetVersion);
  assert.deepEqual(remote, local, "staged Draft assets differ from the verified local release set");
  return { status: "draft-staging-verified" };
}

export function verifyPublishedRelease(options) {
  const expectedReleaseId = Number(options.releaseId);
  assert.match(options.releaseId, /^[1-9][0-9]*$/,
    "published release id must be a positive decimal integer");
  assert.ok(Number.isSafeInteger(expectedReleaseId), "published release id is invalid");
  assert.match(options.sourceFullSha, fullShaPattern,
    "published release target source must be a full SHA");
  assert.equal(options.release.id, expectedReleaseId, "published release id changed");
  assert.equal(options.release.tag_name, options.targetVersion, "published tag name changed");
  assert.equal(options.release.target_commitish, options.sourceFullSha,
    "published target source changed");
  assert.equal(options.release.name, `Logseq ${options.targetVersion}`, "published name changed");
  assert.equal(options.release.body, `Source commit ${options.sourceFullSha}.`,
    "published body changed");
  assert.equal(options.release.draft, false, "version Release is still a Draft");
  assert.equal(options.release.prerelease, false, "version Release is a prerelease");
  assert.match(options.release.published_at ?? "", /^\d{4}-\d{2}-\d{2}T/,
    "version Release has no publication time");
  assert.equal(options.latestRelease.id, expectedReleaseId,
    "version Release is not the GitHub latest stable Release");
  assert.ok(Array.isArray(options.releases), "release list response must be an array");
  assert.ok(options.releases.length < 100, "release list exceeded the bounded uniqueness gate");
  const matchingReleases = options.releases
    .filter(({ tag_name: tagName }) => tagName === options.targetVersion);
  assert.equal(matchingReleases.length, 1, "published version Release must be unique");
  assert.equal(matchingReleases[0].id, expectedReleaseId, "unique published Release id changed");
  assert.ok(Array.isArray(options.tagRefs), "matching tag refs response must be an array");
  const exactRefs = options.tagRefs.filter(({ ref }) => ref === `refs/tags/${options.targetVersion}`);
  assert.equal(exactRefs.length, 1, "published version must have one exact tag ref");
  assert.equal(exactRefs[0].object?.sha, options.sourceFullSha,
    "published version tag does not target the qualified source");
  const remote = normalizedTraditionalRemoteAssets(options.assets, options.targetVersion)
    .map(({ name, size, digest }) => ({ name, size, digest }));
  const local = normalizedLocalAssets(options.artifactDirectory, options.targetVersion);
  assert.deepEqual(remote, local, "published assets differ from the verified release set");
  return { status: "published-release-verified" };
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  const release = JSON.parse(fs.readFileSync(options.releaseJson, "utf8"));
  const assets = JSON.parse(fs.readFileSync(options.assetsJson, "utf8"));
  const tagRefs = JSON.parse(fs.readFileSync(options.tagRefsJson, "utf8"));
  const releases = JSON.parse(fs.readFileSync(options.releaseListJson, "utf8"));
  const latestRelease = options.latestReleaseJson
    ? JSON.parse(fs.readFileSync(options.latestReleaseJson, "utf8"))
    : undefined;
  const common = {
    release,
    assets,
    releases,
    tagRefs,
    releaseId: options.releaseId,
    sourceFullSha: options.sourceFullSha,
    targetVersion: options.targetVersion,
  };
  let receipt;
  if (options.phase === "before") {
    receipt = verifyDraftBeforeReplacement({
      ...common,
      replacementSourceFullSha: options.replacementSourceFullSha,
      artifactDirectory: options.artifactDirectory,
      expectedAssets: JSON.parse(process.env.SELFHOST_DRAFT_ASSET_INVENTORY_JSON),
      expectedInventorySha256: options.expectedInventorySha256,
    });
    fs.writeFileSync(
      options.deleteListOutput,
      `${obsoleteDraftAssetIds({
        assets,
        artifactDirectory: options.artifactDirectory,
        targetVersion: options.targetVersion,
      }).join("\n")}\n`,
      { flag: "wx", mode: 0o600 },
    );
  } else if (options.phase === "after") {
    receipt = verifyDraftAfterStaging({ ...common, artifactDirectory: options.artifactDirectory });
  } else {
    receipt = verifyPublishedRelease({
      ...common,
      latestRelease,
      artifactDirectory: options.artifactDirectory,
    });
  }
  process.stdout.write(`${JSON.stringify(receipt)}\n`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    await main();
  } catch {
    process.stderr.write("Draft release verification failed\n");
    process.exitCode = 1;
  }
}
