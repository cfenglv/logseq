#!/usr/bin/env node

import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

import { bridgeImmutableAssetNames } from "../lib/selfhost6-existing-release-promotion.mjs";
import { readReleasePolicy } from "../lib/selfhost6-release-identity.mjs";
import { verifyArtifactDescriptor } from "../lib/selfhost6-update-feed.mjs";
import { loadProjectSigningPolicy } from "../../resources/updater/project-update-signature.mjs";
import { validateTargetBuildManifest } from "../../resources/updater/target-build-manifest.mjs";
import { buildArchiveReader } from "../lib/windows-archive-reader.mjs";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const bridgeVersion = "2.0.1-selfhost.7";
const shaPattern = /^[0-9a-f]{40}$/;
const digestPattern = /^sha256:[0-9a-f]{64}$/;

export function readBridgeInputs(env) {
  for (const name of ["BRIDGE_SOURCE_FULL_SHA", "FINAL_SOURCE_FULL_SHA"])
    assert.match(env[name] ?? "", shaPattern, `${name} must be a full SHA`);
  for (const name of ["BRIDGE_BUILD_RUN_ID", "BRIDGE_BUILD_ARTIFACT_ID"]) {
    assert.match(env[name] ?? "", /^[1-9][0-9]*$/, `${name} must be a positive integer`);
    assert.ok(Number.isSafeInteger(Number(env[name])), `${name} is invalid`);
  }
  assert.match(env.BRIDGE_BUILD_ARTIFACT_SHA256 ?? "", digestPattern,
    "BRIDGE_BUILD_ARTIFACT_SHA256 must be a SHA-256 digest");
  return {
    bridgeSourceFullSha: env.BRIDGE_SOURCE_FULL_SHA,
    finalSourceFullSha: env.FINAL_SOURCE_FULL_SHA,
    runId: Number(env.BRIDGE_BUILD_RUN_ID),
    artifactId: Number(env.BRIDGE_BUILD_ARTIFACT_ID),
    artifactSha256: env.BRIDGE_BUILD_ARTIFACT_SHA256,
  };
}

export async function verifyBridgeArtifactDownload({ run, artifact, archivePath, repository, ...input }) {
  assert.equal(run.id, input.runId, "bridge build run changed");
  assert.equal(run.name, "Build-Desktop-Release", "bridge build must use the desktop release owner");
  assert.equal(run.path, ".github/workflows/build-desktop-release.yml");
  assert.equal(run.repository.full_name, repository, "bridge run repository changed");
  assert.equal(run.event, "workflow_dispatch");
  assert.equal(run.status, "completed");
  assert.equal(run.conclusion, "success", "bridge build did not succeed");
  assert.match(run.head_sha, shaPattern);
  assert.equal(artifact.id, input.artifactId, "bridge artifact id changed");
  assert.equal(artifact.name,
    `selfhost-offline-bridge-${input.bridgeSourceFullSha}-${input.finalSourceFullSha}`,
    "bridge artifact does not bind both sources");
  assert.equal(artifact.workflow_run.id, run.id);
  assert.equal(artifact.workflow_run.head_sha, run.head_sha);
  assert.equal(artifact.expired, false, "bridge artifact expired");
  assert.equal(artifact.digest, input.artifactSha256, "bridge artifact digest changed");
  const archiveHash = createHash("sha256");
  for await (const content of fs.createReadStream(archivePath)) archiveHash.update(content);
  assert.equal(`sha256:${archiveHash.digest("hex")}`, input.artifactSha256,
    "downloaded bridge artifact differs from its exact digest");
}

function readMember(reader, archive, member, typeArgs = []) {
  const result = spawnSync(reader, ["e", "-so", "-bd", ...typeArgs, archive, member], { maxBuffer: 2097152 });
  assert.equal(result.status, 0, "bridge archive metadata could not be read");
  return result.stdout;
}

function memberNames(reader, archive, typeArgs = []) {
  const result = spawnSync(reader, ["l", "-slt", "-ba", ...typeArgs, archive], {
    encoding: "utf8", maxBuffer: 2097152,
  });
  assert.equal(result.status, 0, `bridge archive could not be listed: ${path.basename(archive)}`);
  return [...result.stdout.matchAll(/^Path = (.+)$/gm)].map((match) => match[1]);
}

