#!/usr/bin/env node

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash, generateKeyPairSync } from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const productionPolicyPath = path.join(
  repoRoot,
  "resources",
  "updater",
  "project-signing-policy.json",
);
const isolatedFiles = [
  "resources/project-updater-signature.mjs",
  "scripts/project-update-signing.mjs",
  "scripts/require-project-signing-policy.mjs",
  "scripts/sign-macos-project-update.mjs",
  "scripts/verify-project-signed-macos-update.mjs",
];

const sha256 = (value) =>
  createHash("sha256").update(value).digest("hex");

const rawPublicKey = (publicKey) =>
  Buffer.from(publicKey.export({ format: "jwk" }).x, "base64url");

const validPolicy = (publicKeyRaw) => ({
  algorithm: "ed25519-sha512-manifest-v1",
  bundleIdentifier: "com.logseq.logseq",
  keyId: `ed25519:${sha256(publicKeyRaw)}`,
  minimumBootstrapRevision: 5,
  payloadDomain: "logseq-selfhost-macos-update-v1",
  publicKeyBase64: publicKeyRaw.toString("base64"),
});

const command = (args, options = {}) => {
  const result = spawnSync(process.execPath, args, {
    cwd: options.cwd ?? repoRoot,
    encoding: "utf8",
    env: options.env ?? process.env,
    stdio: ["ignore", "pipe", "pipe"],
  });
  if (result.error) throw result.error;
  return {
    output: `${result.stdout || ""}${result.stderr || ""}`,
    status: result.status,
  };
};

