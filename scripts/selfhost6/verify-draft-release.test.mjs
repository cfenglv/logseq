import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
  draftInventorySha256,
  verifyDraftAfterStaging,
  verifyDraftBeforeReplacement,
} from "./verify-draft-release.mjs";

const releaseId = 424242;
const sourceFullSha = "a".repeat(40);
const replacementSourceFullSha = "b".repeat(40);
const targetVersion = "2.0.1-selfhost.7";

function digest(bytes) {
  return `sha256:${createHash("sha256").update(bytes).digest("hex")}`;
}

function fixture() {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "selfhost-draft-verifier-"));
  const names = [];
  for (let index = 0; index < 8; index += 1) {
    const archiveName = `Logseq-fixture-${index}-${targetVersion}.zip`;
    names.push(archiveName, `${archiveName}.selfhost6.json`);
  }
  const assets = [];
  const replacementAssets = names.sort().map((name, index) => {
    const oldBytes = Buffer.from(`old:${name}`);
    const replacementBytes = Buffer.from(`replacement:${name}`);
    fs.writeFileSync(path.join(directory, name), replacementBytes);
    assets.push({ id: 1000 + index, name, size: oldBytes.length, digest: digest(oldBytes) });
    return {
      id: 2000 + index,
      name,
      size: replacementBytes.length,
      digest: digest(replacementBytes),
    };
  });
  const release = {
    id: releaseId,
    tag_name: targetVersion,
    target_commitish: sourceFullSha,
    name: `Logseq ${targetVersion}`,
    body: `Source commit ${sourceFullSha}.`,
    draft: true,
    prerelease: false,
    published_at: null,
  };
  return { directory, assets, replacementAssets, release, releases: [release], tagRefs: [] };
}

test("frozen Draft identity and inventory pass before replacement", (context) => {
  const input = fixture();
  context.after(() => fs.rmSync(input.directory, { recursive: true, force: true }));
  assert.deepEqual(verifyDraftBeforeReplacement({
    ...input,
    releaseId: String(releaseId),
    sourceFullSha,
    replacementSourceFullSha,
    targetVersion,
    artifactDirectory: input.directory,
    expectedAssets: input.assets,
    expectedInventorySha256: draftInventorySha256(input.assets),
  }), { status: "draft-prewrite-verified" });
});

test("Draft replacement rejects identity or inventory drift", (context) => {
  const input = fixture();
  context.after(() => fs.rmSync(input.directory, { recursive: true, force: true }));
  const expectedInventorySha256 = draftInventorySha256(input.assets);
  assert.throws(() => verifyDraftBeforeReplacement({
    ...input,
    release: { ...input.release, target_commitish: "c".repeat(40) },
    releaseId: String(releaseId),
    sourceFullSha,
    replacementSourceFullSha,
    targetVersion,
    artifactDirectory: input.directory,
    expectedAssets: input.assets,
    expectedInventorySha256,
  }), /neither frozen nor replacement/);
  assert.throws(() => verifyDraftBeforeReplacement({
    ...input,
    assets: input.assets.map((asset, index) => index === 0
      ? { ...asset, digest: `sha256:${"c".repeat(64)}` }
      : asset),
    releaseId: String(releaseId),
    sourceFullSha,
    replacementSourceFullSha,
    targetVersion,
    artifactDirectory: input.directory,
    expectedAssets: input.assets,
    expectedInventorySha256,
  }), /outside the frozen\/replacement boundary/);
  assert.throws(() => verifyDraftBeforeReplacement({
    ...input,
    assets: input.assets.map((asset, index) => index === 0
      ? { ...asset, id: asset.id + 10000 }
      : asset),
    releaseId: String(releaseId),
    sourceFullSha,
    replacementSourceFullSha,
    targetVersion,
    artifactDirectory: input.directory,
    expectedAssets: input.assets,
    expectedInventorySha256,
  }), /outside the frozen\/replacement boundary/);
  assert.throws(() => verifyDraftBeforeReplacement({
    ...input,
    tagRefs: [{ ref: `refs/tags/${targetVersion}` }],
    releaseId: String(releaseId),
    sourceFullSha,
    replacementSourceFullSha,
    targetVersion,
    artifactDirectory: input.directory,
    expectedAssets: input.assets,
    expectedInventorySha256,
  }), /unexpectedly has a tag ref/);
  assert.throws(() => verifyDraftBeforeReplacement({
    ...input,
    releases: [input.release, { ...input.release, id: releaseId + 1 }],
    releaseId: String(releaseId),
    sourceFullSha,
    replacementSourceFullSha,
    targetVersion,
    artifactDirectory: input.directory,
    expectedAssets: input.assets,
    expectedInventorySha256,
  }), /must be unique/);
  const renamedFrozenAssets = input.assets.map((asset, index) => index === 0
    ? { ...asset, name: `Logseq-renamed-${targetVersion}.zip` }
    : asset);
  assert.throws(() => verifyDraftBeforeReplacement({
    ...input,
    releaseId: String(releaseId),
    sourceFullSha,
    replacementSourceFullSha,
    targetVersion,
    artifactDirectory: input.directory,
    expectedAssets: renamedFrozenAssets,
    expectedInventorySha256: draftInventorySha256(renamedFrozenAssets),
  }), /asset names differ/);
});

