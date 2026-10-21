import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import {
  bridgeImmutableAssetNames,
  PromotionError,
  promoteExistingRelease,
} from "../lib/selfhost6-existing-release-promotion.mjs";
import { readReleasePolicy } from "../lib/selfhost6-release-identity.mjs";
import { traditionalReleaseAssetNames } from "../lib/selfhost6-update-feed.mjs";
import {
  GitHubReleaseApi,
  parseExpectedReleaseId,
} from "./promote-existing-release.mjs";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const workflow = fs.readFileSync(
  path.join(repoRoot, ".github/workflows/build-selfhost6-candidate.yml"),
  "utf8",
);
const legacyWorkflow = fs.readFileSync(
  path.join(repoRoot, ".github/workflows/build-desktop-release.yml"),
  "utf8",
);
const rtcWorkflow = fs.readFileSync(
  path.join(repoRoot, ".github/workflows/clj-rtc-e2e.yml"),
  "utf8",
);
const packagedRuntimeWorkspace = fs.readFileSync(
  path.join(repoRoot, "resources/pnpm-workspace.yaml"),
  "utf8",
);
const platformSwapProbe = fs.readFileSync(
  path.join(repoRoot, "scripts/selfhost6/platform-sqlite-swap.mjs"),
  "utf8",
);
const builtReleaseVerifier = fs.readFileSync(
  path.join(repoRoot, "scripts/selfhost6/verify-built-release.mjs"),
  "utf8",
);
const existingReleasePromotion = fs.readFileSync(
  path.join(repoRoot, "scripts/lib/selfhost6-existing-release-promotion.mjs"),
  "utf8",
);
const existingReleasePromotionCli = fs.readFileSync(
  path.join(repoRoot, "scripts/selfhost6/promote-existing-release.mjs"),
  "utf8",
);
const releasePolicy = readReleasePolicy(
  path.join(repoRoot, "resources/updater/selfhost-release-policy.json"),
);

