#!/usr/bin/env node

import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawn, spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

assert.equal(
  process.platform,
  "darwin",
  "the appOut xattr release contract requires the real macOS codesign runtime",
);

const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const staticRoot = path.join(repoRoot, "static");
const sourceNodeModules = path.join(staticRoot, "node_modules");
const sourceElectronApp = path.join(
  sourceNodeModules,
  "electron",
  "dist",
  "Electron.app",
);
const sourceHelper = path.join(
  sourceElectronApp,
  "Contents",
  "Frameworks",
  "Electron Helper.app",
  "Contents",
  "MacOS",
  "Electron Helper",
);
const outputRoot = path.join(staticRoot, "dist");
const fixtureShim = path.join(
  repoRoot,
  "scripts",
  "fixtures",
  "electron-appout-xattr-command-shim.mjs",
);
const expectedVersion = "2.0.1-selfhost.5";
const quarantineValue =
  "0083;7f5e1000;LogseqAppOutXattrContract;01234567-89AB-CDEF-0123-456789ABCDEF";
const finderInfoHex =
  "0000000000000000000000000000000000000000000000000000000000000001";
const cleanupAttributes = [
  "com.apple.FinderInfo",
  "com.apple.ResourceFork",
  "com.apple.provenance",
];
const unsafeArtifactAttributes = [
  "com.apple.FinderInfo",
  "com.apple.ResourceFork",
];

const run = (command, args, options = {}) => {
  const result = spawnSync(command, args, {
    cwd: options.cwd ?? repoRoot,
    env: options.env ?? process.env,
    encoding: options.encoding ?? "utf8",
    stdio: options.stdio ?? "pipe",
    maxBuffer: 128 * 1024 * 1024,
  });
  if (options.expectSuccess !== false) {
    assert.equal(
      result.status,
      0,
      `${command} ${args.join(" ")} failed:\n${result.stdout ?? ""}${
        result.stderr ?? ""
      }`,
    );
  }
  return result;
};

const xattr = (args, options = {}) =>
  run("/usr/bin/xattr", args, options);

const readXattrHex = (target, name) => {
  const result = xattr(["-px", name, target], { expectSuccess: false });
  return result.status === 0
    ? result.stdout.replace(/\s+/g, "").toLowerCase()
    : null;
};

const recursiveXattrs = (target) => {
  const result = xattr(["-lr", target], { expectSuccess: false });
  assert.ok(
    result.status === 0 || result.status === 1,
    `could not inspect recursive xattrs for ${target}`,
  );
  return result.stdout;
};

const digest = (value) =>
  crypto.createHash("sha256").update(value).digest("hex");

const runReleaseWithInjectedAppOut = ({ env, appOutHelper, tracePath }) =>
  new Promise((resolve, reject) => {
    const child = spawn("pnpm", ["release-electron:unsigned"], {
      cwd: repoRoot,
      env,
      stdio: ["ignore", "pipe", "pipe"],
    });
    const stdout = [];
    const stderr = [];
    let injected = false;
    let injectionError;
    child.stdout.on("data", (chunk) => stdout.push(chunk));
    child.stderr.on("data", (chunk) => stderr.push(chunk));
    child.on("error", reject);

    const poll = setInterval(() => {
      if (injected || injectionError || !fs.existsSync(appOutHelper)) return;
      try {
        xattr(["-wx", "com.apple.FinderInfo", finderInfoHex, appOutHelper]);
        xattr([
          "-w",
          "com.apple.ResourceFork",
          "logseq-appout-xattr-contract-resource-fork",
          appOutHelper,
        ]);
        xattr([
          "-w",
          "com.apple.provenance",
          "logseq-appout-xattr-contract-provenance",
          appOutHelper,
        ]);
        xattr([
          "-w",
          "com.apple.quarantine",
          quarantineValue,
          appOutHelper,
        ]);
        for (const attribute of [
          ...cleanupAttributes,
          "com.apple.quarantine",
        ]) {
          assert.ok(
            readXattrHex(appOutHelper, attribute),
            `could not inject ${attribute} into the temporary appOut helper`,
          );
        }
        fs.appendFileSync(
          tracePath,
          `${JSON.stringify({
            command: "contract-inject-xattrs",
            args: [appOutHelper],
            timestamp: process.hrtime.bigint().toString(),
          })}\n`,
        );
        injected = true;
      } catch (error) {
        injectionError = error;
      }
    }, 5);

    child.on("close", (status) => {
      clearInterval(poll);
      resolve({
        status,
        stdout: Buffer.concat(stdout).toString("utf8"),
        stderr: Buffer.concat(stderr).toString("utf8"),
        injected,
        injectionError,
      });
    });
  });

