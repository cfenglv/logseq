import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { parseSelfhostProjectVersion } from "../resources/project-updater-signature.mjs";
import { macosUpdaterMetadataName } from "../resources/selfhost-updater-version.mjs";
import { signMacosProjectUpdate } from "./sign-macos-project-update.mjs";
import { verifyProjectSignedMacosUpdate } from "./verify-project-signed-macos-update.mjs";
import { verifyUnsignedMacosProjectUpdateCandidate } from "./verify-unsigned-macos-project-update-candidate.mjs";

const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);

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

export const finalizeMacosProjectUpdate = async ({
  dir,
  finalizeArtifact,
  policy,
  rollbackArtifact,
  signingKey,
  version,
}) => {
  parseSelfhostProjectVersion(version);
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
    await finalizeArtifact?.();
  } catch (error) {
    try {
      await rollbackArtifact?.();
    } finally {
      for (const [metadata, contents] of originalMetadata) {
        fs.writeFileSync(metadata, contents);
      }
      if (originalChecksum) {
        fs.writeFileSync(checksumPath, originalChecksum);
      } else {
        fs.rmSync(checksumPath, { force: true });
      }
    }
    throw error;
  }
  return Object.freeze({
    architectures: Object.freeze(candidates.map(({ arch }) => arch)),
    keyId: policy.keyId,
    version,
  });
};
