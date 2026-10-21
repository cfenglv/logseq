import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import fs from "node:fs";
import { createRequire } from "node:module";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { draftInventorySha256 } from "./verify-draft-release.mjs";
import { readReleasePolicy } from "../lib/selfhost6-release-identity.mjs";
import { traditionalReleaseAssetNames } from "../lib/selfhost6-update-feed.mjs";

const { parse } = createRequire(import.meta.url)("yaml");
const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const workflow = parse(fs.readFileSync(path.join(repoRoot, ".github/workflows/build-desktop-release.yml"), "utf8"));
const steps = workflow.jobs["selfhost-release"].steps;
const before = steps.find(({ name }) => /^Verify .*Draft before/.test(name ?? ""));
const remove = steps.find(({ name }) => name === "Remove only obsolete assets from the verified Draft");
const publish = steps.find(({ name }) => name === "Create the reviewed release as a draft");
const after = steps.find(({ name }) => name === "Bind and verify the exact reviewed Draft");
const policy = readReleasePolicy(path.join(repoRoot, "resources/updater/selfhost-release-policy.json"));
const version = policy.sourceVersion;
const oldSource = "a".repeat(40);
const newSource = "b".repeat(40);
const releaseId = 424242;
const oldReleaseId = 777777;
const sha256 = (bytes) => `sha256:${createHash("sha256").update(bytes).digest("hex")}`;

// Only the external GitHub API/action boundary is synthetic. Run the checked-in
// workflow shell and verifier CLI, using physical files (not import-only tests).
const fakeGh = `#!/usr/bin/env node
const fs = require("node:fs");
const args = process.argv.slice(2);
const state = JSON.parse(fs.readFileSync(process.env.DRAFT_TEST_STATE));
const methodIndex = args.indexOf("--method");
const method = methodIndex < 0 ? "GET" : args[methodIndex + 1];
const route = args.find((value) => value.startsWith("repos/"));
if (args[0] !== "api" || !route?.startsWith("repos/example/release-fixture/")) process.exit(90);
state.calls.push({ method, route });
const save = () => fs.writeFileSync(process.env.DRAFT_TEST_STATE, JSON.stringify(state));
const reply = (value) => { save(); process.stdout.write(JSON.stringify(value) + "\\n"); };
if (method === "GET" && route.includes("/git/matching-refs/tags/")) reply(state.tagRefs);
else if (method === "GET" && route.endsWith("/releases?per_page=100")) reply(state.releases);
else if (method === "DELETE" && /\\/releases\\/assets\\/[0-9]+$/.test(route)) {
  const id = Number(route.split("/").at(-1));
  const release = state.releases.find((item) => item.assets.some((asset) => asset.id === id));
  if (!release) { save(); process.exit(44); }
  release.assets = release.assets.filter((asset) => asset.id !== id); reply({});
} else {
  const match = route.match(/\\/releases\\/([0-9]+)(\\/assets\\?per_page=100)?$/);
  const release = state.releases.find((item) => item.id === Number(match?.[1]));
  if (!release) { save(); process.exit(44); }
  if (method === "GET") reply(match[2] ? release.assets : release);
  else if (method === "PATCH" && !match[2]) {
    for (let index = 0; index < args.length; index++) {
      if (args[index] !== "-f" && args[index] !== "-F") continue;
      const typed = args[index] === "-F", pair = args[++index], split = pair.indexOf("=");
      const key = pair.slice(0, split), value = pair.slice(split + 1);
      release[key] = typed ? JSON.parse(value) : value;
    }
    reply(release);
  } else { save(); process.exit(45); }
}
`;

