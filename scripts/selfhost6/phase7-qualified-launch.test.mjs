import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import {
  buildLaunchSpec,
  parseArgs,
} from "./phase7-qualified-launch.mjs";

const sourceFullSha = "a".repeat(40);

function fixture() {
  const qualificationRoot = fs.mkdtempSync(path.join(os.tmpdir(), "selfhost6-phase7-launch-"));
  const testHome = path.join(qualificationRoot, "home");
  const userData = path.join(qualificationRoot, "user-data");
  const app = path.join(qualificationRoot, "Logseq.app");
  const executable = path.join(app, "Contents/MacOS/Logseq");
  const manifest = path.join(app, "Contents/Resources/updater/TARGET_BUILD_MANIFEST.json");
  const config = path.join(qualificationRoot, "cli.edn");
  const stateFile = path.join(qualificationRoot, "remote-graph.json");
  const sessionFile = path.join(qualificationRoot, "session.json");
  fs.mkdirSync(path.dirname(executable), { recursive: true });
  fs.mkdirSync(path.dirname(manifest), { recursive: true });
  fs.writeFileSync(executable, "fixture");
  fs.chmodSync(executable, 0o755);
  fs.writeFileSync(manifest, JSON.stringify({
    "target-source-full-sha": sourceFullSha,
    "target-version": "2.0.1-selfhost.6",
    "release-line-id": "selfhost-official-architecture-v1",
    platform: "darwin",
    arch: "arm64",
  }));
  fs.writeFileSync(config, "{}\n");
  fs.writeFileSync(sessionFile, JSON.stringify({
    kind: "selfhost6.phase7.qualification-session.v1",
    qualificationRoot,
    testHome,
    userData,
    config,
    appExecutable: executable,
    sourceFullSha,
    debugPort: 9347,
    graph: "selfhost6-phase7-20260815172129-7e756b28",
    stateFile,
  }));
  return { qualificationRoot, testHome, userData, executable, config, stateFile, sessionFile };
}

test("the launch spec always binds both Electron home owners", () => {
  const paths = fixture();
  const spec = buildLaunchSpec({
    ...paths,
    sourceFullSha,
    debugPort: 9347,
    inheritedEnv: { NODE_USE_ENV_PROXY: "1", PATH: "/usr/bin" },
    actualHome: "/Users/real-user",
  });

  assert.equal(spec.env.LOGSEQ_TEST_HOME_DIR, paths.testHome);
  assert.equal(spec.env.LOGSEQ_TEST_USER_DATA_DIR, paths.userData);
  assert.equal(spec.env.HOME, paths.testHome);
  assert.equal(spec.env.NODE_USE_ENV_PROXY, undefined);
  assert.deepEqual(spec.args, [
    `--user-data-dir=${paths.userData}`,
    "--remote-debugging-port=9347",
  ]);
  assert.equal(spec.artifact.sourceFullSha, sourceFullSha);
});

test("mode plus the frozen session are the only launch arguments", () => {
  const paths = fixture();
  assert.throws(
    () => parseArgs([]),
    /missing --mode/,
  );
  assert.deepEqual(parseArgs(["--mode", "describe", "--session-file", paths.sessionFile]), {
    mode: "describe",
    sessionFile: path.resolve(paths.sessionFile),
  });
  assert.throws(
    () => parseArgs(["--mode", "launch", "--debug-port", "9999"]),
    /only --mode/,
  );
});

test("real-home qualification paths fail closed", () => {
  const paths = fixture();
  assert.throws(
    () => buildLaunchSpec({
      ...paths,
      testHome: "/Users/real-user",
      sourceFullSha,
      debugPort: 9347,
      inheritedEnv: {},
      actualHome: "/Users/real-user",
    }),
    /real Home/,
  );
});

test("an artifact from another source cannot be launched", () => {
  const paths = fixture();
  assert.throws(
    () => buildLaunchSpec({
      ...paths,
      sourceFullSha: "b".repeat(40),
      debugPort: 9347,
      inheritedEnv: {},
      actualHome: "/Users/real-user",
    }),
    /artifact source does not match/,
  );
});
