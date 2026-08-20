#!/usr/bin/env node

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { parseSelfhostProjectVersion } from "./project-update-signing.mjs";

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
    version: required("--version"),
  });
};

export const verifyUnsignedMacosProjectUpdateCandidate = ({
  arch,
  archive,
  metadata,
  version,
}) => {
  if (!["arm64", "x64"].includes(arch)) {
    throw new Error("unsupported architecture");
  }
  parseSelfhostProjectVersion(version);
  const yaml = fs.readFileSync(metadata, "utf8");
  if (/^projectUpdateSignature:/m.test(yaml)) {
    throw new Error("CI candidate metadata must remain unsigned");
  }
  const yamlVersion = yaml.match(/^version:\s*(.+)$/m)?.[1]?.trim();
  if (yamlVersion !== version) {
    throw new Error(
      `candidate metadata version ${yamlVersion} does not match ${version}`,
    );
  }
  const archiveName = path.basename(archive);
  const expectedName = `Logseq-darwin-${arch}-${version}.zip`;
  if (archiveName !== expectedName) {
    throw new Error(
      `candidate archive ${archiveName} does not match ${expectedName}`,
    );
  }
  const entries = [
    ...yaml.matchAll(
      /^\s*-\s+url:\s*(.+)\n\s+sha512:\s*(.+)\n\s+size:\s*(\d+)$/gm,
    ),
  ].map((match) => ({
    digest: match[2].trim(),
    name: decodeURIComponent(match[1].trim()),
    size: Number(match[3]),
  }));
  const archiveEntries = entries.filter(
    (entry) => entry.name === archiveName,
  );
  assert.equal(
    archiveEntries.length,
    1,
    `candidate metadata must contain exactly one ${archiveName} entry`,
  );
  const [entry] = archiveEntries;
  const archiveBytes = fs.readFileSync(archive);
  const digest = createHash("sha512")
    .update(archiveBytes)
    .digest("base64");
  if (
    entry.size !== archiveBytes.length ||
    entry.digest !== digest
  ) {
    throw new Error("candidate metadata does not match the updater ZIP");
  }
  const topLevelPath = yaml.match(/^path:\s*(.+)$/m)?.[1]?.trim();
  const topLevelDigest = yaml.match(/^sha512:\s*(.+)$/m)?.[1]?.trim();
  if (
    decodeURIComponent(topLevelPath || "") !== archiveName ||
    topLevelDigest !== digest
  ) {
    throw new Error(
      "candidate primary path or SHA-512 does not match the updater ZIP",
    );
  }
  return Object.freeze({ arch, archive: archiveName, version });
};

const isEntrypoint =
  process.argv[1] &&
  path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isEntrypoint) {
  const result = verifyUnsignedMacosProjectUpdateCandidate(
    parseArgs(process.argv.slice(2)),
  );
  console.log(
    `[project-update-candidate] OK version=${result.version} arch=${result.arch} archive=${result.archive}`,
  );
}
