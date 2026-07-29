import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync } from "node:fs";
import os from "node:os";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  macosUpdaterChannel,
  macosUpdaterMetadataName,
  resolveSelfhostUpdaterVersions,
} from "../resources/selfhost-updater-version.mjs";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

const readText = (relativePath) =>
  fs.readFileSync(path.join(repoRoot, relativePath), "utf8");

const readJson = (relativePath) =>
  JSON.parse(readText(relativePath));

assert.deepEqual(
  resolveSelfhostUpdaterVersions("2.0.1-selfhost.4"),
  {
    currentRevision: 4,
    currentVersion: "2.0.1-selfhost.4",
    isNightlyRehearsal: false,
    nextVersion: "2.0.1-selfhost.5",
  },
  "stable selfhost updater versions should advance one numbered revision",
);
assert.deepEqual(
  resolveSelfhostUpdaterVersions(
    "2.0.1-selfhost.4-alpha.nightly.20260726",
  ),
  {
    currentRevision: 4,
    currentVersion: "2.0.1-selfhost.4",
    isNightlyRehearsal: true,
    nextVersion: "2.0.1-selfhost.5",
  },
  "push rehearsals should normalize only the dated nightly suffix",
);
for (const invalidVersion of [
  "2.0.1-selfhost.3",
  "2.0.1-selfhost.4-alpha.nightly.20260230",
  "2.0.1-selfhost.4-alpha.other.20260726",
  "2.0.1",
]) {
  assert.throws(
    () => resolveSelfhostUpdaterVersions(invalidVersion),
    undefined,
    `invalid updater rehearsal version should be rejected: ${invalidVersion}`,
  );
}
assert.equal(
  macosUpdaterChannel("2.0.1-selfhost.4", "arm64"),
  "latest-arm64",
  "published selfhost.4 clients must remain on the frozen legacy channel",
);
assert.equal(
  macosUpdaterChannel("2.0.1-selfhost.5", "arm64"),
  "selfhost-macos-v2-arm64",
  "manual migration clients should use the new signed macOS channel",
);
assert.equal(
  macosUpdaterMetadataName("2.0.1-selfhost.5", "x64"),
  "selfhost-macos-v2-x64-mac.yml",
  "published metadata should match the signed macOS channel",
);
assert.throws(
  () => macosUpdaterChannel("2.0.1-selfhost.5", "ia32"),
  /unsupported macOS updater architecture/,
  "macOS metadata generation should reject unsupported architectures",
);
const updaterVersionCli = path.join(
  repoRoot,
  "resources",
  "selfhost-updater-version.mjs",
);
for (const [version, arch, expected] of [
  ["2.0.1-selfhost.4", "arm64", "latest-arm64-mac.yml"],
  ["2.0.1-selfhost.4", "x64", "latest-x64-mac.yml"],
  [
    "2.0.1-selfhost.5",
    "arm64",
    "selfhost-macos-v2-arm64-mac.yml",
  ],
  ["2.0.1-selfhost.5", "x64", "selfhost-macos-v2-x64-mac.yml"],
]) {
  assert.equal(
    execFileSync(
      process.execPath,
      [updaterVersionCli, "macos-metadata-name", version, arch],
      { encoding: "utf8" },
    ).trim(),
    expected,
    `workflow metadata helper should resolve ${version}/${arch}`,
  );
}

const assertContains = (text, needle, label) => {
  assert.ok(text.includes(needle), `${label} should contain ${needle}`);
};

const rootPackage = readJson("package.json");
const desktopPackage = readJson("resources/package.json");
const dbSyncPackage = readJson("deps/db-sync/package.json");
const dbSyncWorkspace = readText("deps/db-sync/pnpm-workspace.yaml");
const desktopPackagingGulpfile = readText("gulpfile.js");
const desktopPackagingWorkflow = readText(".github/workflows/build-desktop-release.yml");
const prLabelerWorkflow = readText(".github/workflows/pr-labeler.yml");
const desktopBuilderConfig = readText("resources/electron-builder.yml");
const unsignedDesktopBuilder = readText("resources/electron-builder-unsigned.mjs");
const unsignedDesktopConfig = readText("resources/electron-builder.unsigned.yml");
const adhocAfterSign = readText("resources/electron-builder-adhoc-after-sign.cjs");
const verifyDesktopRuntimeRevisionsScript = readText(
  "scripts/verify-desktop-runtime-revisions.mjs",
);
const desktopReleasePreflight = readText(
  "scripts/desktop-release-preflight.mjs",
);
const fullDesktopReleasePreflight = readText(
  "scripts/run-desktop-release-preflight.mjs",
);
const desktopReleaseAssetVerifier = readText(
  "scripts/verify-desktop-release-assets.mjs",
);
const macosUpdaterSignatureVerifier = readText(
  "scripts/verify-macos-updater-signature.mjs",
);
const macosUpdaterSignaturePolicy = readText(
  "scripts/run-macos-updater-signature-policy.mjs",
);
const projectSignedMacosUpdaterVerifier = readText(
  "scripts/verify-project-signed-macos-update.mjs",
);
const macosUpdaterBaseline = readJson(
  "scripts/fixtures/macos-updater-baseline.json",
);
const packagedDesktopVerifier = readText(
  "resources/verify-packaged-desktop.mjs",
);
const updaterProviderVerifier = readText(
  "resources/verify-updater-provider.mjs",
);
const electronUpdater = readText("src/electron/electron/updater.cljs");
const electronUpdaterConfig = readText(
  "src/electron/electron/updater_config.cljs",
);
const desktopSettings = readText(
  "src/main/frontend/components/settings.cljs",
);
const e2eSettings = readText("clj-e2e/src/logseq/e2e/settings.clj");
const e2eGraph = readText("clj-e2e/src/logseq/e2e/graph.clj");
const e2eRtc = readText("clj-e2e/src/logseq/e2e/rtc.clj");
const e2eUtil = readText("clj-e2e/src/logseq/e2e/util.clj");
const e2eOutliner = readText(
  "clj-e2e/test/logseq/e2e/outliner_basic_test.clj",
);
const e2eRtcExtra = readText(
  "clj-e2e/test/logseq/e2e/rtc_extra_test.clj",
);
const e2eRtcExtraPart2 = readText(
  "clj-e2e/test/logseq/e2e/rtc_extra_part2_test.clj",
);

