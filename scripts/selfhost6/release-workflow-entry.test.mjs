import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash, generateKeyPairSync } from "node:crypto";
import fs from "node:fs";
import { createRequire } from "node:module";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath, pathToFileURL } from "node:url";

const { parse } = createRequire(import.meta.url)("yaml");
const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const workflow = parse(fs.readFileSync(path.join(repo, ".github/workflows/build-desktop-release.yml"), "utf8"));
const sourceSha = "b".repeat(40);
const hash = (algorithm, payload) => createHash(algorithm).update(payload).digest(algorithm === "sha512" ? "base64" : "hex");

test("current Draft routing preserves every release gate and excludes bridge and upstream builds", () => {
  const ref = `release/${workflow.env.SELFHOST6_TARGET_VERSION}`;
  const values = {
    "github.event_name": "workflow_dispatch", "github.ref_name": ref,
    "github.event.inputs.git-ref": ref, "github.event.inputs.build-target": "stable",
    "github.event.inputs.selfhost-operation": "stage-draft", "github.event.inputs.is-draft": "true",
    "github.event.inputs.build-android": "false",
  };
  const enabled = (name) => Function(`return (${workflow.jobs[name].if.slice(3, -2)
    .replace(/github\.[\w.-]+/g, (key) => { assert.ok(Object.hasOwn(values, key), key); return JSON.stringify(values[key]); })})`)();
  for (const name of ["resolve-release-source", "selfhost-release"]) assert.equal(enabled(name), true, name);
  for (const name of ["upstream-compile-cljs", "build-android", "selfhost-bridge-build-inputs", "selfhost-release-bridge"])
    assert.equal(enabled(name), false, name);
  assert.deepEqual(workflow.jobs["release-source-gate"].needs, ["resolve-release-source", "source-preflight"]);
  assert.deepEqual(workflow.jobs["compile-cljs"].needs, ["release-source-gate", "rtc-browser-e2e"]);
  assert.deepEqual(workflow.jobs["selfhost-release"].needs, ["release-source-gate", "selfhost-release-verifier"]);
});

