#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { loadProjectSigningPolicy } from "../resources/project-updater-signature.mjs";
import { finalizeMacosProjectUpdate } from "./finalize-macos-project-update-core.mjs";
import {
  assertLocalMacosProjectUpdatePublisher,
  loadProjectUpdateSigningKey,
} from "./project-update-keychain.mjs";
import {
  assertSourceRevision,
  assertSourceRevisionAbsent,
  removeSourceRevision,
  writeSourceRevision,
} from "./selfhost-release-provenance.mjs";

const parseArgs = (argv) => {
  const values = new Map();
  for (let index = 0; index < argv.length; index += 2) {
    const key = argv[index];
    const value = argv[index + 1];
    if (!key?.startsWith("--") || !value || values.has(key)) {
      throw new Error(`invalid or duplicate argument near ${key || "<end>"}`);
    }
    values.set(key, value);
  }
  const required = (key) => {
    const value = values.get(key);
    if (!value) throw new Error(`missing ${key}`);
    return value;
  };
  return Object.freeze({
    dir: path.resolve(required("--dir")),
    sourceRevision: values.has("--source-revision")
      ? assertSourceRevision(values.get("--source-revision"))
      : undefined,
    version: required("--version"),
  });
};

const main = async () => {
  assertLocalMacosProjectUpdatePublisher();
  const args = parseArgs(process.argv.slice(2));
  if (args.sourceRevision) assertSourceRevisionAbsent(args.dir);
  const policy = loadProjectSigningPolicy();
  const signingKey = loadProjectUpdateSigningKey(policy);
  const result = await finalizeMacosProjectUpdate({
    ...args,
    finalizeArtifact: args.sourceRevision
      ? () =>
          writeSourceRevision({
            dir: args.dir,
            sourceRevision: args.sourceRevision,
          })
      : undefined,
    policy,
    rollbackArtifact: args.sourceRevision
      ? () => removeSourceRevision(args.dir)
      : undefined,
    signingKey,
  });
  console.log(
    `[project-update-finalize] OK version=${result.version} keyId=${result.keyId} architectures=${result.architectures.join(",")}`,
  );
};

const sameRealFile = (left, right) => {
  try {
    return fs.realpathSync.native(left) === fs.realpathSync.native(right);
  } catch {
    return false;
  }
};

if (
  process.argv[1] &&
  sameRealFile(fileURLToPath(import.meta.url), process.argv[1])
) {
  try {
    await main();
  } catch (error) {
    console.error(
      `[project-update-finalize] RELEASE BLOCKED: ${
        error instanceof Error ? error.message : error
      }`,
    );
    process.exitCode = 1;
  }
}
