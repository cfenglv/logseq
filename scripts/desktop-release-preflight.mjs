#!/usr/bin/env node

import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);

const args = new Set(process.argv.slice(2));
const strict = args.has("--strict");
const failures = [];
const warnings = [];

const fail = (message) => failures.push(message);
const warn = (message) => warnings.push(message);
const readText = (relativePath) =>
  fs.readFileSync(path.join(repoRoot, relativePath), "utf8");
const readJson = (relativePath) => JSON.parse(readText(relativePath));

const commandOutput = (command, commandArgs) => {
  const result = spawnSync(command, commandArgs, {
    cwd: repoRoot,
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
  });
  if (result.error || result.status !== 0) {
    fail(
      `${command} ${commandArgs.join(" ")} failed: ${
        result.stderr?.trim() || result.error?.message || `exit ${result.status}`
      }`,
    );
    return "";
  }
  return `${result.stdout || ""}${result.stderr || ""}`.trim();
};

const assertFile = (relativePath) => {
  if (!fs.existsSync(path.join(repoRoot, relativePath))) {
    fail(`missing required release file: ${relativePath}`);
  }
};

const assertContains = (text, needle, label) => {
  if (!text.includes(needle)) {
    fail(`${label} is missing ${needle}`);
  }
};

const assertNotContains = (text, needle, label) => {
  if (text.includes(needle)) {
    fail(`${label} must not contain ${needle}`);
  }
};

const semverPattern =
  /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/;

const rootPackage = readJson("package.json");
const desktopPackage = readJson("resources/package.json");
const dbSyncPackage = readJson("deps/db-sync/package.json");
const versionSource = readText("src/main/frontend/version.cljs");
const versionMatch = versionSource.match(/\(defonce version "([^"]+)"\)/);
const version = versionMatch?.[1];
const expectedPnpm = rootPackage.packageManager?.replace(/^pnpm@/, "");

if (!version) {
  fail("src/main/frontend/version.cljs does not define a release version");
} else if (!semverPattern.test(version)) {
  fail(`invalid SemVer release version: ${version}`);
}

if (desktopPackage.version !== version) {
  fail(
    `resources/package.json version ${desktopPackage.version} does not match ${version}`,
  );
}

for (const [label, packageManager] of [
  ["resources/package.json", desktopPackage.packageManager],
  ["deps/db-sync/package.json", dbSyncPackage.packageManager],
]) {
  if (packageManager !== rootPackage.packageManager) {
    fail(
      `${label} packageManager ${packageManager} does not match ${rootPackage.packageManager}`,
    );
  }
}

if (version) {
  assertFile(`docs/releases/${version}.md`);
  const readme = readText("README.md");
  assertContains(
    readme,
    `docs/releases/${version}.md`,
    "README current release link",
  );
}

for (const relativePath of [
  "pnpm-lock.yaml",
  "resources/pnpm-lock.yaml",
  "resources/electron-builder.yml",
  "resources/electron-builder.unsigned.yml",
  "resources/electron-builder-unsigned.mjs",
  "resources/electron-builder-adhoc-after-sign.cjs",
  "resources/entitlements.local-signed.plist",
  "resources/verify-packaged-desktop.mjs",
  "scripts/verify-desktop-release-assets.mjs",
  "deps/db-sync/pnpm-lock.yaml",
  "deps/db-sync/pnpm-workspace.yaml",
  ".github/workflows/build-desktop-release.yml",
]) {
  assertFile(relativePath);
}

const requiredDesktopBuildDependencies = [
  "@zvec/zvec",
  "electron",
  "electron-winstaller",
  "keytar",
];
for (const dependency of requiredDesktopBuildDependencies) {
  if (!desktopPackage.pnpm?.onlyBuiltDependencies?.includes(dependency)) {
    fail(
      `resources/package.json must allow the ${dependency} install/build script`,
    );
  }
}

