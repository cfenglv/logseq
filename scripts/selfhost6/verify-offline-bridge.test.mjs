import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createHash, generateKeyPairSync } from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

import { bridgeImmutableAssetNames } from "../lib/selfhost6-existing-release-promotion.mjs";
import { buildTargetManifest, readReleasePolicy } from "../lib/selfhost6-release-identity.mjs";
import { buildPromotion, prepareSignedArtifact, writePromotion } from "../lib/selfhost6-update-feed.mjs";
import {
  algorithm, bundleIdentity, payloadDomain, releaseLineId, signingKeyIdentity,
} from "../../resources/updater/project-update-signature.mjs";
import {
  getBridgeArchiveReader, readBridgeInputs, verifyBridgeArtifactDownload, verifyOfflineBridgeSet,
} from "./verify-offline-bridge.mjs";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const readerSource = process.env.ELECTRON_BUILDER_7ZIP_PATH ?? await getBridgeArchiveReader();
const finalReleasePolicy = readReleasePolicy(
  path.join(repoRoot, "resources/updater/selfhost-release-policy.json"));
const bridgeReleasePolicy = {
  ...finalReleasePolicy, sourceVersion: "2.0.1-selfhost.7",
  syntheticForwardTargetVersion: "2.0.1-selfhost.8",
};
const { privateKey, publicKey } = generateKeyPairSync("ed25519");
const publicKeyBase64 = publicKey.export({ format: "der", type: "spki" }).subarray(-32).toString("base64");
const signingPolicy = {
  schemaVersion: 1, algorithm, bundleIdentity, payloadDomain, releaseLineId,
  keyId: signingKeyIdentity(publicKeyBase64), publicKeyBase64,
};
const privateKeyPem = privateKey.export({ format: "pem", type: "pkcs8" });
const bridgeSourceFullSha = "a".repeat(40), finalSourceFullSha = "b".repeat(40);
const digest = content => `sha256:${createHash("sha256").update(content).digest("hex")}`;

function fixture(context, { corrupt = false, duplicate = false, embedMac = false, wrongURL = false, missingOffline = false } = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "offline-bridge-input-"));
  context.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const reader = path.join(root, process.platform === "win32" ? "7za.exe" : "7za");
  fs.copyFileSync(readerSource, reader);
  if (process.platform === "win32")
    fs.copyFileSync(path.join(path.dirname(readerSource), "7z.dll"), path.join(root, "7z.dll"));
  fs.chmodSync(reader, 0o755);
  const bridgeDirectory = path.join(root, "bridge"), finalDirectory = path.join(root, "final");
  fs.mkdirSync(bridgeDirectory); fs.mkdirSync(finalDirectory);
  const descriptors = [];
  for (const [index, name] of bridgeImmutableAssetNames(bridgeReleasePolicy.sourceVersion).entries()) {
    const platform = name.includes("darwin") ? "darwin" : name.includes("linux") ? "linux" : "win32";
    const arch = name.includes("arm64") ? "arm64" : "x64";
    const finalName = name.replace(`-${bridgeReleasePolicy.sourceVersion}`,
      `-${finalReleasePolicy.sourceVersion}`);
    const finalPath = path.join(finalDirectory, finalName);
    const finalBytes = Buffer.from(`opaque synthetic final ${platform}/${arch}`);
    fs.writeFileSync(finalPath, finalBytes);
    const source = path.join(root, `contents-${index}`);
    fs.mkdirSync(path.join(source, "payload"), { recursive: true });
    const manifestPath = path.join(source, "TARGET_BUILD_MANIFEST.json");
    function sign(archivePath, policy, sourceFullSha) {
      fs.writeFileSync(manifestPath, `${JSON.stringify(buildTargetManifest({
        policy, sourceFullSha, targetVersion: policy.sourceVersion, platform, arch,
        signingKeyIdentity: signingPolicy.keyId,
      }), null, 2)}\n`);
      const descriptor = prepareSignedArtifact({
        archivePath, targetManifestPath: manifestPath, sourceFullSha,
        targetVersion: policy.sourceVersion, platform, arch,
        releasePolicy: policy, signingPolicy, privateKeyPem,
      });
      fs.writeFileSync(`${archivePath}.selfhost6.json`, `${JSON.stringify(descriptor, null, 2)}\n`);
      return descriptor;
    }
    sign(finalPath, finalReleasePolicy, finalSourceFullSha);
    const payload = Buffer.from(finalBytes);
    if (corrupt && platform === "linux" && arch === "arm64") payload[0] ^= 1;
    if ((platform !== "darwin" && !missingOffline) || embedMac)
      fs.writeFileSync(path.join(source, "payload", finalName), payload);
    if (duplicate && platform === "linux" && arch === "arm64") {
      fs.mkdirSync(path.join(source, "duplicate"));
      fs.writeFileSync(path.join(source, "duplicate", finalName), payload);
    }
    fs.copyFileSync(`${finalPath}.selfhost6.json`,
      path.join(source, "payload", `${finalName}.selfhost6.json`));
    if (platform === "darwin") {
      const { owner, repo } = finalReleasePolicy.provider;
      const url = `https://github.com/${owner}/${repo}/releases/download/${finalReleasePolicy.sourceVersion}/${finalName}\n`;
      fs.writeFileSync(path.join(source, "payload/download-url"), wrongURL ? url.replace(arch, "other") : url);
    }
    fs.writeFileSync(manifestPath, `${JSON.stringify(buildTargetManifest({
      policy: bridgeReleasePolicy, sourceFullSha: bridgeSourceFullSha,
      targetVersion: bridgeReleasePolicy.sourceVersion, platform, arch,
      signingKeyIdentity: signingPolicy.keyId,
    }), null, 2)}\n`);
    const archivePath = path.join(bridgeDirectory, name);
    // All five unit containers are ZIPs; this tests input contracts, not NSIS/AppImage execution.
    execFileSync(reader, ["a", "-tzip", archivePath, "."], { cwd: source, stdio: "pipe" });
    descriptors.push(sign(archivePath, bridgeReleasePolicy, bridgeSourceFullSha));
  }
  return {
    root, bridgeDirectory, finalDirectory, reader, descriptors,
    bridgeSourceFullSha, finalSourceFullSha, bridgeReleasePolicy, finalReleasePolicy, signingPolicy,
  };
}

