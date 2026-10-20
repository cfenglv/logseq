import assert from "node:assert/strict";
import { createHash, generateKeyPairSync } from "node:crypto";
import { spawn, spawnSync } from "node:child_process";
import { once } from "node:events";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import {
  algorithm,
  bundleIdentity,
  payloadDomain,
  releaseLineId,
  signUpdateMetadata,
  signingKeyIdentity,
} from "../../resources/updater/project-update-signature.mjs";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");

function run(command, args, options = {}) {
  const result = spawnSync(command, args, { encoding: "utf8", ...options });
  if (options.expectFailure) {
    assert.notEqual(result.status, 0, `${command} unexpectedly passed`);
  } else {
    assert.equal(result.status, 0, result.stderr || result.stdout || result.error?.message);
  }
  return result;
}

function infoPlist(version) {
  return `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleExecutable</key><string>Logseq</string>
<key>CFBundleIdentifier</key><string>${bundleIdentity}</string>
<key>CFBundleName</key><string>Logseq</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleShortVersionString</key><string>${version}</string>
<key>CFBundleVersion</key><string>${version}</string>
</dict></plist>
`;
}

function makeApp(appPath, version, manifest, sign = true) {
  fs.rmSync(appPath, { recursive: true, force: true });
  const contents = path.join(appPath, "Contents");
  const macos = path.join(contents, "MacOS");
  const updater = path.join(contents, "Resources/updater");
  fs.mkdirSync(macos, { recursive: true });
  fs.mkdirSync(updater, { recursive: true });
  fs.copyFileSync("/usr/bin/true", path.join(macos, "Logseq"));
  fs.chmodSync(path.join(macos, "Logseq"), 0o755);
  fs.writeFileSync(path.join(contents, "Info.plist"), infoPlist(version));
  if (manifest) {
    fs.writeFileSync(path.join(updater, "TARGET_BUILD_MANIFEST.json"), manifest);
  }
  run("/usr/bin/codesign", sign
    ? ["--force", "--deep", "--sign", "-", appPath]
    : ["--remove-signature", path.join(macos, "Logseq")]);
}

function installedVersion(appPath) {
  return run("/usr/libexec/PlistBuddy", ["-c", "Print :CFBundleShortVersionString", path.join(appPath, "Contents/Info.plist")]).stdout.trim();
}