if (
  desktopPackage.scripts?.["electron:verify-package"] !==
  "node ./verify-packaged-desktop.mjs"
) {
  fail(
    "resources/package.json must expose electron:verify-package from the standalone static package",
  );
}
if (desktopPackage.devDependencies?.["@electron/asar"] !== "3.4.1") {
  fail(
    "resources/package.json must declare @electron/asar directly for packaged-app verification",
  );
}

const workflow = readText(".github/workflows/build-desktop-release.yml");
for (const needle of [
  "push:",
  "source-preflight:",
  "release-rehearsal-gate:",
  "pnpm desktop:release-preflight:quick -- --strict",
  "Verify successful push rehearsal",
  "Verify packaged desktop",
  "node scripts/verify-desktop-release-assets.mjs",
  "softprops/action-gh-release@v2",
  "permissions:\n  contents: read",
  "contents: write",
  "pnpm install --frozen-lockfile --ignore-workspace",
  "electron:make-unsigned --mac dmg zip --x64",
  "electron:make-unsigned --mac dmg zip --arm64",
  "codesign --verify --deep --strict",
]) {
  assertContains(workflow, needle, "desktop release workflow");
}

for (const jobName of [
  "build-linux-x64",
  "build-linux-arm64",
  "build-windows-x64",
  "build-windows-arm64",
  "build-macos-x64",
  "build-macos-arm64",
]) {
  assertContains(workflow, `${jobName}:`, "desktop release workflow");
}
const packagedVerificationSteps =
  workflow.match(/- name: Verify packaged desktop/g)?.length || 0;
if (packagedVerificationSteps !== 6) {
  fail(
    `desktop release workflow must verify all six packaged desktops; found ${packagedVerificationSteps} verifier steps`,
  );
}

for (const forbidden of [
  "sha256sum *.apk",
  "softprops/action-gh-release@v1",
  "APPLE_CERTIFICATES_P12: ${{ secrets.APPLE_CERTIFICATES_P12 }}\n    steps:",
]) {
  assertNotContains(workflow, forbidden, "desktop release workflow");
}

const actualNodeMajor = Number(process.versions.node.split(".")[0]);
if (actualNodeMajor !== 24) {
  const message = `Node ${process.versions.node} does not match CI major 24`;
  strict ? fail(message) : warn(message);
}

const actualPnpm = commandOutput("pnpm", ["--version"]);
if (actualPnpm && actualPnpm !== expectedPnpm) {
  const message = `pnpm ${actualPnpm} does not match pinned ${expectedPnpm}`;
  strict ? fail(message) : warn(message);
}

const javaVersion = commandOutput("java", ["-version"]);
const javaMajorMatch = javaVersion.match(/version "(?:1\.)?(\d+)/);
const javaMajor = Number(javaMajorMatch?.[1]);
if (!javaMajor || javaMajor < 21) {
  fail(`Java 21 or newer is required; detected ${javaVersion || "nothing"}`);
}

const clojureDescription = commandOutput("clojure", ["-Sdescribe"]);
if (!clojureDescription.includes(":version")) {
  fail("Clojure CLI is unavailable or did not return -Sdescribe metadata");
}

const trackedChanges = commandOutput("git", [
  "status",
  "--porcelain",
  "--untracked-files=no",
]);
if (trackedChanges) {
  const message = `tracked worktree changes must be committed before release:\n${trackedChanges}`;
  strict ? fail(message) : warn(message);
}

for (const message of warnings) {
  console.warn(`[desktop-release-preflight] WARNING: ${message}`);
}

if (failures.length > 0) {
  for (const message of failures) {
    console.error(`[desktop-release-preflight] ERROR: ${message}`);
  }
  console.error(
    `[desktop-release-preflight] FAILED with ${failures.length} error(s)`,
  );
  process.exit(1);
}

console.log(
  `[desktop-release-preflight] OK version=${version} node=${process.versions.node} pnpm=${actualPnpm} java=${javaMajor}`,
);
