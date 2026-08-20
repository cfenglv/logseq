#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";
import { spawnSync } from "node:child_process";

const require = createRequire(import.meta.url);
const asar = require("@electron/asar");

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

if (!["darwin", "linux", "win32"].includes(expectedPlatform)) {
  throw new Error(`unsupported --platform: ${expectedPlatform}`);
}
if (!["x64", "arm64"].includes(expectedArch)) {
  throw new Error(`unsupported --arch: ${expectedArch}`);
}
if (!expectedVersion) {
  throw new Error("--version is required");
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

for (const filePath of [mainExecutable, keytar, appUpdateConfig]) {
  if (!fs.existsSync(filePath)) {
    throw new Error(`missing packaged native runtime: ${filePath}`);
  }
}

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

const packageJson = JSON.parse(
  asar.extractFile(appAsar, "package.json").toString(),
);
if (packageJson.version !== expectedVersion) {
  throw new Error(
    `packaged app version ${packageJson.version} does not match ${expectedVersion}`,
  );
}
if (packageJson.main !== "electron.js") {
  throw new Error(`packaged app main is ${packageJson.main}, expected electron.js`);
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
  `[verify-packaged-desktop] OK platform=${expectedPlatform} arch=${expectedArch} version=${expectedVersion}`,
);
