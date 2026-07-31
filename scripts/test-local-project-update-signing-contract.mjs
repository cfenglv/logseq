#!/usr/bin/env node

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const read = (relativePath) =>
  fs.readFileSync(path.join(repoRoot, relativePath), "utf8");

const workflowJob = (workflow, name) => {
  const marker = `  ${name}:`;
  const start = workflow.indexOf(marker);
  assert.notEqual(start, -1, `workflow job ${name} is missing`);
  const tail = workflow.slice(start + marker.length);
  const end = tail.search(/^  [a-zA-Z0-9_-]+:/m);
  return end === -1 ? tail : tail.slice(0, end);
};

const cases = [];
const addCase = (name, run) => cases.push([name, run]);

addCase("production signer uses only the fixed login Keychain identity", () => {
  const signer = read("scripts/sign-macos-project-update.mjs");
  const keychain = read("scripts/project-update-keychain.mjs");
  assert.doesNotMatch(
    signer,
    /LOGSEQ_MACOS_UPDATE_ED25519_PRIVATE_KEY_BASE64/,
  );
  assert.doesNotMatch(
    keychain,
    /LOGSEQ_MACOS_UPDATE_ED25519_PRIVATE_KEY_BASE64/,
  );
  assert.match(keychain, /\/usr\/bin\/security/);
  assert.match(keychain, /find-generic-password/);
  assert.match(keychain, /login\.keychain-db/);
  assert.match(
    keychain,
    /com\.logseq\.selfhost\.project-update-signing\.ed25519-pkcs8-base64/,
  );
  assert.match(keychain, /policy\.keyId/);
  assert.doesNotMatch(
    keychain,
    /add-generic-password|list-keychains|default-keychain|add-trusted-cert|security\s+import/,
  );
});

addCase("legacy environment injection is rejected before signing in CI", () => {
  const tempRoot = fs.mkdtempSync(
    path.join(os.tmpdir(), "logseq-local-project-signing-contract-"),
  );
  try {
    const archive = path.join(tempRoot, "update.zip");
    const metadata = path.join(tempRoot, "latest.yml");
    fs.writeFileSync(archive, "candidate");
    fs.writeFileSync(
      metadata,
      "version: 2.0.1-selfhost.6\npath: update.zip\n",
    );
    const before = fs.readFileSync(metadata);
    const result = spawnSync(
      process.execPath,
      [
        path.join(repoRoot, "scripts", "sign-macos-project-update.mjs"),
        "--arch",
        "arm64",
        "--version",
        "2.0.1-selfhost.6",
        "--archive",
        archive,
        "--metadata",
        metadata,
      ],
      {
        encoding: "utf8",
        env: {
          ...process.env,
          CI: "true",
          LOGSEQ_MACOS_UPDATE_ED25519_PRIVATE_KEY_BASE64:
            "legacy-environment-value-must-not-be-consumed",
        },
      },
    );
    assert.notEqual(result.status, 0);
    assert.match(
      `${result.stdout}${result.stderr}`,
      /local macOS publisher only|refuses CI/i,
    );
    assert.deepEqual(fs.readFileSync(metadata), before);
  } finally {
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
});

addCase("symlink and realpath entrypoints still execute the signer gate", () => {
  const tempRoot = fs.mkdtempSync(
    path.join(os.tmpdir(), "logseq-project-signer-entrypoint-"),
  );
  try {
    const signerLink = path.join(tempRoot, "sign-project-update.mjs");
    fs.symlinkSync(
      path.join(repoRoot, "scripts", "sign-macos-project-update.mjs"),
      signerLink,
    );
    const archive = path.join(tempRoot, "update.zip");
    const metadata = path.join(tempRoot, "latest.yml");
    fs.writeFileSync(archive, "candidate");
    fs.writeFileSync(
      metadata,
      "version: 2.0.1-selfhost.6\npath: update.zip\n",
    );
    const before = fs.readFileSync(metadata);
    const result = spawnSync(
      process.execPath,
      [
        signerLink,
        "--arch",
        "arm64",
        "--version",
        "2.0.1-selfhost.6",
        "--archive",
        archive,
        "--metadata",
        metadata,
      ],
      {
        encoding: "utf8",
        env: { ...process.env, CI: "true" },
      },
    );
    assert.notEqual(
      result.status,
      0,
      "signer silently skipped its entrypoint through a symlink",
    );
    assert.match(
      `${result.stdout}${result.stderr}`,
      /local macOS publisher only|refuses CI/i,
    );
    assert.deepEqual(fs.readFileSync(metadata), before);
  } finally {
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
});

addCase("GitHub Actions builds candidates but cannot sign or publish selfhost", () => {
  const workflow = read(".github/workflows/build-desktop-release.yml");
  assert.doesNotMatch(
    workflow,
    /LOGSEQ_MACOS_UPDATE_ED25519_PRIVATE_KEY_BASE64/,
  );
  assert.doesNotMatch(workflow, /secrets\.[A-Z0-9_]*ED25519[A-Z0-9_]*/);
  for (const jobName of ["build-macos-x64", "build-macos-arm64"]) {
    const job = workflowJob(workflow, jobName);
    assert.doesNotMatch(job, /sign-macos-project-update\.mjs/);
    assert.match(job, /project update candidate/i);
  }
  for (const jobName of ["nightly-release", "release"]) {
    const job = workflowJob(workflow, jobName);
    assert.match(
      job,
      /!contains\(needs\.release-assets-preflight\.outputs\.version,\s*'-selfhost\.'\)/,
      `${jobName} can publish unsigned selfhost metadata`,
    );
  }
});

addCase("local finalizer signs and verifies both macOS candidates", () => {
  const packageJson = JSON.parse(read("package.json"));
  assert.equal(
    packageJson.scripts["project-update:finalize-local-macos-candidates"],
    "node ./scripts/finalize-local-macos-project-update.mjs",
  );
  const finalizer = read("scripts/finalize-local-macos-project-update.mjs");
  assert.match(finalizer, /for \(const arch of \["arm64", "x64"\]\)/);
  assert.match(finalizer, /signMacosProjectUpdate/);
  assert.match(finalizer, /verifyProjectSignedMacosUpdate/);
  assert.match(finalizer, /verify-desktop-release-assets\.mjs/);
  assert.doesNotMatch(
    finalizer,
    /PRIVATE_KEY|privateKeyBase64|add-generic-password/,
  );
});

addCase("publisher documentation keeps private material local", () => {
  const guide = read("docs/selfhost-sync.md");
  assert.match(guide, /project-update:finalize-local-macos-candidates/);
  assert.match(guide, /Keychain Access/);
  assert.match(
    guide,
    /com\.logseq\.selfhost\.project-update-signing\.ed25519-pkcs8-base64/,
  );
  assert.match(guide, /account[\s\S]{0,160}keyId/i);
  assert.doesNotMatch(
    guide,
    /LOGSEQ_MACOS_UPDATE_ED25519_PRIVATE_KEY_BASE64/,
  );
});

let passed = 0;
let failed = 0;
for (const [name, run] of cases) {
  try {
    await run();
    passed += 1;
    console.log(`[local-project-update-signing-contract] PASS ${name}`);
  } catch (error) {
    failed += 1;
    console.error(
      `[local-project-update-signing-contract] FAIL ${name}: ${
        error instanceof Error ? error.stack || error.message : error
      }`,
    );
  }
}

console.log(
  `[local-project-update-signing-contract] SUMMARY passed=${passed} failed=${failed} total=${cases.length}`,
);
if (failed > 0) process.exit(1);