test("a partial official Draft replacement remains safely resumable", (context) => {
  const input = fixture();
  context.after(() => fs.rmSync(input.directory, { recursive: true, force: true }));
  const mixedAssets = input.assets.map((asset, index) => index % 2 === 0
    ? input.replacementAssets[index]
    : asset).slice(0, -1);
  assert.deepEqual(verifyDraftBeforeReplacement({
    ...input,
    release: {
      ...input.release,
      target_commitish: replacementSourceFullSha,
      body: `Source commit ${replacementSourceFullSha}.`,
    },
    assets: mixedAssets,
    releaseId: String(releaseId),
    sourceFullSha,
    replacementSourceFullSha,
    targetVersion,
    artifactDirectory: input.directory,
    expectedAssets: input.assets,
    expectedInventorySha256: draftInventorySha256(input.assets),
  }), { status: "draft-prewrite-verified" });
});

test("staged Draft must exactly match all local verified assets", (context) => {
  const input = fixture();
  context.after(() => fs.rmSync(input.directory, { recursive: true, force: true }));
  assert.throws(() => verifyDraftAfterStaging({
    ...input,
    release: { ...input.release, target_commitish: replacementSourceFullSha },
    assets: input.replacementAssets,
    releaseId: String(releaseId),
    sourceFullSha: replacementSourceFullSha,
    targetVersion,
    artifactDirectory: input.directory,
  }), /Draft body changed/);
  assert.deepEqual(verifyDraftAfterStaging({
    ...input,
    release: {
      ...input.release,
      target_commitish: replacementSourceFullSha,
      body: `Source commit ${replacementSourceFullSha}.`,
    },
    assets: input.replacementAssets,
    releaseId: String(releaseId),
    sourceFullSha: replacementSourceFullSha,
    targetVersion,
    artifactDirectory: input.directory,
  }), { status: "draft-staging-verified" });
  assert.throws(() => verifyDraftAfterStaging({
    ...input,
    release: {
      ...input.release,
      target_commitish: replacementSourceFullSha,
      body: `Source commit ${replacementSourceFullSha}.`,
    },
    assets: input.replacementAssets.map((asset, index) => index === 0
      ? { ...asset, size: asset.size + 1 }
      : asset),
    releaseId: String(releaseId),
    sourceFullSha: replacementSourceFullSha,
    targetVersion,
    artifactDirectory: input.directory,
  }), /staged Draft assets differ/);
});

test("staged Draft rejects every published-envelope drift", (context) => {
  const input = fixture();
  context.after(() => fs.rmSync(input.directory, { recursive: true, force: true }));
  const exactRelease = {
    ...input.release,
    target_commitish: replacementSourceFullSha,
    body: `Source commit ${replacementSourceFullSha}.`,
  };
  const cases = [
    [{ ...exactRelease, id: releaseId + 1 }, /Draft release id changed/],
    [{ ...exactRelease, draft: false }, /must remain a Draft/],
    [{ ...exactRelease, prerelease: true }, /must not be a prerelease/],
    [{ ...exactRelease, published_at: "2026-08-29T00:00:00Z" }, /must remain unpublished/],
  ];
  for (const [release, expected] of cases) {
    assert.throws(() => verifyDraftAfterStaging({
      ...input,
      release,
      assets: input.replacementAssets,
      releaseId: String(releaseId),
      sourceFullSha: replacementSourceFullSha,
      targetVersion,
      artifactDirectory: input.directory,
    }), expected);
  }
});

test("Draft verifier CLI emits only a constant failure status", (context) => {
  const input = fixture();
  context.after(() => fs.rmSync(input.directory, { recursive: true, force: true }));
  const releaseJson = path.join(input.directory, "release.json");
  const assetsJson = path.join(input.directory, "assets.json");
  const tagRefsJson = path.join(input.directory, "tag-refs.json");
  const releaseListJson = path.join(input.directory, "release-list.json");
  fs.writeFileSync(releaseJson, JSON.stringify({ ...input.release, id: releaseId + 1 }));
  fs.writeFileSync(assetsJson, JSON.stringify(input.assets));
  fs.writeFileSync(tagRefsJson, JSON.stringify(input.tagRefs));
  fs.writeFileSync(releaseListJson, JSON.stringify(input.releases));
  const result = spawnSync(process.execPath, [
    fileURLToPath(new URL("./verify-draft-release.mjs", import.meta.url)),
    "--phase", "before",
    "--release-json", releaseJson,
    "--assets-json", assetsJson,
    "--tag-refs-json", tagRefsJson,
    "--release-list-json", releaseListJson,
    "--artifact-directory", input.directory,
    "--release-id", String(releaseId),
    "--source-full-sha", sourceFullSha,
    "--replacement-source-full-sha", replacementSourceFullSha,
    "--target-version", targetVersion,
    "--expected-inventory-sha256", draftInventorySha256(input.assets),
  ], {
    encoding: "utf8",
    env: {
      ...process.env,
      SELFHOST_DRAFT_ASSET_INVENTORY_JSON: JSON.stringify(input.assets),
    },
  });
  assert.equal(result.status, 1);
  assert.equal(result.stdout, "");
  assert.equal(result.stderr, "Draft release verification failed\n");
});
