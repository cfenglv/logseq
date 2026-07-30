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
const physicalHarness = read(
  "scripts/test-project-signed-macos-updater.mjs",
);
const productionVerifier = read(
  "scripts/verify-macos-updater-signature.mjs",
);
const cases = [];
const test = (name, callback) => cases.push([name, callback]);

const functionBlock = (source, start, end, label) => {
  const block = source.match(
    new RegExp(
      `(?:export )?const ${start} = [\\s\\S]*?(?=\\n(?:export )?const ${end} = )`,
    ),
  )?.[0];
  assert.ok(block, `${label} function block is missing`);
  return block;
};

const expectGateError = (input, predicate, label) => {
  assert.throws(
    () => classifyShipItOutcome(input),
    (error) => {
      assert.equal(
        error instanceof UpdaterSignatureGateError,
        true,
        `${label} did not use the production outcome classifier`,
      );
      predicate(error);
      return true;
    },
  );
};

test("production classifier separates success, unreadable request, and signal termination", () => {
  assert.equal(
    classifyShipItOutcome({
      status: 0,
      signal: null,
      log: "Installation completed successfully",
      before: "2.0.1-selfhost.5",
      after: "2.0.1-selfhost.6",
      newVersion: "2.0.1-selfhost.6",
    }),
    "ShipIt exit=0 target-before=2.0.1-selfhost.5 target-after=2.0.1-selfhost.6",
  );

  expectGateError(
    {
      status: 1,
      signal: null,
      log: "SQRLShipItRequestErrorDomain Code=2 Could not read update request",
      before: "2.0.1-selfhost.5",
      after: "2.0.1-selfhost.5",
      newVersion: "2.0.1-selfhost.6",
    },
    (error) => {
      assert.equal(error.kind, "fixture-error");
      assert.match(error.message, /request fixture was unreadable or invalid/);
    },
    "request-unreadable outcome",
  );

  expectGateError(
    {
      status: null,
      signal: "SIGABRT",
      log:
        "Invalid parameter not satisfying: bundleIdentifier != nil",
      before: "2.0.1-selfhost.5",
      after: "2.0.1-selfhost.5",
      newVersion: "2.0.1-selfhost.6",
    },
    (error) => {
      assert.equal(
        error.kind,
        "fixture-error",
        "a signal termination must be a hard fixture failure",
      );
      assert.match(error.message, /terminated by SIGABRT/);
      assert.doesNotMatch(
        error.message,
        /request fixture was unreadable|not an updater signature regression/,
        "a process crash was mislabeled as request-unreadable",
      );
    },
    "signal-terminated outcome",
  );
});

test("physical ShipIt fixture supplies identity and treats signals as failures", () => {
  const runShipIt = functionBlock(
    physicalHarness,
    "runShipIt",
    "physicalAdHocWeakness",
    "physical ShipIt",
  );
  const physicalAdHocWeakness = functionBlock(
    physicalHarness,
    "physicalAdHocWeakness",
    "explicitCertificateHashConsumerProbe",
    "physical ShipIt outcome",
  );
  const command = functionBlock(
    physicalHarness,
    "command",
    "sha256",
    "physical command",
  );
  assert.match(
    runShipIt,
    /bundleIdentifier:\s*["']com\.logseq\.logseq["']/,
    "physical ShipIt request omits its non-null bundle identity",
  );
  assert.doesNotMatch(
    runShipIt,
    /bundleIdentifier:\s*null/,
    "physical ShipIt request retains the crashing null bundle identity",
  );
  assert.match(
    command,
    /signal:\s*result\.signal/,
    "physical ShipIt spawn result drops its terminating signal",
  );
  const signalBranch = physicalAdHocWeakness.match(
    /if\s*\(shipIt\.signal\)\s*\{[\s\S]*?\n\s*\}/,
  )?.[0];
  assert.ok(signalBranch, "physical ShipIt has no signal-specific branch");
  assert.match(signalBranch, /throw new Error/);
  assert.match(signalBranch, /terminated by \$\{shipIt\.signal\}/);
  assert.doesNotMatch(
    signalBranch,
    /BLOCK|ReleaseBlock|SkipTest/,
    "physical ShipIt turns a process crash into a block or skip",
  );
  const unreadableBranch = physicalAdHocWeakness.match(
    /else if\s*\([\s\S]*?SQRLShipItRequestErrorDomain[\s\S]*?\)\s*\{[\s\S]*?\n\s*\}/,
  )?.[0];
  assert.ok(
    unreadableBranch,
    "physical ShipIt has no request-unreadable branch",
  );
  assert.match(
    unreadableBranch,
    /throw new ReleaseBlock/,
    "physical ShipIt reports request-unreadable as a passing case",
  );
  assert.ok(
    physicalAdHocWeakness.indexOf("if (shipIt.signal)") <
      physicalAdHocWeakness.indexOf(
        'shipIt.output.includes("SQRLShipItRequestErrorDomain")',
      ),
    "physical ShipIt checks request-unreadable before signal termination",
  );
});

test("production physical verifier supplies identity and preserves spawn signals", () => {
  const runShipItInstall = functionBlock(
    productionVerifier,
    "runShipItInstall",
    "loadBaseline",
    "production ShipIt",
  );
  assert.match(
    runShipItInstall,
    /bundleIdentifier:\s*["']com\.logseq\.logseq["']/,
    "production ShipIt request omits its non-null bundle identity",
  );
  assert.doesNotMatch(
    runShipItInstall,
    /bundleIdentifier:\s*null/,
    "production ShipIt request retains the crashing null bundle identity",
  );
  assert.match(
    runShipItInstall,
    /signal:\s*result\.signal/,
    "production verifier drops ShipIt's terminating signal",
  );
  const classifier = functionBlock(
    productionVerifier,
    "classifyShipItOutcome",
    "runShipItInstall",
    "production ShipIt classifier",
  );
  assert.match(classifier, /\bsignal\b/);
  assert.match(classifier, /terminated by \$\{signal\}/);
  assert.ok(
    classifier.indexOf("signal") <
      classifier.indexOf('log.includes("SQRLShipItRequestErrorDomain")'),
    "production classifier checks request-unreadable before signal termination",
  );
});

let passed = 0;
let failed = 0;
for (const [name, callback] of cases) {
  try {
    await callback();
    passed += 1;
    console.log(`[shipit-outcome-contract] PASS ${name}`);
  } catch (error) {
    failed += 1;
    console.error(
      `[shipit-outcome-contract] FAIL ${name}: ${
        error instanceof Error ? error.stack || error.message : error
      }`,
    );
  }
}
console.log(
  `[shipit-outcome-contract] SUMMARY passed=${passed} failed=${failed} total=${cases.length}`,
);
if (failed > 0) process.exitCode = 1;
