#!/usr/bin/env node

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const sourceRevision = "a".repeat(40);
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

const withFixture = (runtimeText, body) => {
  const root = fs.mkdtempSync(
    path.join(os.tmpdir(), "logseq-runtime-revision-token-"),
  );
  try {
    fs.mkdirSync(path.join(root, "scripts"));
    fs.copyFileSync(
      path.join(repoRoot, "scripts", "verify-desktop-runtime-revisions.mjs"),
      path.join(root, "scripts", "verify-desktop-runtime-revisions.mjs"),
    );
    for (const relativePath of runtimeFiles) {
      const target = path.join(root, relativePath);
      fs.mkdirSync(path.dirname(target), { recursive: true });
      fs.writeFileSync(target, runtimeText);
    }
    body(root);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
};

const runVerifier = (root, environment) => {
  const env = { ...process.env, ...environment };
  delete env.NODE_OPTIONS;
  const result = spawnSync(
    process.execPath,
    [path.join(root, "scripts", "verify-desktop-runtime-revisions.mjs")],
    {
      cwd: root,
      encoding: "utf8",
      env,
      shell: false,
      stdio: ["ignore", "pipe", "pipe"],
    },
  );
  if (result.error) throw result.error;
  return {
    output: `${result.stdout ?? ""}${result.stderr ?? ""}`,
    status: result.status,
  };
};

test("runtime verifier accepts an exact delimited revision token", () => {
  withFixture(`const revision = "${sourceRevision}";\n`, (root) => {
    const result = runVerifier(root, {
      LOGSEQ_RELEASE_SOURCE_SHA: sourceRevision,
      LOGSEQ_REVISION: sourceRevision,
    });
    assert.equal(result.status, 0, result.output);
  });
});

test("runtime verifier rejects a SHA that is only a longer token substring", () => {
  withFixture(`const revision = "f${sourceRevision}0";\n`, (root) => {
    const result = runVerifier(root, {
      LOGSEQ_RELEASE_SOURCE_SHA: sourceRevision,
      LOGSEQ_REVISION: sourceRevision,
    });
    assert.notEqual(result.status, 0, result.output);
    assert.match(result.output, /exact revision token|does not contain/i);
  });
});

test("runtime verifier rejects whitespace around either supplied identity", () => {
  withFixture(`const revision = "${sourceRevision}";\n`, (root) => {
    for (const environment of [
      {
        LOGSEQ_RELEASE_SOURCE_SHA: ` ${sourceRevision}`,
        LOGSEQ_REVISION: sourceRevision,
      },
      {
        LOGSEQ_RELEASE_SOURCE_SHA: sourceRevision,
        LOGSEQ_REVISION: `${sourceRevision} `,
      },
    ]) {
      const result = runVerifier(root, environment);
      assert.notEqual(result.status, 0, result.output);
      assert.match(result.output, /exact lowercase 40-hex/i);
    }
  });
});
