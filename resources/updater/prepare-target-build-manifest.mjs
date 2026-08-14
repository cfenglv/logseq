#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const updaterDirectory = path.dirname(fileURLToPath(import.meta.url));
const releasePolicy = JSON.parse(
  fs.readFileSync(path.join(updaterDirectory, "selfhost-release-policy.json"), "utf8"),
);
const signingPolicy = JSON.parse(
  fs.readFileSync(path.join(updaterDirectory, "project-signing-policy.json"), "utf8"),
);
const packageJson = JSON.parse(
  fs.readFileSync(path.join(updaterDirectory, "..", "package.json"), "utf8"),
);

const sourceFullSha = process.env.SELFHOST6_SOURCE_FULL_SHA;
const targetVersion = process.env.SELFHOST6_TARGET_VERSION;
const platform = process.env.SELFHOST6_TARGET_PLATFORM ?? process.platform;
const arch = process.env.SELFHOST6_TARGET_ARCH ?? process.arch;

assert.match(sourceFullSha ?? "", /^[0-9a-f]{40}$/, "SELFHOST6_SOURCE_FULL_SHA must be a full lowercase Git SHA");
assert.ok(
  [releasePolicy.sourceVersion, releasePolicy.syntheticForwardTargetVersion].includes(targetVersion),
  "SELFHOST6_TARGET_VERSION must be the reissued .6 or synthetic .7",
);
assert.equal(packageJson.version, targetVersion, "static package version must equal SELFHOST6_TARGET_VERSION");
assert.ok(
  new Set(["darwin/arm64", "darwin/x64", "win32/x64", "linux/arm64", "linux/x64"])
    .has(`${platform}/${arch}`),
  "target platform/arch is not qualified",
);

const manifest = {
  "schema-version": 1,
  "target-source-full-sha": sourceFullSha,
  "target-version": targetVersion,
  "release-line-id": releasePolicy.releaseLineId,
  platform,
  arch,
  "bundle-identity": releasePolicy.bundleIdentity,
  "signing-key-identity": signingPolicy.keyId,
  "readable-activation-formats": releasePolicy.readableActivationFormats,
  "readable-client-ops-formats": releasePolicy.readableClientOpsFormats,
  "activation-write-format": releasePolicy.activationWriteFormat,
  "client-ops-write-format": releasePolicy.clientOpsWriteFormat,
};
const output = path.join(updaterDirectory, "TARGET_BUILD_MANIFEST.json");
fs.writeFileSync(output, `${JSON.stringify(manifest, null, 2)}\n`, { mode: 0o600 });
process.stdout.write(`${JSON.stringify({ status: "prepared", output, manifest })}\n`);
