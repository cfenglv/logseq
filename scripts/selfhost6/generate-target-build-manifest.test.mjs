import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { generateKeyPairSync } from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import {
  buildTargetManifest,
  readReleasePolicy,
  releasePolicyFromHistoricalIdentity,
  writeTargetManifest,
} from "../lib/selfhost6-release-identity.mjs";
import {
  buildPromotion,
  prepareSignedArtifact,
  stageSignedArtifact,
  verifyArtifactDescriptor,
} from "../lib/selfhost6-update-feed.mjs";
import {
  algorithm,
  bundleIdentity,
  payloadDomain,
  releaseLineId,
  signingKeyIdentity,
} from "../../resources/updater/project-update-signature.mjs";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const policyPath = path.join(repoRoot, "resources/updater/selfhost-release-policy.json");

test("candidate version CLI writes the current and synthetic identities", (context) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "candidate-version-entry-"));
  context.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const script = path.join(root, "scripts/selfhost6/set-candidate-version.mjs");
  const source = path.join(root, "src/main/frontend/version.cljs");
  fs.mkdirSync(path.dirname(script), { recursive: true });
  fs.mkdirSync(path.dirname(source), { recursive: true });
  fs.copyFileSync(path.join(repoRoot, "scripts/selfhost6/set-candidate-version.mjs"), script);
  fs.copyFileSync(path.join(repoRoot, "src/main/frontend/version.cljs"), source);
  const policy = readReleasePolicy(policyPath);
  for (const version of [policy.syntheticForwardTargetVersion, policy.sourceVersion]) {
    const result = spawnSync(process.execPath, [script, "--version", version], { encoding: "utf8" });
    assert.equal(result.status, 0, result.stderr);
    assert.ok(fs.readFileSync(source, "utf8").includes(`(defonce version "${version}")`));
  }
  const current = fs.readFileSync(source, "utf8");
  assert.notEqual(spawnSync(process.execPath, [script, "--version", "invalid"]).status, 0);
  assert.equal(fs.readFileSync(source, "utf8"), current);
});

