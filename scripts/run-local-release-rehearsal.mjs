#!/usr/bin/env node

import { spawnSync } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { assertClean, capture } from "./selfhost6/final-gate-runner.mjs";

const scriptPath = fileURLToPath(import.meta.url);
const root = path.resolve(path.dirname(scriptPath), "..");
const pnpm = process.platform === "win32" ? "pnpm.cmd" : "pnpm";
export const rehearsalScripts = ["rtc:prepush", "desktop:release-preflight"];

if (path.resolve(process.argv[1] ?? "") === scriptPath) {
  if (process.argv.includes("--plan")) {
    console.log(JSON.stringify(rehearsalScripts));
  } else {
    const startSha = capture(root, "git", ["rev-parse", "HEAD"]);
    assertClean(root, "before release:rehearsal");
    for (const script of rehearsalScripts) {
      console.log(`\n[release-rehearsal] START ${script}`);
      const result = spawnSync(pnpm, [script], { cwd: root, shell: false, stdio: "inherit" });
      if (result.error) throw result.error;
      if (result.status !== 0) throw new Error(`${script} failed with exit code ${result.status}`);
      if (capture(root, "git", ["rev-parse", "HEAD"]) !== startSha) {
        throw new Error(`${script} changed HEAD during rehearsal`);
      }
      assertClean(root, `after ${script}`);
      console.log(`[release-rehearsal] PASS ${script}`);
    }
    console.log(`[release-rehearsal] FULL PASS sha=${startSha}`);
  }
}
