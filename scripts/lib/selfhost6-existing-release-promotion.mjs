import assert from "node:assert/strict";
import { createHash } from "node:crypto";

const fullShaPattern = /^[0-9a-f]{40}$/;
const versionPattern = /^2\.0\.1-selfhost\.(\d+)$/;

export class PromotionError extends Error {
  constructor(message, receipt) {
    super(message);
    this.name = "PromotionError";
    this.receipt = receipt;
  }
}

function digest(bytes) {
  return `sha256:${createHash("sha256").update(bytes).digest("hex")}`;
}

function localRecord(name, bytes) {
  assert.ok(Buffer.isBuffer(bytes), `${name} must be a Buffer`);
  return Object.freeze({
    present: true,
    name,
    size: bytes.length,
    digest: digest(bytes),
    bytes,
  });
}

function absentRecord(name) {
  return Object.freeze({ present: false, name, size: 0, digest: null, bytes: null });
}

function sameRecord(left, right) {
  return left.present === right.present &&
    left.size === right.size &&
    left.digest === right.digest &&
    (!left.present || left.bytes.equals(right.bytes));
}

function receiptRecord(record) {
  return {
    present: record.present,
    size: record.size,
    digest: record.digest,
    bytesBase64: record.present ? record.bytes.toString("base64") : null,
  };
}

async function assetByName(api, release, name) {
  const matches = (await api.listAssets(release.id)).filter((asset) => asset.name === name);
  assert.ok(matches.length <= 1, `release contains duplicate asset name ${name}`);
  return matches[0] ?? null;
}

async function remoteRecord(api, release, name) {
  const asset = await assetByName(api, release, name);
  if (!asset) return absentRecord(name);
  const bytes = Buffer.from(await api.downloadAsset(asset.id));
  const record = localRecord(name, bytes);
  assert.equal(asset.size, record.size, `remote asset size differs for ${name}`);
  assert.equal(asset.digest, record.digest, `remote asset digest differs for ${name}`);
  return Object.freeze({ ...record, assetId: asset.id });
}

async function protectedAssetInventory(api, release, pointerNames) {
  const result = new Map();
  for (const asset of await api.listAssets(release.id)) {
    if (pointerNames.has(asset.name)) continue;
    assert.equal(result.has(asset.name), false, `release contains duplicate asset name ${asset.name}`);
    assert.ok(Number.isSafeInteger(asset.id), `release asset id is invalid for ${asset.name}`);
    assert.ok(Number.isSafeInteger(asset.size) && asset.size > 0,
      `release asset size is invalid for ${asset.name}`);
    assert.match(asset.digest ?? "", /^sha256:[0-9a-f]{64}$/,
      `release asset digest is invalid for ${asset.name}`);
    result.set(asset.name, {
      id: asset.id,
      name: asset.name,
      size: asset.size,
      digest: asset.digest,
    });
  }
  return result;
}

async function assertProtectedAssets(api, release, expected, pointerNames) {
  const current = await protectedAssetInventory(api, release, pointerNames);
  for (const [name, identity] of expected) {
    assert.deepEqual(current.get(name), identity,
      `existing immutable release asset changed during promotion: ${name}`);
  }
}

function assertSameRecord(actual, expected, message) {
  assert.ok(sameRecord(actual, expected), message);
}

function versionOrdinal(version) {
  const match = versionPattern.exec(version);
  assert.ok(match, `unsupported release version ${version}`);
  return Number(match[1]);
}

function pointerMetadata(bytes, name) {
  let metadata;
  try {
    metadata = JSON.parse(bytes.toString("utf8"));
  } catch {
    throw new Error(`channel pointer ${name} is not canonical JSON`);
  }
  assert.equal(typeof metadata.version, "string", `channel pointer ${name} has no version`);
  assert.ok(Array.isArray(metadata.files) && metadata.files.length > 0,
    `channel pointer ${name} has no files`);
  const signatures = Object.values(metadata.selfhostUpdateSignatures ?? {});
  assert.ok(signatures.length > 0, `channel pointer ${name} has no signed metadata`);
  const versions = new Set(signatures.map((value) => value["target-version"]));
  const sourceShas = new Set(signatures.map((value) => value["target-source-full-sha"]));
  assert.deepEqual(versions, new Set([metadata.version]),
    `channel pointer ${name} mixes target versions`);
  assert.equal(sourceShas.size, 1, `channel pointer ${name} mixes source SHAs`);
  const sourceFullSha = [...sourceShas][0];
  assert.match(sourceFullSha ?? "", fullShaPattern,
    `channel pointer ${name} has an invalid source SHA`);
  return { version: metadata.version, sourceFullSha };
}

