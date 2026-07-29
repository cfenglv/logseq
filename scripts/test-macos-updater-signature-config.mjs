#!/usr/bin/env node

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { updaterSignatureGatePlan } from "./run-macos-updater-signature-policy.mjs";
import {
  classifyShipItOutcome,
  UpdaterSignatureGateError,
} from "./verify-macos-updater-signature.mjs";
import {
  compareSelfhostProjectVersions,
  parseSelfhostProjectVersion,
  projectUpdateKeyId,
} from "../resources/project-updater-signature.mjs";

const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const read = (relativePath) =>
  fs.readFileSync(path.join(repoRoot, relativePath), "utf8");

const workflow = read(".github/workflows/build-desktop-release.yml");
const verifier = read("scripts/verify-macos-updater-signature.mjs");
const electronUpdater = read("src/electron/electron/updater.cljs");
const electronCore = read("src/electron/electron/core.cljs");
const rendererIpc = read("src/main/electron/ipc.cljs");
const rendererHandler = read("src/main/frontend/handler.cljs");
const header = read("src/main/frontend/components/header.cljs");
const settings = read("src/main/frontend/components/settings.cljs");
const nativeHelper = read("resources/macos-project-updater/ProjectUpdater.swift");
const baseline = JSON.parse(read("scripts/fixtures/macos-updater-baseline.json"));
const helperPath = path.join(repoRoot, "resources", "selfhost-updater-version.mjs");
const runHelper = (...args) => {
  const result = spawnSync(process.execPath, [helperPath, ...args], {
    encoding: "utf8",
  });
  assert.equal(result.status, 0, result.stderr);
  return result.stdout.trim();
};
const runProjectUpdateScript = (script, version) =>
  spawnSync(
    process.execPath,
    [
      path.join(repoRoot, "scripts", script),
      "--arch",
      "arm64",
      "--version",
      version,
      "--archive",
      path.join(repoRoot, "does-not-exist.zip"),
      "--metadata",
      path.join(repoRoot, "does-not-exist.yml"),
    ],
    {
      encoding: "utf8",
      env: {
        ...process.env,
        LOGSEQ_MACOS_UPDATE_ED25519_PRIVATE_KEY_BASE64: "",
      },
    },
  );