assert.match(
  prLabelerWorkflow,
  /permissions:\s+contents: read\s+pull-requests: write/,
  "PR labeler should receive only the repository permissions it needs",
);
assert.match(
  prLabelerWorkflow,
  /TimonVS\/pr-labeler-action@bd0b592a410983316a454e3d48444608f028ec8e/,
  "write-capable PR labeler action should be pinned to an immutable commit",
);
assert.match(
  e2eSettings,
  /\(w\/wait-for "#search-button"\)\s+\(assert\/assert-in-normal-mode\?\)/,
  "E2E setup should wait for the application shell before asserting normal mode",
);
assert.match(
  e2eGraph,
  /rtc-graph-control-timeout-ms 15000[\s\S]*?rtc-sync-toggle \{:timeout rtc-graph-control-timeout-ms\}[\s\S]*?rtc-graph-e2ee-toggle \{:timeout rtc-graph-control-timeout-ms\}/,
  "RTC graph setup should use a bounded cold-runner timeout for both controls",
);
assert.match(
  e2eUtil,
  /rtc-entitlement-ready-script[\s\S]*?rtc_2025_07_10/,
  "RTC E2E entitlement gate should accept both supported account groups",
);
assert.match(
  e2eUtil,
  /rtc-login-dismiss-timeout-ms 30000[\s\S]*?wait-login-dismissed![\s\S]*?w\/visible\? "\.cp__user-login"[\s\S]*?System\/nanoTime[\s\S]*?RTC login modal was not dismissed/,
  "RTC E2E login should use a bounded cold-runner allowance without resubmitting credentials",
);
assert.match(
  e2eUtil,
  /w\/click "\.cp__user-login button\[type=\\"submit\\"\]"[\s\S]*?\(wait-login-dismissed!\)[\s\S]*?\(wait-rtc-entitlement-ready!\)/,
  "RTC E2E login should await dismissal and asynchronous account entitlement before opening graph controls",
);
assert.match(
  e2eRtc,
  /wait-current-tx-synced[\s\S]*?button\.cloud\.on\.idle[\s\S]*?\(= local-tx remote-tx\)[\s\S]*?\(= previous current\)/,
  "RTC destructive UI tests should require two consecutive synced transaction observations",
);
assert.match(
  e2eOutliner,
  /\(settle!\)[\s\S]*?get-by-text "b4" true[\s\S]*?\(b\/delete-blocks\)[\s\S]*?\(settle!\)[\s\S]*?get-by-text "b3" true[\s\S]*?select-blocks-to-count 2/,
  "outliner deletion should settle RTC before re-establishing each destructive selection context",
);
assert.match(
  e2eRtcExtra,
  /outliner-basic-test\/delete rtc\/wait-current-tx-synced/,
  "RTC outliner tests should enable the transaction-settling deletion path",
);
assert.match(
  e2eOutliner,
  /defn move-up-down[\s\S]*?\(util\/exit-edit\)[\s\S]*?\(settle!\)[\s\S]*?select-b3-and-b4[\s\S]*?move-selected-blocks[\s\S]*?\(settle!\)[\s\S]*?select-b3-and-b4[\s\S]*?move-selected-blocks[\s\S]*?\(settle!\)/,
  "outliner moves should establish exact selection and observe each ordered stage",
);
assert.match(
  e2eRtcExtra,
  /outliner-basic-test\/move-up-down rtc\/wait-current-tx-synced/,
  "RTC outliner tests should settle synchronized move stages",
);
assert.match(
  e2eRtcExtra,
  /rtc-outliner-conflict-update-test[\s\S]*?focus-exact-block! \(str title-prefix "-" 3\)[\s\S]*?k\/meta\+shift\+arrow-down[\s\S]*?k\/enter[\s\S]*?focus-exact-block! \(str title-prefix "-" 3\)[\s\S]*?\(b\/indent\)/,
  "RTC conflict moves should re-establish the exact editor target before indentation",
);
assert.match(
  e2eRtcExtraPart2,
  /current-editor-layout[\s\S]*?when-let \[box \(\.boundingBox editor\)\][\s\S]*?:editor-id[\s\S]*?try-indent![\s\S]*?\(= editor-id editor-id'\)[\s\S]*?try-outdent![\s\S]*?\(= editor-id editor-id'\)/,
  "parallel RTC stress indentation should tolerate detached editor layouts without changing targets",
);

const zvecOptionalRuntimeDependencies = [
  "@zvec/bindings-darwin-arm64",
  "@zvec/bindings-linux-arm64",
  "@zvec/bindings-linux-x64",
  "@zvec/bindings-win32-x64",
  "@zvec/zvec",
];

const assertNotContains = (text, needle, label) => {
  assert.equal(
    text.includes(needle),
    false,
    `${label} should not contain ${needle}`,
  );
};

const assertNoShadowRuntime = (text, label) => {
  for (const needle of ["SHADOW_IMPORT", ".shadow-cljs", "cljs-runtime"]) {
    assertNotContains(text, needle, label);
  }
};

