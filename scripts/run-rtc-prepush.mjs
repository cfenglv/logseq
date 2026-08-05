#!/usr/bin/env node

import { spawnSync } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const pnpmCommand = process.platform === "win32" ? "pnpm.cmd" : "pnpm";
const run = (label, command, args, options = {}) => {
  const startedAt = Date.now();
  console.log(`\n[rtc-prepush] START ${label}`);
  const result = spawnSync(command, args, {
    cwd: options.cwd || repoRoot,
    env: { ...process.env, ...options.env },
    stdio: "inherit",
    shell: false,
    timeout: options.timeout,
  });
  if (result.error) throw result.error;
  if (result.signal) {
    throw new Error(`${label} terminated by ${result.signal}`);
  }
  if (result.status !== 0) {
    throw new Error(`${label} failed with exit code ${result.status}`);
  }
  console.log(
    `[rtc-prepush] PASS ${label} (${(
      (Date.now() - startedAt) /
      1000
    ).toFixed(1)}s)`,
  );
};

const capture = (command, args) => {
  const result = spawnSync(command, args, {
    cwd: repoRoot,
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
    shell: false,
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(
      `${command} ${args.join(" ")} failed: ${
        result.stderr?.trim() || `exit ${result.status}`
      }`,
    );
  }
  return result.stdout.trim();
};

const assertClean = (phase) => {
  const status = capture("git", [
    "status",
    "--porcelain",
    "--untracked-files=all",
  ]);
  if (status) {
    throw new Error(
      `${phase}: worktree is not clean; commit the intended change and remove unrelated files before the final RTC push gate`,
    );
  }
};

const initialSha = capture("git", ["rev-parse", "HEAD"]);
assertClean("before tests");

run(
  "RTC stress completion barrier contract",
  process.execPath,
  ["--test", "scripts/test-rtc-stress-completion-barrier-contract.mjs"],
);
run(
  "strict source and toolchain preflight",
  process.execPath,
  ["scripts/desktop-release-preflight.mjs", "--strict"],
);
run("build application assets", pnpmCommand, ["gulp:build"]);
run("build DEV-RELEASE RTC application", "clojure", [
  "-Srepro",
  "-M:cljs",
  "release",
  "app",
  "db-worker",
  "--config-merge",
  "{:closure-defines {frontend.config/DEV-RELEASE true}}",
  "--debug",
]);
run("build application webpack assets", pnpmCommand, ["webpack-app-build"]);

const e2eDir = path.join(repoRoot, "clj-e2e");
run(
  "prefetch E2E Clojure dependencies",
  "clojure",
  ["-Srepro", "-P", "-M:test"],
  { cwd: e2eDir },
);
run(
  "RTC browser E2E part 1",
  process.execPath,
  ["scripts/run-rtc-e2e.mjs", "rtc-extra-test"],
  { timeout: 30 * 60 * 1000 },
);
run(
  "RTC browser E2E part 2",
  process.execPath,
  ["scripts/run-rtc-e2e.mjs", "rtc-extra-part2-test"],
  { timeout: 30 * 60 * 1000 },
);

const finalSha = capture("git", ["rev-parse", "HEAD"]);
if (finalSha !== initialSha) {
  throw new Error(
    `HEAD changed while the gate was running (${initialSha} -> ${finalSha})`,
  );
}
assertClean("after tests");

console.log(
  `\n[rtc-prepush] PASS sha=${initialSha} toolchain+build+rtc-e2e`,
);