function fixture(context, mode) {
  const root = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), "draft-workflow-entry-")));
  context.after(() => fs.rmSync(root, { recursive: true, force: true }));
  for (const name of ["bin", "temp", "release-assets"]) fs.mkdirSync(path.join(root, name));
  const files = [
    "scripts/selfhost6/verify-draft-release.mjs",
    "scripts/lib/selfhost6-release-identity.mjs",
    "scripts/lib/selfhost6-update-feed.mjs",
    "resources/updater/project-update-signature.mjs",
    "resources/updater/selfhost-release-policy.json",
  ];
  for (const name of files) {
    const destination = path.join(root, "release-source", name);
    fs.mkdirSync(path.dirname(destination), { recursive: true });
    fs.copyFileSync(path.join(repoRoot, name), destination);
  }
  fs.writeFileSync(path.join(root, "bin/gh"), fakeGh, { mode: 0o700 });
  const assets = traditionalReleaseAssetNames(policy, version).map((name, index) => {
    const bytes = Buffer.from(`replacement:${name}`);
    fs.writeFileSync(path.join(root, "release-assets", name), bytes);
    return { id: 2000 + index, name, size: bytes.length, digest: sha256(bytes) };
  });
  const legacyNames = ["darwin-arm64.dmg", "darwin-arm64.zip", "darwin-x64.dmg", "darwin-x64.zip",
    "linux-arm64.AppImage", "linux-x64.AppImage", "win32-arm64.exe", "win32-x64.exe"]
    .flatMap((name) => [`Logseq-${name}`, `Logseq-${name}.selfhost6.json`]);
  const legacyAssets = legacyNames.map((name, index) => {
    const bytes = Buffer.from(`frozen:${name}`);
    return { id: 1000 + index, name, size: bytes.length, digest: sha256(bytes) };
  });
  const release = {
    id: releaseId, tag_name: version, target_commitish: oldSource,
    name: `Logseq ${version}`, body: `Source commit ${oldSource}.`,
    draft: true, prerelease: false, published_at: null, assets: legacyAssets,
  };
  const oldRelease = {
    ...release, id: oldReleaseId, tag_name: "2.0.1-selfhost.7",
    name: "Logseq 2.0.1-selfhost.7", assets: [{ ...legacyAssets[0], id: 99 }],
  };
  const secrets = {
    SELFHOST_DRAFT_RELEASE_ID: "", SELFHOST_DRAFT_TARGET_FULL_SHA: "",
    SELFHOST_DRAFT_ASSET_INVENTORY_SHA256: "", SELFHOST_DRAFT_ASSET_INVENTORY_JSON: "",
  };
  if (mode !== "create") Object.assign(secrets, {
    SELFHOST_DRAFT_RELEASE_ID: String(mode === "wrong" ? oldReleaseId : releaseId),
    SELFHOST_DRAFT_TARGET_FULL_SHA: oldSource,
    SELFHOST_DRAFT_ASSET_INVENTORY_SHA256: draftInventorySha256(legacyAssets),
    SELFHOST_DRAFT_ASSET_INVENTORY_JSON: JSON.stringify(legacyAssets),
  });
  const statePath = path.join(root, "state.json");
  fs.writeFileSync(statePath, JSON.stringify({
    releases: mode === "create" ? [oldRelease] : [oldRelease, release], tagRefs: [], calls: [],
  }));
  const expressions = {
    "github.token": "synthetic-token",
    "needs.selfhost-release-verifier.outputs.product-source-sha": newSource,
    "env.SELFHOST6_TARGET_VERSION": version,
    "steps.publish-draft.outputs.id": String(releaseId),
    ...Object.fromEntries(Object.entries(secrets).map(([key, value]) => [`secrets.${key}`, value])),
  };
  const expand = (value) => String(value).replace(/\$\{\{\s*(.*?)\s*\}\}/g, (_, key) => {
    assert.ok(Object.hasOwn(expressions, key), `unhandled workflow expression: ${key}`);
    return expressions[key];
  });
  const state = () => JSON.parse(fs.readFileSync(statePath, "utf8"));
  const save = (value) => fs.writeFileSync(statePath, JSON.stringify(value));
  const run = (step) => {
    assert.ok(step?.run, "actual workflow run step is required");
    const env = {
      PATH: `${path.join(root, "bin")}${path.delimiter}${process.env.PATH}`,
      GH_TOKEN: "synthetic-token", GITHUB_REPOSITORY: "example/release-fixture",
      RUNNER_TEMP: path.join(root, "temp"), SELFHOST6_TARGET_VERSION: version,
      DRAFT_TEST_STATE: statePath,
      ...Object.fromEntries(Object.entries(step.env ?? {}).map(([key, value]) => [key, expand(value)])),
    };
    return spawnSync("bash", ["-e", "-o", "pipefail", "-c", expand(step.run)], {
      cwd: root, env, encoding: "utf8", timeout: 15000,
    });
  };
  const json = (name) => JSON.parse(fs.readFileSync(path.join(root, "temp", name), "utf8"));
  const deletionList = () => fs.readFileSync(path.join(root, "temp/selfhost-draft-obsolete-asset-ids.txt"), "utf8");
  const action = () => {
    assert.equal(publish.uses, "softprops/action-gh-release@v2");
    for (const [key, value] of Object.entries({ draft: true, prerelease: false, make_latest: false,
      fail_on_unmatched_files: true, overwrite_files: true })) assert.equal(publish.with[key], value);
    const names = expand(publish.with.files).trim().split(/\s+/).map((name) => path.basename(name));
    assert.deepEqual(new Set(names), new Set(assets.map(({ name }) => name)));
    assert.equal(expand(publish.with.tag_name), version);
    assert.equal(expand(publish.with.target_commitish), newSource);
    const current = state();
    let target = current.releases.find(({ tag_name: tagName }) => tagName === version);
    if (!target) {
      target = { ...release, target_commitish: newSource };
      current.releases.push(target);
    }
    Object.assign(target, { assets, name: expand(publish.with.name), body: expand(publish.with.body) });
    save(current);
    if (publish.id) expressions[`steps.${publish.id}.outputs.id`] = String(target.id);
  };
  return { root, assets, legacyAssets, oldRelease, state, save, run, json, deletionList, action, expressions };
}

