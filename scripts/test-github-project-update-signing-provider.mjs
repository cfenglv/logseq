#!/usr/bin/env node

import assert from "node:assert/strict";
import {
  createHash,
  generateKeyPairSync,
} from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  assertGithubProjectUpdateSigningContext,
  githubProjectUpdateSigningSecretName,
  loadGithubProjectUpdateSigningKey,
} from "./project-update-github-actions.mjs";
import {
  removeSourceRevision,
  verifySourceRevision,
  writeSourceRevision,
} from "./selfhost-release-provenance.mjs";

const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const read = (relativePath) =>
  fs.readFileSync(path.join(repoRoot, relativePath), "utf8");

const rawPublicKey = (publicKey) =>
  Buffer.from(publicKey.export({ format: "jwk" }).x, "base64url");

const policyFor = (publicKey) => {
  const raw = rawPublicKey(publicKey);
  return Object.freeze({
    algorithm: "ed25519-sha512-manifest-v1",
    bundleIdentifier: "com.logseq.logseq",
    keyId: `ed25519:${createHash("sha256").update(raw).digest("hex")}`,
    minimumBootstrapRevision: 5,
    payloadDomain: "logseq-selfhost-macos-update-v1",
    publicKeyBase64: raw.toString("base64"),
  });
};

const contextEnvironment = Object.freeze({
  GITHUB_ACTIONS: "true",
  GITHUB_EVENT_NAME: "workflow_dispatch",
  GITHUB_REF: "refs/heads/release/2.0.1-selfhost.5",
  GITHUB_REF_NAME: "release/2.0.1-selfhost.5",
  GITHUB_REPOSITORY: "cfenglv/logseq",
  GITHUB_SHA: "a".repeat(40),
  LOGSEQ_RELEASE_BUILD_TARGET: "stable",
  LOGSEQ_RELEASE_SOURCE_REF: "release/2.0.1-selfhost.5",
  LOGSEQ_RELEASE_SOURCE_SHA: "a".repeat(40),
});

const environmentNames = [
  ...Object.keys(contextEnvironment),
  githubProjectUpdateSigningSecretName,
];

const withEnvironment = (overrides, test) => {
  const original = new Map(
    environmentNames.map((name) => [name, process.env[name]]),
  );
  try {
    for (const name of environmentNames) delete process.env[name];
    Object.assign(process.env, contextEnvironment, overrides);
    return test();
  } finally {
    for (const name of environmentNames) {
      const value = original.get(name);
      if (value === undefined) delete process.env[name];
      else process.env[name] = value;
    }
  }
};

const cases = [];
const addCase = (name, test) => cases.push([name, test]);

addCase("exact workflow/source ref and SHA context is accepted", () => {
  withEnvironment({}, () => {
    const context = assertGithubProjectUpdateSigningContext({
      version: "2.0.1-selfhost.5",
    });
    assert.equal(context.sourceSha, "a".repeat(40));
    assert.equal(context.sourceRef, "release/2.0.1-selfhost.5");
  });
});

addCase("SOURCE_REVISION is exact, immutable, and fail-closed", () => {
  const root = fs.mkdtempSync(
    path.join(os.tmpdir(), "logseq-source-revision-"),
  );
  try {
    const sourceRevision = "a".repeat(40);
    writeSourceRevision({ dir: root, sourceRevision });
    assert.equal(
      fs.readFileSync(path.join(root, "SOURCE_REVISION"), "utf8"),
      `${sourceRevision}\n`,
    );
    assert.equal(
      verifySourceRevision({ dir: root, sourceRevision }),
      sourceRevision,
    );
    assert.throws(
      () => verifySourceRevision({ dir: root, sourceRevision: "b".repeat(40) }),
      /does not equal the exact rehearsed source SHA/,
    );
    assert.throws(
      () => writeSourceRevision({ dir: root, sourceRevision }),
      /already contain SOURCE_REVISION/,
    );
    assert.throws(
      () =>
        verifySourceRevision({
          dir: root,
          sourceRevision: "A".repeat(40),
        }),
      /exact lowercase 40-hex/,
    );
    removeSourceRevision(root);
    assert.equal(fs.existsSync(path.join(root, "SOURCE_REVISION")), false);
  } finally {
    fs.rmSync(root, { force: true, recursive: true });
  }
});

for (const [name, overrides, version, pattern] of [
  [
    "push event",
    { GITHUB_EVENT_NAME: "push" },
    "2.0.1-selfhost.5",
    /workflow_dispatch/,
  ],
  [
    "wrong repository",
    { GITHUB_REPOSITORY: "someone/logseq" },
    "2.0.1-selfhost.5",
    /cfenglv\/logseq/,
  ],
  [
    "nightly target",
    { LOGSEQ_RELEASE_BUILD_TARGET: "nightly" },
    "2.0.1-selfhost.5",
    /stable or beta/,
  ],
  [
    "non-selfhost version",
    {},
    "2.0.1",
    /unsupported selfhost version|stable selfhost/,
  ],
  [
    "selfhost nightly version",
    {},
    "2.0.1-selfhost.5.nightly.20260731",
    /stable selfhost/,
  ],
  [
    "source ref mismatch",
    { LOGSEQ_RELEASE_SOURCE_REF: "release/other" },
    "2.0.1-selfhost.5",
    /workflow ref to equal the source ref/,
  ],
  [
    "source SHA mismatch",
    { LOGSEQ_RELEASE_SOURCE_SHA: "b".repeat(40) },
    "2.0.1-selfhost.5",
    /workflow SHA to equal the resolved source SHA/,
  ],
]) {
  addCase(`${name} fails closed`, () => {
    withEnvironment(overrides, () => {
      assert.throws(
        () => assertGithubProjectUpdateSigningContext({ version }),
        pattern,
      );
    });
  });
}

