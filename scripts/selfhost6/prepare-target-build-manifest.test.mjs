import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");

function fixture(context, packageVersion) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "selfhost6-target-build-"));
  context.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const updater = path.join(root, "static/updater");
  fs.mkdirSync(updater, { recursive: true });
  for (const file of [
    "prepare-target-build-manifest.mjs",
    "selfhost-release-policy.json",
    "project-signing-policy.json",
  ]) fs.copyFileSync(path.join(repoRoot, "resources/updater", file), path.join(updater, file));
  fs.writeFileSync(path.join(root, "static/package.json"), `${JSON.stringify({ version: packageVersion })}\n`);
  const scripts = path.join(root, "scripts/lib");
  fs.mkdirSync(scripts, { recursive: true });
  // Only test the manifest owner's build-call contract here. Actual pinned reader
  // preparation/execution is covered by its unit tests and the native probe.
  fs.writeFileSync(path.join(scripts, "windows-archive-reader.mjs"), `
import fs from "node:fs";
import path from "node:path";
export async function prepareWindowsArchiveReader({arch, outputDirectory}) {
  fs.mkdirSync(outputDirectory, {recursive:true});
  fs.writeFileSync(path.join(outputDirectory,"prepared.json"),JSON.stringify({arch}));
}
`);
  return { root, updater, script: path.join(updater, "prepare-target-build-manifest.mjs") };
}

function fakeGit(root, { sourceFullSha = "b".repeat(40), dirty = false } = {}) {
  const bin = path.join(root, "bin");
  fs.mkdirSync(bin);
  const executable = path.join(bin, "git");
  fs.writeFileSync(executable, `#!/usr/bin/env node
const command = process.argv.slice(2).join(" ");
if (command === "status --porcelain") process.stdout.write(${JSON.stringify(dirty ? " M source\n" : "")});
else if (command === "rev-parse HEAD") process.stdout.write(${JSON.stringify(`${sourceFullSha}\n`)});
else process.exitCode = 2;
`, { mode: 0o755 });
  return bin;
}

test("packaging writes the exact platform target manifest and rejects a version split", context => {
  const valid = fixture(context, "2.0.2-selfhost.5");
  const environment = {
    ...process.env,
    SELFHOST6_SOURCE_FULL_SHA: "a".repeat(40),
    SELFHOST6_TARGET_VERSION: "2.0.2-selfhost.5",
    SELFHOST6_TARGET_PLATFORM: "linux",
    SELFHOST6_TARGET_ARCH: "arm64",
  };
  const result = spawnSync(process.execPath, [valid.script], { encoding: "utf8", env: environment });
  assert.equal(result.status, 0, result.stderr);
  const manifest = JSON.parse(
    fs.readFileSync(path.join(valid.updater, "TARGET_BUILD_MANIFEST.json"), "utf8"),
  );
  assert.equal(manifest["target-source-full-sha"], "a".repeat(40));
  assert.equal(manifest["target-version"], "2.0.2-selfhost.5");
  assert.equal(manifest.platform, "linux");
  assert.equal(manifest.arch, "arm64");

  assert.equal(fs.existsSync(path.join(valid.updater, "windows")), false);
  const invalid = fixture(context, "2.0.2-selfhost.2");
  const rejected = spawnSync(process.execPath, [invalid.script], { encoding: "utf8", env: environment });
  assert.notEqual(rejected.status, 0);
  assert.match(rejected.stderr, /static package version must equal/);
});

test("packaging infers immutable build identity when callers omit repeated parameters", context => {
  const valid = fixture(context, "2.0.2-selfhost.5");
  const bin = fakeGit(valid.root);
  const environment = {
    ...process.env,
    PATH: `${bin}${path.delimiter}${process.env.PATH}`,
    SELFHOST6_TARGET_PLATFORM: "darwin",
    SELFHOST6_TARGET_ARCH: "arm64",
  };
  delete environment.SELFHOST6_SOURCE_FULL_SHA;
  delete environment.SELFHOST6_TARGET_VERSION;
  const result = spawnSync(process.execPath, [valid.script], { encoding: "utf8", env: environment });
  assert.equal(result.status, 0, result.stderr);
  const manifest = JSON.parse(
    fs.readFileSync(path.join(valid.updater, "TARGET_BUILD_MANIFEST.json"), "utf8"),
  );
  assert.equal(manifest["target-source-full-sha"], "b".repeat(40));
  assert.equal(manifest["target-version"], "2.0.2-selfhost.5");

  const dirty = fixture(context, "2.0.2-selfhost.5");
  const dirtyBin = fakeGit(dirty.root, { dirty: true });
  const dirtyResult = spawnSync(process.execPath, [dirty.script], {
    encoding: "utf8",
    env: { ...environment, PATH: `${dirtyBin}${path.delimiter}${process.env.PATH}` },
  });
  assert.notEqual(dirtyResult.status, 0);
  assert.match(dirtyResult.stderr, /checkout must be clean/);
});

test("win32 arm64 prepares the native reader before the admitted target manifest", context => {
  const valid = fixture(context, "2.0.2-selfhost.5");
  const environment = {
    ...process.env,
    SELFHOST6_SOURCE_FULL_SHA: "a".repeat(40),
    SELFHOST6_TARGET_VERSION: "2.0.2-selfhost.5",
    SELFHOST6_TARGET_PLATFORM: "win32",
    SELFHOST6_TARGET_ARCH: "arm64",
  };
  const result = spawnSync(process.execPath, [valid.script], { encoding: "utf8", env: environment });
  assert.equal(result.status, 0, result.stderr);
  const manifest = JSON.parse(
    fs.readFileSync(path.join(valid.updater, "TARGET_BUILD_MANIFEST.json"), "utf8"),
  );
  assert.equal(manifest.platform, "win32");
  assert.equal(manifest.arch, "arm64");
  assert.deepEqual(JSON.parse(fs.readFileSync(path.join(valid.updater, "windows/arm64/prepared.json"))), { arch: "arm64" });
});
