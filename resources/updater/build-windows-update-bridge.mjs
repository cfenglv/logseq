#!/usr/bin/env node
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import { createRequire } from "node:module";
import os from "node:os";
import path from "node:path";
import { pipeline } from "node:stream/promises";
import { fileURLToPath } from "node:url";
import { loadProjectSigningPolicy, verifySignedUpdateMetadata } from "./project-update-signature.mjs";
import { validateTargetBuildManifest } from "./target-build-manifest.mjs";
import { buildTargetManifest, readReleasePolicy } from "../../scripts/lib/selfhost6-release-identity.mjs";

const require = createRequire(import.meta.url);
const updaterRoot = path.dirname(fileURLToPath(import.meta.url));
const quote = value => '"' + value.replaceAll("$", () => "$$").replaceAll('"', '$\\"')
  .replaceAll("\r", "$\\r").replaceAll("\n", "$\\n") + '"';

export function windowsBridgeConfiguration({ root, templates, plugins, payloadName, payloadSize, payloadSha512, strings }) {
  assert.equal(path.basename(payloadName), payloadName);
  assert.ok(Number.isSafeInteger(payloadSize) && payloadSize > 0 && payloadSize < 0x80000000);
  assert.match(payloadSha512, /^[0-9a-f]{128}$/);
  const lines = [
    "OutFile " + quote(path.join(root, "stub.exe")),
    "!addincludedir " + quote(path.join(templates, "include")),
    "!addplugindir /x86-unicode " + quote(path.join(plugins, "x86-unicode")),
    "!define BRIDGE_PAYLOAD_NAME " + quote(payloadName),
    "!define BRIDGE_PAYLOAD_SIZE " + payloadSize,
    "!define BRIDGE_PAYLOAD_SHA512 " + quote(payloadSha512),
  ];
  for (const [locale, id] of [["en", 1033], ["zh-CN", 2052]]) {
    const text = strings[locale]?.description;
    assert.ok(typeof text === "string" && text.length > 0, "missing native update description");
    lines.push(`LangString BridgeErrorDescription ${id} ${quote(text)}`);
  }
  return lines.join("\n") + "\n";
}

