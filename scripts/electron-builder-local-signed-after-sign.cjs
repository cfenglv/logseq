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

  const identity = process.env.CSC_NAME;
  const keychain = process.env.CSC_KEYCHAIN;
  if (!identity || !keychain) {
    throw new Error("CSC_NAME and CSC_KEYCHAIN are required for local signing");
  }

  const appPath = path.join(
    appOutDir,
    `${packager.appInfo.productFilename}.app`,
  );
  const entitlements = path.resolve(
    __dirname,
    "../resources/entitlements.local-signed.plist",
  );

  // electron-builder's generated entitlement blob can be invalid on macOS 26.
  // Re-sign the completed bundle with the system tool before artifacts are made.
  runCodesign([
    "--force",
    "--deep",
    "--options",
    "runtime",
    "--timestamp=none",
    "--entitlements",
    entitlements,
    "--keychain",
    keychain,
    "--sign",
    identity,
    appPath,
  ]);
  runCodesign(["--verify", "--deep", "--strict", "--verbose=4", appPath]);
};
