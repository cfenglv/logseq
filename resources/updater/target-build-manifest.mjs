import assert from "node:assert/strict";

const fullShaPattern = /^[0-9a-f]{40}$/;
const manifestFields = new Set([
  "schema-version",
  "target-source-full-sha",
  "target-version",
  "release-line-id",
  "platform",
  "arch",
  "bundle-identity",
  "signing-key-identity",
  "readable-activation-formats",
  "readable-client-ops-formats",
  "activation-write-format",
  "client-ops-write-format",
]);

function requireString(value, label) {
  assert.equal(typeof value, "string", `${label} must be a string`);
  assert.ok(value.length > 0, `${label} must not be empty`);
}

function requireFormatSet(value, label) {
  assert.ok(Array.isArray(value), `${label} must be an array`);
  assert.ok(value.length > 0, `${label} must not be empty`);
  assert.equal(new Set(value).size, value.length, `${label} must not contain duplicates`);
  for (const format of value) requireString(format, `${label} entry`);
}

export function validateTargetBuildManifest({
  manifest,
  archiveDigestVerified,
  expected,
  currentFormats,
}) {
  assert.equal(archiveDigestVerified, true, "target archive digest must be verified before manifest preflight");
  assert.ok(manifest && typeof manifest === "object" && !Array.isArray(manifest), "target build manifest is required");
  assert.deepEqual(new Set(Object.keys(manifest)), manifestFields, "target build manifest fields must match schema v1");
  assert.equal(manifest["schema-version"], 1, "unsupported target build manifest schema");

  const identityPairs = [
    ["target-source-full-sha", "targetSourceFullSha"],
    ["target-version", "targetVersion"],
    ["release-line-id", "releaseLineId"],
    ["platform", "platform"],
    ["arch", "arch"],
    ["bundle-identity", "bundleIdentity"],
    ["signing-key-identity", "signingKeyIdentity"],
  ];
  assert.ok(expected && typeof expected === "object", "expected target identity is required");
  for (const [manifestKey, expectedKey] of identityPairs) {
    requireString(expected[expectedKey], `expected ${expectedKey}`);
    assert.equal(manifest[manifestKey], expected[expectedKey], `${manifestKey} does not match expected target identity`);
  }
  assert.match(manifest["target-source-full-sha"], fullShaPattern, "target source full SHA is invalid");

  requireFormatSet(manifest["readable-activation-formats"], "readable activation formats");
  requireFormatSet(manifest["readable-client-ops-formats"], "readable client-ops formats");
  requireString(manifest["activation-write-format"], "activation write format");
  requireString(manifest["client-ops-write-format"], "client-ops write format");
  assert.ok(currentFormats && typeof currentFormats === "object", "current format identity is required");
  requireString(currentFormats.activation, "current activation format");
  requireString(currentFormats.clientOps, "current client-ops format");
  assert.ok(
    manifest["readable-activation-formats"].includes(currentFormats.activation),
    "target cannot read the current activation format",
  );
  assert.ok(
    manifest["readable-client-ops-formats"].includes(currentFormats.clientOps),
    "target cannot read the current client-ops format",
  );
  assert.ok(
    manifest["readable-activation-formats"].includes(manifest["activation-write-format"]),
    "target activation write format must be readable by the target",
  );
  assert.ok(
    manifest["readable-client-ops-formats"].includes(manifest["client-ops-write-format"]),
    "target client-ops write format must be readable by the target",
  );

  return manifest;
}
