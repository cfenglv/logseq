#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  classifyShipItOutcome,
  UpdaterSignatureGateError,
} from "./verify-macos-updater-signature.mjs";

const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const read = (relativePath) =>
  fs.readFileSync(path.join(repoRoot, relativePath), "utf8");

const workflow = read(".github/workflows/build-desktop-release.yml");
const verifier = read("scripts/verify-macos-updater-signature.mjs");
const baseline = JSON.parse(
  read("scripts/fixtures/macos-updater-baseline.json"),
);

const cases = [
  [
    "published .4 arm64 inputs are SHA-256 pinned",
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
        },
      }),
  ],
  [
    "release workflow runs the gate against the candidate ZIP",
    () =>
      assert.match(
        workflow,
        /build-macos-arm64:[\s\S]*?Verify macOS updater installation compatibility[\s\S]*?verify-macos-updater-signature\.mjs[\s\S]*?--candidate-metadata static\/dist\/latest-mac\.yml[\s\S]*?--candidate-zip/,
      ),
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
