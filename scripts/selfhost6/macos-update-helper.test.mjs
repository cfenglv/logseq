import assert from "node:assert/strict";
import { createHash, generateKeyPairSync } from "node:crypto";
import { spawnSync } from "node:child_process";
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

for (const arch of new Set([process.arch, "x64"])) {
test(`macOS ${arch} helper verifies, replaces, and rolls back the project-signed target`, { timeout: 120_000 }, (t) => {
  if (process.platform !== "darwin") return t.skip("macOS-only physical helper test");
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "selfhost6-helper-test-"));
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
      "--arch", arch,
      "--output", helper,
    ]);
    const productionHelper = path.join(root, "ProjectUpdater-production");
    run(process.execPath, [
      path.join(repoRoot, "resources/updater/build-macos-update-helper.mjs"),
      "--arch", arch,
      "--output", productionHelper,
    ]);

    const sourceSha = "a".repeat(40);
    const manifestObject = {
      "schema-version": 1,
      "target-source-full-sha": sourceSha,
      "target-version": "2.0.2-selfhost.1",
      "release-line-id": releaseLineId,
      platform: "darwin",
      arch,
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
    makeApp(candidate, "2.0.2-selfhost.1", manifest, arch !== "x64");
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
      arch,
      "bundle-identity": bundleIdentity,
      "immutable-object-key": `${releaseLineId}/${sourceSha}/${archiveSha256}/darwin/${arch}/Logseq.zip`,
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
    if (arch === "x64") {
      run("/usr/bin/codesign", ["--verify", "--deep", "--strict", candidate], { expectFailure: true });
    }
    if (arch === "arm64") {
      const unsignedCandidate = path.join(root, "unsigned/Logseq.app");
      makeApp(unsignedCandidate, "2.0.2-selfhost.1", manifest, false);
      const unsignedArchive = path.join(root, "unsigned.zip");
      run("/usr/bin/ditto", ["-c", "-k", "--keepParent", unsignedCandidate, unsignedArchive]);
      const unsignedBytes = fs.readFileSync(unsignedArchive);
      const unsignedSha256 = createHash("sha256").update(unsignedBytes).digest("hex");
      const unsignedMetadata = path.join(root, "unsigned-metadata.json");
      fs.writeFileSync(unsignedMetadata, JSON.stringify(signUpdateMetadata({
        metadata: { ...unsigned,
          "archive-size": unsignedBytes.length,
          "archive-sha256": unsignedSha256,
          "archive-sha512": createHash("sha512").update(unsignedBytes).digest("hex"),
          "immutable-object-key": `${releaseLineId}/${sourceSha}/${unsignedSha256}/darwin/${arch}/unsigned.zip`,
        }, policy, privateKeyPem,
      })));
      const rejected = run(helper, [
        "--archive", unsignedArchive, "--metadata", unsignedMetadata,
        "--parent-pid", "2147483647", "--relaunch", "false", "--target", target,
      ], { expectFailure: true });
      assert.match(rejected.stderr, /codesign failed/);
      assert.equal(installedVersion(target), "2.0.1-selfhost.7");
    }
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
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
}