function uniqueMember(names, basename) {
  const matches = names.filter((name) => name.split(/[\\/]/).at(-1) === basename);
  assert.equal(matches.length, 1, `bridge must contain exactly one ${basename}`);
  return matches[0];
}

export function readArchiveBuildManifest(reader, archive, typeArgs = []) {
  return readMember(reader, archive,
    uniqueMember(memberNames(reader, archive, typeArgs), "TARGET_BUILD_MANIFEST.json"), typeArgs);
}

async function hashMember(reader, archive, member, typeArgs = []) {
  const child = spawn(reader, ["e", "-so", "-bd", ...typeArgs, archive, member], {
    stdio: ["ignore", "pipe", "ignore"],
  });
  const sha256 = createHash("sha256"), sha512 = createHash("sha512");
  let size = 0;
  child.stdout.on("data", (content) => {
    size += content.length;
    sha256.update(content);
    sha512.update(content);
  });
  await new Promise((resolve, reject) => {
    child.once("error", () => reject(new Error("bridge archive reader did not start")));
    child.once("close", (code) => code === 0
      ? resolve() : reject(new Error("bridge payload could not be read")));
  });
  return { size, sha256: sha256.digest("hex"), sha512: sha512.digest("hex") };
}

export async function verifyOfflineBridgePair({
  bridgeArchivePath, finalArchivePath, reader,
  bridgeSourceFullSha, finalSourceFullSha,
  bridgeReleasePolicy, finalReleasePolicy, signingPolicy,
}) {
  assert.equal(bridgeReleasePolicy.sourceVersion, bridgeVersion, "bridge must use its own .7 policy");
  assert.equal(finalReleasePolicy.sourceVersion, "2.0.2-selfhost.1",
    "final must use its own .1 policy");
  const bridgeDescriptor = JSON.parse(fs.readFileSync(`${bridgeArchivePath}.selfhost6.json`));
  const finalDescriptorBytes = fs.readFileSync(`${finalArchivePath}.selfhost6.json`);
  const finalDescriptor = JSON.parse(finalDescriptorBytes);
  assert.equal(bridgeDescriptor.sourceFullSha, bridgeSourceFullSha, "bridge source differs");
  assert.equal(finalDescriptor.sourceFullSha, finalSourceFullSha, "final source differs");
  assert.equal(bridgeDescriptor.targetVersion, bridgeVersion);
  assert.equal(finalDescriptor.targetVersion, finalReleasePolicy.sourceVersion);
  assert.equal(bridgeDescriptor.platform, finalDescriptor.platform);
  assert.equal(bridgeDescriptor.arch, finalDescriptor.arch);
  const bridgeMetadata = verifyArtifactDescriptor({
    descriptor: bridgeDescriptor, artifactPath: bridgeArchivePath,
    releasePolicy: bridgeReleasePolicy, signingPolicy,
  });
  const finalMetadata = verifyArtifactDescriptor({
    descriptor: finalDescriptor, artifactPath: finalArchivePath,
    releasePolicy: finalReleasePolicy, signingPolicy,
  });
  const typeArgs = bridgeDescriptor.platform === "win32" ? ["-tzip"] : [];
  const names = memberNames(reader, bridgeArchivePath, typeArgs);
  const manifestBytes = readMember(reader, bridgeArchivePath,
    uniqueMember(names, "TARGET_BUILD_MANIFEST.json"), typeArgs);
  assert.equal(createHash("sha256").update(manifestBytes).digest("hex"),
    bridgeMetadata["target-build-manifest-sha256"], "bridge manifest bytes differ");
  validateTargetBuildManifest({
    manifest: JSON.parse(manifestBytes), archiveDigestVerified: true,
    expected: {
      targetSourceFullSha: bridgeSourceFullSha, targetVersion: bridgeVersion,
      releaseLineId: signingPolicy.releaseLineId, platform: bridgeMetadata.platform,
      arch: bridgeMetadata.arch, bundleIdentity: signingPolicy.bundleIdentity,
      signingKeyIdentity: signingPolicy.keyId,
    },
    currentFormats: {
      activation: bridgeReleasePolicy.activationWriteFormat,
      clientOps: bridgeReleasePolicy.clientOpsWriteFormat,
    },
  });
  const descriptorBytes = readMember(reader, bridgeArchivePath,
    uniqueMember(names, `${path.basename(finalArchivePath)}.selfhost6.json`), typeArgs);
  assert.ok(descriptorBytes.equals(finalDescriptorBytes), "embedded final descriptor differs");
  const payload = await hashMember(reader, bridgeArchivePath,
    uniqueMember(names, path.basename(finalArchivePath)), typeArgs);
  assert.equal(payload.size, finalMetadata["archive-size"], "embedded final size differs");
  assert.equal(payload.sha256, finalMetadata["archive-sha256"], "embedded final SHA-256 differs");
  assert.equal(payload.sha512, finalMetadata["archive-sha512"], "embedded final SHA-512 differs");
}

