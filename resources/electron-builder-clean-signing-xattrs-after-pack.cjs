const fs = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");

const codesignIncompatibleXattrs = [
  "com.apple.FinderInfo",
  "com.apple.ResourceFork",
];

const isStrictDescendant = (parent, candidate) => {
  const relative = path.relative(parent, candidate);
  return (
    relative !== "" &&
    relative !== ".." &&
    !relative.startsWith(`..${path.sep}`) &&
    !path.isAbsolute(relative)
  );
};

const deleteRecursively = (attribute, appPath) => {
  const result = spawnSync(
    "/usr/bin/xattr",
    ["-dr", attribute, appPath],
    { stdio: "inherit" },
  );

  if (result.error) {
    throw result.error;
  }
  if (result.status !== 0) {
    throw new Error(
      `xattr failed to delete ${attribute} with exit code ${result.status}`,
    );
  }
};

module.exports = async ({
  electronPlatformName,
  outDir,
  appOutDir,
  packager,
}) => {
  if (electronPlatformName !== "darwin") {
    return;
  }

  const resolvedOutDir = path.resolve(outDir);
  const resolvedAppOutDir = path.resolve(appOutDir);
  if (!isStrictDescendant(resolvedOutDir, resolvedAppOutDir)) {
    throw new Error(
      `refusing to clean xattrs outside electron-builder output: ${resolvedAppOutDir}`,
    );
  }

  const appPath = path.resolve(
    resolvedAppOutDir,
    `${packager.appInfo.productFilename}.app`,
  );
  if (path.dirname(appPath) !== resolvedAppOutDir) {
    throw new Error(`invalid packaged app path: ${appPath}`);
  }

  const appStats = fs.lstatSync(appPath);
  if (!appStats.isDirectory() || appStats.isSymbolicLink()) {
    throw new Error(`packaged app is not a directory: ${appPath}`);
  }

  for (const attribute of codesignIncompatibleXattrs) {
    deleteRecursively(attribute, appPath);
  }
};
