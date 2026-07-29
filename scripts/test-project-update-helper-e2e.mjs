#!/usr/bin/env node

import {
  createHash,
  generateKeyPairSync,
  sign,
} from "node:crypto";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { projectUpdatePayload } from "./project-update-signing.mjs";

if (process.platform !== "darwin") {
  throw new Error("the project-signed macOS updater test requires macOS");
}

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const temporaryRoot = fs.mkdtempSync(
  path.join(os.tmpdir(), "logseq-project-updater-test-"),
);
const helper = path.join(temporaryRoot, "project-updater");
const hostArch = process.arch === "arm64" ? "arm64" : "x64";
const executableSource = path.join(temporaryRoot, "host-executable");
let passed = 0;

const run = (command, args, options = {}) => {
  const result = spawnSync(command, args, {
    cwd: options.cwd,
    encoding: "utf8",
    env: options.env ?? process.env,
    stdio: ["ignore", "pipe", "pipe"],
  });
  if (result.error || result.status !== 0) {
    throw new Error(
      `${command} ${args.join(" ")} failed (${result.status}): ${
        result.stderr || result.stdout || result.error?.message
      }`,
    );
  }
  return result;
};

const plist = (version) => `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleExecutable</key><string>Logseq</string>
  <key>CFBundleIdentifier</key><string>com.logseq.logseq</string>
  <key>CFBundleName</key><string>Logseq</string>
  <key>CFBundleShortVersionString</key><string>${version}</string>
  <key>CFBundleVersion</key><string>${version}</string>
</dict>
</plist>
`;

const createApp = (parent, version, marker) => {
  const app = path.join(parent, "Logseq.app");
  const contents = path.join(app, "Contents");
  const macOS = path.join(contents, "MacOS");
  const resources = path.join(contents, "Resources");
  fs.mkdirSync(macOS, { recursive: true });
  fs.mkdirSync(resources, { recursive: true });
  fs.writeFileSync(path.join(contents, "Info.plist"), plist(version));
  fs.copyFileSync(executableSource, path.join(macOS, "Logseq"));
  fs.chmodSync(path.join(macOS, "Logseq"), 0o755);
  fs.writeFileSync(path.join(resources, "release-marker.txt"), `${marker}\n`);
  run("/usr/bin/codesign", ["--force", "--deep", "--sign", "-", app]);
  return app;
};

const zipApp = (app, output) => {
  run("/usr/bin/ditto", ["-c", "-k", "--keepParent", app, output]);
};

const archiveFacts = (archive) => {
  const payload = fs.readFileSync(archive);
  return {
    sha512: createHash("sha512").update(payload).digest("hex"),
    size: String(payload.length),
  };
};

const signedArguments = ({
  archive,
  arch = hostArch,
  candidateVersion,
  privateKey,
  relaunch = false,
  target,
  verifyOnly = true,
}) => {
  const { sha512, size } = archiveFacts(archive);
  const signature = sign(
    null,
    Buffer.from(
      projectUpdatePayload({
        arch,
        sha512,
        size,
        version: candidateVersion,
      }),
    ),
    privateKey,
  ).toString("base64");
  const args = [
    "--archive", archive,
    "--target", target,
    "--arch", arch,
    "--version", candidateVersion,
    "--sha512", sha512,
    "--size", size,
    "--parent-pid", "0",
    "--relaunch", String(relaunch),
    "--signature", signature,
  ];
  if (verifyOnly) args.push("--verify-only");
  return args;
};

const invoke = (args) =>
  spawnSync(
    process.execPath,
    [
      path.join(repoRoot, "scripts", "run-project-signed-macos-update.mjs"),
      "--helper",
      helper,
      "--",
      ...args,
    ],
    { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] },
  );

const expectPass = (name, args, pattern) => {
  const result = invoke(args);
  if (result.error || result.status !== 0 || !pattern.test(result.stdout)) {
    throw new Error(
      `${name} expected success: ${result.stderr || result.stdout || result.error?.message}`,
    );
  }
  passed += 1;
  console.log(`[project-updater-test] PASS ${name}`);
};

const expectFail = (name, args, pattern) => {
  const result = invoke(args);
  const output = `${result.stdout}\n${result.stderr}`;
  if (result.status === 0 || !pattern.test(output)) {
    throw new Error(
      `${name} expected ${pattern}, status=${result.status}: ${output}`,
    );
  }
  passed += 1;
  console.log(`[project-updater-test] PASS ${name}`);
};

const crcTable = Array.from({ length: 256 }, (_, index) => {
  let value = index;
  for (let bit = 0; bit < 8; bit += 1) {
    value = (value & 1) !== 0
      ? (value >>> 1) ^ 0xedb88320
      : value >>> 1;
  }
  return value >>> 0;
});

const crc32 = (payload) => {
  let crc = 0xffffffff;
  for (const byte of payload) {
    crc = (crc >>> 8) ^ crcTable[(crc ^ byte) & 0xff];
  }
  return (crc ^ 0xffffffff) >>> 0;
};

const storedZip = (output, entryName, content) => {
  const name = Buffer.from(entryName);
  const payload = Buffer.from(content);
  const crc = crc32(payload);
  const local = Buffer.alloc(30);
  local.writeUInt32LE(0x04034b50, 0);
  local.writeUInt16LE(20, 4);
  local.writeUInt32LE(crc, 14);
  local.writeUInt32LE(payload.length, 18);
  local.writeUInt32LE(payload.length, 22);
  local.writeUInt16LE(name.length, 26);
  const central = Buffer.alloc(46);
  central.writeUInt32LE(0x02014b50, 0);
  central.writeUInt16LE(0x0314, 4);
  central.writeUInt16LE(20, 6);
  central.writeUInt32LE(crc, 16);
  central.writeUInt32LE(payload.length, 20);
  central.writeUInt32LE(payload.length, 24);
  central.writeUInt16LE(name.length, 28);
  central.writeUInt32LE((0o100644 * 0x10000) >>> 0, 38);
  const centralOffset = local.length + name.length + payload.length;
  const centralSize = central.length + name.length;
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(1, 8);
  end.writeUInt16LE(1, 10);
  end.writeUInt32LE(centralSize, 12);
  end.writeUInt32LE(centralOffset, 16);
  fs.writeFileSync(
    output,
    Buffer.concat([local, name, payload, central, name, end]),
  );
};

