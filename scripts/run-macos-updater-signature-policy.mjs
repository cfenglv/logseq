#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { selfhostUpdaterRevision } from "../resources/selfhost-updater-version.mjs";
import { runGate } from "./verify-macos-updater-signature.mjs";

const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
export const signedBaselineManifest = path.join(
  repoRoot,
  "scripts",
  "fixtures",
  "macos-updater-signed-baseline.json",
);

export const updaterSignatureGatePlan = (
  candidateVersion,
  baselineExists = fs.existsSync(signedBaselineManifest),
) => {
  const revision = selfhostUpdaterRevision(candidateVersion);
  if (revision === 5) {
    return {
      mode: "manual-migration",
      message:
        "2.0.1-selfhost.5 starts the Developer ID trust chain by manual installation; the pinned .4 signature rejection is a reproducer, not a release gate",
    };
  }
  if (revision > 5 && !baselineExists) {
    throw new Error(
      "future macOS updater candidates require scripts/fixtures/macos-updater-signed-baseline.json pinned from the published Developer ID signed and notarized 2.0.1-selfhost.5 release",
    );
  }
  if (revision > 5) {
    return { mode: "signed-baseline", manifest: signedBaselineManifest };
  }
  throw new Error(
    `selfhost.${revision} predates the signed macOS updater trust chain`,
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
  await runGate({
    arch: args.arch,
    candidateMetadata: path.resolve(args["candidate-metadata"]),
    candidateVersion: args["candidate-version"],
    candidateZip: path.resolve(args["candidate-zip"]),
    baselineManifest: plan.manifest,
    baselineMetadata: process.env.LOGSEQ_UPDATER_BASELINE_METADATA,
    baselineZip: process.env.LOGSEQ_UPDATER_BASELINE_ZIP,
    requireDeveloperIdBaseline: true,
  });
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