export async function verifyOfflineBridgeSet(options) {
  const names = bridgeImmutableAssetNames(bridgeVersion);
  const files = names.flatMap((name) => [name, `${name}.selfhost6.json`]).sort();
  assert.deepEqual(fs.readdirSync(options.bridgeDirectory).sort(), files,
    "bridge input must contain exactly five archives and five descriptors");
  assert.ok(files.every((name) => fs.lstatSync(path.join(options.bridgeDirectory, name)).isFile()),
    "bridge inputs must be regular files");
  for (const name of names) await verifyOfflineBridgePair({
    ...options,
    bridgeArchivePath: path.join(options.bridgeDirectory, name),
    finalArchivePath: path.join(options.finalDirectory,
      name.replace(`-${bridgeVersion}`, `-${options.finalReleasePolicy.sourceVersion}`)),
  });
}

export async function getBridgeArchiveReader() {
  return buildArchiveReader();
}

async function main() {
  const phase = process.argv[2];
  assert.ok(["inputs", "download", "payloads"].includes(phase), "invalid verification phase");
  const input = readBridgeInputs(process.env);
  if (phase === "download") {
    const temporary = process.env.RUNNER_TEMP;
    await verifyBridgeArtifactDownload({
      ...input, repository: process.env.GITHUB_REPOSITORY,
      run: JSON.parse(fs.readFileSync(path.join(temporary, "offline-bridge-run.json"))),
      artifact: JSON.parse(fs.readFileSync(path.join(temporary, "offline-bridge-artifact.json"))),
      archivePath: path.join(temporary, "offline-bridge-artifact.zip"),
    });
  } else if (phase === "payloads") {
    const bridgeRoot = path.join(repoRoot, "bridge-source");
    const actualSource = spawnSync("git", ["rev-parse", "HEAD"], {
      cwd: bridgeRoot, encoding: "utf8",
    });
    assert.equal(actualSource.status, 0);
    assert.equal(actualSource.stdout.trim(), input.bridgeSourceFullSha);
    const { readReleasePolicy: readBridgePolicy } = await import(pathToFileURL(
      path.join(bridgeRoot, "scripts/lib/selfhost6-release-identity.mjs")));
    const signingPolicy = loadProjectSigningPolicy();
    assert.deepEqual(loadProjectSigningPolicy(pathToFileURL(
      path.join(bridgeRoot, "resources/updater/project-signing-policy.json"))), signingPolicy);
    await verifyOfflineBridgeSet({
      ...input, reader: await getBridgeArchiveReader(),
      bridgeDirectory: path.join(repoRoot, "bridge-release-assets"),
      finalDirectory: path.join(repoRoot, "published-release-assets"),
      bridgeReleasePolicy: readBridgePolicy(
        path.join(bridgeRoot, "resources/updater/selfhost-release-policy.json")),
      finalReleasePolicy: readReleasePolicy(
        path.join(repoRoot, "resources/updater/selfhost-release-policy.json")),
      signingPolicy,
    });
  }
  process.stdout.write(`${JSON.stringify({ status: `offline-bridge-${phase}-verified` })}\n`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href)
  main().catch(() => {
    process.stderr.write("offline-bridge-input-rejected\n");
    process.exitCode = 1;
  });
