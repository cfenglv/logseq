#!/usr/bin/env node

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
export const rtcPrepushSteps = [
  { label: "full client lint and tests", command: "bb", args: ["dev:lint-and-test"], timeout: 60 * 60 * 1000 },
  { label: "official db-sync server tests", command: pnpm, args: ["--dir", "deps/db-sync", "test"], timeout: 45 * 60 * 1000 },
  { label: "128 MiB large-operation gate", command: pnpm, args: ["--dir", "deps/db-sync", "test:large-op-128m"], timeout: 20 * 60 * 1000 },
];

if (path.resolve(process.argv[1] ?? "") === scriptPath) {
  if (process.argv.includes("--plan")) {
    console.log(JSON.stringify(printableSteps(root, rtcPrepushSteps), null, 2));
  } else {
    const startSha = capture(root, "git", ["rev-parse", "HEAD"]);
    assertClean(root, "before rtc:prepush");
    runSteps(root, "rtc-prepush", rtcPrepushSteps);
    if (capture(root, "git", ["rev-parse", "HEAD"]) !== startSha) {
      throw new Error("HEAD changed while rtc:prepush was running");
    }
    assertClean(root, "after rtc:prepush");
    console.log(`\n[rtc-prepush] FULL PASS sha=${startSha}`);
  }
}
