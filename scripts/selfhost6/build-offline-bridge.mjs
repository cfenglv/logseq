#!/usr/bin/env node

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import fs from "node:fs";
import { createRequire } from "node:module";
import os from "node:os";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

import { readReleasePolicy } from "../lib/selfhost6-release-identity.mjs";
import { verifyArtifactDescriptor } from "../lib/selfhost6-update-feed.mjs";
import { loadProjectSigningPolicy } from "../../resources/updater/project-update-signature.mjs";
import { unsignedBuildEnv } from "../electron-builder-unsigned.mjs";
import { getBridgeArchiveReader, readArchiveBuildManifest } from "./verify-offline-bridge.mjs";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const bridgeVersion = "2.0.1-selfhost.7";

export function readOfflineBridgeBuildInputs(env) {
  for (const name of ["BRIDGE_SOURCE_FULL_SHA", "FINAL_SOURCE_FULL_SHA"])
    assert.match(env[name] ?? "", /^[0-9a-f]{40}$/, `${name} must be a full SHA`);
  return { bridgeSourceFullSha: env.BRIDGE_SOURCE_FULL_SHA,
    finalSourceFullSha: env.FINAL_SOURCE_FULL_SHA };
}

export function bridgeBuildNames(platform, arch, finalVersion) {
  assert.ok(["darwin", "linux", "win32"].includes(platform), "native bridge platform is not implemented");
  assert.ok(["arm64", "x64"].includes(arch), "native target architecture required");
  if (platform === "win32") {
    assert.equal(arch, "x64", "the immutable .6 automatic Windows entry is x64");
    return { bridge: `Logseq-win-x64-${bridgeVersion}-nsis.exe`,
      final: `Logseq-win-x64-${finalVersion}-nsis.exe` };
  }
  const label = platform === "linux" && arch === "x64" ? "x86_64" : arch;
  const suffix = platform === "darwin" ? "zip" : "AppImage";
  return {
    bridge: `Logseq-${platform}-${label}-${bridgeVersion}.${suffix}`,
    final: `Logseq-${platform}-${label}-${finalVersion}.${suffix}`,
  };
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, { encoding: "utf8", ...options });
  assert.equal(result.status, 0, "offline bridge build command failed");
  return result.stdout;
}

export function verifyOfflineBridgeSource(bridgeRoot, bridgeSourceFullSha) {
  assert.equal(run("git", ["rev-parse", "HEAD"], { cwd: bridgeRoot }).trim(), bridgeSourceFullSha,
    "bridge checkout differs from its exact source");
  assert.equal(run("git", ["status", "--porcelain", "--", "resources", "scripts"], { cwd: bridgeRoot }), "",
    "bridge checkout must be clean");
}

