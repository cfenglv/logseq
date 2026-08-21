import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import {
  buildTargetManifest,
  readReleasePolicy,
  writeTargetManifest,
} from "../lib/selfhost6-release-identity.mjs";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const policyPath = path.join(repoRoot, "resources/updater/selfhost-release-policy.json");

test("reissued .6 and synthetic .7 share one isolated release identity contract", () => {
  const policy = readReleasePolicy(policyPath);
  const common = {
    policy,
    sourceFullSha: "a".repeat(40),
    platform: "darwin",
    arch: "arm64",
    signingKeyIdentity: "managed-test-key",
  };
  const source = buildTargetManifest({ ...common, targetVersion: policy.sourceVersion });
  const target = buildTargetManifest({ ...common, targetVersion: policy.syntheticForwardTargetVersion });
  assert.equal(source["release-line-id"], target["release-line-id"]);
  assert.equal(source["target-source-full-sha"], target["target-source-full-sha"]);
  assert.equal(source["bundle-identity"], "com.logseq.logseq");
  assert.deepEqual(source["readable-activation-formats"], ["selfhost-activation-v1"]);
  assert.deepEqual(source["readable-client-ops-formats"], ["official-client-ops-sqlite-v2+selfhost-upload-v1"]);
  const output = path.join(fs.mkdtempSync(path.join(os.tmpdir(), "selfhost6-target-manifest-")), "manifest.json");
  writeTargetManifest(output, target);
  assert.deepEqual(JSON.parse(fs.readFileSync(output, "utf8")), target);
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
  assert.throws(() => buildTargetManifest({ ...valid, targetVersion: "2.0.1-selfhost.5" }), /reissued .6/);
  assert.throws(() => buildTargetManifest({ ...valid, platform: "ios", arch: "arm64" }), /unsupported/);
  assert.throws(() => buildTargetManifest({ ...valid, signingKeyIdentity: "" }), /required/);
});

test("packaging configuration requires the generated target manifest", () => {
  const builder = fs.readFileSync(path.join(repoRoot, "resources/electron-builder.yml"), "utf8");
  assert.match(builder, /from: updater\/TARGET_BUILD_MANIFEST\.json\n\s+to: updater\/TARGET_BUILD_MANIFEST\.json/);
  const version = fs.readFileSync(path.join(repoRoot, "src/main/frontend/version.cljs"), "utf8");
  assert.match(version, /defonce version "2\.0\.1-selfhost\.6"/);
  const gulpfile = fs.readFileSync(path.join(repoRoot, "gulpfile.js"), "utf8");
  assert.ok(gulpfile.includes('match(/defonce version "([^"]+)"/)?.[1]'));
});