export function bridgeImmutableAssetNames(version) {
  return [
    `Logseq-darwin-arm64-${version}.zip`,
    `Logseq-darwin-x64-${version}.zip`,
    `Logseq-linux-arm64-${version}.AppImage`,
    `Logseq-linux-x86_64-${version}.AppImage`,
    `Logseq-win-x64-${version}-nsis.exe`,
  ];
}

function validateInputs({
  releaseLineId,
  expectedSourceFullSha,
  expectedVersion,
  expectedReleaseIdentity,
  immutableFiles,
  pointerFiles,
  initialPointerBaseline,
}) {
  assert.equal(typeof releaseLineId, "string");
  assert.ok(releaseLineId.length > 0, "release line id is required");
  assert.match(expectedSourceFullSha, fullShaPattern, "expected source SHA must be full");
  versionOrdinal(expectedVersion);
  assert.ok(Number.isSafeInteger(expectedReleaseIdentity?.releaseId),
    "frozen existing release id is required");
  for (const field of [
    "releaseTargetFullSha",
    "tagObjectFullSha",
    "tagPeeledCommitFullSha",
  ]) assert.match(expectedReleaseIdentity[field] ?? "", fullShaPattern,
    `frozen existing ${field} is required`);
  assert.ok(immutableFiles instanceof Map, "immutable files must be a Map");
  assert.deepEqual(
    [...immutableFiles.keys()].sort(),
    bridgeImmutableAssetNames(expectedVersion).sort(),
    "bridge promotion requires exactly five admitted updater assets",
  );

  const expectedPointerNames = new Set([
    `${releaseLineId}.yml`,
    `${releaseLineId}-mac.yml`,
    `${releaseLineId}-linux.yml`,
    `${releaseLineId}-linux-arm64.yml`,
  ]);
  assert.ok(pointerFiles instanceof Map, "pointer files must be a Map");
  assert.deepEqual(new Set(pointerFiles.keys()), expectedPointerNames,
    "promotion must update exactly the four release-line pointers");
  assert.ok(["absent", "compatible-prior"].includes(initialPointerBaseline),
    "initial pointer baseline is invalid");
  for (const [name, bytes] of pointerFiles) {
    const identity = pointerMetadata(bytes, name);
    assert.equal(identity.version, expectedVersion, `${name} target version differs`);
    assert.equal(identity.sourceFullSha, expectedSourceFullSha, `${name} source SHA differs`);
  }
}

async function releaseIdentity(api, releaseLineId) {
  const release = await api.getReleaseByTag(releaseLineId);
  assert.ok(Number.isSafeInteger(release.id), "existing release id is invalid");
  assert.equal(release.tagName, releaseLineId, "existing release tag differs");
  assert.match(release.targetCommitish ?? "", fullShaPattern,
    "existing release target must remain a full SHA");
  const tag = await api.getTagIdentity(releaseLineId);
  assert.match(tag.objectSha ?? "", fullShaPattern, "release tag object SHA is invalid");
  assert.match(tag.peeledCommitSha ?? "", fullShaPattern, "release tag peeled commit is invalid");
  return {
    release: {
      id: release.id,
      tagName: release.tagName,
      targetCommitish: release.targetCommitish,
    },
    tag: {
      objectSha: tag.objectSha,
      peeledCommitSha: tag.peeledCommitSha,
    },
    uploadOwner: release,
  };
}

function publicReleaseIdentity(identity) {
  return { release: identity.release, tag: identity.tag };
}

function assertReleaseIdentity(actual, expected) {
  assert.deepEqual(publicReleaseIdentity(actual), publicReleaseIdentity(expected),
    "existing Release/tag identity changed during promotion");
}

