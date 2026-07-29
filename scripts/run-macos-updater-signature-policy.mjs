#!/usr/bin/env node

import path from "node:path";
import { fileURLToPath } from "node:url";
import { selfhostUpdaterRevision } from "../resources/selfhost-updater-version.mjs";
import { verifyProjectSignedMacosUpdate } from "./verify-project-signed-macos-update.mjs";

export const updaterSignatureGatePlan = (candidateVersion) => {
  const revision = selfhostUpdaterRevision(candidateVersion);
  if (revision === 5) {
    return {
      mode: "manual-migration",
      message:
        "2.0.1-selfhost.4 users must manually replace the App with 2.0.1-selfhost.5 to bootstrap the fixed project Ed25519 key; the pinned .4 signature rejection is a reproducer, not a release gate",
    };
  }
  if (revision > 5) {
    return { mode: "project-signed" };
  }
  throw new Error(
    `selfhost.${revision} predates the project-signed macOS updater chain`,
  );
};

const parseArgs = (argv) => {
  const result = {};
  for (let index = 0; index < argv.length; index += 2) {
    const key = argv[index];
    const value = argv[index + 1];
    if (!key?.startsWith("--") || !value) {
      throw new Error(`invalid argument near ${key ?? "<end>"}`);
    }
    result[key.slice(2)] = value;
  }
  if (!result["candidate-version"]) {
    throw new Error("missing required --candidate-version");
  }
  return result;
};

const main = async () => {
  const args = parseArgs(process.argv.slice(2));
  const plan = updaterSignatureGatePlan(args["candidate-version"]);
  if (plan.mode === "manual-migration") {
    console.log(`[macos-updater-signature-policy] NOT_APPLICABLE ${plan.message}`);
    return;
  }

  for (const key of ["arch", "candidate-metadata", "candidate-zip"]) {
    if (!args[key]) throw new Error(`missing required --${key}`);
  }
  const result = verifyProjectSignedMacosUpdate({
    arch: args.arch,
    archive: path.resolve(args["candidate-zip"]),
    metadata: path.resolve(args["candidate-metadata"]),
    version: args["candidate-version"],
  });
  console.log(
    `[macos-updater-signature-policy] OK mode=${plan.mode} version=${result.version} arch=${result.arch} keyId=${result.keyId}`,
  );
};

const isEntrypoint =
  process.argv[1] &&
  path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);

if (isEntrypoint) {
  try {
    await main();
  } catch (error) {
    console.error(
      `[macos-updater-signature-policy] ERROR ${
        error instanceof Error ? error.message : error
      }`,
    );
    process.exitCode = 1;
  }
}