const snapshotExternalApp = () => {
  const installedApp = "/Applications/Logseq.app";
  if (!fs.existsSync(installedApp)) return null;
  const stat = fs.lstatSync(installedApp);
  return {
    mode: stat.mode,
    size: stat.size,
    mtimeMs: stat.mtimeMs,
    ctimeMs: stat.ctimeMs,
    xattrs: digest(recursiveXattrs(installedApp)),
  };
};

const findNamed = (root, name) => {
  const found = [];
  const visit = (current) => {
    for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
      const child = path.join(current, entry.name);
      if (entry.name === name) found.push(child);
      if (entry.isDirectory() && !entry.isSymbolicLink()) visit(child);
    }
  };
  visit(root);
  return found;
};

const assertSafeBundleXattrs = (appPath, label) => {
  const output = recursiveXattrs(appPath);
  for (const attribute of unsafeArtifactAttributes) {
    assert.doesNotMatch(
      output,
      new RegExp(`${attribute.replaceAll(".", "\\.")}:`),
      `${label} retained codesign-incompatible ${attribute}`,
    );
  }
  return output;
};

const frontendVersion = fs
  .readFileSync(
    path.join(repoRoot, "src", "main", "frontend", "version.cljs"),
    "utf8",
  )
  .match(/defonce version "([^"]+)"/)?.[1];
const resourcePackage = JSON.parse(
  fs.readFileSync(path.join(repoRoot, "resources", "package.json"), "utf8"),
);
assert.equal(frontendVersion, expectedVersion);
assert.equal(resourcePackage.version, expectedVersion);
assert.equal(
  fs.lstatSync(sourceNodeModules).isSymbolicLink(),
  false,
  "the contract requires an isolated static/node_modules tree",
);
assert.ok(fs.statSync(sourceElectronApp).isDirectory());
assert.ok(fs.statSync(sourceHelper).isFile());