const assertRootScriptDoesNotBuildShadowCli = (scriptName, command) => {
  assert.ok(command, `package.json should define ${scriptName}`);
  assert.doesNotMatch(
    command,
    /clojure -[MA]:cljs (?:watch|compile|release)[^"]*\blogseq-cli\b/,
    `${scriptName} should not build the old Shadow CLI`,
  );
};

assert.equal(
  desktopPackage.scripts["electron:make-unsigned"],
  "node ./electron-builder-unsigned.mjs",
  "the standalone desktop artifact should run its bundled unsigned builder",
);
assert.equal(
  desktopPackage.scripts["electron:verify-package"],
  "node ./verify-packaged-desktop.mjs",
  "the standalone desktop artifact should expose package verification",
);
assert.equal(
  desktopPackage.scripts["electron:verify-updater-provider"],
  "node ./verify-updater-provider.mjs",
  "the standalone desktop artifact should expose updater provider verification",
);
assert.equal(
  desktopPackage.devDependencies["@electron/asar"],
  "3.4.1",
  "package verification must not rely on a transitive asar dependency",
);
assert.match(
  desktopPackagingGulpfile,
  /resourceFilePath = path\.join\(resourcesPath, '\*\*'\)/,
  "desktop resource sync should include the bundled signing scripts",
);
assert.match(
  desktopBuilderConfig,
  /publish:\s+- provider: github\s+owner: cfenglv\s+repo: logseq/,
  "packaged selfhost clients should read updates from the fork release feed",
);

assert.match(
  rootPackage.scripts["desktop:verify-runtime-revisions"],
  /verify-desktop-runtime-revisions\.mjs/,
  "package.json should expose desktop runtime revision verification",
);
assert.match(
  desktopPackagingGulpfile,
  /pnpm desktop:verify-runtime-revisions/,
  "desktop packaging should reject inconsistent runtime revisions",
);
assert.match(
  desktopPackagingWorkflow,
  /pnpm desktop:verify-runtime-revisions/,
  "desktop release CI should reject inconsistent runtime revisions",
);
for (const relativePath of [
  "static/electron.js",
  "static/db-worker-node.js",
  "static/logseq-cli.js",
  "dist/db-worker-node.js",
  "static/js/db-worker-node.js",
  "static/js/logseq-cli.js",
  "static/js/main.js",
  "static/js/db-worker.js",
  "static/js/publishing/main.js",
]) {
  assert.match(
    verifyDesktopRuntimeRevisionsScript,
    new RegExp(relativePath.replaceAll("/", "[\\\\/]")),
    `runtime revision verification should cover ${relativePath}`,
  );
}

const assertCliReleaseCommand = (command, label) => {
  assert.match(command, /pnpm --dir cli bundle/, `${label} should bundle cli/`);
  assert.match(
    command,
    /node \.\/scripts\/stage-cli-runtime\.mjs/,
    `${label} should stage the cli/ bundle`,
  );
};

const filesUnder = (relativePath) => {
  const absolutePath = path.join(repoRoot, relativePath);
  if (!fs.existsSync(absolutePath)) {
    return [];
  }
  const stat = fs.statSync(absolutePath);
  if (stat.isFile()) {
    return [relativePath];
  }
  return fs
    .readdirSync(absolutePath, { withFileTypes: true })
    .flatMap((entry) => {
      const entryPath = path.join(relativePath, entry.name);
      return entry.isDirectory() ? filesUnder(entryPath) : [entryPath];
    });
};

const assertFilesDoNotMatch = (relativePaths, pattern, label) => {
  const matches = relativePaths.flatMap((relativePath) => {
    const text = readText(relativePath);
    return pattern.test(text) ? [relativePath] : [];
  });
  assert.deepEqual(matches, [], `${label} should not contain ${pattern}`);
};

const workflow = readText(".github/workflows/deps-cli.yml");
assertNotContains(workflow, "deps/cli", "deps-cli workflow");
assert.match(workflow, /cli\/\*\*/, "workflow should watch cli/ sources");
assert.match(workflow, /pnpm cli:release/, "workflow should build new CLI");
assertNotContains(workflow, "clojure -M:cljs release logseq-cli", "deps-cli workflow");
assert.match(workflow, /pnpm db-worker-node:release:bundle/, "workflow should build bundled db-worker-node runtime");
assert.match(workflow, /node scripts\/prepare-cli-package\.mjs/, "workflow should prepare publish package");
assert.match(workflow, /working-directory: dist\/cli-package/, "workflow should publish prepared package");

const buildWorkflow = readText(".github/workflows/build.yml");
assert.match(buildWorkflow, /pnpm cli:release/, "db graph workflow should build release CLI");
assertNotContains(buildWorkflow, "clojure -M:cljs release logseq-cli", "db graph workflow");
assert.match(buildWorkflow, /pnpm db-worker-node:release:bundle/, "db graph workflow should build release db-worker-node runtime");
assert.match(buildWorkflow, /pnpm --dir dist\/cli-package install --prod --ignore-workspace/, "db graph workflow should install prepared CLI package dependencies");
assert.match(buildWorkflow, /libsecret-1-0/, "db graph workflow should install keytar's Linux runtime dependency");
assert.match(buildWorkflow, /pnpm --dir dist\/cli-package pack --pack-destination \.\.\//, "db graph workflow should verify the prepared CLI package with pnpm pack");
assertNotContains(buildWorkflow, "create_graph_with_schema_org.cljs ./cli-root/graphs/schema-graph --subset", "db graph workflow");
assert.match(buildWorkflow, /node dist\/cli-package\/dist\/logseq\.js --root-dir scripts\/cli-root/, "db graph workflow should test packaged CLI");
assert.match(buildWorkflow, /node dist\/cli-package\/dist\/logseq\.js --root-dir scripts\/cli-root.+--timeout-ms 3000/, "db graph workflow should use a 3s CLI request timeout for packaged CLI graph commands");
assert.match(buildWorkflow, /--graph schema-graph --timeout-ms 120000/, "db graph workflow should allow the full schema graph validation to finish in CI");
assertNotContains(buildWorkflow, "clojure -M:cljs compile logseq-cli", "db graph workflow");
assertNotContains(buildWorkflow, "pnpm db-worker-node:compile:bundle", "db graph workflow");

const desktopReleaseWorkflow = readText(".github/workflows/build-desktop-release.yml");
assert.match(
  desktopReleaseWorkflow,
  /OCAML_VERSION: '5\.4\.0'/,
  "desktop release workflow should define the OCaml version used by cli/",
);
assert.match(
  desktopReleaseWorkflow,
  /uses: ocaml\/setup-ocaml@v3/,
  "desktop release workflow should set up OCaml before building cli/",
);
assert.match(
  desktopReleaseWorkflow,
  /working-directory: cli\s+run: opam install \. --deps-only --with-test --yes/,
  "desktop release workflow should install cli/ OCaml deps",
);
assert.match(
  desktopReleaseWorkflow,
  /pnpm --dir cli install --frozen-lockfile --ignore-workspace/,
  "desktop release workflow should install cli/ pnpm deps",
);
assert.match(
  desktopReleaseWorkflow,
  /opam exec -- pnpm cli:release/,
  "desktop release workflow should build and stage the OCaml CLI",
);
assert.ok(
  desktopReleaseWorkflow.indexOf("opam exec -- pnpm cli:release") <
    desktopReleaseWorkflow.indexOf("pnpm desktop:prepare-runtime-js"),
  "desktop release workflow should stage the CLI before preparing desktop runtime scripts",
);
assertNotContains(
  desktopReleaseWorkflow,
  "clojure -M:cljs release logseq-cli",
  "desktop release workflow",
);
assert.doesNotMatch(
  desktopReleaseWorkflow,
  /name: Signing By Apple Developer ID\s+if: \$\{\{ github\.repository == 'logseq\/logseq' \}\}/,
  "desktop release workflow Apple signing should not exclude forks",
);
assert.match(
  desktopReleaseWorkflow,
  /HAS_APPLE_SIGNING: \$\{\{ secrets\.APPLE_CERTIFICATES_P12 != '' \}\}/,
  "desktop release workflow should expose only a non-secret signing availability flag at job scope",
);
assert.doesNotMatch(
  desktopReleaseWorkflow,
  /^\s{6}APPLE_CERTIFICATES_P12: \$\{\{ secrets\.APPLE_CERTIFICATES_P12 \}\}$/m,
  "desktop release workflow should not expose the signing certificate at job scope",
);
assert.match(
  desktopReleaseWorkflow,
  /p12-file-base64: \$\{\{ secrets\.APPLE_CERTIFICATES_P12 \}\}/,
  "desktop release workflow should pass the signing certificate only to the import step",
);
assert.match(
  desktopReleaseWorkflow,
  /if: \$\{\{ env\.HAS_APPLE_SIGNING == 'true' \}\}/,
  "desktop release workflow should import Apple certificates when a fork configures them",
);
assert.match(
  desktopReleaseWorkflow,
  /permissions:\s+contents: read/,
  "desktop release workflow should default to read-only repository permissions",
);
for (const job of ["nightly-release", "release"]) {
  assert.match(
    desktopReleaseWorkflow,
    new RegExp(`${job}:\\n[\\s\\S]*?permissions:\\n\\s+contents: write`),
    `${job} should receive write permission explicitly`,
  );
}
assert.match(
  desktopReleaseWorkflow,
  /rtc-release-gate:[\s\S]*?pnpm cljs:test[\s\S]*?pnpm --dir deps\/db-sync test[\s\S]*?pnpm --dir deps\/db-sync test:large-op-128m/,
  "desktop release workflow should gate packaging on client and server RTC tests",
);
assert.match(
  desktopReleaseWorkflow,
  /push:[\s\S]*?selfhost\/cloudflare-rtc/,
  "desktop release workflow should automatically rehearse every pushed selfhost commit",
);
assert.match(
  desktopReleaseWorkflow,
  /source-preflight:[\s\S]*?pnpm desktop:release-preflight:quick -- --strict/,
  "desktop release workflow should fail on source and environment drift before expensive builds",
);
assert.match(
  desktopReleaseWorkflow,
  /release-rehearsal-gate:[\s\S]*?Verify successful push rehearsal[\s\S]*?head_sha: sha/,
  "stable and beta releases should require a successful rehearsal of the exact commit",
);
assert.match(
  desktopReleaseWorkflow,
  /release-assets-preflight:[\s\S]*?pattern: logseq-\*-builds[\s\S]*?merge-multiple: true[\s\S]*?Verify complete desktop release asset set[\s\S]*?verify-desktop-release-assets\.mjs/,
  "push rehearsals should merge and validate the exact complete six-platform asset set",
);
for (const job of ["nightly-release", "release"]) {
  assert.match(
    desktopReleaseWorkflow,
    new RegExp(`${job}:\\n[\\s\\S]*?needs: \\[ release-assets-preflight \\]`),
    `${job} should publish only after the aggregate asset preflight`,
  );
}
assert.match(
  desktopReleaseWorkflow,
  /Verify packaged desktop/g,
  "desktop release workflow should verify packaged applications",
);
assert.match(
  desktopReleaseWorkflow,
  /node scripts\/verify-desktop-release-assets\.mjs[\s\S]*?--write-checksums/,
  "desktop release workflow should validate the complete asset set before publishing",
);
assert.equal(
  desktopReleaseWorkflow.match(/pnpm electron:verify-updater-provider/g)
    ?.length,
  6,
  "all six desktop builders should rehearse the updater provider contract",
);
assert.match(
  desktopReleaseWorkflow,
  /prerelease: \$\{\{ !contains\(steps\.ref\.outputs\.version, '-selfhost\.'\) && github\.event\.inputs\.is-pre-release \}\}/,
  "selfhost versions must be GitHub production releases so /releases/latest can discover them",
);
assertNotContains(
  desktopReleaseWorkflow,
  "sha256sum *.apk",
  "desktop release workflow",
);
assert.match(
  desktopReleaseWorkflow,
  /clojure -M:test release db-sync-backup-memory-test[\s\S]*?node --expose-gc --max-old-space-size=128[\s\S]*?static\/db-sync-backup-memory-test\.js/,
  "RTC release gate should exercise durable client backup under a 128 MB heap",
);
assert.match(
  desktopReleaseWorkflow,
  /frontend\\\.handler\\\.db-based\\\.\(rtc-background-tasks\|sync\)-test/,
  "RTC release gate should include suspend, resume, and background trigger coverage",
);
assert.match(
  desktopReleaseWorkflow,
  /frontend\\\.worker\\\.\(db-core\|db-sync\|db-sync-sim\|db-worker\|pipeline\|platform-node\|state\)-test/,
  "RTC release gate should execute db-worker import and cleanup coverage",
);
assert.match(
  desktopReleaseWorkflow,
  /logseq\\\.cli\\\.command\\\.sync-test/,
  "RTC release gate should include CLI repair-required behavior",
);
assert.match(
  desktopReleaseWorkflow,
  /rtc-release-gate:[\s\S]*?pnpm --dir deps\/db-sync install --frozen-lockfile[\s\S]*?pnpm --dir deps\/db-sync test/,
  "RTC release gate should install the isolated db-sync dependency tree before testing it",
);
assert.match(
  desktopReleaseWorkflow,
  /rtc-release-gate:[\s\S]*?pnpm --dir deps\/db-sync release[\s\S]*?pnpm --dir deps\/db-sync build:api-docs[\s\S]*?pnpm exec wrangler deploy --dry-run --env=""/,
  "RTC release gate should build all Worker assets and dry-run the production bundle",
);
assert.match(
  desktopReleaseWorkflow,
  /name: Update APP Version\s+run: pnpm pkg set version="\$\{\{ steps\.ref\.outputs\.version \}\}"\s+working-directory: \.\/static/,
  "desktop release workflow should update any previous static package version",
);
assert.equal(
  dbSyncPackage.packageManager,
  "pnpm@10.33.0",
  "db-sync should pin the same pnpm version used by CI",
);
assert.equal(
  dbSyncPackage.devDependencies.wrangler,
  "4.113.0",
  "db-sync deployments should use a reproducible Wrangler version",
);
assert.match(
  dbSyncWorkspace,
  /allowBuilds:[\s\S]*"@sentry\/cli": true[\s\S]*better-sqlite3: true[\s\S]*esbuild: true[\s\S]*sharp: true[\s\S]*workerd: true/,
  "db-sync should explicitly allow only its required native install scripts",
);
assert.match(
  desktopReleaseWorkflow,
  /compile-cljs:[\s\S]*?needs: \[ rtc-release-gate, rtc-browser-e2e \]/,
  "desktop compilation should wait for both RTC release and browser E2E gates",
);
assert.doesNotMatch(
  desktopReleaseWorkflow,
  /actions\/setup-python@v[1-4]\b/,
  "desktop release workflow should not use an unsupported setup-python runtime",
);
assert.match(
  desktopReleaseWorkflow,
  /rtc-release-gate:[\s\S]*?persist-credentials: false/,
  "RTC test checkout should not retain repository credentials",
);
assert.match(
  desktopReleaseWorkflow,
  /spctl --assess --type execute/,
  "desktop release workflow should verify notarized apps with Gatekeeper",
);
assert.match(
  desktopReleaseWorkflow,
  /xcrun stapler validate/,
  "desktop release workflow should verify stapled notarization tickets",
);
assert.equal(
  desktopReleaseWorkflow.match(
    /build-project-update-helper\.mjs/g,
  )?.length,
  2,
  "both macOS builders should embed the project updater helper",
);
assert.match(
  desktopReleaseWorkflow,
  /LOGSEQ_MACOS_UPDATE_ED25519_PRIVATE_KEY_BASE64/,
  "the macOS release gate should require the external project signing key",
);
assert.equal(
  desktopReleaseWorkflow.match(
    /release-gate-source\/resources\/selfhost-updater-version\.mjs macos-metadata-name/g,
  )?.length,
  2,
  "both macOS builders should execute the checked-out metadata helper",
);
assert.equal(
  desktopReleaseWorkflow.match(
    /sign-macos-project-update\.mjs/g,
  )?.length,
  2,
  "both macOS builders should sign the project update manifest",
);
assert.equal(
  desktopReleaseWorkflow.match(
    /verify-project-signed-macos-update\.mjs/g,
  )?.length,
  2,
  "both macOS builders should verify the signed manifest against the ZIP",
);
assert.doesNotMatch(
  desktopReleaseWorkflow,
  /node release-gate-source\/scripts\/verify-macos-updater-signature\.mjs/,
  "the .5 workflow must not run the .4 designated requirement as a release gate",
);
assert.equal(
  desktopReleaseWorkflow.match(
    /resources\/updater\/legacy-macos\/latest-(?:arm64|x64)-mac\.yml/g,
  )?.length,
  2,
  "both macOS releases should carry pinned legacy metadata sentinels",
);
assert.match(
  macosUpdaterSignaturePolicy,
  /manual-migration[\s\S]*revision > 5[\s\S]*project-signed[\s\S]*verifyProjectSignedMacosUpdate/,
  "the signature policy should route post-bootstrap releases through project signatures",
);
assert.doesNotMatch(
  macosUpdaterSignaturePolicy,
  /requireDeveloperIdBaseline|macos-updater-signed-baseline/,
  "the project signature policy must not require an Apple signing baseline",
);
assert.match(
  desktopReleaseWorkflow,
  /electron:make-unsigned --mac dmg zip --x64/,
  "fork desktop release workflow should build an unsigned macOS x64 app",
);
assert.match(
  desktopReleaseWorkflow,
  /Build\/Release Electron App for x64[\s\S]*?pnpm install --frozen-lockfile --ignore-workspace[\s\S]*?pnpm rebuild:all/,
  "macOS x64 dependencies should be installed outside the root workspace",
);
assert.match(
  desktopReleaseWorkflow,
  /electron:make-unsigned --mac dmg zip --arm64/,
  "fork desktop release workflow should build an unsigned macOS arm64 app",
);
assert.match(
  desktopReleaseWorkflow,
  /Fetch deps[\s\S]*?pnpm install --frozen-lockfile --ignore-workspace --config\.supportedArchitectures\.os=darwin --config\.supportedArchitectures\.cpu=arm64/,
  "macOS arm64 dependencies should be installed outside the root workspace",
);
assert.match(
  desktopReleaseWorkflow,
  /ELECTRON_RUN_AS_NODE=1/,
  "fork desktop release workflow should smoke-test the packaged Electron runtime",
);
assert.match(
  unsignedDesktopBuilder,
  /electron-builder\.unsigned\.yml/,
  "unsigned desktop builds should use the ad-hoc signing configuration",
);
assert.match(
  unsignedDesktopConfig,
  /afterSign: \.\/electron-builder-adhoc-after-sign\.cjs/,
  "unsigned macOS builds should re-sign the completed application bundle",
);
assert.match(
  adhocAfterSign,
  /"--sign",\s+"-"/,
  "the fork afterSign hook should use an ad-hoc identity",
);
assert.match(
  adhocAfterSign,
  /entitlements\.local-signed\.plist/,
  "the fork afterSign hook should disable library validation",
);
assert.deepEqual(
  macosUpdaterBaseline,
  {
    repository: "cfenglv/logseq",
    version: "2.0.1-selfhost.4",
    architectures: {
      arm64: {
        metadata: "latest-arm64-mac.yml",
        metadataSha256:
          "2dd11f39538c801cf2356a40e753b8f6a9963641df6951e13ed3493b1c5ed705",
        zip: "Logseq-darwin-arm64-2.0.1-selfhost.4.zip",
        zipSha256:
          "6668bc87712d849374b5de823cce6bac2c32aa93486dd88ea9fcad8c82c41643",
      },
      x64: {
        metadata: "latest-x64-mac.yml",
        metadataSha256:
          "7b35999d6cd7edcd54b08944bca4112abb39e6fc2f12b7d2f602a2c35cdb8ec0",
        zip: "Logseq-darwin-x64-2.0.1-selfhost.4.zip",
        zipSha256:
          "48aef39093d0395c692c78a75a4e4cbb00a9d728e118e11f04c86b1783f3ef90",
      },
    },
  },
  "macOS updater regression reproducer should pin the published arm64 and x64 baseline assets",
);
assert.match(
  projectSignedMacosUpdaterVerifier,
  /loadProjectSigningPolicy[\s\S]*projectUpdatePayload[\s\S]*verify\(/,
  "future physical updater gates should require the fixed project key signature",
);
for (const requiredVerifierContract of [
  "LOGSEQ_UPDATER_BASELINE_ZIP",
  "LOGSEQ_UPDATER_BASELINE_METADATA",
  "published baseline metadata",
  "candidate download payload",
  "Squirrel designated requirement authorization",
  "Squirrel.framework",
  "Squirrel physical install",
  "target-after=",
]) {
  assertContains(
    macosUpdaterSignatureVerifier,
    requiredVerifierContract,
    "macOS updater signature verifier",
  );
}
assert.match(
  desktopReleasePreflight,
  /tracked worktree changes must be committed before release/,
  "desktop preflight should reject dirty tracked release inputs in strict mode",
);
assert.match(
  desktopReleasePreflight,
  /resources\/verify-updater-provider\.mjs/,
  "desktop preflight should require the updater provider rehearsal",
);
assert.match(
  fullDesktopReleasePreflight,
  /verify updater provider contract[\s\S]*?electron:verify-updater-provider/,
  "the full desktop preflight should execute the real updater provider rehearsal",
);
assert.match(
  electronUpdater,
  /set! \(\.-allowPrerelease autoUpdater\) allow-prerelease\?/,
  "the Electron runtime should override prerelease discovery for selfhost versions",
);
assert.match(
  electronUpdaterConfig,
  /-selfhost\(\?:\\\.\|\$\)/,
  "the updater contract should identify only selfhost SemVer prereleases",
);
assert.match(
  updaterProviderVerifier,
  /ERR_UPDATER_NO_PUBLISHED_VERSIONS/,
  "the updater rehearsal should preserve the original channel mismatch as a regression case",
);
assert.match(
  updaterProviderVerifier,
  /across six platform\/architecture contracts/,
  "the updater rehearsal should cover all six desktop targets",
);
assert.match(
  desktopReleaseAssetVerifier,
  /macosUpdaterMetadataName/,
  "release asset verification should require the versioned macOS metadata names",
);
for (const needle of [
  'path.join(resourcesDir, "app-update.yml")',
  '["provider", /^provider:\\s*github\\s*$/m]',
  '["owner", /^owner:\\s*cfenglv\\s*$/m]',
  '["repo", /^repo:\\s*logseq\\s*$/m]',
]) {
  assertContains(
    packagedDesktopVerifier,
    needle,
    "packaged desktop updater feed verification",
  );
}
assert.match(
  desktopSettings,
  /openExternal fv\/releases-url/,
  "selfhost updater errors should link to the fork release page",
);
assert.match(
  desktopReleaseAssetVerifier,
  /release artifact set mismatch/,
  "desktop release asset verification should reject incomplete or unexpected assets",
);
for (const [format, pattern] of [
  ["PE", /0x8664[\s\S]*?0xaa64/],
  ["ELF", /machine === 62[\s\S]*?machine === 183/],
  ["Mach-O", /0x01000007[\s\S]*?0x0100000c/],
]) {
  assert.match(
    packagedDesktopVerifier,
    pattern,
    `packaged desktop verification should understand ${format} binaries`,
  );
}

const shadowCljs = readText("shadow-cljs.edn");
assertNotContains(shadowCljs, ":logseq-cli", "shadow-cljs.edn");

assertCliReleaseCommand(rootPackage.scripts?.["cli:release"], "cli:release");
for (const [scriptName, command] of Object.entries(rootPackage.scripts ?? {})) {
  assertRootScriptDoesNotBuildShadowCli(scriptName, command);
}

const gulpfile = readText("gulpfile.js");
assert.match(
  gulpfile,
  /pnpm cli:release/,
  "Electron maker preparation should stage the CLI runtime",
);
assert.ok(
  gulpfile.indexOf("pnpm cli:release") <
    gulpfile.indexOf("pnpm desktop:prepare-runtime-js"),
  "Electron maker preparation should stage the CLI before preparing desktop runtime scripts",
);

const stageScriptPath = path.join(repoRoot, "scripts", "stage-cli-runtime.mjs");
assert.equal(
  fs.existsSync(stageScriptPath),
  true,
  "scripts/stage-cli-runtime.mjs should exist",
);
const stageScript = readText("scripts/stage-cli-runtime.mjs");
assert.match(
  stageScript,
  /cli[\\/]_build[\\/]default[\\/]dist[\\/]logseq-cli\.js/,
  "stage script should read cli/_build/default/dist/logseq-cli.js",
);
assert.match(
  stageScript,
  /static[\\/]logseq-cli\.js/,
  "stage script should write static/logseq-cli.js",
);
for (const needle of ["SHADOW_IMPORT", ".shadow-cljs", "cljs-runtime"]) {
  assert.match(
    stageScript,
    new RegExp(needle.replace(".", "\\.")),
    `stage script should reject ${needle}`,
  );
}

const runStageCliRuntime = (fixtureRoot) =>
  execFileSync(process.execPath, ["scripts/stage-cli-runtime.mjs"], {
    cwd: repoRoot,
    env: {
      ...process.env,
      LOGSEQ_STAGE_CLI_RUNTIME_REPO_ROOT: fixtureRoot,
    },
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
  });

const makeStageFixture = () =>
  mkdtempSync(path.join(os.tmpdir(), "logseq-stage-cli-"));

{
  const fixtureRoot = makeStageFixture();
  assert.throws(
    () => runStageCliRuntime(fixtureRoot),
    /cli\/_build\/default\/dist\/logseq-cli\.js/,
    "stage script should fail when the cli/ bundle is missing",
  );
}

{
  const fixtureRoot = makeStageFixture();
  const sourcePath = path.join(
    fixtureRoot,
    "cli",
    "_build",
    "default",
    "dist",
    "logseq-cli.js",
  );
  fs.mkdirSync(path.dirname(sourcePath), { recursive: true });
  fs.writeFileSync(sourcePath, "console.log('SHADOW_IMPORT');\n");
  assert.throws(
    () => runStageCliRuntime(fixtureRoot),
    /SHADOW_IMPORT/,
    "stage script should reject Shadow runtime markers",
  );
}

{
  const fixtureRoot = makeStageFixture();
  const sourcePath = path.join(
    fixtureRoot,
    "cli",
    "_build",
    "default",
    "dist",
    "logseq-cli.js",
  );
  const stagedPath = path.join(fixtureRoot, "static", "logseq-cli.js");
  const staleMapPath = path.join(fixtureRoot, "static", "logseq-cli.js.map");
  fs.mkdirSync(path.dirname(sourcePath), { recursive: true });
  fs.mkdirSync(path.dirname(staleMapPath), { recursive: true });
  fs.writeFileSync(sourcePath, "console.log('new cli');\n");
  fs.writeFileSync(staleMapPath, "{}\n");
  const output = runStageCliRuntime(fixtureRoot);
  assert.equal(
    fs.readFileSync(stagedPath, "utf8"),
    "console.log('new cli');\n",
  );
  assert.equal(
    fs.existsSync(staleMapPath),
    false,
    "stage script should remove stale root sourcemap",
  );
  assert.match(output, /cli\/_build\/default\/dist\/logseq-cli\.js/);
  assert.match(output, /static\/logseq-cli\.js/);
}

{
  const fixtureRoot = makeStageFixture();
  const sourcePath = path.join(
    fixtureRoot,
    "cli",
    "_build",
    "default",
    "dist",
    "logseq-cli.js",
  );
  const stagedPath = path.join(fixtureRoot, "static", "logseq-cli.js");
  fs.mkdirSync(path.dirname(sourcePath), { recursive: true });
  fs.mkdirSync(path.dirname(stagedPath), { recursive: true });
  fs.writeFileSync(sourcePath, "console.log('first');\n");
  fs.writeFileSync(stagedPath, "console.log('old');\n");
  fs.chmodSync(stagedPath, 0o444);
  runStageCliRuntime(fixtureRoot);
  assert.equal(
    fs.readFileSync(stagedPath, "utf8"),
    "console.log('first');\n",
    "stage script should overwrite an existing read-only staged file",
  );
}

assert.equal(
  fs.existsSync(path.join(repoRoot, "deps/cli/package.json")),
  false,
  "old deps/cli package should be removed",
);

const depsEdn = readText("deps.edn");
assertNotContains(depsEdn, 'logseq/cli', "deps.edn");
assertNotContains(depsEdn, '"deps/cli"', "deps.edn");

const bbEdn = readText("bb.edn");
assertNotContains(bbEdn, "legacy cli", "bb.edn");
assertNotContains(bbEdn, "../cli/src", "bb.edn");

const lintTask = readText("scripts/src/logseq/tasks/dev/lint.clj");
assertNotContains(lintTask, '"deps/cli"', "lint task");

const lintDepsTask = readText("scripts/src/logseq/tasks/dev/lint_test_deps.clj");
assertNotContains(lintDepsTask, '"deps/cli"', "lint/test deps task");

for (const dependencyName of zvecOptionalRuntimeDependencies) {
  assert.equal(
    rootPackage.dependencies?.[dependencyName],
    undefined,
    `${dependencyName} should not be a hard root runtime dependency`,
  );
  assert.ok(
    rootPackage.optionalDependencies?.[dependencyName],
    `${dependencyName} should be an optional root runtime dependency`,
  );
  assert.equal(
    desktopPackage.dependencies?.[dependencyName],
    undefined,
    `${dependencyName} should not be a hard desktop runtime dependency`,
  );
  assert.ok(
    desktopPackage.optionalDependencies?.[dependencyName],
    `${dependencyName} should be an optional desktop runtime dependency`,
  );
}

assert.equal(
  fs.existsSync(path.join(repoRoot, "src/main/logseq/cli/common/mcp/server.cljs")),
  false,
  "CLI sources should not include an MCP server",
);

assertFilesDoNotMatch(
  [
    ...filesUnder("src/main/logseq/cli"),
    ...filesUnder("src/test/logseq/cli"),
    "scripts/prepare-cli-package.mjs",
  ],
  /logseq\.cli\.common\.mcp|modelcontextprotocol|zod\/v3|mcp-server/,
  "CLI sources and package preparation",
);

const prepareCliPackageScript = readText("scripts/prepare-cli-package.mjs");
assert.match(
  prepareCliPackageScript,
  /static[\\/]logseq-cli\.js/,
  "package preparation should package the staged CLI",
);
assert.match(
  prepareCliPackageScript,
  /cli[\\/]_build[\\/]default[\\/]dist[\\/]logseq-cli\.js/,
  "package preparation should compare the staged CLI against the cli/ bundle",
);
for (const needle of ["SHADOW_IMPORT", ".shadow-cljs", "cljs-runtime"]) {
  assert.match(
    prepareCliPackageScript,
    new RegExp(needle.replace(".", "\\.")),
    `package preparation should reject ${needle}`,
  );
}

const prepareDesktopRuntimeScript = readText(
  "scripts/prepare-desktop-runtime-js.mjs",
);
assert.match(
  prepareDesktopRuntimeScript,
  /static[\\/]logseq-cli\.js/,
  "desktop runtime preparation should read the staged CLI",
);
assert.match(
  prepareDesktopRuntimeScript,
  /static[\\/]js[\\/]logseq-cli\.js/,
  "desktop runtime preparation should write static/js/logseq-cli.js",
);
assert.match(
  prepareDesktopRuntimeScript,
  /cli[\\/]_build[\\/]default[\\/]dist[\\/]logseq-cli\.js/,
  "desktop runtime preparation should compare the staged CLI against the cli/ bundle",
);
assert.match(
  prepareDesktopRuntimeScript,
  /fs\.chmod\(to, stats\.mode \| 0o200\)/,
  "desktop runtime preparation should overwrite read-only staged runtime files",
);
assertNotContains(
  prepareDesktopRuntimeScript,
  'fs.rm(path.join(staticDir, "logseq-cli.js")',
  "desktop runtime preparation should keep root staged CLI available",
);
assertNotContains(
  prepareDesktopRuntimeScript,
  'fs.rm(path.join(staticDir, "db-worker-node.js")',
  "desktop runtime preparation should keep root staged db-worker-node available",
);

execFileSync(process.execPath, ["scripts/prepare-cli-package.mjs"], {
  cwd: repoRoot,
  stdio: "pipe",
});

const packageRoot = path.join(repoRoot, "dist/cli-package");
const packageJson = JSON.parse(
  fs.readFileSync(path.join(packageRoot, "package.json"), "utf8"),
);

assert.equal(packageJson.name, "@logseq/cli");
assert.equal(packageJson.bin.logseq, "dist/logseq.js");
assert.equal(packageJson.private, undefined);
assert.equal(packageJson.dependencies?.["@modelcontextprotocol/sdk"], undefined);
assert.equal(packageJson.dependencies?.zod, undefined);
assert.ok(packageJson.dependencies?.["@js-joda/core"], "publish package should include @js-joda/core for release artifacts");
assert.ok(packageJson.dependencies?.keytar, "publish package should include keytar for db-worker-node");
assert.ok(packageJson.dependencies?.["string-width"], "publish package should include string-width for CLI rendering");
for (const dependencyName of zvecOptionalRuntimeDependencies) {
  assert.equal(
    packageJson.dependencies?.[dependencyName],
    undefined,
    `${dependencyName} should not be a hard publish package dependency`,
  );
  assert.ok(
    packageJson.optionalDependencies?.[dependencyName],
    `${dependencyName} should be an optional publish package dependency`,
  );
}
assert.deepEqual(packageJson.pnpm?.onlyBuiltDependencies, [
  "@zvec/zvec",
  "better-sqlite3",
  "keytar",
]);
assert.deepEqual(packageJson.files, [
  "dist/logseq.js",
  "static/logseq-cli.js",
  "static/js/db-worker-node.js",
  "static/js/db-worker-node-assets.json",
  ".agents/skills/logseq-cli/SKILL.md",
]);

for (const relativePath of packageJson.files) {
  assert.equal(
    fs.existsSync(path.join(packageRoot, relativePath)),
    true,
    `publish package should include ${relativePath}`,
  );
}

assertNoShadowRuntime(readText("static/logseq-cli.js"), "root CLI release artifact");
assertNoShadowRuntime(
  fs.readFileSync(path.join(packageRoot, "static/logseq-cli.js"), "utf8"),
  "publish package CLI release artifact",
);
