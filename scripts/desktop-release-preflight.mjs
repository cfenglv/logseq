#!/usr/bin/env node

import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { resolveSelfhostUpdaterVersions } from "../resources/selfhost-updater-version.mjs";

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
if (version?.includes("-selfhost.")) {
  try {
    resolveSelfhostUpdaterVersions(version);
  } catch (error) {
    fail(error instanceof Error ? error.message : String(error));
  }
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
  "resources/electron-builder-verify-runtime-revisions.cjs",
  "resources/electron-builder.unsigned.yml",
  "resources/electron-builder-unsigned.mjs",
  "resources/electron-builder-adhoc-after-sign.cjs",
  "resources/entitlements.local-signed.plist",
  "resources/packaged-resource-contract.mjs",
  "resources/selfhost-updater-version.mjs",
  "resources/project-updater-signature.mjs",
  "resources/packaged-resource-contract.mjs",
  "resources/updater/project-signing-policy.json",
  "resources/updater/legacy-macos/latest-arm64-mac.yml",
  "resources/updater/legacy-macos/latest-x64-mac.yml",
  "resources/verify-packaged-desktop.mjs",
  "resources/verify-updater-provider.mjs",
  "scripts/fixtures/macos-updater-baseline.json",
  "scripts/reproduce-macos-updater-signature-regression.mjs",
  "scripts/run-macos-updater-signature-policy.mjs",
  "scripts/build-project-update-helper.mjs",
  "scripts/run-project-signed-macos-update.mjs",
  "scripts/sign-macos-project-update.mjs",
  "scripts/test-project-update-helper-e2e.mjs",
  "scripts/verify-project-signed-macos-update.mjs",
  "scripts/test-desktop-sidecar-release-contract.mjs",
  "scripts/test-desktop-runtime-packaging-contract.mjs",
  "scripts/test-desktop-preflight-preload-contract.mjs",
  "scripts/test-project-signing-policy-contract.mjs",
  "scripts/test-project-signed-macos-updater.mjs",
  "scripts/test-shipit-process-outcome-contract.mjs",
  "scripts/test-updater-private-material-policy-contract.mjs",
  "scripts/test-selfhost-macos-updater-release-contract.mjs",
  "scripts/test-updater-install-entry-contract.mjs",
  "scripts/test-selfhost-macos-user-guidance.mjs",
  "scripts/test-macos-updater-signature-config.mjs",
  "scripts/test-selfhost-nightly-semver-contract.mjs",
  "scripts/test-packaged-project-signature-runtime.mjs",
  "scripts/require-project-signing-policy.mjs",
  "scripts/verify-macos-updater-signature.mjs",
  "scripts/verify-desktop-release-assets.mjs",
  "src/test/electron/updater_test.cljs",
  "scripts/run-rtc-e2e.mjs",
  "scripts/run-rtc-prepush.mjs",
  "sidecar/embedding_server.py",
  "deps/db-sync/pnpm-lock.yaml",
  "deps/db-sync/pnpm-workspace.yaml",
  ".github/workflows/build-desktop-release.yml",
]) {
  assertFile(relativePath);
}

