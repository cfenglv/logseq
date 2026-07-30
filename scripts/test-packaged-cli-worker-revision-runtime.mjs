#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";

const parseArgs = (argv) => {
  const result = {};
  for (let index = 0; index < argv.length; index += 2) {
    const key = argv[index];
    const value = argv[index + 1];
    if (!key?.startsWith("--") || !value) {
      throw new Error(`invalid argument pair: ${key ?? ""} ${value ?? ""}`);
    }
    result[key.slice(2)] = value;
  }
  return result;
};

const parseJsonOutput = (stdout, label) => {
  const lines = stdout
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean);

  for (let index = lines.length - 1; index >= 0; index -= 1) {
    try {
      return JSON.parse(lines[index]);
    } catch {
      // Ignore diagnostic lines and keep looking for the CLI JSON response.
    }
  }

  throw new Error(
    `${label} did not return a JSON response: stdout=${JSON.stringify(stdout)}`,
  );
};

const formatRunFailure = (label, result) =>
  `${label} failed: status=${result.status} signal=${result.signal ?? "none"} ` +
  `stdout=${JSON.stringify(result.stdout)} stderr=${JSON.stringify(result.stderr)}`;

const args = parseArgs(process.argv.slice(2));
const appPath = path.resolve(args.app || "");
const expectedRevision = args["expected-revision"]?.trim();

if (process.platform !== "darwin") {
  throw new Error(
    `packaged macOS CLI runtime test requires darwin, got ${process.platform}`,
  );
}
if (!args.app) {
  throw new Error("--app is required");
}
if (!expectedRevision) {
  throw new Error("--expected-revision is required");
}

const executable = path.join(appPath, "Contents", "MacOS", "Logseq");
const appAsar = path.join(appPath, "Contents", "Resources", "app.asar");
const cliEntry = path.join(appAsar, "js", "logseq-cli.js");
const expectedWorkerEntry = path.join(appAsar, "js", "db-worker-node.js");

for (const [label, filePath] of [
  ["packaged executable", executable],
  ["packaged app.asar", appAsar],
]) {
  const stats = fs.statSync(filePath);
  if (!stats.isFile()) {
    throw new Error(`${label} is not a regular file: ${filePath}`);
  }
}
fs.accessSync(executable, fs.constants.X_OK);

const disposableRoot = fs.mkdtempSync(
  path.join(os.tmpdir(), "logseq-packaged-cli-worker-revision-"),
);
const graph = `packaged-worker-revision-${process.pid}-${Date.now()}`;
const commonArgs = [
  cliEntry,
  "--root-dir",
  disposableRoot,
  "--graph",
  graph,
  "--timeout-ms",
  "30000",
  "--output",
  "json",
];
const env = {
  ...process.env,
  ELECTRON_RUN_AS_NODE: "1",
};
let observedServer;

const runCli = (label, commandArgs, { allowFailure = false } = {}) => {
  const result = spawnSync(executable, [...commonArgs, ...commandArgs], {
    cwd: disposableRoot,
    env,
    encoding: "utf8",
    timeout: 45_000,
  });

  if (result.error) {
    throw new Error(`${label} could not run: ${result.error.message}`);
  }
  if (!allowFailure && result.status !== 0) {
    throw new Error(formatRunFailure(label, result));
  }
  return result;
};

try {
  const startResult = runCli("packaged CLI server start", ["server", "start"]);
  const startPayload = parseJsonOutput(
    startResult.stdout,
    "packaged CLI server start",
  );
  assert.equal(
    startPayload.status,
    "ok",
    `packaged CLI server start must succeed: ${JSON.stringify(startPayload)}`,
  );

  const listResult = runCli("packaged CLI server list", ["server", "list"]);
  const listPayload = parseJsonOutput(
    listResult.stdout,
    "packaged CLI server list",
  );
  assert.equal(
    listPayload.status,
    "ok",
    `packaged CLI server list must succeed: ${JSON.stringify(listPayload)}`,
  );

  const servers = listPayload.data?.servers ?? [];
  const matchingServers = servers.filter((server) => server.graph === graph);
  assert.equal(
    matchingServers.length,
    1,
    `expected exactly one disposable packaged worker for ${graph}: ${JSON.stringify(
      servers,
    )}`,
  );

  observedServer = matchingServers[0];
  assert.equal(
    fs.realpathSync(observedServer["root-dir"]),
    fs.realpathSync(disposableRoot),
    "packaged CLI worker must use only the disposable root",
  );
  assert.equal(
    observedServer["owner-source"],
    "cli",
    "packaged worker must be owned by the packaged CLI",
  );
  assert.equal(
    observedServer.status,
    "ready",
    `packaged worker must report ready before revision verification: ${JSON.stringify(
      observedServer,
    )}`,
  );

  if (observedServer.revision !== expectedRevision) {
    throw new Error(
      `packaged CLI consumed db-worker-node revision ${JSON.stringify(
        observedServer.revision,
      )}, expected ${JSON.stringify(expectedRevision)} from the packaged build; ` +
        `CLI entry=${cliEntry} expected worker entry=${expectedWorkerEntry}`,
    );
  }

  console.log(
    `[packaged-cli-worker-revision] PASS revision=${observedServer.revision} ` +
      `pid=${observedServer.pid} root=${disposableRoot}`,
  );
} finally {
  const stopResult = runCli(
    "packaged CLI server stop",
    ["server", "stop"],
    { allowFailure: true },
  );

  if (stopResult.status !== 0 && observedServer?.pid) {
    try {
      process.kill(observedServer.pid, "SIGTERM");
    } catch {
      // The worker may already have exited.
    }
  }

  fs.rmSync(disposableRoot, { recursive: true, force: true });
}
