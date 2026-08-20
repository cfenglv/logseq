#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { createHash } from "node:crypto";
import { createRequire } from "node:module";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import {
  assertMatchesStagedResource,
  assertRegularFile,
  verifyProjectSignatureRuntime,
} from "./packaged-resource-contract.mjs";

const require = createRequire(import.meta.url);
const asar = require("@electron/asar");
const stagedResourcesDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(stagedResourcesDir, "..");

const parseArgs = (argv) => {
  if (argv[0] === "--") argv = argv.slice(1);
  const result = {};
  for (let index = 0; index < argv.length; index += 2) {
    const key = argv[index];
    const value = argv[index + 1];
    if (!key?.startsWith("--") || !value) {
      throw new Error(`invalid argument pair: ${key ?? ""} ${value ?? ""}`);
    }
    result[key.slice(2)] = value;
  }
  return result;
};

const args = parseArgs(process.argv.slice(2));
const searchRoot = path.resolve(args["search-root"] || "dist");
const expectedPlatform = args.platform;
const expectedArch = args.arch;
const expectedVersion = args.version;
const expectedElectron =
  args["electron-version"] || require("./package.json").devDependencies.electron;
const stagedRevisionPath = path.join(stagedResourcesDir, "RUNTIME_REVISION");
const stagedRevision = fs.existsSync(stagedRevisionPath)
  ? fs.readFileSync(stagedRevisionPath, "utf8").trim()
  : "";
const expectedRevision =
  process.env.LOGSEQ_REVISION?.trim() ||
  stagedRevision ||
  spawnSync("git", ["describe", "--long", "--always", "--dirty"], {
    cwd: repoRoot,
    encoding: "utf8",
    shell: false,
  }).stdout?.trim();

if (!["darwin", "linux", "win32"].includes(expectedPlatform)) {
  throw new Error(`unsupported --platform: ${expectedPlatform}`);
}
if (!["x64", "arm64"].includes(expectedArch)) {
  throw new Error(`unsupported --arch: ${expectedArch}`);
}
if (!expectedVersion) {
  throw new Error("--version is required");
}
if (!expectedRevision) {
  throw new Error(
    "could not determine the expected desktop runtime revision",
  );
}

const findFiles = (directory, fileName, results = []) => {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const entryPath = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      findFiles(entryPath, fileName, results);
    } else if (entry.name === fileName) {
      results.push(entryPath);
    }
  }
  return results;
};

const appAsars = findFiles(searchRoot, "app.asar").filter(
  (filePath) => !filePath.includes("app.asar.unpacked"),
);
if (appAsars.length !== 1) {
  throw new Error(
    `expected exactly one packaged app.asar under ${searchRoot}, found ${appAsars.length}`,
  );
}

const appAsar = appAsars[0];
const resourcesDir = path.dirname(appAsar);
const appRoot =
  expectedPlatform === "darwin"
    ? path.resolve(resourcesDir, "..", "..")
    : path.resolve(resourcesDir, "..");
const mainExecutable =
  expectedPlatform === "darwin"
    ? path.join(appRoot, "Contents", "MacOS", "Logseq")
    : expectedPlatform === "win32"
      ? path.join(appRoot, "Logseq.exe")
      : path.join(appRoot, "logseq");
const keytar = path.join(
  resourcesDir,
  "app.asar.unpacked",
  "node_modules",
  "keytar",
  "build",
  "Release",
  "keytar.node",
);
const appUpdateConfig = path.join(resourcesDir, "app-update.yml");
const embeddingServer = path.join(resourcesDir, "sidecar", "embedding_server.py");

for (const filePath of [mainExecutable, keytar, appUpdateConfig]) {
  if (!fs.existsSync(filePath)) {
    throw new Error(`missing packaged native runtime: ${filePath}`);
  }
}

assertMatchesStagedResource(
  embeddingServer,
  path.join(stagedResourcesDir, "sidecar", "embedding_server.py"),
  "embedding sidecar",
);
verifyProjectSignatureRuntime({
  arch: expectedArch,
  platform: expectedPlatform,
  resourcesDir,
  stagedResourcesDir,
});

const appUpdateText = fs.readFileSync(appUpdateConfig, "utf8");
for (const [label, pattern] of [
  ["provider", /^provider:\s*github\s*$/m],
  ["owner", /^owner:\s*cfenglv\s*$/m],
  ["repo", /^repo:\s*logseq\s*$/m],
]) {
  if (!pattern.test(appUpdateText)) {
    throw new Error(
      `packaged updater ${label} does not target cfenglv/logseq: ${appUpdateConfig}`,
    );
  }
}