const releaseLineId = "selfhost-official-architecture-v1";
test("Windows updater packages the full pinned reader for the target architecture", () => {
  const config = fs.readFileSync(path.join(repoRoot, "resources/electron-builder.yml"), "utf8");
  assert.match(config, /from: updater\/windows\/\$\{arch\}/);
  for (const name of ["7za.exe", "7z.dll", "7zip-LICENSE.txt", "7zip-NOTICE.txt"])
    assert.ok(config.includes("- " + name));
  assert.doesNotMatch(config, /from: node_modules\/7zip-bin\/win\//);
  const prepare = fs.readFileSync(path.join(repoRoot, "resources/updater/prepare-target-build-manifest.mjs"), "utf8");
  assert.match(prepare, /if \(platform === "win32"\)[\s\S]*await prepareWindowsArchiveReader\(\{ arch,/);
});

const pointerNames = [
  `${releaseLineId}.yml`,
  `${releaseLineId}-mac.yml`,
  `${releaseLineId}-linux.yml`,
  `${releaseLineId}-linux-arm64.yml`,
];

function digest(bytes) {
  return `sha256:${createHash("sha256").update(bytes).digest("hex")}`;
}

function pointerBytes(version, sourceFullSha, name) {
  return Buffer.from(`${JSON.stringify({
    version,
    files: [{ url: `${name}.asset`, sha512: "fixture", size: 1 }],
    selfhostUpdateSignatures: {
      arm64: {
        "target-version": version,
        "target-source-full-sha": sourceFullSha,
      },
    },
  })}\n`);
}

function promotionFixture(sourceFullSha = "b".repeat(40)) {
  const version = "2.0.1-selfhost.7";
  const immutableFiles = new Map(bridgeImmutableAssetNames(version).map((name) => [
    name,
    Buffer.from(`archive:${name}`),
  ]));
  return {
    releaseLineId,
    expectedSourceFullSha: sourceFullSha,
    expectedVersion: version,
    expectedReleaseIdentity: {
      releaseId: 71,
      releaseTargetFullSha: "c".repeat(40),
      tagObjectFullSha: "c".repeat(40),
      tagPeeledCommitFullSha: "c".repeat(40),
    },
    immutableFiles,
    pointerFiles: new Map(pointerNames.map((name) => [
      name,
      pointerBytes(version, sourceFullSha, name),
    ])),
  };
}

class FakeReleaseApi {
  constructor(seed = new Map()) {
    this.release = {
      id: 71,
      tagName: releaseLineId,
      targetCommitish: "c".repeat(40),
    };
    this.tagIdentity = {
      objectSha: "c".repeat(40),
      peeledCommitSha: "c".repeat(40),
    };
    this.assets = new Map();
    this.nextId = 1;
    this.mutations = [];
    this.hook = null;
    for (const [name, bytes] of seed) this.setAsset(name, bytes);
  }

  setAsset(name, bytes) {
    this.assets.set(name, { id: this.nextId++, name, bytes: Buffer.from(bytes) });
  }

  async getReleaseByTag() {
    return { ...this.release };
  }

  async getTagIdentity() {
    return { ...this.tagIdentity };
  }

  async listAssets() {
    return [...this.assets.values()].map(({ id, name, bytes }) => ({
      id,
      name,
      size: bytes.length,
      digest: digest(bytes),
    }));
  }

  async downloadAsset(assetId) {
    const asset = [...this.assets.values()].find(({ id }) => id === assetId);
    assert.ok(asset, `missing fake asset id ${assetId}`);
    return Buffer.from(asset.bytes);
  }

  async uploadAsset(_release, name, bytes) {
    await this.hook?.({ phase: "before-upload", name, bytes, api: this });
    assert.equal(this.assets.has(name), false, `duplicate fake asset ${name}`);
    this.setAsset(name, bytes);
    this.mutations.push({ operation: "upload", name });
    await this.hook?.({ phase: "after-upload", name, bytes, api: this });
  }

  async deleteAsset(assetId) {
    const asset = [...this.assets.values()].find(({ id }) => id === assetId);
    assert.ok(asset, `missing fake asset id ${assetId}`);
    await this.hook?.({ phase: "before-delete", name: asset.name, bytes: asset.bytes, api: this });
    this.assets.delete(asset.name);
    this.mutations.push({ operation: "delete", name: asset.name });
    await this.hook?.({ phase: "after-delete", name: asset.name, bytes: asset.bytes, api: this });
  }
}

function oldPointerSeed() {
  return new Map(pointerNames.map((name) => [
    name,
    pointerBytes("2.0.1-selfhost.6", "a".repeat(40), name),
  ]));
}

function assertRemoteBytes(api, expected) {
  for (const [name, bytes] of expected) {
    assert.deepEqual(api.assets.get(name)?.bytes, bytes, name);
  }
}

async function rejectedReceipt(run) {
  try {
    await run();
  } catch (error) {
    assert.ok(error instanceof PromotionError);
    return error.receipt;
  }
  assert.fail("promotion unexpectedly succeeded");
}

test("candidate workflow builds only the frozen desktop matrix and never publishes", () => {
  const targets = [...workflow.matchAll(/^\s+- id: (\S+)$/gm)].map((match) => match[1]);
  assert.deepEqual(targets, [
    "darwin-x64",
    "darwin-arm64",
    "win32-x64",
    "win32-arm64",
    "linux-x64",
    "linux-arm64",
  ]);
  assert.match(workflow, /workflow_dispatch:/);
  assert.match(workflow, /options:\n\s+- 2\.0\.2-selfhost\.1\n\s+- 2\.0\.2-selfhost\.2/);
  assert.match(workflow, /permissions:\n\s+contents: read/);
  assert.match(workflow, /environment: selfhost-release-signing/);
  assert.equal((workflow.match(/LOGSEQ_PROJECT_UPDATE_SIGNING_KEY_PKCS8_BASE64/g) ?? []).length, 2);
  assert.doesNotMatch(workflow, /gh release|--publish always|action-gh-release|contents: write/);
  assert.doesNotMatch(workflow, /promote-update-feed\.mjs|Stage isolated synthetic forward-update channel/);
  assert.doesNotMatch(legacyWorkflow, /Refuse the isolated Selfhost6 release line/);
  assert.doesNotMatch(legacyWorkflow, /Use Build-Selfhost6-Candidate/);
});

test("the mature desktop action stages one complete traditional draft and stops", () => {
  const selfhostReleaseJobs = legacyWorkflow.match(
    /  compile-cljs:[\s\S]*?\n  selfhost-release-bridge:/,
  )[0];
  assert.match(legacyWorkflow, /name: Build-Desktop-Release/);
  assert.equal((legacyWorkflow.match(/^name: Build-Desktop-Release$/gm) ?? []).length, 1);
  assert.match(legacyWorkflow, /SELFHOST6_TARGET_VERSION: '2\.0\.2-selfhost\.1'/);
  assert.match(legacyWorkflow, /selfhost-operation:[\s\S]*stage-draft[\s\S]*bridge-published-\.7/);
  assert.match(selfhostReleaseJobs, /selfhost-operation == 'stage-draft'/);
  assert.match(selfhostReleaseJobs, /github\.ref_name == 'release\/2\.0\.2-selfhost\.1'/);
  const bridgeJob = legacyWorkflow.split('\n  selfhost-release-bridge:')[1];
  assert.match(bridgeJob, /test "\$\{SELFHOST6_TARGET_VERSION\}" = '2\.0\.2-selfhost\.1'/);
  assert.match(bridgeJob, /github\.ref_name == 'release\/2\.0\.2-selfhost\.1'/);
  assert.match(
    legacyWorkflow,
    /run-name: Desktop release · \$\{\{ github\.event\.inputs\.build-target \}\} · \$\{\{ github\.event\.inputs\.selfhost-operation \}\} · \$\{\{ github\.event\.inputs\.git-ref \}\}/,
  );
  assert.doesNotMatch(legacyWorkflow.match(/on:[\s\S]*?\nenv:/)[0], /\n\s+push:/);
  assert.match(legacyWorkflow, /resolve-release-source:/);
  assert.match(legacyWorkflow, /source-preflight:/);
  assert.match(
    legacyWorkflow,
    /Install frozen dependencies[\s\S]*mkdir -p static\n\s+cp resources\/package\.json resources\/pnpm-lock\.yaml static\/[\s\S]*pnpm --dir static install --frozen-lockfile --ignore-workspace[\s\S]*Run the same RTC prepush gate used by local rehearsal\n\s+run: pnpm rtc:prepush/,
  );
  assert.match(legacyWorkflow, /release-source-gate:/);
  assert.doesNotMatch(legacyWorkflow, /release-rehearsal-gate:|No successful Selfhost6 push rehearsal exists/);
  assert.match(legacyWorkflow, /rtc-browser-e2e:/);
  assert.match(
    legacyWorkflow,
    /rtc-browser-e2e:[\s\S]*secrets:[\s\S]*SELFHOST_SYNC_SERVER_URL: \$\{\{ secrets\.SELFHOST_SYNC_SERVER_URL \}\}/,
  );
  assert.match(
    rtcWorkflow,
    /secrets:[\s\S]*SELFHOST_SYNC_SERVER_URL:[\s\S]*required: true[\s\S]*Require the selected RTC target for release qualification[\s\S]*if: \$\{\{ inputs\.source-ref != '' \}\}[\s\S]*test -n "\$LOGSEQ_E2E_SYNC_SERVER_URL"[\s\S]*LOGSEQ_E2E_SYNC_SERVER_URL: \$\{\{ secrets\.SELFHOST_SYNC_SERVER_URL \}\}/,
  );
  assert.doesNotMatch(legacyWorkflow, /workers\.dev/);
  assert.equal(
    (rtcWorkflow.match(/if: \$\{\{ inputs\.source-ref != '' \|\| contains\(github\.event\.head_commit\.message, 'rtc'\) \}\}/g) ?? []).length,
    2,
  );
  assert.doesNotMatch(rtcWorkflow, /github\.event_name == 'workflow_call'/);
  assert.match(
    rtcWorkflow,
    /Collect screenshots\n\s+if: \$\{\{ failure\(\) && inputs\.source-ref == '' \}\}/,
  );
  assert.match(legacyWorkflow, /upstream-compile-cljs:\n\s+name: compile-cljs \(upstream desktop\)/);
  assert.match(selfhostReleaseJobs, /compile-cljs:[\s\S]*Compile the release desktop owners/);
  assert.match(selfhostReleaseJobs, /selfhost-build-platform:/);
  assert.equal((selfhostReleaseJobs.match(/^\s+- id: (darwin-x64|darwin-arm64|win32-x64|win32-arm64|linux-x64|linux-arm64)$/gm) ?? []).length, 6);
  assert.match(selfhostReleaseJobs, /environment: selfhost-release-signing/);
  assert.match(selfhostReleaseJobs, /selfhost-release-verifier:/);
  assert.equal((selfhostReleaseJobs.match(/verify-built-release\.mjs/g) ?? []).length, 2);
  assert.match(selfhostReleaseJobs, /environment: selfhost-production/);
  assert.match(
    selfhostReleaseJobs,
    /concurrency:\n\s+group: selfhost-release-selfhost-official-architecture-v1\n\s+cancel-in-progress: false/,
  );
  assert.match(selfhostReleaseJobs, /github\.event\.inputs\.is-draft == 'true'/);
  assert.match(selfhostReleaseJobs, /Verify Draft before creation or replacement/);
  assert.equal((selfhostReleaseJobs.match(/git\/matching-refs\/tags\/\$\{SELFHOST6_TARGET_VERSION\}/g) ?? []).length, 2);
  assert.equal((selfhostReleaseJobs.match(/releases\?per_page=100/g) ?? []).length, 2);
  assert.equal((selfhostReleaseJobs.match(/--release-list-json/g) ?? []).length, 3);
  assert.match(selfhostReleaseJobs, /--phase create/);
  assert.match(selfhostReleaseJobs, /verify-draft-release\.mjs[\s\S]*--phase before/);
  assert.match(selfhostReleaseJobs, /--delete-list-output/);
  assert.match(selfhostReleaseJobs, /Remove only obsolete assets from the verified Draft/);
  assert.match(selfhostReleaseJobs, /Create the reviewed release as a draft/);
  assert.match(selfhostReleaseJobs, /softprops\/action-gh-release@v2/);
  assert.match(selfhostReleaseJobs, /files:\s*\|/);
  const releaseUpload = selfhostReleaseJobs.match(
    /Create the reviewed release as a draft[\s\S]*?files:\s*\|\n([\s\S]*?)\n\s+tag_name:/,
  )[1];
  const releaseUploadFiles = releaseUpload.trim().split("\n").map((line) => line.trim());
  assert.equal(releaseUploadFiles.length, 35);
  assert.equal(new Set(releaseUploadFiles).size, 35);
  assert.ok(releaseUploadFiles.every((name) => name.startsWith("release-assets/")));
  assert.ok(releaseUploadFiles.every((name) => !name.includes("*")));
  assert.deepEqual(
    releaseUploadFiles.map((name) => name
      .slice("release-assets/".length)
      .replace("${{ env.SELFHOST6_TARGET_VERSION }}", releasePolicy.sourceVersion))
      .sort(),
    traditionalReleaseAssetNames(releasePolicy, releasePolicy.sourceVersion),
  );
  for (const name of ["VERSION", "SOURCE_REVISION", "SHA256SUMS.txt"]) {
    assert.ok(releaseUploadFiles.includes(`release-assets/${name}`));
  }
  assert.match(selfhostReleaseJobs, /tag_name: \$\{\{ env\.SELFHOST6_TARGET_VERSION \}\}/);
  assert.match(selfhostReleaseJobs, /target_commitish: \$\{\{ needs\.selfhost-release-verifier\.outputs\.product-source-sha \}\}/);
  assert.match(selfhostReleaseJobs, /draft: true/);
  assert.match(selfhostReleaseJobs, /prerelease: false/);
  assert.match(selfhostReleaseJobs, /make_latest: false/);
  assert.match(selfhostReleaseJobs, /fail_on_unmatched_files: true/);
  assert.match(selfhostReleaseJobs, /overwrite_files: true/);
  assert.match(selfhostReleaseJobs, /Bind and verify the exact reviewed Draft/);
  assert.match(selfhostReleaseJobs, /SELFHOST_DRAFT_RELEASE_ID: \$\{\{ steps\.publish-draft\.outputs\.id \}\}/);
  assert.match(selfhostReleaseJobs, /--phase binding/);
  assert.match(selfhostReleaseJobs, /verify-draft-release\.mjs[\s\S]*--phase after/);
  assert.match(selfhostReleaseJobs, /-f tag_name="\$\{SELFHOST6_TARGET_VERSION\}"/);
  assert.match(selfhostReleaseJobs, /-f name="Logseq \$\{SELFHOST6_TARGET_VERSION\}"/);
  assert.match(selfhostReleaseJobs, /-f body="Source commit \$\{EXPECTED_SOURCE_FULL_SHA\}\."/);
  for (const name of [
    "SELFHOST_DRAFT_RELEASE_ID",
    "SELFHOST_DRAFT_TARGET_FULL_SHA",
    "SELFHOST_DRAFT_ASSET_INVENTORY_SHA256",
    "SELFHOST_DRAFT_ASSET_INVENTORY_JSON",
  ]) assert.match(selfhostReleaseJobs, new RegExp(`${name}: \\$\\{\\{ secrets\\.${name} \\}\\}`));
  assert.match(
    selfhostReleaseJobs,
    /Verify Draft before creation or replacement[\s\S]*Remove only obsolete assets from the verified Draft[\s\S]*Create the reviewed release as a draft[\s\S]*Bind and verify the exact reviewed Draft/,
  );
  assert.doesNotMatch(selfhostReleaseJobs, /Stage four source-version channel pointers|promote-update-feed\.mjs/);
  assert.doesNotMatch(selfhostReleaseJobs, /Promote immutable assets after the draft is staged|promote-existing-release\.mjs/);
  for (const name of [
    "SELFHOST_EXISTING_RELEASE_ID",
    "SELFHOST_EXISTING_RELEASE_TARGET_FULL_SHA",
    "SELFHOST_EXISTING_TAG_OBJECT_FULL_SHA",
    "SELFHOST_EXISTING_TAG_PEELED_COMMIT_FULL_SHA",
  ]) assert.doesNotMatch(selfhostReleaseJobs, new RegExp(`${name}: \\$\\{\\{ secrets\\.${name} \\}\\}`));
  assert.doesNotMatch(legacyWorkflow, /selfhost-release-terminal-audit:/);
  assert.doesNotMatch(legacyWorkflow, /gh release (create|edit|upload)/);
  assert.doesNotMatch(selfhostReleaseJobs, /Build-Selfhost6-Candidate|32051789643|verify-release-promotion\.mjs/);
  assert.doesNotMatch(selfhostReleaseJobs, /wrangler (deploy|versions deploy)|2\.0\.1-selfhost\.8/);
  assert.match(builtReleaseVerifier, /formal release set must contain exactly thirty-five assets/);
  assert.match(builtReleaseVerifier, /traditionalReleaseAssetNames/);
  for (const channelFile of [
    "selfhost-official-architecture-v1-x64.yml",
    "selfhost-official-architecture-v1-arm64.yml",
    "selfhost-official-architecture-v1-x64-mac.yml",
    "selfhost-official-architecture-v1-arm64-mac.yml",
    "selfhost-official-architecture-v1-linux.yml",
    "selfhost-official-architecture-v1-linux-arm64.yml",
  ]) assert.match(selfhostReleaseJobs, new RegExp(channelFile.replaceAll(".", "\\.")));
  assert.doesNotMatch(builtReleaseVerifier, /endsWith\("\.yml"\)\), false/);
  assert.match(builtReleaseVerifier, /built-assets-verified-awaiting-product-qualification/);
  assert.match(builtReleaseVerifier, /withdrawnArchiveSha256Denylist/);
  assert.match(builtReleaseVerifier, /release archive must not reuse withdrawn \.6 bytes/);
  assert.doesNotMatch(builtReleaseVerifier, /docs\/selfhost6-phase/);
});

test("the same desktop action binds the published final to a separate .7 bridge", () => {
  const bridgeJob = legacyWorkflow.match(
    /  selfhost-release-bridge:[\s\S]*?\n  nightly-release:/,
  )[0];
  assert.match(bridgeJob, /selfhost-operation == 'bridge-published-\.7'/);
  assert.match(bridgeJob, /environment: selfhost-production/);
  assert.match(bridgeJob, /group: selfhost-release-selfhost-official-architecture-v1/);
  assert.match(
    bridgeJob,
    /Download and verify the published final latest release[\s\S]*--phase published[\s\S]*Verify both sources and the exact embedded final bytes[\s\S]*Stage the one-time \.6 bridge pointers[\s\S]*Promote exactly five updater assets and four pointers/,
  );
  assert.match(bridgeJob, /repos\/\$\{GITHUB_REPOSITORY\}\/releases\/latest/);
  assert.match(bridgeJob, /gh release download "\$\{SELFHOST6_TARGET_VERSION\}"/);
  assert.equal((bridgeJob.match(/\.selfhost6\.json/g) ?? []).length, 5);
  assert.equal((bridgeJob.match(/promote-update-feed\.mjs/g) ?? []).length, 1);
  assert.equal((bridgeJob.match(/promote-existing-release\.mjs/g) ?? []).length, 1);
  assert.match(bridgeJob, /--source-full-sha "\$\{FINAL_SOURCE_FULL_SHA\}"/);
  assert.match(bridgeJob, /--expected-source-full-sha "\$\{BRIDGE_SOURCE_FULL_SHA\}"/);
  assert.doesNotMatch(bridgeJob, /--(?:source-full-sha|expected-source-full-sha) "\$\{GITHUB_SHA\}"/);
  assert.match(bridgeJob, /ref: \$\{\{ env\.BRIDGE_SOURCE_FULL_SHA \}\}[\s\S]*path: bridge-source/);
  assert.match(bridgeJob, /node bridge-source\/scripts\/selfhost6\/promote-update-feed\.mjs/);
  assert.match(bridgeJob, /node bridge-source\/scripts\/selfhost6\/promote-existing-release\.mjs/);
  assert.match(bridgeJob, /--expected-target-version '2\.0\.1-selfhost\.7'/);
  assert.match(bridgeJob, /Validate explicit bridge build inputs[\s\S]*verify-offline-bridge\.mjs inputs[\s\S]*Download and verify the exact bridge build artifact[\s\S]*verify-offline-bridge\.mjs download[\s\S]*unzip -q -n -j[\s\S]*verify-offline-bridge\.mjs payloads/);
  for (const name of ["BRIDGE_SOURCE_FULL_SHA", "FINAL_SOURCE_FULL_SHA", "BRIDGE_BUILD_RUN_ID",
    "BRIDGE_BUILD_ARTIFACT_ID", "BRIDGE_BUILD_ARTIFACT_SHA256"]) assert.match(bridgeJob, new RegExp(name));
  for (const name of [
    "SELFHOST_EXISTING_RELEASE_ID",
    "SELFHOST_EXISTING_RELEASE_TARGET_FULL_SHA",
    "SELFHOST_EXISTING_TAG_OBJECT_FULL_SHA",
    "SELFHOST_EXISTING_TAG_PEELED_COMMIT_FULL_SHA",
  ]) assert.match(bridgeJob, new RegExp(`${name}: \\$\\{\\{ secrets\\.${name} \\}\\}`));
  assert.doesNotMatch(bridgeJob, /softprops\/action-gh-release|draft: false|setTimeout|setInterval|retry/);
  assert.doesNotMatch(
    legacyWorkflow.match(/on:[\s\S]*?\nenv:/)[0],
    /release:\s*\n\s+types:\s*\[published\]/,
  );
});

test("the Windows probe is private, non-release and independent of real package signing", () => {
  const probe = legacyWorkflow.match(/  selfhost-windows-bridge-probe:[\s\S]*?\n  selfhost-bridge-build-inputs:/)[0];
  assert.match(probe, /selfhost-operation == 'build-offline-bridge'/);
  assert.match(probe, /build-target == 'non-release'/);
  assert.match(probe, /is-draft == 'true'/);
  assert.match(probe, /github\.event\.inputs\.git-ref == github\.ref_name/);
  assert.match(probe, /permissions:\n\s+contents: read/);
  assert.match(probe, /Require a private test context[\s\S]*private-windows-test-context-required[\s\S]*Check out exact Windows probe tools/);
  assert.deepEqual([...probe.matchAll(/runner: (windows-\S+)\n\s+arch: (\S+)/g)].map(match => match.slice(1)), [
    ["windows-2022", "x64"], ["windows-11-arm", "arm64"],
  ]);
  assert.match(probe, /ref: \$\{\{ github\.sha \}\}/);
  assert.match(probe, /persist-credentials: false/);
  assert.match(probe, /npm exec --yes --package=pnpm@10\.33\.0 -- pnpm --dir static install/);
  assert.ok([...probe.matchAll(/uses: (\S+)/g)].every(match => match[1].startsWith("actions/")));
  assert.match(probe, /pnpm --dir static install --frozen-lockfile --ignore-workspace --ignore-scripts/);
  assert.match(probe, /run: node --test scripts\/selfhost6\/windows-nsis-probe\.test\.mjs/);
  assert.match(probe, /run: node scripts\/selfhost6\/windows-nsis-probe\.mjs/);
  assert.match(probe, /if: \$\{\{ always\(\) && github\.event\.repository\.private \}\}/);
  assert.match(probe, /retention-days: 3/);
  assert.doesNotMatch(probe, /needs:|environment:|secrets\.|contents: write|gh release|promote-|prepare-update-artifact|--publish/);
});

test("the desktop action builds native bridge inputs without promoting a partial set", () => {
  for (const name of ["upstream-compile-cljs", "build-android"]) {
    const root = legacyWorkflow.split(`\n  ${name}:`)[1].split(/\n  [a-z][a-z0-9-]+:/)[0];
    assert.match(root, /if: .*selfhost-operation != 'build-offline-bridge'/);
  }
  const jobs = legacyWorkflow.match(
    /  selfhost-bridge-build-inputs:[\s\S]*?\n  selfhost-bridge-signing:/,
  )[0];
  const inputs = jobs.split("\n  selfhost-bridge-build-macos:")[0];
  assert.match(inputs, /selfhost-operation == 'build-offline-bridge'/);
  assert.match(inputs, /build-target == 'stable'/);
  assert.match(inputs, /is-draft == 'true'/);
  assert.match(inputs, /github\.ref_name == 'release\/2\.0\.2-selfhost\.1'/);
  assert.match(inputs, /git-ref == 'release\/2\.0\.2-selfhost\.1'/);
  assert.match(inputs, /environment: selfhost-production/);
  assert.match(inputs, /permissions:\n\s+contents: read/);
  assert.match(inputs, /build-offline-bridge\.mjs inputs[\s\S]*Download and verify the existing final Draft[\s\S]*verify-draft-release\.mjs[\s\S]*--phase after[\s\S]*Retain exact final macOS arm64 input/);
  assert.match(inputs, /--source-full-sha "\$\{FINAL_SOURCE_FULL_SHA\}"/);
  assert.doesNotMatch(inputs, /--source-full-sha "\$\{GITHUB_SHA\}"/);
  assert.equal((inputs.match(/uses: actions\/upload-artifact@v4/g) ?? []).length, 5);
  for (const [id, filename] of [
    ["darwin-arm64", "Logseq-darwin-arm64-${{ env.SELFHOST6_TARGET_VERSION }}.zip"],
    ["darwin-x64", "Logseq-darwin-x64-${{ env.SELFHOST6_TARGET_VERSION }}.zip"],
    ["linux-arm64", "Logseq-linux-arm64-${{ env.SELFHOST6_TARGET_VERSION }}.AppImage"],
    ["linux-x64", "Logseq-linux-x86_64-${{ env.SELFHOST6_TARGET_VERSION }}.AppImage"],
    ["win32-x64", "Logseq-win-x64-${{ env.SELFHOST6_TARGET_VERSION }}-nsis.exe"],
  ]) {
    const name = `name: selfhost-offline-final-${id}-` + "${{ env.FINAL_SOURCE_FULL_SHA }}";
    const upload = inputs.split(name)[1]?.split("if-no-files-found:")[0];
    assert.ok(upload?.includes(`path: |\n            final-release-assets/${filename}\n            final-release-assets/${filename}.selfhost6.json\n`), id);
  }
  assert.deepEqual([...jobs.matchAll(/^\s+- id: (\S+)$/gm)].map(match => match[1]), [
    "darwin-arm64", "darwin-x64", "linux-arm64", "linux-x64",
  ]);
  const mac = jobs.match(/  selfhost-bridge-build-macos:[\s\S]*?\n  selfhost-bridge-build-linux:/)[0];
  const linux = jobs.split("\n  selfhost-bridge-build-linux:")[1].split("\n  selfhost-bridge-build-windows:")[0];
  for (const job of [mac, linux]) {
    assert.match(job, /needs: \[ selfhost-bridge-build-inputs \]/);
    assert.match(job, /ref: \$\{\{ github\.sha \}\}/);
    assert.match(job, /ref: \$\{\{ env\.BRIDGE_SOURCE_FULL_SHA \}\}[\s\S]*path: bridge-source/);
    assert.match(job, /name: selfhost-offline-final-\$\{\{ matrix\.id \}\}-\$\{\{ env\.FINAL_SOURCE_FULL_SHA \}\}[\s\S]*path: final-release-assets/);
    assert.match(job, /pnpm --dir static install --frozen-lockfile --ignore-workspace --ignore-scripts/);
    assert.match(job, /uses: actions\/setup-java@v4\n\s+with:\n\s+distribution: zulu\n\s+java-version: \$\{\{ env\.JAVA_VERSION \}\}[\s\S]*Export existing native update strings/);
    assert.match(job, /working-directory: bridge-source\n\s+run: bb lang:export-native-update-strings "\$RUNNER_TEMP\/offline-bridge-native-strings\.json"/);
    assert.match(job, /run: node scripts\/selfhost6\/build-offline-bridge\.mjs build/);
    assert.match(job, /name: selfhost-offline-bridge-input-\$\{\{ matrix\.id \}\}-\$\{\{ env\.BRIDGE_SOURCE_FULL_SHA \}\}-\$\{\{ env\.FINAL_SOURCE_FULL_SHA \}\}/);
    assert.match(job, /path: bridge-build-input/);
    assert.match(job, /if-no-files-found: error/);
  }
  assert.match(linux, /container: debian:bullseye-slim/);
  assert.match(linux, /Prepare the native compiler baseline[\s\S]*curl git unzip build-essential pkg-config libglib2\.0-dev libgtk-3-dev[\s\S]*Check out exact bridge workflow tools/);
  const windows = jobs.split("\n  selfhost-bridge-build-windows:")[1];
  assert.match(windows, /needs: \[ selfhost-bridge-build-inputs \]/);
  assert.match(windows, /runs-on: windows-2022[\s\S]*shell: pwsh/);
  assert.match(windows, /ref: \$\{\{ github\.sha \}\}/);
  assert.match(windows, /ref: \$\{\{ env\.BRIDGE_SOURCE_FULL_SHA \}\}[\s\S]*path: bridge-source/);
  assert.match(windows, /name: selfhost-offline-final-win32-x64-\$\{\{ env\.FINAL_SOURCE_FULL_SHA \}\}[\s\S]*path: final-release-assets/);
  assert.match(windows, /architecture: x64/);
  assert.match(windows, /Copy-Item -LiteralPath resources\/package\.json,resources\/pnpm-lock\.yaml,resources\/pnpm-workspace\.yaml -Destination static/);
  assert.match(windows, /pnpm --dir static install --frozen-lockfile --ignore-workspace --ignore-scripts[\s\S]*if \(\$LASTEXITCODE -ne 0\)/);
  assert.match(windows, /working-directory: bridge-source\n\s+run: bb lang:export-native-update-strings "\$env:RUNNER_TEMP\/offline-bridge-native-strings\.json"/);
  assert.match(windows, /run: node scripts\/selfhost6\/build-offline-bridge\.mjs build/);
  assert.match(windows, /name: selfhost-offline-bridge-input-win32-x64-\$\{\{ env\.BRIDGE_SOURCE_FULL_SHA \}\}-\$\{\{ env\.FINAL_SOURCE_FULL_SHA \}\}[\s\S]*path: bridge-build-input[\s\S]*if-no-files-found: error/);
  assert.doesNotMatch(windows, /arm64|matrix|strategy/);
  assert.doesNotMatch(jobs, /contents: write|--publish always|action-gh-release|promote-update-feed|promote-existing-release/);
  assert.doesNotMatch(jobs, /LOGSEQ_PROJECT_UPDATE_SIGNING_KEY|prepare-update-artifact|cljs:release|rtc:prepush/);
  assert.doesNotMatch(jobs, /name: selfhost-offline-bridge-\$\{\{/);
});

test("existing release promotion keeps one bounded transaction owner", () => {
  assert.deepEqual(bridgeImmutableAssetNames("2.0.1-selfhost.7"), [
    "Logseq-darwin-arm64-2.0.1-selfhost.7.zip",
    "Logseq-darwin-x64-2.0.1-selfhost.7.zip",
    "Logseq-linux-arm64-2.0.1-selfhost.7.AppImage",
    "Logseq-linux-x86_64-2.0.1-selfhost.7.AppImage",
    "Logseq-win-x64-2.0.1-selfhost.7-nsis.exe",
  ]);
  assert.match(existingReleasePromotion, /for \(const \[name, bytes\] of immutableFiles\)/);
  assert.match(existingReleasePromotion, /for \(const name of pointerFiles\.keys\(\)\)/);
  assert.match(existingReleasePromotion, /assertPointerOwnership/);
  assert.match(existingReleasePromotion, /restorePointer/);
  assert.match(existingReleasePromotionCli, /initialPointerBaseline: "absent"/);
  assert.match(existingReleasePromotion, /promotion-incomplete\/recovery-required/);
  assert.doesNotMatch(existingReleasePromotion, /setTimeout|setInterval|retry/);
  assert.match(existingReleasePromotionCli, /GITHUB_TOKEN \?\? process\.env\.GH_TOKEN/);
  assert.doesNotMatch(existingReleasePromotionCli, /console\.log\(.*token|upload-artifact/);
});

test("existing release promotion rejects a missing release id before API access", () => {
  assert.throws(() => parseExpectedReleaseId(""), /positive decimal integer/);
  assert.throws(() => parseExpectedReleaseId(undefined), /positive decimal integer/);
  assert.equal(parseExpectedReleaseId("424243"), 424243);
});

test("existing-release promotion resumes a partial run and is idempotent", async () => {
  const input = promotionFixture();
  const seed = oldPointerSeed();
  seed.set(pointerNames[0], input.pointerFiles.get(pointerNames[0]));
  seed.set(pointerNames[1], input.pointerFiles.get(pointerNames[1]));
  seed.set("Logseq-existing-release-fixture.zip", Buffer.from("published bytes"));
  const api = new FakeReleaseApi(seed);
  const existingAssetId = api.assets.get("Logseq-existing-release-fixture.zip").id;
  const first = await promoteExistingRelease({ ...input, api });
  assert.equal(first.status, "promotion-complete");
  assertRemoteBytes(api, new Map([...input.immutableFiles, ...input.pointerFiles]));
  const mutationsAfterFirst = api.mutations.length;
  const second = await promoteExistingRelease({ ...input, api });
  assert.equal(second.status, "promotion-complete");
  assert.equal(api.mutations.length, mutationsAfterFirst);
  assert.deepEqual(second.releaseIdentity.before, second.releaseIdentity.after);
  assert.equal(api.assets.get("Logseq-existing-release-fixture.zip").id, existingAssetId);
});

test("second and third pointer write failures restore all four snapshots", async () => {
  for (const failedName of pointerNames.slice(1, 3)) {
    const input = promotionFixture();
    const seed = oldPointerSeed();
    const api = new FakeReleaseApi(seed);
    let failed = false;
    api.hook = ({ phase, name, bytes }) => {
      if (!failed && phase === "before-upload" && name === failedName &&
          bytes.equals(input.pointerFiles.get(name))) {
        failed = true;
        throw new Error("injected pointer upload failure");
      }
    };
    const receipt = await rejectedReceipt(() => promoteExistingRelease({ ...input, api }));
    assert.equal(receipt.status, "promotion-failed-recovered", failedName);
    assertRemoteBytes(api, seed);
    for (const [name, bytes] of input.immutableFiles) {
      assert.deepEqual(api.assets.get(name)?.bytes, bytes, name);
    }
  }
});

test("first .7 promotion restores newly created pointers to absence", async () => {
  for (const failedName of pointerNames) {
    const input = promotionFixture();
    const api = new FakeReleaseApi();
    let failed = false;
    api.hook = ({ phase, name }) => {
      if (!failed && phase === "before-upload" && name === failedName) {
        failed = true;
        throw new Error("injected first-promotion pointer failure");
      }
    };
    const receipt = await rejectedReceipt(() => promoteExistingRelease({
      ...input,
      initialPointerBaseline: "absent",
      api,
    }));
    assert.equal(receipt.status, "promotion-failed-recovered", failedName);
    for (const name of pointerNames) assert.equal(api.assets.has(name), false, name);
    assertRemoteBytes(api, input.immutableFiles);
  }
});

test("first .7 promotion rejects an unexpected prior channel baseline", async () => {
  const input = promotionFixture();
  const seed = oldPointerSeed();
  const api = new FakeReleaseApi(seed);
  const receipt = await rejectedReceipt(() => promoteExistingRelease({
    ...input,
    initialPointerBaseline: "absent",
    api,
  }));
  assert.equal(receipt.status, "promotion-failed-no-pointer-mutation");
  assertRemoteBytes(api, seed);
});

test("failed compensation records exact recovery targets without overwriting them", async () => {
  const input = promotionFixture();
  const seed = oldPointerSeed();
  const api = new FakeReleaseApi(seed);
  let targetFailed = false;
  api.hook = ({ phase, name, bytes }) => {
    if (phase !== "before-upload") return;
    if (!targetFailed && name === pointerNames[1] && bytes.equals(input.pointerFiles.get(name))) {
      targetFailed = true;
      throw new Error("injected target failure");
    }
    if (targetFailed && name === pointerNames[0] && bytes.equals(seed.get(name))) {
      throw new Error("injected restore failure");
    }
  };
  const receipt = await rejectedReceipt(() => promoteExistingRelease({ ...input, api }));
  assert.equal(receipt.status, "promotion-incomplete/recovery-required");
  assert.deepEqual(receipt.pendingRecovery.map(({ name }) => name), [pointerNames[0]]);
  assert.equal(receipt.pendingRecovery[0].snapshot.bytesBase64, seed.get(pointerNames[0]).toString("base64"));
});

test("a concurrent pointer writer is detected and never overwritten by compensation", async () => {
  const input = promotionFixture();
  const seed = oldPointerSeed();
  const api = new FakeReleaseApi(seed);
  const concurrentBytes = pointerBytes(input.expectedVersion, "d".repeat(40), pointerNames[2]);
  let interleaved = false;
  api.hook = ({ phase, name }) => {
    if (!interleaved && phase === "after-upload" && name === pointerNames[0]) {
      interleaved = true;
      api.setAsset(pointerNames[2], concurrentBytes);
    }
  };
  const receipt = await rejectedReceipt(() => promoteExistingRelease({ ...input, api }));
  assert.equal(receipt.status, "promotion-incomplete/recovery-required");
  assert.deepEqual(api.assets.get(pointerNames[2]).bytes, concurrentBytes);
  assert.ok(receipt.pendingRecovery.some(({ name }) => name === pointerNames[2]));
  assert.deepEqual(api.assets.get(pointerNames[0]).bytes, seed.get(pointerNames[0]));
});

test("a same-version different-source pointer conflicts before pointer mutation", async () => {
  const input = promotionFixture();
  const seed = oldPointerSeed();
  seed.set(pointerNames[0], pointerBytes(input.expectedVersion, "d".repeat(40), pointerNames[0]));
  const api = new FakeReleaseApi(seed);
  const receipt = await rejectedReceipt(() => promoteExistingRelease({ ...input, api }));
  assert.equal(receipt.status, "promotion-failed-no-pointer-mutation");
  assertRemoteBytes(api, seed);
  assert.equal(api.mutations.some(({ name }) => pointerNames.includes(name)), false);
});

test("an existing immutable name with different bytes is never overwritten", async () => {
  const input = promotionFixture();
  const [name] = input.immutableFiles.keys();
  const api = new FakeReleaseApi(new Map([[name, Buffer.from("different published bytes")]]));
  const receipt = await rejectedReceipt(() => promoteExistingRelease({ ...input, api }));
  assert.equal(receipt.status, "promotion-failed-no-pointer-mutation");
  assert.deepEqual(api.assets.get(name).bytes, Buffer.from("different published bytes"));
  assert.equal(api.mutations.length, 0);
});

test("the frozen Release identity is checked before any upload", async () => {
  const input = promotionFixture();
  input.expectedReleaseIdentity = { ...input.expectedReleaseIdentity, releaseId: 72 };
  const api = new FakeReleaseApi();
  await assert.rejects(
    () => promoteExistingRelease({ ...input, api }),
    /differs from the frozen gate/,
  );
  assert.equal(api.mutations.length, 0);
});

test("the GitHub adapter uses bounded REST reads and content-addressed asset calls", async () => {
  const requests = [];
  const sourceFullSha = "e".repeat(40);
  const fetchImpl = async (url, options) => {
    requests.push({ url, options });
    if (url.endsWith(`/releases/tags/${releaseLineId}`)) {
      return Response.json({
        id: 71,
        tag_name: releaseLineId,
        target_commitish: sourceFullSha,
        upload_url: "https://uploads.example.invalid/releases/71/assets{?name,label}",
      });
    }
    if (url.endsWith(`/git/ref/tags/${releaseLineId}`)) {
      return Response.json({ object: { type: "commit", sha: sourceFullSha } });
    }
    if (url.includes("/releases/71/assets?")) {
      return Response.json([{ id: 9, name: "fixture.bin", size: 7, digest: `sha256:${"a".repeat(64)}` }]);
    }
    if (url.endsWith("/releases/assets/9") && options.method === "GET") {
      return new Response("fixture");
    }
    return new Response(null, { status: options.method === "DELETE" ? 204 : 201 });
  };
  const api = new GitHubReleaseApi({
    repository: "example/project",
    token: "synthetic-token",
    apiBaseUrl: "https://api.example.invalid",
    fetchImpl,
  });
  const release = await api.getReleaseByTag(releaseLineId);
  assert.deepEqual(await api.getTagIdentity(releaseLineId), {
    objectSha: sourceFullSha,
    peeledCommitSha: sourceFullSha,
  });
  assert.equal((await api.listAssets(release.id))[0].name, "fixture.bin");
  assert.equal((await api.downloadAsset(9)).toString(), "fixture");
  await api.uploadAsset(release, "new fixture.bin", Buffer.from("new"));
  await api.deleteAsset(9);
  assert.ok(requests.some(({ url }) => url === "https://uploads.example.invalid/releases/71/assets?name=new%20fixture.bin"));
  assert.ok(requests.every(({ options }) => options.headers.Authorization === "Bearer synthetic-token"));
  assert.equal(requests.filter(({ options }) => options.method === "DELETE").length, 1);
});

test("version override and every signed descriptor bind the requested full SHA", () => {
  assert.match(workflow, /test "\$\(git rev-parse HEAD\)" = "\$\{\{ inputs\.source-full-sha \}\}"/);
  assert.match(workflow, /set-candidate-version\.mjs --version "\$\{\{ inputs\.target-version \}\}"/);
  assert.equal((workflow.match(/prepare-update-artifact\.mjs --archive/g) ?? []).length, 8);
  assert.doesNotMatch(workflow, /candidate\.(zip|dmg|exe|AppImage)/);
  assert.equal((workflow.match(/--source-full-sha "\$\{\{ inputs\.source-full-sha \}\}"/g) ?? []).length, 9);
});

test("every desktop target produces one native SQLite swap receipt", () => {
  assert.match(workflow, /runner: ubuntu-24\.04-arm\n\s+platform: linux\n\s+arch: arm64/);
  assert.match(workflow, /Checkout qualification tool from this workflow revision/);
  assert.match(workflow, /ref: \$\{\{ github\.sha \}\}/);
  assert.match(workflow, /sparse-checkout: scripts\/selfhost6\/platform-sqlite-swap\.mjs/);
  assert.equal((workflow.match(/platform-sqlite-swap\.mjs/g) ?? []).length, 2);
  assert.match(workflow, /--platform "\$\{\{ matrix\.platform \}\}"/);
  assert.match(workflow, /--arch "\$\{\{ matrix\.arch \}\}"/);
  assert.match(workflow, /--source-full-sha "\$\{\{ inputs\.source-full-sha \}\}"/);
  assert.equal((workflow.match(/PLATFORM_SQLITE_SWAP_RECEIPT\.json signed\/qualification-receipts\//g) ?? []).length, 6);
  assert.match(platformSwapProbe, /fs\.openSync\(filePath, "r\+"\)/);
  assert.doesNotMatch(platformSwapProbe, /fs\.openSync\(filePath, "r"\)/);
});

test("packaged runtime install uses the isolated static lockfile", () => {
  assert.match(
    workflow,
    /working-directory: static\n\s+run: pnpm install --frozen-lockfile --ignore-workspace/,
  );
  assert.equal(packagedRuntimeWorkspace, "packages:\n  - .\n");
  assert.match(workflow, /Verify packaged macOS main-process dependencies/);
  assert.match(workflow, /find "\$\{GITHUB_WORKSPACE\}\/static\/dist"/);
  assert.match(workflow, /node_modules\/electron-log/);
  assert.match(workflow, /packaged-runtime-ok/);
});

test("static compilation pins and verifies opam without release discovery", () => {
  assert.match(workflow, /OPAM_VERSION: '2\.5\.2'/);
  assert.match(
    workflow,
    /OPAM_X86_64_LINUX_SHA512: '508a128cec8ddf06e763db56232818481a4eee7725fb725c08f543b4aefa0daa23042ec89a4dcb4fe8eac99c53c219196c715eec46b4f1f6769b47782f79943a'/,
  );
  assert.match(
    workflow,
    /github\.com\/ocaml\/opam\/releases\/download\/\$\{OPAM_VERSION\}\/opam-\$\{OPAM_VERSION\}-x86_64-linux/,
  );
  assert.match(workflow, /sha512sum --check --strict/);
  assert.match(workflow, /switch create \. "ocaml-base-compiler\.\$\{OCAML_VERSION\}" --yes/);
  assert.doesNotMatch(workflow, /ocaml\/setup-ocaml@/);
});

test("fork candidates preserve the accepted unsigned platform packaging boundary", () => {
  assert.match(workflow, /pnpm electron:make-unsigned --mac dmg zip --arm64/);
  assert.match(workflow, /pnpm electron:make-unsigned --mac dmg zip --x64/);
  assert.match(workflow, /pnpm electron:make-unsigned --win nsis zip --\${{ matrix\.arch }}/);
  assert.doesNotMatch(workflow, /import-codesign-certs|azureSignOptions|AZURE_TENANT_ID/);
  assert.equal((workflow.match(/LOGSEQ_PROJECT_UPDATE_SIGNING_KEY_PKCS8_BASE64/g) ?? []).length, 2);
});
