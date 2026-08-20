#!/usr/bin/env node

import { spawnSync } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const pnpm = process.platform === "win32" ? "pnpm.cmd" : "pnpm";

const run = (command, args, label, capture = false) => {
  const result = spawnSync(command, args, {
    cwd: repoRoot,
    encoding: capture ? "utf8" : undefined,
    stdio: capture ? ["ignore", "pipe", "inherit"] : "inherit",
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`${label} failed with exit code ${result.status}`);
  }
  return capture ? result.stdout.trim() : "";
};

const git = (...args) => run("git", args, `git ${args.join(" ")}`, true);
const startSha = git("rev-parse", "HEAD");
const startStatus = git("status", "--porcelain", "--untracked-files=all");

if (startStatus !== "") {
  throw new Error("local release rehearsal requires a clean worktree");
}

for (const [script, label] of [
  ["rtc:prepush", "RTC pre-push gate"],
  ["desktop:release-preflight", "desktop release preflight"],
]) {
  console.log(`[release-rehearsal] START ${label}`);
  run(pnpm, [script], label);
  if (git("rev-parse", "HEAD") !== startSha) {
    throw new Error(`${label} changed HEAD during rehearsal`);
  }
  if (git("status", "--porcelain", "--untracked-files=all") !== "") {
    throw new Error(`${label} left the worktree dirty`);
  }
  console.log(`[release-rehearsal] PASS ${label}`);
}

console.log(`[release-rehearsal] FULL PASS sha=${startSha}`);
