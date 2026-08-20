#!/usr/bin/env node

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const updaterRoot = path.dirname(fileURLToPath(import.meta.url));
const resourceRoot = path.resolve(updaterRoot, "..");
const sourcePath = path.join(resourceRoot, "macos-project-updater/ProjectUpdater.swift");
const policyPath = path.join(updaterRoot, "project-signing-policy.json");
const usage = "build-macos-update-helper.mjs --arch <arm64|x64> --output <path> [--test-only --public-key-base64 <key>]";

const values = new Map();
let testOnly = false;
for (let index = 2; index < process.argv.length; index += 1) {
  const key = process.argv[index];
  if (key === "--test-only") {
    testOnly = true;
    continue;
  }
  const value = process.argv[index + 1];
  assert.ok(key?.startsWith("--") && value, `${usage}; invalid argument near ${key ?? "<end>"}`);
  assert.equal(values.has(key), false, `duplicate ${key}`);
  values.set(key, value);
  index += 1;
}

if (values.size === 0 && !testOnly && process.platform !== "darwin") {
  process.stdout.write(`${JSON.stringify({ status: "skipped", platform: process.platform })}\n`);
  process.exit(0);
}
if (values.size === 0 && !testOnly) {
  values.set("--arch", process.arch);
  values.set("--output", path.join(updaterRoot, "ProjectUpdater"));
}

const required = (name) => {
  const value = values.get(name);
  assert.ok(value, `${usage}; missing ${name}`);
  return value;
};
const arch = required("--arch");
assert.ok(["arm64", "x64"].includes(arch), "--arch must be arm64 or x64");
const output = path.resolve(required("--output"));
assert.equal(values.has("--public-key-base64") && !testOnly, false, "public key override is test-only");

const policy = JSON.parse(fs.readFileSync(policyPath, "utf8"));
const publicKeyBase64 = testOnly ? required("--public-key-base64") : policy.publicKeyBase64;
const rawKey = Buffer.from(publicKeyBase64, "base64");
assert.equal(rawKey.length, 32, "public key must contain 32 raw Ed25519 bytes");
assert.equal(rawKey.toString("base64"), publicKeyBase64, "public key must use canonical base64");
const keyId = `ed25519:${createHash("sha256").update(rawKey).digest("hex")}`;
if (!testOnly) {
  assert.equal(policy.algorithm, "ed25519-selfhost-release-v1");
  assert.equal(policy.payloadDomain, "logseq-selfhost-official-architecture-update-v1");
  assert.equal(policy.keyId, keyId);
}

let source = fs.readFileSync(sourcePath, "utf8");
const replacements = new Map([
  ["__LOGSEQ_SELFHOST6_UPDATE_PUBLIC_KEY_BASE64__", publicKeyBase64],
  ["__LOGSEQ_SELFHOST6_UPDATE_KEY_ID__", keyId],
]);
for (const [marker, value] of replacements) {
  assert.equal(source.split(marker).length, 2, `Swift helper must contain exactly one ${marker}`);
  source = source.replace(marker, value);
}

const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), "selfhost6-helper-build-"));
try {
  const generatedSource = path.join(temporaryRoot, "ProjectUpdater.swift");
  const moduleCache = path.join(temporaryRoot, "module-cache");
  fs.mkdirSync(moduleCache, { mode: 0o700 });
  fs.writeFileSync(generatedSource, source, { mode: 0o600 });
  fs.mkdirSync(path.dirname(output), { recursive: true });
  const target = arch === "x64" ? "x86_64-apple-macos12.0" : "arm64-apple-macos12.0";
  const compile = spawnSync("xcrun", [
    "swiftc",
    "-O",
    "-whole-module-optimization",
    ...(testOnly ? ["-DSELFHOST6_UPDATER_TESTING"] : []),
    "-target",
    target,
    generatedSource,
    "-o",
    output,
  ], {
    encoding: "utf8",
    env: {
      ...process.env,
      CLANG_MODULE_CACHE_PATH: moduleCache,
      SWIFT_MODULE_CACHE_PATH: moduleCache,
    },
  });
  assert.equal(compile.status, 0, compile.stderr || compile.stdout || compile.error?.message);
  fs.chmodSync(output, 0o755);
  const strings = spawnSync("strings", [output], { encoding: "utf8" });
  assert.equal(strings.status, 0, strings.stderr);
  assert.ok(strings.stdout.includes(publicKeyBase64));
  assert.ok(strings.stdout.includes(keyId));
  for (const marker of replacements.keys()) assert.equal(strings.stdout.includes(marker), false);
  process.stdout.write(`${JSON.stringify({ status: "ok", arch, keyId, output })}\n`);
} finally {
  fs.rmSync(temporaryRoot, { recursive: true, force: true });
}
