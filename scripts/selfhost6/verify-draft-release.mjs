#!/usr/bin/env node

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const fullShaPattern = /^[0-9a-f]{40}$/;
const sha256Pattern = /^sha256:[0-9a-f]{64}$/;

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
    else if (flag === "--artifact-directory") result.artifactDirectory = path.resolve(value);
    else if (flag === "--release-id") result.releaseId = value;
    else if (flag === "--source-full-sha") result.sourceFullSha = value;
    else if (flag === "--replacement-source-full-sha") result.replacementSourceFullSha = value;
    else if (flag === "--target-version") result.targetVersion = value;
    else if (flag === "--expected-inventory-sha256") result.expectedInventorySha256 = value;
    else throw new Error(`unknown argument: ${flag}`);
  }
  assert.ok(["before", "after"].includes(result.phase), "--phase must be before or after");
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
  } else {
    assert.ok(result.artifactDirectory, "artifactDirectory is required after staging");
  }
  return result;
}

function normalizedRemoteAssets(assets, { exact = true } = {}) {
  assert.ok(Array.isArray(assets), "Draft assets response must be an array");
  if (exact) assert.equal(assets.length, 16, "Draft must contain exactly sixteen formal assets");
  else assert.ok(assets.length <= 16, "recoverable Draft cannot contain extra assets");
  const normalized = assets.map((asset) => {
    assert.ok(Number.isSafeInteger(asset.id) && asset.id > 0, "Draft asset id is invalid");
    assert.match(asset.name ?? "", /^Logseq-/, "Draft contains a non-formal asset");
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
  if (exact) {
    assert.equal(normalized.filter(({ name }) => name.endsWith(".selfhost6.json")).length, 8,
      "Draft must contain eight artifact descriptors");
  }
  return normalized;
}

function normalizedLocalAssets(directory, targetVersion) {
  const names = fs.readdirSync(directory, { withFileTypes: true })
    .filter((entry) => entry.isFile() && entry.name.startsWith("Logseq-"))
    .map(({ name }) => name)
    .sort();
  assert.equal(names.length, 16, "local Draft input must contain sixteen formal assets");
  assert.equal(names.filter((name) => name.endsWith(".selfhost6.json")).length, 8,
    "local Draft input must contain eight artifact descriptors");
  return names.map((name) => {
    assert.ok(name.includes(`-${targetVersion}.`), `Draft asset name does not bind ${targetVersion}`);
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
  const normalized = normalizedRemoteAssets(assets);
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

  const frozen = normalizedRemoteAssets(options.expectedAssets);
  const replacement = normalizedLocalAssets(options.artifactDirectory, options.targetVersion);
  assert.deepEqual(new Set(frozen.map(({ name }) => name)), new Set(replacement.map(({ name }) => name)),
    "frozen and replacement Draft asset names differ");
  const frozenByName = new Map(frozen.map((asset) => [asset.name, asset]));
  const replacementByName = new Map(replacement.map((asset) => [asset.name, asset]));
  for (const { id, name, size, digest } of normalizedRemoteAssets(options.assets, { exact: false })) {
    const frozenAsset = frozenByName.get(name);
    const replacementAsset = replacementByName.get(name);
    const isFrozen = frozenAsset?.id === id && frozenAsset.size === size && frozenAsset.digest === digest;
    const isReplacement = replacementAsset?.size === size && replacementAsset.digest === digest;
    assert.ok(isFrozen || isReplacement,
      `Draft contains bytes outside the frozen/replacement boundary: ${name}`);
  }
  return { status: "draft-prewrite-verified" };
}

export function verifyDraftAfterStaging(options) {
  verifyReleaseIdentity(options);
  const remote = normalizedRemoteAssets(options.assets)
    .map(({ name, size, digest }) => ({ name, size, digest }));
  const local = normalizedLocalAssets(options.artifactDirectory, options.targetVersion);
  assert.deepEqual(remote, local, "staged Draft assets differ from the verified local release set");
  return { status: "draft-staging-verified" };
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  const release = JSON.parse(fs.readFileSync(options.releaseJson, "utf8"));
  const assets = JSON.parse(fs.readFileSync(options.assetsJson, "utf8"));
  const tagRefs = JSON.parse(fs.readFileSync(options.tagRefsJson, "utf8"));
  const releases = JSON.parse(fs.readFileSync(options.releaseListJson, "utf8"));
  const common = {
    release,
    assets,
    releases,
    tagRefs,
    releaseId: options.releaseId,
    sourceFullSha: options.sourceFullSha,
    targetVersion: options.targetVersion,
  };
  const receipt = options.phase === "before"
    ? verifyDraftBeforeReplacement({
      ...common,
      replacementSourceFullSha: options.replacementSourceFullSha,
      artifactDirectory: options.artifactDirectory,
      expectedAssets: JSON.parse(process.env.SELFHOST_DRAFT_ASSET_INVENTORY_JSON),
      expectedInventorySha256: options.expectedInventorySha256,
    })
    : verifyDraftAfterStaging({ ...common, artifactDirectory: options.artifactDirectory });
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
