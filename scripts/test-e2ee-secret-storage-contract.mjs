#!/usr/bin/env node

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const read = (relativePath) =>
  fs.readFileSync(path.join(repoRoot, relativePath), "utf8");

const shadow = read("shadow-cljs.edn");
const packageJson = JSON.parse(read("package.json"));
const platformSource = read("src/main/frontend/worker/platform/node.cljs");
const cliPreflight = read("cli-e2e/src/logseq/cli/e2e/preflight.clj");
const testDefine =
  "frontend.worker.platform.node/TEST-SECRET-STORAGE true";

const bundleFlag = process.argv.indexOf("--bundle");
let bundlePath;
if (bundleFlag === -1) {
  const result = spawnSync(
    "clojure",
    ["-M:cljs", "release", "db-worker-node"],
    { cwd: repoRoot, encoding: "utf8", stdio: "inherit" },
  );
  assert.equal(result.status, 0, "production db-worker-node release must compile");
  bundlePath = path.join(repoRoot, "static", "db-worker-node.js");
} else {
  const bundleArg = process.argv[bundleFlag + 1];
  assert.ok(bundleArg, "--bundle requires a path");
  bundlePath = path.resolve(repoRoot, bundleArg);
}

const bundle = fs.readFileSync(bundlePath, "utf8");
assert.match(
  bundle,
  /Logseq E2EE/,
  "release bundle must retain the formal production Keychain service",
);
for (const forbidden of [
  /CLI_E2E_TEST/,
  /TEST-SECRET-STORAGE/,
  /platform_node_test\.cljs/,
  /(?:test|fixture|isolated)[._ -]*secret/i,
  /secret[._ -]*(?:test|fixture|isolated)/i,
]) {
  assert.doesNotMatch(
    bundle,
    forbidden,
    `release bundle must not retain test-secret marker ${forbidden}`,
  );
}

const buildBlock = (buildId) => {
  const marker = new RegExp(`^  :${buildId}(?:\\s|$)`, "m").exec(shadow);
  assert.ok(marker, `shadow-cljs.edn must declare :${buildId}`);
  const start = marker.index;
  const afterMarker = start + marker[0].length;
  const next = shadow.slice(afterMarker).search(/^  :[\w-]+(?:\s|$)/m);
  return next === -1
    ? shadow.slice(start)
    : shadow.slice(start, afterMarker + next);
};

assert.match(
  platformSource,
  /\(goog-define\s+TEST-SECRET-STORAGE\s+false\)/,
  "production must default TEST-SECRET-STORAGE to false",
);
assert.doesNotMatch(
  platformSource,
  /CLI_E2E_TEST/,
  "runtime environment variables must not select secret storage",
);
for (const buildId of ["test", "db-sync-backup-memory-test"]) {
  assert.match(
    buildBlock(buildId),
    new RegExp(testDefine.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")),
    `:${buildId} must opt in to compile-time test secret storage`,
  );
}
for (const buildId of ["test-no-worker", "db-worker-node", "electron"]) {
  assert.doesNotMatch(
    buildBlock(buildId),
    /TEST-SECRET-STORAGE\s+true/,
    `:${buildId} must retain production secret storage`,
  );
}

const shadowOptIns = [...shadow.matchAll(
  /frontend\.worker\.platform\.node\/TEST-SECRET-STORAGE\s+true/g,
)];
assert.equal(
  shadowOptIns.length,
  2,
  "only the two dedicated Shadow test targets may enable test secret storage",
);

const scripts = packageJson.scripts ?? {};
const e2eCompile = scripts["db-worker-node:e2e-compile"];
assert.equal(
  typeof e2eCompile,
  "string",
  "package.json must expose the dedicated CLI-E2E db-worker-node compiler",
);
assert.match(e2eCompile, /\b(?:compile|release)\s+db-worker-node\b/);
assert.match(e2eCompile, /--config-merge/);
assert.match(
  e2eCompile,
  /frontend\.worker\.platform\.node\/TEST-SECRET-STORAGE\s+true/,
);

const packageOptIns = Object.entries(scripts).filter(([, command]) =>
  /frontend\.worker\.platform\.node\/TEST-SECRET-STORAGE\s+true/.test(command),
);
assert.deepEqual(
  packageOptIns.map(([name]) => name),
  ["db-worker-node:e2e-compile"],
  "only the dedicated CLI-E2E compile command may enable test secret storage",
);
assert.match(
  cliPreflight,
  /pnpm db-worker-node:[^\s"]*e2e[^\s"]*/,
  "CLI-E2E preflight must select an E2E-specific db-worker-node build",
);

for (const [name, command] of Object.entries(scripts)) {
  if (
    /(?:watch|release|compile)/.test(name) &&
    !name.includes("e2e")
  ) {
    assert.doesNotMatch(
      command,
      /TEST-SECRET-STORAGE\s+true/,
      `${name} must retain production secret storage`,
    );
  }
}
for (const [label, text] of [
  ["package.json", JSON.stringify(packageJson)],
  ["shadow-cljs.edn", shadow],
  ["CLI-E2E preflight", cliPreflight],
]) {
  assert.doesNotMatch(
    text,
    /CLI_E2E_TEST/,
    `${label} must not contain the retired runtime switch`,
  );
}

console.log("E2EE compile-time secret-storage source/build/bundle contract: PASS");
