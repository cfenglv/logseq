#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  loadProjectSigningPolicy,
  parseSelfhostProjectVersion,
} from "../resources/project-updater-signature.mjs";
import {
  assertLocalMacosProjectUpdatePublisher,
  loadProjectUpdateSigningKey,
} from "./project-update-keychain.mjs";
import { createProjectUpdateSignature } from "./project-update-signer-core.mjs";

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
    arch: required("--arch"),
    archive: path.resolve(required("--archive")),
    metadata: path.resolve(required("--metadata")),
    signatureOutput: values.get("--signature-output")
      ? path.resolve(values.get("--signature-output"))
      : undefined,
    version: required("--version"),
  });
};

const atomicWrite = (destination, contents) => {
  const temporary = path.join(
    path.dirname(destination),
    `.${path.basename(destination)}.${process.pid}.tmp`,
  );
  try {
    fs.writeFileSync(temporary, contents, {
      flag: "wx",
      mode: fs.statSync(destination).mode,
    });
    fs.renameSync(temporary, destination);
  } finally {
    fs.rmSync(temporary, { force: true });
  }
};

export const signMacosProjectUpdate = async ({
  arch,
  archive,
  metadata,
  privateKey,
  policy,
  signatureOutput,
  version,
}) => {
  const result = await createProjectUpdateSignature({
    arch,
    archive,
    metadata,
    policy,
    privateKey,
    version,
  });
  if (signatureOutput) {
    fs.writeFileSync(
      signatureOutput,
      `${JSON.stringify(
        {
          algorithm: policy.algorithm,
          arch,
          bundleId: policy.bundleIdentifier,
          keyId: policy.keyId,
          sha512: result.sha512,
          signature: result.signature,
          size: result.size,
          version,
        },
        null,
        2,
      )}\n`,
      { flag: "wx", mode: 0o644 },
    );
  }
  atomicWrite(metadata, result.metadata);
  return result;
};

const sameRealFile = (left, right) => {
  try {
    return (
      fs.realpathSync.native(left) ===
      fs.realpathSync.native(right)
    );
  } catch {
    return false;
  }
};

const isEntrypoint =
  process.argv[1] &&
  sameRealFile(
    path.resolve(process.argv[1]),
    fileURLToPath(import.meta.url),
  );
if (isEntrypoint) {
  try {
    const args = parseArgs(process.argv.slice(2));
    if (!["arm64", "x64"].includes(args.arch)) {
      throw new Error("unsupported architecture");
    }
    parseSelfhostProjectVersion(args.version);
    assertLocalMacosProjectUpdatePublisher();
    const policy = loadProjectSigningPolicy();
    const privateKey = loadProjectUpdateSigningKey(policy);
    const result = await signMacosProjectUpdate({
      ...args,
      policy,
      privateKey,
    });
    console.log(
      `[project-update-sign] OK version=${result.version} arch=${result.arch} keyId=${result.keyId} sha512=${result.sha512}`,
    );
  } catch (error) {
    console.error(
      `[project-update-sign] RELEASE BLOCKED: ${
        error instanceof Error ? error.message : error
      }`,
    );
    process.exitCode = 1;
  }
}
