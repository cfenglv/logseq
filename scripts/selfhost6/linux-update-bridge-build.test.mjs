import assert from "node:assert/strict";
import { createHash, generateKeyPairSync } from "node:crypto";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import { createRequire } from "node:module";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import {
  algorithm, bundleIdentity, payloadDomain, releaseLineId,
  signUpdateMetadata, signingKeyIdentity,
} from "../../resources/updater/project-update-signature.mjs";

const require = createRequire(import.meta.url);
const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const sha256 = (value) => createHash("sha256").update(value).digest("hex");

test("Linux bridge builder binds the signed final artifact before compiling", { timeout: 30_000 }, async (t) => {
  if (process.platform !== "linux") return t.skip("native Linux compiler baseline required");
  const tools = process.env.LOGSEQ_TEST_APPIMAGE_TOOLS;
  assert.ok(tools, "LOGSEQ_TEST_APPIMAGE_TOOLS must point to the pinned AppImage 1.0.2 toolset");
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "bridge-build-inputs-"));
  try {
    const updater = path.join(root, "repo/resources/updater");
    fs.mkdirSync(updater, { recursive: true });
    for (const name of ["build-linux-update-bridge.mjs", "project-update-signature.mjs", "target-build-manifest.mjs"])
      fs.copyFileSync(path.join(repo, "resources/updater", name), path.join(updater, name));
    const native = path.join(root, "repo/resources/linux-project-updater");
    fs.mkdirSync(native);
    fs.copyFileSync(path.join(repo, "resources/linux-project-updater/bridge.c"), path.join(native, "bridge.c"));
    const { privateKey, publicKey } = generateKeyPairSync("ed25519");
    const publicKeyBase64 = publicKey.export({ format: "der", type: "spki" }).subarray(-32).toString("base64");
    const policy = { schemaVersion: 1, algorithm, payloadDomain, releaseLineId, bundleIdentity,
      keyId: signingKeyIdentity(publicKeyBase64), publicKeyBase64 };
    fs.writeFileSync(path.join(updater, "project-signing-policy.json"), JSON.stringify(policy));
    fs.copyFileSync(path.join(repo, "resources/updater/selfhost-release-policy.json"),
      path.join(updater, "selfhost-release-policy.json"));
    fs.mkdirSync(path.join(root, "repo/scripts/lib"), { recursive: true });
    fs.copyFileSync(path.join(repo, "scripts/lib/selfhost6-release-identity.mjs"),
      path.join(root, "repo/scripts/lib/selfhost6-release-identity.mjs"));
    const version = "2.0.2-selfhost.1", source = "a".repeat(40), arch = process.arch;
    const label = arch === "x64" ? "x86_64" : "arm64";
    const outputName = `Logseq-linux-${label}-2.0.1-selfhost.7.AppImage`;
    const payload = path.join(root, `Logseq-linux-${label}-${version}.AppImage`);
    const content = Buffer.from("synthetic opaque final archive\n");
    fs.writeFileSync(payload, content);
    fs.chmodSync(payload, 0o600);
    const formats = {
      "readable-activation-formats": ["selfhost-activation-v1"],
      "readable-client-ops-formats": ["official-client-ops-sqlite-v2+selfhost-upload-v1"],
      "activation-write-format": "selfhost-activation-v1",
      "client-ops-write-format": "official-client-ops-sqlite-v2+selfhost-upload-v1",
    };
    const manifest = path.join(root, "manifest.json");
    const manifestBytes = Buffer.from(JSON.stringify({
      "schema-version": 1, "target-source-full-sha": source, "target-version": version,
      "release-line-id": releaseLineId, platform: "linux", arch,
      "bundle-identity": bundleIdentity, "signing-key-identity": policy.keyId, ...formats,
    }));
    fs.writeFileSync(manifest, manifestBytes);
    const metadata = {
      "schema-version": 1, algorithm, "key-id": policy.keyId,
      "release-line-id": releaseLineId, "target-source-full-sha": source, "target-version": version,
      platform: "linux", arch, "bundle-identity": bundleIdentity,
      "immutable-object-key": `${releaseLineId}/${source}/${sha256(content)}/linux/${arch}/${path.basename(payload)}`,
      "archive-size": content.length, "archive-sha256": sha256(content),
      "archive-sha512": createHash("sha512").update(content).digest("hex"),
      "target-build-manifest-sha256": sha256(manifestBytes), ...formats,
    };
    const descriptor = path.join(root, "descriptor.json");
    const writeDescriptor = (values) => fs.writeFileSync(descriptor, JSON.stringify({
      signedMetadata: signUpdateMetadata({ metadata: values, policy,
        privateKeyPem: privateKey.export({ format: "pem", type: "pkcs8" }) }),
    }));
    writeDescriptor(metadata);
    const strings = path.join(root, "strings.json");
    fs.writeFileSync(strings, JSON.stringify({
      en: { title: "Update error", description: "Open the app again." },
      "zh-CN": { title: "更新错误", description: "请重新打开应用。" },
    }));
    const invoke = (output, expectedSource = source) => spawnSync(process.execPath, [
      path.join(updater, "build-linux-update-bridge.mjs"),
      "--payload", payload, "--descriptor", descriptor, "--target-manifest", manifest,
      "--native-strings", strings, "--final-version", version, "--final-source-full-sha", expectedSource,
      "--bridge-source-full-sha", "c".repeat(40), "--appimage-tools", tools,
      "--arch", arch, "--output", output,
    ], { encoding: "utf8", env: { ...process.env,
      NODE_PATH: path.dirname(path.dirname(require.resolve("semver/package.json"))) } });

    await t.test("matching inputs package one outer manifest and an opaque final payload", () => {
      const output = path.join(root, "compiled", outputName);
      const previousMask = process.umask(0o077);
      let result;
      try { result = invoke(output); } finally { process.umask(previousMask); }
      assert.equal(result.status, 0, result.stderr || result.stdout);
      const elf = fs.readFileSync(output);
      assert.equal(elf.subarray(0, 4).toString("hex"), "7f454c46");
      assert.equal(elf.readUInt16LE(18), arch === "arm64" ? 183 : 62);
      assert.equal(fs.statSync(output).mode & 0o777, 0o755);
      const offset = fs.statSync(path.join(tools, "runtimes", `runtime-${arch}`)).size;
      const listing = spawnSync("unsquashfs", ["-ll", "-offset", String(offset), output], { encoding: "utf8" });
      assert.equal(listing.status, 0, listing.stderr);
      assert.equal((listing.stdout.match(/TARGET_BUILD_MANIFEST\.json/g) ?? []).length, 1);
      const unpacked = path.join(root, "unpacked");
      const unpack = spawnSync("unsquashfs", ["-d", unpacked, "-offset", String(offset), output], { encoding: "utf8" });
      assert.equal(unpack.status, 0, unpack.stderr);
      const extract = (entry) => fs.readFileSync(path.join(unpacked, entry));
      for (const directory of ["", "bridge", "bridge/payload", "bridge/resources", "bridge/resources/updater"])
        assert.equal(fs.statSync(path.join(unpacked, directory)).mode & 0o777, 0o755, `packaged directory mode: ${directory}`);
      for (const file of ["bridge/resources/updater/TARGET_BUILD_MANIFEST.json", `bridge/payload/${path.basename(payload)}`,
        `bridge/payload/${path.basename(payload)}.selfhost6.json`])
        assert.equal(fs.statSync(path.join(unpacked, file)).mode & 0o777, 0o644, `packaged resource mode: ${file}`);
      const outer = JSON.parse(extract("bridge/resources/updater/TARGET_BUILD_MANIFEST.json"));
      assert.equal(outer["target-version"], "2.0.1-selfhost.7");
      assert.equal(outer["target-source-full-sha"], "c".repeat(40));
      assert.equal(outer.arch, arch);
      assert.deepEqual(extract(`bridge/payload/${path.basename(payload)}`), content);
      assert.equal(fs.statSync(path.join(unpacked, "AppRun")).mode & 0o777, 0o755);
      const native = extract("AppRun");
      assert.equal(native.subarray(0, 4).toString("hex"), "7f454c46");
      assert.equal(native.readUInt16LE(18), arch === "arm64" ? 183 : 62);
    });
    await t.test("wrong expected source is rejected before output writes", () => {
      const output = path.join(root, "wrong-source", outputName);
      const result = invoke(output, "b".repeat(40));
      assert.notEqual(result.status, 0);
      assert.match(result.stderr, /AssertionError/);
      assert.equal(fs.existsSync(path.dirname(output)), false);
    });
    await t.test("a differently signed architecture is rejected", () => {
      writeDescriptor({ ...metadata, arch: arch === "arm64" ? "x64" : "arm64" });
      const output = path.join(root, "wrong-arch", outputName);
      const result = invoke(output);
      assert.notEqual(result.status, 0);
      assert.equal(fs.existsSync(path.dirname(output)), false);
      writeDescriptor(metadata);
    });
    await t.test("changed same-size archive is rejected before output writes", () => {
      const changed = Buffer.from(content);
      changed[0] ^= 1;
      fs.writeFileSync(payload, changed);
      const output = path.join(root, "wrong-digest", outputName);
      const result = invoke(output);
      assert.notEqual(result.status, 0);
      assert.equal(fs.existsSync(path.dirname(output)), false);
    });
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
