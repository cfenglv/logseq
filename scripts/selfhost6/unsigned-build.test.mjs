import assert from "node:assert/strict";
import test from "node:test";
import { unsignedElectronBuilderArgs } from "../electron-builder-unsigned.mjs";

test("unsigned x64-only macOS builds do not rewrite the native runtime identity", () => {
  const extra = ["--mac", "zip", "--x64"];
  const args = unsignedElectronBuilderArgs(extra);
  assert.equal(args.some((value) => value.startsWith("-c.mac.identity=")), false);
  assert.ok(args.includes("-c.mac.hardenedRuntime=false"));
  assert.equal(args[args.indexOf("--publish") + 1], "never");
  assert.deepEqual(args.slice(-extra.length), extra);
});

test("ARM64, default and mixed macOS builds retain the existing ad-hoc signing", () => {
  for (const extra of [
    [],
    ["--mac", "zip", "--arm64"],
    ["--mac", "zip", "--universal"],
    ["--mac", "zip", "--x64", "--arm64"],
    ["--mac", "zip:x64", "zip:arm64", "--x64"],
    ["--mac", "zip:universal", "--x64"],
    ["--mac", "zip", "--x64", "--arm64=true"],
  ]) {
    const args = unsignedElectronBuilderArgs(extra);
    assert.equal(args.filter((value) => value === "-c.mac.identity=-").length, 1);
    assert.ok(args.includes("-c.mac.hardenedRuntime=false"));
    assert.equal(args[args.indexOf("--publish") + 1], "never");
  }
});