function validateExpectedReleaseIdentity(actual, expected, releaseLineId) {
  assert.ok(expected && typeof expected === "object", "frozen existing release identity is required");
  assert.deepEqual(publicReleaseIdentity(actual), {
    release: {
      id: expected.releaseId,
      tagName: releaseLineId,
      targetCommitish: expected.releaseTargetFullSha,
    },
    tag: {
      objectSha: expected.tagObjectFullSha,
      peeledCommitSha: expected.tagPeeledCommitFullSha,
    },
  }, "existing Release/tag identity differs from the frozen gate");
}

async function assertPointerOwnership(api, release, snapshots, promoted) {
  for (const [name, snapshot] of snapshots) {
    const expected = promoted.get(name) ?? snapshot;
    const current = await remoteRecord(api, release, name);
    assertSameRecord(current, expected,
      `channel pointer ${name} changed after the promotion snapshot`);
  }
}

function validateExistingPointers(snapshots, targets, expectedVersion, initialPointerBaseline) {
  const expectedOrdinal = versionOrdinal(expectedVersion);
  for (const [name, snapshot] of snapshots) {
    if (!snapshot.present || sameRecord(snapshot, targets.get(name))) continue;
    assert.equal(initialPointerBaseline, "compatible-prior",
      `channel pointer ${name} violates the frozen absent baseline`);
    const identity = pointerMetadata(snapshot.bytes, name);
    assert.ok(versionOrdinal(identity.version) < expectedOrdinal,
      `channel pointer ${name} already targets a conflicting release`);
  }
}

async function restorePointer({ api, release, name, snapshot, target }) {
  let current = await remoteRecord(api, release, name);
  if (sameRecord(current, snapshot)) return;
  assert.ok(!current.present || sameRecord(current, target),
    `channel pointer ${name} is no longer owned by this promotion`);
  if (current.present) await api.deleteAsset(current.assetId);
  if (snapshot.present) await api.uploadAsset(release, name, snapshot.bytes);
  current = await remoteRecord(api, release, name);
  assertSameRecord(current, snapshot, `channel pointer ${name} was not restored`);
}

