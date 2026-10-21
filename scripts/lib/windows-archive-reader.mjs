import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import fs from "node:fs";
import { createRequire } from "node:module";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

export function windowsReaderDistribution(arch) {
  assert.ok(["x64", "arm64"].includes(arch), "unsupported Windows reader architecture");
  return arch === "arm64"
    ? { name: "7z2604-arm64.exe", sha256: "d4117b95495b0e925334ae7b5bddf2853513274603de15dee6acf03999fbc09c" }
    : { name: "7z2604-x64.exe", sha256: "d54bf805f9f3704d1e8db2fa3498ae7ef2df0312b40b558e7c71c734430a665d" };
}

export function verifyWindowsReaderBinary(payload, arch) {
  assert.equal(payload.subarray(0, 2).toString(), "MZ");
  const offset = payload.readUInt32LE(0x3c);
  assert.equal(payload.subarray(offset, offset + 4).toString(), "PE\0\0");
  assert.equal(payload.readUInt16LE(offset + 4), arch === "arm64" ? 0xaa64 : 0x8664,
    "Windows reader architecture differs from target");
}

// Build/qualification tool only. This script is outside the packaged application.
export async function buildArchiveReader() {
  if (process.platform === "win32") return path.join(process.env.ProgramFiles, "7-Zip", "7z.exe");
  const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
  const staticRequire = createRequire(path.join(repoRoot, "static/package.json"));
  const builderRequire = createRequire(staticRequire.resolve("electron-builder"));
  return builderRequire("app-builder-lib/out/toolsets/7zip.js").getPath7za();
}

export async function prepareWindowsArchiveReader({ arch, outputDirectory }) {
  const distribution = windowsReaderDistribution(arch);
  const response = await fetch("https://github.com/ip7z/7zip/releases/download/26.04/" + distribution.name,
    { signal: AbortSignal.timeout(120_000) });
  assert.ok(response.ok, "official Windows reader download failed");
  const payload = Buffer.from(await response.arrayBuffer());
  assert.equal(createHash("sha256").update(payload).digest("hex"), distribution.sha256,
    "official Windows reader digest differs");
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "windows-reader-build-"));
  try {
    const installer = path.join(temporary, distribution.name);
    fs.writeFileSync(installer, payload);
    const extracted = path.join(temporary, "reader");
    const reader = await buildArchiveReader();
    const child = spawnSync(reader,
      ["e", "-y", "-bd", "-o" + extracted, installer, "7z.exe", "7z.dll", "License.txt"],
      { stdio: "pipe" });
    assert.equal(child.status, 0, "official Windows reader extraction failed");
    for (const name of ["7z.exe", "7z.dll"])
      verifyWindowsReaderBinary(fs.readFileSync(path.join(extracted, name)), arch);
    assert.ok(fs.statSync(path.join(extracted, "License.txt")).size > 0);
    fs.mkdirSync(outputDirectory, { recursive: true });
    for (const [from, to] of [["7z.exe", "7za.exe"], ["7z.dll", "7z.dll"], ["License.txt", "7zip-LICENSE.txt"]])
      fs.copyFileSync(path.join(extracted, from), path.join(outputDirectory, to));
    fs.writeFileSync(path.join(outputDirectory, "7zip-NOTICE.txt"),
      "7-Zip 26.04 (unmodified 7z.exe and 7z.dll; executable filename is 7za.exe).\n" +
      "License: 7zip-LICENSE.txt. Source: https://github.com/ip7z/7zip/tree/26.04\n");
  } finally {
    fs.rmSync(temporary, { recursive: true, force: true });
  }
}
