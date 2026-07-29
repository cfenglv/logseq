#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { verifyProjectSignatureRuntime } from "../resources/packaged-resource-contract.mjs";

const temporaryRoot = fs.mkdtempSync(
  path.join(os.tmpdir(), "logseq-packaged-project-signature-"),
);
const targets = [
  ["darwin", "x64"],
  ["darwin", "arm64"],
  ["win32", "x64"],
  ["win32", "arm64"],
  ["linux", "x64"],
  ["linux", "arm64"],
];
let passed = 0;

try {
  for (const [platform, arch] of targets) {
    const targetRoot = path.join(temporaryRoot, `${platform}-${arch}`);
    const resourcesDir = path.join(targetRoot, "packaged");
    const stagedResourcesDir = path.join(targetRoot, "staged");
    const packagedRuntime = path.join(
      resourcesDir,
      "project-updater-signature.mjs",
    );
    const stagedRuntime = path.join(
      stagedResourcesDir,
      "project-updater-signature.mjs",
    );
    fs.mkdirSync(resourcesDir, { recursive: true });
    fs.mkdirSync(stagedResourcesDir, { recursive: true });
    fs.writeFileSync(stagedRuntime, `runtime-${platform}-${arch}\n`);
    fs.copyFileSync(stagedRuntime, packagedRuntime);

    const verify = () =>
      verifyProjectSignatureRuntime({
        arch,
        platform,
        resourcesDir,
        stagedResourcesDir,
      });
    assert.doesNotThrow(verify);

    fs.writeFileSync(packagedRuntime, "tampered\n");
    assert.throws(
      verify,
      /does not match the staged release resource/,
      `${platform}/${arch} must reject a tampered packaged runtime`,
    );

    fs.rmSync(packagedRuntime);
    assert.throws(
      verify,
      /missing packaged project updater signature runtime/,
      `${platform}/${arch} must reject a missing packaged runtime`,
    );

    fs.mkdirSync(packagedRuntime);
    assert.throws(
      verify,
      /is not a regular file/,
      `${platform}/${arch} must reject a non-file packaged runtime`,
    );

    fs.rmSync(packagedRuntime, { recursive: true });
    fs.copyFileSync(stagedRuntime, packagedRuntime);
    fs.rmSync(stagedRuntime);
    assert.throws(
      verify,
      /missing packaged staged project updater signature runtime/,
      `${platform}/${arch} must reject a missing staged runtime`,
    );

    fs.mkdirSync(stagedRuntime);
    assert.throws(
      verify,
      /is not a regular file/,
      `${platform}/${arch} must reject a non-file staged runtime`,
    );

    passed += 1;
    console.log(
      `[packaged-project-signature-runtime] PASS ${platform}/${arch}`,
    );
  }
} finally {
  fs.rmSync(temporaryRoot, { recursive: true, force: true });
}

console.log(
  `[packaged-project-signature-runtime] SUMMARY passed=${passed} failed=0 total=${targets.length}`,
);
