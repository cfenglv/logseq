#!/usr/bin/env node

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import { createRequire } from "node:module";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { loadProjectSigningPolicy, verifySignedUpdateMetadata } from "./project-update-signature.mjs";
import { validateTargetBuildManifest } from "./target-build-manifest.mjs";
import { buildTargetManifest, readReleasePolicy } from "../../scripts/lib/selfhost6-release-identity.mjs";

const require = createRequire(import.meta.url);
const semver = require("semver");
const updaterRoot = path.dirname(fileURLToPath(import.meta.url));
const bridgePolicy = readReleasePolicy(path.join(updaterRoot, "selfhost-release-policy.json"));
const flags = new Set([
  "--payload", "--descriptor", "--target-manifest", "--native-strings",
  "--final-version", "--final-source-full-sha", "--arch", "--output",
  "--bridge-source-full-sha", "--appimage-tools",
]);
const options = new Map();
for (let index = 2; index < process.argv.length; index += 2) {
  const flag = process.argv[index], value = process.argv[index + 1];
  assert.ok(flags.has(flag) && value, `invalid build argument: ${flag}`);
  assert.equal(options.has(flag), false, `duplicate ${flag}`);
  options.set(flag, value);
}
for (const flag of flags) assert.ok(options.get(flag), `missing ${flag}`);
const arch = options.get("--arch");
assert.equal(process.platform, "linux", "compile the bridge on its Linux baseline");
assert.ok(["arm64", "x64"].includes(arch) && process.arch === arch, "native Linux target architecture required");
const payload = path.resolve(options.get("--payload"));
const descriptor = JSON.parse(fs.readFileSync(options.get("--descriptor")));
const policy = loadProjectSigningPolicy();
const metadata = verifySignedUpdateMetadata({ signedMetadata: descriptor.signedMetadata, policy });
assert.equal(metadata.platform, "linux");
assert.equal(metadata.arch, arch);
assert.equal(metadata["target-version"], options.get("--final-version"));
assert.equal(metadata["target-source-full-sha"], options.get("--final-source-full-sha"));
assert.equal(semver.gt(metadata["target-version"], bridgePolicy.sourceVersion), true, "final version must be newer than the bridge");
const label = arch === "x64" ? "x86_64" : "arm64";
const name = `Logseq-linux-${label}-${metadata["target-version"]}.AppImage`;
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
const manifestBytes = fs.readFileSync(options.get("--target-manifest"));
assert.equal(createHash("sha256").update(manifestBytes).digest("hex"), metadata["target-build-manifest-sha256"]);
const manifest = validateTargetBuildManifest({
  manifest: JSON.parse(manifestBytes),
  archiveDigestVerified: true,
  expected: {
    targetSourceFullSha: metadata["target-source-full-sha"], targetVersion: metadata["target-version"],
    releaseLineId: policy.releaseLineId, platform: "linux", arch,
    bundleIdentity: policy.bundleIdentity, signingKeyIdentity: policy.keyId,
  },
  currentFormats: { activation: bridgePolicy.activationWriteFormat, clientOps: bridgePolicy.clientOpsWriteFormat },
});
for (const key of ["readable-activation-formats", "readable-client-ops-formats", "activation-write-format", "client-ops-write-format"])
  assert.deepEqual(manifest[key], metadata[key], `manifest format differs from signed ${key}`);
const bridgeManifest = buildTargetManifest({
  policy: bridgePolicy, sourceFullSha: options.get("--bridge-source-full-sha"),
  targetVersion: bridgePolicy.sourceVersion, platform: "linux", arch,
  signingKeyIdentity: policy.keyId,
});
for (const key of ["readable-activation-formats", "readable-client-ops-formats", "activation-write-format", "client-ops-write-format"])
  assert.deepEqual(bridgeManifest[key], manifest[key], `bridge capability differs from its fixed final ${key}`);
