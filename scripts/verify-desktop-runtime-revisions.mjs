#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const packagedRuntimeRoot = process.env.LOGSEQ_DESKTOP_RUNTIME_ROOT?.trim();
const runtimeRoot = packagedRuntimeRoot
  ? path.resolve(packagedRuntimeRoot)
  : repoRoot;

const expectedRevision =
  process.env.LOGSEQ_REVISION?.trim() ||
  execFileSync("git", ["describe", "--long", "--always", "--dirty"], {
    cwd: repoRoot,
    encoding: "utf8",
  }).trim();

const runtimeFiles = packagedRuntimeRoot
  ? [
      "electron.js",
      "db-worker-node.js",
      "logseq-cli.js",
      "js/db-worker-node.js",
      "js/logseq-cli.js",
      "js/main.js",
      "js/db-worker.js",
      "js/publishing/main.js",
    ]
  : [
      "static/electron.js",
      "static/db-worker-node.js",
      "static/logseq-cli.js",
      "dist/db-worker-node.js",
      "static/js/db-worker-node.js",
      "static/js/logseq-cli.js",
      "static/js/main.js",
      "static/js/db-worker.js",
      "static/js/publishing/main.js",
    ];

for (const relativePath of runtimeFiles) {
  const filePath = path.join(runtimeRoot, relativePath);
  if (!fs.existsSync(filePath)) {
    throw new Error(`missing desktop runtime: ${relativePath}`);
  }

  const source = fs.readFileSync(filePath, "utf8");
  if (!source.includes(expectedRevision)) {
    throw new Error(
      `${relativePath} does not contain current revision ${expectedRevision}; rebuild all desktop runtimes before packaging`,
    );
  }

  console.log(`[desktop-runtime-revision] ${relativePath}: ${expectedRevision}`);
}
