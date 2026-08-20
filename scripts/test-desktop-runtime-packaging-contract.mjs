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
const packagedVerifierSource = fs.readFileSync(
  path.join(repoRoot, "resources", "verify-packaged-desktop.mjs"),
  "utf8",
);

assert.equal(
  typeof verifyDesktopRuntimeRevisions,
  "function",
  "the Electron builder hook should expose its verifier for contract tests",
);
assert.match(
  packagedVerifierSource,
  /stagedRevisionPath\s*=\s*path\.join\(stagedResourcesDir,\s*["']RUNTIME_REVISION["']\)/,
  "the packaged verifier should resolve the sealed artifact revision without repository metadata",
);
assert.match(
  packagedVerifierSource,
  /process\.env\.LOGSEQ_REVISION\?\.trim\(\)\s*\|\|\s*stagedRevision\s*\|\|/,
  "the packaged verifier should prefer an explicit revision, then the sealed artifact revision",
);

const fixtureRoot = fs.mkdtempSync(
  path.join(os.tmpdir(), "logseq-desktop-runtime-contract-"),
);

try {
  const fixtureStaticDir = path.join(fixtureRoot, "static");
  const fixtureVerifier = path.join(
    fixtureStaticDir,
    "verify-desktop-runtime-revisions.mjs",
  );
  fs.mkdirSync(fixtureStaticDir);
  fs.writeFileSync(
    path.join(fixtureStaticDir, "RUNTIME_REVISION"),
    "contract-revision\n",
  );

  fs.writeFileSync(
    fixtureVerifier,
    [
      'import fs from "node:fs";',
      'import path from "node:path";',
      'fs.writeFileSync(path.join(process.cwd(), "verified.txt"), `${process.env.LOGSEQ_REVISION || "unset"}\n${process.env.LOGSEQ_DESKTOP_RUNTIME_ROOT || "unset"}`);',
    ].join("\n"),
  );

  verifyDesktopRuntimeRevisions(
    { packager: { projectDir: fixtureStaticDir } },
    { appDir: fixtureStaticDir, stdio: "pipe" },
  );

  assert.equal(
    fs.readFileSync(path.join(fixtureStaticDir, "verified.txt"), "utf8"),
    `contract-revision\n${fixtureStaticDir}`,
    "the pre-pack hook should execute the verifier from the isolated static artifact with its sealed revision",
  );

  fs.writeFileSync(fixtureVerifier, "process.exitCode = 17;\n");
  assert.throws(
    () =>
      verifyDesktopRuntimeRevisions(
        { packager: { projectDir: fixtureStaticDir } },
        { appDir: fixtureStaticDir, stdio: "pipe" },
      ),
    /verification failed before packaging \(status=17 signal=none\)/,
    "a stale or incomplete runtime verifier result should fail packaging",
  );

  assert.throws(
    () =>
      verifyDesktopRuntimeRevisions(
        { appDir: fixtureRoot },
        { appDir: fixtureStaticDir, stdio: "pipe" },
      ),
    /desktop packaging appDir must be/,
    "the hook should reject an unexpected application directory",
  );
} finally {
  fs.rmSync(fixtureRoot, { recursive: true, force: true });
}

console.log("Desktop runtime packaging contract checks passed.");
