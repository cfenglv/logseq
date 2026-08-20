#!/usr/bin/env node

import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

const separator = process.argv.indexOf("--");
if (separator < 0) {
  throw new Error(
    "usage: run-project-signed-macos-update.mjs --helper PATH -- [native helper arguments]",
  );
}
const wrapperArgs = process.argv.slice(2, separator);
const helperArgs = process.argv.slice(separator + 1);
if (
  wrapperArgs.length !== 2 ||
  wrapperArgs[0] !== "--helper" ||
  helperArgs.length === 0
) {
  throw new Error("exactly one --helper PATH and native helper arguments are required");
}
const helper = path.resolve(wrapperArgs[1]);
const stat = fs.lstatSync(helper);
if (!stat.isFile() || stat.isSymbolicLink() || (stat.mode & 0o111) === 0) {
  throw new Error("native project updater helper must be a non-symlink executable file");
}
const result = spawnSync(helper, helperArgs, {
  encoding: "utf8",
  stdio: ["ignore", "pipe", "pipe"],
});
process.stdout.write(result.stdout || "");
process.stderr.write(result.stderr || "");
if (result.error) throw result.error;
process.exitCode = result.status ?? 1;
