#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { readReleasePolicy } from "../lib/selfhost6-release-identity.mjs";
import { buildPromotion, writePromotion } from "../lib/selfhost6-update-feed.mjs";
import { loadProjectSigningPolicy } from "../../resources/updater/project-update-signature.mjs";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const separator = process.argv.indexOf("--descriptors");
assert.ok(separator >= 0, "--descriptors is required");
const before = process.argv.slice(2, separator);
assert.deepEqual(before.slice(0, 2), ["--artifact-directory", before[1]]);
assert.deepEqual(before.slice(2, 4), ["--output-directory", before[3]]);
assert.deepEqual(before.slice(4, 6), ["--expected-target-version", before[5]]);
const artifactDirectory = path.resolve(before[1]);
const outputDirectory = path.resolve(before[3]);
const expectedTargetVersion = before[5];
const descriptorPaths = process.argv.slice(separator + 1).map((entry) => path.resolve(entry));
assert.ok(descriptorPaths.length > 0, "at least one descriptor path is required");

const releasePolicy = readReleasePolicy(
  path.join(repoRoot, "resources/updater/selfhost-release-policy.json"),
);
const signingPolicy = loadProjectSigningPolicy();
const descriptors = descriptorPaths.map((entry) => JSON.parse(fs.readFileSync(entry, "utf8")));
const metadataByFile = buildPromotion({
  descriptors,
  artifactDirectory,
  expectedTargetVersion,
  releasePolicy,
  signingPolicy,
});
writePromotion({ metadataByFile, outputDirectory });
process.stdout.write(`${JSON.stringify({ status: "promotion-staged", files: [...metadataByFile.keys()] })}\n`);
