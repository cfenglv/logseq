#!/usr/bin/env node

import { createPrivateKey } from "node:crypto";
import path from "node:path";
import { loadProjectSigningPolicy } from "../../resources/project-updater-signature.mjs";
import { signMacosProjectUpdate } from "../sign-macos-project-update.mjs";

if (
  process.env.NODE_ENV !== "test" ||
  process.argv[2] !== "--test-only"
) {
  throw new Error(
    "project update signer fixture requires explicit test-only mode",
  );
}

const values = new Map();
for (let index = 3; index < process.argv.length; index += 2) {
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
const encodedKey =
  process.env.LOGSEQ_PROJECT_UPDATE_SIGNER_TEST_ONLY_KEY_BASE64;
if (!encodedKey) {
  throw new Error("test-only project update signer key is missing");
}
const privateKey = createPrivateKey({
  key: Buffer.from(encodedKey, "base64"),
  format: "der",
  type: "pkcs8",
});
const policy = loadProjectSigningPolicy();
await signMacosProjectUpdate({
  arch: required("--arch"),
  archive: path.resolve(required("--archive")),
  metadata: path.resolve(required("--metadata")),
  policy,
  privateKey,
  signatureOutput: values.get("--signature-output")
    ? path.resolve(values.get("--signature-output"))
    : undefined,
  version: required("--version"),
});