const bridgeManifestBytes = Buffer.from(`${JSON.stringify(bridgeManifest, null, 2)}\n`);
const strings = JSON.parse(fs.readFileSync(options.get("--native-strings")));
const headers = [
  `#define BRIDGE_PAYLOAD_NAME ${JSON.stringify(name)}`,
  `#define BRIDGE_PAYLOAD_SIZE ${metadata["archive-size"]}ULL`,
  `#define BRIDGE_PAYLOAD_SHA256 ${JSON.stringify(metadata["archive-sha256"])}`,
];
const messages = [];
for (const [locale, suffix] of [["en", "EN"], ["zh-CN", "ZH_CN"]]) {
  for (const [field, macro] of [["title", "TITLE"], ["description", "DESC"]]) {
    const text = strings[locale]?.[field];
    assert.ok(typeof text === "string" && text.length > 0, `missing native update ${locale}/${field}`);
    messages.push(`#define BRIDGE_ERROR_${macro}_${suffix} ${JSON.stringify(text)}`);
  }
}
const output = path.resolve(options.get("--output"));
assert.equal(path.basename(output), `Logseq-linux-${label}-${bridgePolicy.sourceVersion}.AppImage`);
assert.equal(fs.existsSync(output), false, "bridge output already exists");
const tools = path.resolve(options.get("--appimage-tools"));
const runtime = fs.readFileSync(path.join(tools, "runtimes", `runtime-${arch}`));
assert.equal(runtime.subarray(0, 4).toString("hex"), "7f454c46");
assert.equal(runtime.subarray(8, 11).toString("hex"), "414902", "type-2 AppImage runtime required");
assert.equal(runtime.readUInt16LE(18), arch === "arm64" ? 183 : 62, "AppImage runtime architecture differs");
const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "linux-update-bridge-build-"));
let createdOutput = false;
try {
  fs.writeFileSync(path.join(temporary, "bridge_payload.h"), `${headers.join("\n")}\n`);
  fs.writeFileSync(path.join(temporary, "native_update_strings.h"), `${messages.join("\n")}\n`);
  const pkg = spawnSync("pkg-config", ["--cflags", "--libs", "glib-2.0", "gtk+-3.0"], { encoding: "utf8" });
  assert.equal(pkg.status, 0, pkg.stderr || pkg.error?.message);
  const appDirectory = path.join(temporary, "bridge.AppDir");
  fs.mkdirSync(appDirectory);
  const executable = path.join(appDirectory, "AppRun");
  const compile = spawnSync("cc", [
    "-std=gnu11", "-O2", "-Wall", "-Wextra", "-Werror", "-I", temporary,
    path.join(updaterRoot, "../linux-project-updater/bridge.c"), "-o", executable,
    ...pkg.stdout.trim().split(/\s+/),
  ], { encoding: "utf8" });
  assert.equal(compile.status, 0, compile.stderr || compile.error?.message);
  const elf = fs.readFileSync(executable);
  assert.equal(elf.subarray(0, 4).toString("hex"), "7f454c46");
  assert.equal(elf[4], 2, "bridge must be ELF64");
  assert.equal(elf.readUInt16LE(18), arch === "arm64" ? 183 : 62, "compiled bridge architecture differs");
  fs.chmodSync(executable, 0o755);
  const updaterResources = path.join(appDirectory, "bridge/resources/updater");
  const payloadDirectory = path.join(appDirectory, "bridge/payload");
  fs.mkdirSync(updaterResources, { recursive: true });
  fs.mkdirSync(payloadDirectory, { recursive: true });
  fs.writeFileSync(path.join(updaterResources, "TARGET_BUILD_MANIFEST.json"), bridgeManifestBytes);
  fs.copyFileSync(payload, path.join(payloadDirectory, name));
  fs.copyFileSync(options.get("--descriptor"), path.join(payloadDirectory, `${name}.selfhost6.json`));
  // SquashFS is root-owned. Do not inherit private build umasks or payload source modes.
  for (const directory of ["", "bridge", "bridge/payload", "bridge/resources", "bridge/resources/updater"])
    fs.chmodSync(path.join(appDirectory, directory), 0o755);
  for (const resource of [path.join(updaterResources, "TARGET_BUILD_MANIFEST.json"),
    path.join(payloadDirectory, name), path.join(payloadDirectory, `${name}.selfhost6.json`)])
    fs.chmodSync(resource, 0o644);
  const squash = path.join(temporary, "bridge.squashfs");
  const pack = spawnSync(path.join(tools, "mksquashfs"), [
    appDirectory, squash, "-root-owned", "-comp", "gzip", "-noappend",
    "-b", "131072", "-processors", "2", "-no-progress",
  ], { encoding: "utf8" });
  assert.equal(pack.status, 0, pack.stderr || pack.error?.message);
  fs.mkdirSync(path.dirname(output), { recursive: true });
  const filesystem = fs.openSync(squash, "r");
  let archive;
  try {
    archive = fs.openSync(output, "wx", 0o755);
    createdOutput = true;
    fs.writeFileSync(archive, runtime);
    const chunk = Buffer.alloc(65536);
    for (let size; (size = fs.readSync(filesystem, chunk)) > 0;) {
      for (let offset = 0; offset < size;)
        offset += fs.writeSync(archive, chunk, offset, size - offset);
    }
    fs.fchmodSync(archive, 0o755);
  } finally {
    fs.closeSync(filesystem);
    if (archive !== undefined) fs.closeSync(archive);
  }
  process.stdout.write(`${JSON.stringify({ status: "packaged", arch, payloadSha256: metadata["archive-sha256"] })}\n`);
} catch (error) {
  if (createdOutput) fs.unlinkSync(output);
  throw error;
} finally {
  fs.rmSync(temporary, { recursive: true, force: true });
}
