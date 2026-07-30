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

const escapeRegExp = (value) =>
  value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

const identityDeclarations = (source) =>
  [...source.matchAll(
    /\b(?:const|let)\s+([A-Za-z_$][\w$]*)\s*=\s*([\s\S]*?);/g,
  )].filter(([, name, value]) =>
    /CFBundleIdentifier|bundle(?:Identifier|Id|ID)/i.test(
      `${name} ${value}`,
    ),
  );

const hasNonEmptyGuard = (source, name) => {
  const escaped = escapeRegExp(name);
  return [
    new RegExp(`assert(?:\\.ok)?\\s*\\(\\s*${escaped}\\b`),
    new RegExp(
      `assert\\.notEqual\\s*\\(\\s*${escaped}\\s*,\\s*["']\\s*["']`,
    ),
    new RegExp(
      `if\\s*\\([\\s\\S]{0,400}!\\s*${escaped}\\b[\\s\\S]{0,400}\\)\\s*\\{?[\\s\\S]{0,250}?throw\\b`,
    ),
    new RegExp(
      `if\\s*\\([\\s\\S]{0,400}${escaped}\\s*(?:===|==)\\s*["']\\s*["'][\\s\\S]{0,400}\\)\\s*\\{?[\\s\\S]{0,250}?throw\\b`,
    ),
    new RegExp(
      `if\\s*\\([\\s\\S]{0,400}${escaped}\\.length\\s*(?:===|==|<=)\\s*0\\b[\\s\\S]{0,400}\\)\\s*\\{?[\\s\\S]{0,250}?throw\\b`,
    ),
  ].some((pattern) => pattern.test(source));
};