function stagePointers(input) {
  const metadataByFile = buildPromotion({
    descriptors: input.descriptors, artifactDirectory: input.bridgeDirectory,
    expectedTargetVersion: bridgeReleasePolicy.sourceVersion,
    releasePolicy: input.bridgeReleasePolicy, signingPolicy,
  });
  const outputDirectory = path.join(input.root, "pointers");
  writePromotion({ metadataByFile, outputDirectory });
  return outputDirectory;
}

test("five separately signed bridges bind the exact final bytes before four pointers are staged", async context => {
  const input = fixture(context);
  await verifyOfflineBridgeSet(input);
  const directory = stagePointers(input);
  assert.equal(fs.readdirSync(directory).length, 4);
  for (const name of fs.readdirSync(directory)) {
    const pointer = JSON.parse(fs.readFileSync(path.join(directory, name)));
    assert.equal(pointer.version, bridgeReleasePolicy.sourceVersion);
    for (const metadata of Object.values(pointer.selfhostUpdateSignatures))
      assert.equal(metadata["target-source-full-sha"], bridgeSourceFullSha);
  }
});

test("a mixed source or final policy is rejected before pointer writes", async context => {
  const input = fixture(context);
  for (const changed of [
    { bridgeSourceFullSha: "c".repeat(40) },
    { finalSourceFullSha: "c".repeat(40) },
    { bridgeReleasePolicy: finalReleasePolicy },
  ]) {
    await assert.rejects(async () => {
      await verifyOfflineBridgeSet({ ...input, ...changed });
      stagePointers(input);
    }, /source differs|own \.7 policy/);
    assert.equal(fs.existsSync(path.join(input.root, "pointers")), false);
  }
});

test("a valid outer signature cannot hide a changed same-size embedded final", async context => {
  const input = fixture(context, { corrupt: true });
  await assert.rejects(async () => {
    await verifyOfflineBridgeSet(input);
    stagePointers(input);
  }, /embedded final SHA-256 differs/);
  assert.equal(fs.existsSync(path.join(input.root, "pointers")), false);
});

test("ambiguous embedded final names are rejected before pointer writes", async context => {
  const input = fixture(context, { duplicate: true });
  await assert.rejects(async () => {
    await verifyOfflineBridgeSet(input);
    stagePointers(input);
  }, /bridge must contain exactly one Logseq-/);
  assert.equal(fs.existsSync(path.join(input.root, "pointers")), false);
});

test("both Mac bridges must be light and pin the exact public target; other platforms remain offline", async context => {
  for (const options of [{ embedMac: true }, { wrongURL: true }, { missingOffline: true }]) {
    const input = fixture(context, options);
    await assert.rejects(verifyOfflineBridgeSet(input), /Mac bridge must not embed|fixed Mac download URL differs|exactly one Logseq-/);
    assert.equal(fs.existsSync(path.join(input.root, "pointers")), false);
  }
});

test("bridge build transport binds explicit run, artifact, both sources and digest", async context => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "offline-bridge-transport-"));
  context.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const archivePath = path.join(directory, "artifact.zip"), bytes = Buffer.from("synthetic artifact bytes");
  fs.writeFileSync(archivePath, bytes);
  const env = {
    BRIDGE_SOURCE_FULL_SHA: bridgeSourceFullSha, FINAL_SOURCE_FULL_SHA: finalSourceFullSha,
    BRIDGE_BUILD_RUN_ID: "73", BRIDGE_BUILD_ARTIFACT_ID: "74",
    BRIDGE_BUILD_ARTIFACT_SHA256: digest(bytes),
  };
  const input = readBridgeInputs(env);
  const run = {
    id: 73, name: "Desktop release · stable · build-offline-bridge · release/2.0.2-selfhost.1",
    path: ".github/workflows/build-desktop-release.yml",
    repository: { full_name: "example/project" }, event: "workflow_dispatch",
    status: "completed", conclusion: "success", head_sha: "c".repeat(40),
  };
  const artifact = {
    id: 74, name: `selfhost-offline-bridge-${bridgeSourceFullSha}-${finalSourceFullSha}`,
    workflow_run: { id: 73, head_sha: run.head_sha }, expired: false, digest: digest(bytes),
  };
  const options = { ...input, run, artifact, archivePath, repository: "example/project" };
  await verifyBridgeArtifactDownload(options);
  await assert.rejects(verifyBridgeArtifactDownload({
    ...options, run: { ...run, path: ".github/workflows/unrelated.yml" },
  }));
  assert.throws(() => readBridgeInputs({ ...env, BRIDGE_BUILD_RUN_ID: "" }), /positive integer/);
  await assert.rejects(verifyBridgeArtifactDownload({
    ...options, artifact: { ...artifact, workflow_run: { ...artifact.workflow_run, id: 75 } },
  }));
  await assert.rejects(verifyBridgeArtifactDownload({
    ...options, artifact: { ...artifact, name: "unbound-artifact" },
  }), /bind both sources/);
  fs.writeFileSync(archivePath, "changed artifact");
  await assert.rejects(verifyBridgeArtifactDownload(options), /exact digest/);
});
