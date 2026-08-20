#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { DatabaseSync } from "node:sqlite";
import { fileURLToPath } from "node:url";

const supported = new Set([
  "darwin/arm64",
  "darwin/x64",
  "win32/x64",
  "win32/arm64",
  "linux/arm64",
  "linux/x64",
]);
const fullSha = /^[0-9a-f]{40}$/;

function parseArgs(argv) {
  const values = {};
  for (let index = 0; index < argv.length; index += 2) {
    const key = argv[index];
    const value = argv[index + 1];
    assert.ok(key?.startsWith("--") && value !== undefined, `invalid argument near ${key ?? "end"}`);
    assert.equal(values[key.slice(2)], undefined, `duplicate ${key}`);
    values[key.slice(2)] = value;
  }
  assert.deepEqual(Object.keys(values).sort(), ["arch", "output", "platform", "source-full-sha"]);
  assert.ok(supported.has(`${values.platform}/${values.arch}`), "unsupported platform/arch");
  assert.match(values["source-full-sha"], fullSha);
  return {
    platform: values.platform,
    arch: values.arch,
    sourceFullSha: values["source-full-sha"],
    output: path.resolve(values.output),
  };
}

function fsyncFile(filePath) {
  // Windows rejects fsync on a read-only handle even when the file itself is
  // writable. Use one cross-platform write-capable handle; do not weaken the
  // fsync oracle or special-case the target platform.
  const descriptor = fs.openSync(filePath, "r+");
  try {
    fs.fsyncSync(descriptor);
  } finally {
    fs.closeSync(descriptor);
  }
}

function createDatabase(filePath, value, journalMode) {
  const database = new DatabaseSync(filePath);
  database.exec(`PRAGMA journal_mode=${journalMode}`);
  database.exec("PRAGMA synchronous=FULL");
  database.exec("CREATE TABLE qualification (id INTEGER PRIMARY KEY, value TEXT NOT NULL)");
  database.prepare("INSERT INTO qualification (id, value) VALUES (1, ?)").run(value);
  return database;
}

export function qualifyPlatformSwap({ platform, arch, sourceFullSha, output }) {
  assert.equal(process.platform, platform, "qualification must run on the target operating system");
  assert.equal(process.arch, arch, "qualification must run on the target architecture");
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "selfhost6-platform-swap-"));
  const canonical = path.join(directory, "db.sqlite");
  const previous = path.join(directory, "db.previous.sqlite");
  const target = path.join(directory, "db.target.sqlite");
  let sourceDatabase;
  let targetDatabase;
  try {
    sourceDatabase = createDatabase(canonical, "source", "WAL");
    const walPath = `${canonical}-wal`;
    const nonEmptyWalBytes = fs.statSync(walPath).size;
    assert.ok(nonEmptyWalBytes > 0, "qualification WAL must be non-empty");
    const checkpoint = sourceDatabase.prepare("PRAGMA wal_checkpoint(FULL)").get();
    assert.equal(checkpoint.busy, 0, "WAL checkpoint must not be busy");
    fsyncFile(walPath);
    sourceDatabase.close();
    sourceDatabase = undefined;
    fsyncFile(canonical);

    targetDatabase = createDatabase(target, "target", "DELETE");
    targetDatabase.close();
    targetDatabase = undefined;
    fsyncFile(target);

    fs.renameSync(canonical, previous);
    fs.renameSync(target, canonical);

    const reopened = new DatabaseSync(canonical, { readOnly: true });
    const activeValue = reopened.prepare("SELECT value FROM qualification WHERE id = 1").get().value;
    reopened.close();
    const old = new DatabaseSync(previous, { readOnly: true });
    const previousValue = old.prepare("SELECT value FROM qualification WHERE id = 1").get().value;
    old.close();
    assert.equal(activeValue, "target");
    assert.equal(previousValue, "source");

    const receipt = {
      schemaVersion: 1,
      kind: "selfhost6.phase0.platform-sqlite-swap.v1",
      result: "pass",
      sourceFullSha,
      platform,
      arch,
      runnerPlatform: process.platform,
      runnerArch: process.arch,
      nodeVersion: process.version,
      nonEmptyWalBytes,
      authorityCommitRenameCount: 1,
      oracles: {
        checkpoint: true,
        fsync: true,
        close: true,
        singleRename: true,
        reopen: true,
      },
    };
    fs.mkdirSync(path.dirname(output), { recursive: true });
    fs.writeFileSync(output, `${JSON.stringify(receipt, null, 2)}\n`, { flag: "wx", mode: 0o600 });
    return receipt;
  } finally {
    sourceDatabase?.close();
    targetDatabase?.close();
    fs.rmSync(directory, { recursive: true, force: true });
  }
}

if (path.resolve(process.argv[1] ?? "") === fileURLToPath(import.meta.url)) {
  const receipt = qualifyPlatformSwap(parseArgs(process.argv.slice(2)));
  process.stdout.write(`${JSON.stringify(receipt)}\n`);
}
