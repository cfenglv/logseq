#!/usr/bin/env node

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const preloadPath = path.join(
  repoRoot,
  "scripts",
  "fixtures",
  "electron-project-signature-loader-preload.cjs",
);
const productionBundle = path.join(repoRoot, "static", "electron.js");
const probeBundle = path.join(
  repoRoot,
  "static",
  "electron-project-signature-loader-contract.js",
);
const baseEnv = { ...process.env, NODE_ENV: "production" };
delete baseEnv.NODE_OPTIONS;

const run = (label, command, args, options = {}) => {
  const result = spawnSync(command, args, {
    cwd: repoRoot,
    env: { ...baseEnv, ...options.env },
    encoding: "utf8",
    stdio: options.capture ? "pipe" : "inherit",
  });
  assert.equal(
    result.status,
    0,
    `${label} failed${options.capture ? `:\n${result.stdout}${result.stderr}` : ""}`,
  );
  return result;
};

run("production Electron release", "pnpm", ["cljs:release-electron"]);

const productionSource = fs.readFileSync(productionBundle, "utf8");
assert.match(
  productionSource,
  /project-updater-signature\.mjs/,
  "production Electron CJS must retain the real packaged signature-module path",
);
for (const forbidden of [
  /--experimental-vm-modules/,
  /electron-project-signature-loader-preload/,
  /LOGSEQ_PROJECT_SIGNATURE_CONTRACT_SCENARIO/,
  /signature-module-stub/i,
  /bypass-project-signature/i,
]) {
  assert.doesNotMatch(
    productionSource,
    forbidden,
    `production Electron CJS must not retain test/bypass marker ${forbidden}`,
  );
}

const preloadSource = fs.readFileSync(preloadPath, "utf8");
for (const forbidden of [
  /node:vm/,
  /runInThisContext/,
  /experimental-vm-modules/,
  /project-updater-signature\.mjs/,
]) {
  assert.doesNotMatch(
    preloadSource,
    forbidden,
    `contract preload must not alter or replace the ESM loader: ${forbidden}`,
  );
}

const probeBuild = [
  "-M:test",
  "release",
  "electron-project-signature-loader-contract",
];
run("production-mode signature loader probe", "clojure", probeBuild);

const parseResult = (result, scenario) => {
  const line = result.stdout
    .split(/\r?\n/)
    .find((candidate) =>
      candidate.startsWith("PROJECT_SIGNATURE_LOADER_CONTRACT "),
    );
  assert.ok(line, `${scenario} probe did not emit a contract result`);
  return JSON.parse(line.slice("PROJECT_SIGNATURE_LOADER_CONTRACT ".length));
};
const runProbe = (scenario, extraEnv = {}) =>
  run(
    `${scenario} signature loader probe`,
    process.execPath,
    ["--require", preloadPath, probeBundle],
    {
      capture: true,
      env: {
        ...extraEnv,
        LOGSEQ_PROJECT_SIGNATURE_CONTRACT_SCENARIO: scenario,
      },
    },
  );

const success = parseResult(runProbe("success"), "success");
assert.deepEqual(success, {
  scenario: "success",
  algorithm: "ed25519-sha512-manifest-v1",
  "same-promise": true,
});

const missingResources = fs.mkdtempSync(
  path.join(os.tmpdir(), "logseq-missing-signature-resources-"),
);
const failure = parseResult(
  runProbe("failure", {
    LOGSEQ_PROJECT_SIGNATURE_RESOURCES_PATH: missingResources,
  }),
  "failure",
);
assert.equal(failure.scenario, "failure");
assert.equal(failure["same-promise"], true);
assert.equal(failure.rejected, true);
assert.match(
  failure.code ?? "ERR_MODULE_NOT_FOUND",
  /ERR_MODULE_NOT_FOUND/,
);

console.log(
  "Electron production CJS -> real ESM signature loader contract: PASS",
);
