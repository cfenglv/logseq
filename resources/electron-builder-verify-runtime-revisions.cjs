const fs = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");

const defaultAppDir = __dirname;

const assertRegularFile = (filePath, label) => {
  const stats = fs.lstatSync(filePath);
  if (stats.isSymbolicLink() || !stats.isFile()) {
    throw new Error(`${label} must be a regular, non-symlink file: ${filePath}`);
  }
};

const verifyDesktopRuntimeRevisions = (
  context,
  {
    appDir = defaultAppDir,
    verifierPath = path.join(appDir, "verify-desktop-runtime-revisions.mjs"),
    revisionPath = path.join(appDir, "RUNTIME_REVISION"),
    stdio = "inherit",
  } = {},
) => {
  const contextAppDir = context.packager?.projectDir ?? context.appDir;
  if (!contextAppDir || path.resolve(contextAppDir) !== path.resolve(appDir)) {
    throw new Error(
      `desktop packaging appDir must be ${appDir}, got ${contextAppDir ?? "undefined"}`,
    );
  }

  assertRegularFile(verifierPath, "desktop runtime revision verifier");
  assertRegularFile(revisionPath, "desktop runtime revision manifest");
  const revision = fs.readFileSync(revisionPath, "utf8").trim();
  if (!revision || revision.includes("\n") || revision.includes("\r")) {
    throw new Error("desktop runtime revision manifest must contain one revision");
  }
  const result = spawnSync(process.execPath, [verifierPath], {
    cwd: appDir,
    env: {
      ...process.env,
      LOGSEQ_DESKTOP_RUNTIME_ROOT: appDir,
      LOGSEQ_REVISION: revision,
    },
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
