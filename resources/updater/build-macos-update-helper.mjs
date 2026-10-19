#!/usr/bin/env node

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import { createRequire } from "node:module";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const updaterRoot = path.dirname(fileURLToPath(import.meta.url));
const resourceRoot = path.resolve(updaterRoot, "..");
const sourcePath = path.join(resourceRoot, "macos-project-updater/ProjectUpdater.swift");
const policyPath = path.join(updaterRoot, "project-signing-policy.json");
const usage = "build-macos-update-helper.mjs --arch <arm64|x64> --output <path> [--bridge --payload <zip> --descriptor <json> --target-manifest <json> --native-strings <json> --final-version <version> --final-source-full-sha <sha> --bridge-source-full-sha <sha>] [--test-only --public-key-base64 <key>]";

const values = new Map();
let testOnly = false;
let isBridge = false;
for (let index = 2; index < process.argv.length; index += 1) {
  const key = process.argv[index];
  if (key === "--bridge") {
    assert.equal(isBridge, false, "duplicate --bridge");
    isBridge = true;
    continue;
  }
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

if (values.size === 0 && !testOnly && !isBridge && process.platform !== "darwin") {
  process.stdout.write(`${JSON.stringify({ status: "skipped", platform: process.platform })}\n`);
  process.exit(0);
}
if (values.size === 0 && !testOnly && !isBridge) {
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

const policy = isBridge
  ? (await import("./project-update-signature.mjs")).loadProjectSigningPolicy()
  : JSON.parse(fs.readFileSync(policyPath, "utf8"));
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
let bridgeInputs;
if (isBridge) {
  assert.equal(process.platform, "darwin", "compile the bridge on macOS");
  assert.equal(path.basename(output), "Logseq.app");
  assert.equal(fs.existsSync(output), false, "bridge App output already exists");
  const { verifySignedUpdateMetadata } = await import("./project-update-signature.mjs");
  const { validateTargetBuildManifest } = await import("./target-build-manifest.mjs");
  const { readReleasePolicy, buildTargetManifest } = await import("../../scripts/lib/selfhost6-release-identity.mjs");
  const semver = createRequire(import.meta.url)("semver");
  const bridgePolicy = readReleasePolicy(path.join(updaterRoot, "selfhost-release-policy.json"));
  const signingPolicy = { ...policy, publicKeyBase64, keyId };
  const descriptor = required("--descriptor");
  const signed = JSON.parse(fs.readFileSync(descriptor));
  const metadata = verifySignedUpdateMetadata({ signedMetadata: signed.signedMetadata, policy: signingPolicy });
  assert.equal(metadata.platform, "darwin");
  assert.equal(metadata.arch, arch);
  assert.equal(metadata["target-version"], required("--final-version"));
  assert.equal(metadata["target-source-full-sha"], required("--final-source-full-sha"));
  assert.equal(semver.gt(metadata["target-version"], bridgePolicy.sourceVersion), true, "final version must be newer than the bridge");
  const name = `Logseq-darwin-${arch}-${metadata["target-version"]}.zip`;
  const payload = path.resolve(required("--payload"));
  assert.equal(path.basename(payload), name);
  assert.equal(path.posix.basename(metadata["immutable-object-key"]), name);
  assert.equal(fs.statSync(payload).size, metadata["archive-size"]);
  const hashes = [createHash("sha256"), createHash("sha512")];
  const input = fs.openSync(payload, "r");
  try {
    const chunk = Buffer.alloc(65536);
    for (let size; (size = fs.readSync(input, chunk)) > 0;)
      for (const hash of hashes) hash.update(chunk.subarray(0, size));
  } finally {
    fs.closeSync(input);
  }
  assert.equal(hashes[0].digest("hex"), metadata["archive-sha256"]);
  assert.equal(hashes[1].digest("hex"), metadata["archive-sha512"]);
  const manifestBytes = fs.readFileSync(required("--target-manifest"));
  assert.equal(createHash("sha256").update(manifestBytes).digest("hex"), metadata["target-build-manifest-sha256"]);
  const manifest = validateTargetBuildManifest({
    manifest: JSON.parse(manifestBytes), archiveDigestVerified: true,
    expected: {
      targetSourceFullSha: metadata["target-source-full-sha"], targetVersion: metadata["target-version"],
      releaseLineId: policy.releaseLineId, platform: "darwin", arch,
      bundleIdentity: policy.bundleIdentity, signingKeyIdentity: keyId,
    },
    currentFormats: { activation: bridgePolicy.activationWriteFormat, clientOps: bridgePolicy.clientOpsWriteFormat },
  });
  const bridgeManifest = buildTargetManifest({
    policy: bridgePolicy, sourceFullSha: required("--bridge-source-full-sha"),
    targetVersion: bridgePolicy.sourceVersion, platform: "darwin", arch, signingKeyIdentity: keyId,
  });
  for (const field of ["readable-activation-formats", "readable-client-ops-formats", "activation-write-format", "client-ops-write-format"]) {
    assert.deepEqual(manifest[field], metadata[field], `manifest differs from signed ${field}`);
    assert.deepEqual(bridgeManifest[field], manifest[field], `bridge differs from fixed final ${field}`);
  }
  const bridgeBytes = Buffer.from(`${JSON.stringify(bridgeManifest, null, 2)}\n`);
  for (const [marker, text] of [
    ["PAYLOAD_NAME", name], ["FINAL_VERSION", metadata["target-version"]],
    ["FINAL_SOURCE", metadata["target-source-full-sha"]],
    ["PAYLOAD_SHA256", metadata["archive-sha256"]],
    ["MANIFEST_SHA256", createHash("sha256").update(bridgeBytes).digest("hex")],
  ]) replacements.set(`__LOGSEQ_BRIDGE_${marker}__`, JSON.stringify(text).slice(1, -1));
  replacements.set("__LOGSEQ_BRIDGE_PAYLOAD_SIZE__", String(metadata["archive-size"]));
  const nativeText = JSON.parse(fs.readFileSync(required("--native-strings")));
  for (const [locale, suffix] of [["en", "EN"], ["zh-CN", "ZH"]]) {
    for (const [field, marker] of [["title", "TITLE"], ["description", "DESC"]]) {
      const text = nativeText[locale]?.[field];
      assert.ok(typeof text === "string" && text.length > 0, `missing native update ${locale}/${field}`);
      replacements.set(`__LOGSEQ_BRIDGE_ERROR_${marker}_${suffix}__`, JSON.stringify(text).slice(1, -1));
    }
  }
  bridgeInputs = { payload, descriptor, name, manifest: bridgeBytes, version: bridgePolicy.sourceVersion };
}
for (const [marker, value] of replacements) {
  assert.equal(source.split(marker).length, 2, `Swift helper must contain exactly one ${marker}`);
  source = source.replace(marker, value);
}

const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), "selfhost6-helper-build-"));
let createdApp = false;
try {
  const generatedSource = path.join(temporaryRoot, "ProjectUpdater.swift");
  const moduleCache = path.join(temporaryRoot, "module-cache");
  fs.mkdirSync(moduleCache, { mode: 0o700 });
  fs.writeFileSync(generatedSource, source, { mode: 0o600 });
  const target = arch === "x64" ? "x86_64-apple-macos12.0" : "arm64-apple-macos12.0";
  fs.mkdirSync(path.dirname(output), { recursive: true });
  let executable = output;
  const bridgeCompileArguments = [];
  if (isBridge) {
    fs.mkdirSync(output);
    createdApp = true;
    for (const directory of ["Contents", "Contents/MacOS", "Contents/Resources", "Contents/Resources/updater", "Contents/Resources/payload"])
      fs.mkdirSync(path.join(output, directory));
    executable = path.join(output, "Contents/MacOS/LogseqBridge");
    const handoff = path.join(resourceRoot, "macos-project-updater/BridgeHandoff");
    const object = path.join(temporaryRoot, "BridgeHandoff.o");
    const native = spawnSync("xcrun", ["clang", "-target", target, "-O2", "-Wall", "-Wextra", "-Werror", "-c", `${handoff}.c`, "-o", object], { encoding: "utf8" });
    assert.equal(native.status, 0, native.stderr || native.error?.message);
    bridgeCompileArguments.push("-DSELFHOST6_OFFLINE_BRIDGE", "-import-objc-header", `${handoff}.h`, object);
  }
  const compile = spawnSync("xcrun", [
    "swiftc",
    "-O",
    "-whole-module-optimization",
    ...(testOnly ? ["-DSELFHOST6_UPDATER_TESTING"] : []),
    ...bridgeCompileArguments,
    "-target",
    target,
    generatedSource,
    "-o",
    executable,
  ], {
    encoding: "utf8",
    env: {
      ...process.env,
      CLANG_MODULE_CACHE_PATH: moduleCache,
      SWIFT_MODULE_CACHE_PATH: moduleCache,
    },
  });
  assert.equal(compile.status, 0, compile.stderr || compile.stdout || compile.error?.message);
  fs.chmodSync(executable, 0o755);
  const strings = spawnSync("strings", [executable], { encoding: "utf8" });
  assert.equal(strings.status, 0, strings.stderr);
  assert.ok(strings.stdout.includes(publicKeyBase64));
  assert.ok(strings.stdout.includes(keyId));
  for (const marker of replacements.keys()) assert.equal(strings.stdout.includes(marker), false);
  if (isBridge) {
    const resources = path.join(output, "Contents/Resources");
    const info = {
      CFBundleExecutable: "LogseqBridge", CFBundleIdentifier: policy.bundleIdentity,
      CFBundleName: "Logseq", CFBundleDisplayName: "Logseq", CFBundlePackageType: "APPL",
      CFBundleShortVersionString: bridgeInputs.version, CFBundleVersion: bridgeInputs.version,
      CFBundleIconFile: "icon.icns", LSMinimumSystemVersion: "12.0", LSUIElement: true,
    };
    const plist = spawnSync("/usr/bin/plutil", ["-convert", "xml1", "-o", path.join(output, "Contents/Info.plist"), "--", "-"], {
      input: JSON.stringify(info), encoding: "utf8",
    });
    assert.equal(plist.status, 0, plist.stderr || plist.stdout);
    fs.writeFileSync(path.join(resources, "updater/TARGET_BUILD_MANIFEST.json"), bridgeInputs.manifest);
    fs.copyFileSync(bridgeInputs.payload, path.join(resources, "payload", bridgeInputs.name));
    fs.copyFileSync(bridgeInputs.descriptor, path.join(resources, "payload", `${bridgeInputs.name}.selfhost6.json`));
    fs.copyFileSync(path.join(resourceRoot, "icons/logseq.icns"), path.join(resources, "icon.icns"));
    for (const directory of ["", "Contents", "Contents/MacOS", "Contents/Resources", "Contents/Resources/updater", "Contents/Resources/payload"])
      fs.chmodSync(path.join(output, directory), 0o755);
    for (const file of ["Contents/Info.plist", "Contents/Resources/icon.icns", "Contents/Resources/updater/TARGET_BUILD_MANIFEST.json",
      `Contents/Resources/payload/${bridgeInputs.name}`, `Contents/Resources/payload/${bridgeInputs.name}.selfhost6.json`])
      fs.chmodSync(path.join(output, file), 0o644);
  }
  process.stdout.write(`${JSON.stringify(isBridge ? { status: "bridge-app-prepared-unsigned", arch } : { status: "ok", arch, keyId, output })}\n`);
} catch (error) {
  if (createdApp) fs.rmSync(output, { recursive: true, force: true });
  throw error;
} finally {
  fs.rmSync(temporaryRoot, { recursive: true, force: true });
}
