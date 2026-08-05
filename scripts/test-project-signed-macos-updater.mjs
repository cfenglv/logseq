#!/usr/bin/env node

import assert from "node:assert/strict";
import {
  createHash,
  createPrivateKey,
  createPublicKey,
  generateKeyPairSync,
  sign as cryptoSign,
  verify as cryptoVerify,
  X509Certificate,
} from "node:crypto";
import { spawn, spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { updaterSignatureGatePlan } from "./run-macos-updater-signature-policy.mjs";
import { projectUpdateAlgorithm } from "../resources/project-updater-signature.mjs";
import {
  macosUpdaterChannel,
  resolveSelfhostUpdaterVersions,
} from "../resources/selfhost-updater-version.mjs";

const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const policyPath = path.join(
  repoRoot,
  "resources",
  "updater",
  "project-signing-policy.json",
);
const proxyTlsFixture = Object.freeze({
  auditConsumer:
    "scripts/test-updater-private-material-policy-contract.mjs",
  caCertificate: "scripts/fixtures/proxy-fetch-test-ca.cert.pem",
  consumer: "scripts/test-cli-worker-fetch-proxy.mjs",
  privateKey: "scripts/fixtures/proxy-fetch-test-server.key.pem",
  serverCertificate: "scripts/fixtures/proxy-fetch-test-server.cert.pem",
  sha256: Object.freeze({
    "scripts/fixtures/proxy-fetch-test-ca.cert.pem":
      "c07adf1132c0ea6c2df86eb5260e0dd0a90f9ee46d3859da1733a821a705082e",
    "scripts/fixtures/proxy-fetch-test-server.cert.pem":
      "307c758041e3b04bcd3eaf359a00532d79843f22f44051ee812af8f54b4aab23",
    "scripts/fixtures/proxy-fetch-test-server.key.pem":
      "84b5d6ec5bc56e117b5d8b21bad0de2244a603926a635544e43b446c2d7bb483",
  }),
});
const helperRunnerPath = path.join(
  repoRoot,
  "scripts",
  "run-project-signed-macos-update.mjs",
);
const workflowPath = path.join(
  repoRoot,
  ".github",
  "workflows",
  "build-desktop-release.yml",
);
const legacyDigests = {
  arm64:
    "2dd11f39538c801cf2356a40e753b8f6a9963641df6951e13ed3493b1c5ed705",
  x64: "7b35999d6cd7edcd54b08944bca4112abb39e6fc2f12b7d2f602a2c35cdb8ec0",
};

class SkipTest extends Error {}
class ReleaseBlock extends Error {}

const command = (
  executable,
  args,
  { allowFailure = false, cwd = repoRoot, env = process.env } = {},
) => {
  const result = spawnSync(executable, args, {
    cwd,
    encoding: "utf8",
    env,
    stdio: ["ignore", "pipe", "pipe"],
  });
  const output = `${result.stdout || ""}${result.stderr || ""}`.trim();
  if (result.error) throw result.error;
  if (!allowFailure && result.status !== 0) {
    throw new Error(
      `${executable} ${args.join(" ")} failed with exit ${result.status}${
        output ? `\n${output}` : ""
      }`,
    );
  }
  return {
    output,
    signal: result.signal,
    status: result.status,
  };
};

const sha256 = (value) =>
  createHash("sha256").update(value).digest("hex");

const fileSha256 = (file) => sha256(fs.readFileSync(file));

const addCase = (cases, name, test) => cases.push([name, test]);

const nulSeparatedFiles = (output) =>
  output ? output.split("\0").filter(Boolean) : [];

const trackedFiles = () =>
  nulSeparatedFiles(command("git", ["ls-files", "-z"]).output);

const gitGrepFiles = (pattern, { extended = false } = {}) => {
  const args = ["grep", "-l", "-z", extended ? "-E" : "-F"];
  args.push(
    "-e",
    pattern,
    "--",
    ".",
    ":(exclude)scripts/test-project-signed-macos-updater.mjs",
  );
  const result = command("git", args, { allowFailure: true });
  assert.ok(
    result.status === 0 || result.status === 1,
    `git grep failed with exit ${result.status}: ${result.output}`,
  );
  return result.status === 0 ? nulSeparatedFiles(result.output) : [];
};

const assertProxyFixturePathsTracked = (tracked) => {
  for (const fixturePath of [
    proxyTlsFixture.privateKey,
    proxyTlsFixture.serverCertificate,
    proxyTlsFixture.caCertificate,
    proxyTlsFixture.consumer,
  ]) {
    assert.ok(
      tracked.includes(fixturePath),
      `${fixturePath} must remain tracked for the narrow TLS fixture exception`,
    );
  }
};

const assertPinnedProxyFixtureFile = (fixturePath, bytes) => {
  assert.equal(
    sha256(bytes),
    proxyTlsFixture.sha256[fixturePath],
    `${fixturePath} digest changed; the TLS fixture exception is fail-closed`,
  );
};

const assertProxyFixtureConsumer = (source) => {
  assert.match(
    source,
    /const caPath = path\.join\(\s*repoRoot,\s*"scripts",\s*"fixtures",\s*"proxy-fetch-test-ca\.cert\.pem",?\s*\);/,
    "proxy test must load the pinned CA fixture",
  );
  assert.match(
    source,
    /const serverCertPath = path\.join\(\s*repoRoot,\s*"scripts",\s*"fixtures",\s*"proxy-fetch-test-server\.cert\.pem",?\s*\);/,
    "proxy test must load the pinned server certificate fixture",
  );
  assert.match(
    source,
    /const serverKeyPath = path\.join\(\s*repoRoot,\s*"scripts",\s*"fixtures",\s*"proxy-fetch-test-server\.key\.pem",?\s*\);/,
    "proxy test must load the pinned server private-key fixture",
  );
  assert.match(
    source,
    /server\.listen\(0,\s*"127\.0\.0\.1"/,
    "proxy test servers must bind only to IPv4 loopback",
  );
  assert.match(
    source,
    /httpsTarget = https\.createServer\(\s*\{\s*cert:\s*fs\.readFileSync\(serverCertPath\),\s*key:\s*fs\.readFileSync\(serverKeyPath\),\s*\}/,
    "proxy test must use the pinned key only for its local HTTPS target",
  );
  assert.match(
    source,
    /httpsTargetPort = await listen\(httpsTarget\)/,
    "proxy test HTTPS target must use the loopback-only listener",
  );
  assert.match(
    source,
    /env\.NODE_EXTRA_CA_CERTS = caPath/,
    "proxy test must trust only its adjacent CA fixture",
  );
};

const assertProxyFixtureAuditConsumer = (tracked) => {
  if (!tracked.includes(proxyTlsFixture.auditConsumer)) return;
  const source = fs.readFileSync(
    path.join(repoRoot, proxyTlsFixture.auditConsumer),
    "utf8",
  );
  assert.match(
    source,
    /const auditContractRole = "proxy-tls-fixture-security-audit-v1";/,
    "proxy TLS audit contract does not declare its exact security-audit role",
  );
  assert.doesNotMatch(
    source,
    /\b(?:http|https)\.createServer\s*\(|\bserver\.listen\s*\(/,
    "proxy TLS audit contract became a runtime proxy consumer",
  );
  for (const expectedPath of [
    "scripts/test-project-signed-macos-updater.mjs",
    proxyTlsFixture.consumer,
    proxyTlsFixture.privateKey,
    proxyTlsFixture.serverCertificate,
    proxyTlsFixture.caCertificate,
  ]) {
    assert.ok(
      source.includes(`"${expectedPath}"`),
      `proxy TLS audit contract is not wired to ${expectedPath}`,
    );
  }
  assert.match(
    source,
    /const child = spawn\(process\.execPath, \[scannerPath\]/,
    "proxy TLS audit contract does not invoke the production scanner",
  );
  assert.match(
    source,
    /assert\.deepEqual\(\s*fixturePublicKey,\s*certificatePublicKey/,
    "proxy TLS audit contract does not verify the key-certificate pair",
  );
  assert.match(
    source,
    /copied proxy private key is rejected[\s\S]*renamed proxy private key is rejected[\s\S]*replacement private key at the allowed path is rejected[\s\S]*byte-modified allowed fixture is rejected/,
    "proxy TLS audit contract does not cover copy, rename, replacement, and tampering",
  );
  assert.match(
    source,
    /additional \.key private material is rejected[\s\S]*additional \.pem private material is rejected[\s\S]*private-key marker outside the exact fixture is rejected/,
    "proxy TLS audit contract does not cover other private material",
  );
};

const expectedProxyFixtureConsumers = (tracked) => [
  proxyTlsFixture.consumer,
  ...(tracked.includes(proxyTlsFixture.auditConsumer)
    ? [proxyTlsFixture.auditConsumer]
    : []),
];

const assertProxyFixtureConsumers = (tracked, fixturePath, consumers) => {
  assert.deepEqual(
    consumers,
    expectedProxyFixtureConsumers(tracked),
    `${fixturePath} must be referenced only by the local proxy E2E and its pinned audit contract`,
  );
};

const privateMaterialFindings = ({ markerFiles, tracked }) => {
  const allowed = proxyTlsFixture.privateKey;
  return {
    forbiddenNames: tracked.filter(
      (file) =>
        file !== allowed &&
        /(?:^|\/)(?:[^/]+\.(?:p12|pfx|key)(?:\.pem)?|private[-_]?key(?:\.pem)?|release[-_]?key(?:\.[^/]+)?)$/i.test(
          file,
        ),
    ),
    markerFiles: markerFiles
      .filter((file) => file !== allowed)
      .join("\n"),
  };
};

const userTrustSettingsDigest = () => {
  if (process.platform !== "darwin") return null;
  const tempRoot = fs.mkdtempSync(
    path.join(os.tmpdir(), "logseq-trust-settings-readonly-"),
  );
  const exported = path.join(tempRoot, "user-trust-settings.plist");
  try {
    const result = command(
      "security",
      ["trust-settings-export", exported],
      { allowFailure: true },
    );
    const exportedDigest = fs.existsSync(exported)
      ? fileSha256(exported)
      : "<no-export>";
    return sha256(
      `${result.status}\n${result.output}\n${exportedDigest}`,
    );
  } finally {
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
};

const strictBase64 = (value, label) => {
  assert.equal(typeof value, "string", `${label} must be a base64 string`);
  assert.match(value, /^[A-Za-z0-9+/]+={0,2}$/, `${label} is not base64`);
  const decoded = Buffer.from(value, "base64");
  assert.equal(
    decoded.toString("base64"),
    value,
    `${label} is not canonical base64`,
  );
  return decoded;
};

const inlinePublicKey = (policy) => {
  const direct = [
    policy.publicKeyBase64,
    policy.publicKeyRawBase64,
    policy.ed25519PublicKeyBase64,
    typeof policy.publicKey === "string" ? policy.publicKey : null,
  ].find((value) => typeof value === "string");
  if (direct) return direct;
  if (
    policy.publicKey &&
    typeof policy.publicKey === "object" &&
    policy.publicKey.encoding === "base64" &&
    typeof policy.publicKey.value === "string"
  ) {
    return policy.publicKey.value;
  }
  return null;
};

const loadPolicy = () => {
  if (!fs.existsSync(policyPath)) {
    throw new ReleaseBlock(
      "production Ed25519 signing policy is missing; release is blocked",
    );
  }
  const policy = JSON.parse(fs.readFileSync(policyPath, "utf8"));
  const payloadDomain = [
    policy.schema,
    policy.payloadDomain,
    policy.payloadType,
    policy.domain,
    policy.signatureDomain,
    policy.payload?.domain,
  ].find((value) => typeof value === "string");
  assert.match(
    payloadDomain,
    /(?:logseq|selfhost)[\s\S]*(?:update|signature)[\s\S]*v\d+|v\d+[\s\S]*(?:logseq|selfhost)[\s\S]*(?:update|signature)/i,
    "policy does not declare a versioned project-update payload domain",
  );
  const algorithm = [
    policy.algorithm,
    policy.alg,
    policy.signatureAlgorithm,
    policy.signingAlgorithm,
    policy.signature?.algorithm,
    policy.signature?.alg,
  ].find((value) => typeof value === "string");
  assert.match(
    algorithm,
    /ed[-_ ]?25519/i,
    "policy does not declare Ed25519 signing",
  );
  const bundleId =
    policy.bundleId ??
    policy.bundleIdentifier ??
    policy.applicationId ??
    policy.payload?.bundleId;
  assert.match(
    bundleId,
    /^[a-z0-9]+(?:[.-][a-z0-9]+)+$/i,
    "policy does not declare the signed bundle identifier",
  );
  const serialized = JSON.stringify(policy);
  if (/\bUNCONFIGURED\b/i.test(serialized)) {
    return {
      bundleId,
      configured: false,
      payloadDomain,
      policy,
    };
  }

  let publicKeyRaw;
  let publicKeyPath = null;
  const inline = inlinePublicKey(policy);
  if (inline) {
    publicKeyRaw = strictBase64(inline, "inline Ed25519 public key");
    assert.equal(
      publicKeyRaw.length,
      32,
      "inline Ed25519 public key must contain exactly 32 raw bytes",
    );
  } else {
    assert.equal(
      typeof policy.publicKeyPath,
      "string",
      "policy must contain either an inline raw key or publicKeyPath",
    );
    assert.equal(path.isAbsolute(policy.publicKeyPath), false);
    publicKeyPath = path.resolve(
      path.dirname(policyPath),
      policy.publicKeyPath,
    );
    const publicKeyPem = fs.readFileSync(publicKeyPath, "utf8");
    assert.match(publicKeyPem, /^-----BEGIN PUBLIC KEY-----/);
    const jwk = createPublicKey(publicKeyPem).export({ format: "jwk" });
    assert.equal(jwk.kty, "OKP");
    assert.equal(jwk.crv, "Ed25519");
    publicKeyRaw = Buffer.from(jwk.x, "base64url");
    assert.equal(publicKeyRaw.length, 32);
    if (policy.publicKeySha256) {
      assert.equal(fileSha256(publicKeyPath), policy.publicKeySha256);
    }
  }

  const derivedKeyId = sha256(publicKeyRaw);
  const declaredKeyId =
    policy.keyId ??
    policy.keyID ??
    policy.publicKeyId ??
    policy.publicKeyID ??
    policy.ed25519PublicKeyId;
  if (inline || declaredKeyId !== undefined) {
    assert.equal(
      declaredKeyId,
      `ed25519:${derivedKeyId}`,
      "project keyId must be ed25519: followed by the complete lowercase SHA-256 of the raw public key",
    );
  } else {
    assert.ok(
      policy.publicKeySha256,
      "PEM policy must pin either keyId or publicKeySha256",
    );
  }
  if (policy.publicKeyRawSha256) {
    assert.equal(
      policy.publicKeyRawSha256.toLowerCase(),
      derivedKeyId,
      "raw public key hash does not match the configured key",
    );
  }
  if (inline && policy.publicKeySha256) {
    assert.equal(
      policy.publicKeySha256.toLowerCase(),
      derivedKeyId,
      "inline public key hash does not match the configured key",
    );
  }
  return {
    configured: true,
    derivedKeyId,
    bundleId,
    payloadDomain,
    policy,
    publicKeyPath,
    publicKeyRaw,
  };
};

const configuredProjectPublicKey = (loadedPolicy) => {
  if (loadedPolicy.publicKeyPath) {
    return createPublicKey(
      fs.readFileSync(loadedPolicy.publicKeyPath, "utf8"),
    );
  }
  return createPublicKey({
    format: "jwk",
    key: {
      crv: "Ed25519",
      kty: "OKP",
      x: loadedPolicy.publicKeyRaw.toString("base64url"),
    },
  });
};

const validateTrackedProxyTlsFixture = (tracked) => {
  assertProxyFixturePathsTracked(tracked);
  const fixtureDirectory = path.posix.dirname(proxyTlsFixture.privateKey);
  for (const certificatePath of [
    proxyTlsFixture.serverCertificate,
    proxyTlsFixture.caCertificate,
  ]) {
    assert.equal(
      path.posix.dirname(certificatePath),
      fixtureDirectory,
      `${certificatePath} must remain adjacent to the proxy TLS private key`,
    );
  }

  const fixtureBytes = new Map();
  for (const fixturePath of [
    proxyTlsFixture.privateKey,
    proxyTlsFixture.serverCertificate,
    proxyTlsFixture.caCertificate,
  ]) {
    const bytes = fs.readFileSync(path.join(repoRoot, fixturePath));
    assertPinnedProxyFixtureFile(fixturePath, bytes);
    fixtureBytes.set(fixturePath, bytes);
    assertProxyFixtureConsumers(
      tracked,
      fixturePath,
      gitGrepFiles(path.posix.basename(fixturePath)),
    );
  }

  const consumerSource = fs.readFileSync(
    path.join(repoRoot, proxyTlsFixture.consumer),
    "utf8",
  );
  assertProxyFixtureConsumer(consumerSource);
  assertProxyFixtureAuditConsumer(tracked);

  const privateKey = createPrivateKey(
    fixtureBytes.get(proxyTlsFixture.privateKey),
  );
  const fixturePublicKey = createPublicKey(privateKey);
  const serverCertificate = new X509Certificate(
    fixtureBytes.get(proxyTlsFixture.serverCertificate),
  );
  const caCertificate = new X509Certificate(
    fixtureBytes.get(proxyTlsFixture.caCertificate),
  );
  assert.equal(
    privateKey.asymmetricKeyType,
    "rsa",
    "proxy TLS fixture must remain a non-release RSA key",
  );
  assert.equal(
    fixturePublicKey.equals(serverCertificate.publicKey),
    true,
    "proxy TLS private key does not match its server certificate",
  );
  assert.equal(
    serverCertificate.checkIssued(caCertificate) &&
      serverCertificate.verify(caCertificate.publicKey),
    true,
    "proxy TLS server certificate does not verify against its adjacent CA",
  );
  assert.equal(
    caCertificate.ca && caCertificate.verify(caCertificate.publicKey),
    true,
    "proxy TLS CA fixture is not a self-signed CA",
  );
  for (const loopbackAddress of ["127.0.0.1", "127.0.0.2"]) {
    assert.equal(
      serverCertificate.checkIP(loopbackAddress),
      loopbackAddress,
      `proxy TLS certificate is not pinned to ${loopbackAddress}`,
    );
  }

  const productionPolicy = loadPolicy();
  assert.notEqual(
    fixturePublicKey.asymmetricKeyType,
    "ed25519",
    "proxy TLS fixture must not satisfy the Ed25519 release-signing policy",
  );
  if (productionPolicy.configured) {
    const projectPublicKey =
      configuredProjectPublicKey(productionPolicy);
    assert.equal(
      fixturePublicKey.asymmetricKeyType ===
        projectPublicKey.asymmetricKeyType &&
        fixturePublicKey.equals(projectPublicKey),
      false,
      "proxy TLS fixture public key matches the project signing public key",
    );
  }
};

const trackedPrivateMaterial = () => {
  const tracked = trackedFiles();
  validateTrackedProxyTlsFixture(tracked);
  return privateMaterialFindings({
    markerFiles: gitGrepFiles(
      "-----BEGIN ([A-Z0-9]+ )*PRIVATE KEY-----",
      { extended: true },
    ),
    tracked,
  });
};

const testProxyTlsFixtureExceptionFailClosed = () => {
  const tracked = trackedFiles();
  assertProxyFixturePathsTracked(tracked);

  const copiedKey =
    "scripts/fixtures/proxy-fetch-test-server-copy.key.pem";
  const renamedKey = "scripts/fixtures/proxy-fetch-renamed.pem";
  const releaseKey = "resources/updater/release-signing.key";
  const releaseKeyText = "resources/updater/release-key.txt";
  const releasePem = "resources/updater/release-secret.pem";
  const releaseP12 = "resources/updater/release-signing.p12";
  const findings = privateMaterialFindings({
    markerFiles: [
      proxyTlsFixture.privateKey,
      copiedKey,
      renamedKey,
      releasePem,
    ],
    tracked: [
      proxyTlsFixture.privateKey,
      copiedKey,
      renamedKey,
      releaseKey,
      releaseKeyText,
      releasePem,
      releaseP12,
    ],
  });
  assert.deepEqual(findings.forbiddenNames, [
    copiedKey,
    releaseKey,
    releaseKeyText,
    releaseP12,
  ]);
  assert.equal(
    findings.markerFiles,
    [copiedKey, renamedKey, releasePem].join("\n"),
  );

  assert.throws(
    () =>
      assertProxyFixturePathsTracked(
        tracked.map((file) =>
          file === proxyTlsFixture.privateKey ? renamedKey : file,
        ),
      ),
    /must remain tracked/,
    "renaming the exempt fixture must fail closed",
  );
  const privateKeyBytes = fs.readFileSync(
    path.join(repoRoot, proxyTlsFixture.privateKey),
  );
  assert.throws(
    () =>
      assertPinnedProxyFixtureFile(
        proxyTlsFixture.privateKey,
        Buffer.concat([privateKeyBytes, Buffer.from("\n")]),
      ),
    /digest changed/,
    "tampering with the exempt fixture must fail closed",
  );
  const consumerSource = fs.readFileSync(
    path.join(repoRoot, proxyTlsFixture.consumer),
    "utf8",
  );
  assert.throws(
    () =>
      assertProxyFixtureConsumer(
        consumerSource.replace(
          "key: fs.readFileSync(serverKeyPath),",
          "key: Buffer.alloc(0),",
        ),
      ),
    /local HTTPS target/,
    "disconnecting the fixture from the proxy E2E must fail closed",
  );
  assert.throws(
    () =>
      assertProxyFixtureConsumers(
        tracked,
        proxyTlsFixture.privateKey,
        [
          ...expectedProxyFixtureConsumers(tracked),
          "scripts/test-unrelated-private-key-consumer.mjs",
        ],
      ),
    /referenced only/,
    "an additional private-key consumer must fail closed",
  );
};

const scriptsMatching = (pattern) =>
  fs
    .readdirSync(path.join(repoRoot, "scripts"), { withFileTypes: true })
    .filter((entry) => entry.isFile() && pattern.test(entry.name))
    .map((entry) => path.join(repoRoot, "scripts", entry.name));

const discoverSignerPath = (workflow) => {
  const signer = path.join(
    repoRoot,
    "scripts",
    "fixtures",
    "run-project-update-signer-test-only.mjs",
  );
  assert.equal(
    fs.existsSync(signer),
    true,
    "test-only project update signer fixture is missing",
  );
  assert.equal(
    workflow.includes(path.basename(signer)),
    false,
    "release workflow references the test-only signer fixture",
  );
  return signer;
};

const createIsolatedSignerTree = ({
  algorithm = projectUpdateAlgorithm,
  bundleId,
  destinationRoot,
  fullKeyId,
  payloadDomain,
  policyTemplate,
  publicKeyRawBase64,
  signerPath,
  sourceRoot,
}) => {
  const relativeSignerPath = path.relative(sourceRoot, signerPath);
  assert.equal(
    path.isAbsolute(relativeSignerPath),
    false,
    "isolated signer path must remain relative to its source tree",
  );
  assert.doesNotMatch(
    relativeSignerPath,
    /^(?:\.\.(?:\/|\\|$))/,
    "isolated signer path escapes its source tree",
  );
  for (const directoryName of ["scripts", "resources"]) {
    const source = path.join(sourceRoot, directoryName);
    if (fs.existsSync(source)) {
      fs.cpSync(source, path.join(destinationRoot, directoryName), {
        filter: (entry) => path.basename(entry) !== "node_modules",
        recursive: true,
      });
    }
  }
  const sourcePackage = path.join(sourceRoot, "package.json");
  if (fs.existsSync(sourcePackage)) {
    fs.copyFileSync(sourcePackage, path.join(destinationRoot, "package.json"));
  }

  const publicKeyRaw = strictBase64(
    publicKeyRawBase64,
    "isolated signer raw Ed25519 public key",
  );
  assert.equal(publicKeyRaw.length, 32);
  const derivedKeyId = `ed25519:${sha256(publicKeyRaw)}`;
  assert.equal(
    fullKeyId,
    derivedKeyId,
    "isolated signer policy keyId does not match its raw public key",
  );
  const policy = structuredClone(policyTemplate ?? {});
  const normalizedKeys = {
    algorithm: new Set([
      "alg",
      "algorithm",
      "signaturealg",
      "signaturealgorithm",
      "signingalgorithm",
    ]),
    bundleId: new Set([
      "applicationid",
      "bundleid",
      "bundleidentifier",
    ]),
    keyId: new Set([
      "ed25519publickeyid",
      "keyid",
      "publickeyid",
    ]),
    payloadDomain: new Set([
      "domain",
      "payloaddomain",
      "payloadtype",
      "schema",
      "signaturedomain",
    ]),
    publicKey: new Set([
      "ed25519publickeybase64",
      "publickeybase64",
      "publickeyrawbase64",
    ]),
    publicKeyHash: new Set([
      "publickeyrawsha256",
      "publickeysha256",
    ]),
  };
  const replacements = {
    algorithm: 0,
    bundleId: 0,
    keyId: 0,
    payloadDomain: 0,
    publicKey: 0,
    publicKeyPath: [],
  };
  const configurePolicy = (value) => {
    if (!value || typeof value !== "object" || Array.isArray(value)) return;
    for (const [key, child] of Object.entries(value)) {
      const normalized = key.toLowerCase().replace(/[^a-z0-9]/g, "");
      if (normalizedKeys.algorithm.has(normalized)) {
        value[key] = algorithm;
        replacements.algorithm += 1;
      } else if (normalizedKeys.bundleId.has(normalized)) {
        value[key] = bundleId;
        replacements.bundleId += 1;
      } else if (normalizedKeys.keyId.has(normalized)) {
        value[key] = fullKeyId;
        replacements.keyId += 1;
      } else if (normalizedKeys.payloadDomain.has(normalized)) {
        value[key] = payloadDomain;
        replacements.payloadDomain += 1;
      } else if (normalizedKeys.publicKey.has(normalized)) {
        value[key] = publicKeyRawBase64;
        replacements.publicKey += 1;
      } else if (
        normalized === "publickey" &&
        typeof child === "string"
      ) {
        value[key] = publicKeyRawBase64;
        replacements.publicKey += 1;
      } else if (
        normalized === "publickey" &&
        child &&
        typeof child === "object" &&
        child.encoding === "base64" &&
        typeof child.value === "string"
      ) {
        child.value = publicKeyRawBase64;
        replacements.publicKey += 1;
      } else if (normalizedKeys.publicKeyHash.has(normalized)) {
        value[key] = derivedKeyId.slice("ed25519:".length);
      } else if (
        normalized === "publickeypath" &&
        typeof child === "string"
      ) {
        replacements.publicKeyPath.push(child);
      }
      configurePolicy(value[key]);
    }
  };
  configurePolicy(policy);
  if (
    Object.values(replacements)
      .filter((value) => typeof value === "number")
      .every((value) => value === 0)
  ) {
    Object.assign(policy, {
      algorithm,
      bundleId,
      keyId: fullKeyId,
      payloadDomain,
      publicKeyBase64: publicKeyRawBase64,
      publicKeyRawSha256: derivedKeyId.slice("ed25519:".length),
      schema: payloadDomain,
    });
    delete policy.status;
  }
  const publicKeyPem = createPublicKey({
    format: "jwk",
    key: {
      crv: "Ed25519",
      kty: "OKP",
      x: publicKeyRaw.toString("base64url"),
    },
  }).export({ format: "pem", type: "spki" });
  const correctPolicyHashes = (value) => {
    if (!value || typeof value !== "object" || Array.isArray(value)) return;
    for (const [key, child] of Object.entries(value)) {
      const normalized = key.toLowerCase().replace(/[^a-z0-9]/g, "");
      if (normalized === "publickeyrawsha256") {
        value[key] = derivedKeyId.slice("ed25519:".length);
      } else if (normalized === "publickeysha256") {
        value[key] = replacements.publicKeyPath.length > 0
          ? sha256(publicKeyPem)
          : derivedKeyId.slice("ed25519:".length);
      }
      correctPolicyHashes(value[key]);
    }
  };
  correctPolicyHashes(policy);
  const isolatedPolicyPath = path.join(
    destinationRoot,
    "resources",
    "updater",
    "project-signing-policy.json",
  );
  fs.mkdirSync(path.dirname(isolatedPolicyPath), { recursive: true });
  fs.writeFileSync(
    isolatedPolicyPath,
    `${JSON.stringify(policy, null, 2)}\n`,
  );
  for (const relativePublicKeyPath of replacements.publicKeyPath) {
    assert.equal(
      path.isAbsolute(relativePublicKeyPath),
      false,
      "isolated signer public key path must be relative",
    );
    const destination = path.resolve(
      path.dirname(isolatedPolicyPath),
      relativePublicKeyPath,
    );
    assert.equal(
      destination.startsWith(`${destinationRoot}${path.sep}`),
      true,
      "isolated signer public key path escapes the isolated tree",
    );
    fs.mkdirSync(path.dirname(destination), { recursive: true });
    fs.writeFileSync(destination, publicKeyPem);
  }

  const isolatedSignerPath = path.join(
    destinationRoot,
    relativeSignerPath,
  );
  assert.equal(
    fs.existsSync(isolatedSignerPath),
    true,
    `isolated signer copy is missing: ${isolatedSignerPath}`,
  );
  return {
    policyPath: isolatedPolicyPath,
    signerPath: isolatedSignerPath,
  };
};

const runIsolatedSignerTreeSelfTest = () => {
  const tempRoot = fs.mkdtempSync(
    path.join(os.tmpdir(), "logseq-isolated-signer-self-test-"),
  );
  try {
    const sourceRoot = path.join(tempRoot, "source");
    const sourceSignerPath = path.join(
      sourceRoot,
      "scripts",
      "sign-project-update.mjs",
    );
    const sourcePolicyPath = path.join(
      sourceRoot,
      "resources",
      "updater",
      "project-signing-policy.json",
    );
    fs.mkdirSync(path.dirname(sourceSignerPath), { recursive: true });
    fs.mkdirSync(path.dirname(sourcePolicyPath), { recursive: true });
    fs.writeFileSync(
      sourcePolicyPath,
      '{"status":"UNCONFIGURED"}\n',
    );
    fs.writeFileSync(
      sourceSignerPath,
      [
        'import fs from "node:fs";',
        'import { createHash, createPrivateKey, createPublicKey, sign } from "node:crypto";',
        'const policy = JSON.parse(fs.readFileSync(new URL("../resources/updater/project-signing-policy.json", import.meta.url), "utf8"));',
        'const key = createPrivateKey({ key: Buffer.from(process.env.TEST_SIGNER_KEY_BASE64, "base64"), format: "der", type: "pkcs8" });',
        'const raw = Buffer.from(createPublicKey(key).export({ format: "jwk" }).x, "base64url");',
        'const keyId = `ed25519:${createHash("sha256").update(raw).digest("hex")}`;',
        `if (policy.algorithm !== ${JSON.stringify(projectUpdateAlgorithm)}) throw new Error("isolated policy algorithm mismatch");`,
        'if (policy.publicKeyBase64 !== raw.toString("base64") || policy.keyId !== keyId) throw new Error("isolated policy mismatch");',
        'const outputIndex = process.argv.indexOf("--signature-output");',
        'if (outputIndex < 0 || !process.argv[outputIndex + 1]) throw new Error("missing signature output");',
        'fs.writeFileSync(process.argv[outputIndex + 1], sign(null, Buffer.from("isolated signer fixture"), key).toString("base64"));',
        "",
      ].join("\n"),
    );

    const signingKeys = generateKeyPairSync("ed25519");
    const publicKeyRaw = Buffer.from(
      signingKeys.publicKey.export({ format: "jwk" }).x,
      "base64url",
    );
    const publicKeyRawBase64 = publicKeyRaw.toString("base64");
    const fullKeyId = `ed25519:${sha256(publicKeyRaw)}`;
    const privateKeyPkcs8Base64 = signingKeys.privateKey
      .export({ format: "der", type: "pkcs8" })
      .toString("base64");
    const isolated = createIsolatedSignerTree({
      bundleId: "com.logseq.logseq",
      destinationRoot: path.join(tempRoot, "isolated"),
      fullKeyId,
      payloadDomain: "logseq-selfhost-project-update-signature-v1",
      policyTemplate: JSON.parse(fs.readFileSync(sourcePolicyPath, "utf8")),
      publicKeyRawBase64,
      signerPath: sourceSignerPath,
      sourceRoot,
    });
    const signatureOutput = path.join(tempRoot, "signature.txt");
    const isolatedPolicy = JSON.parse(
      fs.readFileSync(isolated.policyPath, "utf8"),
    );
    assert.equal(isolatedPolicy.algorithm, projectUpdateAlgorithm);
    fs.writeFileSync(
      isolated.policyPath,
      `${JSON.stringify(
        { ...isolatedPolicy, algorithm: "Ed25519" },
        null,
        2,
      )}\n`,
    );
    const rejected = command(
      process.execPath,
      [
        isolated.signerPath,
        "--signature-output",
        signatureOutput,
      ],
      {
        allowFailure: true,
        env: {
          ...process.env,
          TEST_SIGNER_KEY_BASE64: privateKeyPkcs8Base64,
        },
      },
    );
    assert.notEqual(
      rejected.status,
      0,
      "isolated signer accepted a generic Ed25519 algorithm label",
    );
    assert.match(rejected.output, /isolated policy algorithm mismatch/);
    assert.equal(fs.existsSync(signatureOutput), false);
    fs.writeFileSync(
      isolated.policyPath,
      `${JSON.stringify(isolatedPolicy, null, 2)}\n`,
    );
    const result = command(
      process.execPath,
      [
        isolated.signerPath,
        "--signature-output",
        signatureOutput,
      ],
      {
        allowFailure: true,
        env: {
          ...process.env,
          TEST_SIGNER_KEY_BASE64: privateKeyPkcs8Base64,
        },
      },
    );
    assert.equal(result.status, 0, result.output);
    const signature = fs.readFileSync(signatureOutput, "utf8");
    assert.equal(
      cryptoVerify(
        null,
        Buffer.from("isolated signer fixture"),
        signingKeys.publicKey,
        Buffer.from(signature, "base64"),
      ),
      true,
      "isolated signer did not emit a valid signature",
    );
    assert.equal(
      fs.readFileSync(sourcePolicyPath, "utf8"),
      '{"status":"UNCONFIGURED"}\n',
      "isolated signer setup modified its source policy",
    );
    assert.equal(
      JSON.parse(fs.readFileSync(isolated.policyPath, "utf8")).keyId,
      fullKeyId,
    );
  } finally {
    fs.rmSync(tempRoot, { force: true, recursive: true });
  }
};

const exactProjectSigningAlgorithm = "ed25519-sha512-manifest-v1";

const withIsolatedProductionSigner = (test) => {
  const tempRoot = fs.mkdtempSync(
    path.join(os.tmpdir(), "logseq-isolated-production-signer-"),
  );
  try {
    const sourceRoot = path.join(tempRoot, "source");
    const workflow = fs.readFileSync(workflowPath, "utf8");
    const productionSignerPath = discoverSignerPath(workflow);
    const relativeSignerPath = path.relative(
      repoRoot,
      productionSignerPath,
    );
    const relativeVerifierPath =
      "scripts/verify-project-signed-macos-update.mjs";
    const copyRelativeModuleClosure = (
      relativePath,
      seen = new Set(),
    ) => {
      if (seen.has(relativePath)) return;
      seen.add(relativePath);
      const source = path.join(repoRoot, relativePath);
      const destination = path.join(sourceRoot, relativePath);
      fs.mkdirSync(path.dirname(destination), { recursive: true });
      fs.copyFileSync(source, destination);
      const sourceText = fs.readFileSync(source, "utf8");
      for (const match of sourceText.matchAll(
        /(?:from\s+|import\s*\(\s*)["'](\.[^"']+\.(?:c?js|mjs))["']/g,
      )) {
        const dependency = path
          .normalize(path.join(path.dirname(relativePath), match[1]))
          .replaceAll(path.sep, "/");
        copyRelativeModuleClosure(dependency, seen);
      }
    };
    copyRelativeModuleClosure(relativeSignerPath);
    copyRelativeModuleClosure(relativeVerifierPath);

    const signingKeys = generateKeyPairSync("ed25519");
    const publicKeyRaw = Buffer.from(
      signingKeys.publicKey.export({ format: "jwk" }).x,
      "base64url",
    );
    const publicKeyRawBase64 = publicKeyRaw.toString("base64");
    const fullKeyId = `ed25519:${sha256(publicKeyRaw)}`;
    const policy = JSON.parse(fs.readFileSync(policyPath, "utf8"));
    Object.assign(policy, {
      algorithm: exactProjectSigningAlgorithm,
      keyId: fullKeyId,
      publicKeyBase64: publicKeyRawBase64,
    });
    const sourcePolicyPath = path.join(
      sourceRoot,
      "resources",
      "updater",
      "project-signing-policy.json",
    );
    fs.mkdirSync(path.dirname(sourcePolicyPath), { recursive: true });
    const sourcePolicyText = `${JSON.stringify(policy, null, 2)}\n`;
    fs.writeFileSync(sourcePolicyPath, sourcePolicyText);

    const privateKeyBase64 = signingKeys.privateKey
      .export({ format: "der", type: "pkcs8" })
      .toString("base64");
    const signingEnvironmentNames = signingVariableNames(workflow);
    assert.ok(
      signingEnvironmentNames.length > 0,
      "release workflow exposes no Ed25519 signing environment",
    );
    const signerEnv = { ...process.env, NODE_ENV: "test" };
    for (const name of signingEnvironmentNames) {
      signerEnv[name] = privateKeyBase64;
    }

    const makeFixture = (algorithm, label) => {
      const destinationRoot = path.join(
        tempRoot,
        label.replaceAll(/[^a-z0-9]+/gi, "-"),
      );
      const isolated = createIsolatedSignerTree({
        algorithm,
        bundleId: policy.bundleIdentifier,
        destinationRoot,
        fullKeyId,
        payloadDomain: policy.payloadDomain,
        policyTemplate: { ...policy, algorithm },
        publicKeyRawBase64,
        signerPath: path.join(sourceRoot, relativeSignerPath),
        sourceRoot,
      });
      const archive = path.join(destinationRoot, "update.zip");
      const metadata = path.join(destinationRoot, "latest-mac.yml");
      const signatureOutput = path.join(
        destinationRoot,
        "signature.json",
      );
      const archiveBytes = Buffer.from(
        `isolated production signer ${label}\n`,
      );
      fs.writeFileSync(archive, archiveBytes);
      const sha512 = createHash("sha512")
        .update(archiveBytes)
        .digest("base64");
      fs.writeFileSync(
        metadata,
        [
          "version: 2.0.1-selfhost.6",
          "files:",
          "  - url: update.zip",
          `    sha512: ${sha512}`,
          `    size: ${archiveBytes.length}`,
          "path: update.zip",
          `sha512: ${sha512}`,
          "",
        ].join("\n"),
      );
      const signerArgs = [
        isolated.signerPath,
        "--test-only",
        "--arch",
        "arm64",
        "--version",
        "2.0.1-selfhost.6",
        "--archive",
        archive,
        "--metadata",
        metadata,
        "--signature-output",
        signatureOutput,
      ];
      const verifierArgs = [
        fs.realpathSync(
          path.join(destinationRoot, relativeVerifierPath),
        ),
        "--arch",
        "arm64",
        "--version",
        "2.0.1-selfhost.6",
        "--archive",
        archive,
        "--metadata",
        metadata,
      ];
      return {
        algorithm,
        archive,
        archiveBytes,
        isolated,
        metadata,
        signatureOutput,
        signerArgs,
        verifierArgs,
      };
    };

    test({
      makeFixture,
      signerEnv,
    });
    assert.equal(
      fs.readFileSync(sourcePolicyPath, "utf8"),
      sourcePolicyText,
      "isolated signer contract modified its source policy",
    );
  } finally {
    fs.rmSync(tempRoot, { force: true, recursive: true });
  }
};

const runIsolatedSignerAlgorithmRejections = () =>
  withIsolatedProductionSigner(({ makeFixture, signerEnv }) => {
    for (const [label, algorithm] of [
      ["generic", "Ed25519"],
      ["case-variant", "ED25519-SHA512-MANIFEST-V1"],
      ["near-match", "ed25519-sha512-manifest-v2"],
      ["empty", ""],
    ]) {
      const fixture = makeFixture(algorithm, label);
      const metadataBefore = fs.readFileSync(fixture.metadata, "utf8");
      const result = command(process.execPath, fixture.signerArgs, {
        allowFailure: true,
        env: signerEnv,
      });
      assert.notEqual(result.status, 0, `${label} algorithm was accepted`);
      assert.equal(
        fs.existsSync(fixture.signatureOutput),
        false,
        `${label} algorithm emitted a signature`,
      );
      assert.equal(
        fs.readFileSync(fixture.metadata, "utf8"),
        metadataBefore,
        `${label} algorithm modified release metadata`,
      );
      console.log(
        `[project-updater] PASS isolated signer algorithm rejection: ${label}`,
      );
    }
  });

const runIsolatedSignerExactAlgorithmRoundTrip = ({
  exactPolicyControl = false,
} = {}) =>
  withIsolatedProductionSigner(({ makeFixture, signerEnv }) => {
    const fixture = makeFixture(
      exactProjectSigningAlgorithm,
      exactPolicyControl
        ? "exact-policy-signer-verifier-control"
        : "exact-production-algorithm",
    );
    if (exactPolicyControl) {
      const isolatedPolicy = JSON.parse(
        fs.readFileSync(fixture.isolated.policyPath, "utf8"),
      );
      isolatedPolicy.algorithm = exactProjectSigningAlgorithm;
      fs.writeFileSync(
        fixture.isolated.policyPath,
        `${JSON.stringify(isolatedPolicy, null, 2)}\n`,
      );
    }
    const signed = command(process.execPath, fixture.signerArgs, {
      allowFailure: true,
      env: signerEnv,
    });
    assert.equal(
      signed.status,
      0,
      `exact production algorithm was rejected:\n${signed.output}`,
    );
    assert.equal(
      fs.existsSync(fixture.signatureOutput),
      true,
      "exact production algorithm emitted no detached signature",
    );
    assert.equal(
      JSON.parse(
        fs.readFileSync(fixture.isolated.policyPath, "utf8"),
      ).algorithm,
      exactProjectSigningAlgorithm,
      "isolated fixture did not preserve the exact production algorithm",
    );
    const verified = command(process.execPath, fixture.verifierArgs, {
      allowFailure: true,
    });
    assert.equal(verified.status, 0, verified.output);
    assert.match(
      verified.output,
      /\[project-update-verify\] OK/,
      "isolated verifier did not execute its CLI entrypoint",
    );
    const signedMetadata = fs.readFileSync(fixture.metadata, "utf8");

    fs.appendFileSync(fixture.archive, "tampered");
    const artifactTamper = command(
      process.execPath,
      fixture.verifierArgs,
      { allowFailure: true },
    );
    assert.notEqual(artifactTamper.status, 0, "artifact tamper was accepted");
    console.log("[project-updater] PASS isolated verifier rejects artifact tamper");
    fs.writeFileSync(fixture.archive, fixture.archiveBytes);

    const signatureTamper = signedMetadata.replace(
      /(^  signature: )([A-Za-z0-9+/])/m,
      (_, prefix, firstCharacter) =>
        `${prefix}${firstCharacter === "A" ? "B" : "A"}`,
    );
    assert.notEqual(signatureTamper, signedMetadata);
    fs.writeFileSync(fixture.metadata, signatureTamper);
    const invalidSignature = command(
      process.execPath,
      fixture.verifierArgs,
      { allowFailure: true },
    );
    assert.notEqual(
      invalidSignature.status,
      0,
      "signature tamper was accepted",
    );
    console.log("[project-updater] PASS isolated verifier rejects signature tamper");

    const metadataTamper = signedMetadata.replace(
      /^  bundleId: .+$/m,
      "  bundleId: com.example.tampered",
    );
    assert.notEqual(metadataTamper, signedMetadata);
    fs.writeFileSync(fixture.metadata, metadataTamper);
    const invalidMetadata = command(
      process.execPath,
      fixture.verifierArgs,
      { allowFailure: true },
    );
    assert.notEqual(
      invalidMetadata.status,
      0,
      "signed metadata tamper was accepted",
    );
    console.log("[project-updater] PASS isolated verifier rejects metadata tamper");
  });

const workflowJobSource = (workflow, jobName) => {
  const match = workflow.match(
    new RegExp(
      `^  ${jobName}:\\n([\\s\\S]*?)(?=^  [a-zA-Z0-9_-]+:\\n|(?![\\s\\S]))`,
      "m",
    ),
  );
  assert.ok(match, `workflow job ${jobName} is missing`);
  return match[1];
};

const workflowJobNames = (workflow) =>
  [...workflow.matchAll(/^  ([a-zA-Z0-9_-]+):\n/g)].map(
    ([, jobName]) => jobName,
  );

const workflowJobNeeds = (jobSource) => {
  const inline = jobSource.match(/^    needs:\s*\[([^\]]*)\]/m)?.[1];
  if (inline !== undefined) {
    return inline
      .split(",")
      .map((name) => name.trim())
      .filter(Boolean);
  }
  const block = jobSource.match(
    /^    needs:\s*\n((?:^      -\s*[^\n]+\n?)+)/m,
  )?.[1];
  return block
    ? [...block.matchAll(/^      -\s*([^\s#]+)/gm)].map((match) => match[1])
    : [];
};

const workflowJobCondition = (jobSource) => {
  const scalar = jobSource.match(/^    if:\s*(\S.*)$/m)?.[1];
  if (scalar && !/^[>|][-+]?\s*$/.test(scalar)) return scalar.trim();
  const block = jobSource.match(
    /^    if:\s*[>|][-+]?\s*\n((?:^      [^\n]*\n?)+)/m,
  )?.[1];
  assert.ok(block, "workflow job is missing an auditable if condition");
  return block
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean)
    .join(" ");
};

const workflowJobPermissions = (jobSource) => {
  const block = jobSource.match(
    /^    permissions:\s*\n((?:^      [a-z-]+:\s*[^\n]+\n?)+)/m,
  )?.[1];
  assert.ok(block, "workflow job must declare explicit permissions");
  return new Map(
    [...block.matchAll(/^      ([a-z-]+):\s*([^\s#]+)/gm)].map((match) => [
      match[1],
      match[2],
    ]),
  );
};

const stripExpressionEnvelope = (expression) =>
  expression
    .trim()
    .replace(/^\$\{\{\s*/, "")
    .replace(/\s*\}\}$/, "")
    .trim();

const hasBalancedOuterParentheses = (expression) => {
  if (!expression.startsWith("(") || !expression.endsWith(")")) return false;
  let depth = 0;
  let quote = null;
  for (let index = 0; index < expression.length; index += 1) {
    const character = expression[index];
    if (quote) {
      if (character === quote && expression[index - 1] !== "\\") quote = null;
      continue;
    }
    if (character === "'" || character === '"') {
      quote = character;
      continue;
    }
    if (character === "(") depth += 1;
    if (character === ")") depth -= 1;
    if (depth === 0 && index < expression.length - 1) return false;
  }
  return depth === 0;
};

const stripOuterParentheses = (expression) => {
  let result = expression.trim();
  while (hasBalancedOuterParentheses(result)) {
    result = result.slice(1, -1).trim();
  }
  return result;
};

const splitTopLevel = (expression, operator) => {
  const parts = [];
  let depth = 0;
  let quote = null;
  let start = 0;
  for (let index = 0; index < expression.length; index += 1) {
    const character = expression[index];
    if (quote) {
      if (character === quote && expression[index - 1] !== "\\") quote = null;
      continue;
    }
    if (character === "'" || character === '"') {
      quote = character;
      continue;
    }
    if (character === "(") depth += 1;
    if (character === ")") depth -= 1;
    if (depth === 0 && expression.startsWith(operator, index)) {
      parts.push(expression.slice(start, index).trim());
      start = index + operator.length;
      index += operator.length - 1;
    }
  }
  if (parts.length === 0) return [expression.trim()];
  parts.push(expression.slice(start).trim());
  return parts;
};

const evaluateSignerCondition = (condition, scenario) => {
  const unwrappedCondition = stripExpressionEnvelope(condition);
  const evaluate = (rawExpression) => {
    const expression = stripOuterParentheses(rawExpression.trim());
    const disjunction = splitTopLevel(expression, "||");
    if (disjunction.length > 1) return disjunction.some(evaluate);
    const conjunction = splitTopLevel(expression, "&&");
    if (conjunction.length > 1) return conjunction.every(evaluate);
    if (/^!contains\(/.test(expression)) return true;
    if (expression.startsWith("!") && !expression.startsWith("!=")) {
      return !evaluate(expression.slice(1));
    }
    if (expression === "always()") return true;
    if (expression === "cancelled()") {
      return Object.values(scenario.needs).includes("cancelled");
    }
    if (expression === "failure()") {
      return Object.values(scenario.needs).includes("failure");
    }
    if (expression === "success()") {
      return Object.values(scenario.needs).every((result) => result === "success");
    }
    const needResult = expression.match(
      /^needs\.([a-zA-Z0-9_-]+)\.result\s*(==|!=)\s*'([^']+)'$/,
    );
    if (needResult) {
      const actual = scenario.needs[needResult[1]];
      return needResult[2] === "=="
        ? actual === needResult[3]
        : actual !== needResult[3];
    }
    const androidInput = expression.match(
      /^github\.event\.inputs\.build-android\s*(==|!=)\s*'true'$/,
    );
    if (androidInput) {
      return androidInput[1] === "=="
        ? scenario.buildAndroid
        : !scenario.buildAndroid;
    }
    // Release eligibility is held valid in these dependency-gate scenarios.
    return true;
  };
  const hasStatusOverride = /\b(?:always|cancelled|failure)\(\)/.test(
    unwrappedCondition,
  );
  if (
    !hasStatusOverride &&
    Object.values(scenario.needs).some((result) => result !== "success")
  ) {
    return false;
  }
  return evaluate(unwrappedCondition);
};

const workflowDependencyPath = (workflow, start, target, visited = new Set()) => {
  if (start === target) return [start];
  if (visited.has(start)) return undefined;
  const nextVisited = new Set(visited).add(start);
  for (const dependency of workflowJobNeeds(workflowJobSource(workflow, start))) {
    const suffix = workflowDependencyPath(
      workflow,
      dependency,
      target,
      nextVisited,
    );
    if (suffix) return [start, ...suffix];
  }
  return undefined;
};

const assertSignerDependencyGate = (needs, condition) => {
  assert.deepEqual(
    [...needs].sort(),
    ["build-android", "release-assets-preflight", "release-rehearsal-gate"].sort(),
    "signer dependencies must be exactly desktop preflight, rehearsal, and optional Android",
  );

  const baseNeeds = {
    "build-android": "skipped",
    "release-assets-preflight": "success",
    "release-rehearsal-gate": "success",
  };
  const scenarios = [
    ["Android disabled after successful desktop rehearsal", false, baseNeeds, true],
    ["Android enabled and successful", true, { ...baseNeeds, "build-android": "success" }, true],
    ["Android enabled but skipped", true, baseNeeds, false],
    ["Android enabled but failed", true, { ...baseNeeds, "build-android": "failure" }, false],
    [
      "desktop preflight failed",
      false,
      { ...baseNeeds, "release-assets-preflight": "failure" },
      false,
    ],
    [
      "release rehearsal failed",
      false,
      { ...baseNeeds, "release-rehearsal-gate": "failure" },
      false,
    ],
  ];
  for (const [label, buildAndroid, needs, expected] of scenarios) {
    assert.equal(
      evaluateSignerCondition(condition, { buildAndroid, needs }),
      expected,
      `signer dependency gate violates scenario: ${label}`,
    );
  }
};

const assertDirectSuccessGate = (label, needs, condition) => {
  const expression = stripExpressionEnvelope(condition);
  assert.match(
    expression,
    /\balways\(\)/,
    `${label} must override transitive skipped-ancestor propagation`,
  );
  const allSuccess = Object.fromEntries(
    needs.map((name) => [name, "success"]),
  );
  assert.equal(
    evaluateSignerCondition(condition, {
      buildAndroid: false,
      needs: allSuccess,
    }),
    true,
    `${label} must run when every direct dependency succeeds`,
  );
  for (const name of needs) {
    for (const result of ["failure", "skipped", "cancelled"]) {
      assert.equal(
        evaluateSignerCondition(condition, {
          buildAndroid: false,
          needs: { ...allSuccess, [name]: result },
        }),
        false,
        `${label} must fail closed when ${name} is ${result}`,
      );
    }
  }
};

const assertProtectedReleaseDag = (workflow) => {
  const signer = workflowJobSource(workflow, "selfhost-release-signing");
  assertSignerDependencyGate(
    workflowJobNeeds(signer),
    workflowJobCondition(signer),
  );

  const verifier = workflowJobSource(workflow, "selfhost-release-verifier");
  const publisher = workflowJobSource(workflow, "selfhost-release");
  for (const required of [
    "build-android",
    "release-assets-preflight",
    "release-rehearsal-gate",
  ]) {
    assert.ok(
      workflowDependencyPath(workflow, "selfhost-release", required),
      `protected publication DAG has no path through ${required}`,
    );
  }
  assert.ok(
    workflowJobNeeds(verifier).includes("selfhost-release-signing"),
    "secretless verifier does not depend on the signer",
  );
  assert.ok(
    workflowJobNeeds(publisher).includes("selfhost-release-verifier"),
    "publisher does not depend on the secretless verifier",
  );
  assert.deepEqual(
    workflowJobNeeds(verifier),
    ["selfhost-release-signing", "release-rehearsal-gate"],
    "secretless verifier dependencies drifted",
  );
  assertDirectSuccessGate(
    "secretless verifier",
    workflowJobNeeds(verifier),
    workflowJobCondition(verifier),
  );
  assert.deepEqual(
    workflowJobNeeds(publisher),
    ["selfhost-release-verifier"],
    "publisher dependencies drifted",
  );
  assertDirectSuccessGate(
    "protected publisher",
    workflowJobNeeds(publisher),
    workflowJobCondition(publisher),
  );

  assert.deepEqual(workflowJobPermissions(signer), new Map([
    ["actions", "read"],
    ["contents", "read"],
  ]));
  assert.deepEqual(workflowJobPermissions(verifier), new Map([
    ["actions", "read"],
    ["contents", "read"],
  ]));
  assert.deepEqual(workflowJobPermissions(publisher), new Map([
    ["actions", "read"],
    ["contents", "write"],
  ]));
  assert.match(signer, /environment:\s*selfhost-release-signing/);
  assert.match(signer, /LOGSEQ_PROJECT_UPDATE_SIGNING_KEY_PKCS8_BASE64/);
  assert.doesNotMatch(verifier, /environment:|\bsecrets\./);
  assert.match(publisher, /environment:\s*selfhost-production/);
};

const workflowJobInvocationCount = (workflow, jobName, invocation) =>
  workflowJobSource(workflow, jobName).split(invocation).length - 1;

const signingVariableNames = (workflow) => {
  assert.doesNotMatch(
    workflow,
    /LOGSEQ_PROJECT_UPDATE_SIGNER_TEST_ONLY_KEY_BASE64/,
    "release workflow references the test-only signer key",
  );
  return ["LOGSEQ_PROJECT_UPDATE_SIGNER_TEST_ONLY_KEY_BASE64"];
};

const discoverHelperBuildPath = () => {
  const candidates = fs
    .readdirSync(path.join(repoRoot, "scripts"), { withFileTypes: true })
    .filter(
      (entry) =>
        entry.isFile() &&
        /^(?=.*build)(?=.*update)(?=.*helper)/i.test(entry.name),
    )
    .map((entry) => path.join(repoRoot, "scripts", entry.name));
  assert.equal(
    candidates.length,
    1,
    `expected one native update-helper builder, found ${candidates
      .map((file) => path.basename(file))
      .join(", ") || "none"}`,
  );
  return candidates[0];
};

const scriptCommand = (script, args) =>
  path.extname(script) === ".mjs"
    ? [process.execPath, [script, ...args]]
    : [script, args];

const canonicalNativePayload = ({
  arch,
  bundleId,
  payloadDomain,
  sha512,
  size,
  version,
}) =>
  [
    payloadDomain,
    `bundle-id=${bundleId}`,
    `version=${version}`,
    `arch=${arch}`,
    `zip-size=${size}`,
    `zip-sha512=${sha512}`,
    "",
  ].join("\n");

const electronFixture = () => {
  const candidates = [
    process.env.LOGSEQ_ELECTRON_APP_FIXTURE,
    path.join(
      repoRoot,
      "static",
      "node_modules",
      "electron",
      "dist",
      "Electron.app",
    ),
    path.join(
      "/Users/cfenglv/Documents/WorkSpace/logseq_tweak/logseq-release-selfhost-5",
      "static",
      "node_modules",
      ".pnpm",
      "electron@42.4.1",
      "node_modules",
      "electron",
      "dist",
      "Electron.app",
    ),
  ].filter(Boolean);
  const app = candidates.find((candidate) => fs.existsSync(candidate));
  if (!app) {
    throw new Error(
      "physical ad-hoc DR test requires Electron.app or LOGSEQ_ELECTRON_APP_FIXTURE",
    );
  }
  return app;
};

const makeMinimalApp = ({
  destination,
  fixture,
  marker,
  requirement,
  version,
}) => {
  command("ditto", [fixture, destination]);
  const info = path.join(destination, "Contents", "Info.plist");
  for (const [key, value] of [
    ["CFBundleIdentifier", "com.logseq.logseq"],
    ["CFBundleName", "Logseq"],
    ["CFBundleDisplayName", "Logseq"],
    ["CFBundleShortVersionString", version],
    ["CFBundleVersion", version],
  ]) {
    command("plutil", ["-replace", key, "-string", value, info]);
  }
  fs.writeFileSync(
    path.join(destination, "Contents", "Resources", "update-test-marker.txt"),
    marker,
  );
  command("xattr", ["-dr", "com.apple.FinderInfo", destination]);
  command("xattr", ["-dr", "com.apple.ResourceFork", destination]);
  command("codesign", [
    "--force",
    "--deep",
    "--timestamp=none",
    "--sign",
    "-",
    destination,
  ]);
  command("codesign", [
    "--force",
    "--timestamp=none",
    "--sign",
    "-",
    `-r=designated => ${requirement}`,
    destination,
  ]);
  command("codesign", [
    "--verify",
    "--deep",
    "--strict",
    "--all-architectures",
    destination,
  ]);
  return destination;
};

const signatureDetails = (app) =>
  command("codesign", ["-dvvv", "-r-", app]).output;

const designatedRequirement = (app) => {
  const requirement = signatureDetails(app).match(
    /^(?:# )?designated => (.+)$/m,
  )?.[1];
  assert.ok(requirement);
  return requirement;
};

const satisfies = (requirement, app) =>
  command(
    "codesign",
    [
      "--verify",
      "--deep",
      "--strict",
      "--all-architectures",
      `-R=${requirement}`,
      app,
    ],
    { allowFailure: true },
  );

const satisfiesTopLevel = (requirement, app) =>
  command(
    "codesign",
    [
      "--verify",
      "--strict",
      "--all-architectures",
      `-R=${requirement}`,
      app,
    ],
    { allowFailure: true },
  );

const runShipIt = ({ baseline, candidate, tempRoot }) => {
  const targetApp = path.join(tempRoot, "target", "Logseq.app");
  const updateApp = path.join(tempRoot, "update", "Logseq.app");
  fs.mkdirSync(path.dirname(targetApp), { recursive: true });
  fs.mkdirSync(path.dirname(updateApp), { recursive: true });
  command("ditto", [baseline, targetApp]);
  command("ditto", [candidate, updateApp]);
  const targetBundleIdentifier = command("plutil", [
    "-extract",
    "CFBundleIdentifier",
    "raw",
    path.join(targetApp, "Contents", "Info.plist"),
  ]).output;
  const updateBundleIdentifier = command("plutil", [
    "-extract",
    "CFBundleIdentifier",
    "raw",
    path.join(updateApp, "Contents", "Info.plist"),
  ]).output;
  assert.ok(
    targetBundleIdentifier,
    "ShipIt fixture target App requires a bundle identifier",
  );
  assert.equal(
    updateBundleIdentifier,
    targetBundleIdentifier,
    "ShipIt fixture requires matching target and update bundle identifiers",
  );
  const shipIt = path.join(
    targetApp,
    "Contents",
    "Frameworks",
    "Squirrel.framework",
    "Versions",
    "A",
    "Resources",
    "ShipIt",
  );
  const before = command("plutil", [
    "-extract",
    "CFBundleShortVersionString",
    "raw",
    path.join(targetApp, "Contents", "Info.plist"),
  ]).output;
  const jobLabel = `com.logseq.project-signed-test.${process.pid}.ShipIt`;
  const userCaches = path.join(os.homedir(), "Library", "Caches");
  const stateDirectory = fs.mkdtempSync(
    path.join(userCaches, `${jobLabel}.`),
  );
  try {
    fs.chmodSync(stateDirectory, 0o700);
    const statePath = path.join(stateDirectory, "ShipItState.plist");
    fs.writeFileSync(
      statePath,
      JSON.stringify({
        updateBundleURL: new URL(`file://${updateApp}`).href,
        targetBundleURL: new URL(`file://${targetApp}`).href,
        bundleIdentifier: targetBundleIdentifier,
        launchAfterInstallation: false,
        useUpdateBundleName: false,
      }),
      { mode: 0o600 },
    );
    const result = command(shipIt, [jobLabel, statePath], {
      allowFailure: true,
    });
    const after = fs.existsSync(targetApp)
      ? command("plutil", [
          "-extract",
          "CFBundleShortVersionString",
          "raw",
          path.join(targetApp, "Contents", "Info.plist"),
        ]).output
      : "<missing>";
    return { ...result, before, after };
  } finally {
    fs.rmSync(stateDirectory, { recursive: true, force: true });
  }
};

const physicalAdHocWeakness = () => {
  if (process.platform !== "darwin") {
    throw new SkipTest("physical ad-hoc DR test requires macOS");
  }
  const tempRoot = fs.mkdtempSync(
    path.join(os.tmpdir(), "logseq-project-updater-adhoc-"),
  );
  try {
    const fixture = electronFixture();
    const requirement = 'identifier "com.logseq.logseq"';
    const baseline = makeMinimalApp({
      destination: path.join(tempRoot, "baseline", "Logseq.app"),
      fixture,
      marker: "trusted release .5",
      requirement,
      version: "2.0.1-selfhost.5",
    });
    const candidate = makeMinimalApp({
      destination: path.join(tempRoot, "candidate", "Logseq.app"),
      fixture,
      marker: "trusted release .6",
      requirement,
      version: "2.0.1-selfhost.6",
    });
    const attacker = makeMinimalApp({
      destination: path.join(tempRoot, "attacker", "Logseq.app"),
      fixture,
      marker: "attacker-controlled payload",
      requirement,
      version: "2.0.1-selfhost.6",
    });
    const oldRequirement = designatedRequirement(baseline);
    assert.equal(oldRequirement, requirement);
    assert.equal(satisfies(oldRequirement, candidate).status, 0);
    assert.equal(
      satisfies(oldRequirement, attacker).status,
      0,
      "identifier-only DR unexpectedly rejected attacker content",
    );
    console.log(
      "[project-updater] PASS security regression reproduced: identifier-only ad-hoc DR accepts attacker content",
    );

    const shipItRoot = path.join(tempRoot, "shipit");
    fs.mkdirSync(shipItRoot);
    const shipIt = runShipIt({
      baseline,
      candidate,
      tempRoot: shipItRoot,
    });
    if (shipIt.signal) {
      throw new Error(
        [
          `physical ShipIt terminated by ${shipIt.signal}`,
          `exit=${shipIt.status} before=${shipIt.before} after=${shipIt.after}`,
          shipIt.output,
        ]
          .filter(Boolean)
          .join("\n"),
      );
    } else if (
      shipIt.output.includes("SQRLShipItRequestErrorDomain") ||
      shipIt.output.includes("Could not read update request")
    ) {
      throw new ReleaseBlock(
        `physical ShipIt fixture exit=${shipIt.status} before=${shipIt.before} after=${shipIt.after}: request unreadable`,
      );
    } else {
      assert.equal(shipIt.status, 0, shipIt.output);
      assert.equal(shipIt.after, "2.0.1-selfhost.6");
      console.log(
        "[project-updater] PASS physical ShipIt replacement .5 -> .6",
      );
    }
  } finally {
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
};

const explicitCertificateHashConsumerProbe = () => {
  const baseline = process.env.LOGSEQ_CERT_HASH_DR_BASELINE_APP;
  const candidate = process.env.LOGSEQ_CERT_HASH_DR_CANDIDATE_APP;
  if (!baseline || !candidate) {
    throw new SkipTest(
      "set LOGSEQ_CERT_HASH_DR_BASELINE_APP and LOGSEQ_CERT_HASH_DR_CANDIDATE_APP",
    );
  }
  const oldRequirement = designatedRequirement(baseline);
  assert.match(oldRequirement, /certificate (?:root|leaf) = H"[a-f0-9]+"/i);
  assert.doesNotMatch(oldRequirement, /\btrusted\b/i);
  const topLevel = satisfiesTopLevel(oldRequirement, candidate);
  const deep = satisfies(oldRequirement, candidate);
  for (const [scope, result] of [
    ["top-level", topLevel],
    ["deep", deep],
  ]) {
    assert.notEqual(
      result.status,
      0,
      `explicit certificate-hash DR unexpectedly bypassed ${scope} trust validation`,
    );
    assert.match(
      result.output,
      /CSSMERR_TP_NOT_TRUSTED|not trusted|unable to build chain/i,
      `${scope} certificate-hash rejection did not expose the trust failure:\n${result.output}`,
    );
    console.log(
      `[project-updater] PASS cert-hash ${scope} rejection: ${result.output.replaceAll("\n", " | ")}`,
    );
  }

  const nestedRelative = path.join(
    "Contents",
    "Frameworks",
    "Logseq Helper.app",
  );
  const baselineNested = path.join(baseline, nestedRelative);
  const candidateNested = path.join(candidate, nestedRelative);
  assert.equal(fs.existsSync(baselineNested), true);
  assert.equal(fs.existsSync(candidateNested), true);
  const nestedRequirement = designatedRequirement(baselineNested);
  assert.match(
    nestedRequirement,
    /certificate (?:root|leaf) = H"[a-f0-9]+"/i,
  );
  assert.doesNotMatch(nestedRequirement, /\btrusted\b/i);
  const nested = satisfiesTopLevel(nestedRequirement, candidateNested);
  assert.notEqual(
    nested.status,
    0,
    "nested certificate-hash DR unexpectedly bypassed trust validation",
  );
  assert.match(
    nested.output,
    /CSSMERR_TP_NOT_TRUSTED|not trusted|unable to build chain/i,
    `nested certificate-hash rejection did not expose the trust failure:\n${nested.output}`,
  );
  console.log(
    `[project-updater] PASS cert-hash nested rejection: ${nested.output.replaceAll("\n", " | ")}`,
  );
};

const runAsync = (executable, args, { cwd = repoRoot, env = process.env, observe } = {}) =>
  new Promise((resolve, reject) => {
    const child = spawn(executable, args, {
      cwd,
      env,
      stdio: ["ignore", "pipe", "pipe"],
    });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk) => {
      stdout += chunk;
    });
    child.stderr.on("data", (chunk) => {
      stderr += chunk;
    });
    child.once("error", reject);
    const timer = observe ? setInterval(observe, 1) : null;
    child.once("close", (status, signal) => {
      if (timer) clearInterval(timer);
      resolve({
        output: `${stdout}${stderr}`.trim(),
        signal,
        status,
      });
    });
  });

const appInfoPlist = ({ applicationId, version }) =>
  `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleExecutable</key><string>Logseq</string>
<key>CFBundleIdentifier</key><string>${applicationId}</string>
<key>CFBundleName</key><string>Logseq</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleShortVersionString</key><string>${version}</string>
<key>CFBundleVersion</key><string>${version}</string>
</dict></plist>
`;

const quarantineValue = (target) => {
  const result = command(
    "xattr",
    ["-p", "com.apple.quarantine", target],
    { allowFailure: true },
  );
  return result.status === 0 ? result.output : null;
};

const thinMachORoot = fs.mkdtempSync(
  path.join(os.tmpdir(), "logseq-thin-macho-fixtures-"),
);
const thinMachOCache = new Map();
process.once("exit", () => {
  fs.rmSync(thinMachORoot, { recursive: true, force: true });
});

const lipoArchitecture = (arch) => {
  assert.ok(arch === "arm64" || arch === "x64");
  return arch === "x64" ? "x86_64" : "arm64";
};

const thinMachOExecutable = (arch) => {
  if (thinMachOCache.has(arch)) return thinMachOCache.get(arch);
  const source = path.join(thinMachORoot, "main.c");
  const executable = path.join(thinMachORoot, `Logseq-${arch}`);
  if (!fs.existsSync(source)) {
    fs.writeFileSync(source, "int main(void) { return 0; }\n");
  }
  command("xcrun", [
    "clang",
    "-arch",
    lipoArchitecture(arch),
    "-Os",
    "-Wl,-no_uuid",
    source,
    "-o",
    executable,
  ]);
  assert.equal(
    command("lipo", ["-archs", executable]).output,
    lipoArchitecture(arch),
  );
  thinMachOCache.set(arch, executable);
  return executable;
};

const universalMachOExecutable = () => {
  if (thinMachOCache.has("universal")) {
    return thinMachOCache.get("universal");
  }
  const executable = path.join(thinMachORoot, "Logseq-universal");
  command("lipo", [
    "-create",
    thinMachOExecutable("arm64"),
    thinMachOExecutable("x64"),
    "-output",
    executable,
  ]);
  assert.deepEqual(
    command("lipo", ["-archs", executable]).output.split(/\s+/).sort(),
    ["arm64", "x86_64"],
  );
  thinMachOCache.set("universal", executable);
  return executable;
};

const makeNativeHelperApp = ({
  adHocSigned = true,
  applicationId = "com.logseq.logseq",
  arch = process.arch === "arm64" ? "arm64" : "x64",
  destination,
  escapeSymlink,
  marker,
  quarantine,
  universal = false,
  version,
}) => {
  const contents = path.join(destination, "Contents");
  const macOS = path.join(contents, "MacOS");
  const resources = path.join(contents, "Resources");
  fs.mkdirSync(macOS, { recursive: true });
  fs.mkdirSync(resources, { recursive: true });
  fs.writeFileSync(
    path.join(contents, "Info.plist"),
    appInfoPlist({ applicationId, version }),
  );
  const executable = path.join(macOS, "Logseq");
  fs.copyFileSync(
    universal ? universalMachOExecutable() : thinMachOExecutable(arch),
    executable,
  );
  fs.chmodSync(executable, 0o755);
  const executableArchs = command("lipo", ["-archs", executable]).output
    .split(/\s+/)
    .sort();
  assert.deepEqual(
    executableArchs,
    universal ? ["arm64", "x86_64"] : [lipoArchitecture(arch)],
  );
  fs.writeFileSync(path.join(resources, "update-state.txt"), marker);
  if (quarantine) {
    command("xattr", [
      "-w",
      "com.apple.quarantine",
      quarantine,
      destination,
    ]);
  }
  if (adHocSigned) {
    command("xattr", ["-dr", "com.apple.FinderInfo", destination]);
    command("xattr", ["-dr", "com.apple.ResourceFork", destination]);
    command("codesign", [
      "--force",
      "--deep",
      "--timestamp=none",
      "--sign",
      "-",
      destination,
    ]);
    command("codesign", [
      "--verify",
      "--deep",
      "--strict",
      "--all-architectures",
      destination,
    ]);
  } else {
    assert.notEqual(
      command(
        "codesign",
        ["--verify", "--deep", "--strict", destination],
        { allowFailure: true },
      ).status,
      0,
      "unsigned App fixture unexpectedly has a valid code signature",
    );
  }
  if (escapeSymlink) {
    fs.symlinkSync(
      escapeSymlink,
      path.join(resources, "escape-link"),
    );
  }
  return destination;
};

const sevenZipExecutable = () => {
  const candidates = [
    process.env.LOGSEQ_7ZIP,
    "7zz",
    "7z",
    "7za",
  ].filter(Boolean);
  const executable = candidates.find((candidate) => {
    const probe = spawnSync(candidate, ["i"], {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    });
    return !probe.error && probe.status === 0;
  });
  assert.ok(executable, "electron-builder-compatible 7-Zip is unavailable");
  return executable;
};

const archiveApp = ({ app, archive }) => {
  // The macOS p7zip binary bundled with electron-builder rejects the
  // -mtc/-mta/-mtm timestamp switches before it writes any archive entries.
  command(
    sevenZipExecutable(),
    [
      "a",
      "-bd",
      "-bb0",
      "-tzip",
      archive,
      path.basename(app),
    ],
    { cwd: path.dirname(app) },
  );
  const entries = command("zipinfo", ["-1", archive]).output
    .split("\n")
    .filter(Boolean);
  assert.ok(entries.length > 0);
  assert.equal(
    entries.every(
      (entry) =>
        entry === "Logseq.app" || entry.startsWith("Logseq.app/"),
    ),
    true,
    `legal update ZIP contains an App-external entry:\n${entries.join("\n")}`,
  );
  assert.equal(entries.some((entry) => entry.startsWith("__MACOSX/")), false);
  return archive;
};

const treeDigest = (root) => {
  const entries = [];
  const visit = (entry, relative = ".") => {
    const stat = fs.lstatSync(entry);
    const mode = stat.mode & 0o7777;
    if (stat.isSymbolicLink()) {
      entries.push(["link", relative, mode, fs.readlinkSync(entry)]);
      return;
    }
    if (stat.isDirectory()) {
      entries.push(["dir", relative, mode]);
      for (const name of fs.readdirSync(entry).sort()) {
        visit(path.join(entry, name), path.join(relative, name));
      }
      return;
    }
    entries.push(["file", relative, mode, fileSha256(entry)]);
  };
  visit(root);
  entries.push(["quarantine", quarantineValue(root)]);
  return sha256(JSON.stringify(entries));
};

const signedNativeArchive = ({
  arch,
  artifactPath,
  bundleId,
  payloadDomain,
  privateKeyPem,
  version,
}) => {
  const artifact = fs.readFileSync(artifactPath);
  const size = artifact.length;
  const sha512 = createHash("sha512").update(artifact).digest("hex");
  const payload = canonicalNativePayload({
    arch,
    bundleId,
    payloadDomain,
    sha512,
    size,
    version,
  });
  const signature = cryptoSign(null, Buffer.from(payload), privateKeyPem).toString(
    "base64",
  );
  return { arch, artifactPath, sha512, signature, size, version };
};

const makeSignedNativeUpdate = ({
  adHocSigned = true,
  archiveQuarantine = null,
  applicationId,
  appArch,
  arch = "arm64",
  bundleId,
  damageCodeSignature = false,
  escapeSymlink,
  payloadDomain,
  privateKeyPem,
  root,
  universalApp = false,
  version = "2.0.1-selfhost.6",
}) => {
  const packagedAppArch = appArch ?? arch;
  fs.mkdirSync(root, { recursive: true });
  const app = makeNativeHelperApp({
    adHocSigned,
    applicationId: applicationId ?? bundleId,
    arch: packagedAppArch,
    destination: path.join(root, "payload", "Logseq.app"),
    escapeSymlink,
    marker: version,
    quarantine: "0081;5f000000;Logseq project update;test-origin",
    universal: universalApp,
    version,
  });
  if (damageCodeSignature) {
    fs.appendFileSync(
      path.join(app, "Contents", "Resources", "update-state.txt"),
      "\ndamaged after codesign",
    );
    assert.notEqual(
      command(
        "codesign",
        ["--verify", "--deep", "--strict", app],
        { allowFailure: true },
      ).status,
      0,
      "damaged App fixture unexpectedly retained a valid code signature",
    );
  }
  const artifactPath = path.join(
    root,
    `Logseq-darwin-${arch}-${version}.zip`,
  );
  archiveApp({ app, archive: artifactPath });
  if (archiveQuarantine !== null) {
    command("xattr", [
      "-w",
      "com.apple.quarantine",
      archiveQuarantine,
      artifactPath,
    ]);
  }
  assert.equal(
    quarantineValue(artifactPath),
    archiveQuarantine,
    "source update ZIP quarantine fixture was not created as requested",
  );
  const probeRoot = path.join(root, "archive-layout-probe");
  command(sevenZipExecutable(), [
    "x",
    "-bd",
    "-bb0",
    `-o${probeRoot}`,
    artifactPath,
  ]);
  const archivedApp = path.join(probeRoot, "Logseq.app");
  const archivedQuarantine = quarantineValue(archivedApp);
  assert.deepEqual(
    command("lipo", [
      "-archs",
      path.join(archivedApp, "Contents", "MacOS", "Logseq"),
    ]).output
      .split(/\s+/)
      .sort(),
    universalApp
      ? ["arm64", "x86_64"]
      : [lipoArchitecture(packagedAppArch)],
    "7-Zip archive changed the fixture executable architecture",
  );
  const archivedSignature = command(
    "codesign",
    ["--verify", "--deep", "--strict", "--all-architectures", archivedApp],
    { allowFailure: true },
  );
  assert.equal(
    archivedSignature.status === 0,
    adHocSigned && !damageCodeSignature && !escapeSymlink,
    `archive round-trip produced an unexpected code-signature state:\n${archivedSignature.output}`,
  );
  fs.rmSync(probeRoot, { recursive: true, force: true });
  return {
    app,
    archivedQuarantine,
    sourceArchiveQuarantine: quarantineValue(artifactPath),
    ...signedNativeArchive({
      arch,
      artifactPath,
      bundleId,
      payloadDomain,
      privateKeyPem,
      version,
    }),
  };
};

const makeTraversalArchive = ({ archive, sentinel }) => {
  const name = Buffer.from("Logseq.app/../escape");
  const payload = Buffer.from(`path traversal fixture ${sentinel}`);
  let crc = 0xffffffff;
  for (const byte of payload) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit += 1) {
      crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0);
    }
  }
  crc = (crc ^ 0xffffffff) >>> 0;

  const local = Buffer.alloc(30);
  local.writeUInt32LE(0x04034b50, 0);
  local.writeUInt16LE(20, 4);
  local.writeUInt32LE(crc, 14);
  local.writeUInt32LE(payload.length, 18);
  local.writeUInt32LE(payload.length, 22);
  local.writeUInt16LE(name.length, 26);

  const central = Buffer.alloc(46);
  central.writeUInt32LE(0x02014b50, 0);
  central.writeUInt16LE(20, 4);
  central.writeUInt16LE(20, 6);
  central.writeUInt32LE(crc, 16);
  central.writeUInt32LE(payload.length, 20);
  central.writeUInt32LE(payload.length, 24);
  central.writeUInt16LE(name.length, 28);

  const centralOffset = local.length + name.length + payload.length;
  const centralSize = central.length + name.length;
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(1, 8);
  end.writeUInt16LE(1, 10);
  end.writeUInt32LE(centralSize, 12);
  end.writeUInt32LE(centralOffset, 16);

  fs.writeFileSync(
    archive,
    Buffer.concat([local, name, payload, central, name, end]),
  );
  assert.equal(command("zipinfo", ["-1", archive]).output, name.toString());
};

const addMacosxSidecarEntry = ({ archive, root }) => {
  const sidecarRoot = path.join(root, "finder-sidecar");
  const macosx = path.join(sidecarRoot, "__MACOSX");
  fs.mkdirSync(macosx, { recursive: true });
  fs.writeFileSync(path.join(macosx, "._Logseq"), "Finder metadata sidecar");
  command(
    sevenZipExecutable(),
    [
      "a",
      "-bd",
      "-bb0",
      "-tzip",
      archive,
      "__MACOSX",
    ],
    { cwd: sidecarRoot },
  );
  const entries = command("zipinfo", ["-1", archive]).output.split("\n");
  assert.ok(entries.some((entry) => entry.startsWith("__MACOSX/")));
  fs.rmSync(sidecarRoot, { recursive: true, force: true });
};

const nativeInstallArgs = ({
  arch,
  artifactPath,
  relaunch = false,
  sha512,
  signature,
  size,
  targetApp,
  testExitAfterSwap = false,
  verifyOnly = false,
  version,
}) => [
  "--archive",
  artifactPath,
  "--target",
  targetApp,
  "--arch",
  arch,
  "--version",
  version,
  "--sha512",
  sha512,
  "--size",
  String(size),
  "--parent-pid",
  "2147483647",
  "--relaunch",
  String(relaunch),
  "--signature",
  signature,
  ...(testExitAfterSwap ? ["--test-exit-after-swap"] : []),
  ...(verifyOnly ? ["--verify-only"] : []),
];

const makeOldTarget = (
  root,
  bundleId = "com.logseq.logseq",
  arch = process.arch === "arm64" ? "arm64" : "x64",
  quarantine = "0081;4f000000;Logseq legacy install;test-origin",
  version = "2.0.1-selfhost.5",
) => {
  const parent = path.join(root, "installed");
  const targetApp = makeNativeHelperApp({
    applicationId: bundleId,
    arch,
    destination: path.join(parent, "Logseq.app"),
    marker: version,
    quarantine,
    version,
  });
  return { parent, targetApp };
};

const runNativeHelperContract = async ({
  managedSignerFixture = null,
  nativeFixtureSigningKeys = null,
} = {}) => {
  if (process.platform !== "darwin") {
    throw new SkipTest("native replacement helper requires macOS");
  }
  assert.equal(
    fs.existsSync(helperRunnerPath),
    true,
    `${helperRunnerPath} is missing`,
  );
  const runnerSource = fs.readFileSync(helperRunnerPath, "utf8");
  assert.match(
    runnerSource,
    /--helper\b/,
    "local/CI runner does not expose its explicit test-helper override",
  );
  const {
    bundleId,
    configured: productionPolicyConfigured,
    derivedKeyId: productionDerivedKeyId,
    payloadDomain,
    policy: productionPolicy,
    publicKeyRaw: productionPublicKeyRaw,
  } = managedSignerFixture?.loadedPolicy ?? loadPolicy();
  const helperBuildPath = discoverHelperBuildPath();
  const [helpExecutable, helpArgs] = scriptCommand(helperBuildPath, ["--help"]);
  const help = command(helpExecutable, helpArgs, { allowFailure: true });
  const testKeyFlag = [
    "--test-only-public-key",
    "--test-public-key",
    "--test-public-key-path",
    "--test-public-key-base64",
  ].find((flag) => help.output.includes(flag));
  const splitTestOnlyKeyFlags =
    help.output.includes("--test-only") &&
    help.output.includes("--public-key-base64");
  assert.ok(
    testKeyFlag || splitTestOnlyKeyFlags,
    `native helper builder does not advertise a TEST-ONLY public-key input:\n${help.output}`,
  );
  assert.match(help.output, /--output\b/);

  const tempRoot = fs.mkdtempSync(
    path.join(os.tmpdir(), "logseq-native-helper-contract-"),
  );
  const initialTrust = userTrustSettingsDigest();
  try {
    const signingKeys =
      nativeFixtureSigningKeys ?? generateKeyPairSync("ed25519");
    const wrongKeys = generateKeyPairSync("ed25519");
    const publicKeyPath = path.join(tempRoot, "test-public-key.pem");
    fs.writeFileSync(
      publicKeyPath,
      signingKeys.publicKey.export({ format: "pem", type: "spki" }),
    );
    const publicKeyRawBase64 = Buffer.from(
      signingKeys.publicKey.export({ format: "jwk" }).x,
      "base64url",
    ).toString("base64");
    const wrongPublicKeyRawBase64 = Buffer.from(
      wrongKeys.publicKey.export({ format: "jwk" }).x,
      "base64url",
    ).toString("base64");
    const fullKeyId = `ed25519:${sha256(
      Buffer.from(publicKeyRawBase64, "base64"),
    )}`;
    const privateKeyPem = signingKeys.privateKey.export({
      format: "pem",
      type: "pkcs8",
    });
    const wrongPrivateKeyPem = wrongKeys.privateKey.export({
      format: "pem",
      type: "pkcs8",
    });

    const helperPath = path.join(tempRoot, "project-update-helper");
    const helperArch = process.arch === "arm64" ? "arm64" : "x64";
    const testKeyArgs = splitTestOnlyKeyFlags
      ? ["--test-only", "--public-key-base64", publicKeyRawBase64]
      : [
          testKeyFlag,
          testKeyFlag.endsWith("-base64")
            ? publicKeyRawBase64
            : publicKeyPath,
        ];
    const [buildExecutable, buildArgs] = scriptCommand(helperBuildPath, [
      ...testKeyArgs,
      "--arch",
      helperArch,
      "--output",
      helperPath,
    ]);
    const build = command(
      buildExecutable,
      buildArgs,
      { allowFailure: true },
    );
    assert.equal(build.status, 0, build.output);
    assert.equal(fs.existsSync(helperPath), true);
    assert.match(
      build.output,
      new RegExp(fullKeyId.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")),
      "native helper builder does not report the complete derived keyId",
    );
    if (splitTestOnlyKeyFlags) {
      const unsafeHelperPath = path.join(tempRoot, "unsafe-key-override-helper");
      const [unsafeExecutable, unsafeArgs] = scriptCommand(helperBuildPath, [
        "--public-key-base64",
        publicKeyRawBase64,
        "--arch",
        helperArch,
        "--output",
        unsafeHelperPath,
      ]);
      const unsafeBuild = command(unsafeExecutable, unsafeArgs, {
        allowFailure: true,
      });
      assert.notEqual(
        unsafeBuild.status,
        0,
        "builder accepted a public-key override without --test-only",
      );
      assert.equal(
        fs.existsSync(unsafeHelperPath),
        false,
        "builder emitted a helper for a non-test public-key override",
      );
    }
    assert.match(
      command("file", [helperPath]).output,
      /Mach-O/,
      "helper build did not produce a native Mach-O executable",
    );
    assert.match(
      command("strings", [helperPath]).output,
      new RegExp(
        publicKeyRawBase64.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"),
      ),
      "built native helper does not embed the configured raw Ed25519 public key",
    );
    assert.doesNotMatch(
      command("strings", [helperPath]).output,
      new RegExp(
        wrongPublicKeyRawBase64.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"),
      ),
      "built native helper embeds an unrelated Ed25519 public key",
    );
    const flagSearch = command(
      "git",
      ["grep", "-l", "--", "--test-exit-after-swap"],
      { allowFailure: true },
    );
    const productionSourcesWithGuard = flagSearch.status === 0
      ? flagSearch.output
          .split("\n")
          .filter(
            (file) =>
              file &&
              file !== "scripts/test-project-signed-macos-updater.mjs",
          )
          .filter((file) => {
            const source = fs.readFileSync(path.join(repoRoot, file), "utf8");
            return /PROJECT_UPDATER_TESTING[\s\S]{0,1600}test-exit-after-swap|test-exit-after-swap[\s\S]{0,1600}PROJECT_UPDATER_TESTING/.test(
              source,
            );
          })
      : [];
    assert.ok(
      productionSourcesWithGuard.length > 0,
      "--test-exit-after-swap is not compile-guarded by PROJECT_UPDATER_TESTING",
    );

    const makeFixture = (options) =>
      makeSignedNativeUpdate({
        arch: helperArch,
        bundleId,
        payloadDomain,
        privateKeyPem,
        ...options,
      });
    const invokeNative = (fixture, options = {}) =>
      command(
        helperPath,
        nativeInstallArgs({
          ...fixture,
          ...(options.fields ?? {}),
          targetApp: options.targetApp,
        }),
        {
          allowFailure: true,
          env: { ...process.env, ...(options.env ?? {}) },
        },
      );

    const expectNoDamage = ({
      fixture,
      label,
      mutateTargetParent,
      postcondition,
      invocationFields,
    }) => {
      const caseRoot = path.join(tempRoot, `reject-${label.replaceAll(" ", "-")}`);
      const { parent, targetApp } = makeOldTarget(
        caseRoot,
        bundleId,
        helperArch,
      );
      const before = treeDigest(targetApp);
      const oldQuarantine = quarantineValue(targetApp);
      try {
        mutateTargetParent?.(parent);
        const result = invokeNative(fixture, {
          fields: invocationFields,
          targetApp,
        });
        assert.notEqual(result.status, 0, `${label} was accepted`);
        assert.equal(fs.existsSync(targetApp), true, `${label} removed old App`);
        assert.equal(treeDigest(targetApp), before, `${label} changed old App`);
        assert.equal(
          quarantineValue(targetApp),
          oldQuarantine,
          `${label} changed quarantine metadata`,
        );
        postcondition?.();
      } finally {
        if (mutateTargetParent) fs.chmodSync(parent, 0o755);
      }
      assert.deepEqual(
        fs.readdirSync(parent).sort(),
        ["Logseq.app"],
        `${label} left replacement debris`,
      );
      console.log(`[project-updater] PASS native rejection: ${label}`);
    };

    const sourceQuarantine =
      "0081;6f000000;Logseq signed update;source-origin";
    const oldQuarantine =
      "0081;4f000000;Logseq legacy install;old-origin";
    const base = makeFixture({
      archiveQuarantine: sourceQuarantine,
      root: path.join(tempRoot, "valid-base"),
    });
    assert.equal(base.sourceArchiveQuarantine, sourceQuarantine);
    const basePayload = canonicalNativePayload({
      arch: base.arch,
      bundleId,
      payloadDomain,
      sha512: base.sha512,
      size: base.size,
      version: base.version,
    });
    assert.match(base.sha512, /^[a-f0-9]{128}$/);
    assert.equal(basePayload.endsWith("\n"), true);
    assert.equal(basePayload.endsWith("\n\n"), false);
    for (const [label, payload] of [
      [
        "canonical payload missing terminal newline",
        basePayload.slice(0, -1),
      ],
      ["canonical payload has double terminal newline", `${basePayload}\n`],
    ]) {
      expectNoDamage({
        fixture: {
          ...base,
          signature: cryptoSign(
            null,
            Buffer.from(payload),
            privateKeyPem,
          ).toString("base64"),
        },
        label,
      });
    }
    const universalFixture = makeFixture({
      root: path.join(tempRoot, "safe-universal-app"),
      universalApp: true,
    });
    const universalTarget = makeOldTarget(
      path.join(tempRoot, "safe-universal-target"),
      bundleId,
      helperArch,
    );
    const universalBefore = treeDigest(universalTarget.targetApp);
    const universalVerification = invokeNative(universalFixture, {
      fields: { verifyOnly: true },
      targetApp: universalTarget.targetApp,
    });
    assert.equal(universalVerification.status, 0, universalVerification.output);
    assert.equal(treeDigest(universalTarget.targetApp), universalBefore);
    assert.equal(
      quarantineValue(universalTarget.targetApp),
      "0081;4f000000;Logseq legacy install;test-origin",
      "verify-only changed the installed App quarantine metadata",
    );
    assert.deepEqual(fs.readdirSync(universalTarget.parent), ["Logseq.app"]);
    console.log(
      "[project-updater] PASS native verification: safe universal App includes declared arch",
    );

    const expectInstalledQuarantine = ({
      expectedQuarantine,
      fixture,
      label,
      targetQuarantine,
    }) => {
      const caseRoot = path.join(
        tempRoot,
        `quarantine-${label.replaceAll(" ", "-")}`,
      );
      const target = makeOldTarget(
        caseRoot,
        bundleId,
        helperArch,
        targetQuarantine,
      );
      assert.equal(
        quarantineValue(target.targetApp),
        targetQuarantine,
        `${label} old target fixture has the wrong quarantine`,
      );
      const archiveQuarantineBefore = quarantineValue(fixture.artifactPath);
      const result = invokeNative(fixture, { targetApp: target.targetApp });
      assert.equal(result.status, 0, result.output);
      assert.equal(
        quarantineValue(target.targetApp),
        expectedQuarantine,
        `${label} installed the wrong quarantine metadata`,
      );
      assert.equal(
        quarantineValue(fixture.artifactPath),
        archiveQuarantineBefore,
        `${label} modified the source ZIP quarantine metadata`,
      );
      assert.deepEqual(fs.readdirSync(target.parent), ["Logseq.app"]);
      assert.equal(userTrustSettingsDigest(), initialTrust);
      console.log(`[project-updater] PASS native quarantine: ${label}`);
    };

    expectInstalledQuarantine({
      expectedQuarantine: sourceQuarantine,
      fixture: base,
      label: "source ZIP quarantine applies when old target has none",
      targetQuarantine: null,
    });
    const noSourceQuarantine = makeFixture({
      archiveQuarantine: null,
      root: path.join(tempRoot, "no-source-quarantine"),
    });
    expectInstalledQuarantine({
      expectedQuarantine: oldQuarantine,
      fixture: noSourceQuarantine,
      label: "old target quarantine survives when source ZIP has none",
      targetQuarantine: oldQuarantine,
    });
    expectInstalledQuarantine({
      expectedQuarantine: sourceQuarantine,
      fixture: base,
      label: "source ZIP quarantine wins over a different old target value",
      targetQuarantine: oldQuarantine,
    });

    const workflow = fs.readFileSync(workflowPath, "utf8");
    const signerSecrets = signingVariableNames(workflow);
    const managedSignerAvailable =
      productionPolicyConfigured &&
      managedSignerFixture !== null;
    const productionCompositeBlockReason = !productionPolicyConfigured
      ? "production Ed25519 policy is UNCONFIGURED; managed signer/native composite is blocked"
      : null;
    let signWithReleaseCli = null;
    let expectSignerRejectsVersion = null;
    if (managedSignerAvailable) {
    const productionSignerPath = discoverSignerPath(workflow);
    const productionPublicKeyRawBase64 =
      productionPublicKeyRaw.toString("base64");
    const productionFullKeyId =
      `ed25519:${productionDerivedKeyId}`;
    const productionPublicKey = createPublicKey({
      format: "jwk",
      key: {
        crv: "Ed25519",
        kty: "OKP",
        x: productionPublicKeyRaw.toString("base64url"),
      },
    });
    const isolatedSigner = createIsolatedSignerTree({
      bundleId,
      destinationRoot: path.join(tempRoot, "isolated-release-signer"),
      fullKeyId: productionFullKeyId,
      payloadDomain,
      policyTemplate: productionPolicy,
      publicKeyRawBase64: productionPublicKeyRawBase64,
      signerPath: productionSignerPath,
      sourceRoot: repoRoot,
    });
    const productionHelperPath = path.join(
      tempRoot,
      "project-update-helper-production-key",
    );
    const selectedHelperBuildPath = managedSignerFixture
      ? path.join(
          path.dirname(path.dirname(isolatedSigner.signerPath)),
          path.basename(helperBuildPath),
        )
      : helperBuildPath;
    const [productionBuildExecutable, productionBuildArgs] = scriptCommand(
      selectedHelperBuildPath,
      [
        "--arch",
        helperArch,
        "--output",
        productionHelperPath,
      ],
    );
    command(productionBuildExecutable, productionBuildArgs);
    assert.equal(fs.existsSync(productionHelperPath), true);
    const signerPath = isolatedSigner.signerPath;
    assert.notEqual(
      signerPath,
      productionSignerPath,
      "native release test must not execute the production-tree signer",
    );
    assert.equal(
      signerPath.startsWith(`${tempRoot}${path.sep}`),
      true,
      "native release test signer is not isolated under its fixture root",
    );
    assert.ok(
      signerSecrets.length > 0,
      "workflow does not expose the release signer private-key environment",
    );
    const signerEnv = managedSignerFixture?.signerEnv ?? { ...process.env };
    signWithReleaseCli = (fixture, label) => {
      const metadata = path.join(
        path.dirname(fixture.artifactPath),
        `${label.replaceAll(" ", "-")}-latest-mac.yml`,
      );
      const signatureOutput = path.join(
        path.dirname(fixture.artifactPath),
        `${label.replaceAll(" ", "-")}-signature.txt`,
      );
      const archiveBytes = fs.readFileSync(fixture.artifactPath);
      const updaterSha512 = createHash("sha512")
        .update(archiveBytes)
        .digest("base64");
      fs.writeFileSync(
        metadata,
        [
          `version: ${fixture.version}`,
          "files:",
          `  - url: ${path.basename(fixture.artifactPath)}`,
          `    sha512: ${updaterSha512}`,
          `    size: ${archiveBytes.length}`,
          `path: ${path.basename(fixture.artifactPath)}`,
          `sha512: ${updaterSha512}`,
          `releaseDate: '${new Date().toISOString()}'`,
          "",
        ].join("\n"),
      );
      const result = command(
        process.execPath,
        [
          signerPath,
          "--test-only",
          "--arch",
          fixture.arch,
          "--version",
          fixture.version,
          "--archive",
          fixture.artifactPath,
          "--metadata",
          metadata,
          "--signature-output",
          signatureOutput,
        ],
        { allowFailure: true, env: signerEnv },
      );
      assert.equal(result.status, 0, result.output);
      assert.equal(
        fs.existsSync(signatureOutput),
        true,
        `${label} signer did not emit its detached signature`,
      );
      const signatureText = fs.readFileSync(signatureOutput, "utf8");
      const signature = signatureText.match(
        /(?:^|[^A-Za-z0-9+/])([A-Za-z0-9+/]{86}==)(?:$|[^A-Za-z0-9+/])/m,
      )?.[1];
      assert.ok(signature, `${label} signer output is not an Ed25519 signature`);
      const metadataText = fs.readFileSync(metadata, "utf8");
      assert.match(
        metadataText,
        new RegExp(
          fixture.version.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"),
        ),
        `${label} metadata lost the exact candidate version`,
      );
      assert.match(
        metadataText,
        new RegExp(
          productionFullKeyId.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"),
        ),
        `${label} metadata does not contain the complete Ed25519 keyId`,
      );
      assert.match(
        metadataText,
        new RegExp(signature.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")),
        `${label} metadata does not contain the detached project signature`,
      );
      const exactPayload = canonicalNativePayload({
        arch: fixture.arch,
        bundleId,
        payloadDomain,
        sha512: fixture.sha512,
        size: fixture.size,
        version: fixture.version,
      });
      assert.equal(
        cryptoVerify(
          null,
          Buffer.from(exactPayload),
          productionPublicKey,
          Buffer.from(signature, "base64"),
        ),
        true,
        `${label} signature does not cover the exact canonical payload`,
      );
      const stableAlias = fixture.version.replace(
        /\.nightly\.\d{8}$/,
        "",
      );
      if (stableAlias !== fixture.version) {
        assert.equal(
          cryptoVerify(
            null,
            Buffer.from(
              canonicalNativePayload({
                arch: fixture.arch,
                bundleId,
                payloadDomain,
                sha512: fixture.sha512,
                size: fixture.size,
                version: stableAlias,
              }),
            ),
            productionPublicKey,
            Buffer.from(signature, "base64"),
          ),
          false,
          `${label} signature also validates after stripping its nightly suffix`,
        );
      }
      return {
        ...fixture,
        nativeHelperPath: productionHelperPath,
        signature,
      };
    };
    expectSignerRejectsVersion = (candidateVersion) => {
      const fixture = makeFixture({
        root: path.join(
          tempRoot,
          `signer-invalid-${candidateVersion.replaceAll("/", "-")}`,
        ),
        version: candidateVersion,
      });
      const metadata = path.join(
        path.dirname(fixture.artifactPath),
        "latest-mac.yml",
      );
      const signatureOutput = path.join(
        path.dirname(fixture.artifactPath),
        "signature.txt",
      );
      const archiveBytes = fs.readFileSync(fixture.artifactPath);
      const updaterSha512 = createHash("sha512")
        .update(archiveBytes)
        .digest("base64");
      fs.writeFileSync(
        metadata,
        [
          `version: ${candidateVersion}`,
          "files:",
          `  - url: ${path.basename(fixture.artifactPath)}`,
          `    sha512: ${updaterSha512}`,
          `    size: ${archiveBytes.length}`,
          `path: ${path.basename(fixture.artifactPath)}`,
          `sha512: ${updaterSha512}`,
          "",
        ].join("\n"),
      );
      const metadataBefore = fs.readFileSync(metadata);
      const result = command(
        process.execPath,
        [
          signerPath,
          "--test-only",
          "--arch",
          fixture.arch,
          "--version",
          candidateVersion,
          "--archive",
          fixture.artifactPath,
          "--metadata",
          metadata,
          "--signature-output",
          signatureOutput,
        ],
        { allowFailure: true, env: signerEnv },
      );
      assert.notEqual(
        result.status,
        0,
        `release signer accepted invalid nightly ${candidateVersion}`,
      );
      assert.equal(fs.existsSync(signatureOutput), false);
      assert.deepEqual(
        fs.readFileSync(metadata),
        metadataBefore,
        `release signer modified metadata for invalid ${candidateVersion}`,
      );
    };
    }
    const invokeViaJsRuntimeWithHelper = (
      fixture,
      targetApp,
      selectedHelperPath,
    ) =>
      command(
        process.execPath,
        [
          helperRunnerPath,
          "--helper",
          selectedHelperPath,
          "--",
          ...nativeInstallArgs({ ...fixture, targetApp }),
        ],
        { allowFailure: true },
      );
    const invokeViaJsRuntime = (fixture, targetApp) =>
      invokeViaJsRuntimeWithHelper(
        fixture,
        targetApp,
        fixture.nativeHelperPath ?? helperPath,
      );
    const expectVersionTransition = ({
      accepted,
      candidateVersion,
      currentVersion,
      fixture,
      helperOverride,
      label,
    }) => {
      const updateFixture =
        fixture ??
        makeFixture({
          root: path.join(
            tempRoot,
            `version-${label.replaceAll(" ", "-")}`,
          ),
          version: candidateVersion,
        });
      const target = makeOldTarget(
        path.join(tempRoot, `target-${label.replaceAll(" ", "-")}`),
        bundleId,
        helperArch,
        oldQuarantine,
        currentVersion,
      );
      const before = treeDigest(target.targetApp);
      const result = helperOverride
        ? invokeViaJsRuntimeWithHelper(
            updateFixture,
            target.targetApp,
            helperOverride,
          )
        : invokeViaJsRuntime(updateFixture, target.targetApp);
      assert.equal(
        result.status === 0,
        accepted,
        `${label} returned status=${result.status}\n${result.output}`,
      );
      if (accepted) {
        assert.equal(
          command("plutil", [
            "-extract",
            "CFBundleShortVersionString",
            "raw",
            path.join(target.targetApp, "Contents", "Info.plist"),
          ]).output,
          candidateVersion,
          `${label} did not install the exact candidate version`,
        );
      } else {
        assert.equal(
          treeDigest(target.targetApp),
          before,
          `${label} changed the rejected target`,
        );
        assert.equal(
          quarantineValue(target.targetApp),
          oldQuarantine,
          `${label} changed quarantine on a rejected target`,
        );
        assert.deepEqual(
          fs.readdirSync(target.parent).sort(),
          ["Logseq.app"],
          `${label} left replacement debris`,
        );
      }
      assert.equal(userTrustSettingsDigest(), initialTrust);
      console.log(
        `[project-updater] PASS native version transition: ${label}`,
      );
    };

    const nightlyEarly =
      "2.0.1-selfhost.6.nightly.20260728";
    const nightlyLate =
      "2.0.1-selfhost.6.nightly.20260729";
    const nightlyFixture = makeFixture({
      root: path.join(tempRoot, "release-signed-nightly"),
      version: nightlyLate,
    });
    const releaseSignedNightly = signWithReleaseCli
      ? signWithReleaseCli(nightlyFixture, "release signed nightly")
      : nightlyFixture;
    if (
      managedSignerFixture !== null &&
      nativeFixtureSigningKeys === null
    ) {
      expectVersionTransition({
        accepted: false,
        candidateVersion: nightlyLate,
        currentVersion: nightlyEarly,
        fixture: releaseSignedNightly,
        helperOverride: helperPath,
        label:
          "unrelated TEST-ONLY helper rejects managed signer nightly without damage",
      });
    }
    expectVersionTransition({
      accepted: true,
      candidateVersion: nightlyLate,
      currentVersion: nightlyEarly,
      fixture: releaseSignedNightly,
      label: signWithReleaseCli
        ? "managed release signer metadata JS runtime native helper nightly chain"
        : "TEST-ONLY helper JS runtime native nightly chain",
    });
    for (const transition of [
      {
        accepted: false,
        candidateVersion: "2.0.1-selfhost.6",
        currentVersion: nightlyLate,
        label: "same revision nightly to stable",
      },
      {
        accepted: false,
        candidateVersion: nightlyLate,
        currentVersion: "2.0.1-selfhost.6",
        label: "same revision stable to nightly",
      },
      {
        accepted: false,
        candidateVersion: nightlyLate,
        currentVersion: nightlyLate,
        label: "same nightly date replay",
      },
      {
        accepted: false,
        candidateVersion: nightlyEarly,
        currentVersion: nightlyLate,
        label: "later nightly to earlier nightly",
      },
      {
        accepted: false,
        candidateVersion:
          "2.0.1-selfhost.7.nightly.20260730",
        currentVersion: "2.0.1-selfhost.6",
        label: "stable to higher revision nightly",
      },
      {
        accepted: false,
        candidateVersion: "2.0.1-selfhost.7",
        currentVersion: nightlyLate,
        label: "lower revision nightly to higher stable",
      },
      {
        accepted: true,
        candidateVersion: "2.0.1-selfhost.6",
        currentVersion: "2.0.1-selfhost.5",
        label: "stable behavior remains unchanged",
      },
    ]) {
      expectVersionTransition(transition);
    }
    for (const invalidVersion of [
      "2.0.1-selfhost.6-alpha.nightly.20260729",
      "2.0.1-selfhost.6.nightly.20260230",
      "2.0.1-selfhost.6.nightly",
      "2.0.1-selfhost.6.nightly.20260729.extra",
    ]) {
      expectSignerRejectsVersion?.(invalidVersion);
      expectVersionTransition({
        accepted: false,
        candidateVersion: invalidVersion,
        currentVersion: "2.0.1-selfhost.6",
        label: `invalid nightly ${invalidVersion}`,
      });
    }

    const wrongKey = makeFixture({
      privateKeyPem: wrongPrivateKeyPem,
      root: path.join(tempRoot, "wrong-key"),
    });
    expectNoDamage({ fixture: wrongKey, label: "wrong signing key" });
    const signatureTamper = makeFixture({
      root: path.join(tempRoot, "signature-tamper"),
    });
    signatureTamper.signature = signatureTamper.signature.replace(
      /^[A-Za-z0-9+/]/,
      (firstCharacter) => (firstCharacter === "A" ? "B" : "A"),
    );
    expectNoDamage({
      fixture: signatureTamper,
      label: "tampered Ed25519 signature bytes",
    });

    expectNoDamage({
      fixture: makeFixture({
        adHocSigned: false,
        root: path.join(tempRoot, "unsigned-app"),
      }),
      label: "validly project-signed but unsigned App",
    });
    expectNoDamage({
      fixture: makeFixture({
        damageCodeSignature: true,
        root: path.join(tempRoot, "damaged-code-signature"),
      }),
      label: "validly project-signed but damaged App code signature",
    });

    const tampered = makeFixture({
      root: path.join(tempRoot, "tampered"),
    });
    fs.appendFileSync(tampered.artifactPath, "tampered after signing");
    expectNoDamage({ fixture: tampered, label: "tampered zip bytes" });

    expectNoDamage({
      fixture: makeFixture({
        applicationId: "com.attacker.logseq",
        root: path.join(tempRoot, "bundle-id-substitution"),
      }),
      label: "validly signed bundle-id substitution",
    });
    for (const [label, invocationFields] of [
      ["signed version substitution", { version: "2.0.1-selfhost.7" }],
      [
        "signed architecture substitution",
        { arch: helperArch === "arm64" ? "x64" : "arm64" },
      ],
      ["signed zip-size substitution", { size: base.size + 1 }],
      [
        "signed zip-hash substitution",
        { sha512: Buffer.alloc(64, 7).toString("base64") },
      ],
    ]) {
      expectNoDamage({ fixture: base, invocationFields, label });
    }

    for (const [label, version] of [
      ["replayed current version", "2.0.1-selfhost.5"],
      ["signed downgrade", "2.0.1-selfhost.4"],
    ]) {
      expectNoDamage({
        fixture: makeFixture({
          root: path.join(tempRoot, label.replaceAll(" ", "-")),
          version,
        }),
        label,
      });
    }

    const oppositeArch = helperArch === "arm64" ? "x64" : "arm64";
    const wrongArchitecture = makeFixture({
      appArch: oppositeArch,
      root: path.join(tempRoot, "wrong-architecture"),
    });
    assert.equal(wrongArchitecture.arch, helperArch);
    assert.equal(
      command("lipo", [
        "-archs",
        path.join(
          wrongArchitecture.app,
          "Contents",
          "MacOS",
          "Logseq",
        ),
      ]).output,
      lipoArchitecture(oppositeArch),
    );
    expectNoDamage({
      fixture: wrongArchitecture,
      label: "validly signed wrong architecture",
    });

    const sentinel = `logseq-update-traversal-${process.pid}-${Date.now()}`;
    const traversalRoot = path.join(tempRoot, "path-traversal");
    fs.mkdirSync(traversalRoot);
    const traversalArchive = path.join(traversalRoot, "traversal.zip");
    makeTraversalArchive({ archive: traversalArchive, sentinel });
    const traversalFixture = signedNativeArchive({
      arch: helperArch,
      artifactPath: traversalArchive,
      bundleId,
      payloadDomain,
      privateKeyPem,
      version: "2.0.1-selfhost.6",
    });
    const escaped = path.join("/private/tmp", sentinel);
    expectNoDamage({
      fixture: traversalFixture,
      label: "signed zip path traversal",
      postcondition: () => assert.equal(fs.existsSync(escaped), false),
    });

    const sidecarRoot = path.join(tempRoot, "finder-sidecar-entry");
    const sidecarBase = makeFixture({ root: sidecarRoot });
    addMacosxSidecarEntry({
      archive: sidecarBase.artifactPath,
      root: sidecarRoot,
    });
    expectNoDamage({
      fixture: {
        ...sidecarBase,
        ...signedNativeArchive({
          arch: helperArch,
          artifactPath: sidecarBase.artifactPath,
          bundleId,
          payloadDomain,
          privateKeyPem,
          version: "2.0.1-selfhost.6",
        }),
      },
      label: "validly signed App-external __MACOSX entry",
    });

    const symlinkDestination = path.join(tempRoot, "outside-symlink-target");
    fs.writeFileSync(symlinkDestination, "must not be reachable");
    expectNoDamage({
      fixture: makeFixture({
        escapeSymlink: symlinkDestination,
        root: path.join(tempRoot, "escaping-symlink"),
      }),
      label: "signed escaping symlink",
      postcondition: () =>
        assert.equal(
          fs.readFileSync(symlinkDestination, "utf8"),
          "must not be reachable",
        ),
    });

    expectNoDamage({
      fixture: base,
      label: "unwritable target parent",
      mutateTargetParent: (parent) => fs.chmodSync(parent, 0o555),
    });

    const swapExitRoot = path.join(tempRoot, "exit-after-atomic-swap");
    const swapExitTarget = makeOldTarget(
      swapExitRoot,
      bundleId,
      helperArch,
    );
    const oldAppDigest = treeDigest(swapExitTarget.targetApp);
    const swapStatePath = path.join(
      swapExitTarget.targetApp,
      "Contents",
      "Resources",
      "update-state.txt",
    );
    const observedSwapStates = new Set();
    const observeSwap = () => {
      try {
        observedSwapStates.add(fs.readFileSync(swapStatePath, "utf8"));
      } catch (error) {
        if (error?.code === "ENOENT") observedSwapStates.add("<missing>");
        else throw error;
      }
    };
    observeSwap();
    const swapExit = await runAsync(
      helperPath,
      nativeInstallArgs({
        ...base,
        targetApp: swapExitTarget.targetApp,
        testExitAfterSwap: true,
      }),
      { observe: observeSwap },
    );
    observeSwap();
    assert.equal(
      swapExit.status,
      86,
      `test-only helper did not exit at the post-swap fault point:\n${swapExit.output}`,
    );
    assert.equal(observedSwapStates.has("<missing>"), false);
    for (const state of observedSwapStates) {
      assert.ok(
        state === "2.0.1-selfhost.5" || state === "2.0.1-selfhost.6",
        `post-swap observer saw partial state ${JSON.stringify(state)}`,
      );
    }
    assert.equal(fs.readFileSync(swapStatePath, "utf8"), "2.0.1-selfhost.6");
    assert.equal(
      quarantineValue(swapExitTarget.targetApp),
      sourceQuarantine,
      "post-swap crash changed or cleared the installed source quarantine",
    );
    assert.equal(
      command("plutil", [
        "-extract",
        "CFBundleShortVersionString",
        "raw",
        path.join(swapExitTarget.targetApp, "Contents", "Info.plist"),
      ]).output,
      "2.0.1-selfhost.6",
    );
    command("codesign", [
      "--verify",
      "--deep",
      "--strict",
      "--all-architectures",
      swapExitTarget.targetApp,
    ]);
    assert.ok(
      command("lipo", [
        "-archs",
        path.join(
          swapExitTarget.targetApp,
          "Contents",
          "MacOS",
          "Logseq",
        ),
      ]).output
        .split(/\s+/)
        .includes(lipoArchitecture(helperArch)),
    );
    const stagedApps = [];
    const collectStagedApps = (entry, depth = 0) => {
      if (depth > 2 || !fs.existsSync(entry)) return;
      if (
        fs.statSync(entry).isDirectory() &&
        fs.existsSync(path.join(entry, "Contents", "Info.plist"))
      ) {
        stagedApps.push(entry);
        return;
      }
      if (!fs.statSync(entry).isDirectory()) return;
      for (const child of fs.readdirSync(entry)) {
        collectStagedApps(path.join(entry, child), depth + 1);
      }
    };
    for (const entry of fs.readdirSync(swapExitTarget.parent)) {
      if (entry.startsWith(".")) {
        collectStagedApps(path.join(swapExitTarget.parent, entry));
      }
    }
    assert.ok(
      stagedApps.some((stagedApp) => treeDigest(stagedApp) === oldAppDigest),
      "atomic swap did not retain the complete old App in hidden staging",
    );
    console.log(
      "[project-updater] PASS native test exit after atomic swap preserves recoverable old App",
    );

    const successRoot = path.join(tempRoot, "success");
    const successTarget = makeOldTarget(successRoot, bundleId, helperArch);
    const observedStates = new Set();
    const statePath = path.join(
      successTarget.targetApp,
      "Contents",
      "Resources",
      "update-state.txt",
    );
    const observe = () => {
      try {
        observedStates.add(fs.readFileSync(statePath, "utf8"));
      } catch (error) {
        if (error?.code === "ENOENT") observedStates.add("<missing>");
        else throw error;
      }
    };
    observe();
    const success = await runAsync(
      process.execPath,
      [
        helperRunnerPath,
        "--helper",
        helperPath,
        "--",
        ...nativeInstallArgs({
          ...base,
          targetApp: successTarget.targetApp,
        }),
      ],
      { observe },
    );
    observe();
    assert.equal(success.status, 0, success.output);
    assert.equal(observedStates.has("<missing>"), false);
    for (const state of observedStates) {
      assert.ok(
        state === "2.0.1-selfhost.5" || state === "2.0.1-selfhost.6",
        `observer saw partial state ${JSON.stringify(state)}`,
      );
    }
    assert.equal(fs.readFileSync(statePath, "utf8"), "2.0.1-selfhost.6");
    assert.equal(
      command("plutil", [
        "-extract",
        "CFBundleShortVersionString",
        "raw",
        path.join(successTarget.targetApp, "Contents", "Info.plist"),
      ]).output,
      "2.0.1-selfhost.6",
    );
    assert.equal(
      quarantineValue(successTarget.targetApp),
      sourceQuarantine,
      "successful replacement did not apply the source ZIP quarantine",
    );
    assert.deepEqual(fs.readdirSync(successTarget.parent), ["Logseq.app"]);
    assert.equal(userTrustSettingsDigest(), initialTrust);
    if (productionCompositeBlockReason) {
      throw new ReleaseBlock(productionCompositeBlockReason);
    }
  } finally {
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
};

const isolatedManagedSignerFixture = () => {
  const workflow = fs.readFileSync(workflowPath, "utf8");
  const signerSecrets = signingVariableNames(workflow);
  assert.ok(
    signerSecrets.length > 0,
    "workflow does not expose the managed Ed25519 signing environment",
  );
  const managedKeys = generateKeyPairSync("ed25519");
  const publicKeyRaw = Buffer.from(
    managedKeys.publicKey.export({ format: "jwk" }).x,
    "base64url",
  );
  const derivedKeyId = sha256(publicKeyRaw);
  const bundleId = "com.logseq.logseq";
  const payloadDomain = "logseq-selfhost-macos-update-v1";
  const policy = {
    algorithm: exactProjectSigningAlgorithm,
    bundleIdentifier: bundleId,
    keyId: `ed25519:${derivedKeyId}`,
    minimumBootstrapRevision: 5,
    payloadDomain,
    publicKeyBase64: publicKeyRaw.toString("base64"),
  };
  const privateKeyBase64 = managedKeys.privateKey
    .export({ format: "der", type: "pkcs8" })
    .toString("base64");
  const signerEnv = {
    ...process.env,
    NODE_ENV: "test",
    ...Object.fromEntries(
      signerSecrets.map((name) => [name, privateKeyBase64]),
    ),
  };
  return {
    managedKeys,
    managedSignerFixture: {
      loadedPolicy: {
        bundleId,
        configured: true,
        derivedKeyId,
        payloadDomain,
        policy,
        publicKeyRaw,
      },
      signerEnv,
    },
  };
};

const runIsolatedManagedSignerNativeKeyAlignmentContract = async ({
  alignNativeFixtureKey = false,
} = {}) => {
  const { managedKeys, managedSignerFixture } =
    isolatedManagedSignerFixture();
  await runNativeHelperContract({
    managedSignerFixture,
    nativeFixtureSigningKeys: alignNativeFixtureKey
      ? managedKeys
      : null,
  });
};

const cases = [];
const releaseBlockSelfTest = process.argv.includes(
  "--release-block-self-test",
);
const isolatedSignerAlgorithmContractSelfTest = process.argv.includes(
  "--isolated-signer-algorithm-contract",
);
const managedSignerNativeKeyAlignmentContractSelfTest = process.argv.includes(
  "--managed-signer-native-key-alignment-contract",
);
const physicalShipItContractSelfTest = process.argv.includes(
  "--physical-shipit-contract",
);

addCase(cases, "proxy TLS private-key exception is pinned and fail-closed", () => {
  validateTrackedProxyTlsFixture(trackedFiles());
  testProxyTlsFixtureExceptionFailClosed();
});

addCase(cases, "repository contains no updater private key", () => {
  const tracked = trackedPrivateMaterial();
  assert.deepEqual(tracked.forbiddenNames, []);
  assert.equal(tracked.markerFiles, "");
});

addCase(cases, "fixed Ed25519 project public key policy is internally pinned", () => {
  const loaded = loadPolicy();
  if (!loaded.configured) {
    throw new ReleaseBlock(
      "production Ed25519 public key is UNCONFIGURED; release is blocked",
    );
  }
});

addCase(cases, ".4 legacy feed remains pinned and .5 remains manual", () => {
  assert.equal(
    updaterSignatureGatePlan("2.0.1-selfhost.5", false).mode,
    "manual-migration",
  );
  const futurePlan = updaterSignatureGatePlan("2.0.1-selfhost.6", false);
  assert.match(
    futurePlan.mode,
    /project[\s_-]*(?:signature|signed)|signature[\s_-]*project/i,
    ".6+ updater policy is not the project-signature verifier",
  );
  assert.doesNotMatch(
    JSON.stringify(futurePlan),
    /developer[\s_-]*id|notari[sz]|signed[\s_-]*baseline/i,
    ".6+ updater policy still depends on the obsolete Developer ID baseline",
  );
  const stable = resolveSelfhostUpdaterVersions("2.0.1-selfhost.6");
  assert.equal(stable.currentVersion, "2.0.1-selfhost.6");
  assert.equal(stable.isNightlyRehearsal, false);
  for (const arch of ["arm64", "x64"]) {
    assert.equal(
      macosUpdaterChannel(stable.currentVersion, arch),
      `selfhost-macos-v2-${arch}`,
    );
  }
  for (const [arch, digest] of Object.entries(legacyDigests)) {
    assert.equal(
      fileSha256(
        path.join(
          repoRoot,
          "resources",
          "updater",
          "legacy-macos",
          `latest-${arch}-mac.yml`,
        ),
      ),
      digest,
    );
  }
});

addCase(cases, "local signer stays local while protected CI uses a separate provider", () => {
  const workflow = fs.readFileSync(workflowPath, "utf8");
  assert.doesNotMatch(
    workflow,
    /sign-macos-project-update\.mjs/,
    "release workflow invokes the local signer",
  );
  const unsignedCandidateVerifier =
    "verify-unsigned-macos-project-update-candidate.mjs";
  const expectedVerificationJobs = new Map([
    ["build-macos-x64", 1],
    ["build-macos-arm64", 1],
    ["selfhost-release-signing", 1],
  ]);
  for (const [jobName, expectedCount] of expectedVerificationJobs) {
    assert.equal(
      workflowJobInvocationCount(
        workflow,
        jobName,
        unsignedCandidateVerifier,
      ),
      expectedCount,
      `${jobName} must verify unsigned candidates exactly once within its own boundary`,
    );
  }
  for (const jobName of workflowJobNames(workflow)) {
    if (expectedVerificationJobs.has(jobName)) continue;
    assert.equal(
      workflowJobInvocationCount(
        workflow,
        jobName,
        unsignedCandidateVerifier,
      ),
      0,
      `${jobName} must not verify unsigned macOS candidates`,
    );
  }
  const protectedSigner = workflowJobSource(
    workflow,
    "selfhost-release-signing",
  );
  assert.match(protectedSigner, /environment:\s*selfhost-release-signing/);
  assert.match(
    protectedSigner,
    /secrets\.LOGSEQ_PROJECT_UPDATE_SIGNING_KEY_PKCS8_BASE64/,
  );
  assert.match(
    protectedSigner,
    /finalize-github-macos-project-update\.mjs/,
  );
  assert.doesNotMatch(
    protectedSigner,
    /\/usr\/bin\/security|Keychain|contents:\s*write/,
  );
  const tempRoot = fs.mkdtempSync(
    path.join(os.tmpdir(), "logseq-project-signer-no-key-"),
  );
  try {
    const archive = path.join(tempRoot, "update.zip");
    const metadata = path.join(tempRoot, "latest.yml");
    const signatureOutput = path.join(tempRoot, "signature.txt");
    fs.writeFileSync(archive, "release artifact");
    const archiveBytes = fs.readFileSync(archive);
    const archiveSha512 = createHash("sha512")
      .update(archiveBytes)
      .digest("base64");
    fs.writeFileSync(
      metadata,
      [
        "version: 2.0.1-selfhost.6",
        "files:",
        "  - url: update.zip",
        `    sha512: ${archiveSha512}`,
        `    size: ${archiveBytes.length}`,
        "path: update.zip",
        `sha512: ${archiveSha512}`,
        "",
      ].join("\n"),
    );
    const metadataBefore = fs.readFileSync(metadata, "utf8");
    const blockedCiSigner = command(
      process.execPath,
      [
        path.join(repoRoot, "scripts", "sign-macos-project-update.mjs"),
        "--arch",
        "arm64",
        "--version",
        "2.0.1-selfhost.6",
        "--archive",
        archive,
        "--metadata",
        metadata,
        "--signature-output",
        signatureOutput,
      ],
      {
        allowFailure: true,
        env: {
          ...process.env,
          CI: "true",
        },
      },
    );
    assert.notEqual(
      blockedCiSigner.status,
      0,
      "local release signer ran in CI",
    );
    assert.match(blockedCiSigner.output, /refuses CI|local macOS publisher/i);
    assert.equal(
      fs.existsSync(signatureOutput),
      false,
      "release signer emitted a signature without the private key",
    );
    assert.equal(
      fs.readFileSync(metadata, "utf8"),
      metadataBefore,
      "release signer modified updater metadata without the private key",
    );
  } finally {
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
});

addCase(cases, "protected release DAG contract is structural and fail-closed", () => {
  const inlineFixture = [
    "    needs: [ build-android, release-rehearsal-gate, release-assets-preflight ]",
    "    runs-on: macos-14",
  ].join("\n");
  const blockFixture = [
    "    needs:",
    "      - release-assets-preflight",
    "      - build-android",
    "      - release-rehearsal-gate",
    "    runs-on: macos-14",
  ].join("\n");
  const expectedNeeds = [
    "build-android",
    "release-assets-preflight",
    "release-rehearsal-gate",
  ];
  assert.deepEqual(workflowJobNeeds(inlineFixture).sort(), expectedNeeds.sort());
  assert.deepEqual(workflowJobNeeds(blockFixture).sort(), expectedNeeds.sort());

  const validCondition = [
    "${{ always()",
    "needs.release-assets-preflight.result == 'success'",
    "needs.release-rehearsal-gate.result == 'success'",
    "(github.event.inputs.build-android != 'true' || needs.build-android.result == 'success') }}",
  ].join(" && ");
  assertSignerDependencyGate(expectedNeeds, validCondition);

  assert.throws(
    () => assertSignerDependencyGate(expectedNeeds.slice(1), validCondition),
    /dependencies must be exactly/,
  );

  const missingSkippedOverride = validCondition.replace("always() && ", "");
  assert.throws(
    () => assertSignerDependencyGate(expectedNeeds, missingSkippedOverride),
    /Android disabled after successful desktop rehearsal/,
  );

  const missingPreflightGate = validCondition.replace(
    "needs.release-assets-preflight.result == 'success' && ",
    "",
  );
  assert.throws(
    () => assertSignerDependencyGate(expectedNeeds, missingPreflightGate),
    /desktop preflight failed/,
  );

  const missingRehearsalGate = validCondition.replace(
    "needs.release-rehearsal-gate.result == 'success' && ",
    "",
  );
  assert.throws(
    () => assertSignerDependencyGate(expectedNeeds, missingRehearsalGate),
    /release rehearsal failed/,
  );

  const missingAndroidSuccessGate = validCondition.replace(
    "(github.event.inputs.build-android != 'true' || needs.build-android.result == 'success')",
    "(github.event.inputs.build-android == 'true' || github.event.inputs.build-android != 'true')",
  );
  assert.throws(
    () => assertSignerDependencyGate(expectedNeeds, missingAndroidSuccessGate),
    /Android enabled but skipped/,
  );
});

addCase(cases, "selfhost CI candidates reach publication only through protected verification", () => {
  const workflow = fs.readFileSync(workflowPath, "utf8");
  for (const [jobName, arch] of [
    ["build-macos-x64", "x64"],
    ["build-macos-arm64", "arm64"],
  ]) {
    const job = workflowJobSource(workflow, jobName);
    assert.match(
      job,
      /verify-unsigned-macos-project-update-candidate\.mjs/,
      `${jobName} does not verify its unsigned candidate`,
    );
    assert.match(
      job,
      new RegExp(`(?:--arch\\s+${arch}\\b|--${arch}\\b)`),
      `${jobName} does not bind its ${arch} candidate`,
    );
    assert.match(
      job,
      /--version[\s\S]{0,120}steps\.ref\.outputs\.version/,
      `${jobName} does not pass the exact workflow version to candidate verification`,
    );
  }
  for (const jobName of ["nightly-release", "release"]) {
    assert.match(
      workflowJobSource(workflow, jobName),
      /!contains\(needs\.release-assets-preflight\.outputs\.version,\s*'-selfhost\.'\)/,
      `${jobName} can publish unsigned selfhost metadata`,
    );
  }
  const signer = workflowJobSource(workflow, "selfhost-release-signing");
  assertProtectedReleaseDag(workflow);
  assert.match(signer, /github\.event_name == 'workflow_dispatch'/);
  const verifier = workflowJobSource(workflow, "selfhost-release-verifier");
  assert.match(verifier, /verify-finalized-selfhost-release\.mjs/);
  assert.doesNotMatch(
    verifier,
    /LOGSEQ_PROJECT_UPDATE_SIGNING_KEY_PKCS8_BASE64|secrets\.|environment:/,
  );
  const publisher = workflowJobSource(workflow, "selfhost-release");
  assert.match(publisher, /needs:\s*\[\s*selfhost-release-verifier\s*\]/);
  assert.match(publisher, /environment:\s*selfhost-production/);
  assert.match(publisher, /contents:\s*write/);
});

addCase(cases, "runtime replacement has no direct unauthenticated bypass", () => {
  const handler = fs.readFileSync(
    path.join(repoRoot, "src", "electron", "electron", "handler.cljs"),
    "utf8",
  );
  const updater = fs.readFileSync(
    path.join(repoRoot, "src", "electron", "electron", "updater.cljs"),
    "utf8",
  );
  const updaterConfig = fs.readFileSync(
    path.join(
      repoRoot,
      "src",
      "electron",
      "electron",
      "updater_config.cljs",
    ),
    "utf8",
  );
  const combined = `${handler}\n${updater}`;
  assert.doesNotMatch(
    combined,
    /run-project-signed-macos-update|--helper\b/,
    "production Electron calls the local/CI test runner or its helper override",
  );
  assert.match(
    combined,
    /project-signed-macos-updater\?/,
    "runtime does not use the shared signed-macOS routing predicate",
  );
  const predicateDefinition = updaterConfig.match(
    /\(defn-?\s+project-signed-macos-updater\?[\s\S]*?(?=\n\(defn|\s*$)/,
  )?.[0];
  assert.ok(
    predicateDefinition,
    "updater config does not define project-signed-macos-updater?",
  );
  assert.match(predicateDefinition, /darwin/i);
  assert.match(predicateDefinition, /selfhost/i);
  assert.match(
    predicateDefinition,
    /(?:>=\s+(?:[^\n()]+\s+)?5|<=\s+5(?:\s+[^\n()]*)?|at-least\??[^\n()]*5)/i,
    "signed-macOS predicate does not require selfhost revision >= 5",
  );
  const updaterFunction = (name) => {
    const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    return updater.match(
      new RegExp(
        `\\(defn-?\\s+${escaped}[\\s\\S]*?(?=\\n\\(defn|\\s*$)`,
      ),
    )?.[0];
  };
  const validateProjectUpdateInfo = updaterFunction(
    "<validate-project-update-info",
  );
  assert.ok(
    validateProjectUpdateInfo,
    "Electron does not validate signed update metadata before installation",
  );
  assert.match(
    validateProjectUpdateInfo,
    /:arch\s+\(\.-arch\s+js\/process\)/,
    "validated update metadata does not bind its architecture to process.arch",
  );
  const rememberProjectUpdate = updaterFunction("remember-project-update!");
  assert.ok(
    rememberProjectUpdate,
    "Electron does not persist validated project update metadata",
  );
  assert.match(
    rememberProjectUpdate,
    /:arch\s+\(\.-arch\s+manifest\)/,
    "remembered update architecture does not come from the validated manifest",
  );
  assert.match(
    rememberProjectUpdate,
    /\*project-update/,
    "validated manifest architecture is not persisted in project update state",
  );
  assert.doesNotMatch(
    rememberProjectUpdate,
    /\.-arch\s+js\/process/,
    "remembered update architecture bypasses the validated manifest",
  );
  const projectSignedInstall = updaterFunction("<project-signed-install!");
  assert.ok(
    projectSignedInstall,
    "Electron does not expose the guarded project-signed install path",
  );
  assert.match(
    projectSignedInstall,
    /\{:keys\s+\[[^\]]*\barch\b[^\]]*\][^}]*\}\s+@?\*project-update/,
    "project-signed install does not read architecture from remembered validated state",
  );
  assert.match(
    projectSignedInstall,
    /project-helper-arguments[\s\S]{0,500}\barch\b/,
    "project-signed install does not pass remembered architecture to helper arguments",
  );
  assert.doesNotMatch(
    projectSignedInstall,
    /\.-arch\s+js\/process/,
    "project-signed install replaces the validated architecture with process input",
  );
  const projectHelperArguments = updaterFunction("project-helper-arguments");
  assert.ok(
    projectHelperArguments,
    "Electron does not construct native project helper arguments",
  );
  assert.match(
    projectHelperArguments,
    /["']--arch["']\s+arch\b/,
    "native helper arguments do not emit the remembered validated architecture",
  );
  assert.doesNotMatch(
    projectHelperArguments,
    /\.-arch\s+js\/process/,
    "native helper arguments bypass remembered validated architecture",
  );
  assert.match(
    combined,
    /(?:native[\s\S]{0,120}helper|helper[\s\S]{0,120}native|project[-_\s]?signed[\s\S]{0,120}(?:install|update))/i,
    "macOS selfhost branch does not call the packaged native helper",
  );
  const legacyInstall = updaterFunction("<legacy-install!");
  assert.ok(
    legacyInstall,
    "Electron does not isolate the electron-updater fallback",
  );
  assert.match(
    legacyInstall,
    /\.quitAndInstall\s+autoUpdater\s+false\s+true/,
    "legacy fallback does not contain the expected electron-updater install call",
  );
  const allQuitAndInstallCalls = `${handler}\n${updater}`.match(
    /\.quitAndInstall\s+autoUpdater/g,
  ) ?? [];
  assert.equal(
    allQuitAndInstallCalls.length,
    1,
    "quitAndInstall must exist only inside the isolated legacy fallback",
  );
  assert.doesNotMatch(
    `${handler}\n${updater.replace(legacyInstall, "")}`,
    /\.quitAndInstall\s+autoUpdater/,
    "a direct quitAndInstall path bypasses the isolated legacy fallback",
  );
  const installDownloadedUpdate = updaterFunction(
    "install-downloaded-update!",
  );
  assert.ok(
    installDownloadedUpdate,
    "Electron does not expose the shared downloaded-update install entry",
  );
  assert.match(
    installDownloadedUpdate,
    /\(if\s+\(project-signed-macos-updater\?\)[\s\S]{0,800}<project-signed-install![\s\S]{0,800}<legacy-install!/,
    "legacy quitAndInstall is not confined to the explicit negative branch of the signed macOS predicate",
  );
  assert.equal(
    (installDownloadedUpdate.match(/<legacy-install!/g) ?? []).length,
    1,
    "downloaded-update entry must have exactly one legacy fallback call",
  );
});

addCase(
  cases,
  "isolated signer fixture rejects a generic algorithm and reaches verified output",
  () => runIsolatedSignerTreeSelfTest(),
);

addCase(
  cases,
  "isolated signer rejects non-production algorithms without output",
  () => runIsolatedSignerAlgorithmRejections(),
);

addCase(
  cases,
  "exact-policy signer/verifier control rejects tampering",
  () =>
    runIsolatedSignerExactAlgorithmRoundTrip({
      exactPolicyControl: true,
    }),
);

addCase(
  cases,
  "isolated signer preserves exact production algorithm and rejects tampering",
  () => runIsolatedSignerExactAlgorithmRoundTrip(),
);

addCase(
  cases,
  "native helper is fail-closed, atomic, and rollback-safe",
  async () => runNativeHelperContract(),
);

addCase(cases, "weak identifier-only ad-hoc DR is forgeable", () =>
  physicalAdHocWeakness(),
);

addCase(cases, "explicit certificate-hash DR fails closed without consumer trust", () =>
  explicitCertificateHashConsumerProbe(),
);

const initialUserTrustSettingsDigest = userTrustSettingsDigest();
addCase(cases, "diagnostics leave user Trust Settings unchanged", () => {
  if (process.platform !== "darwin") {
    throw new SkipTest("Trust Settings snapshot requires macOS");
  }
  assert.equal(userTrustSettingsDigest(), initialUserTrustSettingsDigest);
});

if (physicalShipItContractSelfTest) {
  cases.splice(0, cases.length);
  addCase(
    cases,
    "physical ShipIt replacement distinguishes success, unreadable request, and signal termination",
    () => physicalAdHocWeakness(),
  );
} else if (managedSignerNativeKeyAlignmentContractSelfTest) {
  cases.splice(0, cases.length);
  addCase(
    cases,
    "isolated managed signer/native same-key control is fail-closed",
    () =>
      runIsolatedManagedSignerNativeKeyAlignmentContract({
        alignNativeFixtureKey: true,
      }),
  );
  addCase(
    cases,
    "managed signer and native helper embed the same Ed25519 policy key",
    () => runIsolatedManagedSignerNativeKeyAlignmentContract(),
  );
} else if (isolatedSignerAlgorithmContractSelfTest) {
  cases.splice(0, cases.length);
  addCase(
    cases,
    "isolated signer rejects non-production algorithms without output",
    () => runIsolatedSignerAlgorithmRejections(),
  );
  addCase(
    cases,
    "exact-policy signer/verifier control rejects tampering",
    () =>
      runIsolatedSignerExactAlgorithmRoundTrip({
        exactPolicyControl: true,
      }),
  );
  addCase(
    cases,
    "isolated signer preserves exact production algorithm and rejects tampering",
    () => runIsolatedSignerExactAlgorithmRoundTrip(),
  );
} else if (releaseBlockSelfTest) {
  cases.splice(0, cases.length);
  addCase(cases, "UNCONFIGURED production signing policy", () => {
    throw new ReleaseBlock(
      "production Ed25519 public key is UNCONFIGURED; release is blocked",
    );
  });
} else {
  addCase(
    cases,
    "UNCONFIGURED policy is a release block rather than a failure",
    () => {
      const result = command(
        process.execPath,
        [fileURLToPath(import.meta.url), "--release-block-self-test"],
        { allowFailure: true },
      );
      assert.notEqual(
        result.status,
        0,
        "UNCONFIGURED release-block probe exited successfully",
      );
      assert.match(
        result.output,
        /\[project-updater\] SUMMARY passed=0 failed=0 blocked=1 skipped=0 total=1/,
        `UNCONFIGURED probe did not report the exact release-block summary:\n${result.output}`,
      );
    },
  );
}

let passed = 0;
let failed = 0;
let skipped = 0;
let blocked = 0;
for (const [name, test] of cases) {
  try {
    await test();
    passed += 1;
    console.log(`[project-updater] PASS ${name}`);
  } catch (error) {
    if (error instanceof ReleaseBlock) {
      blocked += 1;
      console.error(`[project-updater] BLOCK ${name}: ${error.message}`);
    } else if (error instanceof SkipTest) {
      skipped += 1;
      console.log(`[project-updater] SKIP ${name}: ${error.message}`);
    } else {
      failed += 1;
      console.error(
        `[project-updater] FAIL ${name}: ${
          error instanceof Error ? error.stack || error.message : error
        }`,
      );
    }
  }
}

console.log(
  `[project-updater] SUMMARY passed=${passed} failed=${failed} blocked=${blocked} skipped=${skipped} total=${cases.length}`,
);
if (failed > 0 || blocked > 0) process.exitCode = 1;