async function main() {
  assert.equal(process.platform, "win32", "compile the bridge on Windows");
  assert.equal(process.arch, "x64", "the immutable .6 automatic Windows entry is x64");
  const flags = new Set(["--payload", "--descriptor", "--target-manifest", "--native-strings",
    "--final-version", "--final-source-full-sha", "--bridge-source-full-sha", "--arch", "--output"]);
  const options = new Map();
  for (let index = 2; index < process.argv.length; index += 2) {
    const flag = process.argv[index], value = process.argv[index + 1];
    assert.ok(flags.has(flag) && value && !options.has(flag), "invalid bridge build argument");
    options.set(flag, value);
  }
  for (const flag of flags) assert.ok(options.get(flag), `missing ${flag}`);
  assert.equal(options.get("--arch"), "x64");
  const bridgePolicy = readReleasePolicy(path.join(updaterRoot, "selfhost-release-policy.json"));
  const policy = loadProjectSigningPolicy();
  const descriptorPath = path.resolve(options.get("--descriptor"));
  const descriptor = JSON.parse(fs.readFileSync(descriptorPath));
  const metadata = verifySignedUpdateMetadata({ signedMetadata: descriptor.signedMetadata, policy });
  assert.equal(metadata.platform, "win32");
  assert.equal(metadata.arch, "x64");
  assert.equal(metadata["target-version"], options.get("--final-version"));
  assert.equal(metadata["target-source-full-sha"], options.get("--final-source-full-sha"));
  assert.ok(require("semver").gt(metadata["target-version"], bridgePolicy.sourceVersion));
  const payload = path.resolve(options.get("--payload"));
  const name = `Logseq-win-x64-${metadata["target-version"]}-nsis.exe`;
  assert.equal(path.basename(payload), name);
  assert.equal(path.posix.basename(metadata["immutable-object-key"]), name);
  assert.equal(fs.statSync(payload).size, metadata["archive-size"]);
  const hashes = [createHash("sha256"), createHash("sha512")];
  for await (const chunk of fs.createReadStream(payload)) for (const hash of hashes) hash.update(chunk);
  assert.equal(hashes[0].digest("hex"), metadata["archive-sha256"]);
  assert.equal(hashes[1].digest("hex"), metadata["archive-sha512"]);
  const manifestBytes = fs.readFileSync(options.get("--target-manifest"));
  assert.equal(createHash("sha256").update(manifestBytes).digest("hex"), metadata["target-build-manifest-sha256"]);
  const manifest = validateTargetBuildManifest({
    manifest: JSON.parse(manifestBytes), archiveDigestVerified: true,
    expected: { targetSourceFullSha: metadata["target-source-full-sha"], targetVersion: metadata["target-version"],
      releaseLineId: policy.releaseLineId, platform: "win32", arch: "x64",
      bundleIdentity: policy.bundleIdentity, signingKeyIdentity: policy.keyId },
    currentFormats: { activation: bridgePolicy.activationWriteFormat, clientOps: bridgePolicy.clientOpsWriteFormat },
  });
  const bridgeManifest = buildTargetManifest({ policy: bridgePolicy,
    sourceFullSha: options.get("--bridge-source-full-sha"), targetVersion: bridgePolicy.sourceVersion,
    platform: "win32", arch: "x64", signingKeyIdentity: policy.keyId });
  for (const key of ["readable-activation-formats", "readable-client-ops-formats", "activation-write-format", "client-ops-write-format"]) {
    assert.deepEqual(manifest[key], metadata[key], "signed final format differs");
    assert.deepEqual(bridgeManifest[key], manifest[key], "bridge capability differs from fixed final");
  }
  const output = path.resolve(options.get("--output"));
  assert.equal(path.basename(output), `Logseq-win-x64-${bridgePolicy.sourceVersion}-nsis.exe`);
  assert.equal(fs.existsSync(output), false, "bridge output already exists");
  assert.equal(require("electron-builder/package.json").version, "26.17.0");
  const builderRequire = createRequire(require.resolve("electron-builder"));
  const { getMakeNsisPath, getNsisPluginsPath } = builderRequire("app-builder-lib/out/toolsets/windows.js");
  const { nsisTemplatesDir } = builderRequire("app-builder-lib/out/targets/nsis/nsisUtil.js");
  const compiler = await getMakeNsisPath(undefined), plugins = await getNsisPluginsPath(undefined);
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "windows-update-bridge-build-"));
  let createdOutput = false;
  try {
    const contents = path.join(temporary, "contents");
    fs.mkdirSync(contents);
    fs.writeFileSync(path.join(contents, "TARGET_BUILD_MANIFEST.json"), JSON.stringify(bridgeManifest, null, 2) + "\n");
    fs.copyFileSync(payload, path.join(contents, name));
    fs.copyFileSync(descriptorPath, path.join(contents, `${name}.selfhost6.json`));
    const configuration = path.join(temporary, "bridge-config.nsh");
    fs.writeFileSync(configuration, windowsBridgeConfiguration({ root: temporary,
      templates: nsisTemplatesDir, plugins, payloadName: name,
      payloadSize: metadata["archive-size"], payloadSha512: metadata["archive-sha512"],
      strings: JSON.parse(fs.readFileSync(options.get("--native-strings"))) }));
    function run(command, args, env = process.env) {
      const child = spawnSync(command, args, { cwd: contents, env, stdio: "inherit" });
      assert.equal(child.status, 0, "Windows bridge build command failed");
    }
    run(compiler.path, ["-V2", "-DBRIDGE_CONFIG=" + configuration,
      path.join(updaterRoot, "../windows-project-updater/bridge.nsi")], { ...process.env, ...compiler.env });
    assert.equal(require("7zip-bin/package.json").version, "5.2.0");
    const zip = path.join(path.dirname(require.resolve("7zip-bin/package.json")), "win/x64/7za.exe");
    const trailer = path.join(temporary, "payload.zip");
    run(zip, ["a", "-tzip", "-mx=0", "-bd", trailer, "TARGET_BUILD_MANIFEST.json", name, `${name}.selfhost6.json`]);
    fs.mkdirSync(path.dirname(output), { recursive: true });
    fs.copyFileSync(path.join(temporary, "stub.exe"), output, fs.constants.COPYFILE_EXCL);
    createdOutput = true;
    await pipeline(fs.createReadStream(trailer), fs.createWriteStream(output, { flags: "a" }));
    process.stdout.write(JSON.stringify({ status: "packaged", arch: "x64", payloadSha256: metadata["archive-sha256"] }) + "\n");
  } catch (error) {
    if (createdOutput) fs.unlinkSync(output);
    throw error;
  } finally {
    fs.rmSync(temporary, { recursive: true, force: true });
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url))
  main().catch(() => { process.stderr.write("windows-update-bridge-build-rejected\n"); process.exitCode = 1; });
