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

  const previousRevision = process.env.LOGSEQ_REVISION;
  process.env.LOGSEQ_REVISION = "contract-revision";
  try {
    verifyDesktopRuntimeRevisions(
      { packager: { projectDir: fixtureStaticDir } },
      {
        repoRoot: fixtureRoot,
        verifierPath: fixtureVerifier,
        stdio: "pipe",
      },
    );
  } finally {
    if (previousRevision === undefined) {
      delete process.env.LOGSEQ_REVISION;
    } else {
      process.env.LOGSEQ_REVISION = previousRevision;
    }
  }

  assert.equal(
    fs.readFileSync(path.join(fixtureRoot, "verified.txt"), "utf8"),
    "contract-revision",
    "the pre-pack hook should execute the canonical verifier from the repository root with the build environment",
  );

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
  fs.rmSync(fixtureRoot, { recursive: true, force: true });
}

console.log("Desktop runtime packaging contract checks passed.");
