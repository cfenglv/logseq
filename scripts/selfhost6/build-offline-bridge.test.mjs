import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { bridgeBuildNames, buildOfflineBridge, readOfflineBridgeBuildInputs } from "./build-offline-bridge.mjs";

test("native build inputs keep bridge and final sources independent", () => {
  const env = { BRIDGE_SOURCE_FULL_SHA: "a".repeat(40), FINAL_SOURCE_FULL_SHA: "b".repeat(40) };
  assert.deepEqual(readOfflineBridgeBuildInputs(env), {
    bridgeSourceFullSha: env.BRIDGE_SOURCE_FULL_SHA, finalSourceFullSha: env.FINAL_SOURCE_FULL_SHA,
  });
  assert.throws(() => readOfflineBridgeBuildInputs({ ...env, FINAL_SOURCE_FULL_SHA: "" }), /full SHA/);
  assert.deepEqual(bridgeBuildNames("darwin", "arm64", "2.0.2-selfhost.1"), {
    bridge: "Logseq-darwin-arm64-2.0.1-selfhost.7.zip", final: "Logseq-darwin-arm64-2.0.2-selfhost.1.zip",
  });
  assert.deepEqual(bridgeBuildNames("linux", "x64", "2.0.2-selfhost.1"), {
    bridge: "Logseq-linux-x86_64-2.0.1-selfhost.7.AppImage", final: "Logseq-linux-x86_64-2.0.2-selfhost.1.AppImage",
  });
  assert.deepEqual(bridgeBuildNames("win32", "x64", "2.0.2-selfhost.1"), {
    bridge: "Logseq-win-x64-2.0.1-selfhost.7-nsis.exe", final: "Logseq-win-x64-2.0.2-selfhost.1-nsis.exe",
  });
  assert.throws(() => bridgeBuildNames("win32", "arm64", "2.0.2-selfhost.1"), /entry is x64/);
});

test("a wrong or dirty bridge source rejects before compilation or output writes", async context => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "offline-bridge-source-"));
  context.after(() => fs.rmSync(root, { recursive: true, force: true }));
  execFileSync("git", ["init", "--quiet", root]);
  execFileSync("git", ["-c", "user.name=Build Fixture", "-c", "user.email=build@example.invalid",
    "commit", "--allow-empty", "--quiet", "-m", "test: source fixture"], { cwd: root });
  const outputDirectory = path.join(root, "output");
  if (!["darwin", "linux", "win32"].includes(process.platform)) {
    await assert.rejects(buildOfflineBridge({ outputDirectory }), /not implemented/);
  } else {
    await assert.rejects(buildOfflineBridge({
      bridgeRoot: root, outputDirectory, bridgeSourceFullSha: "a".repeat(40),
    }), /checkout differs/);
    const bridgeSourceFullSha = execFileSync("git", ["rev-parse", "HEAD"], {
      cwd: root, encoding: "utf8",
    }).trim();
    fs.writeFileSync(path.join(root, "untracked.txt"), "synthetic unsaved source\n");
    await assert.rejects(buildOfflineBridge({ bridgeRoot: root, outputDirectory, bridgeSourceFullSha }),
      /bridge checkout must be clean/);
  }
  assert.equal(fs.existsSync(outputDirectory), false);
});

test("build CLI validates sources without exposing assertion details", () => {
  const script = fileURLToPath(new URL("./build-offline-bridge.mjs", import.meta.url));
  const env = { ...process.env, BRIDGE_SOURCE_FULL_SHA: "a".repeat(40),
    FINAL_SOURCE_FULL_SHA: "b".repeat(40) };
  const invoke = finalSource => spawnSync(process.execPath, [script, "inputs"], {
    encoding: "utf8", env: { ...env, FINAL_SOURCE_FULL_SHA: finalSource },
  });
  const valid = invoke(env.FINAL_SOURCE_FULL_SHA);
  assert.equal(valid.status, 0);
  assert.deepEqual(JSON.parse(valid.stdout), { status: "offline-bridge-build-inputs-verified" });
  const invalid = invoke("");
  assert.equal(invalid.status, 1);
  assert.equal(invalid.stdout, "");
  assert.equal(invalid.stderr, "offline-bridge-build-rejected\n");
});