try {
  const { privateKey, publicKey } = generateKeyPairSync("ed25519");
  const publicDer = publicKey.export({ format: "der", type: "spki" });
  const publicKeyBase64 = publicDer.subarray(-32).toString("base64");
  run(
    process.execPath,
    [
      path.join(repoRoot, "scripts", "build-project-update-helper.mjs"),
      "--test-only",
      "--public-key-base64",
      publicKeyBase64,
      "--arch",
      hostArch,
      "--output",
      helper,
    ],
  );
  run("/usr/bin/lipo", [
    "-thin",
    hostArch === "arm64" ? "arm64e" : "x86_64",
    "/usr/bin/true",
    "-output",
    executableSource,
  ]);

  const targetParent = path.join(temporaryRoot, "target");
  const candidateParent = path.join(temporaryRoot, "candidate");
  fs.mkdirSync(targetParent);
  fs.mkdirSync(candidateParent);
  const target = createApp(
    targetParent,
    "2.0.1-selfhost.4",
    "installed-selfhost.4",
  );
  const candidate = createApp(
    candidateParent,
    "2.0.1-selfhost.5",
    "candidate-selfhost.5",
  );
  const archive = path.join(temporaryRoot, "candidate.zip");
  zipApp(candidate, archive);
  const validArgs = signedArguments({
    archive,
    candidateVersion: "2.0.1-selfhost.5",
    privateKey,
    target,
  });

  expectPass(
    "valid signed archive verifies at native replacement boundary",
    validArgs,
    /VERIFIED 2\.0\.1-selfhost\.4 -> 2\.0\.1-selfhost\.5/,
  );

  const invalidSignature = [...validArgs];
  invalidSignature[invalidSignature.indexOf("--signature") + 1] =
    Buffer.alloc(64).toString("base64");
  expectFail(
    "invalid Ed25519 signature fails closed",
    invalidSignature,
    /signature is invalid/,
  );

  const hashMismatch = [...validArgs];
  hashMismatch[hashMismatch.indexOf("--sha512") + 1] = "0".repeat(128);
  expectFail(
    "SHA-512 mismatch fails closed",
    hashMismatch,
    /SHA-512 does not match/,
  );

  const wrongArch = signedArguments({
    archive,
    arch: hostArch === "arm64" ? "x64" : "arm64",
    candidateVersion: "2.0.1-selfhost.5",
    privateKey,
    target,
  });
  expectFail(
    "signed architecture mismatch is rejected",
    wrongArch,
    /does not contain signed architecture/,
  );

  const downgradeParent = path.join(temporaryRoot, "downgrade");
  fs.mkdirSync(downgradeParent);
  const downgradeApp = createApp(
    downgradeParent,
    "2.0.1-selfhost.3",
    "candidate-selfhost.3",
  );
  const downgradeArchive = path.join(temporaryRoot, "downgrade.zip");
  zipApp(downgradeApp, downgradeArchive);
  expectFail(
    "downgrade is rejected before replacement",
    signedArguments({
      archive: downgradeArchive,
      candidateVersion: "2.0.1-selfhost.3",
      privateKey,
      target,
    }),
    /refuses downgrade or same-version/,
  );

  const traversalArchive = path.join(temporaryRoot, "traversal.zip");
  storedZip(traversalArchive, "Logseq.app/../escape", "malicious");
  expectFail(
    "ZIP path traversal is rejected",
    signedArguments({
      archive: traversalArchive,
      candidateVersion: "2.0.1-selfhost.5",
      privateKey,
      target,
    }),
    /ZIP entry escapes/,
  );

  const archiveSymlink = path.join(temporaryRoot, "archive-symlink.zip");
  fs.symlinkSync(archive, archiveSymlink);
  expectFail(
    "archive symlink is rejected",
    signedArguments({
      archive: archiveSymlink,
      candidateVersion: "2.0.1-selfhost.5",
      privateKey,
      target,
    }),
    /without following symlinks/,
  );

  expectPass(
    "valid signed update atomically replaces the App",
    signedArguments({
      archive,
      candidateVersion: "2.0.1-selfhost.5",
      privateKey,
      target,
      verifyOnly: false,
    }),
    /INSTALLED 2\.0\.1-selfhost\.4 -> 2\.0\.1-selfhost\.5/,
  );
  const installedInfo = fs.readFileSync(
    path.join(target, "Contents", "Info.plist"),
    "utf8",
  );
  const installedMarker = fs.readFileSync(
    path.join(target, "Contents", "Resources", "release-marker.txt"),
    "utf8",
  );
  if (
    !installedInfo.includes("2.0.1-selfhost.5") ||
    installedMarker !== "candidate-selfhost.5\n"
  ) {
    throw new Error("replacement did not install the signed candidate");
  }
  passed += 1;
  console.log("[project-updater-test] PASS installed App identity and payload");

  console.log(
    `[project-updater-test] SUMMARY passed=${passed} failed=0`,
  );
} finally {
  fs.rmSync(temporaryRoot, { recursive: true, force: true });
}
