#!/usr/bin/env node

import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  assertClean,
  capture,
  printableSteps,
  runSteps,
} from "./selfhost6/final-gate-runner.mjs";

const scriptPath = fileURLToPath(import.meta.url);
const root = path.resolve(path.dirname(scriptPath), "..");
const pnpm = process.platform === "win32" ? "pnpm.cmd" : "pnpm";
const opamSwitch = path.resolve(process.env.SELFHOST6_OPAM_SWITCH ?? path.join(root, "cli"));
const selfhostTests = fs.readdirSync(path.join(root, "scripts/selfhost6"))
  .filter((name) => name.endsWith(".test.mjs"))
  .sort()
  .map((name) => `scripts/selfhost6/${name}`);
export const desktopReleasePreflightStepsFor = (wranglerOut) => [
  {
    label: "Selfhost6 build and updater contracts",
    command: process.execPath,
    args: ["--test", ...selfhostTests],
    timeout: 20 * 60 * 1000,
  },
  { label: "desktop resources", command: pnpm, args: ["exec", "gulp", "build"] },
  { label: "release Electron and DB workers", command: pnpm, args: ["cljs:release-electron"], timeout: 45 * 60 * 1000 },
  { label: "bundle db-worker-node", command: pnpm, args: ["db-worker-node:bundle"] },
  {
    label: "build and stage official CLI",
    command: pnpm,
    args: ["cli:release"],
    env: { OPAMSWITCH: opamSwitch },
    timeout: 30 * 60 * 1000,
  },
  { label: "webpack desktop application", command: pnpm, args: ["webpack-app-build"], timeout: 20 * 60 * 1000 },
  { label: "stage desktop runtime JavaScript", command: pnpm, args: ["desktop:prepare-runtime-js"] },
  { label: "production Worker release", command: pnpm, args: ["--dir", "deps/db-sync", "release"], timeout: 30 * 60 * 1000 },
  {
    label: "exact Selfhost6 Worker dry run",
    command: pnpm,
    args: [
      "--dir", "deps/db-sync", "exec", "wrangler", "versions", "upload", "--dry-run",
      "--config", "worker/wrangler.selfhost6.example.jsonc", "--outdir", wranglerOut,
    ],
    timeout: 20 * 60 * 1000,
  },
];
export const desktopReleasePreflightSteps = desktopReleasePreflightStepsFor("<temporary-directory>");

if (path.resolve(process.argv[1] ?? "") === scriptPath) {
  if (process.argv.includes("--plan")) {
    console.log(JSON.stringify(printableSteps(root, desktopReleasePreflightSteps), null, 2));
  } else {
    const wranglerOut = fs.mkdtempSync(path.join(os.tmpdir(), "selfhost6-wrangler-dry-run-"));
    const steps = desktopReleasePreflightStepsFor(wranglerOut);
    const startSha = capture(root, "git", ["rev-parse", "HEAD"]);
    assertClean(root, "before desktop:release-preflight");
    try {
      runSteps(root, "desktop-release-preflight", steps);
    } finally {
      fs.rmSync(wranglerOut, { recursive: true, force: true });
    }
    if (capture(root, "git", ["rev-parse", "HEAD"]) !== startSha) {
      throw new Error("HEAD changed while desktop:release-preflight was running");
    }
    assertClean(root, "after desktop:release-preflight");
    console.log(`\n[desktop-release-preflight] FULL PASS sha=${startSha}`);
  }
}
