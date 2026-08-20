#!/usr/bin/env node

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const read = (relativePath) =>
  fs.readFileSync(path.join(repoRoot, relativePath), "utf8");

const write = (root, relativePath, contents, mode) => {
  const destination = path.join(root, relativePath);
  fs.mkdirSync(path.dirname(destination), { recursive: true });
  fs.writeFileSync(destination, contents);
  if (mode) fs.chmodSync(destination, mode);
};

const createPrepushWiringFixture = () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "rtc-prepush-wiring-"));
  const fakeBin = path.join(root, "fake-bin");
  const marker = path.join(root, "events.log");
  fs.mkdirSync(fakeBin);
  fs.copyFileSync(
    path.join(repoRoot, "scripts", "run-rtc-prepush.mjs"),
    path.join(root, "run-rtc-prepush.mjs"),
  );
  fs.mkdirSync(path.join(root, "scripts"));
  fs.renameSync(
    path.join(root, "run-rtc-prepush.mjs"),
    path.join(root, "scripts", "run-rtc-prepush.mjs"),
  );
  const sentinel = (label, exitCode = 0) =>
    `import fs from "node:fs";
fs.appendFileSync(process.env.RTC_WIRING_MARKER, ${JSON.stringify(`${label}\n`)});
process.exit(${exitCode});
`;
  write(
    root,
    "scripts/test-rtc-stress-completion-barrier-contract.mjs",
    sentinel("quiescence-contract"),
  );
  write(
    root,
    "scripts/rtc-ci-release-gate.test.mjs",
    sentinel("ci-release-gate"),
  );
  write(
    root,
    "scripts/desktop-release-preflight.mjs",
    sentinel("after-contracts", 91),
  );
  write(
    root,
    "fake-bin/pnpm",
    `#!/bin/sh
case "$*" in
  *test:rtc-stress-completion-barrier*)
    exec "${process.execPath}" --test scripts/test-rtc-stress-completion-barrier-contract.mjs ;;
  *test:rtc-ci-release-gate*)
    exec "${process.execPath}" --test scripts/rtc-ci-release-gate.test.mjs ;;
  *) exit 92 ;;
esac
`,
    0o755,
  );
  const git = (...args) => {
    const result = spawnSync("git", args, {
      cwd: root,
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    });
    assert.equal(result.status, 0, result.stderr);
  };
  git("init", "--quiet");
  git("config", "user.email", "rtc-wiring@example.invalid");
  git("config", "user.name", "RTC Wiring");
  git("add", ".");
  git("commit", "--quiet", "-m", "fixture");
  return {
    dispose: () => fs.rmSync(root, { force: true, recursive: true }),
    marker,
    root,
  };
};

const packageScripts = JSON.parse(read("package.json")).scripts;
const expandPackageCommands = (source) => {
  let closure = source;
  const expanded = new Set();
  let changed = true;
  while (changed) {
    changed = false;
    for (const [name, command] of Object.entries(packageScripts)) {
      if (expanded.has(name)) continue;
      const invocation = new RegExp(
        `\\bpnpm(?:\\s+run)?\\s+${name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}\\b`,
      );
      if (!invocation.test(closure)) continue;
      expanded.add(name);
      closure += `\n${command}`;
      changed = true;
    }
  }
  return closure;
};

test("rtc:prepush executes both mandatory contract suites before builds", () => {
  const fixture = createPrepushWiringFixture();
  try {
    const env = {
      ...process.env,
      PATH: `${path.join(fixture.root, "fake-bin")}${path.delimiter}${process.env.PATH}`,
      RTC_WIRING_MARKER: fixture.marker,
    };
    delete env.NODE_TEST_CONTEXT;
    const result = spawnSync(
      process.execPath,
      [path.join(fixture.root, "scripts", "run-rtc-prepush.mjs")],
      {
        cwd: fixture.root,
        encoding: "utf8",
        env,
        stdio: ["ignore", "pipe", "pipe"],
        timeout: 15_000,
      },
    );
    if (result.error) throw result.error;
    const events = fs.existsSync(fixture.marker)
      ? fs.readFileSync(fixture.marker, "utf8").trim().split("\n")
      : [];
    assert.deepEqual(
      events,
      ["quiescence-contract", "ci-release-gate", "after-contracts"],
      `rtc:prepush contract wiring drifted:\n${result.stdout}\n${result.stderr}`,
    );
    assert.notEqual(result.status, 0, "fixture stop must keep long builds disabled");
  } finally {
    fixture.dispose();
  }
});

test("formal RTC release gate executes both contracts and this wiring proof", () => {
  const workflow = read(".github/workflows/build-desktop-release.yml");
  const start = workflow.indexOf("  rtc-release-gate:");
  const end = workflow.indexOf("\n  rtc-browser-e2e:", start);
  assert.ok(start >= 0 && end > start, "formal RTC release job is missing");
  const commandClosure = expandPackageCommands(workflow.slice(start, end));
  for (const required of [
    "scripts/test-rtc-stress-completion-barrier-contract.mjs",
    "scripts/rtc-ci-release-gate.test.mjs",
    "scripts/test-rtc-contract-wiring.mjs",
  ]) {
    assert.match(
      commandClosure,
      new RegExp(required.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")),
      `formal RTC release gate does not execute ${required}`,
    );
  }
});