const assertDerivedMatchingBundleIdentifier = ({
  block,
  label,
  moduleSource,
}) => {
  assert.match(
    moduleSource,
    /CFBundleIdentifier/,
    `${label} never reads CFBundleIdentifier from an App Info.plist`,
  );
  const declarations = identityDeclarations(block);
  const target = declarations.find(([, , value]) =>
    /\btargetApp\b/.test(value),
  );
  const update = declarations.find(([, , value]) =>
    /\bupdateApp\b/.test(value),
  );
  assert.ok(
    target,
    `${label} does not derive the request identity from targetApp`,
  );
  assert.ok(
    update,
    `${label} does not derive an identity from updateApp`,
  );
  const targetName = target[1];
  const updateName = update[1];
  assert.notEqual(
    targetName,
    updateName,
    `${label} does not keep independently read target/update identities`,
  );
  assert.ok(
    hasNonEmptyGuard(block, targetName) ||
      hasNonEmptyGuard(block, updateName),
    `${label} does not reject an empty bundle identity`,
  );

  const targetPattern = escapeRegExp(targetName);
  const updatePattern = escapeRegExp(updateName);
  const exactMatchGuards = [
    new RegExp(
      `if\\s*\\([\\s\\S]{0,400}\\b${targetPattern}\\b\\s*!==\\s*\\b${updatePattern}\\b[\\s\\S]{0,400}\\)\\s*\\{?[\\s\\S]{0,250}?throw\\b`,
    ),
    new RegExp(
      `if\\s*\\([\\s\\S]{0,400}\\b${updatePattern}\\b\\s*!==\\s*\\b${targetPattern}\\b[\\s\\S]{0,400}\\)\\s*\\{?[\\s\\S]{0,250}?throw\\b`,
    ),
    new RegExp(
      `assert\\.(?:equal|strictEqual|deepEqual)\\s*\\(\\s*${targetPattern}\\s*,\\s*${updatePattern}\\b`,
    ),
    new RegExp(
      `assert\\.(?:equal|strictEqual|deepEqual)\\s*\\(\\s*${updatePattern}\\s*,\\s*${targetPattern}\\b`,
    ),
    new RegExp(
      `if\\s*\\([\\s\\S]{0,400}!\\s*Object\\.is\\s*\\(\\s*${targetPattern}\\s*,\\s*${updatePattern}\\s*\\)[\\s\\S]{0,400}\\)\\s*\\{?[\\s\\S]{0,250}?throw\\b`,
    ),
    new RegExp(
      `if\\s*\\([\\s\\S]{0,400}!\\s*Object\\.is\\s*\\(\\s*${updatePattern}\\s*,\\s*${targetPattern}\\s*\\)[\\s\\S]{0,400}\\)\\s*\\{?[\\s\\S]{0,250}?throw\\b`,
    ),
  ];
  assert.ok(
    exactMatchGuards.some((pattern) => pattern.test(block)),
    `${label} does not reject a target/update bundle-identity mismatch`,
  );

  const explicitProperty = block.match(
    /(?:["']bundleIdentifier["']|bundleIdentifier)\s*:\s*([^,\n}]+)/,
  );
  const shorthandProperty =
    targetName === "bundleIdentifier" &&
    /(?:\{|,)\s*bundleIdentifier\s*(?:,|\})/.test(block);
  assert.ok(
    explicitProperty || shorthandProperty,
    `${label} does not write a bundleIdentifier into the ShipIt request`,
  );
  if (explicitProperty) {
    const requestValue = explicitProperty[1].trim();
    assert.doesNotMatch(
      requestValue,
      /^(?:null|undefined|["']\s*["'])$/,
      `${label} writes a null or empty ShipIt request identity`,
    );
    assert.match(
      requestValue,
      new RegExp(`\\b${targetPattern}\\b`),
      `${label} does not write the target App identity into the request`,
    );
  }
};

const identityContractControl = `
  const targetBundleIdentifier = plistValue(
    targetApp,
    "CFBundleIdentifier",
  );
  const updateBundleIdentifier = plistValue(
    updateApp,
    "CFBundleIdentifier",
  );
  if (
    !targetBundleIdentifier ||
    targetBundleIdentifier !== updateBundleIdentifier
  ) {
    throw new Error("invalid fixture identity");
  }
  fs.writeFileSync(
    statePath,
    JSON.stringify({ bundleIdentifier: targetBundleIdentifier }),
  );
`;

const verifyIdentityContractOracle = () => {
  assert.doesNotThrow(() =>
    assertDerivedMatchingBundleIdentifier({
      block: identityContractControl,
      label: "identity contract control",
      moduleSource: identityContractControl,
    }),
  );
  for (const [label, unsafe] of [
    [
      "null request identity",
      identityContractControl.replace(
        "bundleIdentifier: targetBundleIdentifier",
        "bundleIdentifier: null",
      ),
    ],
    [
      "empty identity accepted",
      identityContractControl.replace(
        "!targetBundleIdentifier ||",
        "false ||",
      ),
    ],
    [
      "mismatched identity accepted",
      identityContractControl.replace(
        "targetBundleIdentifier !== updateBundleIdentifier",
        "false",
      ),
    ],
  ]) {
    assert.throws(
      () =>
        assertDerivedMatchingBundleIdentifier({
          block: unsafe,
          label,
          moduleSource: unsafe,
        }),
      undefined,
      `identity oracle accepted ${label}`,
    );
  }
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

  for (const [label, status, after] of [
    ["unchanged target", 0, "2.0.1-selfhost.5"],
    ["nonzero process", 1, "2.0.1-selfhost.6"],
  ]) {
    expectGateError(
      {
        status,
        signal: null,
        log: "",
        before: "2.0.1-selfhost.5",
        after,
        newVersion: "2.0.1-selfhost.6",
      },
      (error) => assert.equal(error.kind, "install-failure"),
      label,
    );
  }

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
  verifyIdentityContractOracle();
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
  assertDerivedMatchingBundleIdentifier({
    block: runShipIt,
    label: "physical ShipIt",
    moduleSource: physicalHarness,
  });
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
  assertDerivedMatchingBundleIdentifier({
    block: runShipItInstall,
    label: "production ShipIt",
    moduleSource: productionVerifier,
  });
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