const writeJson = (filePath, value) => {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`);
};

const createIsolatedContractTree = (policy) => {
  const root = fs.mkdtempSync(
    path.join(os.tmpdir(), "logseq-signing-policy-contract-"),
  );
  for (const relativePath of isolatedFiles) {
    const destination = path.join(root, relativePath);
    fs.mkdirSync(path.dirname(destination), { recursive: true });
    fs.copyFileSync(path.join(repoRoot, relativePath), destination);
  }
  const versionPath = path.join(root, "src", "main", "frontend", "version.cljs");
  fs.mkdirSync(path.dirname(versionPath), { recursive: true });
  fs.writeFileSync(
    versionPath,
    '(ns frontend.version)\n(defonce version "2.0.1-selfhost.6")\n',
  );
  writeJson(
    path.join(root, "resources", "updater", "project-signing-policy.json"),
    policy,
  );
  return root;
};

const policyGate = (root = repoRoot) =>
  command(["scripts/require-project-signing-policy.mjs"], { cwd: root });

const assertRejected = (root, policy, label) => {
  writeJson(
    path.join(root, "resources", "updater", "project-signing-policy.json"),
    policy,
  );
  const result = policyGate(root);
  assert.notEqual(result.status, 0, `${label} was accepted`);
  assert.match(
    result.output,
    /RELEASE BLOCKED: .*fail-closed/,
    `${label} did not fail closed:\n${result.output}`,
  );
};

const mutate = (policy, fields) => ({ ...policy, ...fields });

const assertNoPrivateMaterial = (value, location = "policy") => {
  if (Array.isArray(value)) {
    value.forEach((child, index) =>
      assertNoPrivateMaterial(child, `${location}[${index}]`),
    );
    return;
  }
  if (value && typeof value === "object") {
    for (const [key, child] of Object.entries(value)) {
      assert.doesNotMatch(
        key,
        /private[-_]?key|secret|seed|mnemonic|passphrase|password|pkcs8|credential|api[-_]?key|token/i,
        `${location}.${key} is a forbidden private-material field`,
      );
      assertNoPrivateMaterial(child, `${location}.${key}`);
    }
    return;
  }
  if (typeof value === "string") {
    assert.doesNotMatch(
      value,
      /-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----|(?:PRIVATE_KEY|SECRET|TOKEN|PASSWORD)\s*=/i,
      `${location} contains serialized or assigned private material`,
    );
  }
};

const cases = [];
const addCase = (name, test) => cases.push([name, test]);

const fixtureKeys = generateKeyPairSync("ed25519");
const fixtureRawPublicKey = rawPublicKey(fixtureKeys.publicKey);
const fixturePolicy = validPolicy(fixtureRawPublicKey);
const isolatedRoot = createIsolatedContractTree(fixturePolicy);

addCase("valid raw Ed25519 key and complete sha256 keyId are accepted", () => {
  const result = policyGate(isolatedRoot);
  assert.equal(result.status, 0, result.output);
  assert.match(
    result.output,
    new RegExp(
      `READY keyId=${fixturePolicy.keyId} minimumBootstrapRevision=5`,
    ),
  );
});

addCase("UNCONFIGURED policy is rejected", () => {
  assertRejected(
    isolatedRoot,
    mutate(fixturePolicy, {
      keyId: "UNCONFIGURED",
      publicKeyBase64: "UNCONFIGURED",
    }),
    "UNCONFIGURED policy",
  );
});

for (const length of [31, 33]) {
  addCase(`${length}-byte raw public key is rejected`, () => {
    const publicKey = Buffer.alloc(length, length);
    assertRejected(
      isolatedRoot,
      validPolicy(publicKey),
      `${length}-byte raw public key`,
    );
  });
}

addCase("public key and keyId mismatch is rejected", () => {
  const wrongKeyId = validPolicy(Buffer.alloc(32, 0xa5)).keyId;
  assert.notEqual(wrongKeyId, fixturePolicy.keyId);
  assertRejected(
    isolatedRoot,
    mutate(fixturePolicy, { keyId: wrongKeyId }),
    "mismatched public key and keyId",
  );
});

addCase("truncated sha256 keyId is rejected", () => {
  assertRejected(
    isolatedRoot,
    mutate(fixturePolicy, {
      keyId: fixturePolicy.keyId.slice(0, -"0123456789abcdef".length),
    }),
    "truncated keyId",
  );
});

for (const [label, fields] of [
  ["algorithm", { algorithm: "ed25519" }],
  ["payload domain", { payloadDomain: "logseq-selfhost-macos-update-v2" }],
  ["bundle identifier", { bundleIdentifier: "com.example.logseq" }],
  ["minimum bootstrap revision", { minimumBootstrapRevision: 4 }],
]) {
  addCase(`wrong ${label} is rejected`, () => {
    assertRejected(
      isolatedRoot,
      mutate(fixturePolicy, fields),
      `wrong ${label}`,
    );
  });
}

addCase("production policy contains no private or suspicious secret material", () => {
  const policy = JSON.parse(fs.readFileSync(productionPolicyPath, "utf8"));
  assertNoPrivateMaterial(policy);
});

addCase("temporary Ed25519 sign/verify roundtrip rejects tampering", () => {
  writeJson(
    path.join(
      isolatedRoot,
      "resources",
      "updater",
      "project-signing-policy.json",
    ),
    fixturePolicy,
  );
  const archive = path.join(isolatedRoot, "update.zip");
  const metadata = path.join(isolatedRoot, "latest-mac.yml");
  const archiveBytes = Buffer.from("isolated project update artifact\n");
  fs.writeFileSync(archive, archiveBytes);
  fs.writeFileSync(
    metadata,
    [
      "version: 2.0.1-selfhost.6",
      "path: update.zip",
      "",
    ].join("\n"),
  );
  const privateKeyBase64 = fixtureKeys.privateKey
    .export({ format: "der", type: "pkcs8" })
    .toString("base64");
  const signer = command(
    [
      "scripts/sign-macos-project-update.mjs",
      "--arch",
      "arm64",
      "--version",
      "2.0.1-selfhost.6",
      "--archive",
      archive,
      "--metadata",
      metadata,
    ],
    {
      cwd: isolatedRoot,
      env: {
        ...process.env,
        LOGSEQ_MACOS_UPDATE_ED25519_PRIVATE_KEY_BASE64: privateKeyBase64,
      },
    },
  );
  assert.equal(signer.status, 0, signer.output);
  assert.equal(
    signer.output.includes(privateKeyBase64),
    false,
    "signer output exposed the temporary private key",
  );

  const verifyArgs = [
    "scripts/verify-project-signed-macos-update.mjs",
    "--arch",
    "arm64",
    "--version",
    "2.0.1-selfhost.6",
    "--archive",
    archive,
    "--metadata",
    metadata,
  ];
  const verified = command(verifyArgs, { cwd: isolatedRoot });
  assert.equal(verified.status, 0, verified.output);
  assert.match(verified.output, /\[project-update-verify\] OK/);

  fs.appendFileSync(archive, "tampered");
  const tamperedArtifact = command(verifyArgs, { cwd: isolatedRoot });
  assert.notEqual(tamperedArtifact.status, 0);
  assert.match(
    tamperedArtifact.output,
    /signed project update manifest does not match the artifact/,
  );

  fs.writeFileSync(archive, archiveBytes);
  const signedMetadata = fs.readFileSync(metadata, "utf8");
  const tamperedMetadata = signedMetadata.replace(
    /(^  signature: )([A-Za-z0-9+/])/m,
    (_, prefix, firstCharacter) =>
      `${prefix}${firstCharacter === "A" ? "B" : "A"}`,
  );
  assert.notEqual(tamperedMetadata, signedMetadata);
  fs.writeFileSync(metadata, tamperedMetadata);
  const tamperedSignature = command(verifyArgs, { cwd: isolatedRoot });
  assert.notEqual(tamperedSignature.status, 0);
  assert.match(
    tamperedSignature.output,
    /metadata Ed25519 project update signature is invalid/,
  );
});

addCase("production signing policy passes the release gate", () => {
  const policyText = fs.readFileSync(productionPolicyPath, "utf8");
  assert.doesNotMatch(
    policyText,
    /\bUNCONFIGURED\b/i,
    "production policy still contains UNCONFIGURED",
  );
  const result = policyGate();
  assert.equal(result.status, 0, result.output);
  assert.match(
    result.output,
    /\[project-signing-policy\] READY keyId=ed25519:[0-9a-f]{64}/,
  );
});

let passed = 0;
let failed = 0;
try {
  for (const [name, test] of cases) {
    try {
      await test();
      passed += 1;
      console.log(`[project-signing-policy-contract] PASS ${name}`);
    } catch (error) {
      failed += 1;
      console.error(
        `[project-signing-policy-contract] FAIL ${name}: ${
          error instanceof Error ? error.stack || error.message : error
        }`,
      );
    }
  }
} finally {
  fs.rmSync(isolatedRoot, { recursive: true, force: true });
}

console.log(
  `[project-signing-policy-contract] SUMMARY passed=${passed} failed=${failed} total=${cases.length}`,
);
if (failed > 0) process.exit(1);
