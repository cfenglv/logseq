#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

const embeddedRevision =
  process.env.LOGSEQ_REVISION?.trim() ||
  execFileSync("git", ["describe", "--long", "--always", "--dirty"], {
    cwd: repoRoot,
    encoding: "utf8",
  }).trim();
const releaseSourceSha = process.env.LOGSEQ_RELEASE_SOURCE_SHA?.trim();
if (releaseSourceSha && !/^[0-9a-f]{40}$/.test(releaseSourceSha)) {
  throw new Error(
    "LOGSEQ_RELEASE_SOURCE_SHA must be an exact lowercase 40-hex commit SHA",
  );
}
if (releaseSourceSha && embeddedRevision !== releaseSourceSha) {
  throw new Error(
    `LOGSEQ_REVISION ${embeddedRevision} does not match release source SHA ${releaseSourceSha}`,
  );
}
const expectedRevision = releaseSourceSha || embeddedRevision;

const runtimeFiles = [
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
  const filePath = path.join(repoRoot, relativePath);
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
