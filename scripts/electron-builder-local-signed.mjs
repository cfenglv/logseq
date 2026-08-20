#!/usr/bin/env node

import { spawnSync } from "node:child_process";
import os from "node:os";
import { fileURLToPath } from "node:url";
import path from "node:path";
import {
  identityName,
  keychainPath,
  setupLocalMacCodesign,
} from "./setup-local-macos-codesign.mjs";

const appleNotarizationEnvKeys = [
  "APPLE_ID",
  "APPLE_APP_SPECIFIC_PASSWORD",
  "APPLE_TEAM_ID",
  "APPLE_API_KEY",
  "APPLE_API_KEY_ID",
  "APPLE_API_ISSUER",
  "APPLE_KEYCHAIN",
  "APPLE_KEYCHAIN_PROFILE",
];

export const localSignedOutputDir =
  process.env.LOGSEQ_LOCAL_SIGNED_OUTPUT_DIR ||
  path.join(
    os.homedir(),
    "Library",
    "Caches",
    "logseq-selfhost-build",
    "dist",
  );

export const localSignedBuildEnv = (baseEnv = process.env) => {
  const env = {
    ...baseEnv,
    CSC_IDENTITY_AUTO_DISCOVERY: "true",
    CSC_KEYCHAIN: keychainPath,
    CSC_NAME: identityName,
  };

  for (const key of appleNotarizationEnvKeys) {
    delete env[key];
  }
  delete env.CSC_LINK;
  delete env.CSC_KEY_PASSWORD;

  return env;
};

export const localSignedElectronBuilderArgs = (extraArgs = []) => [
  "exec",
  "electron-builder",
  "--config",
  "electron-builder.local-signed.yml",
  "--mac",
  "dmg",
  "zip",
  "--publish",
  "never",
  `-c.mac.identity=${identityName}`,
  "-c.mac.notarize=false",
  `-c.directories.output=${localSignedOutputDir}`,
  ...extraArgs,
];

export const runLocalSignedElectronBuilder = ({
  cwd = process.cwd(),
  env = process.env,
  extraArgs = process.argv.slice(2),
} = {}) => {
  setupLocalMacCodesign();

  console.log(`Local signed build output: ${localSignedOutputDir}`);

  const result = spawnSync(
    "pnpm",
    localSignedElectronBuilderArgs(extraArgs),
    {
      cwd,
      env: localSignedBuildEnv(env),
      shell: false,
      stdio: "inherit",
    },
  );

  if (result.error) {
    throw result.error;
  }

  return result.status ?? 1;
};

const isEntrypoint =
  process.argv[1] &&
  path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);

if (isEntrypoint) {
  try {
    process.exitCode = runLocalSignedElectronBuilder();
  } catch (error) {
    console.error(error instanceof Error ? error.message : error);
    process.exitCode = 1;
  }
}
