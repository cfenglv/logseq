import assert from "node:assert/strict";
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

test("formal .7 and synthetic .8 bind only the final public source identity", () => {
  const policy = readReleasePolicy(policyPath);
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
  assert.equal(policy.sourceVersion, "2.0.1-selfhost.7");
  assert.equal(policy.syntheticForwardTargetVersion, "2.0.1-selfhost.8");
  assert.equal(source["release-line-id"], target["release-line-id"]);
  assert.equal(source["target-source-full-sha"], target["target-source-full-sha"]);
  assert.equal(source["target-source-full-sha"], finalPublicSourceSha);
  assert.notEqual(source["target-source-full-sha"], privateDevelopmentParentSha);
  assert.equal(source["bundle-identity"], "com.logseq.logseq");
  assert.deepEqual(source["readable-activation-formats"], ["selfhost-activation-v1"]);
  assert.deepEqual(source["readable-client-ops-formats"], ["official-client-ops-sqlite-v2+selfhost-upload-v1"]);
  assert.throws(
    () => buildTargetManifest({ ...common, targetVersion: "2.0.1-selfhost.6" }),
    /formal \.7/,
  );

  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "selfhost6-version-identity-"));
  const archivePath = path.join(directory, "candidate.exe");
  const manifestPath = path.join(directory, "manifest.json");
  fs.writeFileSync(archivePath, "candidate bytes");
  const descriptorFor = (manifest) => {
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
  stageSignedArtifact({ descriptor: syntheticDescriptor, archivePath, outputDirectory: directory });
  const channel = buildPromotion({
    descriptors: [syntheticDescriptor],
    artifactDirectory: directory,
    expectedTargetVersion: policy.syntheticForwardTargetVersion,
    releasePolicy: policy,
    signingPolicy,
  }).values().next().value;
  for (const identity of [source, target, formalDescriptor, syntheticDescriptor, channel]) {
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
  const historicalDescriptor = prepareSignedArtifact({
    archivePath,
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
    archivePath,
    outputDirectory: directory,
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
  assert.throws(() => buildTargetManifest({ ...valid, targetVersion: "2.0.1-selfhost.6" }), /formal \.7/);
  assert.throws(() => buildTargetManifest({ ...valid, platform: "ios", arch: "arm64" }), /unsupported/);
  assert.throws(() => buildTargetManifest({ ...valid, signingKeyIdentity: "" }), /required/);
});

test("packaging configuration requires the generated target manifest", () => {
  const builder = fs.readFileSync(path.join(repoRoot, "resources/electron-builder.yml"), "utf8");
  assert.match(builder, /from: updater\/TARGET_BUILD_MANIFEST\.json\n\s+to: updater\/TARGET_BUILD_MANIFEST\.json/);
  const version = fs.readFileSync(path.join(repoRoot, "src/main/frontend/version.cljs"), "utf8");
  assert.match(version, /defonce version "2\.0\.1-selfhost\.7"/);
  const gulpfile = fs.readFileSync(path.join(repoRoot, "gulpfile.js"), "utf8");
  assert.ok(gulpfile.includes('match(/defonce version "([^"]+)"/)?.[1]'));
});
