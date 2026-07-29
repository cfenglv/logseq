import fs from "node:fs";
import path from "node:path";

export const assertRegularFile = (filePath, label) => {
  let stats;
  try {
    stats = fs.lstatSync(filePath);
  } catch {
    throw new Error(`missing packaged ${label}: ${filePath}`);
  }
  if (stats.isSymbolicLink() || !stats.isFile()) {
    throw new Error(`packaged ${label} is not a regular file: ${filePath}`);
  }
  return stats;
};

export const assertMatchesStagedResource = (
  packagedPath,
  stagedPath,
  label,
) => {
  assertRegularFile(packagedPath, label);
  assertRegularFile(stagedPath, `staged ${label}`);
  if (!fs.readFileSync(packagedPath).equals(fs.readFileSync(stagedPath))) {
    throw new Error(
      `packaged ${label} does not match the staged release resource: ${packagedPath}`,
    );
  }
};

export const verifyProjectSignatureRuntime = ({
  arch,
  platform,
  resourcesDir,
  stagedResourcesDir,
}) => {
  if (!["darwin", "linux", "win32"].includes(platform)) {
    throw new Error(`unsupported packaged runtime platform: ${platform}`);
  }
  if (!["x64", "arm64"].includes(arch)) {
    throw new Error(`unsupported packaged runtime architecture: ${arch}`);
  }
  assertMatchesStagedResource(
    path.join(resourcesDir, "project-updater-signature.mjs"),
    path.join(stagedResourcesDir, "project-updater-signature.mjs"),
    `project updater signature runtime for ${platform}/${arch}`,
  );
};
