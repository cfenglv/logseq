#!/usr/bin/env node

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const preflightRelativePath =
  "scripts/run-desktop-release-preflight.mjs";
const preloadRelativePath =
  "scripts/fixtures/electron-test-preload.cjs";
const preflightPath = path.join(repoRoot, preflightRelativePath);
const preloadPath = path.join(repoRoot, preloadRelativePath);
const probeFlag = "--electron-test-preload-contract-probe";
const probeMarker = "ELECTRON_TEST_PRELOAD_CONTRACT ";
const cases = [];

const test = (name, callback) => cases.push([name, callback]);
const run = (command, args, { cwd = repoRoot } = {}) => {
  const env = { ...process.env };
  delete env.NODE_OPTIONS;
  const result = spawnSync(command, args, {
    cwd,
    encoding: "utf8",
    env,
    shell: false,
    stdio: ["ignore", "pipe", "pipe"],
  });
  if (result.error) throw result.error;
  return {
    output: `${result.stdout ?? ""}${result.stderr ?? ""}`.trim(),
    status: result.status,
  };
};

const probe = ({ candidate, cwd = repoRoot, preflight = preflightPath } = {}) =>
  run(
    process.execPath,
    [
      preflight,
      probeFlag,
      ...(candidate === undefined
        ? []
        : ["--electron-test-preload-candidate", candidate]),
    ],
    { cwd },
  );

const invocationFrom = (result, label) => {
  assert.equal(result.status, 0, `${label} probe failed:\n${result.output}`);
  const markerLine = result.output
    .split(/\r?\n/)
    .find((line) => line.startsWith(probeMarker));
  assert.ok(markerLine, `${label} probe emitted no invocation`);
  return JSON.parse(markerLine.slice(probeMarker.length));
};

const requireArgument = (invocation) => {
  assert.equal(invocation.command, process.execPath);
  assert.equal(invocation.shell, false, "preflight must not invoke a shell");
  assert.equal(invocation.args[0], "--require");
  assert.equal(typeof invocation.args[1], "string");
  return invocation.args[1];
};

const executeSelectedPreload = (invocation, cwd, label) => {
  const selectedPreload = requireArgument(invocation);
  const result = run(
    invocation.command,
    [
      "--require",
      selectedPreload,
      "-e",
      [
        "if (!globalThis.__LOGSEQ_TEST_AUTO_UPDATER__)",
        '  throw new Error("Electron preload did not install its test double");',
        'process.stdout.write("ELECTRON_PRELOAD_LOADED");',
      ].join("\n"),
    ],
    { cwd },
  );
  assert.equal(
    result.status,
    0,
    `${label} could not load selected preload:\n${result.output}`,
  );
  assert.equal(result.output, "ELECTRON_PRELOAD_LOADED");
};

const assertTrackedRegularPreload = (root, selectedPreload) => {
  assert.equal(
    path.isAbsolute(selectedPreload),
    true,
    "selected preload must be absolute",
  );
  assert.equal(
    path.relative(root, selectedPreload).split(path.sep).join("/"),
    preloadRelativePath,
    "preflight selected a different preload path",
  );
  const stat = fs.lstatSync(selectedPreload);
  assert.equal(stat.isFile(), true, "selected preload is not a regular file");
  assert.equal(
    stat.isSymbolicLink(),
    false,
    "selected preload must not be a symlink",
  );
  const tracked = run(
    "git",
    ["ls-files", "--error-unmatch", "--", preloadRelativePath],
    { cwd: root },
  );
  assert.equal(
    tracked.status,
    0,
    `selected preload is not the exact tracked fixture:\n${tracked.output}`,
  );
};

const makeProbeRoot = (name) => {
  const root = path.join(
    fs.mkdtempSync(path.join(os.tmpdir(), "logseq-preload-contract-")),
    name,
  );
  fs.mkdirSync(path.join(root, "scripts", "fixtures"), {
    recursive: true,
  });
  fs.copyFileSync(
    preflightPath,
    path.join(root, preflightRelativePath),
  );
  const initialized = run("git", ["init", "--quiet"], { cwd: root });
  assert.equal(initialized.status, 0, initialized.output);
  return root;
};

