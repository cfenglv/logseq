import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

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
  assert.match(workflow, /permissions:\n\s+contents: read/);
  assert.match(workflow, /environment: selfhost-release-signing/);
  assert.equal((workflow.match(/LOGSEQ_PROJECT_UPDATE_SIGNING_KEY_PKCS8_BASE64/g) ?? []).length, 2);
  assert.doesNotMatch(workflow, /gh release|--publish always|action-gh-release|contents: write/);
  assert.match(workflow, /promote-update-feed\.mjs[\s\S]*--expected-target-version/);
  assert.match(
    workflow,
    /Stage isolated synthetic forward-update channel\n\s+if: inputs\.target-version == '2\.0\.1-selfhost\.7'/,
  );
  assert.equal((workflow.match(/promote-update-feed\.mjs/g) ?? []).length, 1);
  assert.doesNotMatch(legacyWorkflow, /Refuse the isolated Selfhost6 release line/);
  assert.doesNotMatch(legacyWorkflow, /Use Build-Selfhost6-Candidate/);
});

test("the mature desktop action compiles, signs, verifies, and publishes one exact release set", () => {
  const selfhostReleaseJobs = legacyWorkflow.match(
    /  compile-cljs:[\s\S]*?\n  nightly-release:/,
  )[0];
  assert.match(legacyWorkflow, /name: Build-Desktop-Release/);
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
  assert.match(legacyWorkflow, /upstream-compile-cljs:\n\s+name: compile-cljs \(upstream desktop\)/);
  assert.match(selfhostReleaseJobs, /compile-cljs:[\s\S]*Compile the release desktop owners/);
  assert.match(selfhostReleaseJobs, /selfhost-build-platform:/);
  assert.equal((selfhostReleaseJobs.match(/^\s+- id: (darwin-x64|darwin-arm64|win32-x64|win32-arm64|linux-x64|linux-arm64)$/gm) ?? []).length, 6);
  assert.match(selfhostReleaseJobs, /environment: selfhost-release-signing/);
  assert.match(selfhostReleaseJobs, /selfhost-release-verifier:/);
  assert.equal((selfhostReleaseJobs.match(/verify-built-release\.mjs/g) ?? []).length, 2);
  assert.match(selfhostReleaseJobs, /environment: selfhost-production/);
  assert.match(selfhostReleaseJobs, /uses: softprops\/action-gh-release@v2/);
  assert.match(selfhostReleaseJobs, /target_commitish: \$\{\{ needs\.selfhost-release-verifier\.outputs\.product-source-sha \}\}/);
  assert.match(selfhostReleaseJobs, /make_latest: false/);
  assert.doesNotMatch(legacyWorkflow, /selfhost-release-terminal-audit:/);
  assert.doesNotMatch(legacyWorkflow, /gh release (create|edit|upload)/);
  assert.doesNotMatch(selfhostReleaseJobs, /Build-Selfhost6-Candidate|32051789643|verify-release-promotion\.mjs/);
  assert.doesNotMatch(
    selfhostReleaseJobs,
    /wrangler (deploy|versions deploy)|latest-x64|latest-arm64|2\.0\.1-selfhost\.7/,
  );
  assert.match(builtReleaseVerifier, /formal release set must contain eight platform archives and eight descriptors/);
  assert.match(builtReleaseVerifier, /built-assets-verified-awaiting-product-qualification/);
  assert.match(builtReleaseVerifier, /withdrawnArchiveSha256Denylist/);
  assert.match(builtReleaseVerifier, /release archive must not reuse withdrawn \.6 bytes/);
  assert.doesNotMatch(builtReleaseVerifier, /docs\/selfhost6-phase/);
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
