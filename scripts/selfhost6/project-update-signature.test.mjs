import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import {
  algorithm,
  bundleIdentity,
  payloadDomain,
  releaseLineId,
  signUpdateMetadata,
  signingKeyIdentity,
  verifySignedUpdateMetadata,
} from "../../resources/updater/project-update-signature.mjs";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");

const { privateKey, publicKey } = generateKeyPairSync("ed25519");
const publicKeyBase64 = publicKey.export({ format: "der", type: "spki" }).subarray(-32).toString("base64");
const privateKeyPem = privateKey.export({ format: "pem", type: "pkcs8" });
const policy = Object.freeze({
  schemaVersion: 1,
  algorithm,
  payloadDomain,
  releaseLineId,
  bundleIdentity,
  keyId: signingKeyIdentity(publicKeyBase64),
  publicKeyBase64,
});
const source = "a".repeat(40);
const archiveSha256 = "d".repeat(64);
const archiveSha512 = "b".repeat(128);
const metadata = {
  "schema-version": 1,
  algorithm,
  "key-id": policy.keyId,
  "release-line-id": releaseLineId,
  "target-source-full-sha": source,
  "target-version": "2.0.1-selfhost.8",
  platform: "darwin",
  arch: "arm64",
  "bundle-identity": bundleIdentity,
  "immutable-object-key": `${releaseLineId}/${source}/${archiveSha256}/darwin/arm64/Logseq.zip`,
  "archive-size": 123456,
  "archive-sha256": archiveSha256,
  "archive-sha512": archiveSha512,
  "target-build-manifest-sha256": "c".repeat(64),
  "readable-activation-formats": ["selfhost-activation-v1"],
  "readable-client-ops-formats": ["official-client-ops-sqlite-v2+selfhost-upload-v1"],
  "activation-write-format": "selfhost-activation-v1",
  "client-ops-write-format": "official-client-ops-sqlite-v2+selfhost-upload-v1",
};

test("one signature binds archive, target identity, and compatibility declarations", () => {
  const signed = signUpdateMetadata({ metadata, policy, privateKeyPem });
  assert.deepEqual(verifySignedUpdateMetadata({ signedMetadata: signed, policy }), metadata);
});

test("win32 arm64 is an admitted signed update target", () => {
  const winArm64 = {
    ...metadata,
    platform: "win32",
    arch: "arm64",
    "immutable-object-key": `${releaseLineId}/${source}/${archiveSha256}/win32/arm64/Logseq.exe`,
  };
  const signed = signUpdateMetadata({ metadata: winArm64, policy, privateKeyPem });
  assert.deepEqual(verifySignedUpdateMetadata({ signedMetadata: signed, policy }), winArm64);
});

test("a mutation of any signed field fails closed", () => {
  const signed = signUpdateMetadata({ metadata, policy, privateKeyPem });
  for (const field of Object.keys(metadata)) {
    const original = signed[field];
    const replacement = Array.isArray(original)
      ? [...original, "tampered"]
      : typeof original === "number"
        ? original + 1
        : `${original}tampered`;
    assert.throws(
      () => verifySignedUpdateMetadata({ signedMetadata: { ...signed, [field]: replacement }, policy }),
      undefined,
      field,
    );
  }
});

test("the trust anchor, validators, and bounded ZIP reader ship in the desktop runtime", () => {
  const builder = fs.readFileSync(path.join(repoRoot, "resources/electron-builder.yml"), "utf8");
  const runtimePackage = JSON.parse(fs.readFileSync(path.join(repoRoot, "resources/package.json"), "utf8"));
  const runtimeLock = fs.readFileSync(path.join(repoRoot, "resources/pnpm-lock.yaml"), "utf8");
  for (const resource of [
    "updater/project-update-signature.mjs",
    "updater/project-signing-policy.json",
    "updater/target-build-manifest.mjs",
  ]) {
    assert.match(builder, new RegExp(`from: ${resource.replaceAll("/", "\\/")}`));
  }
  assert.match(builder, /mac:\n(?:.|\n)*?extraResources:\n\s+- from: updater\/ProjectUpdater\n\s+to: updater\/ProjectUpdater/);
  assert.match(builder, /win:\n(?:.|\n)*?from: node_modules\/7zip-bin\/win\/x64\/7za\.exe\n\s+to: updater\/7za\.exe/);
  assert.match(builder, /nsis:\n(?:.|\n)*?include: windows\/selfhost6-updater\.nsh/);
  assert.match(builder, /publish:\n\s+- provider: generic\n\s+url: https:\/\/github\.com\/cfenglv\/logseq\/releases\/download\/selfhost-official-architecture-v1/);
  assert.match(runtimePackage.scripts["electron:make"],
    /^node updater\/prepare-target-build-manifest\.mjs && node updater\/build-macos-update-helper\.mjs && /);
  assert.equal(runtimePackage.scripts["electron:publish:github"], undefined);
  assert.equal(runtimePackage.devDependencies["7zip-bin"], "5.2.0");
  assert.equal(runtimePackage.dependencies.yauzl, "2.10.0");
  assert.match(runtimeLock, /yauzl:\n\s+specifier: 2\.10\.0\n\s+version: 2\.10\.0/);
});
