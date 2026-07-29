#!/usr/bin/env node

import {
  createPrivateKey,
  createPublicKey,
  createHash,
  sign,
} from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  projectUpdateAlgorithm,
  projectUpdateBundleIdentifier,
  projectUpdateKeyId,
  projectUpdatePayload,
  parseSelfhostProjectVersion,
} from "./project-update-signing.mjs";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const values = new Map();
for (let index = 2; index < process.argv.length; index += 2) {
  const key = process.argv[index];
  const value = process.argv[index + 1];
  if (!key?.startsWith("--") || !value) {
    throw new Error(`invalid argument near ${key || "<end>"}`);
  }
  if (values.has(key)) throw new Error(`duplicate ${key}`);
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
parseSelfhostProjectVersion(version);
const privateKeyBase64 =
  process.env.LOGSEQ_MACOS_UPDATE_ED25519_PRIVATE_KEY_BASE64?.trim();
if (!privateKeyBase64) {
  throw new Error(
    "missing LOGSEQ_MACOS_UPDATE_ED25519_PRIVATE_KEY_BASE64; signing is fail-closed",
  );
}
const privateKey = createPrivateKey({
  key: Buffer.from(privateKeyBase64, "base64"),
  format: "der",
  type: "pkcs8",
});
if (privateKey.asymmetricKeyType !== "ed25519") {
  throw new Error("project update private key must be Ed25519 PKCS#8 DER");
}
const publicKey = createPublicKey(privateKey);
const spki = publicKey.export({ format: "der", type: "spki" });
const rawPublicKey = spki.subarray(-32);
const publicManifest = JSON.parse(
  fs.readFileSync(
    path.join(
      repoRoot,
      "resources",
      "updater",
      "project-signing-policy.json",
    ),
    "utf8",
  ),
);
if (
  publicManifest.algorithm !== projectUpdateAlgorithm ||
  rawPublicKey.toString("base64") !== publicManifest.publicKeyBase64 ||
  projectUpdateKeyId(rawPublicKey) !== publicManifest.keyId
) {
  throw new Error("private key does not match the fixed project update public key");
}

const archiveHash = createHash("sha512");
let size = 0;
for await (const chunk of fs.createReadStream(archive)) {
  size += chunk.length;
  archiveHash.update(chunk);
}
const sha512 = archiveHash.digest("hex");
const payload = projectUpdatePayload({ arch, sha512, size, version });
const signature = sign(null, Buffer.from(payload), privateKey).toString("base64");
const block = [
  "projectUpdateSignature:",
  `  algorithm: ${projectUpdateAlgorithm}`,
  `  keyId: ${publicManifest.keyId}`,
  `  bundleId: ${projectUpdateBundleIdentifier}`,
  `  version: ${version}`,
  `  arch: ${arch}`,
  `  zipSize: '${size}'`,
  `  zipSha512: ${sha512}`,
  `  signature: ${signature}`,
  "",
].join("\n");
const currentMetadata = fs.readFileSync(metadata, "utf8");
if (/^projectUpdateSignature:/m.test(currentMetadata)) {
  throw new Error("metadata already contains a project update signature");
}
fs.appendFileSync(metadata, block);
const signatureOutput = values.get("--signature-output");
if (signatureOutput) {
  fs.writeFileSync(
    path.resolve(signatureOutput),
    `${JSON.stringify(
      {
        algorithm: projectUpdateAlgorithm,
        arch,
        bundleId: projectUpdateBundleIdentifier,
        keyId: publicManifest.keyId,
        sha512,
        signature,
        size: String(size),
        version,
      },
      null,
      2,
    )}\n`,
  );
}
console.log(
  `[project-update-sign] OK version=${version} arch=${arch} keyId=${publicManifest.keyId} sha512=${sha512}`,
);
