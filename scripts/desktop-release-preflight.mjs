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
  "resources/desktop-runtime-provenance.mjs",
  "resources/selfhost-updater-version.mjs",
  "resources/project-updater-signature.mjs",
  "resources/packaged-resource-contract.mjs",
  "resources/updater/project-signing-policy.json",
  "resources/updater/legacy-macos/latest-arm64-mac.yml",
  "resources/updater/legacy-macos/latest-x64-mac.yml",
  "resources/verify-packaged-desktop.mjs",
  "resources/verify-updater-provider.mjs",
  "scripts/fixtures/macos-updater-baseline.json",
  "scripts/fixtures/run-project-update-signer-test-only.mjs",
  "scripts/reproduce-macos-updater-signature-regression.mjs",
  "scripts/run-macos-updater-signature-policy.mjs",
  "scripts/build-project-update-helper.mjs",
  "scripts/run-project-signed-macos-update.mjs",
  "scripts/project-update-keychain.mjs",
  "scripts/project-update-private-key.mjs",
  "scripts/project-update-github-actions.mjs",
  "scripts/selfhost-release-provenance.mjs",
  "scripts/project-update-signer-core.mjs",
  "scripts/sign-macos-project-update.mjs",
  "scripts/finalize-macos-project-update-core.mjs",
  "scripts/finalize-local-macos-project-update.mjs",
  "scripts/finalize-github-macos-project-update.mjs",
  "scripts/test-project-update-helper-e2e.mjs",
  "scripts/verify-unsigned-macos-project-update-candidate.mjs",
  "scripts/verify-project-signed-macos-update.mjs",
  "scripts/verify-finalized-selfhost-release.mjs",
  "scripts/verify-desktop-archive-source-revision.mjs",
  "scripts/test-desktop-sidecar-release-contract.mjs",
  "scripts/test-desktop-runtime-packaging-contract.mjs",
  "scripts/test-desktop-release-source-binding-contract.mjs",
  "scripts/test-desktop-preflight-preload-contract.mjs",
  "scripts/test-project-signing-policy-contract.mjs",
  "scripts/test-local-project-update-signing-contract.mjs",
  "scripts/test-project-signed-macos-updater.mjs",
  "scripts/test-shipit-process-outcome-contract.mjs",
  "scripts/test-updater-private-material-policy-contract.mjs",
  "scripts/test-local-keychain-release-signing-contract.mjs",
  "scripts/test-github-project-update-signing-provider.mjs",
  "scripts/test-protected-selfhost-release-workflow.mjs",
  "scripts/test-selfhost-optional-android-release-contract.mjs",
  "scripts/test-selfhost-macos-updater-release-contract.mjs",
  "scripts/test-selfhost-updater-runtime-dependency-boundary.mjs",
  "scripts/test-desktop-release-assets-nightly-metadata-contract.mjs",
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
const requiredDesktopReleaseContracts = [
  "node ./scripts/test-desktop-runtime-packaging-contract.mjs",
  "node ./scripts/test-desktop-release-source-binding-contract.mjs",
  "node ./scripts/test-updater-private-material-policy-contract.mjs",
  "node ./scripts/test-local-keychain-release-signing-contract.mjs",
  "node ./scripts/test-github-project-update-signing-provider.mjs",
  "node ./scripts/test-protected-selfhost-release-workflow.mjs",
  "node ./scripts/test-selfhost-optional-android-release-contract.mjs",
  "node ./scripts/test-desktop-release-contract-gate-drift.mjs",
  "node ./scripts/test-shipit-process-outcome-contract.mjs",
  "node ./scripts/test-project-signed-macos-updater.mjs --physical-shipit-contract",
  "node ./scripts/test-desktop-preflight-preload-contract.mjs",
  "node ./scripts/test-project-signed-macos-updater.mjs --isolated-signer-algorithm-contract",
  "node ./scripts/test-project-signed-macos-updater.mjs --managed-signer-native-key-alignment-contract",
  "node ./scripts/test-project-signing-policy-contract.mjs",
  "node ./scripts/test-local-project-update-signing-contract.mjs",
  "node ./scripts/test-desktop-sidecar-release-contract.mjs",
  "node ./scripts/test-updater-install-entry-contract.mjs",
  "node ./scripts/test-selfhost-macos-user-guidance.mjs",
  "node ./scripts/test-macos-updater-signature-config.mjs",
  "node ./scripts/test-selfhost-nightly-semver-contract.mjs",
  "node ./scripts/test-packaged-project-signature-runtime.mjs",
];
const desktopReleaseContractGate =
  rootPackage.scripts?.["desktop:test-release-contracts"];