test("macOS helper and thin bridge preserve native install contracts", { timeout: 120_000 }, async (t) => {
  if (process.platform !== "darwin") return t.skip("macOS-only physical helper test");
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "selfhost6-helper-test-"));
  let sourceProcess, sourceClosed, bridgeClosed, finalInput;
  const canary = root + ".canary";
  try {
    const { privateKey, publicKey } = generateKeyPairSync("ed25519");
    const publicKeyBase64 = publicKey.export({ format: "der", type: "spki" }).subarray(-32).toString("base64");
    const privateKeyPem = privateKey.export({ format: "pem", type: "pkcs8" });
    const keyId = signingKeyIdentity(publicKeyBase64);
    const policy = { schemaVersion: 1, algorithm, payloadDomain, releaseLineId, bundleIdentity, keyId, publicKeyBase64 };
    const helper = path.join(root, "ProjectUpdater");
    run(process.execPath, [
      path.join(repoRoot, "resources/updater/build-macos-update-helper.mjs"),
      "--test-only",
      "--public-key-base64", publicKeyBase64,
      "--arch", process.arch,
      "--output", helper,
    ]);
    const productionHelper = path.join(root, "ProjectUpdater-production");
    run(process.execPath, [
      path.join(repoRoot, "resources/updater/build-macos-update-helper.mjs"),
      "--arch", process.arch,
      "--output", productionHelper,
    ]);

    const sourceSha = "a".repeat(40);
    const manifestObject = {
      "schema-version": 1,
      "target-source-full-sha": sourceSha,
      "target-version": "2.0.2-selfhost.1",
      "release-line-id": releaseLineId,
      platform: "darwin",
      arch: process.arch,
      "bundle-identity": bundleIdentity,
      "signing-key-identity": keyId,
      "readable-activation-formats": ["selfhost-activation-v1"],
      "readable-client-ops-formats": ["official-client-ops-sqlite-v2+selfhost-upload-v1"],
      "activation-write-format": "selfhost-activation-v1",
      "client-ops-write-format": "official-client-ops-sqlite-v2+selfhost-upload-v1",
    };
    const manifest = `${JSON.stringify(manifestObject, null, 2)}\n`;
    const candidateRoot = path.join(root, "candidate");
    const candidate = path.join(candidateRoot, "Logseq.app");
    fs.mkdirSync(candidateRoot);
    makeApp(candidate, "2.0.2-selfhost.1", manifest, process.arch !== "x64");
    const archive = path.join(root, "Logseq.zip");
    run("/usr/bin/ditto", ["-c", "-k", "--keepParent", candidate, archive]);
    const archiveBytes = fs.readFileSync(archive);
    const archiveSha256 = createHash("sha256").update(archiveBytes).digest("hex");
    const archiveSha512 = createHash("sha512").update(archiveBytes).digest("hex");
    const unsigned = {
      "schema-version": 1,
      algorithm,
      "key-id": keyId,
      "release-line-id": releaseLineId,
      "target-source-full-sha": sourceSha,
      "target-version": "2.0.2-selfhost.1",
      platform: "darwin",
      arch: process.arch,
      "bundle-identity": bundleIdentity,
      "immutable-object-key": `${releaseLineId}/${sourceSha}/${archiveSha256}/darwin/${process.arch}/Logseq.zip`,
      "archive-size": archiveBytes.length,
      "archive-sha256": archiveSha256,
      "archive-sha512": archiveSha512,
      "target-build-manifest-sha256": createHash("sha256").update(manifest).digest("hex"),
      "readable-activation-formats": manifestObject["readable-activation-formats"],
      "readable-client-ops-formats": manifestObject["readable-client-ops-formats"],
      "activation-write-format": manifestObject["activation-write-format"],
      "client-ops-write-format": manifestObject["client-ops-write-format"],
    };
    const signed = signUpdateMetadata({ metadata: unsigned, policy, privateKeyPem });
    const target = path.join(root, "installed/Logseq.app");
    fs.mkdirSync(path.dirname(target));
    makeApp(target, "2.0.1-selfhost.7");

    const attempt = path.join(root, "attempt-success");
    fs.mkdirSync(attempt);
    const metadata = path.join(attempt, "metadata.json");
    fs.writeFileSync(metadata, `${JSON.stringify(signed)}\n`, { mode: 0o600 });
    const baseArgs = [
      "--archive", archive,
      "--metadata", metadata,
      "--parent-pid", "2147483647",
      "--relaunch", "false",
      "--target", target,
    ];
    const productionFaultAttempt = run(productionHelper,
      [...baseArgs, "--test-fail-after-swap"], { expectFailure: true });
    assert.match(productionFaultAttempt.stderr, /invalid argument near --test-fail-after-swap/);
    run(helper, [...baseArgs, "--verify-only"]);
    run(helper, baseArgs);
    assert.equal(installedVersion(target), "2.0.2-selfhost.1");

    makeApp(target, "2.0.1-selfhost.7");
    const parentExitFailureAttempt = path.join(root, "attempt-parent-exit-failure");
    fs.mkdirSync(parentExitFailureAttempt);
    const parentExitFailureMetadata = path.join(parentExitFailureAttempt, "metadata.json");
    fs.writeFileSync(parentExitFailureMetadata, `${JSON.stringify(signed)}\n`, { mode: 0o600 });
    run(helper, [
      "--archive", archive,
      "--metadata", parentExitFailureMetadata,
      "--parent-pid", "2147483647",
      "--relaunch", "false",
      "--target", target,
      "--test-fail-after-parent-exit",
    ], { expectFailure: true });
    assert.equal(installedVersion(target), "2.0.1-selfhost.7");

    const failureAttempt = path.join(root, "attempt-failure");
    fs.mkdirSync(failureAttempt);
    const failureMetadata = path.join(failureAttempt, "metadata.json");
    fs.writeFileSync(failureMetadata, `${JSON.stringify(signed)}\n`, { mode: 0o600 });
    run(helper, [
      "--archive", archive,
      "--metadata", failureMetadata,
      "--parent-pid", "2147483647",
      "--relaunch", "false",
      "--target", target,
      "--test-fail-after-swap",
    ], { expectFailure: true });
    assert.equal(installedVersion(target), "2.0.1-selfhost.7");

    const cleanupAttempt = path.join(root, "attempt-backup-cleanup");
    fs.mkdirSync(cleanupAttempt);
    const cleanupMetadata = path.join(cleanupAttempt, "metadata.json");
    fs.writeFileSync(cleanupMetadata, `${JSON.stringify(signed)}\n`, { mode: 0o600 });
    const productionCleanupFault = run(productionHelper,
      [...baseArgs, "--test-fail-backup-cleanup"], { expectFailure: true });
    assert.match(productionCleanupFault.stderr, /invalid argument near --test-fail-backup-cleanup/);
    const cleanup = run(helper, [
      "--archive", archive, "--metadata", cleanupMetadata,
      "--parent-pid", "2147483647", "--relaunch", "false", "--target", target,
      "--test-fail-backup-cleanup",
    ]);
    assert.match(cleanup.stderr, /backup cleanup.*injected failure during backup cleanup/);
    assert.equal(installedVersion(target), "2.0.2-selfhost.1");
    const backups = fs.readdirSync(path.dirname(target)).filter((name) => name.startsWith(".logseq-previous-"));
    assert.equal(backups.length, 1);
    assert.equal(installedVersion(path.join(path.dirname(target), backups[0])), "2.0.1-selfhost.7");

    const handoffTest = path.join(root, "handoff-test");
    const clangArch = process.arch === "x64" ? "x86_64" : process.arch;
    run("/usr/bin/xcrun", ["clang", "-arch", clangArch, "-O2", "-Wall", "-Wextra", "-Werror",
      path.join(repoRoot, "scripts/selfhost6/macos-update-bridge-handoff.test.c"), "-o", handoffTest]);
    fs.copyFileSync(handoffTest, path.join(candidate, "Contents/MacOS/Logseq"));
    const finalPayloadDirectory = path.join(candidate, "Contents/Resources/payload");
    fs.mkdirSync(finalPayloadDirectory);
    fs.writeFileSync(path.join(finalPayloadDirectory, "synthetic-sentinel"), "final-owned-resource\n");
    if (process.arch !== "x64") {
      run("/usr/bin/codesign", ["--force", "--deep", "--sign", "-", candidate]);
    }
    const bridgePayload = path.join(root, `Logseq-darwin-${process.arch}-2.0.2-selfhost.1.zip`);
    run("/usr/bin/ditto", ["-c", "-k", "--keepParent", candidate, bridgePayload]);
    const bridgeArchiveBytes = fs.readFileSync(bridgePayload);
    const bridgeArchiveSha256 = createHash("sha256").update(bridgeArchiveBytes).digest("hex");
    fs.chmodSync(bridgePayload, 0o600);
    const finalManifest = path.join(root, "final-manifest.json");
    fs.writeFileSync(finalManifest, manifest);
    const bridgeDescriptor = path.join(root, "final-descriptor.json");
    const bridgeUnsigned = { ...unsigned,
      "archive-size": bridgeArchiveBytes.length, "archive-sha256": bridgeArchiveSha256,
      "archive-sha512": createHash("sha512").update(bridgeArchiveBytes).digest("hex"),
      "immutable-object-key": `${releaseLineId}/${sourceSha}/${bridgeArchiveSha256}/darwin/${process.arch}/${path.basename(bridgePayload)}` };
    const writeBridgeDescriptor = (value) => fs.writeFileSync(bridgeDescriptor,
      JSON.stringify({ signedMetadata: signUpdateMetadata({ metadata: value, policy, privateKeyPem }) }));
    writeBridgeDescriptor(bridgeUnsigned);
    const nativeStrings = path.join(root, "native-strings.json");
    fs.writeFileSync(nativeStrings, JSON.stringify({
      en: { title: "Update error", description: "Open the app again." },
      "zh-CN": { title: "更新错误", description: "请重新打开应用。" },
    }));
    const buildBridge = (app, expectedSource = sourceSha, expectFailure = false) => run(process.execPath, [
      path.join(repoRoot, "resources/updater/build-macos-update-helper.mjs"),
      "--bridge", "--test-only", "--public-key-base64", publicKeyBase64,
      "--arch", process.arch, "--output", app,
      "--payload", bridgePayload, "--descriptor", bridgeDescriptor,
      "--target-manifest", finalManifest, "--native-strings", nativeStrings,
      "--final-version", "2.0.2-selfhost.1", "--final-source-full-sha", expectedSource,
      "--bridge-source-full-sha", "c".repeat(40),
    ], { expectFailure });
    const bridgeApp = path.join(root, "bridge/Logseq.app");
    const previousMask = process.umask(0o077);
    try { buildBridge(bridgeApp); } finally { process.umask(previousMask); }
    const bridgeInfo = JSON.parse(run("/usr/bin/plutil", ["-convert", "json", "-o", "-",
      path.join(bridgeApp, "Contents/Info.plist")]).stdout);
    assert.equal(bridgeInfo.CFBundleIdentifier, bundleIdentity);
    assert.equal(bridgeInfo.CFBundleShortVersionString, "2.0.1-selfhost.7");
    assert.equal(bridgeInfo.LSUIElement, true);
    assert.equal(Object.hasOwn(bridgeInfo, "LSMultipleInstancesProhibited"), false);
    assert.equal(Object.hasOwn(bridgeInfo, "CFBundleURLTypes"), false);
    const bridgeResources = path.join(bridgeApp, "Contents/Resources");
    const outer = JSON.parse(fs.readFileSync(path.join(bridgeResources, "updater/TARGET_BUILD_MANIFEST.json")));
    assert.equal(outer["target-version"], "2.0.1-selfhost.7");
    assert.equal(outer["target-source-full-sha"], "c".repeat(40));
    assert.equal(fs.readdirSync(bridgeResources, { recursive: true }).filter((name) => name.endsWith("TARGET_BUILD_MANIFEST.json")).length, 1);
    assert.deepEqual(fs.readFileSync(path.join(bridgeResources, "payload", path.basename(bridgePayload))), bridgeArchiveBytes);
    assert.deepEqual(fs.readFileSync(path.join(bridgeResources, "payload", `${path.basename(bridgePayload)}.selfhost6.json`)),
      fs.readFileSync(bridgeDescriptor));
    const bridgeExecutable = path.join(bridgeApp, "Contents/MacOS/LogseqBridge");
    assert.equal(run("/usr/bin/lipo", ["-archs", bridgeExecutable]).stdout.trim(), process.arch === "x64" ? "x86_64" : "arm64");
    for (const directory of ["", "Contents", "Contents/MacOS", "Contents/Resources", "Contents/Resources/updater", "Contents/Resources/payload"])
      assert.equal(fs.statSync(path.join(bridgeApp, directory)).mode & 0o777, 0o755);
    assert.equal(fs.statSync(bridgeExecutable).mode & 0o777, 0o755);
    assert.equal(fs.statSync(path.join(bridgeResources, "payload", path.basename(bridgePayload))).mode & 0o777, 0o644);
    assert.equal(fs.existsSync(path.join(bridgeApp, "Contents/Frameworks")), false);
    assert.equal(fs.existsSync(path.join(bridgeResources, "app.asar")), false);
    const wrongSourceApp = path.join(root, "wrong-bridge-source/Logseq.app");
    buildBridge(wrongSourceApp, "b".repeat(40), true);
    assert.equal(fs.existsSync(path.dirname(wrongSourceApp)), false);
    writeBridgeDescriptor({ ...bridgeUnsigned, arch: process.arch === "arm64" ? "x64" : "arm64" });
    const wrongArchApp = path.join(root, "wrong-bridge-arch/Logseq.app");
    buildBridge(wrongArchApp, sourceSha, true);
    assert.equal(fs.existsSync(path.dirname(wrongArchApp)), false);
    writeBridgeDescriptor(bridgeUnsigned);
    const changedArchive = Buffer.from(bridgeArchiveBytes);
    changedArchive[0] ^= 1;
    fs.writeFileSync(bridgePayload, changedArchive);
    const wrongBytesApp = path.join(root, "wrong-bridge-bytes/Logseq.app");
    buildBridge(wrongBytesApp, sourceSha, true);
    assert.equal(fs.existsSync(path.dirname(wrongBytesApp)), false);

    const sourceFixture = path.join(root, "ProjectUpdater-fixture/ProjectUpdater");
    fs.mkdirSync(path.dirname(sourceFixture));
    fs.copyFileSync(handoffTest, sourceFixture);
    fs.chmodSync(sourceFixture, 0o755);
    const handoff = run(handoffTest, [sourceFixture, target, path.join(root, "unrelated/Logseq.app")]);
    assert.match(handoff.stdout, /PASS exact-source-kernel-exit-before-handoff/);
    assert.match(handoff.stdout, /PASS unrelated-target-left-running/);
    assert.match(handoff.stdout, /PASS already-exited-source-needs-no-wait/);

    const profile = path.join(repoRoot, "scripts/selfhost6/macos-update-bridge.test.sb");
    const sandbox = (command, args) => ["-D", `root=${fs.realpathSync(root)}`, "-f", profile, command, ...args];
    fs.writeFileSync(canary, "synthetic-outside-canary\n", { flag: "wx" });
    run("/usr/bin/sandbox-exec", sandbox(handoffTest, ["--boundary", canary]));
    const boundary = path.join(root, "bridge-boundary.dylib");
    run("/usr/bin/xcrun", ["clang", "-arch", clangArch, "-dynamiclib", "-Wall", "-Wextra", "-Werror", "-framework", "CoreFoundation",
      path.join(repoRoot, "scripts/selfhost6/macos-update-bridge-boundary.test.c"), "-o", boundary]);
    run("/usr/bin/codesign", ["--force", "--deep", "--sign", "-", bridgeApp]);
    fs.mkdirSync(path.join(root, "home")); fs.mkdirSync(path.join(root, "tmp"));
    run("/usr/bin/mkfifo", [path.join(root, "final-input")]);
    finalInput = fs.openSync(path.join(root, "final-input"), fs.constants.O_RDWR | fs.constants.O_NONBLOCK);
    const isolatedEnv = { ...process.env, HOME: path.join(root, "home"), TMPDIR: path.join(root, "tmp"),
      LOGSEQ_BRIDGE_TEST_ROOT: fs.realpathSync(root), LOGSEQ_BRIDGE_TEST_APP: bridgeApp, LOGSEQ_BRIDGE_TEST_TOOL: handoffTest };
    const embeddedPayload = path.join(bridgeResources, "payload", path.basename(bridgePayload));
    fs.writeFileSync(embeddedPayload, changedArchive);
    run("/usr/bin/codesign", ["--force", "--deep", "--sign", "-", bridgeApp]);
    const badPayloadRoot = path.join(root, "bad-native-payload");
    fs.mkdirSync(badPayloadRoot);
    const badPayload = run("/usr/bin/sandbox-exec", sandbox("/usr/bin/env", [
      `DYLD_INSERT_LIBRARIES=${boundary}`, bridgeExecutable,
    ]), { env: { ...isolatedEnv, LOGSEQ_BRIDGE_TEST_ROOT: badPayloadRoot }, expectFailure: true, timeout: 5_000 });
    assert.equal(badPayload.error, undefined);
    assert.equal(badPayload.status, 1);
    assert.match(badPayload.stderr, /archive bytes do not match signed metadata/);
    assert.deepEqual(fs.readdirSync(badPayloadRoot).sort(), ["boundary-loaded", "native-alert"]);
    assert.equal(installedVersion(bridgeApp), "2.0.1-selfhost.7");
    assert.deepEqual(fs.readdirSync(path.dirname(bridgeApp)), ["Logseq.app"]);
    fs.writeFileSync(embeddedPayload, bridgeArchiveBytes);
    run("/usr/bin/codesign", ["--force", "--deep", "--sign", "-", bridgeApp]);
    sourceProcess = spawn("/usr/bin/sandbox-exec", sandbox(sourceFixture, ["--target", bridgeApp]), {
      env: isolatedEnv, stdio: ["pipe", "pipe", "pipe"],
    });
    sourceClosed = once(sourceProcess, "close");
    const [ready] = await once(sourceProcess.stdout, "data");
    assert.equal(ready.toString(), "R");
    const bridgeProcess = spawn("/usr/bin/sandbox-exec", sandbox("/usr/bin/env", [
      `DYLD_INSERT_LIBRARIES=${boundary}`, bridgeExecutable, "--test-fail-backup-cleanup",
    ]), { env: { ...isolatedEnv, LOGSEQ_BRIDGE_TEST_SOURCE_PID: String(sourceProcess.pid) }, stdio: ["ignore", "pipe", "pipe"] });
    bridgeClosed = once(bridgeProcess, "close");
    let bridgeStderr = "";
    bridgeProcess.stderr.on("data", (data) => { bridgeStderr += data; });
    const waitForMarker = async (name) => {
      const deadline = Date.now() + 10_000;
      while (!fs.existsSync(path.join(root, name))) {
        assert.equal(bridgeProcess.exitCode, null, bridgeStderr);
        assert.ok(Date.now() < deadline, `missing native component marker ${name}: ${bridgeStderr}`);
        await new Promise((resolve) => setTimeout(resolve, 20));
      }
    };
    await waitForMarker("source-subscribed");
    assert.equal(fs.existsSync(path.join(root, "boundary-loaded")), true);
    assert.equal(fs.existsSync(path.join(root, "launch-requested")), false);
    const contenderRoot = path.join(root, "contender");
    fs.mkdirSync(contenderRoot);
    const contender = run("/usr/bin/sandbox-exec", sandbox("/usr/bin/env", [
      `DYLD_INSERT_LIBRARIES=${boundary}`, bridgeExecutable,
    ]), { env: { ...isolatedEnv, LOGSEQ_BRIDGE_TEST_ROOT: contenderRoot }, expectFailure: true, timeout: 5_000 });
    assert.equal(contender.error, undefined);
    assert.match(contender.stderr, /another bridge owns this installation/);
    assert.equal(fs.existsSync(path.join(contenderRoot, "native-alert")), true);
    assert.equal(fs.existsSync(path.join(contenderRoot, "launch-requested")), false);
    assert.equal(fs.existsSync(path.join(contenderRoot, "source-subscribed")), false);
    sourceProcess.stdin.end();
    assert.equal((await sourceClosed)[0], 0);
    assert.equal((await bridgeClosed)[0], 0, bridgeStderr);
    assert.match(bridgeStderr, /backup cleanup.*injected failure during backup cleanup/);
    assert.equal(fs.existsSync(path.join(root, "native-alert")), false);
    assert.equal(fs.existsSync(path.join(root, "launch-requested")), true);
    assert.equal(installedVersion(bridgeApp), "2.0.2-selfhost.1");
    assert.equal(fs.readFileSync(path.join(bridgeResources, "payload/synthetic-sentinel"), "utf8"), "final-owned-resource\n");
    const bridgeBackups = fs.readdirSync(path.dirname(bridgeApp)).filter((name) => name.startsWith(".logseq-previous-"));
    assert.equal(bridgeBackups.length, 1);
    assert.equal(installedVersion(path.join(path.dirname(bridgeApp), bridgeBackups[0])), "2.0.1-selfhost.7");
    const finalPid = Number(fs.readFileSync(path.join(root, "final-pid"), "utf8"));
    assert.ok(Number.isInteger(finalPid) && finalPid > 1);
    process.kill(finalPid, 0);
    run(handoffTest, ["--lock", path.dirname(bridgeApp)]);
    fs.writeSync(finalInput, "X"); fs.closeSync(finalInput); finalInput = undefined;
    run(handoffTest, ["--wait-final", String(finalPid)]);
    assert.equal(fs.readFileSync(path.join(root, "final-exited"), "utf8"), "normal-exit\n");
  } finally {
    if (sourceProcess?.stdin.writable) sourceProcess.stdin.end();
    if (sourceClosed) await sourceClosed;
    if (bridgeClosed) await bridgeClosed;
    if (finalInput !== undefined) {
      fs.writeSync(finalInput, "X"); fs.closeSync(finalInput);
      if (fs.existsSync(path.join(root, "final-pid")))
        run(path.join(root, "handoff-test"), ["--wait-final", fs.readFileSync(path.join(root, "final-pid"), "utf8").trim()]);
    }
    if (fs.existsSync(canary)) fs.unlinkSync(canary);
    fs.rmSync(root, { recursive: true, force: true });
  }
});