test("actual release signing shell hands its 35 files to both verifier CLI entries", async (context) => {
  const root = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), "release-workflow-entry-")));
  context.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const source = path.join(root, "release-source");
  const write = (name, payload) => {
    fs.mkdirSync(path.dirname(name), { recursive: true });
    fs.writeFileSync(name, payload);
  };
  const json = (name, value) => write(name, JSON.stringify(value, null, 2) + "\n");
  for (const name of ["scripts/lib/selfhost6-release-identity.mjs", "scripts/lib/selfhost6-update-feed.mjs",
    "scripts/selfhost6/prepare-update-artifact.mjs", "scripts/selfhost6/finalize-traditional-update-metadata.mjs",
    "scripts/selfhost6/verify-built-release.mjs", "resources/updater/project-update-signature.mjs",
    "resources/updater/project-signing-policy.json", "resources/updater/selfhost-release-policy.json"]) {
    write(path.join(source, name), fs.readFileSync(path.join(repo, name)));
  }
  // Only inputs and the signing identity are synthetic; workflow shell and CLI
  // files are copied verbatim. This is not native-package or product acceptance.
  const { privateKey, publicKey } = generateKeyPairSync("ed25519");
  const signingPath = path.join(source, "resources/updater/project-signing-policy.json");
  const signing = JSON.parse(fs.readFileSync(signingPath, "utf8"));
  const raw = publicKey.export({ format: "der", type: "spki" }).subarray(-32);
  signing.publicKeyBase64 = raw.toString("base64");
  signing.keyId = "ed25519:" + hash("sha256", raw);
  json(signingPath, signing);
  const identity = await import(pathToFileURL(path.join(source, "scripts/lib/selfhost6-release-identity.mjs")));
  const policy = identity.readReleasePolicy(path.join(source, "resources/updater/selfhost-release-policy.json"));
  const version = policy.sourceVersion;
  const targets = workflow.jobs["selfhost-build-platform"].strategy.matrix.include;
  assert.equal(targets.length, 6);
  for (const { id, platform, arch } of targets) {
    const directory = path.join(source, "unsigned", `selfhost6-unsigned-${id}-${version}-${sourceSha}`);
    const stem = platform === "darwin" ? `Logseq-darwin-${arch}-${version}`
      : platform === "win32" ? `Logseq-win-${arch}-${version}`
        : `Logseq-linux-${arch === "x64" ? "x86_64" : arch}-${version}`;
    const archives = platform === "darwin" ? [stem + ".zip", stem + ".dmg"]
      : [stem + (platform === "win32" ? "-nsis.exe" : ".AppImage")];
    const packages = platform === "darwin" ? [...archives, ...archives.map((name) => name + ".blockmap")]
      : platform === "win32" ? [...archives, archives[0] + ".blockmap", stem + ".zip"]
        : [...archives, stem + ".zip"];
    for (const name of packages) write(path.join(directory, name), `synthetic-package:${name}\n`);
    let native = `version: ${version}\nfiles:\n`;
    for (const name of archives) {
      const payload = fs.readFileSync(path.join(directory, name));
      native += `  - url: ${name}\n    sha512: ${hash("sha512", payload)}\n    size: ${payload.length}\n`;
    }
    write(path.join(directory, "native-update.yml"), native + `path: ${archives[0]}\n`);
    json(path.join(directory, "TARGET_BUILD_MANIFEST.json"), identity.buildTargetManifest({
      policy, sourceFullSha: sourceSha, targetVersion: version, platform, arch, signingKeyIdentity: signing.keyId,
    }));
    json(path.join(directory, "PLATFORM_SQLITE_SWAP_RECEIPT.json"), {
      kind: "selfhost6.phase0.platform-sqlite-swap.v1", result: "pass", sourceFullSha: sourceSha,
      platform, arch, authorityCommitRenameCount: 1, oracles: { syntheticFixtureOnly: true },
    });
  }
  const expressions = {
    "needs.release-source-gate.outputs.source-sha": sourceSha,
    "needs.selfhost-release-verifier.outputs.product-source-sha": sourceSha,
    "secrets.LOGSEQ_PROJECT_UPDATE_SIGNING_KEY_PKCS8_BASE64": privateKey.export({ format: "der", type: "pkcs8" }).toString("base64"),
  };
  const expand = (value) => String(value).replace(/\$\{\{\s*(.*?)\s*\}\}/g, (_, key) => {
    assert.ok(Object.hasOwn(expressions, key), `unhandled workflow expression: ${key}`);
    return expressions[key];
  });
  const run = (step, cwd = source) => {
    assert.ok(step?.run, "actual workflow shell entry is required");
    return spawnSync("bash", ["-e", "-o", "pipefail", "-c", expand(step.run)], {
      cwd, encoding: "utf8", timeout: 30000,
      env: { PATH: process.env.PATH, NODE_PATH: process.env.NODE_PATH,
        SELFHOST6_TARGET_VERSION: version, GITHUB_REPOSITORY: "example/release-fixture",
        GITHUB_RUN_ID: "1", GITHUB_SHA: "c".repeat(40), GITHUB_OUTPUT: path.join(root, "github-output"),
        ...Object.fromEntries(Object.entries(step.env ?? {}).map(([key, value]) => [key, expand(value)])) },
    });
  };
  const success = (result) => assert.equal(result.status, 0,
    `workflow entry failed: ${result.error?.code ?? ""}\n${result.stdout}${result.stderr}`);
  const signingSteps = workflow.jobs["selfhost-release-signing"].steps;
  const verifierSteps = workflow.jobs["selfhost-release-verifier"].steps;
  const find = (steps, name) => {
    const step = steps.find((item) => item.name === name); assert.ok(step, name); return step;
  };
  await context.test("sign, retain, checksum, qualify, and independently read the real outputs", () => {
    for (const name of ["Sign immutable artifact descriptors", "Retain native packages, blockmaps, and release-local metadata",
      "Bind native platform qualification receipts"]) success(run(find(signingSteps, name)));
    const signed = path.join(source, "signed");
    const files = fs.readdirSync(signed, { withFileTypes: true }).filter((entry) => entry.isFile()).map((entry) => entry.name);
    assert.equal(files.length, 35);
    assert.equal(files.filter((name) => name.endsWith(".selfhost6.json")).length, 8);
    assert.equal(files.filter((name) => name.endsWith(".yml")).length, 6);
    assert.equal(fs.readdirSync(path.join(signed, "qualification-receipts")).length, 6);
    const checksums = fs.readFileSync(path.join(signed, "SHA256SUMS.txt"), "utf8").trim().split("\n");
    assert.equal(checksums.length, 34);
    assert.ok(checksums.every((line) => !line.endsWith("  SHA256SUMS.txt")));
    fs.cpSync(signed, path.join(source, "release-assets"), { recursive: true });
    success(run(find(verifierSteps, "Independently verify every newly built archive, descriptor, and platform receipt")));
    success(run(find(verifierSteps, "Export immutable release identity")));
    assert.equal(fs.readFileSync(path.join(root, "github-output"), "utf8"), `release-line-id=${policy.releaseLineId}\n`);
    fs.cpSync(signed, path.join(root, "release-assets"), { recursive: true });
    success(run(find(workflow.jobs["selfhost-release"].steps, "Reverify retained release bytes after qualification approval"), root));
    for (const output of [path.join(source, "release-verification/receipt.json"), path.join(root, "release-final-verification/receipt.json")]) {
      const receipt = JSON.parse(fs.readFileSync(output, "utf8"));
      assert.equal(receipt.sourceFullSha, sourceSha);
      assert.equal(receipt.assets.length, 8);
      assert.equal(receipt.platformTargets.length, 6);
      assert.equal(receipt.githubReleaseCreated, false);
    }
  });
  await context.test("a wrong checksum is rejected at the downstream CLI, not just in memory", () => {
    const file = path.join(root, "release-assets/SHA256SUMS.txt");
    const checksum = fs.readFileSync(file, "utf8");
    fs.writeFileSync(file, checksum.replace(/^[0-9a-f]{64}/, "0".repeat(64)));
    const result = run(find(workflow.jobs["selfhost-release"].steps, "Reverify retained release bytes after qualification approval"), root);
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /SHA256SUMS.txt digest differs/);
  });
});
