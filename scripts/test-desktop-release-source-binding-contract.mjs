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

const sourceRevision = "c5820a5ec1a63f505e59ca604476d2bd23df3b70";
const staleRuntimeRevision = "eaeb51ece7";
const workflowRevision = "1111111111111111111111111111111111111111";

const withVerifierFixture = (runtimeRevision, f) => {
  const root = fs.mkdtempSync(
    path.join(os.tmpdir(), "logseq-release-source-binding-"),
  );
  try {
    const scriptsDir = path.join(root, "scripts");
    fs.mkdirSync(scriptsDir, { recursive: true });
    fs.copyFileSync(
      path.join(repoRoot, "scripts", "verify-desktop-runtime-revisions.mjs"),
      path.join(scriptsDir, "verify-desktop-runtime-revisions.mjs"),
    );
    for (const relativePath of runtimeFiles) {
      const destination = path.join(root, relativePath);
      fs.mkdirSync(path.dirname(destination), { recursive: true });
      fs.writeFileSync(
        destination,
        `globalThis.LOGSEQ_TEST_REVISION = ${JSON.stringify(runtimeRevision)};\n`,
      );
    }
    f(root);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
};

const runVerifier = (
  root,
  runtimeRevision,
  {
    omitReleaseSourceSha = false,
    releaseSourceSha = sourceRevision,
  } = {},
) => {
  const env = {
    ...process.env,
    // The workflow commit can differ from the independently selected
    // release source. Runtime self-reporting and GITHUB_SHA must not
    // override LOGSEQ_RELEASE_SOURCE_SHA.
    GITHUB_SHA: workflowRevision,
    LOGSEQ_REVISION: runtimeRevision,
  };
  delete env.LOGSEQ_RELEASE_SOURCE_SHA;
  if (!omitReleaseSourceSha) {
    env.LOGSEQ_RELEASE_SOURCE_SHA = releaseSourceSha;
  }
  const result = spawnSync(
    process.execPath,
    [path.join(root, "scripts", "verify-desktop-runtime-revisions.mjs")],
    {
      cwd: root,
      encoding: "utf8",
      env,
      shell: false,
    },
  );
  return {
    ...result,
    releaseSourceShaWasPresent: Object.hasOwn(
      env,
      "LOGSEQ_RELEASE_SOURCE_SHA",
    ),
  };
};

test("desktop runtime verification is bound to the exact release source SHA", () => {
  withVerifierFixture(staleRuntimeRevision, (root) => {
    const result = runVerifier(root, staleRuntimeRevision);
    assert.notEqual(
      result.status,
      0,
      [
        "a self-reported stale runtime revision must not override the release source SHA",
        `release source: ${sourceRevision}`,
        `embedded runtime: ${staleRuntimeRevision}`,
        `workflow revision: ${workflowRevision}`,
        result.stdout,
        result.stderr,
      ].join("\n"),
    );
    assert.match(
      `${result.stdout}\n${result.stderr}`,
      /source|revision/i,
      "the release gate should identify the source/revision mismatch",
    );
  });
});

test("desktop runtime verification fails closed without an exact release source SHA", () => {
  withVerifierFixture(sourceRevision, (root) => {
    const result = runVerifier(root, sourceRevision, {
      omitReleaseSourceSha: true,
    });
    assert.equal(
      result.releaseSourceShaWasPresent,
      false,
      "the omission fixture accidentally restored LOGSEQ_RELEASE_SOURCE_SHA",
    );
    assert.notEqual(
      result.status,
      0,
      [
        "release packaging must not fall back to a self-reported revision or local git state",
        "LOGSEQ_RELEASE_SOURCE_SHA was intentionally omitted",
        result.stdout,
        result.stderr,
      ].join("\n"),
    );
    assert.match(
      `${result.stdout}\n${result.stderr}`,
      /LOGSEQ_RELEASE_SOURCE_SHA|release source/i,
      "the closed gate should identify the missing exact release source SHA",
    );
  });
});

test("desktop runtime verification rejects a non-exact release source revision", () => {
  withVerifierFixture(sourceRevision, (root) => {
    const result = runVerifier(root, sourceRevision, {
      releaseSourceSha: sourceRevision.slice(0, 12),
    });
    assert.notEqual(result.status, 0, "a short source revision was accepted");
    assert.match(
      `${result.stdout}\n${result.stderr}`,
      /exact lowercase 40-hex commit SHA/,
      "the release gate should require the exact immutable commit identifier",
    );
  });
});

test("desktop runtime verification accepts runtimes built from the exact release source", () => {
  withVerifierFixture(sourceRevision, (root) => {
    const result = runVerifier(root, sourceRevision);
    assert.equal(
      result.status,
      0,
      `matching release source was rejected:\n${result.stdout}\n${result.stderr}`,
    );
  });
});