const relativeModuleClosure = (entryPath, seen = new Set()) => {
  if (seen.has(entryPath)) return seen;
  seen.add(entryPath);
  const source = readText(entryPath);
  for (const match of source.matchAll(
    /(?:from\s+|import\s*\(\s*)["'](\.[^"']+\.(?:c?js|mjs))["']/g,
  )) {
    const dependency = path
      .normalize(path.join(path.dirname(entryPath), match[1]))
      .replaceAll(path.sep, "/");
    relativeModuleClosure(dependency, seen);
  }
  return seen;
};
const packagedVerifierClosure = [
  ...relativeModuleClosure("resources/verify-packaged-desktop.mjs"),
]
  .map((relativePath) => readText(relativePath))
  .join("\n");
assertContains(
  packagedVerifierClosure,
  "project-updater-signature.mjs",
  "packaged desktop verifier import closure",
);

if (
  rootPackage.scripts?.["rtc:prepush"] !==
  "node ./scripts/run-rtc-prepush.mjs"
) {
  fail("package.json must expose the exact rtc:prepush gate");
}
if (
  rootPackage.scripts?.["desktop:test-release-contracts"] !==
  "node ./scripts/test-desktop-runtime-packaging-contract.mjs && node ./scripts/test-updater-private-material-policy-contract.mjs && node ./scripts/test-shipit-process-outcome-contract.mjs && node ./scripts/test-project-signed-macos-updater.mjs --physical-shipit-contract && node ./scripts/test-desktop-preflight-preload-contract.mjs && node ./scripts/test-project-signed-macos-updater.mjs --isolated-signer-algorithm-contract && node ./scripts/test-project-signed-macos-updater.mjs --managed-signer-native-key-alignment-contract && node ./scripts/test-project-signing-policy-contract.mjs && node ./scripts/test-desktop-sidecar-release-contract.mjs && node ./scripts/test-updater-install-entry-contract.mjs && node ./scripts/test-selfhost-macos-user-guidance.mjs && node ./scripts/test-macos-updater-signature-config.mjs && node ./scripts/test-selfhost-nightly-semver-contract.mjs && node ./scripts/test-packaged-project-signature-runtime.mjs"
) {
  fail("package.json must expose the exact desktop release contract gate");
}
if (
  rootPackage.scripts?.["desktop:test-preload-contract"] !==
  "node ./scripts/test-desktop-preflight-preload-contract.mjs"
) {
  fail("package.json must expose the executable desktop preload contract");
}
if (
  rootPackage.scripts?.["project-update:test-signing-policy-contract"] !==
  "node ./scripts/test-project-signing-policy-contract.mjs"
) {
  fail("package.json must expose the exact project signing policy contract");
}
if (
  rootPackage.scripts?.["project-update:test-private-material-policy"] !==
  "node ./scripts/test-updater-private-material-policy-contract.mjs"
) {
  fail("package.json must expose the exact updater private material contract");
}
if (
  rootPackage.scripts?.["project-update:test-signer-algorithm-contract"] !==
  "node ./scripts/test-project-signed-macos-updater.mjs --isolated-signer-algorithm-contract"
) {
  fail("package.json must expose the exact signer algorithm contract");
}
if (
  rootPackage.scripts?.["project-update:test-managed-signer-native-key-alignment"] !==
  "node ./scripts/test-project-signed-macos-updater.mjs --managed-signer-native-key-alignment-contract"
) {
  fail("package.json must expose the managed signer/native key-alignment contract");
}
if (
  rootPackage.scripts?.["project-update:test-physical-shipit-contract"] !==
  "node ./scripts/test-project-signed-macos-updater.mjs --physical-shipit-contract"
) {
  fail("package.json must expose the physical ShipIt replacement contract");
}
if (
  rootPackage.scripts?.["project-update:test-shipit-outcome-contract"] !==
  "node ./scripts/test-shipit-process-outcome-contract.mjs"
) {
  fail("package.json must expose the ShipIt process-outcome contract");
}
if (
  rootPackage.scripts?.["project-update:require-signing-policy"] !==
  "node ./scripts/require-project-signing-policy.mjs"
) {
  fail("package.json must expose the explicit signing-policy release block");
}

const repositoryGuidelines = readText("AGENTS.md");
assertContains(
  repositoryGuidelines,
  "pnpm rtc:prepush",
  "repository push safety gate",
);

const rtcE2eWorkflow = readText(".github/workflows/clj-rtc-e2e.yml");
for (const needle of [
  "workflow_call:",
  "source-ref:",
  "ref: ${{ inputs.source-ref || github.sha }}",
  "Fetch E2E Clojure deps",
  "clojure -Srepro -P -M:test",
  "clojure-e2e-deps-v2-",
]) {
  assertContains(rtcE2eWorkflow, needle, "RTC E2E workflow");
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
if (
  desktopPackage.scripts?.["electron:verify-updater-provider"] !==
  "node ./verify-updater-provider.mjs"
) {
  fail(
    "resources/package.json must expose the updater provider contract rehearsal",
  );
}
if (desktopPackage.devDependencies?.["@electron/asar"] !== "3.4.1") {
  fail(
    "resources/package.json must declare @electron/asar directly for packaged-app verification",
  );
}

const workflow = readText(".github/workflows/build-desktop-release.yml");
const workflowVersion = (name) =>
  workflow.match(new RegExp(`^  ${name}: ['"]?([^'"\\n]+)`, "m"))?.[1];
const expectedJavaMajor = Number(workflowVersion("JAVA_VERSION"));
const expectedClojure = workflowVersion("CLOJURE_VERSION");
const expectedBabashka = workflowVersion("BABASHKA_VERSION");
for (const needle of [
  "push:",
  "source-preflight:",
  "release-rehearsal-gate:",
  "release-assets-preflight:",
  "pnpm desktop:release-preflight:quick -- --strict",
  "pnpm desktop:test-release-contracts",
  "pnpm project-update:require-signing-policy",
  "pnpm project-update:test-helper",
  "Verify successful push rehearsal",
  "Verify packaged desktop",
  "Verify complete desktop release asset set",
  "merge-multiple: true",
  "node scripts/verify-desktop-release-assets.mjs",
  "softprops/action-gh-release@v2",
  "permissions:\n  contents: read",
  "contents: write",
  "pnpm install --frozen-lockfile --ignore-workspace",
  "electron:make-unsigned --mac dmg zip --x64",
  "electron:make-unsigned --mac dmg zip --arm64",
  "codesign --verify --deep --strict",
  "uses: ./.github/workflows/clj-rtc-e2e.yml",
  "source-ref: ${{ github.event.inputs.git-ref || github.sha }}",
  "needs: [ rtc-release-gate, rtc-browser-e2e ]",
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
if (
  workflow.match(/test -f (?:\.\/)?static\/sidecar\/embedding_server\.py/g)?.length !== 3 ||
  workflow.match(/--output static\/sidecar\/logseq-project-updater/g)?.length !== 2
) {
  fail(
    "the compile gate and both macOS jobs must require the staged embedding sidecar before packaging",
  );
}
if (
  workflow.match(/Run (?:x64|arm64) native project updater helper E2E/g)?.length !== 2 ||
  workflow.match(/Verify (?:x64|arm64) updater provider contract/g)?.length !== 2
) {
  fail(
    "both real macOS architecture jobs must run the native helper E2E and updater provider contract",
  );
}
assertNotContains(
  workflow,
  "cp -R release-gate-source/sidecar/. static/sidecar/",
  "desktop release workflow",
);

const desktopBuilder = readText("resources/electron-builder.yml");
assertContains(desktopBuilder, "- from: sidecar\n    to: sidecar", "desktop sidecar packaging");
assertNotContains(desktopBuilder, "- from: ../sidecar", "desktop sidecar packaging");
assertContains(
  desktopBuilder,
  "beforePack: ./electron-builder-verify-runtime-revisions.cjs",
  "desktop runtime revision gate",
);
const desktopRuntimePreparation = readText("scripts/prepare-desktop-runtime-js.mjs");
for (const needle of ['"sidecar", "embedding_server.py"', '"static"', '"sidecar"']) {
  assertContains(desktopRuntimePreparation, needle, "desktop sidecar staging");
}
const packagedDesktopVerifier = readText("resources/verify-packaged-desktop.mjs");
const packagedResourceContract = readText("resources/packaged-resource-contract.mjs");
for (const needle of [
  "embedding_server.py",
  "logseq-project-updater",
  "./packaged-resource-contract.mjs",
  "verifyProjectSignatureRuntime",
  "project-signing-policy.json",
]) {
  assertContains(packagedDesktopVerifier, needle, "packaged desktop verifier");
}
const projectSignatureRuntimeVerification =
  packagedDesktopVerifier.indexOf("verifyProjectSignatureRuntime({");
const darwinOnlyPackagedVerification =
  packagedDesktopVerifier.indexOf('if (expectedPlatform === "darwin")');
if (
  projectSignatureRuntimeVerification === -1 ||
  darwinOnlyPackagedVerification === -1 ||
  projectSignatureRuntimeVerification > darwinOnlyPackagedVerification
) {
  fail(
    "packaged desktop verifier must invoke the shared project signature runtime contract before macOS-only checks",
  );
}
for (const needle of [
  "project-updater-signature.mjs",
  "assertRegularFile",
  "assertMatchesStagedResource",
  "does not match the staged release resource",
]) {
  assertContains(
    packagedResourceContract,
    needle,
    "cross-platform packaged project signature runtime contract",
  );
}
const fullDesktopPreflight = readText("scripts/run-desktop-release-preflight.mjs");
for (const needle of [
  "--test-only",
  "--public-key-base64",
  '"sidecar",',
  '"logseq-project-updater"',
]) {
  assertContains(fullDesktopPreflight, needle, "full desktop preflight");
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
if (!javaMajor || javaMajor !== expectedJavaMajor) {
  const message = `Java ${javaMajor || "unknown"} does not match CI major ${expectedJavaMajor}`;
  strict ? fail(message) : warn(message);
}

const clojureVersionOutput = commandOutput("clojure", ["--version"]);
const actualClojure = clojureVersionOutput.match(/(\d+\.\d+\.\d+\.\d+)/)?.[1];
if (!actualClojure || actualClojure !== expectedClojure) {
  const message = `Clojure CLI ${actualClojure || "unknown"} does not match CI ${expectedClojure}`;
  strict ? fail(message) : warn(message);
}

const babashkaVersionOutput = commandOutput("bb", ["--version"]);
const actualBabashka = babashkaVersionOutput.match(/(\d+\.\d+\.\d+)/)?.[1];
if (!actualBabashka || actualBabashka !== expectedBabashka) {
  const message = `Babashka ${actualBabashka || "unknown"} does not match CI ${expectedBabashka}`;
  strict ? fail(message) : warn(message);
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