export async function buildOfflineBridge({
  bridgeRoot, finalDirectory, outputDirectory, nativeStrings,
  bridgeSourceFullSha, finalSourceFullSha,
}) {
  const names = bridgeBuildNames(process.platform, process.arch, "2.0.2-selfhost.1");
  assert.equal(fs.existsSync(outputDirectory), false, "bridge output directory already exists");
  verifyOfflineBridgeSource(bridgeRoot, bridgeSourceFullSha);
  const owner = await import(pathToFileURL(
    path.join(bridgeRoot, "scripts/lib/selfhost6-release-identity.mjs")));
  const bridgePolicy = owner.readReleasePolicy(
    path.join(bridgeRoot, "resources/updater/selfhost-release-policy.json"));
  assert.equal(bridgePolicy.sourceVersion, bridgeVersion);
  const signingPolicy = loadProjectSigningPolicy();
  assert.deepEqual(loadProjectSigningPolicy(pathToFileURL(
    path.join(bridgeRoot, "resources/updater/project-signing-policy.json"))), signingPolicy);
  const finalPolicy = readReleasePolicy(
    path.join(repoRoot, "resources/updater/selfhost-release-policy.json"));
  const payload = path.join(finalDirectory, names.final);
  const descriptorPath = `${payload}.selfhost6.json`;
  const descriptor = JSON.parse(fs.readFileSync(descriptorPath));
  assert.equal(descriptor.sourceFullSha, finalSourceFullSha, "final source differs");
  assert.equal(descriptor.targetVersion, finalPolicy.sourceVersion);
  assert.equal(descriptor.platform, process.platform);
  assert.equal(descriptor.arch, process.arch);
  const metadata = verifyArtifactDescriptor({
    descriptor, artifactPath: payload, releasePolicy: finalPolicy, signingPolicy,
  });
  const reader = await getBridgeArchiveReader();
  const manifest = readArchiveBuildManifest(reader, payload);
  assert.equal(createHash("sha256").update(manifest).digest("hex"),
    metadata["target-build-manifest-sha256"], "final manifest differs");
  const staticRoot = path.join(repoRoot, "static");
  const staticRequire = createRequire(path.join(staticRoot, "package.json"));
  const builderRequire = createRequire(staticRequire.resolve("electron-builder"));
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "offline-bridge-build-"));
  let createdOutput = false;
  try {
    const manifestPath = path.join(temporary, "final-manifest.json");
    fs.writeFileSync(manifestPath, manifest);
    const app = path.join(temporary, "Logseq.app");
    const archive = path.join(temporary, names.bridge);
    const args = [
      "--payload", payload, "--descriptor", descriptorPath,
      "--target-manifest", manifestPath, "--native-strings", nativeStrings,
      "--final-version", finalPolicy.sourceVersion, "--final-source-full-sha", finalSourceFullSha,
      "--bridge-source-full-sha", bridgeSourceFullSha, "--arch", process.arch,
    ];
    const env = { ...process.env, NODE_PATH: path.join(staticRoot, "node_modules") };
    if (process.platform === "darwin") {
      run(process.execPath, [path.join(bridgeRoot, "resources/updater/build-macos-update-helper.mjs"),
        "--bridge", ...args, "--output", app], { env });
      run("/usr/bin/codesign", ["--force", "--sign", "-", "--timestamp=none", app]);
      run("/usr/bin/codesign", ["--verify", "--deep", "--strict", app]);
      const configPath = path.join(temporary, "electron-builder.json");
      fs.writeFileSync(configPath, JSON.stringify({
        appId: signingPolicy.bundleIdentity,
        extraMetadata: { version: bridgeVersion },
        directories: { output: temporary, buildResources: path.join(bridgeRoot, "resources") },
        mac: { artifactName: names.bridge, identity: "-", hardenedRuntime: false },
        publish: null,
      }));
      run(process.execPath, [staticRequire.resolve("electron-builder/cli.js"),
        "--projectDir", staticRoot, "--config", configPath, "--prepackaged", app,
        "--mac", "zip", `--${process.arch}`, "--publish", "never"], {
        env: unsignedBuildEnv(env),
      });
    } else if (process.platform === "linux") {
      const { getAppImageTools } = builderRequire("app-builder-lib/out/toolsets/linux.js");
      const { Arch } = builderRequire("builder-util");
      const tools = await getAppImageTools("1.0.2", Arch[process.arch]);
      run(process.execPath, [path.join(bridgeRoot, "resources/updater/build-linux-update-bridge.mjs"),
        ...args, "--appimage-tools", path.dirname(tools.mksquashfs), "--output", archive], { env });
    } else {
      run(process.execPath, [path.join(bridgeRoot, "resources/updater/build-windows-update-bridge.mjs"),
        ...args, "--output", archive], { env });
    }
    const bridgeManifest = readArchiveBuildManifest(reader, archive,
      process.platform === "win32" ? ["-tzip"] : []);
    const built = JSON.parse(bridgeManifest);
    assert.equal(built["target-source-full-sha"], bridgeSourceFullSha);
    assert.equal(built["target-version"], bridgeVersion);
    assert.equal(built.platform, process.platform);
    assert.equal(built.arch, process.arch);
    fs.mkdirSync(outputDirectory);
    createdOutput = true;
    fs.copyFileSync(archive, path.join(outputDirectory, names.bridge), fs.constants.COPYFILE_EXCL);
    fs.writeFileSync(path.join(outputDirectory, "TARGET_BUILD_MANIFEST.json"), bridgeManifest,
      { flag: "wx" });
    return { status: "offline-bridge-prepared-awaiting-project-signature",
      platform: process.platform, arch: process.arch };
  } catch (error) {
    if (createdOutput) fs.rmSync(outputDirectory, { recursive: true, force: true });
    throw error;
  } finally {
    fs.rmSync(temporary, { recursive: true, force: true });
  }
}

async function main() {
  const phase = process.argv[2];
  assert.ok(["inputs", "build"].includes(phase), "invalid bridge build phase");
  const sources = readOfflineBridgeBuildInputs(process.env);
  const result = phase === "inputs" ? { status: "offline-bridge-build-inputs-verified" }
    : await buildOfflineBridge({
      ...sources,
      bridgeRoot: path.join(repoRoot, "bridge-source"),
      finalDirectory: path.join(repoRoot, "final-release-assets"),
      outputDirectory: path.join(repoRoot, "bridge-build-input"),
      nativeStrings: path.join(process.env.RUNNER_TEMP, "offline-bridge-native-strings.json"),
    });
  process.stdout.write(`${JSON.stringify(result)}\n`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href)
  main().catch(() => {
    process.stderr.write("offline-bridge-build-rejected\n");
    process.exitCode = 1;
  });
