#!/usr/bin/env node

import assert from "node:assert/strict";
import { createPrivateKey } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { readReleasePolicy } from "../lib/selfhost6-release-identity.mjs";
import {
  prepareSignedArtifact,
  stageSignedArtifact,
} from "../lib/selfhost6-update-feed.mjs";
import {
  loadProjectSigningPolicy,
} from "../../resources/updater/project-update-signature.mjs";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");

function parseArgs(argv) {
  const result = {};
  for (let index = 0; index < argv.length; index += 2) {
    const flag = argv[index];
    const value = argv[index + 1];
    assert.ok(value, `${flag} requires a value`);
    if (flag === "--archive") result.archivePath = path.resolve(value);
    else if (flag === "--target-manifest") result.targetManifestPath = path.resolve(value);
    else if (flag === "--source-full-sha") result.sourceFullSha = value;
    else if (flag === "--target-version") result.targetVersion = value;
    else if (flag === "--platform") result.platform = value;
    else if (flag === "--arch") result.arch = value;
    else if (flag === "--output-directory") result.outputDirectory = path.resolve(value);
    else throw new Error(`unknown argument: ${flag}`);
  }
  for (const field of [
    "archivePath",
    "targetManifestPath",
    "sourceFullSha",
    "targetVersion",
    "platform",
    "arch",
    "outputDirectory",
  ]) assert.ok(result[field], `${field} is required`);
  return result;
}

const privateKeyPath = process.env.SELFHOST6_UPDATE_SIGNING_PRIVATE_KEY_FILE;
const privateKeyBase64 = process.env.LOGSEQ_PROJECT_UPDATE_SIGNING_KEY_PKCS8_BASE64;
assert.notEqual(Boolean(privateKeyPath), Boolean(privateKeyBase64),
  "provide exactly one managed update private-key source");
let privateKeyPem;
if (privateKeyPath) {
  const privateKeyRealPath = fs.realpathSync(privateKeyPath);
  assert.equal(
    privateKeyRealPath.startsWith(`${repoRoot}${path.sep}`),
    false,
    "the managed update private key must remain outside the repository",
  );
  privateKeyPem = fs.readFileSync(privateKeyRealPath, "utf8");
} else {
  const keyBytes = Buffer.from(privateKeyBase64, "base64");
  assert.equal(keyBytes.toString("base64"), privateKeyBase64,
    "managed PKCS#8 key must use canonical base64");
  privateKeyPem = createPrivateKey({ key: keyBytes, format: "der", type: "pkcs8" })
    .export({ format: "pem", type: "pkcs8" });
}
const options = parseArgs(process.argv.slice(2));
const releasePolicy = readReleasePolicy(
  path.join(repoRoot, "resources/updater/selfhost-release-policy.json"),
);
const signingPolicy = loadProjectSigningPolicy();
const descriptor = prepareSignedArtifact({
  ...options,
  releasePolicy,
  signingPolicy,
  privateKeyPem,
});
const staged = stageSignedArtifact({
  descriptor,
  archivePath: options.archivePath,
  outputDirectory: options.outputDirectory,
});
process.stdout.write(`${JSON.stringify({ status: "prepared", ...staged })}\n`);
