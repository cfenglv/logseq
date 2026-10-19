#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync, spawn } from "node:child_process";
import { fileURLToPath } from "node:url";

import {
  defaultSessionFile,
  readQualificationSession,
} from "./phase7-qualified-session.mjs";

const scriptPath = fileURLToPath(import.meta.url);
const root = path.resolve(path.dirname(scriptPath), "../..");
const fullSha = /^[0-9a-f]{40}$/;

const inside = (parent, child) => {
  const relative = path.relative(parent, child);
  return relative === "" || (!relative.startsWith("..") && !path.isAbsolute(relative));
};

const resolvedExistingOrLexical = (target) => {
  let cursor = path.resolve(target);
  const suffix = [];
  while (!fs.existsSync(cursor)) {
    const parent = path.dirname(cursor);
    assert.notEqual(parent, cursor, `no existing parent for ${target}`);
    suffix.unshift(path.basename(cursor));
    cursor = parent;
  }
  return path.join(fs.realpathSync(cursor), ...suffix);
};

export function parseArgs(argv) {
  const values = {};
  for (let index = 0; index < argv.length; index += 2) {
    const key = argv[index];
    const value = argv[index + 1];
    assert.ok(key?.startsWith("--") && key.length > 2 && value !== undefined,
      `invalid argument near ${key ?? "end"}`);
    assert.equal(values[key.slice(2)], undefined, `duplicate ${key}`);
    values[key.slice(2)] = value;
  }
  assert.deepEqual(Object.keys(values).sort(), ["mode", "session-file"].filter((key) => values[key]).sort(),
    "only --mode and optional --session-file are accepted");
  assert.ok(values.mode, "missing --mode");
  assert.ok(["describe", "launch"].includes(values.mode), "--mode must be describe or launch");
  return {
    mode: values.mode,
    sessionFile: path.resolve(values["session-file"] ?? defaultSessionFile),
  };
}

function validateQualificationPaths({ qualificationRoot, testHome, userData, actualHome }) {
  assert.ok(fs.existsSync(qualificationRoot), "qualification root must already exist");
  const realRoot = fs.realpathSync(qualificationRoot);
  const allowedTemporaryRoots = [os.tmpdir(), "/private/tmp", "/tmp"]
    .filter(fs.existsSync)
    .map((entry) => fs.realpathSync(entry));
  assert.ok(allowedTemporaryRoots.some((entry) => inside(entry, realRoot)),
    "qualification root must be under a temporary filesystem root");
  assert.match(path.basename(realRoot), /^selfhost6-phase7[0-9a-z._-]*$/i,
    "qualification root must use the selfhost6-phase7 prefix");

  const realActualHome = resolvedExistingOrLexical(actualHome);
  assert.ok(!inside(realActualHome, realRoot), "qualification root cannot be inside real Home");
  const controlled = [
    ["test Home", testHome],
    ["userData", userData],
  ].map(([label, target]) => [label, resolvedExistingOrLexical(target)]);
  for (const [label, target] of controlled) {
    assert.ok(!inside(realActualHome, target), `${label} cannot be real Home or inside real Home`);
    assert.ok(inside(realRoot, target), `${label} must be inside qualification root`);
  }
  assert.notEqual(controlled[0][1], controlled[1][1], "test Home and userData must be separate");
}

function readArtifactIdentity(executable) {
  assert.ok(fs.existsSync(executable), "packaged App executable does not exist");
  fs.accessSync(executable, fs.constants.X_OK);
  const contentsDir = path.resolve(path.dirname(executable), "..");
  const manifestPath = path.join(contentsDir, "Resources/updater/TARGET_BUILD_MANIFEST.json");
  const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
  assert.match(manifest["target-source-full-sha"], fullSha);
  assert.equal(manifest["target-version"], "2.0.1-selfhost.6");
  assert.equal(manifest["release-line-id"], "selfhost-official-architecture-v1");
  assert.equal(manifest.platform, "darwin");
  assert.equal(manifest.arch, "arm64");
  return {
    sourceFullSha: manifest["target-source-full-sha"],
    version: manifest["target-version"],
    releaseLineId: manifest["release-line-id"],
    platform: manifest.platform,
    arch: manifest.arch,
    manifestPath,
  };
}

export function buildLaunchSpec({
  qualificationRoot,
  testHome,
  userData,
  executable,
  sourceFullSha,
  debugPort,
  inheritedEnv = process.env,
  actualHome = os.homedir(),
}) {
  validateQualificationPaths({ qualificationRoot, testHome, userData, actualHome });
  const artifact = readArtifactIdentity(executable);
  assert.equal(artifact.sourceFullSha, sourceFullSha, "artifact source does not match --source-sha");
  const env = {
    ...inheritedEnv,
    HOME: testHome,
    LOGSEQ_TEST_HOME_DIR: testHome,
    LOGSEQ_TEST_USER_DATA_DIR: userData,
  };
  delete env.NODE_USE_ENV_PROXY;
  return {
    command: fs.realpathSync(executable),
    args: [`--user-data-dir=${userData}`, `--remote-debugging-port=${debugPort}`],
    env,
    artifact,
    qualification: { qualificationRoot, testHome, userData, debugPort },
  };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const session = readQualificationSession(args.sessionFile);
  const options = {
    mode: args.mode,
    qualificationRoot: session.qualificationRoot,
    testHome: session.testHome,
    userData: session.userData,
    executable: session.appExecutable,
    sourceFullSha: session.sourceFullSha,
    debugPort: session.debugPort,
  };
  const spec = buildLaunchSpec(options);
  const checkoutSourceFullSha = execFileSync("git", ["rev-parse", "HEAD"], {
    cwd: root,
    encoding: "utf8",
  }).trim();
  assert.equal(checkoutSourceFullSha, options.sourceFullSha,
    "artifact source does not match checkout HEAD");
  const checkoutStatus = execFileSync("git", ["status", "--porcelain"], {
    cwd: root,
    encoding: "utf8",
  });
  assert.equal(checkoutStatus, "", "qualification checkout must be clean");
  fs.mkdirSync(options.testHome, { recursive: true });
  fs.mkdirSync(options.userData, { recursive: true });
  const description = {
    command: spec.command,
    args: spec.args,
    qualification: spec.qualification,
    artifact: spec.artifact,
    environment: {
      HOME: spec.env.HOME,
      LOGSEQ_TEST_HOME_DIR: spec.env.LOGSEQ_TEST_HOME_DIR,
      LOGSEQ_TEST_USER_DATA_DIR: spec.env.LOGSEQ_TEST_USER_DATA_DIR,
      NODE_USE_ENV_PROXY: spec.env.NODE_USE_ENV_PROXY ?? null,
    },
  };
  console.log(JSON.stringify(description));
  if (options.mode === "describe") return;

  const child = spawn(spec.command, spec.args, {
    cwd: root,
    env: spec.env,
    stdio: "inherit",
  });
  for (const signal of ["SIGINT", "SIGTERM"]) {
    process.once(signal, () => child.kill(signal));
  }
  const exit = await new Promise((resolve, reject) => {
    child.once("error", reject);
    child.once("exit", (code, signal) => resolve({ code, signal }));
  });
  if (exit.signal) process.kill(process.pid, exit.signal);
  process.exitCode = exit.code ?? 1;
}

if (path.resolve(process.argv[1] ?? "") === scriptPath) {
  main().catch((error) => {
    console.error(`Phase 7 qualified launch failed: ${error.message}`);
    process.exitCode = 1;
  });
}
