#!/usr/bin/env node

import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { macosUpdaterMetadataName } from "../resources/selfhost-updater-version.mjs";

const parseArgs = (argv) => {
  const result = {};
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === "--write-checksums" || arg === "--require-release-notes") {
      result[arg.slice(2)] = true;
      continue;
    }
    if (!arg.startsWith("--") || !argv[index + 1]) {
      throw new Error(`invalid argument: ${arg}`);
    }
    result[arg.slice(2)] = argv[index + 1];
    index += 1;
  }
  return result;
};

const args = parseArgs(process.argv.slice(2));
const releaseDir = path.resolve(args.dir || ".");
const version = args.version?.trim();

if (!version) {
  throw new Error("--version is required");
}
if (version.includes("+")) {
  throw new Error(
    `release version ${version} contains SemVer build metadata; electron-builder strips build metadata from updater filenames, so use a prerelease version such as -alpha.nightly.YYYYMMDD`,
  );
}

const desktopArtifactNames = [
  `Logseq-darwin-arm64-${version}.dmg`,
  `Logseq-darwin-arm64-${version}.dmg.blockmap`,
  `Logseq-darwin-arm64-${version}.zip`,
  `Logseq-darwin-arm64-${version}.zip.blockmap`,
  `Logseq-darwin-x64-${version}.dmg`,
  `Logseq-darwin-x64-${version}.dmg.blockmap`,
  `Logseq-darwin-x64-${version}.zip`,
  `Logseq-darwin-x64-${version}.zip.blockmap`,
  `Logseq-linux-arm64-${version}.AppImage`,
  `Logseq-linux-arm64-${version}.zip`,
  `Logseq-linux-x86_64-${version}.AppImage`,
  `Logseq-linux-x86_64-${version}.zip`,
  `Logseq-win-arm64-${version}-nsis.exe`,
  `Logseq-win-arm64-${version}-nsis.exe.blockmap`,
  `Logseq-win-arm64-${version}.zip`,
  `Logseq-win-x64-${version}-nsis.exe`,
  `Logseq-win-x64-${version}-nsis.exe.blockmap`,
  `Logseq-win-x64-${version}.zip`,
  macosUpdaterMetadataName(version, "arm64"),
  "latest-arm64.yml",
  "latest-linux-arm64.yml",
  "latest-linux.yml",
  macosUpdaterMetadataName(version, "x64"),
  "latest-x64.yml",
  "VERSION",
];
const androidArtifactNames = fs
  .readdirSync(releaseDir)
  .filter((name) => name.endsWith(".apk"));
const artifactNames = [
  ...desktopArtifactNames,
  ...androidArtifactNames,
].sort();

const releaseFilePattern =
  /^(?:Logseq-(?:darwin|linux|win)-|latest(?:-|\.yml)|selfhost-macos-v2-(?:arm64|x64)-mac\.yml$|VERSION$)|\.apk$/;
const actualArtifactNames = fs
  .readdirSync(releaseDir)
  .filter((name) => releaseFilePattern.test(name))
  .sort();

if (JSON.stringify(actualArtifactNames) !== JSON.stringify(artifactNames)) {
  const missing = artifactNames.filter(
    (name) => !actualArtifactNames.includes(name),
  );
  const unexpected = actualArtifactNames.filter(
    (name) => !artifactNames.includes(name),
  );
  throw new Error(
    `release artifact set mismatch; missing=${JSON.stringify(
      missing,
    )} unexpected=${JSON.stringify(unexpected)}`,
  );
}

const versionFile = fs
  .readFileSync(path.join(releaseDir, "VERSION"), "utf8")
  .trim();
if (versionFile !== version) {
  throw new Error(`VERSION contains ${versionFile}, expected ${version}`);
}

const sha512 = (filePath) =>
  crypto.createHash("sha512").update(fs.readFileSync(filePath)).digest("base64");

for (const yamlName of artifactNames.filter((name) => name.endsWith(".yml"))) {
  const yaml = fs.readFileSync(path.join(releaseDir, yamlName), "utf8");
  const yamlVersion = yaml.match(/^version:\s*(.+)$/m)?.[1]?.trim();
  if (yamlVersion !== version) {
    throw new Error(`${yamlName} version ${yamlVersion} does not match ${version}`);
  }

  const fileEntries = [
    ...yaml.matchAll(
      /^\s*-\s+url:\s*(.+)\n\s+sha512:\s*(.+)\n\s+size:\s*(\d+)$/gm,
    ),
  ].map((match) => ({
    name: decodeURIComponent(match[1].trim()),
    digest: match[2].trim(),
    size: Number(match[3]),
  }));

  if (fileEntries.length === 0) {
    throw new Error(`${yamlName} does not contain update file metadata`);
  }

  for (const entry of fileEntries) {
    const filePath = path.join(releaseDir, entry.name);
    if (!fs.existsSync(filePath)) {
      throw new Error(`${yamlName} references missing ${entry.name}`);
    }
    const stat = fs.statSync(filePath);
    if (stat.size !== entry.size) {
      throw new Error(
        `${yamlName} size mismatch for ${entry.name}: ${entry.size} != ${stat.size}`,
      );
    }
    const digest = sha512(filePath);
    if (digest !== entry.digest) {
      throw new Error(`${yamlName} SHA-512 mismatch for ${entry.name}`);
    }
  }
}

if (args["require-release-notes"]) {
  const notesPath = path.resolve(
    args["release-notes-root"] || ".",
    "docs",
    "releases",
    `${version}.md`,
  );
  if (!fs.existsSync(notesPath) || fs.statSync(notesPath).size === 0) {
    throw new Error(`missing release notes: ${notesPath}`);
  }
}

if (args["write-checksums"]) {
  const checksumLines = artifactNames.map((name) => {
    const digest = crypto
      .createHash("sha256")
      .update(fs.readFileSync(path.join(releaseDir, name)))
      .digest("hex");
    return `${digest}  ${name}`;
  });
  fs.writeFileSync(
    path.join(releaseDir, "SHA256SUMS.txt"),
    `${checksumLines.join("\n")}\n`,
  );
}

const checksumPath = path.join(releaseDir, "SHA256SUMS.txt");
if (fs.existsSync(checksumPath)) {
  const entries = fs
    .readFileSync(checksumPath, "utf8")
    .trim()
    .split("\n")
    .map((line) => {
      const match = line.match(/^([0-9a-f]{64}) {2}(.+)$/);
      if (!match) throw new Error(`invalid checksum line: ${line}`);
      return { digest: match[1], name: match[2] };
    });
  const checksumNames = entries.map((entry) => entry.name).sort();
  if (JSON.stringify(checksumNames) !== JSON.stringify(artifactNames)) {
    throw new Error("SHA256SUMS.txt does not cover the exact release artifact set");
  }
  for (const entry of entries) {
    const digest = crypto
      .createHash("sha256")
      .update(fs.readFileSync(path.join(releaseDir, entry.name)))
      .digest("hex");
    if (digest !== entry.digest) {
      throw new Error(`SHA-256 mismatch for ${entry.name}`);
    }
  }
}

console.log(
  `[verify-desktop-release-assets] OK version=${version} artifacts=${artifactNames.length}`,
);