test("source and synthetic forward versions bind only the final public source identity", () => {
  const policy = readReleasePolicy(policyPath);
  assert.deepEqual(policy.provider, {
    kind: "github",
    owner: "cfenglv",
    repo: "logseq",
    remoteMutation: "version-release-only",
  });
  const privateDevelopmentParentSha = "a".repeat(40);
  const finalPublicSourceSha = "b".repeat(40);
  const { privateKey, publicKey } = generateKeyPairSync("ed25519");
  const publicKeyBase64 = publicKey.export({ format: "der", type: "spki" })
    .subarray(-32).toString("base64");
  const signingPolicy = Object.freeze({
    schemaVersion: 1,
    algorithm,
    payloadDomain,
    releaseLineId,
    bundleIdentity,
    keyId: signingKeyIdentity(publicKeyBase64),
    publicKeyBase64,
  });
  const privateKeyPem = privateKey.export({ format: "pem", type: "pkcs8" });
  const common = {
    policy,
    sourceFullSha: finalPublicSourceSha,
    platform: "win32",
    arch: "arm64",
    signingKeyIdentity: signingPolicy.keyId,
  };
  const source = buildTargetManifest({ ...common, targetVersion: policy.sourceVersion });
  const target = buildTargetManifest({ ...common, targetVersion: policy.syntheticForwardTargetVersion });
  assert.equal(policy.sourceVersion, "2.0.2-selfhost.3");
  assert.equal(policy.syntheticForwardTargetVersion, "2.0.2-selfhost.4");
  assert.equal(source["release-line-id"], target["release-line-id"]);
  assert.equal(source["target-source-full-sha"], target["target-source-full-sha"]);
  assert.equal(source["target-source-full-sha"], finalPublicSourceSha);
  assert.notEqual(source["target-source-full-sha"], privateDevelopmentParentSha);
  assert.equal(source["bundle-identity"], "com.logseq.logseq");
  assert.deepEqual(source["readable-activation-formats"], ["selfhost-activation-v1"]);
  assert.deepEqual(source["readable-client-ops-formats"], ["official-client-ops-sqlite-v2+selfhost-upload-v1"]);
  assert.throws(
    () => buildTargetManifest({ ...common, targetVersion: "2.0.1-selfhost.6" }),
    /current source/,
  );

  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "selfhost6-version-identity-"));
  const manifestPath = path.join(directory, "manifest.json");
  const descriptorFor = (manifest) => {
    const archivePath = path.join(
      directory,
      `Logseq-win-arm64-${manifest["target-version"]}-nsis.exe`,
    );
    fs.writeFileSync(archivePath, `candidate bytes:${manifest["target-version"]}`);
    writeTargetManifest(manifestPath, manifest);
    return prepareSignedArtifact({
      archivePath,
      targetManifestPath: manifestPath,
      sourceFullSha: finalPublicSourceSha,
      targetVersion: manifest["target-version"],
      platform: "win32",
      arch: "arm64",
      releasePolicy: policy,
      signingPolicy,
      privateKeyPem,
    });
  };
  const formalDescriptor = descriptorFor(source);
  const syntheticDescriptor = descriptorFor(target);
  const stagedDirectory = path.join(directory, "staged");
  stageSignedArtifact({
    descriptor: syntheticDescriptor,
    archivePath: path.join(directory, syntheticDescriptor.assetName),
    outputDirectory: stagedDirectory,
  });
  assert.throws(() => buildPromotion({
    descriptors: [syntheticDescriptor],
    artifactDirectory: stagedDirectory,
    expectedTargetVersion: policy.syntheticForwardTargetVersion,
    releasePolicy: policy,
    signingPolicy,
  }), /only the one-time \.6 to \.7 bridge/);
  for (const identity of [source, target, formalDescriptor, syntheticDescriptor]) {
    assert.ok(JSON.stringify(identity).includes(finalPublicSourceSha));
    assert.ok(!JSON.stringify(identity).includes(privateDevelopmentParentSha));
  }

  const historicalIdentity = {
    status: "policy-frozen",
    productVersion: "2.0.1-selfhost.6",
    releaseLineId,
    newIdentity: {
      provider: "generic:https://updates.example.invalid/selfhost",
      forwardUpdateChannel: releaseLineId,
      firstInstallMode: "controlled-manual-artifact",
      sameVersionAutomaticTransitionFromWithdrawnBuild: false,
      legacySharedLatestMutationAllowed: false,
    },
  };
  const historicalPolicy = releasePolicyFromHistoricalIdentity(historicalIdentity);
  assert.equal(historicalPolicy.sourceVersion, "2.0.1-selfhost.6");
  assert.equal(historicalPolicy.syntheticForwardTargetVersion, "2.0.1-selfhost.7");
  const historicalManifest = { ...source, "target-version": historicalPolicy.sourceVersion };
  writeTargetManifest(manifestPath, historicalManifest);
  const historicalArchivePath = path.join(directory, "Logseq-win-arm64-2.0.1-selfhost.6-nsis.exe");
  fs.writeFileSync(historicalArchivePath, "historical candidate bytes");
  const historicalDescriptor = prepareSignedArtifact({
    archivePath: historicalArchivePath,
    targetManifestPath: manifestPath,
    sourceFullSha: finalPublicSourceSha,
    targetVersion: historicalPolicy.sourceVersion,
    platform: "win32",
    arch: "arm64",
    releasePolicy: historicalPolicy,
    signingPolicy,
    privateKeyPem,
  });
  const historicalStaged = stageSignedArtifact({
    descriptor: historicalDescriptor,
    archivePath: historicalArchivePath,
    outputDirectory: path.join(directory, "historical-staged"),
  });
  assert.equal(
    verifyArtifactDescriptor({
      descriptor: historicalDescriptor,
      artifactPath: historicalStaged.artifactPath,
      releasePolicy: historicalPolicy,
      signingPolicy,
    })["target-version"],
    "2.0.1-selfhost.6",
  );

  const workflows = fs.readdirSync(path.join(repoRoot, ".github/workflows"))
    .filter((entry) => entry.endsWith(".yml"))
    .map((entry) => fs.readFileSync(path.join(repoRoot, ".github/workflows", entry), "utf8"));
  assert.equal(workflows.filter((value) => /^name: Build-Desktop-Release$/m.test(value)).length, 1);
});

test("future identity input fails closed without compatibility fallbacks", () => {
  const policy = readReleasePolicy(policyPath);
  const valid = {
    policy,
    sourceFullSha: "a".repeat(40),
    targetVersion: policy.sourceVersion,
    platform: "darwin",
    arch: "arm64",
    signingKeyIdentity: "managed-test-key",
  };
  assert.throws(() => buildTargetManifest({ ...valid, sourceFullSha: "short" }), /40 lowercase hex/);
  assert.throws(() => buildTargetManifest({ ...valid, targetVersion: "2.0.1-selfhost.6" }), /current source/);
  assert.throws(() => buildTargetManifest({ ...valid, platform: "ios", arch: "arm64" }), /unsupported/);
  assert.throws(() => buildTargetManifest({ ...valid, signingKeyIdentity: "" }), /required/);
});

test("packaging configuration requires the generated target manifest", () => {
  const builder = fs.readFileSync(path.join(repoRoot, "resources/electron-builder.yml"), "utf8");
  assert.match(builder, /from: updater\/TARGET_BUILD_MANIFEST\.json\n\s+to: updater\/TARGET_BUILD_MANIFEST\.json/);
  const version = fs.readFileSync(path.join(repoRoot, "src/main/frontend/version.cljs"), "utf8");
  assert.match(version, /defonce version "2\.0\.2-selfhost\.3"/);
  const gulpfile = fs.readFileSync(path.join(repoRoot, "gulpfile.js"), "utf8");
  assert.ok(gulpfile.includes('match(/defonce version "([^"]+)"/)?.[1]'));
});
