import assert from "node:assert/strict";
import test from "node:test";
import { verifyWindowsReaderBinary, windowsReaderDistribution } from "./windows-archive-reader.mjs";

test("official Windows reader distributions are fixed and target-specific", () => {
  for (const arch of ["x64", "arm64"]) {
    const distribution = windowsReaderDistribution(arch);
    assert.equal(distribution.name, `7z2604-${arch}.exe`);
    assert.match(distribution.sha256, /^[0-9a-f]{64}$/);
  }
  assert.notEqual(windowsReaderDistribution("x64").sha256, windowsReaderDistribution("arm64").sha256);
  assert.throws(() => windowsReaderDistribution("ia32"), /unsupported/);
});

test("both shipped PE members must match the actual target, not the build host", () => {
  const payload = Buffer.alloc(128);
  payload.write("MZ"); payload.writeUInt32LE(64, 0x3c); payload.write("PE\0\0", 64);
  for (const [arch, machine] of [["x64", 0x8664], ["arm64", 0xaa64]]) {
    payload.writeUInt16LE(machine, 68);
    verifyWindowsReaderBinary(payload, arch);
    assert.throws(() => verifyWindowsReaderBinary(payload, arch === "x64" ? "arm64" : "x64"), /architecture differs/);
  }
  payload.writeUInt16LE(0x14c, 68);
  assert.throws(() => verifyWindowsReaderBinary(payload, "x64"), /architecture differs/);
});
