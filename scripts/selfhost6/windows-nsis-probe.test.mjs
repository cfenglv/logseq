import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { requireWindowsProbeRunner, windowsProbeSources } from "./windows-nsis-probe.mjs";

test("the NSIS probe reuses standard path/scope macros and preserves child arguments and exit", () => {
  const source = windowsProbeSources("/synthetic root", "/frozen templates", "/frozen plugins");
  assert.match(source.inner, /multiUser\.nsh/);
  assert.match(source.inner, /!include x64\.nsh[\s\S]*!define APP_64[\s\S]*setInstallModePerAllUsers/);
  assert.match(source.inner, /!insertmacro setInstallModePerAllUsers/);
  assert.match(source.inner, /!insertmacro setInstallModePerUser/);
  assert.ok(source.inner.includes('WriteINIStr "$INSTDIR\\probe.ini"'));
  assert.match(source.inner, /CreateMutexW/);
  assert.match(source.outer, /!define BRIDGE_CONFIG/);
  assert.match(source.outer, /!include .*bridge-source[\\/]resources[\\/]windows-project-updater[\\/]bridge\.nsi/);
  // Runtime/hash/CRC behavior uses the exact product template, not another implementation.
  assert.doesNotMatch(source.outer, /UAC_RunElevated|CreateMutex|WriteReg|DeleteReg|RMDir/);
});

test("the native probe refuses other systems, architectures and non-Actions environments", () => {
  const env = { GITHUB_ACTIONS: "true", RUNNER_OS: "Windows", WINDOWS_PROBE_ARCH: "x64", RUNNER_TEMP: "synthetic" };
  requireWindowsProbeRunner(env, "win32", "x64");
  requireWindowsProbeRunner({ ...env, WINDOWS_PROBE_ARCH: "arm64" }, "win32", "arm64");
  assert.throws(() => requireWindowsProbeRunner(env, "darwin", "x64"), /requires Windows/);
  assert.throws(() => requireWindowsProbeRunner({ ...env, GITHUB_ACTIONS: "false" }, "win32", "x64"), /isolated Actions/);
  assert.throws(() => requireWindowsProbeRunner(env, "win32", "arm64"), /architecture differs/);
});

test("a rejected probe CLI makes no scratch files or tool requests", context => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "windows-probe-rejection-"));
  context.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const child = spawnSync(process.execPath, [fileURLToPath(new URL("./windows-nsis-probe.mjs", import.meta.url))], {
    encoding: "utf8", env: { ...process.env, GITHUB_ACTIONS: "false", RUNNER_TEMP: root },
  });
  assert.equal(child.status, 1);
  assert.equal(child.stdout, "");
  assert.equal(child.stderr, "windows-nsis-probe-rejected\n");
  assert.deepEqual(fs.readdirSync(root), []);
});
