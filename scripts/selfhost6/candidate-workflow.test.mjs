import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import {
  PromotionError,
  promoteExistingRelease,
} from "../lib/selfhost6-existing-release-promotion.mjs";
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

const releaseLineId = "selfhost-official-architecture-v1";
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
  const archiveNames = [
    `Logseq-darwin-x64-${version}.zip`,
    `Logseq-darwin-arm64-${version}.zip`,
    `Logseq-darwin-x64-${version}.dmg`,
    `Logseq-darwin-arm64-${version}.dmg`,
    `Logseq-win32-x64-${version}.exe`,
    `Logseq-win32-arm64-${version}.exe`,
    `Logseq-linux-x64-${version}.AppImage`,
    `Logseq-linux-arm64-${version}.AppImage`,
  ];
  const immutableFiles = new Map();
  for (const name of archiveNames) {
    immutableFiles.set(name, Buffer.from(`archive:${name}`));
    immutableFiles.set(`${name}.selfhost6.json`, Buffer.from(`${JSON.stringify({
      sourceFullSha,
      targetVersion: version,
      signedMetadata: {
        "target-source-full-sha": sourceFullSha,
        "target-version": version,
      },
    })}\n`));
  }
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
  assert.match(workflow, /options:\n\s+- 2\.0\.1-selfhost\.7\n\s+- 2\.0\.1-selfhost\.8/);
  assert.match(workflow, /permissions:\n\s+contents: read/);
  assert.match(workflow, /environment: selfhost-release-signing/);
  assert.equal((workflow.match(/LOGSEQ_PROJECT_UPDATE_SIGNING_KEY_PKCS8_BASE64/g) ?? []).length, 2);
  assert.doesNotMatch(workflow, /gh release|--publish always|action-gh-release|contents: write/);
  assert.match(workflow, /promote-update-feed\.mjs[\s\S]*--expected-target-version/);
  assert.match(
    workflow,
    /Stage isolated synthetic forward-update channel\n\s+if: inputs\.target-version == '2\.0\.1-selfhost\.8'/,
  );
  assert.equal((workflow.match(/promote-update-feed\.mjs/g) ?? []).length, 1);
  assert.doesNotMatch(legacyWorkflow, /Refuse the isolated Selfhost6 release line/);
  assert.doesNotMatch(legacyWorkflow, /Use Build-Selfhost6-Candidate/);
});

test("the mature desktop action stages one exact .7 draft before promotion", () => {
  const selfhostReleaseJobs = legacyWorkflow.match(
    /  compile-cljs:[\s\S]*?\n  nightly-release:/,
  )[0];
  assert.match(legacyWorkflow, /name: Build-Desktop-Release/);
  assert.equal((legacyWorkflow.match(/^name: Build-Desktop-Release$/gm) ?? []).length, 1);
  assert.match(legacyWorkflow, /SELFHOST6_TARGET_VERSION: '2\.0\.1-selfhost\.7'/);
  assert.match(legacyWorkflow, /github\.ref_name == 'release\/2\.0\.1-selfhost\.7'/);
  assert.match(
    legacyWorkflow,
    /run-name: Desktop release · \$\{\{ github\.event\.inputs\.build-target \}\} · \$\{\{ github\.event\.inputs\.git-ref \}\}/,
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
  assert.match(selfhostReleaseJobs, /Verify the frozen Draft before replacement/);
  assert.equal((selfhostReleaseJobs.match(/git\/matching-refs\/tags\/\$\{SELFHOST6_TARGET_VERSION\}/g) ?? []).length, 2);
  assert.equal((selfhostReleaseJobs.match(/releases\?per_page=100/g) ?? []).length, 2);
  assert.equal((selfhostReleaseJobs.match(/--release-list-json/g) ?? []).length, 2);
  assert.match(selfhostReleaseJobs, /verify-draft-release\.mjs[\s\S]*--phase before/);
  assert.match(selfhostReleaseJobs, /Create the reviewed release as a draft/);
  assert.match(selfhostReleaseJobs, /softprops\/action-gh-release@v2/);
  assert.match(selfhostReleaseJobs, /files: release-assets\/Logseq-\*/);
  assert.match(selfhostReleaseJobs, /tag_name: \$\{\{ env\.SELFHOST6_TARGET_VERSION \}\}/);
  assert.match(selfhostReleaseJobs, /target_commitish: \$\{\{ needs\.selfhost-release-verifier\.outputs\.product-source-sha \}\}/);
  assert.match(selfhostReleaseJobs, /draft: true/);
  assert.match(selfhostReleaseJobs, /prerelease: false/);
  assert.match(selfhostReleaseJobs, /make_latest: false/);
  assert.match(selfhostReleaseJobs, /fail_on_unmatched_files: true/);
  assert.match(selfhostReleaseJobs, /overwrite_files: true/);
  assert.match(selfhostReleaseJobs, /Bind and verify the exact reviewed Draft/);
  assert.doesNotMatch(selfhostReleaseJobs, /steps\.draft\.outputs\.id|ACTION_DRAFT_RELEASE_ID/);
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
    /Verify the frozen Draft before replacement[\s\S]*Create the reviewed release as a draft[\s\S]*Bind and verify the exact reviewed Draft[\s\S]*Stage four source-version channel pointers[\s\S]*promote-update-feed\.mjs[\s\S]*Promote immutable assets after the draft is staged[\s\S]*promote-existing-release\.mjs/,
  );
  assert.match(
    selfhostReleaseJobs,
    /promote-existing-release\.mjs[\s\S]*--expected-source-full-sha "\$\{\{ needs\.selfhost-release-verifier\.outputs\.product-source-sha \}\}"/,
  );
  for (const name of [
    "SELFHOST_EXISTING_RELEASE_ID",
    "SELFHOST_EXISTING_RELEASE_TARGET_FULL_SHA",
    "SELFHOST_EXISTING_TAG_OBJECT_FULL_SHA",
    "SELFHOST_EXISTING_TAG_PEELED_COMMIT_FULL_SHA",
  ]) assert.match(selfhostReleaseJobs, new RegExp(`${name}: \\$\\{\\{ secrets\\.${name} \\}\\}`));
  assert.doesNotMatch(legacyWorkflow, /selfhost-release-terminal-audit:/);
  assert.doesNotMatch(legacyWorkflow, /gh release (create|edit|upload)/);
  assert.doesNotMatch(selfhostReleaseJobs, /Build-Selfhost6-Candidate|32051789643|verify-release-promotion\.mjs/);
  assert.doesNotMatch(
    selfhostReleaseJobs,
    /wrangler (deploy|versions deploy)|latest-x64|latest-arm64|2\.0\.1-selfhost\.8/,
  );
  assert.match(builtReleaseVerifier, /formal release set must contain eight platform archives and eight descriptors/);
  assert.match(builtReleaseVerifier, /built-assets-verified-awaiting-product-qualification/);
  assert.match(builtReleaseVerifier, /withdrawnArchiveSha256Denylist/);
  assert.match(builtReleaseVerifier, /release archive must not reuse withdrawn \.6 bytes/);
  assert.doesNotMatch(builtReleaseVerifier, /docs\/selfhost6-phase/);
});

test("existing release promotion keeps one bounded transaction owner", () => {
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
