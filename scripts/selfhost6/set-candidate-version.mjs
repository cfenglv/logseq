#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
assert.deepEqual(process.argv.slice(2, 3), ["--version"]);
const targetVersion = process.argv[3];
assert.ok(
  new Set(["2.0.1-selfhost.6", "2.0.1-selfhost.7"]).has(targetVersion),
  "candidate version must be the reissued .6 or synthetic .7",
);

const sourcePath = path.join(repoRoot, "src/main/frontend/version.cljs");
const source = fs.readFileSync(sourcePath, "utf8");
const pattern = /\(defonce version "2\.0\.1-selfhost\.[67]"\)/;
assert.equal((source.match(new RegExp(pattern.source, "g")) ?? []).length, 1,
  "frontend version source must contain one controlled selfhost candidate version");
fs.writeFileSync(sourcePath, source.replace(pattern, `(defonce version "${targetVersion}")`));
process.stdout.write(`${JSON.stringify({ status: "prepared", targetVersion })}\n`);
