#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { loadProjectSigningPolicy } from "../resources/project-updater-signature.mjs";
import { finalizeMacosProjectUpdate } from "./finalize-macos-project-update-core.mjs";
import {
  assertGithubProjectUpdateSigningContext,
  loadGithubProjectUpdateSigningKey,
} from "./project-update-github-actions.mjs";

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
    version: required("--version"),
  });
};

const main = async () => {
  const args = parseArgs(process.argv.slice(2));
  assertGithubProjectUpdateSigningContext(args);
  const policy = loadProjectSigningPolicy();
  const signingKey = loadGithubProjectUpdateSigningKey(policy);
  const result = await finalizeMacosProjectUpdate({
    ...args,
    policy,
    signingKey,
  });
  console.log(
    `[github-project-update-finalize] OK version=${result.version} keyId=${result.keyId} architectures=${result.architectures.join(",")}`,
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
      `[github-project-update-finalize] RELEASE BLOCKED: ${
        error instanceof Error ? error.message : error
      }`,
    );
    process.exitCode = 1;
  }
}