const desktopReleaseContractCommands =
  desktopReleaseContractGate?.split(" && ") ?? [];
const desktopReleaseContractPattern =
  /^node \.\/(scripts\/test-[a-z0-9-]+\.mjs)(?: --[a-z0-9-]+)*$/;
const desktopReleaseContractMatches = desktopReleaseContractCommands.map(
  (command) => command.match(desktopReleaseContractPattern),
);

if (
  !desktopReleaseContractGate ||
  desktopReleaseContractCommands.join(" && ") !== desktopReleaseContractGate ||
  desktopReleaseContractMatches.some((match) => match === null)
) {
  fail("package.json desktop release contract gate is malformed");
} else {
  if (
    new Set(desktopReleaseContractCommands).size !==
    desktopReleaseContractCommands.length
  ) {
    fail("package.json desktop release contract gate contains duplicates");
  }

  for (const match of desktopReleaseContractMatches) {
    assertFile(match[1]);
  }

  for (const requiredContract of requiredDesktopReleaseContracts) {
    const requiredCount = desktopReleaseContractCommands.filter(
      (command) => command === requiredContract,
    ).length;
    if (requiredCount === 0) {
      fail(
        `package.json desktop release contract gate is missing ${requiredContract}`,
      );
    } else if (requiredCount !== 1) {
      fail(
        `package.json desktop release contract gate must include exactly once: ${requiredContract}`,
      );
    }
  }
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
  rootPackage.scripts?.["project-update:test-local-signing-contract"] !==
  "node ./scripts/test-local-project-update-signing-contract.mjs"
) {
  fail("package.json must expose the local project signing contract");
}
if (
  rootPackage.scripts?.["project-update:test-github-signing-provider"] !==
  "node ./scripts/test-github-project-update-signing-provider.mjs"
) {
  fail("package.json must expose the GitHub signing provider contract");
}
if (
  rootPackage.scripts?.["project-update:test-protected-release-workflow"] !==
  "node ./scripts/test-protected-selfhost-release-workflow.mjs"
) {
  fail("package.json must expose the protected selfhost release workflow contract");
}
if (
  rootPackage.scripts?.[
    "project-update:finalize-local-macos-candidates"
  ] !== "node ./scripts/finalize-local-macos-project-update.mjs"
) {
  fail("package.json must expose the local project update finalizer");
}
if (
  rootPackage.scripts?.[
    "project-update:finalize-github-macos-candidates"
  ] !== "node ./scripts/finalize-github-macos-project-update.mjs"
) {
  fail("package.json must expose the protected GitHub project update finalizer");
}
if (
  rootPackage.scripts?.[
    "project-update:verify-finalized-selfhost-release"
  ] !== "node ./scripts/verify-finalized-selfhost-release.mjs"
) {
  fail("package.json must expose the finalized selfhost release verifier");
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
  "name: Run RTC E2E part 1",
  "timeout 30m node scripts/run-rtc-e2e.mjs rtc-extra-test",
  "name: Run RTC E2E part 2",
  "if: ${{ !cancelled() }}",
  "timeout 30m node scripts/run-rtc-e2e.mjs rtc-extra-part2-test",
]) {
  assertContains(rtcE2eWorkflow, needle, "RTC E2E workflow");
}
for (const forbidden of ["matrix.test-task", "continue-on-error:"]) {
  assertNotContains(rtcE2eWorkflow, forbidden, "RTC E2E workflow");
}
const rtcE2eCommands = [
  ...rtcE2eWorkflow.matchAll(
    /^\s+run: timeout 30m node scripts\/run-rtc-e2e\.mjs (rtc-extra(?:-part2)?-test)\s*$/gm,
  ),
].map((match) => match[1]);
if (
  JSON.stringify(rtcE2eCommands) !==
  JSON.stringify(["rtc-extra-test", "rtc-extra-part2-test"])
) {
  fail(
    `RTC E2E workflow must run both shards exactly once in local-gate order, got ${JSON.stringify(rtcE2eCommands)}`,
  );
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
const workflowJobSource = (jobName) => {
  const marker = `  ${jobName}:\n`;
  const start = workflow.indexOf(marker);
  if (start === -1) {
    fail(`desktop release workflow is missing job ${jobName}`);
    return "";
  }
  const remainder = workflow.slice(start + marker.length);
  const nextJob = remainder.search(/^  [A-Za-z0-9_-]+:\s*$/m);
  return nextJob === -1 ? remainder : remainder.slice(0, nextJob);
};
const workflowStepSource = (jobSource, stepName) => {
  const marker = `      - name: ${stepName}\n`;
  const start = jobSource.indexOf(marker);
  if (start === -1) {
    fail(`desktop release workflow job is missing step ${stepName}`);
    return "";
  }
  const remainder = jobSource.slice(start + marker.length);
  const nextStep = remainder.search(/^      - name:\s/m);
  return nextStep === -1 ? remainder : remainder.slice(0, nextStep);
};
const expectedJavaMajor = Number(workflowVersion("JAVA_VERSION"));
const expectedClojure = workflowVersion("CLOJURE_VERSION");
const expectedBabashka = workflowVersion("BABASHKA_VERSION");
for (const needle of [
  "push:",
  "resolve-release-source:",
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
  "source-ref: ${{ needs.resolve-release-source.outputs.source-sha }}",
  "ref: ${{ needs.release-rehearsal-gate.outputs.source-sha }}",
  "LOGSEQ_RELEASE_SOURCE_SHA: ${{ needs.compile-cljs.outputs.source-sha }}",
  "needs: [ rtc-release-gate, rtc-browser-e2e ]",
]) {
  assertContains(workflow, needle, "desktop release workflow");
}

const desktopBuilderJobs = [
  "build-linux-x64",
  "build-linux-arm64",
  "build-windows-x64",
  "build-windows-arm64",
  "build-macos-x64",
  "build-macos-arm64",
];
for (const jobName of desktopBuilderJobs) {
  assertContains(workflow, `${jobName}:`, "desktop release workflow");
  const job = workflowJobSource(jobName);
  const download = workflowStepSource(job, "Download The Static Asset");
  const verification = workflowStepSource(
    job,
    "Verify desktop runtime verification inputs",
  );
  if (
    !/uses:\s*actions\/download-artifact@v4/.test(download) ||
    !/name:\s*static\s*$/m.test(download) ||
    !/path:\s*(?:\.|\$\{\{\s*github\.workspace\s*\}\})\s*$/m.test(download)
  ) {
    fail(`${jobName} must restore the multi-root static artifact at the job root`);
  }
  for (const binding of [
    "LOGSEQ_REVISION: ${{ needs.compile-cljs.outputs.source-sha }}",
    "LOGSEQ_RELEASE_SOURCE_SHA: ${{ needs.compile-cljs.outputs.source-sha }}",
  ]) {
    assertContains(job, binding, `${jobName} frozen source binding`);
  }
  for (const input of [
    "static/package.json",
    "static/pnpm-lock.yaml",
    "static/electron-builder.yml",
    "verify-desktop-runtime-revisions.mjs",
    "db-worker-node.js",
  ]) {
    assertContains(verification, input, `${jobName} runtime input check`);
  }
}
if (
  !/name:\s+static[\s\S]{0,160}?path:\s*\|[\s\S]{0,100}?^\s+static\s*$[\s\S]{0,100}?^\s+scripts\/verify-desktop-runtime-revisions\.mjs\s*$[\s\S]{0,100}?^\s+dist\/db-worker-node\.js\s*$/m.test(
    workflow,
  )
) {
  fail(
    "desktop release workflow must publish static and its job-root runtime verification inputs in one artifact",
  );
}
for (const input of [
  "scripts/verify-desktop-runtime-revisions.mjs",
  "dist/db-worker-node.js",
  "if-no-files-found: error",
]) {
  assertContains(
    workflow,
    input,
    "desktop runtime verification input artifact",
  );
}
const snapJob = workflowJobSource("publish-linux-snap");
const snapDownload = workflowStepSource(snapJob, "Download The Static Asset");
const snapVerification = workflowStepSource(
  snapJob,
  "Verify desktop runtime verification inputs",
);
const snapPublish = workflowStepSource(snapJob, "Publish Snap");
for (const roleRequirement of [
  "needs: [ release, compile-cljs ]",
  "github.event.inputs.publish-linux-stores == 'true'",
  "pnpm electron:publish-snap",
]) {
  assertContains(snapJob, roleRequirement, "Snap publisher role");
}
if (
  !/uses:\s*actions\/download-artifact@v4/.test(snapDownload) ||
  !/name:\s*static\s*$/m.test(snapDownload) ||
  !/path:\s*(?:\.|\$\{\{\s*github\.workspace\s*\}\})\s*$/m.test(
    snapDownload,
  )
) {
  fail("Snap publisher must restore the multi-root static artifact at the job root");
}
for (const binding of [
  "LOGSEQ_REVISION: ${{ needs.compile-cljs.outputs.source-sha }}",
  "LOGSEQ_RELEASE_SOURCE_SHA: ${{ needs.compile-cljs.outputs.source-sha }}",
]) {
  assertContains(snapJob, binding, "Snap publisher frozen source binding");
}
for (const input of [
  "static/package.json",
  "static/pnpm-lock.yaml",
  "static/electron-builder.yml",
  "scripts/verify-desktop-runtime-revisions.mjs",
  "dist/db-worker-node.js",
]) {
  assertContains(snapVerification, input, "Snap publisher runtime input check");
}
assertContains(snapPublish, "working-directory: ./static", "Snap publisher");
const snapDownloadIndex = snapJob.indexOf(
  "      - name: Download The Static Asset\n",
);
const snapVerificationIndex = snapJob.indexOf(
  "      - name: Verify desktop runtime verification inputs\n",
);
const snapPublishIndex = snapJob.indexOf("      - name: Publish Snap\n");
if (
  snapDownloadIndex > snapVerificationIndex ||
  snapVerificationIndex > snapPublishIndex
) {
  fail("Snap publisher must restore and verify the artifact before packaging");
}

const staticArtifactConsumers = [];
const jobsStart = workflow.indexOf("jobs:\n");
const jobsSource = workflow.slice(jobsStart + "jobs:\n".length);
const jobMarkers = [...jobsSource.matchAll(/^  ([A-Za-z0-9_-]+):\s*$/gm)];
for (let index = 0; index < jobMarkers.length; index += 1) {
  const marker = jobMarkers[index];
  const source = jobsSource.slice(
    marker.index,
    jobMarkers[index + 1]?.index ?? jobsSource.length,
  );
  if (
    /uses:\s*actions\/download-artifact@v4[\s\S]{0,180}?name:\s*static\s*$/m.test(
      source,
    )
  ) {
    staticArtifactConsumers.push(marker[1]);
  }
}
const expectedStaticArtifactConsumers = [
  ...desktopBuilderJobs,
  "publish-linux-snap",
].sort();
if (
  staticArtifactConsumers.sort().join("\n") !==
  expectedStaticArtifactConsumers.join("\n")
) {
  fail(
    `desktop static artifact consumers must be the six builders plus Snap publisher; found ${staticArtifactConsumers.join(", ")}`,
  );
}
if (
  workflow.match(/appBuilderRequire\("7zip-bin"\)\.path7za/g)?.length !== 2 ||
  workflow.match(/LOGSEQ_7ZIP:/g)?.length !== 4 ||
  workflow.match(/LOGSEQ_ELECTRON_APP_FIXTURE:/g)?.length !== 4
) {
  fail(
    "both macOS builders must bind native updater contracts to the locked 7-Zip and Electron.app dependencies",
  );
}
for (const arch of ["x64", "arm64"]) {
  const job = workflowJobSource(`build-macos-${arch}`);
  const materializeStepName = `Materialize locked ${arch} Electron runtime`;
  const materialize = workflowStepSource(job, materializeStepName);
  const resolver = workflowStepSource(
    job,
    `Resolve ${arch} native updater contract tools`,
  );
  for (const requirement of [
    "pnpm exec install-electron",
    'require("electron/package.json").version',
    "node_modules/electron/dist/version",
    "working-directory: ./static",
    "ELECTRON_INSTALL_PLATFORM: darwin",
    `ELECTRON_INSTALL_ARCH: ${arch}`,
  ]) {
    assertContains(materialize, requirement, `${arch} Electron materialization`);
  }
  for (const requirement of [
    'require.resolve("electron/package.json")',
    'test -e "$seven_zip"',
    'test -f "$seven_zip"',
    'chmod u+x "$seven_zip"',
    'test -x "$seven_zip"',
    'test -d "$electron_app"',
    "Native updater contract tool resolution",
    "Locked 7-Zip binary is missing",
    "Locked 7-Zip path is not a regular file",
    "Unable to add the user execute bit with chmod",
  ]) {
    assertContains(resolver, requirement, `${arch} updater tool resolution`);
  }
  assertNotContains(
    resolver,
    "$GITHUB_WORKSPACE/static/node_modules/electron",
    `${arch} updater tool resolution`,
  );
  const materializeIndex = job.indexOf(
    `      - name: ${materializeStepName}\n`,
  );
  const resolveIndex = job.indexOf(
    `      - name: Resolve ${arch} native updater contract tools\n`,
  );
  const probeIndex = job.indexOf(
    `      - name: Probe ${arch} native updater contract tools\n`,
  );
  if (
    materializeIndex === -1 ||
    resolveIndex === -1 ||
    probeIndex === -1 ||
    materializeIndex > resolveIndex ||
    resolveIndex > probeIndex
  ) {
    fail(
      `${arch} Electron runtime must be materialized before the resolver and native probes`,
    );
  }
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
