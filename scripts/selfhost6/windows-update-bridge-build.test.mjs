import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { windowsBridgeConfiguration } from "../../resources/updater/build-windows-update-bridge.mjs";

test("Windows bridge binds fixed payload size/hash without installing a second client", () => {
  const config = windowsBridgeConfiguration({ root: "/synthetic", templates: "/templates", plugins: "/plugins",
    payloadName: "inner.exe", payloadSize: 100, payloadSha512: "a".repeat(128),
    strings: { en: { description: 'Update "error" $value' }, "zh-CN": { description: "更新失败" } } });
  assert.match(config, /BRIDGE_PAYLOAD_SIZE 100/);
  assert.ok(config.includes("a".repeat(128)));
  assert.ok(config.includes('$\\"error$\\" $$value'));
  const source = fs.readFileSync(new URL("../../resources/windows-project-updater/bridge.nsi", import.meta.url), "utf8");
  assert.match(source, /CRCCheck force/);
  assert.match(source, /HashFile[\s\S]*SHA2-512[\s\S]*BRIDGE_PAYLOAD_SHA512[\s\S]*ExecWait/);
  assert.match(source, /FileSeek[\s\S]*BRIDGE_PAYLOAD_SIZE/);
  assert.ok(source.includes('"$PLUGINSDIR\\${BRIDGE_PAYLOAD_NAME}" /S $BridgeParameters'));
  assert.match(source, /SetErrorLevel \$BridgeExit/);
  assert.doesNotMatch(source, /CreateMutex|WriteReg|WriteUninstaller|UAC_RunElevated|CRCCheck off|\/NCRC/);
});

test("a Windows build invoked on another OS rejects before writes", { skip: process.platform === "win32" }, context => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "windows-bridge-reject-"));
  context.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const child = spawnSync(process.execPath, [fileURLToPath(new URL("../../resources/updater/build-windows-update-bridge.mjs", import.meta.url))],
    { cwd: root, encoding: "utf8" });
  assert.equal(child.status, 1);
  assert.equal(child.stdout, "");
  assert.equal(child.stderr, "windows-update-bridge-build-rejected\n");
  assert.deepEqual(fs.readdirSync(root), []);
});
