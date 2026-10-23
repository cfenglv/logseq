import assert from "node:assert/strict";
import { spawnSync, spawn } from "node:child_process";
import { createHash, generateKeyPairSync, randomBytes } from "node:crypto";
import { once } from "node:events";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { signUpdateMetadata, signingKeyIdentity, loadProjectSigningPolicy } from "../../resources/updater/project-update-signature.mjs";

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const run = (command, args, options = {}) => {
  const result = spawnSync(command, args, { encoding: "utf8", ...options });
  assert.equal(result.status, 0, result.stderr || result.stdout || result.error?.message);
  return result.stdout;
};
const sha256 = content => createHash("sha256").update(content).digest("hex");
const waitFor = async (predicate, label) => {
  const deadline = Date.now() + 15000;
  while (!predicate()) {
    assert.ok(Date.now() < deadline, `timeout: ${label}`);
    await new Promise(resolve => setTimeout(resolve, 25));
  }
};

test("Mac light bridge native download, retry and fresh-process continuation", { timeout: 120000 }, async context => {
  if (process.platform !== "darwin") return context.skip("macOS native AppKit test");
  const root = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), "mac-light-bridge-")));
  const openProcesses = new Set();
  const server = http.createServer();
  let responseMode = "ok", requests = 0, payload;
  server.on("request", (_request, response) => {
    requests++;
    response.writeHead(200, { "Content-Length": payload.length, "Content-Type": "application/zip" });
    if (responseMode === "interrupt") {
      response.write(payload.subarray(0, 16384));
      setTimeout(() => response.destroy(), 40);
    } else if (responseMode === "slow") {
      response.write(payload.subarray(0, 16384));
      // The client cancels this response before the rest is sent.
    } else { response.end(payload); }
  });
  try {
    await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
    const address = `http://127.0.0.1:${server.address().port}/final.zip`;
    const { privateKey, publicKey } = generateKeyPairSync("ed25519");
    const raw = publicKey.export({ type: "spki", format: "der" }).subarray(-32).toString("base64");
    const policy = { ...loadProjectSigningPolicy(), publicKeyBase64: raw, keyId: signingKeyIdentity(raw) };
    const source = "a".repeat(40);
    const nativeStrings = path.join(root, "native-strings.json");
    run("bb", ["lang:export-native-update-strings", nativeStrings], { cwd: repo });
    // Compile production conditionals too, with a synthetic policy in a separate fixture.
    // No production key is read and the candidate's policy is never modified.
    const production = path.join(root, "production-fixture");
    for (const name of ["resources/macos-project-updater/ProjectUpdater.swift",
      "resources/macos-project-updater/BridgeHandoff.c", "resources/macos-project-updater/BridgeHandoff.h",
      "resources/updater/build-macos-update-helper.mjs", "resources/updater/project-update-signature.mjs",
      "resources/updater/target-build-manifest.mjs", "resources/updater/selfhost-release-policy.json",
      "scripts/lib/selfhost6-release-identity.mjs", "resources/icons/logseq.icns"]) {
      const target = path.join(production, name);
      fs.mkdirSync(path.dirname(target), { recursive: true });
      fs.copyFileSync(path.join(repo, name), target);
    }
    fs.symlinkSync(path.join(repo, "node_modules"), path.join(production, "node_modules"));
    fs.writeFileSync(path.join(production, "resources/updater/project-signing-policy.json"), JSON.stringify(policy));
    const builtApps = new Map();
    for (const arch of ["x64", "arm64"]) {
      const final = path.join(root, arch, "final/Logseq.app");
      fs.mkdirSync(path.join(final, "Contents/MacOS"), { recursive: true });
      fs.mkdirSync(path.join(final, "Contents/Resources/updater"), { recursive: true });
      fs.copyFileSync("/usr/bin/true", path.join(final, "Contents/MacOS/Logseq"));
      fs.writeFileSync(path.join(final, "Contents/Resources/sample"), randomBytes(512 * 1024));
      const info = { CFBundleExecutable: "Logseq", CFBundleIdentifier: policy.bundleIdentity,
        CFBundleName: "Logseq", CFBundlePackageType: "APPL", CFBundleShortVersionString: "2.0.2-selfhost.1",
        CFBundleVersion: "2.0.2-selfhost.1" };
      run("/usr/bin/plutil", ["-convert", "xml1", "-o", path.join(final, "Contents/Info.plist"), "--", "-"],
        { input: JSON.stringify(info) });
      const manifest = { "schema-version": 1, "target-source-full-sha": source, "target-version": info.CFBundleShortVersionString,
        "release-line-id": policy.releaseLineId, platform: "darwin", arch, "bundle-identity": policy.bundleIdentity,
        "signing-key-identity": policy.keyId, "readable-activation-formats": ["selfhost-activation-v1"],
        "readable-client-ops-formats": ["official-client-ops-sqlite-v2+selfhost-upload-v1"],
        "activation-write-format": "selfhost-activation-v1", "client-ops-write-format": "official-client-ops-sqlite-v2+selfhost-upload-v1" };
      const manifestPath = path.join(final, "Contents/Resources/updater/TARGET_BUILD_MANIFEST.json");
      fs.writeFileSync(manifestPath, JSON.stringify(manifest) + "\n");
      run("/usr/bin/codesign", ["--force", "--deep", "--sign", "-", final]);
      const archive = path.join(root, arch, `Logseq-darwin-${arch}-${info.CFBundleShortVersionString}.zip`);
      run("/usr/bin/ditto", ["-c", "-k", "--keepParent", final, archive]);
      const bytes = fs.readFileSync(archive), hash = sha256(bytes);
      const metadata = { "schema-version": 1, algorithm: policy.algorithm, "key-id": policy.keyId,
        "release-line-id": policy.releaseLineId, "target-source-full-sha": source, "target-version": info.CFBundleShortVersionString,
        platform: "darwin", arch, "bundle-identity": policy.bundleIdentity,
        "immutable-object-key": `${policy.releaseLineId}/${source}/${hash}/darwin/${arch}/${path.basename(archive)}`,
        "archive-size": bytes.length, "archive-sha256": hash,
        "archive-sha512": createHash("sha512").update(bytes).digest("hex"),
        "target-build-manifest-sha256": sha256(fs.readFileSync(manifestPath)),
        ...Object.fromEntries(Object.entries(manifest).filter(([key]) => /formats$|write-format$/.test(key))) };
      const descriptor = archive + ".selfhost6.json";
      fs.writeFileSync(descriptor, JSON.stringify({ signedMetadata: signUpdateMetadata({ metadata, policy,
        privateKeyPem: privateKey.export({ type: "pkcs8", format: "pem" }) }) }));
      const app = path.join(root, arch, "bridge/Logseq.app");
      run(process.execPath, [path.join(repo, "resources/updater/build-macos-update-helper.mjs"),
        "--bridge", "--test-only", "--public-key-base64", raw, "--arch", arch, "--output", app,
        "--payload", archive, "--descriptor", descriptor, "--target-manifest", manifestPath,
        "--native-strings", nativeStrings, "--final-version", info.CFBundleShortVersionString,
        "--final-source-full-sha", source, "--bridge-source-full-sha", "b".repeat(40)]);
      assert.equal(fs.existsSync(path.join(app, "Contents/Resources/payload", path.basename(archive))), false);
      assert.deepEqual(fs.readFileSync(path.join(app, "Contents/Resources/payload", path.basename(descriptor))), fs.readFileSync(descriptor));
      const url = fs.readFileSync(path.join(app, "Contents/Resources/payload/download-url"), "utf8");
      assert.equal(url, `https://github.com/cfenglv/logseq/releases/download/${info.CFBundleShortVersionString}/${path.basename(archive)}\n`);
      run("/usr/bin/codesign", ["--force", "--sign", "-", app]);
      const productionApp = path.join(production, arch, "Logseq.app");
      run(process.execPath, [path.join(production, "resources/updater/build-macos-update-helper.mjs"),
        "--bridge", "--arch", arch, "--output", productionApp,
        "--payload", archive, "--descriptor", descriptor, "--target-manifest", manifestPath,
        "--native-strings", nativeStrings, "--final-version", info.CFBundleShortVersionString,
        "--final-source-full-sha", source, "--bridge-source-full-sha", "b".repeat(40)]);
      const strings = run("/usr/bin/strings", [path.join(productionApp, "Contents/MacOS/LogseqBridge")]);
      for (const option of ["--test-download-url", "--test-cache-root", "--test-events", "--test-fail-after-swap"])
        assert.equal(strings.includes(option), false, `production bridge must not contain ${option}`);
      builtApps.set(arch, { app, bytes, hash, name: path.basename(archive) });
      console.log(`compiled ${arch} light App: no embedded final ZIP`);
    }
    const arches = [process.arch];
    // Rosetta, if already present, supplements native tests; it is not an Intel hardware/old .6 budget receipt.
    if (process.arch === "arm64" && spawnSync("/usr/bin/arch", ["-x86_64", "/usr/bin/true"]).status === 0) arches.push("x64");
    for (const arch of arches) {
      const fixture = builtApps.get(arch);
      payload = fixture.bytes;
      const create = name => {
        const directory = path.join(root, `${arch}-${name}`);
        const app = path.join(directory, "Logseq.app");
        fs.mkdirSync(directory);
        fs.cpSync(fixture.app, app, { recursive: true });
        return { app, directory, cache: path.join(directory, "cache") };
      };
      const launch = async (fixture, flags = []) => {
        const events = path.join(fixture.directory, `events-${randomBytes(4).toString("hex")}`);
        fs.writeFileSync(events, "");
        const child = spawn("/usr/bin/open", ["-n", "-W", fixture.app, "--args", "--test-cache-root", fixture.cache,
          "--test-download-url", address, "--test-events", events, ...flags], { stdio: ["ignore", "pipe", "pipe"] });
        let stderr = "";
        child.stderr.on("data", chunk => { stderr += chunk; });
        const closed = once(child, "close");
        const entries = () => fs.readFileSync(events, "utf8").trim().split("\n");
        await waitFor(() => entries().some(line => line.startsWith("pid=")) || child.exitCode !== null, "native App launch");
        assert.equal(child.exitCode, null, stderr);
        const pid = Number(entries().find(line => line.startsWith("pid=")).slice(4));
        const running = { pid, entries, closed, child, events };
        openProcesses.add(running);
        return running;
      };
      const stage = (process, value) => waitFor(() => process.entries().includes(value), value);
      const exit = async process => {
        const [code] = await process.closed;
        assert.equal(code, 0);
        await waitFor(() => {
          const result = spawnSync("/bin/ps", ["-p", String(process.pid), "-o", "stat="], { encoding: "utf8" });
          return result.status !== 0 || result.stdout.trim().startsWith("Z");
        }, "native bridge has no live process");
        openProcesses.delete(process);
      };
      const version = fixture => run("/usr/libexec/PlistBuddy", ["-c", "Print :CFBundleShortVersionString", path.join(fixture.app, "Contents/Info.plist")]).trim();
      const success = create("success");
      responseMode = "ok";
      let count = requests;
      let current = await launch(success);
      await stage(current, "installed"); await exit(current);
      assert.equal(requests - count, 1);
      assert.equal(version(success), "2.0.2-selfhost.1");
      console.log(`${arch}: automatic download → verify → install → exit`);

      const retry = create("retry");
      responseMode = "interrupt";
      current = await launch(retry);
      await stage(current, "failed");
      assert.equal(version(retry), "2.0.1-selfhost.7");
      responseMode = "ok";
      globalThis.process.kill(current.pid, "SIGUSR1");
      await stage(current, "installed"); await exit(current);
      console.log(`${arch}: interrupted download stays in native bridge; Retry succeeds`);

      const partial = create("partial");
      responseMode = "slow"; count = requests;
      current = await launch(partial);
      await stage(current, "downloading");
      await waitFor(() => requests > count, "partial download request");
      globalThis.process.kill(current.pid, "SIGUSR2"); await exit(current);
      assert.equal(version(partial), "2.0.1-selfhost.7");
      assert.equal(fs.existsSync(path.join(partial.cache, fixture.hash, fixture.name)), false);
      responseMode = "ok";
      current = await launch(partial);
      await stage(current, "installed"); await exit(current);
      assert.equal(requests - count, 2);
      console.log(`${arch}: close during partial download; normal App reopen redownloads`);

      const complete = create("complete");
      count = requests;
      current = await launch(complete, ["--test-pause-before-install"]);
      await stage(current, "download-complete");
      globalThis.process.kill(current.pid, "SIGUSR2"); await exit(current);
      assert.equal(version(complete), "2.0.1-selfhost.7");
      assert.equal(sha256(fs.readFileSync(path.join(complete.cache, fixture.hash, fixture.name))), fixture.hash);
      responseMode = "interrupt"; // Any network request would fail; the complete cache must be reused and reverified.
      current = await launch(complete);
      await stage(current, "installed"); await exit(current);
      assert.equal(requests - count, 1);
      console.log(`${arch}: close before installation; fresh App launch revalidates complete cache without download`);

      if (arch === process.arch) {
        const invalid = create("bad-cache");
        const cached = path.join(invalid.cache, fixture.hash, fixture.name);
        fs.mkdirSync(path.dirname(cached), { recursive: true });
        const bad = Buffer.from(fixture.bytes); bad[0] ^= 1;
        fs.writeFileSync(cached, bad);
        count = requests;
        current = await launch(invalid);
        await stage(current, "failed");
        assert.equal(version(invalid), "2.0.1-selfhost.7");
        assert.equal(fs.existsSync(cached), false);
        assert.equal(requests, count);
        responseMode = "ok";
        globalThis.process.kill(current.pid, "SIGUSR1");
        await stage(current, "installed"); await exit(current);
        assert.equal(requests - count, 1);
        console.log(`${arch}: corrupt complete cache rejected and discarded; Retry downloads valid bytes`);

        const failed = create("swap-failure");
        responseMode = "ok";
        current = await launch(failed, ["--test-fail-after-swap", "-AppleLanguages", "(zh-Hans)"]);
        await stage(current, "failed");
        assert.equal(version(failed), "2.0.1-selfhost.7");
        globalThis.process.kill(current.pid, "SIGUSR2"); await exit(current);
        current = await launch(failed);
        await stage(current, "installed"); await exit(current);
        console.log(`${arch}: failed swap restores bridge; reopening can retry installation`);
      }
    }
  } finally {
    for (const process of openProcesses) {
      try { globalThis.process.kill(process.pid, "SIGUSR2"); } catch {}
      await process.closed;
    }
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
    fs.rmSync(root, { recursive: true, force: true });
  }
});
