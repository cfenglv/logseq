#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync, spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

import {
  defaultSessionFile,
  readQualificationSession,
} from "./phase7-qualified-session.mjs";

const scriptPath = fileURLToPath(import.meta.url);
const root = path.resolve(path.dirname(scriptPath), "../..");

const inside = (parent, child) => {
  const relative = path.relative(parent, child);
  return relative === "" || (!relative.startsWith("..") && !path.isAbsolute(relative));
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
  assert.deepEqual(Object.keys(values).sort(), ["command", "session-file"].filter((key) => values[key]).sort(),
    "only --command and optional --session-file are accepted");
  assert.ok(values.command, "missing --command");
  assert.ok(["status", "start", "stop"].includes(values.command),
    "--command must be status, start, or stop");
  return {
    sessionFile: path.resolve(values["session-file"] ?? defaultSessionFile),
    command: values.command,
  };
}

export function buildCommandSpec({ qualificationRoot, testHome, config, graph, command }) {
  assert.ok(fs.existsSync(qualificationRoot), "qualification root must already exist");
  const realRoot = fs.realpathSync(qualificationRoot);
  const temporaryRoots = [os.tmpdir(), "/private/tmp", "/tmp"]
    .filter(fs.existsSync)
    .map((entry) => fs.realpathSync(entry));
  assert.ok(temporaryRoots.some((entry) => inside(entry, realRoot)),
    "qualification root must be under a temporary filesystem root");
  assert.match(path.basename(realRoot), /^selfhost6-phase7[0-9a-z._-]*$/i,
    "qualification root must use the selfhost6-phase7 prefix");

  const realTestHome = fs.realpathSync(testHome);
  const realConfig = fs.realpathSync(config);
  assert.ok(inside(realRoot, realTestHome), "test Home must be inside qualification root");
  assert.ok(inside(realRoot, realConfig), "config must be inside qualification root");
  assert.ok(fs.statSync(realConfig).isFile(), "config must be a file");
  assert.match(graph, /^selfhost6-phase7-[0-9a-z-]+$/,
    "graph must be the isolated Phase 7 qualification graph");
  assert.ok(["status", "start", "stop"].includes(command),
    "qualification CLI command must be status, start, or stop");

  const commandPath = process.execPath;
  const baseArgs = [
    path.join(root, "static/logseq-cli.js"),
    "--root-dir", path.join(realTestHome, "logseq"),
    "--config", realConfig,
    "--graph", graph,
    "--output", "json",
  ];
  return {
    command: commandPath,
    args: [...baseArgs, "sync", command],
    cleanup: command === "stop"
      ? { command: commandPath, args: [...baseArgs, "server", "stop"] }
      : null,
  };
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  const session = readQualificationSession(args.sessionFile);
  const options = {
    qualificationRoot: session.qualificationRoot,
    testHome: session.testHome,
    config: session.config,
    graph: session.graph,
    sourceFullSha: session.sourceFullSha,
    command: args.command,
  };
  const checkoutSourceFullSha = execFileSync("git", ["rev-parse", "HEAD"], {
    cwd: root,
    encoding: "utf8",
  }).trim();
  assert.equal(checkoutSourceFullSha, options.sourceFullSha,
    "qualification source does not match checkout HEAD");
  const checkoutStatus = execFileSync("git", ["status", "--porcelain"], {
    cwd: root,
    encoding: "utf8",
  });
  assert.equal(checkoutStatus, "", "qualification checkout must be clean");

  const spec = buildCommandSpec(options);
  const result = spawnSync(spec.command, spec.args, {
    cwd: root,
    env: { ...process.env, HOME: options.testHome, LOGSEQ_TEST_HOME_DIR: options.testHome },
    stdio: "inherit",
  });
  const cleanupResult = spec.cleanup && spawnSync(spec.cleanup.command, spec.cleanup.args, {
    cwd: root,
    env: { ...process.env, HOME: options.testHome, LOGSEQ_TEST_HOME_DIR: options.testHome },
    stdio: ["inherit", "ignore", "inherit"],
  });
  if (result.error) throw result.error;
  if (cleanupResult?.error) throw cleanupResult.error;
  process.exitCode = result.status !== 0
    ? (result.status ?? 1)
    : (cleanupResult?.status ?? 0);
}

if (path.resolve(process.argv[1] ?? "") === scriptPath) {
  try {
    main();
  } catch (error) {
    console.error(`Phase 7 qualified CLI command failed: ${error.message}`);
    process.exitCode = 1;
  }
}
