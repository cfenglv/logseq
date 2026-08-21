import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import test from "node:test";

const deploymentSurfaceFiles = [
  ".github/workflows/build-desktop-release.yml",
  ".github/workflows/clj-rtc-e2e.yml",
  "deps/db-sync/worker/wrangler.selfhost6.example.jsonc",
];

function trackedFiles() {
  const result = spawnSync("git", ["ls-files", "-z"], { encoding: "utf8" });
  assert.equal(result.status, 0, result.stderr);
  return result.stdout.split("\0").filter(Boolean);
}

test("the public deployment surface contains no concrete Worker URL or personal workspace path", () => {
  for (const file of deploymentSurfaceFiles) {
    const source = fs.readFileSync(file, "utf8");
    assert.doesNotMatch(source, /https?:\/\/[^\s"']+\.workers\.dev/);
    assert.doesNotMatch(source, /\/Users\/[^/]+\/Documents\/WorkSpace/);
  }
  const benchmark = fs.readFileSync("scripts/test/logseq/selfhost6-editor-benchmark.test.cjs", "utf8");
  assert.doesNotMatch(benchmark, /\/Users\/[^/]+\/Documents\/WorkSpace/);
});

test("internal migration records and the real deployment config stay untracked", () => {
  const files = trackedFiles();
  assert.equal(files.some((file) => file.startsWith("docs/selfhost6-phase")), false);
  assert.equal(files.some((file) => /^docs\/SELFHOST[67]_/.test(file)), false);
  assert.equal(files.includes("deps/db-sync/worker/wrangler.selfhost6.jsonc"), false);
  assert.equal(files.includes("deps/db-sync/worker/wrangler.selfhost6.example.jsonc"), true);
});
