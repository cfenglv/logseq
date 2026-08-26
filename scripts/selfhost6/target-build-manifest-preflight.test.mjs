import assert from "node:assert/strict";
import test from "node:test";
import { validateTargetBuildManifest } from "../../resources/updater/target-build-manifest.mjs";

const manifest = {
  "schema-version": 1,
  "target-source-full-sha": "a".repeat(40),
  "target-version": "2.0.1-selfhost.8",
  "release-line-id": "selfhost-official-architecture-v1",
  platform: "darwin",
  arch: "arm64",
  "bundle-identity": "com.logseq.logseq",
  "signing-key-identity": "managed-test-key",
  "readable-activation-formats": ["selfhost-activation-v1"],
  "readable-client-ops-formats": ["official-client-ops-sqlite-v2+selfhost-upload-v1"],
  "activation-write-format": "selfhost-activation-v1",
  "client-ops-write-format": "official-client-ops-sqlite-v2+selfhost-upload-v1",
};

const validInput = {
  manifest,
  archiveDigestVerified: true,
  expected: {
    targetSourceFullSha: "a".repeat(40),
    targetVersion: "2.0.1-selfhost.8",
    releaseLineId: "selfhost-official-architecture-v1",
    platform: "darwin",
    arch: "arm64",
    bundleIdentity: "com.logseq.logseq",
    signingKeyIdentity: "managed-test-key",
  },
  currentFormats: {
    activation: "selfhost-activation-v1",
    clientOps: "official-client-ops-sqlite-v2+selfhost-upload-v1",
  },
};

test("verified compatible target passes before updater quiesce", () => {
  assert.equal(validateTargetBuildManifest(validInput), manifest);
});

test("missing, incompatible, identity-mismatched, or unverified targets fail closed", () => {
  assert.throws(
    () => validateTargetBuildManifest({ ...validInput, manifest: null }),
    /manifest is required/,
  );
  assert.throws(
    () => validateTargetBuildManifest({ ...validInput, archiveDigestVerified: false }),
    /archive digest/,
  );
  assert.throws(
    () => validateTargetBuildManifest({
      ...validInput,
      expected: { ...validInput.expected, targetSourceFullSha: "b".repeat(40) },
    }),
    /target-source-full-sha/,
  );
  assert.throws(
    () => validateTargetBuildManifest({
      ...validInput,
      manifest: { ...manifest, "readable-client-ops-formats": ["future-only"] },
    }),
    /cannot read the current client-ops format/,
  );
});
