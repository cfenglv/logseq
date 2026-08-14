import assert from "node:assert/strict";
import { createHash, createPrivateKey, createPublicKey, sign, verify } from "node:crypto";
import fs from "node:fs";

export const algorithm = "ed25519-selfhost-release-v1";
export const payloadDomain = "logseq-selfhost-official-architecture-update-v1";
export const releaseLineId = "selfhost-official-architecture-v1";
export const bundleIdentity = "com.logseq.logseq";

const rawEd25519SpkiPrefix = Buffer.from("302a300506032b6570032100", "hex");
const fullShaPattern = /^[0-9a-f]{40}$/;
const sha256Pattern = /^[0-9a-f]{64}$/;
const sha512Pattern = /^[0-9a-f]{128}$/;
const allowedVersions = new Set(["2.0.1-selfhost.6", "2.0.1-selfhost.7"]);
const allowedTargets = new Set([
  "darwin/arm64",
  "darwin/x64",
  "win32/x64",
  "linux/arm64",
  "linux/x64",
]);
const signedFields = [
  "schema-version",
  "algorithm",
  "key-id",
  "release-line-id",
  "target-source-full-sha",
  "target-version",
  "platform",
  "arch",
  "bundle-identity",
  "immutable-object-key",
  "archive-size",
  "archive-sha256",
  "archive-sha512",
  "target-build-manifest-sha256",
  "readable-activation-formats",
  "readable-client-ops-formats",
  "activation-write-format",
  "client-ops-write-format",
];

function requireString(value, label) {
  assert.equal(typeof value, "string", `${label} must be a string`);
  assert.ok(value.length > 0, `${label} must not be empty`);
}

function requireFormatSet(value, label) {
  assert.ok(Array.isArray(value), `${label} must be an array`);
  assert.ok(value.length > 0, `${label} must not be empty`);
  assert.equal(new Set(value).size, value.length, `${label} must not contain duplicates`);
  for (const entry of value) requireString(entry, `${label} entry`);
}

function rawPublicKeyObject(publicKeyBase64) {
  const raw = Buffer.from(publicKeyBase64, "base64");
  assert.equal(raw.length, 32, "project update public key must be 32 raw Ed25519 bytes");
  assert.equal(raw.toString("base64"), publicKeyBase64, "project update public key must use canonical base64");
  return createPublicKey({ key: Buffer.concat([rawEd25519SpkiPrefix, raw]), format: "der", type: "spki" });
}

export function signingKeyIdentity(publicKeyBase64) {
  const raw = Buffer.from(publicKeyBase64, "base64");
  assert.equal(raw.length, 32, "project update public key must be 32 raw Ed25519 bytes");
  return `ed25519:${createHash("sha256").update(raw).digest("hex")}`;
}

export function loadProjectSigningPolicy(policyUrl = new URL("./project-signing-policy.json", import.meta.url)) {
  const policy = JSON.parse(fs.readFileSync(policyUrl, "utf8"));
  assert.deepEqual(new Set(Object.keys(policy)), new Set([
    "schemaVersion",
    "algorithm",
    "payloadDomain",
    "releaseLineId",
    "bundleIdentity",
    "keyId",
    "publicKeyBase64",
  ]));
  assert.equal(policy.schemaVersion, 1);
  assert.equal(policy.algorithm, algorithm);
  assert.equal(policy.payloadDomain, payloadDomain);
  assert.equal(policy.releaseLineId, releaseLineId);
  assert.equal(policy.bundleIdentity, bundleIdentity);
  assert.equal(policy.keyId, signingKeyIdentity(policy.publicKeyBase64));
  return Object.freeze(policy);
}

export function validateUnsignedMetadata(metadata, policy) {
  assert.ok(metadata && typeof metadata === "object" && !Array.isArray(metadata), "signed update metadata is required");
  assert.deepEqual(new Set(Object.keys(metadata)), new Set(signedFields), "unsigned update metadata fields must match schema v1");
  assert.equal(metadata["schema-version"], 1);
  assert.equal(metadata.algorithm, policy.algorithm);
  assert.equal(metadata["key-id"], policy.keyId);
  assert.equal(metadata["release-line-id"], policy.releaseLineId);
  assert.equal(metadata["bundle-identity"], policy.bundleIdentity);
  assert.match(metadata["target-source-full-sha"], fullShaPattern);
  assert.ok(allowedVersions.has(metadata["target-version"]), "unsupported update target version");
  assert.ok(allowedTargets.has(`${metadata.platform}/${metadata.arch}`), "unsupported update target platform/arch");
  requireString(metadata["immutable-object-key"], "immutable object key");
  assert.ok(metadata["immutable-object-key"].includes(metadata["release-line-id"]), "object key must include release line");
  assert.ok(metadata["immutable-object-key"].includes(metadata["target-source-full-sha"]), "object key must include target source SHA");
  assert.ok(Number.isSafeInteger(metadata["archive-size"]) && metadata["archive-size"] > 0, "archive size must be a positive safe integer");
  assert.match(metadata["archive-sha256"], sha256Pattern);
  assert.match(metadata["archive-sha512"], sha512Pattern);
  assert.ok(metadata["immutable-object-key"].includes(metadata["archive-sha256"]), "object key must include archive SHA-256");
  assert.match(metadata["target-build-manifest-sha256"], sha256Pattern);
  requireFormatSet(metadata["readable-activation-formats"], "readable activation formats");
  requireFormatSet(metadata["readable-client-ops-formats"], "readable client-ops formats");
  requireString(metadata["activation-write-format"], "activation write format");
  requireString(metadata["client-ops-write-format"], "client-ops write format");
  return metadata;
}

export function canonicalUpdatePayload(metadata, policy) {
  validateUnsignedMetadata(metadata, policy);
  return Buffer.from([
    policy.payloadDomain,
    ...signedFields.map((field) => `${field}=${JSON.stringify(metadata[field])}`),
    "",
  ].join("\n"), "utf8");
}

export function signUpdateMetadata({ metadata, policy, privateKeyPem }) {
  const payload = canonicalUpdatePayload(metadata, policy);
  const signature = sign(null, payload, createPrivateKey(privateKeyPem)).toString("base64");
  return Object.freeze({ ...metadata, signature });
}

export function verifySignedUpdateMetadata({ signedMetadata, policy = loadProjectSigningPolicy() }) {
  assert.ok(signedMetadata && typeof signedMetadata === "object" && !Array.isArray(signedMetadata), "signed update metadata is required");
  const { signature, ...metadata } = signedMetadata;
  requireString(signature, "update signature");
  const signatureBytes = Buffer.from(signature, "base64");
  assert.equal(signatureBytes.length, 64, "update signature must be 64 Ed25519 bytes");
  assert.equal(signatureBytes.toString("base64"), signature, "update signature must use canonical base64");
  const verified = verify(null, canonicalUpdatePayload(metadata, policy), rawPublicKeyObject(policy.publicKeyBase64), signatureBytes);
  assert.equal(verified, true, "project update signature is invalid");
  return Object.freeze(metadata);
}