const cases = [
  [
    "published .4 arm64 and x64 inputs are SHA-256 pinned",
    () =>
      assert.deepEqual(baseline, {
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
      }),
  ],
  [
    ".4 to .5 signature rejection remains an independent pinned reproducer",
    () => {
      assert.ok(
        fs.existsSync(
          path.join(
            repoRoot,
            "scripts",
            "reproduce-macos-updater-signature-regression.mjs",
          ),
        ),
      );
      assert.doesNotMatch(
        workflow,
        /Verify macOS updater installation compatibility/,
      );
      assert.doesNotMatch(
        workflow,
        /node release-gate-source\/scripts\/verify-macos-updater-signature\.mjs/,
      );
    },
  ],
  [
    "workflow helper produces legacy and v2 metadata names when executed",
    () => {
      assert.equal(
        runHelper("macos-metadata-name", "2.0.1-selfhost.4", "arm64"),
        "latest-arm64-mac.yml",
      );
      assert.equal(
        runHelper("macos-metadata-name", "2.0.1-selfhost.4", "x64"),
        "latest-x64-mac.yml",
      );
      assert.equal(
        runHelper("macos-metadata-name", "2.0.1-selfhost.5", "arm64"),
        "selfhost-macos-v2-arm64-mac.yml",
      );
      assert.equal(
        runHelper("macos-metadata-name", "2.0.1-selfhost.5", "x64"),
        "selfhost-macos-v2-x64-mac.yml",
      );
      assert.equal(runHelper("selfhost-revision", "2.0.1-selfhost.5"), "5");
      assert.equal(
        runHelper("macos-metadata-name", "2.0.1-selfhost.5-alpha.nightly.20260729", "arm64"),
        "selfhost-macos-v2-arm64-mac.yml",
      );
      assert.equal(
        workflow.match(
          /release-gate-source\/resources\/selfhost-updater-version\.mjs macos-metadata-name/g,
        )?.length,
        2,
      );
    },
  ],
  [
    "project updater version ordering supports strict dated nightlies",
    () => {
      const earlier = "2.0.1-selfhost.5-alpha.nightly.20260728";
      const later = "2.0.1-selfhost.5-alpha.nightly.20260729";
      const stable = "2.0.1-selfhost.5";
      assert.ok(compareSelfhostProjectVersions(earlier, later) < 0);
      assert.ok(compareSelfhostProjectVersions(later, stable) < 0);
      assert.ok(
        compareSelfhostProjectVersions(stable, "2.0.1-selfhost.6-alpha.nightly.20260701") < 0,
      );
      assert.equal(compareSelfhostProjectVersions(stable, stable), 0);
      for (const invalid of [
        "2.0.1-selfhost.5-alpha.nightly.20260229",
        "2.0.1-selfhost.5-alpha.nightly.20261301",
        "2.0.1-selfhost.5-alpha.nightly.2026072",
        "2.0.1-selfhost.5-nightly.20260729",
      ]) {
        assert.throws(() => parseSelfhostProjectVersion(invalid), /unsupported selfhost version/);
      }
      assert.doesNotThrow(() =>
        parseSelfhostProjectVersion("2.0.1-selfhost.5-alpha.nightly.20240229"),
      );
    },
  ],
  [
    "production signer and verifier accept only valid nightly versions",
    () => {
      const valid = "2.0.1-selfhost.5-alpha.nightly.20260729";
      const signer = runProjectUpdateScript("sign-macos-project-update.mjs", valid);
      assert.notEqual(signer.status, 0);
      assert.match(signer.stderr, /missing LOGSEQ_MACOS_UPDATE_ED25519_PRIVATE_KEY/);
      const verifier = runProjectUpdateScript("verify-project-signed-macos-update.mjs", valid);
      assert.notEqual(verifier.status, 0);
      assert.match(verifier.stderr, /ENOENT|no such file/i);

      for (const script of [
        "sign-macos-project-update.mjs",
        "verify-project-signed-macos-update.mjs",
      ]) {
        const invalid = runProjectUpdateScript(script, "2.0.1-selfhost.5-alpha.nightly.20260229");
        assert.notEqual(invalid.status, 0);
        assert.match(invalid.stderr, /unsupported selfhost version/);
      }
    },
  ],
  [
    "project updater key IDs retain the complete SHA-256 digest",
    () => {
      assert.match(projectUpdateKeyId(Buffer.alloc(32)), /^ed25519:[0-9a-f]{64}$/);
      assert.throws(() => projectUpdateKeyId(Buffer.alloc(31)), /32 raw Ed25519 bytes/);
      assert.doesNotMatch(read("scripts/build-project-update-helper.mjs"), /\.slice\(0,\s*16\)/);
      assert.doesNotMatch(read("resources/verify-packaged-desktop.mjs"), /\.slice\(0,\s*16\)/);
    },
  ],
  [
    ".5 remains manual and future releases route only through project signatures",
    () => {
      assert.equal(updaterSignatureGatePlan("2.0.1-selfhost.5").mode, "manual-migration");
      assert.deepEqual(updaterSignatureGatePlan("2.0.1-selfhost.6"), {
        mode: "project-signed",
      });
      assert.equal(workflow.match(/build-project-update-helper\.mjs/g)?.length, 2);
      assert.equal(workflow.match(/sign-macos-project-update\.mjs/g)?.length, 2);
      assert.equal(workflow.match(/verify-project-signed-macos-update\.mjs/g)?.length, 2);
      const policy = read("scripts/run-macos-updater-signature-policy.mjs");
      assert.match(policy, /verifyProjectSignedMacosUpdate/);
      assert.doesNotMatch(policy, /requireDeveloperIdBaseline/);
      assert.doesNotMatch(policy, /macos-updater-signed-baseline/);
    },
  ],
  [
    "native replacement uses one atomic macOS directory exchange",
    () => {
      assert.match(nativeHelper, /renameatx_np\(/);
      assert.match(nativeHelper, /RENAME_SWAP/);
      assert.doesNotMatch(
        nativeHelper,
        /moveItem\(at: arguments\.target/,
      );
      assert.doesNotMatch(nativeHelper, /project-update-backup/);
    },
  ],
  [
    "native replacement preserves quarantine before the atomic exchange",
    () => {
      assert.match(
        nativeHelper,
        /quarantineValue\(at: privateArchive\.path\)[\s\S]*\?\? quarantineValue\(at: arguments\.target\.path\)/,
      );
      assert.match(
        nativeHelper,
        /setQuarantine\(quarantine, at: candidate\.path\)[\s\S]*if arguments\.verifyOnly[\s\S]*atomicExchange/,
      );
      assert.doesNotMatch(nativeHelper, /removexattr|xattr -d|spctl --add/);
    },
  ],
  [
    "Electron waits for native verify-only success before quitting to install",
    () => {
      assert.match(electronUpdater, /<verify-project-update!/);
      assert.match(electronUpdater, /"--verify-only"/);
      assert.match(
        electronUpdater,
        /<verify-project-update! helper arguments\)[\s\S]*?\.then #\(<launch-project-update! helper arguments\)/,
      );
      assert.match(
        electronUpdater,
        /<launch-project-update![\s\S]*?\.once child "spawn"[\s\S]*?\.quit app/,
      );
      assert.match(
        electronUpdater,
        /\.once child "spawn"[\s\S]*?set-quit-dirty-state! false[\s\S]*?\.quit app/,
      );
      assert.match(
        electronUpdater,
        /\.catch[\s\S]*?"\[updater\/install\]"[\s\S]*?emit-update! win "error"/,
      );
      assert.match(
        electronCore,
        /:set-quit-dirty-state! #\(vreset! \*quit-dirty\? %\)/,
      );
      assert.doesNotMatch(
        rendererHandler,
        /set-quit-dirty-state/,
      );
    },
  ],
  [
    "both install buttons use one rejection-safe Promise handler",
    () => {
      assert.match(
        rendererIpc,
        /defn quit-and-install-new-version![\s\S]*?invoke "install-updates"[\s\S]*?p\/catch/,
      );
      assert.match(
        header,
        /:on-click #\(ipc\/quit-and-install-new-version!\)/,
      );
      assert.match(
        settings,
        /:on-click #\(ipc\/quit-and-install-new-version!\)/,
      );
      assert.doesNotMatch(settings, /ipc\/ipc :quitAndInstall/);
    },
  ],
  [
    "both legacy sentinels are exact published .4 metadata",
    () => {
      const expected = {
        arm64:
          "2dd11f39538c801cf2356a40e753b8f6a9963641df6951e13ed3493b1c5ed705",
        x64: "7b35999d6cd7edcd54b08944bca4112abb39e6fc2f12b7d2f602a2c35cdb8ec0",
      };
      for (const [arch, digest] of Object.entries(expected)) {
        const metadata = read(
          `resources/updater/legacy-macos/latest-${arch}-mac.yml`,
        );
        assert.match(metadata, /^version: 2\.0\.1-selfhost\.4$/m);
        assert.doesNotMatch(metadata, /2\.0\.1-selfhost\.5/);
        assert.equal(
          createHash("sha256").update(metadata).digest("hex"),
          digest,
        );
      }
      assert.equal(
        workflow.match(/resources\/updater\/legacy-macos\/latest-/g)?.length,
        2,
      );
    },
  ],
  [
    "local baseline overrides still flow through pinned hash checks",
    () => {
      assert.match(verifier, /LOGSEQ_UPDATER_BASELINE_ZIP/);
      assert.match(verifier, /LOGSEQ_UPDATER_BASELINE_METADATA/);
      assert.match(
        verifier,
        /await assertHash\("sha256", metadata, architecture\.metadataSha256\)/,
      );
      assert.match(
        verifier,
        /await assertHash\("sha256", zip, architecture\.zipSha256\)/,
      );
    },
  ],
  [
    "metadata and payload success are separate from installation success",
    () => {
      assert.match(verifier, /"candidate metadata"/);
      assert.match(verifier, /"candidate download payload"/);
      assert.match(verifier, /"candidate generic signature"/);
      assert.match(
        verifier,
        /"Squirrel designated requirement authorization"/,
      );
      assert.match(verifier, /"Squirrel physical install"/);
      assert.match(verifier, /published baseline Developer ID identity/);
      assert.match(verifier, /published baseline Gatekeeper acceptance/);
      assert.match(verifier, /published baseline stapled notarization/);
      assert.match(verifier, /arch === "x64" \? "x86_64" : arch/);
    },
  ],
  [
    "compatible future identity passes only after ShipIt replaces the target",
    () => {
      assert.match(
        verifier,
        /ShipIt exit=0 target-before=\$\{before\} target-after=\$\{after\}/,
      );
      assert.match(
        verifier,
        /ShipIt request fixture was unreadable or invalid; this is not an updater signature regression/,
      );
      assert.doesNotMatch(verifier, /Signature=adhoc.*throw/s);
    },
  ],
  [
    "ShipIt request read errors are fixture failures, never signature failures",
    () =>
      assert.throws(
        () =>
          classifyShipItOutcome({
            status: 1,
            log: "SQRLShipItRequestErrorDomain Code=2 Could not read update request",
            before: "2.0.1-selfhost.4",
            after: "2.0.1-selfhost.4",
            newVersion: "2.0.1-selfhost.5",
          }),
        (error) =>
          error instanceof UpdaterSignatureGateError &&
          error.kind === "fixture-error" &&
          /not an updater signature regression/.test(error.message),
      ),
  ],
  [
    "a compatible ShipIt replacement is green",
    () =>
      assert.equal(
        classifyShipItOutcome({
          status: 0,
          log: "Installation completed successfully",
          before: "2.0.1-selfhost.5",
          after: "2.0.1-selfhost.6",
          newVersion: "2.0.1-selfhost.6",
        }),
        "ShipIt exit=0 target-before=2.0.1-selfhost.5 target-after=2.0.1-selfhost.6",
      ),
  ],
];

let passed = 0;
for (const [name, test] of cases) {
  test();
  passed += 1;
  console.log(`[macos-updater-signature-config] PASS ${name}`);
}
console.log(
  `[macos-updater-signature-config] SUMMARY passed=${passed} failed=0 total=${cases.length}`,
);