addCase("Environment key is consumed once from memory and matches policy", () => {
  const keys = generateKeyPairSync("ed25519");
  const encoded = keys.privateKey
    .export({ format: "der", type: "pkcs8" })
    .toString("base64");
  withEnvironment(
    { [githubProjectUpdateSigningSecretName]: encoded },
    () => {
      const privateKey = loadGithubProjectUpdateSigningKey(
        policyFor(keys.publicKey),
      );
      assert.equal(privateKey.asymmetricKeyType, "ed25519");
      assert.equal(
        Object.hasOwn(process.env, githubProjectUpdateSigningSecretName),
        false,
      );
      assert.throws(
        () => loadGithubProjectUpdateSigningKey(policyFor(keys.publicKey)),
        /protected Environment[\s\S]*missing/i,
      );
    },
  );
});

for (const [name, encoded, expected] of [
  ["missing secret", undefined, /missing/i],
  ["non-canonical base64", " Zm9v ", /canonical base64/i],
  ["invalid PKCS8", Buffer.from("not-pkcs8").toString("base64"), /PKCS#8/i],
]) {
  addCase(`${name} fails closed without retaining the environment value`, () => {
    const overrides = {};
    if (encoded !== undefined) {
      overrides[githubProjectUpdateSigningSecretName] = encoded;
    }
    withEnvironment(overrides, () => {
      const keys = generateKeyPairSync("ed25519");
      assert.throws(
        () => loadGithubProjectUpdateSigningKey(policyFor(keys.publicKey)),
        expected,
      );
      assert.equal(
        Object.hasOwn(process.env, githubProjectUpdateSigningSecretName),
        false,
      );
    });
  });
}

addCase("wrong Ed25519 keyId fails closed", () => {
  const expected = generateKeyPairSync("ed25519");
  const wrong = generateKeyPairSync("ed25519");
  const encoded = wrong.privateKey
    .export({ format: "der", type: "pkcs8" })
    .toString("base64");
  withEnvironment(
    { [githubProjectUpdateSigningSecretName]: encoded },
    () => {
      assert.throws(
        () =>
          loadGithubProjectUpdateSigningKey(policyFor(expected.publicKey)),
        /does not match the fixed project update public key\/policy/,
      );
      assert.equal(
        Object.hasOwn(process.env, githubProjectUpdateSigningSecretName),
        false,
      );
    },
  );
});

addCase("GitHub signer closure has no Keychain or secret file/argv path", () => {
  const source = [
    "scripts/finalize-github-macos-project-update.mjs",
    "scripts/project-update-github-actions.mjs",
    "scripts/project-update-private-key.mjs",
  ]
    .map(read)
    .join("\n");
  assert.doesNotMatch(
    source,
    /\/usr\/bin\/security|Keychain|add-generic-password|find-generic-password/,
  );
  assert.doesNotMatch(
    source,
    /--(?:private[-_]?key|secret|seed|pkcs8|credential)\b/i,
  );
  assert.doesNotMatch(
    source,
    /writeFileSync\s*\([^)]*(?:private|secret|seed|pkcs8|credential)/i,
  );
});

addCase("shared finalizer verifies before and after both metadata signatures", () => {
  const source = read("scripts/finalize-macos-project-update-core.mjs");
  const unsigned = source.indexOf("verifyUnsignedMacosProjectUpdateCandidate");
  const sign = source.lastIndexOf("signMacosProjectUpdate");
  const signed = source.lastIndexOf("verifyProjectSignedMacosUpdate");
  const complete = source.lastIndexOf("verifyCompleteReleaseAssets");
  assert.ok(unsigned !== -1 && unsigned < sign);
  assert.ok(sign < signed && signed < complete);
  assert.ok(complete < source.lastIndexOf("finalizeArtifact"));
  assert.match(source, /rollbackArtifact/);
  assert.match(source, /for \(const arch of \["arm64", "x64"\]\)/);
  const githubFinalizer = read(
    "scripts/finalize-github-macos-project-update.mjs",
  );
  assert.match(githubFinalizer, /writeSourceRevision/);
  assert.match(githubFinalizer, /removeSourceRevision/);
});

let passed = 0;
let failed = 0;
for (const [name, test] of cases) {
  try {
    await test();
    passed += 1;
    console.log(`[github-project-signing-provider] PASS ${name}`);
  } catch (error) {
    failed += 1;
    console.error(
      `[github-project-signing-provider] FAIL ${name}: ${
        error instanceof Error ? error.stack || error.message : error
      }`,
    );
  }
}

console.log(
  `[github-project-signing-provider] SUMMARY passed=${passed} failed=${failed} total=${cases.length}`,
);
if (failed > 0) process.exitCode = 1;