const unsignedConfig = fs.readFileSync(
  path.join(repoRoot, "resources", "electron-builder.unsigned.yml"),
  "utf8",
);
const afterSignReference = unsignedConfig.match(
  /^afterSign:\s*(\S+)\s*$/m,
)?.[1];
assert.ok(afterSignReference, "unsigned build lost its afterSign hook");
const afterSignSource = fs.readFileSync(
  path.resolve(
    path.join(repoRoot, "resources"),
    afterSignReference,
  ),
  "utf8",
);
assert.match(afterSignSource, /["']--sign["']\s*,\s*["']-["']/);
assert.match(afterSignSource, /["']--deep["']/);
assert.match(afterSignSource, /["']--verify["']/);
assert.match(afterSignSource, /["']--strict["']/);

run(process.execPath, [
  path.join(repoRoot, "scripts", "test-no-local-trust-mutation-contract.mjs"),
]);

const sourceFingerprintBefore = digest(recursiveXattrs(sourceElectronApp));
const installedAppBefore = snapshotExternalApp();
const temporaryRoot = fs.mkdtempSync(
  path.join(os.tmpdir(), "logseq-appout-xattr-contract-"),
);
const shimRoot = path.join(temporaryRoot, "bin");
const tracePath = path.join(temporaryRoot, "commands.jsonl");
fs.mkdirSync(shimRoot);
fs.writeFileSync(tracePath, "");
for (const command of ["codesign", "security", "spctl", "sudo", "xattr"]) {
  const target = path.join(shimRoot, command);
  fs.copyFileSync(fixtureShim, target);
  fs.chmodSync(target, 0o755);
}

let buildResult;
fs.rmSync(outputRoot, { recursive: true, force: true });
const env = {
  ...process.env,
  PATH: `${shimRoot}${path.delimiter}${process.env.PATH}`,
  LOGSEQ_APP_OUT_XATTR_TRACE: tracePath,
  LOGSEQ_APP_OUT_XATTR_OUTPUT_ROOT: outputRoot,
  LOGSEQ_APP_OUT_XATTR_SOURCE_NODE_MODULES: sourceNodeModules,
};
const architecture = process.arch === "arm64" ? "arm64" : "x64";
const appOutHelper = path.join(
  outputRoot,
  `mac-${architecture}`,
  "Logseq.app",
  "Contents",
  "Frameworks",
  "Logseq Helper.app",
  "Contents",
  "MacOS",
  "Logseq Helper",
);
buildResult = await runReleaseWithInjectedAppOut({
  env,
  appOutHelper,
  tracePath,
});
assert.ifError(buildResult.injectionError);
assert.equal(
  buildResult.injected,
  true,
  "contract never observed the temporary appOut helper before signing",
);
assert.equal(
  digest(recursiveXattrs(sourceElectronApp)),
  sourceFingerprintBefore,
  "unsigned packaging mutated xattrs in source node_modules",
);

if (buildResult.status !== 0) {
  const failureOutput =
    `real pnpm release-electron:unsigned failed with injected appOut detritus:\n` +
    `${buildResult.stdout}\n${buildResult.stderr}`;
  fs.rmSync(temporaryRoot, { recursive: true, force: true });
  assert.fail(failureOutput);
}

assert.deepEqual(
  snapshotExternalApp(),
  installedAppBefore,
  "unsigned packaging touched /Applications/Logseq.app",
);

const trace = fs
  .readFileSync(tracePath, "utf8")
  .trim()
  .split(/\r?\n/)
  .filter(Boolean)
  .map((line) => JSON.parse(line));
assert.equal(
  trace.some((event) =>
    ["security", "spctl", "sudo"].includes(event.command),
  ),
  false,
  "unsigned packaging attempted to mutate local trust or installation state",
);
for (const event of trace.filter((candidate) => candidate.command === "xattr")) {
  assert.equal(
    event.args.includes("com.apple.quarantine"),
    false,
    "unsigned packaging attempted to delete quarantine",
  );
}

const deepSignIndex = trace.findIndex(
  (event) =>
    event.command === "codesign" &&
    event.args.includes("--force") &&
    event.args.includes("--deep") &&
    event.args.includes("--options") &&
    event.args.includes("runtime") &&
    event.args.includes("--sign") &&
    event.args.includes("-") &&
    event.args.some((arg) => arg.endsWith(".app")),
);
const injectionIndex = trace.findIndex(
  (event) => event.command === "contract-inject-xattrs",
);
const strictVerifyIndex = trace.findIndex(
  (event) =>
    event.command === "codesign" &&
    event.args.includes("--verify") &&
    event.args.includes("--deep") &&
    event.args.includes("--strict") &&
    event.args.some((arg) => arg.endsWith(".app")),
);
assert.ok(deepSignIndex >= 0, "afterSign did not perform a deep ad-hoc sign");
assert.ok(
  strictVerifyIndex > deepSignIndex,
  "afterSign did not strictly verify the deep ad-hoc signature",
);
assert.ok(
  injectionIndex >= 0 && injectionIndex < deepSignIndex,
  "appOut detritus was not injected before the deep ad-hoc signature",
);

const appCandidates = findNamed(outputRoot, "Logseq.app");
assert.equal(
  appCandidates.length,
  1,
  `expected one packaged Logseq.app, found ${appCandidates.join(", ")}`,
);
const appPath = appCandidates[0];
assertSafeBundleXattrs(appPath, "temporary appOut");
const packagedQuarantine = xattr([
  "-p",
  "com.apple.quarantine",
  appOutHelper,
]).stdout;
assert.match(
  packagedQuarantine,
  /01234567-89AB-CDEF-0123-456789ABCDEF/,
  "temporary appOut deleted or replaced the injected quarantine identity",
);

run("/usr/bin/codesign", [
  "--verify",
  "--deep",
  "--strict",
  "--verbose=4",
  appPath,
]);

const plistVersion = run(
  "/usr/libexec/PlistBuddy",
  [
    "-c",
    "Print :CFBundleShortVersionString",
    path.join(appPath, "Contents", "Info.plist"),
  ],
).stdout.trim();
assert.equal(plistVersion, expectedVersion);

const dmgPath = path.join(
  outputRoot,
  `Logseq-darwin-${architecture}-${expectedVersion}.dmg`,
);
const zipPath = path.join(
  outputRoot,
  `Logseq-darwin-${architecture}-${expectedVersion}.zip`,
);
for (const artifactPath of [dmgPath, zipPath]) {
  assert.ok(fs.statSync(artifactPath).size > 1_000_000, `${artifactPath} is empty`);
}
run("/usr/bin/hdiutil", ["verify", dmgPath]);

const zipExtractRoot = path.join(temporaryRoot, "zip");
fs.mkdirSync(zipExtractRoot);
run("/usr/bin/ditto", ["-x", "-k", zipPath, zipExtractRoot]);
const zipApps = findNamed(zipExtractRoot, "Logseq.app");
assert.equal(zipApps.length, 1, "ZIP did not contain exactly one Logseq.app");
assertSafeBundleXattrs(zipApps[0], "ZIP application");
run("/usr/bin/codesign", [
  "--verify",
  "--deep",
  "--strict",
  "--verbose=4",
  zipApps[0],
]);

fs.rmSync(temporaryRoot, { recursive: true, force: true });
console.log(
  `Electron appOut xattr contract: PASS ${expectedVersion} ${architecture} App/DMG/ZIP`,
);
