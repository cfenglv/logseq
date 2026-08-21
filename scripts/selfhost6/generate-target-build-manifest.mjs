#!/usr/bin/env node

import assert from "node:assert/strict";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  buildTargetManifest,
  readReleasePolicy,
  writeTargetManifest,
} from "../lib/selfhost6-release-identity.mjs";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");

function parseArgs(argv) {
  const options = {
    policy: path.join(repoRoot, "resources/updater/selfhost-release-policy.json"),
    output: path.join(repoRoot, "static/updater/TARGET_BUILD_MANIFEST.json"),
  };
  for (let index = 0; index < argv.length; index += 2) {
    const flag = argv[index];
    const value = argv[index + 1];
    assert.ok(value, `${flag} requires a value`);
    if (flag === "--source-full-sha") options.sourceFullSha = value;
    else if (flag === "--target-version") options.targetVersion = value;
    else if (flag === "--platform") options.platform = value;
    else if (flag === "--arch") options.arch = value;
    else if (flag === "--signing-key-identity") options.signingKeyIdentity = value;
    else if (flag === "--policy") options.policy = path.resolve(value);
    else if (flag === "--output") options.output = path.resolve(value);
    else throw new Error(`unknown argument: ${flag}`);
  }
  for (const field of ["sourceFullSha", "targetVersion", "platform", "arch", "signingKeyIdentity"]) {
    assert.ok(options[field], `--${field.replace(/[A-Z]/g, (letter) => `-${letter.toLowerCase()}`)} is required`);
  }
  return options;
}

const options = parseArgs(process.argv.slice(2));
const policy = readReleasePolicy(options.policy);
const manifest = buildTargetManifest({ ...options, policy });
writeTargetManifest(options.output, manifest);
process.stdout.write(`${JSON.stringify({ status: "ok", output: options.output, identity: manifest })}\n`);
