#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { readReleasePolicy } from "../lib/selfhost6-release-identity.mjs";
import {
  finalizeTraditionalMetadata,
  traditionalChannelFileName,
  writeTraditionalMetadata,
} from "../lib/selfhost6-update-feed.mjs";
import { loadProjectSigningPolicy } from "../../resources/updater/project-update-signature.mjs";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");

function parseArgs(argv) {
  const separator = argv.indexOf("--descriptors");
  assert.ok(separator >= 0, "--descriptors is required");
  const result = { descriptorPaths: argv.slice(separator + 1).map((value) => path.resolve(value)) };
  assert.ok(result.descriptorPaths.length > 0, "at least one descriptor is required");
  const values = argv.slice(0, separator);
  for (let index = 0; index < values.length; index += 2) {
    const flag = values[index];
    const value = values[index + 1];
    assert.ok(value, `${flag} requires a value`);
    if (flag === "--metadata-input") result.metadataInput = path.resolve(value);
    else if (flag === "--artifact-directory") result.artifactDirectory = path.resolve(value);
    else if (flag === "--output") result.output = path.resolve(value);
    else if (flag === "--expected-target-version") result.expectedTargetVersion = value;
    else if (flag === "--platform") result.platform = value;
    else if (flag === "--arch") result.arch = value;
    else throw new Error(`unknown argument: ${flag}`);
  }
  for (const field of [
    "metadataInput",
    "artifactDirectory",
    "output",
    "expectedTargetVersion",
    "platform",
    "arch",
  ]) assert.ok(result[field], `${field} is required`);
  return result;
}

const options = parseArgs(process.argv.slice(2));
const releasePolicy = readReleasePolicy(
  path.join(repoRoot, "resources/updater/selfhost-release-policy.json"),
);
const signingPolicy = loadProjectSigningPolicy();
assert.equal(
  path.basename(options.output),
  traditionalChannelFileName(releasePolicy, options.platform, options.arch),
  "traditional metadata output name differs from the qualified channel",
);
const metadata = finalizeTraditionalMetadata({
  metadataText: fs.readFileSync(options.metadataInput, "utf8"),
  artifactDirectory: options.artifactDirectory,
  descriptors: options.descriptorPaths.map((value) => JSON.parse(fs.readFileSync(value, "utf8"))),
  expectedTargetVersion: options.expectedTargetVersion,
  platform: options.platform,
  arch: options.arch,
  releasePolicy,
  signingPolicy,
});
writeTraditionalMetadata({ metadata, outputPath: options.output });
process.stdout.write(`${JSON.stringify({ status: "traditional-metadata-finalized" })}\n`);
