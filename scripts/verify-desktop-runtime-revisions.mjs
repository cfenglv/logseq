#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

const releaseSourceSha = process.env.LOGSEQ_RELEASE_SOURCE_SHA;
if (!releaseSourceSha || !/^[0-9a-f]{40}$/.test(releaseSourceSha)) {
  throw new Error(
    "LOGSEQ_RELEASE_SOURCE_SHA must be an exact lowercase 40-hex commit SHA",
  );
}
const embeddedRevision = process.env.LOGSEQ_REVISION;
if (!embeddedRevision || !/^[0-9a-f]{40}$/.test(embeddedRevision)) {
  throw new Error("LOGSEQ_REVISION must be an exact lowercase 40-hex commit SHA");
}
if (embeddedRevision !== releaseSourceSha) {
  throw new Error(
    `LOGSEQ_REVISION ${embeddedRevision} does not match release source SHA ${releaseSourceSha}`,
  );
}
const expectedRevision = releaseSourceSha;

const containsExactRevision = (source, revision) => {
  const asciiHex = (character) => character !== undefined && /[0-9a-f]/i.test(character);
  let offset = 0;
  while (offset <= source.length - revision.length) {
    const index = source.indexOf(revision, offset);
    if (index === -1) return false;
    if (
      !asciiHex(source[index - 1]) &&
      !asciiHex(source[index + revision.length])
    ) {
      return true;
    }
    offset = index + 1;
  }
  return false;
};

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
  if (!containsExactRevision(source, expectedRevision)) {
    throw new Error(
      `${relativePath} does not contain the exact revision token ${expectedRevision}; rebuild all desktop runtimes before packaging`,
    );
  }

  console.log(`[desktop-runtime-revision] ${relativePath}: ${expectedRevision}`);
}
