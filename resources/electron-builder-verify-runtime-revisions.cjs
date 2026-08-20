const fs = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");

const defaultRepoRoot = path.resolve(__dirname, "..");

const assertRegularFile = (filePath, label) => {
  const stats = fs.lstatSync(filePath);
  if (stats.isSymbolicLink() || !stats.isFile()) {
    throw new Error(`${label} must be a regular, non-symlink file: ${filePath}`);
  }
};

const verifyDesktopRuntimeRevisions = (
  context,
  {
    repoRoot = defaultRepoRoot,
    verifierPath = path.join(
      repoRoot,
      "scripts",
      "verify-desktop-runtime-revisions.mjs",
    ),
    stdio = "inherit",
  } = {},
) => {
  const releaseSourceSha = process.env.LOGSEQ_RELEASE_SOURCE_SHA?.trim();
  if (!releaseSourceSha || !/^[0-9a-f]{40}$/.test(releaseSourceSha)) {
    throw new Error(
      "Electron packaging requires LOGSEQ_RELEASE_SOURCE_SHA as an exact lowercase 40-hex commit SHA",
    );
  }

  const expectedAppDir = path.join(repoRoot, "static");
  const appDir = context.packager?.projectDir ?? context.appDir;
  if (!appDir || path.resolve(appDir) !== path.resolve(expectedAppDir)) {
    throw new Error(
      `desktop packaging appDir must be ${expectedAppDir}, got ${appDir ?? "undefined"}`,
    );
  }

  assertRegularFile(verifierPath, "desktop runtime revision verifier");
  const result = spawnSync(process.execPath, [verifierPath], {
    cwd: repoRoot,
    env: process.env,
    shell: false,
    stdio,
  });

  if (result.error) {
    throw result.error;
  }
  if (result.status !== 0) {
    throw new Error(
      `desktop runtime revision verification failed before packaging (status=${result.status ?? "none"} signal=${result.signal ?? "none"})`,
    );
  }
};

const beforePack = async (context) => {
  verifyDesktopRuntimeRevisions(context);
};

module.exports = beforePack;
module.exports.verifyDesktopRuntimeRevisions =
  verifyDesktopRuntimeRevisions;
