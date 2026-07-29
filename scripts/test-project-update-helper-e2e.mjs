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

const quarantineName = "com.apple.quarantine";
const setQuarantine = (filePath, value) => {
  run("/usr/bin/xattr", ["-w", quarantineName, value, filePath]);
};
const readQuarantine = (filePath) =>
  run("/usr/bin/xattr", ["-p", quarantineName, filePath]).stdout.trim();

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

const expectStatus = (name, args, expectedStatus) => {
  const result = invoke(args);
  if (result.error || result.status !== expectedStatus) {
    throw new Error(
      `${name} expected exit=${expectedStatus}, got ${result.status}: ${
        result.stderr || result.stdout || result.error?.message
      }`,
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
  const help = run(process.execPath, [
    path.join(repoRoot, "scripts", "build-project-update-helper.mjs"),
    "--help",
  ]).stdout;
  if (
    !help.includes("TEST-ONLY") ||
    !help.includes("--test-only --public-key-base64") ||
    !help.includes("--arch <arm64|x64> --output <path>")
  ) {
    throw new Error("helper build --help does not document the stable test-only contract");
  }
  passed += 1;
  console.log("[project-updater-test] PASS helper build documents TEST-ONLY key override");

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
  const markerAfterInvalidPreflight = fs.readFileSync(
    path.join(target, "Contents", "Resources", "release-marker.txt"),
    "utf8",
  );
  if (markerAfterInvalidPreflight !== "installed-selfhost.4\n") {
    throw new Error("invalid preflight changed the installed App");
  }
  passed += 1;
  console.log(
    "[project-updater-test] PASS invalid preflight leaves the installed App unchanged",
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

  const nightlyEarlier = "2.0.1-selfhost.5.nightly.20260728";
  const nightlyLater = "2.0.1-selfhost.5.nightly.20260729";
  const nightlyTargetParent = path.join(temporaryRoot, "nightly-target");
  const nightlyCandidateParent = path.join(temporaryRoot, "nightly-candidate");
  fs.mkdirSync(nightlyTargetParent);
  fs.mkdirSync(nightlyCandidateParent);
  const nightlyTarget = createApp(nightlyTargetParent, nightlyEarlier, "installed-earlier-nightly");
  const nightlyCandidate = createApp(
    nightlyCandidateParent,
    nightlyLater,
    "candidate-later-nightly",
  );
  const nightlyArchive = path.join(temporaryRoot, "nightly-candidate.zip");
  zipApp(nightlyCandidate, nightlyArchive);
  expectPass(
    "later nightly in the same revision verifies",
    signedArguments({
      archive: nightlyArchive,
      candidateVersion: nightlyLater,
      privateKey,
      target: nightlyTarget,
    }),
    /VERIFIED .*20260728 -> .*20260729/,
  );

  const stableCandidateParent = path.join(temporaryRoot, "stable-candidate");
  fs.mkdirSync(stableCandidateParent);
  const stableCandidate = createApp(stableCandidateParent, "2.0.1-selfhost.5", "candidate-stable");
  const stableArchive = path.join(temporaryRoot, "stable-candidate.zip");
  zipApp(stableCandidate, stableArchive);
  expectFail(
    "stable cannot replace a nightly in the same revision",
    signedArguments({
      archive: stableArchive,
      candidateVersion: "2.0.1-selfhost.5",
      privateKey,
      target: nightlyTarget,
    }),
    /refuses stable\/nightly cross-channel/,
  );

  const stableTargetParent = path.join(temporaryRoot, "stable-target");
  fs.mkdirSync(stableTargetParent);
  const stableTarget = createApp(stableTargetParent, "2.0.1-selfhost.5", "installed-stable");
  expectFail(
    "stable clients cannot enter the nightly track",
    signedArguments({
      archive: nightlyArchive,
      candidateVersion: nightlyLater,
      privateKey,
      target: stableTarget,
    }),
    /refuses stable\/nightly cross-channel/,
  );

  const nextNightlyParent = path.join(temporaryRoot, "next-nightly");
  fs.mkdirSync(nextNightlyParent);
  const nextNightlyVersion = "2.0.1-selfhost.6.nightly.20260701";
  const nextNightly = createApp(
    nextNightlyParent,
    nextNightlyVersion,
    "candidate-next-revision-nightly",
  );
  const nextNightlyArchive = path.join(temporaryRoot, "next-nightly.zip");
  zipApp(nextNightly, nextNightlyArchive);
  expectFail(
    "higher revision nightly still cannot replace a stable release",
    signedArguments({
      archive: nextNightlyArchive,
      candidateVersion: nextNightlyVersion,
      privateKey,
      target: stableTarget,
    }),
    /refuses stable\/nightly cross-channel/,
  );
  expectPass(
    "nightly clients can advance to a higher revision nightly",
    signedArguments({
      archive: nextNightlyArchive,
      candidateVersion: nextNightlyVersion,
      privateKey,
      target: nightlyTarget,
    }),
    /VERIFIED .*selfhost\.5\.nightly\.20260728 -> .*selfhost\.6\.nightly\.20260701/,
  );

  const nextStableParent = path.join(temporaryRoot, "next-stable");
  fs.mkdirSync(nextStableParent);
  const nextStable = createApp(
    nextStableParent,
    "2.0.1-selfhost.6",
    "candidate-next-stable",
  );
  const nextStableArchive = path.join(temporaryRoot, "next-stable.zip");
  zipApp(nextStable, nextStableArchive);
  expectPass(
    "stable clients advance to the next stable revision",
    signedArguments({
      archive: nextStableArchive,
      candidateVersion: "2.0.1-selfhost.6",
      privateKey,
      target: stableTarget,
    }),
    /VERIFIED 2\.0\.1-selfhost\.5 -> 2\.0\.1-selfhost\.6/,
  );
  expectFail(
    "nightly clients require manual installation to return to stable",
    signedArguments({
      archive: nextStableArchive,
      candidateVersion: "2.0.1-selfhost.6",
      privateKey,
      target: nightlyTarget,
    }),
    /refuses stable\/nightly cross-channel/,
  );
  expectFail(
    "invalid nightly calendar date fails closed",
    signedArguments({
      archive: nightlyArchive,
      candidateVersion: "2.0.1-selfhost.5.nightly.20260229",
      privateKey,
      target,
    }),
    /unsupported selfhost version/,
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

  const crashTargetParent = path.join(temporaryRoot, "crash-target");
  fs.mkdirSync(crashTargetParent);
  const crashTarget = createApp(
    crashTargetParent,
    "2.0.1-selfhost.4",
    "installed-before-atomic-swap",
  );
  const crashArguments = signedArguments({
    archive,
    candidateVersion: "2.0.1-selfhost.5",
    privateKey,
    target: crashTarget,
    verifyOnly: false,
  });
  crashArguments.push("--test-exit-after-swap");
  expectStatus(
    "simulated crash immediately after atomic exchange",
    crashArguments,
    86,
  );
  const crashTargetMarker = fs.readFileSync(
    path.join(
      crashTarget,
      "Contents",
      "Resources",
      "release-marker.txt",
    ),
    "utf8",
  );
  const abandonedStaging = fs
    .readdirSync(crashTargetParent)
    .filter((name) => name.startsWith(".logseq-project-update-"));
  if (
    crashTargetMarker !== "candidate-selfhost.5\n" ||
    abandonedStaging.length !== 1
  ) {
    throw new Error(
      "atomic crash boundary did not leave the new App at the target and old App in staging",
    );
  }
  const oldAppMarker = fs.readFileSync(
    path.join(
      crashTargetParent,
      abandonedStaging[0],
      "extracted",
      "Logseq.app",
      "Contents",
      "Resources",
      "release-marker.txt",
    ),
    "utf8",
  );
  if (oldAppMarker !== "installed-before-atomic-swap\n") {
    throw new Error("atomic exchange did not leave the old App in staging");
  }
  passed += 1;
  console.log(
    "[project-updater-test] PASS crash boundary keeps target present and old App recoverable",
  );

  const fallbackTargetParent = path.join(temporaryRoot, "quarantine-fallback-target");
  fs.mkdirSync(fallbackTargetParent);
  const fallbackTarget = createApp(
    fallbackTargetParent,
    "2.0.1-selfhost.4",
    "installed-quarantine-fallback",
  );
  const fallbackArchive = path.join(temporaryRoot, "candidate-without-quarantine.zip");
  zipApp(candidate, fallbackArchive);
  const installedQuarantine = "0081;fallback-installed-app;Logseq;";
  setQuarantine(fallbackTarget, installedQuarantine);
  expectPass(
    "installed App quarantine is the fallback when download has none",
    signedArguments({
      archive: fallbackArchive,
      candidateVersion: "2.0.1-selfhost.5",
      privateKey,
      target: fallbackTarget,
      verifyOnly: false,
    }),
    /INSTALLED 2\.0\.1-selfhost\.4 -> 2\.0\.1-selfhost\.5/,
  );
  if (readQuarantine(fallbackTarget) !== installedQuarantine) {
    throw new Error("replacement did not inherit the installed App quarantine");
  }
  passed += 1;
  console.log("[project-updater-test] PASS installed App quarantine fallback survives replacement");

  const targetQuarantine = "0081;older-installed-app;Logseq;";
  const downloadQuarantine = "0083;downloaded-update;Logseq;";
  setQuarantine(target, targetQuarantine);
  setQuarantine(archive, downloadQuarantine);
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
    installedMarker !== "candidate-selfhost.5\n" ||
    readQuarantine(target) !== downloadQuarantine
  ) {
    throw new Error("replacement did not install the signed candidate with download quarantine");
  }
  passed += 1;
  console.log("[project-updater-test] PASS installed App identity and payload");

  console.log(
    `[project-updater-test] SUMMARY passed=${passed} failed=0`,
  );
} finally {
  fs.rmSync(temporaryRoot, { recursive: true, force: true });
}