export async function promoteExistingRelease({
  releaseLineId,
  expectedSourceFullSha,
  expectedVersion,
  expectedReleaseIdentity,
  immutableFiles,
  pointerFiles,
  api,
  initialPointerBaseline = "compatible-prior",
}) {
  validateInputs({
    releaseLineId,
    expectedSourceFullSha,
    expectedVersion,
    expectedReleaseIdentity,
    immutableFiles,
    pointerFiles,
    initialPointerBaseline,
  });
  assert.ok(api, "release API is required");

  const before = await releaseIdentity(api, releaseLineId);
  validateExpectedReleaseIdentity(before, expectedReleaseIdentity, releaseLineId);
  const release = before.uploadOwner;
  const pointerNameSet = new Set(pointerFiles.keys());
  const protectedAssetsBefore = await protectedAssetInventory(api, release, pointerNameSet);
  const immutableAssets = [];
  const targets = new Map(
    [...pointerFiles].map(([name, bytes]) => [name, localRecord(name, bytes)]),
  );
  const snapshots = new Map();
  const promoted = new Map();
  const mutatedNames = [];
  let activeName = null;

  try {
    for (const [name, bytes] of immutableFiles) {
      const expected = localRecord(name, bytes);
      let current = await remoteRecord(api, release, name);
      if (current.present) {
        assertSameRecord(current, expected,
          `existing immutable asset ${name} differs from the formal artifact`);
        immutableAssets.push({ name, disposition: "reused", size: expected.size, digest: expected.digest });
        continue;
      }
      await api.uploadAsset(release, name, bytes);
      current = await remoteRecord(api, release, name);
      assertSameRecord(current, expected, `uploaded immutable asset ${name} failed read-back`);
      immutableAssets.push({ name, disposition: "uploaded", size: expected.size, digest: expected.digest });
    }

    assertReleaseIdentity(await releaseIdentity(api, releaseLineId), before);
    await assertProtectedAssets(api, release, protectedAssetsBefore, pointerNameSet);
    for (const name of pointerFiles.keys()) {
      snapshots.set(name, await remoteRecord(api, release, name));
    }
    validateExistingPointers(snapshots, targets, expectedVersion, initialPointerBaseline);

    for (const name of pointerFiles.keys()) {
      const snapshot = snapshots.get(name);
      const target = targets.get(name);
      if (sameRecord(snapshot, target)) {
        promoted.set(name, target);
        continue;
      }
      await assertPointerOwnership(api, release, snapshots, promoted);
      activeName = name;
      if (snapshot.present) await api.deleteAsset(snapshot.assetId);
      await api.uploadAsset(release, name, target.bytes);
      const current = await remoteRecord(api, release, name);
      assertSameRecord(current, target, `channel pointer ${name} failed read-back`);
      mutatedNames.push(name);
      promoted.set(name, target);
      activeName = null;
    }

    await assertPointerOwnership(api, release, snapshots, promoted);
    const after = await releaseIdentity(api, releaseLineId);
    assertReleaseIdentity(after, before);
    await assertProtectedAssets(api, release, protectedAssetsBefore, pointerNameSet);
    return Object.freeze({
      schemaVersion: 1,
      kind: "selfhost-existing-release-promotion.v1",
      status: "promotion-complete",
      expectedSourceFullSha,
      expectedVersion,
      releaseIdentity: {
        before: publicReleaseIdentity(before),
        after: publicReleaseIdentity(after),
      },
      immutableAssets,
      pointers: [...pointerFiles.keys()].map((name) => ({
        name,
        disposition: mutatedNames.includes(name) ? "updated" : "reused",
        size: targets.get(name).size,
        digest: targets.get(name).digest,
      })),
    });
  } catch (cause) {
    const recoveryNames = [...new Set([
      ...mutatedNames,
      ...(activeName ? [activeName] : []),
    ])].reverse();
    for (const name of recoveryNames) {
      try {
        await restorePointer({
          api,
          release,
          name,
          snapshot: snapshots.get(name),
          target: targets.get(name),
        });
      } catch {
        // Final read-back records exact recovery targets. Never overwrite bytes
        // that are no longer owned by this promotion.
      }
    }

    const pendingRecovery = [];
    if (snapshots.size > 0) {
      for (const [name, snapshot] of snapshots) {
        try {
          const current = await remoteRecord(api, release, name);
          if (sameRecord(current, snapshot)) continue;
          pendingRecovery.push({
            name,
            snapshot: receiptRecord(snapshot),
            current: receiptRecord(current),
          });
        } catch (error) {
          pendingRecovery.push({
            name,
            snapshot: receiptRecord(snapshot),
            current: null,
            readError: error instanceof Error ? error.message : String(error),
          });
        }
      }
    }

    let after = null;
    let releaseIdentityChanged = false;
    try {
      after = await releaseIdentity(api, releaseLineId);
      assertReleaseIdentity(after, before);
    } catch {
      releaseIdentityChanged = true;
    }
    let protectedAssetsChanged = false;
    try {
      await assertProtectedAssets(api, release, protectedAssetsBefore, pointerNameSet);
    } catch {
      protectedAssetsChanged = true;
    }
    const possiblePointerMutation = mutatedNames.length > 0 || activeName !== null;
    const recovered = possiblePointerMutation &&
      pendingRecovery.length === 0 &&
      !releaseIdentityChanged &&
      !protectedAssetsChanged;
    const status = recovered
      ? "promotion-failed-recovered"
      : !possiblePointerMutation && pendingRecovery.length === 0 &&
          !releaseIdentityChanged && !protectedAssetsChanged
        ? "promotion-failed-no-pointer-mutation"
        : "promotion-incomplete/recovery-required";
    throw new PromotionError("existing-release promotion failed", {
      schemaVersion: 1,
      kind: "selfhost-existing-release-promotion.v1",
      status,
      expectedSourceFullSha,
      expectedVersion,
      failure: cause instanceof Error ? cause.message : String(cause),
      releaseIdentity: {
        before: publicReleaseIdentity(before),
        after: after ? publicReleaseIdentity(after) : null,
        changed: releaseIdentityChanged,
      },
      protectedAssetsChanged,
      immutableAssets,
      successfulPointerWrites: [...mutatedNames],
      pendingRecovery,
    });
  }
}