function success(result) {
  assert.equal(result.status, 0, `workflow shell failed: ${result.error?.code ?? ""}\n${result.stdout}${result.stderr}`);
}

function verifyCompleted(input) {
  const result = input.run(after);
  success(result);
  assert.match(result.stdout, /draft-staging-verified/);
  const staged = input.json("selfhost-draft-after.json");
  assert.equal(staged.id, releaseId);
  assert.equal(staged.target_commitish, newSource);
  assert.equal(staged.draft, true);
  assert.equal(staged.published_at, null);
  assert.deepEqual(input.json("selfhost-draft-assets-after.json"), input.assets);
  assert.deepEqual(input.json("selfhost-draft-tag-refs-after.json"), []);
  assert.equal(input.json("selfhost-releases-after.json").length, 2);
  assert.deepEqual(input.state().releases.find(({ id }) => id === oldReleaseId), input.oldRelease);
  assert.equal(input.state().calls.some(({ method }) => method === "POST"), false);
}

test("actual Draft workflow: absent target creates a new Draft and passes its ID downstream", (context) => {
  const input = fixture(context, "create");
  success(input.run(before));
  assert.deepEqual(input.json("selfhost-draft-tag-refs-before.json"), []);
  assert.deepEqual(input.json("selfhost-releases-before.json"), [input.oldRelease]);
  assert.equal(input.deletionList().trim(), "");
  success(input.run(remove));
  assert.equal(input.state().calls.every(({ method }) => method === "GET"), true);
  input.action();
  verifyCompleted(input);
});

test("actual Draft workflow: frozen target replaces only obsolete assets and remains a Draft", (context) => {
  const input = fixture(context, "replace");
  success(input.run(before));
  assert.equal(input.json("selfhost-draft-before.json").id, releaseId);
  assert.deepEqual(input.json("selfhost-draft-assets-before.json"), input.legacyAssets);
  const expected = input.legacyAssets.filter(({ name }) => !input.assets.some((asset) => asset.name === name))
    .map(({ id }) => id).sort((left, right) => left - right);
  assert.deepEqual(input.deletionList().trim().split("\n").map(Number), expected);
  success(input.run(remove));
  assert.deepEqual(input.state().calls.filter(({ method }) => method === "DELETE")
    .map(({ route }) => Number(route.split("/").at(-1))).sort((left, right) => left - right), expected);
  input.action();
  verifyCompleted(input);
});

test("actual Draft workflow: frozen ID pointing at old .7 stops before any mutation", (context) => {
  const input = fixture(context, "wrong");
  const initial = input.state().releases;
  const result = input.run(before);
  assert.equal(result.status, 1);
  assert.match(result.stderr, /Draft release verification failed/);
  assert.equal(fs.existsSync(path.join(input.root, "temp/selfhost-draft-obsolete-asset-ids.txt")), false);
  assert.equal(input.state().calls.every(({ method }) => method === "GET"), true);
  assert.deepEqual(input.state().releases, initial);
});

test("actual Draft workflow: an incorrect action output cannot retag old .7", (context) => {
  for (const mode of ["create", "replace"]) {
    const input = fixture(context, mode);
    success(input.run(before));
    input.action();
    input.expressions["steps.publish-draft.outputs.id"] = String(oldReleaseId);
    const result = input.run(after);
    assert.equal(result.status, 1);
    assert.equal(input.state().calls.every(({ method }) => method === "GET"), true);
    assert.deepEqual(input.state().releases.find(({ id }) => id === oldReleaseId), input.oldRelease);
  }
});

test("actual Draft workflow: absent or partial binding cannot adopt an existing Draft", (context) => {
  for (const partial of [false, true]) {
    const input = fixture(context, "replace");
    for (const key of Object.keys(input.expressions).filter((key) => key.startsWith("secrets."))) {
      input.expressions[key] = "";
    }
    if (partial) input.expressions["secrets.SELFHOST_DRAFT_TARGET_FULL_SHA"] = oldSource;
    const initial = input.state().releases;
    const result = input.run(before);
    assert.equal(result.status, 1);
    assert.equal(fs.existsSync(path.join(input.root, "temp/selfhost-draft-obsolete-asset-ids.txt")), false);
    assert.equal(input.state().calls.every(({ method }) => method === "GET"), true);
    assert.deepEqual(input.state().releases, initial);
  }
});
