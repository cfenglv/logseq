import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import { buildCommandSpec, parseArgs } from "./phase7-qualified-cli.mjs";
import { readQualificationSession } from "./phase7-qualified-session.mjs";

function fixture() {
  const qualificationRoot = fs.mkdtempSync(path.join(os.tmpdir(), "selfhost6-phase7-cli-"));
  const testHome = path.join(qualificationRoot, "home");
  const userData = path.join(qualificationRoot, "user-data");
  const config = path.join(qualificationRoot, "cli.edn");
  const appExecutable = path.join(qualificationRoot, "Logseq.app/Contents/MacOS/Logseq");
  const stateFile = path.join(qualificationRoot, "remote-graph.json");
  const sessionFile = path.join(qualificationRoot, "session.json");
  fs.mkdirSync(testHome);
  fs.mkdirSync(userData);
  fs.writeFileSync(config, "{}\n");
  fs.mkdirSync(path.dirname(appExecutable), { recursive: true });
  fs.writeFileSync(appExecutable, "fixture");
  fs.writeFileSync(sessionFile, JSON.stringify({
    kind: "selfhost6.phase7.qualification-session.v1",
    qualificationRoot,
    testHome,
    userData,
    config,
    appExecutable,
    sourceFullSha: "a".repeat(40),
    debugPort: 9347,
    graph: "selfhost6-phase7-20260815172129-7e756b28",
    stateFile,
  }));
  return { qualificationRoot, testHome, userData, config, appExecutable, stateFile, sessionFile };
}

test("qualified sync control always supplies the frozen CLI owners", () => {
  const paths = fixture();
  const graph = "selfhost6-phase7-20260815172129-7e756b28";
  const spec = buildCommandSpec({ ...paths, graph, command: "stop" });

  assert.deepEqual(spec.args.slice(-10), [
    "--root-dir", path.join(fs.realpathSync(paths.testHome), "logseq"),
    "--config", fs.realpathSync(paths.config),
    "--graph", graph,
    "--output", "json",
    "sync", "stop",
  ]);
  assert.deepEqual(spec.cleanup.args.slice(-10), [
    "--root-dir", path.join(fs.realpathSync(paths.testHome), "logseq"),
    "--config", fs.realpathSync(paths.config),
    "--graph", graph,
    "--output", "json",
    "server", "stop",
  ]);
});

test("the command is the only required runtime argument", () => {
  const paths = fixture();
  assert.throws(
    () => parseArgs([]),
    /missing --command/,
  );
  assert.deepEqual(parseArgs(["--command", "status", "--session-file", paths.sessionFile]), {
    command: "status",
    sessionFile: path.resolve(paths.sessionFile),
  });
});

test("an incomplete frozen session fails before CLI dispatch", () => {
  const paths = fixture();
  const session = JSON.parse(fs.readFileSync(paths.sessionFile, "utf8"));
  delete session.graph;
  fs.writeFileSync(paths.sessionFile, JSON.stringify(session));
  assert.throws(() => readQualificationSession(paths.sessionFile),
    /qualification session missing graph/);
});

test("a path outside the qualification root fails closed", () => {
  const paths = fixture();
  assert.throws(
    () => buildCommandSpec({
      ...paths,
      config: path.join(os.tmpdir(), "outside-cli.edn"),
      graph: "selfhost6-phase7-20260815172129-7e756b28",
      command: "status",
    }),
    /ENOENT|config must be inside qualification root/,
  );
});

test("a nonqualification graph cannot be queried", () => {
  const paths = fixture();
  assert.throws(
    () => buildCommandSpec({ ...paths, graph: "test5", command: "status" }),
    /isolated Phase 7 qualification graph/,
  );
});

test("qualification CLI rejects arbitrary sync subcommands", () => {
  const paths = fixture();
  assert.throws(() => parseArgs(["--command", "upload", "--session-file", paths.sessionFile]),
    /status, start, or stop/);
  assert.throws(() => parseArgs(["--", "ignored", "--command", "status"]),
    /invalid argument near/);
  assert.throws(() => parseArgs(["--command", "status", "--graph", "test5"]),
    /only --command/);
});