const trackProbeRoot = (root) => {
  const added = run("git", ["add", "--", "scripts"], { cwd: root });
  assert.equal(added.status, 0, added.output);
};

test("preflight-selected tracked preload loads from repo and foreign cwd", () => {
  const invocation = invocationFrom(probe(), "repository");
  const selectedPreload = requireArgument(invocation);
  assert.equal(invocation.cwd, repoRoot);
  executeSelectedPreload(invocation, repoRoot, "repository cwd");
  const foreignCwd = fs.mkdtempSync(
    path.join(os.tmpdir(), "logseq-preload-foreign-cwd-"),
  );
  try {
    executeSelectedPreload(invocation, foreignCwd, "foreign cwd");
  } finally {
    fs.rmSync(foreignCwd, { recursive: true, force: true });
  }
  assert.equal(selectedPreload, preloadPath);
  assertTrackedRegularPreload(repoRoot, selectedPreload);
});

test("space-containing repository path remains one shell-free argv item", () => {
  const root = makeProbeRoot("repository path with spaces");
  try {
    fs.copyFileSync(preloadPath, path.join(root, preloadRelativePath));
    trackProbeRoot(root);
    const invocation = invocationFrom(
      probe({
        cwd: os.tmpdir(),
        preflight: path.join(root, preflightRelativePath),
      }),
      "space-containing repository",
    );
    const selectedPreload = requireArgument(invocation);
    assert.match(selectedPreload, / /, "preload path fixture has no space");
    assert.equal(invocation.cwd, root);
    assertTrackedRegularPreload(root, selectedPreload);
    executeSelectedPreload(
      invocation,
      os.tmpdir(),
      "space-containing absolute argv",
    );
  } finally {
    fs.rmSync(path.dirname(root), { recursive: true, force: true });
  }
});

test("preflight rejects bare, missing, directory, and external symlink preload paths", () => {
  const outsideRoot = fs.mkdtempSync(
    path.join(os.tmpdir(), "logseq-preload-outside-"),
  );
  const outsidePreload = path.join(outsideRoot, "outside.cjs");
  fs.writeFileSync(outsidePreload, "globalThis.EXTERNAL_PRELOAD_RAN = true;\n");
  const invalidCandidates = [
    ["bare relative specifier", preloadRelativePath],
    ["missing file", path.join(repoRoot, "scripts", "fixtures", "missing.cjs")],
    ["directory", path.dirname(preloadPath)],
    ["external file", outsidePreload],
  ];
  const accepted = [];
  try {
    for (const [label, candidate] of invalidCandidates) {
      const result = probe({ candidate });
      if (result.status === 0) accepted.push(label);
      else {
        assert.doesNotMatch(
          result.output,
          new RegExp(`^${probeMarker}`, "m"),
          `${label} emitted an executable invocation`,
        );
      }
    }

    const symlinkRoot = makeProbeRoot("external symlink repository");
    try {
      fs.symlinkSync(
        outsidePreload,
        path.join(symlinkRoot, preloadRelativePath),
      );
      trackProbeRoot(symlinkRoot);
      const result = probe({
        preflight: path.join(symlinkRoot, preflightRelativePath),
      });
      if (result.status === 0) accepted.push("tracked external symlink");
      else {
        assert.doesNotMatch(
          result.output,
          new RegExp(`^${probeMarker}`, "m"),
        );
      }
    } finally {
      fs.rmSync(path.dirname(symlinkRoot), {
        recursive: true,
        force: true,
      });
    }
    assert.deepEqual(
      accepted,
      [],
      `preflight accepted unsafe preload candidates: ${accepted.join(", ")}`,
    );
  } finally {
    fs.rmSync(outsideRoot, { recursive: true, force: true });
  }
});

let passed = 0;
let failed = 0;
for (const [name, callback] of cases) {
  try {
    await callback();
    passed += 1;
    console.log(`[desktop-preload-contract] PASS ${name}`);
  } catch (error) {
    failed += 1;
    console.error(
      `[desktop-preload-contract] FAIL ${name}: ${
        error instanceof Error ? error.stack || error.message : error
      }`,
    );
  }
}
console.log(
  `[desktop-preload-contract] SUMMARY passed=${passed} failed=${failed} total=${cases.length}`,
);
if (failed > 0) process.exitCode = 1;
