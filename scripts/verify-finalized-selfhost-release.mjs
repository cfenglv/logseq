#!/usr/bin/env node

import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { parseSelfhostProjectVersion } from "../resources/project-updater-signature.mjs";
import { macosUpdaterMetadataName } from "../resources/selfhost-updater-version.mjs";
import { verifyProjectSignedMacosUpdate } from "./verify-project-signed-macos-update.mjs";

const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);

const parseArgs = (argv) => {
  const values = new Map();
  for (let index = 0; index < argv.length; index += 2) {
    const key = argv[index];
    const value = argv[index + 1];
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
  return Object.freeze({
    dir: path.resolve(required("--dir")),
    version: required("--version"),
  });
};

const verifyCompleteAssets = ({ dir, version }) => {
  if (!fs.existsSync(path.join(dir, "SHA256SUMS.txt"))) {
    throw new Error("finalized release artifact is missing SHA256SUMS.txt");
  }
  const result = spawnSync(
    process.execPath,
    [
      path.join(repoRoot, "scripts", "verify-desktop-release-assets.mjs"),
      "--dir",
      dir,
      "--version",
      version,
      "--require-release-notes",
      "--release-notes-root",
      repoRoot,
    ],
    {
      cwd: repoRoot,
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    },
  );
  if (result.error || result.status !== 0) {
    throw new Error(
      `finalized release asset verification failed${
        result.stdout || result.stderr
          ? `: ${(result.stdout || result.stderr).trim()}`
          : ""
      }`,
    );
  }
};

const main = () => {
  const { dir, version } = parseArgs(process.argv.slice(2));
  const parsed = parseSelfhostProjectVersion(version);
  if (parsed.nightlyDate !== undefined) {
    throw new Error("finalized stable/beta release cannot be a nightly version");
  }
  verifyCompleteAssets({ dir, version });
  const architectures = ["arm64", "x64"];
  for (const arch of architectures) {
    verifyProjectSignedMacosUpdate({
      arch,
      archive: path.join(dir, `Logseq-darwin-${arch}-${version}.zip`),
      metadata: path.join(dir, macosUpdaterMetadataName(version, arch)),
      version,
    });
  }
  console.log(
    `[verify-finalized-selfhost-release] OK version=${version} architectures=${architectures.join(",")}`,
  );
};

try {
  main();
} catch (error) {
  console.error(
    `[verify-finalized-selfhost-release] RELEASE BLOCKED: ${
      error instanceof Error ? error.message : error
    }`,
  );
  process.exitCode = 1;
}
