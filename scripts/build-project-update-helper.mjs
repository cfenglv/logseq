#!/usr/bin/env node

import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const sourcePath = path.join(
  repoRoot,
  "resources",
  "macos-project-updater",
  "ProjectUpdater.swift",
);
const manifestPath = path.join(
  repoRoot,
  "resources",
  "updater",
  "project-signing-policy.json",
);

const usage = `Usage:
  node scripts/build-project-update-helper.mjs --arch <arm64|x64> --output <path>

Production builds always embed the key from resources/updater/project-signing-policy.json.

TEST-ONLY:
  node scripts/build-project-update-helper.mjs --test-only --public-key-base64 <raw-ed25519-base64> --arch <arm64|x64> --output <path>

--public-key-base64 is forbidden unless --test-only is also present.
`;
if (process.argv.length === 3 && process.argv[2] === "--help") {
  process.stdout.write(usage);
  process.exit(0);
}

const values = new Map();
let testOnly = false;
for (let index = 2; index < process.argv.length; index += 1) {
  const key = process.argv[index];
  if (key === "--test-only") {
    testOnly = true;
    continue;
  }
  const value = process.argv[index + 1];
  if (!key?.startsWith("--") || !value) {
    throw new Error(`invalid argument near ${key || "<end>"}`);
  }
  if (values.has(key)) throw new Error(`duplicate ${key}`);
  values.set(key, value);
  index += 1;
}
const required = (key) => {
  const value = values.get(key);
  if (!value) throw new Error(`missing ${key}`);
  return value;
};
const output = path.resolve(required("--output"));
const arch = required("--arch");
if (!["arm64", "x64"].includes(arch)) {
  throw new Error("--arch must be arm64 or x64");
}
if (values.has("--public-key-base64") && !testOnly) {
  throw new Error("runtime/build-time public key override is forbidden for production");
}

const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
const publicKeyBase64 = testOnly
  ? required("--public-key-base64")
  : manifest.publicKeyBase64;
const publicKey = Buffer.from(publicKeyBase64, "base64");
if (
  manifest.algorithm !== "ed25519-sha512-manifest-v1" ||
  publicKey.length !== 32 ||
  publicKey.toString("base64") !== publicKeyBase64
) {
  throw new Error(
    "project update public key is UNCONFIGURED or invalid; production build is fail-closed",
  );
}
const expectedKeyId = `ed25519:${createHash("sha256")
  .update(publicKey)
  .digest("hex")
  .slice(0, 16)}`;
if (!testOnly && manifest.keyId !== expectedKeyId) {
  throw new Error(
    `project update keyId ${manifest.keyId} != derived ${expectedKeyId}`,
  );
}

const source = fs.readFileSync(sourcePath, "utf8");
const marker = "__LOGSEQ_PROJECT_UPDATE_PUBLIC_KEY_BASE64__";
if (source.split(marker).length !== 2) {
  throw new Error("Swift helper must contain exactly one public-key marker");
}
const temporaryRoot = fs.mkdtempSync(
  path.join(os.tmpdir(), "logseq-project-updater-build-"),
);
try {
  const generatedSource = path.join(temporaryRoot, "ProjectUpdater.swift");
  fs.writeFileSync(
    generatedSource,
    source.replace(marker, publicKeyBase64),
    { mode: 0o600 },
  );
  fs.mkdirSync(path.dirname(output), { recursive: true });
  const target = arch === "x64"
    ? "x86_64-apple-macos12.0"
    : "arm64-apple-macos12.0";
  const moduleCache = path.join(temporaryRoot, "module-cache");
  fs.mkdirSync(moduleCache, { mode: 0o700 });
  const result = spawnSync(
    "xcrun",
    [
      "swiftc",
      "-O",
      "-whole-module-optimization",
      ...(testOnly ? ["-DPROJECT_UPDATER_TESTING"] : []),
      "-target",
      target,
      generatedSource,
      "-o",
      output,
    ],
    {
      encoding: "utf8",
      env: {
        ...process.env,
        CLANG_MODULE_CACHE_PATH: moduleCache,
        SWIFT_MODULE_CACHE_PATH: moduleCache,
      },
      stdio: ["ignore", "pipe", "pipe"],
    },
  );
  if (result.error || result.status !== 0) {
    throw new Error(
      `swiftc failed: ${result.stderr || result.stdout || result.error?.message}`,
    );
  }
  fs.chmodSync(output, 0o755);
  const strings = spawnSync("strings", [output], { encoding: "utf8" });
  if (
    strings.status !== 0 ||
    !strings.stdout.includes(publicKeyBase64) ||
    strings.stdout.includes(marker)
  ) {
    throw new Error("compiled helper does not contain exactly the configured public key");
  }
  console.log(
    `[project-updater-build] OK arch=${arch} keyId=${expectedKeyId} output=${output}`,
  );
} finally {
  fs.rmSync(temporaryRoot, { recursive: true, force: true });
}