const detectArchitecture = (filePath) => {
  const payload = fs.readFileSync(filePath);

  if (payload[0] === 0x4d && payload[1] === 0x5a) {
    const peOffset = payload.readUInt32LE(0x3c);
    const machine = payload.readUInt16LE(peOffset + 4);
    if (machine === 0x8664) return { platform: "win32", arch: "x64" };
    if (machine === 0xaa64) return { platform: "win32", arch: "arm64" };
  }

  if (
    payload[0] === 0x7f &&
    payload[1] === 0x45 &&
    payload[2] === 0x4c &&
    payload[3] === 0x46
  ) {
    const littleEndian = payload[5] === 1;
    const machine = littleEndian
      ? payload.readUInt16LE(18)
      : payload.readUInt16BE(18);
    if (machine === 62) return { platform: "linux", arch: "x64" };
    if (machine === 183) return { platform: "linux", arch: "arm64" };
  }

  const magic = payload.readUInt32BE(0);
  if (magic === 0xfeedfacf || magic === 0xcffaedfe) {
    const littleEndian = magic === 0xcffaedfe;
    const cpuType = littleEndian
      ? payload.readUInt32LE(4)
      : payload.readUInt32BE(4);
    if (cpuType === 0x01000007) return { platform: "darwin", arch: "x64" };
    if (cpuType === 0x0100000c) return { platform: "darwin", arch: "arm64" };
  }

  throw new Error(`unsupported native executable format: ${filePath}`);
};

for (const filePath of [mainExecutable, keytar]) {
  const actual = detectArchitecture(filePath);
  if (
    actual.platform !== expectedPlatform ||
    actual.arch !== expectedArch
  ) {
    throw new Error(
      `${filePath} is ${actual.platform}/${actual.arch}, expected ${expectedPlatform}/${expectedArch}`,
    );
  }
}

if (expectedPlatform === "darwin") {
  const helper = path.join(resourcesDir, "sidecar", "logseq-project-updater");
  const helperStats = assertRegularFile(helper, "project updater helper");
  if ((helperStats.mode & 0o111) === 0) {
    throw new Error(`packaged project updater helper is not executable: ${helper}`);
  }
  const helperArchitecture = detectArchitecture(helper);
  if (helperArchitecture.platform !== "darwin" || helperArchitecture.arch !== expectedArch) {
    throw new Error(
      `${helper} is ${helperArchitecture.platform}/${helperArchitecture.arch}, expected darwin/${expectedArch}`,
    );
  }

  const projectSigningPolicy = path.join(resourcesDir, "updater", "project-signing-policy.json");
  assertMatchesStagedResource(
    projectSigningPolicy,
    path.join(stagedResourcesDir, "updater", "project-signing-policy.json"),
    "project updater signing policy",
  );

  const policy = JSON.parse(fs.readFileSync(projectSigningPolicy, "utf8"));
  const rawPublicKey = Buffer.from(policy.publicKeyBase64 || "", "base64");
  const configuredKey =
    rawPublicKey.length === 32 && rawPublicKey.toString("base64") === policy.publicKeyBase64;
  const configuredKeyId = configuredKey
    ? `ed25519:${createHash("sha256").update(rawPublicKey).digest("hex")}`
    : undefined;
  const explicitlyUnconfigured =
    policy.publicKeyBase64 === "UNCONFIGURED" && policy.keyId === "UNCONFIGURED";
  if (
    policy.algorithm !== "ed25519-sha512-manifest-v1" ||
    policy.bundleIdentifier !== "com.logseq.logseq" ||
    policy.payloadDomain !== "logseq-selfhost-macos-update-v1" ||
    policy.minimumBootstrapRevision !== 5 ||
    (!explicitlyUnconfigured && configuredKeyId !== policy.keyId)
  ) {
    throw new Error(
      `packaged project updater signing policy is malformed: ${projectSigningPolicy}`,
    );
  }
}

const packageJson = JSON.parse(asar.extractFile(appAsar, "package.json").toString());
if (packageJson.version !== expectedVersion) {
  throw new Error(
    `packaged app version ${packageJson.version} does not match ${expectedVersion}`,
  );
}
if (packageJson.main !== "electron.js") {
  throw new Error(`packaged app main is ${packageJson.main}, expected electron.js`);
}

for (const relativePath of [
  "js/logseq-cli.js",
  "js/db-worker-node.js",
]) {
  const packagedPayload = asar.extractFile(appAsar, relativePath);
  const stagedPath = path.join(stagedResourcesDir, relativePath);
  const stagedPayload = fs.readFileSync(stagedPath);
  if (!packagedPayload.equals(stagedPayload)) {
    throw new Error(
      `packaged ${relativePath} does not exactly match staged runtime ${stagedPath}`,
    );
  }
  if (!packagedPayload.includes(expectedRevision)) {
    throw new Error(
      `packaged ${relativePath} does not contain current revision ${expectedRevision}`,
    );
  }
}

if (expectedPlatform === process.platform && expectedArch === process.arch) {
  const result = spawnSync(
    mainExecutable,
    ["-e", "process.stdout.write(process.versions.electron)"],
    {
      env: { ...process.env, ELECTRON_RUN_AS_NODE: "1" },
      encoding: "utf8",
      timeout: 30_000,
    },
  );
  if (result.status !== 0 || result.stdout !== expectedElectron) {
    throw new Error(
      `packaged Electron launch failed: status=${result.status} stdout=${JSON.stringify(
        result.stdout,
      )} stderr=${JSON.stringify(result.stderr)}`,
    );
  }
}

console.log(
  `[verify-packaged-desktop] OK platform=${expectedPlatform} arch=${expectedArch} version=${expectedVersion} revision=${expectedRevision}`,
);
