const path = require("node:path");
const { spawnSync } = require("node:child_process");

const runCodesign = (args) => {
  const result = spawnSync("codesign", args, { stdio: "inherit" });

  if (result.error) {
    throw result.error;
  }
  if (result.status !== 0) {
    throw new Error(`codesign failed with exit code ${result.status}`);
  }
};

module.exports = async ({ electronPlatformName, appOutDir, packager }) => {
  if (electronPlatformName !== "darwin") {
    return;
  }

  const appPath = path.join(
    appOutDir,
    `${packager.appInfo.productFilename}.app`,
  );
  const entitlements = path.resolve(
    __dirname,
    "entitlements.local-signed.plist",
  );

  // electron-builder can leave an ad-hoc outer signature around nested
  // Electron binaries whose signatures do not satisfy library validation.
  // Re-sign the completed bundle as one unit and explicitly disable library
  // validation for fork builds that do not have an Apple Developer ID.
  runCodesign([
    "--force",
    "--deep",
    "--options",
    "runtime",
    "--timestamp=none",
    "--entitlements",
    entitlements,
    "--sign",
    "-",
    appPath,
  ]);
  runCodesign(["--verify", "--deep", "--strict", "--verbose=4", appPath]);
};
