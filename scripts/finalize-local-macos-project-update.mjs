#!/usr/bin/env node

import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  loadProjectSigningPolicy,
  parseSelfhostProjectVersion,
} from "../resources/project-updater-signature.mjs";
import { macosUpdaterMetadataName } from "../resources/selfhost-updater-version.mjs";
import {
  assertLocalMacosProjectUpdatePublisher,
  loadProjectUpdateSigningKey,
} from "./project-update-keychain.mjs";
import { signMacosProjectUpdate } from "./sign-macos-project-update.mjs";
import { verifyProjectSignedMacosUpdate } from "./verify-project-signed-macos-update.mjs";
import { verifyUnsignedMacosProjectUpdateCandidate } from "./verify-unsigned-macos-project-update-candidate.mjs";

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

const verifyCompleteReleaseAssets = ({ dir, version }) => {
  const result = spawnSync(
    process.execPath,
    [
      path.join(repoRoot, "scripts", "verify-desktop-release-assets.mjs"),
      "--dir",
      dir,
      "--version",
      version,
      "--write-checksums",
    ],
    {
      cwd: repoRoot,
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    },
  );
  if (result.error || result.status !== 0) {
    throw new Error(
      `complete release asset verification failed${
        result.stdout || result.stderr
          ? `: ${(result.stdout || result.stderr).trim()}`
          : ""
      }`,
    );
  }
};

const main = async () => {
  assertLocalMacosProjectUpdatePublisher();
  const { dir, version } = parseArgs(process.argv.slice(2));
  parseSelfhostProjectVersion(version);
  const policy = loadProjectSigningPolicy();
  const signingKey = loadProjectUpdateSigningKey(policy);
  const candidates = [];
  for (const arch of ["arm64", "x64"]) {
    const archive = path.join(
      dir,
      `Logseq-darwin-${arch}-${version}.zip`,
    );
    const metadata = path.join(
      dir,
      macosUpdaterMetadataName(version, arch),
    );
    verifyUnsignedMacosProjectUpdateCandidate({
      arch,
      archive,
      metadata,
      version,
    });
    candidates.push({ arch, archive, metadata, version });
  }

  const originalMetadata = new Map(
    candidates.map(({ metadata }) => [
      metadata,
      fs.readFileSync(metadata),
    ]),
  );
  const checksumPath = path.join(dir, "SHA256SUMS.txt");
  const originalChecksum = fs.existsSync(checksumPath)
    ? fs.readFileSync(checksumPath)
    : undefined;
  try {
    for (const candidate of candidates) {
      await signMacosProjectUpdate({
        ...candidate,
        policy,
        privateKey: signingKey,
      });
    }
    for (const candidate of candidates) {
      verifyProjectSignedMacosUpdate(candidate);
    }
    verifyCompleteReleaseAssets({ dir, version });
  } catch (error) {
    for (const [metadata, contents] of originalMetadata) {
      fs.writeFileSync(metadata, contents);
    }
    if (originalChecksum) {
      fs.writeFileSync(checksumPath, originalChecksum);
    } else {
      fs.rmSync(checksumPath, { force: true });
    }
    throw error;
  }
  console.log(
    `[project-update-finalize] OK version=${version} keyId=${policy.keyId} architectures=arm64,x64`,
  );
};

try {
  await main();
} catch (error) {
  console.error(
    `[project-update-finalize] RELEASE BLOCKED: ${
      error instanceof Error ? error.message : error
    }`,
  );
  process.exitCode = 1;
}
