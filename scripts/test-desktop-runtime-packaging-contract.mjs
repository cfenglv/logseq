#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const require = createRequire(import.meta.url);
const beforePack = require(
  path.join(
    repoRoot,
    "resources",
    "electron-builder-verify-runtime-revisions.cjs",
  ),
);
const { verifyDesktopRuntimeRevisions } = beforePack;

assert.equal(
  typeof verifyDesktopRuntimeRevisions,
  "function",
  "the Electron builder hook should expose its verifier for contract tests",
);

const fixtureRoot = fs.mkdtempSync(
  path.join(os.tmpdir(), "logseq-desktop-runtime-contract-"),
);
const previousRevision = process.env.LOGSEQ_REVISION;
const previousReleaseSourceSha = process.env.LOGSEQ_RELEASE_SOURCE_SHA;

try {
  const fixtureStaticDir = path.join(fixtureRoot, "static");
  const fixtureScriptsDir = path.join(fixtureRoot, "scripts");
  const fixtureVerifier = path.join(
    fixtureScriptsDir,
    "verify-desktop-runtime-revisions.mjs",
  );
  fs.mkdirSync(fixtureStaticDir);
  fs.mkdirSync(fixtureScriptsDir);

  fs.writeFileSync(
    fixtureVerifier,
    [
      'import fs from "node:fs";',
      'import path from "node:path";',
      'fs.writeFileSync(path.join(process.cwd(), "verified.txt"), process.env.LOGSEQ_REVISION || "unset");',
    ].join("\n"),
  );

  const releaseSourceSha = "a".repeat(40);
  process.env.LOGSEQ_REVISION = releaseSourceSha;
  process.env.LOGSEQ_RELEASE_SOURCE_SHA = releaseSourceSha;
  verifyDesktopRuntimeRevisions(
    { packager: { projectDir: fixtureStaticDir } },
    {
      repoRoot: fixtureRoot,
      verifierPath: fixtureVerifier,
      stdio: "pipe",
    },
  );

  assert.equal(
    fs.readFileSync(path.join(fixtureRoot, "verified.txt"), "utf8"),
    releaseSourceSha,
    "the pre-pack hook should execute the canonical verifier from the repository root with the build environment",
  );

  const currentReleaseSourceSha = process.env.LOGSEQ_RELEASE_SOURCE_SHA;
  delete process.env.LOGSEQ_RELEASE_SOURCE_SHA;
  try {
    assert.throws(
      () =>
        verifyDesktopRuntimeRevisions(
          { packager: { projectDir: fixtureStaticDir } },
          {
            repoRoot: fixtureRoot,
            verifierPath: fixtureVerifier,
            stdio: "pipe",
          },
        ),
      /requires LOGSEQ_RELEASE_SOURCE_SHA as an exact lowercase 40-hex commit SHA/,
      "Electron packaging must fail closed when the exact release source SHA is absent",
    );
  } finally {
    if (currentReleaseSourceSha === undefined) {
      delete process.env.LOGSEQ_RELEASE_SOURCE_SHA;
    } else {
      process.env.LOGSEQ_RELEASE_SOURCE_SHA = currentReleaseSourceSha;
    }
  }

  fs.writeFileSync(fixtureVerifier, "process.exitCode = 17;\n");
  assert.throws(
    () =>
      verifyDesktopRuntimeRevisions(
        { packager: { projectDir: fixtureStaticDir } },
        {
          repoRoot: fixtureRoot,
          verifierPath: fixtureVerifier,
          stdio: "pipe",
        },
      ),
    /verification failed before packaging \(status=17 signal=none\)/,
    "a stale or incomplete runtime verifier result should fail packaging",
  );

  assert.throws(
    () =>
      verifyDesktopRuntimeRevisions(
        { appDir: fixtureRoot },
        {
          repoRoot: fixtureRoot,
          verifierPath: fixtureVerifier,
          stdio: "pipe",
        },
      ),
    /desktop packaging appDir must be/,
    "the hook should reject an unexpected application directory",
  );
} finally {
  if (previousRevision === undefined) {
    delete process.env.LOGSEQ_REVISION;
  } else {
    process.env.LOGSEQ_REVISION = previousRevision;
  }
  if (previousReleaseSourceSha === undefined) {
    delete process.env.LOGSEQ_RELEASE_SOURCE_SHA;
  } else {
    process.env.LOGSEQ_RELEASE_SOURCE_SHA = previousReleaseSourceSha;
  }
  fs.rmSync(fixtureRoot, { recursive: true, force: true });
}

console.log("Desktop runtime packaging contract checks passed.");
