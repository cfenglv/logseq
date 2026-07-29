#!/usr/bin/env node

import {
  createHash,
  createPublicKey,
  verify,
} from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import {
  loadProjectSigningPolicy,
  projectUpdatePayload,
} from "../resources/project-updater-signature.mjs";

const values = new Map();
for (let index = 2; index < process.argv.length; index += 2) {
  const key = process.argv[index];
  const value = process.argv[index + 1];
  if (!key?.startsWith("--") || !value || values.has(key)) {
    throw new Error(`invalid or duplicate argument near ${key || "<end>"}`);
  }
  values.set(key, value);
}
const required = (key) => {
  const value = values.get(key);
  if (!value) throw new Error(`missing ${key}`);
  return value;
};
const arch = required("--arch");
const version = required("--version");
const archive = path.resolve(required("--archive"));
const metadata = path.resolve(required("--metadata"));
if (!["arm64", "x64"].includes(arch)) throw new Error("unsupported architecture");

const yaml = fs.readFileSync(metadata, "utf8");
const field = (name) => {
  const value = yaml
    .match(new RegExp(`^  ${name}:\\s*['"]?([^'"\\n]+)['"]?$`, "m"))?.[1]
    ?.trim();
  if (!value) throw new Error(`metadata projectUpdateSignature is missing ${name}`);
  return value;
};
if (!/^projectUpdateSignature:\s*$/m.test(yaml)) {
  throw new Error("metadata has no projectUpdateSignature");
}
const manifest = {
  algorithm: field("algorithm"),
  arch: field("arch"),
  bundleId: field("bundleId"),
  keyId: field("keyId"),
  sha512: field("zipSha512"),
  signature: field("signature"),
  size: field("zipSize"),
  version: field("version"),
};
const policy = loadProjectSigningPolicy();
const archiveBytes = fs.readFileSync(archive);
const actualSize = String(archiveBytes.length);
const actualSha512 = createHash("sha512").update(archiveBytes).digest("hex");
if (
  manifest.algorithm !== policy.algorithm ||
  manifest.keyId !== policy.keyId ||
  manifest.bundleId !== policy.bundleIdentifier ||
  manifest.version !== version ||
  manifest.arch !== arch ||
  manifest.size !== actualSize ||
  manifest.sha512 !== actualSha512
) {
  throw new Error("signed project update manifest does not match the artifact");
}
const rawPublicKey = Buffer.from(policy.publicKeyBase64, "base64");
const publicKey = createPublicKey({
  key: Buffer.concat([
    Buffer.from("302a300506032b6570032100", "hex"),
    rawPublicKey,
  ]),
  format: "der",
  type: "spki",
});
const payload = projectUpdatePayload({
  arch,
  sha512: actualSha512,
  size: actualSize,
  version,
});
if (
  !verify(
    null,
    Buffer.from(payload),
    publicKey,
    Buffer.from(manifest.signature, "base64"),
  )
) {
  throw new Error("metadata Ed25519 project update signature is invalid");
}
console.log(
  `[project-update-verify] OK version=${version} arch=${arch} keyId=${policy.keyId}`,
);
