#!/usr/bin/env node

import assert from "node:assert/strict";
import {
  createHash,
  createPublicKey,
  generateKeyPairSync,
  sign as cryptoSign,
  verify as cryptoVerify,
} from "node:crypto";
import { spawn, spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { updaterSignatureGatePlan } from "./run-macos-updater-signature-policy.mjs";
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
  return { output, status: result.status };
};

const sha256 = (value) =>
  createHash("sha256").update(value).digest("hex");

const fileSha256 = (file) => sha256(fs.readFileSync(file));

const addCase = (cases, name, test) => cases.push([name, test]);

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

const trackedPrivateMaterial = () => {
  const tracked = command("git", ["ls-files", "-z"]).output.split("\0");
  const forbiddenNames = tracked.filter((file) =>
    /(?:^|\/)(?:[^/]+\.(?:p12|pfx|key)|private[-_]?key(?:\.pem)?)$/i.test(
      file,
    ),
  );
  const marker = ["BEGIN", "PRIVATE", "KEY"].join(" ");
  const markerSearch = command(
    "git",
    [
      "grep",
      "-l",
      marker,
      "--",
      ".",
      ":(exclude)scripts/test-project-signed-macos-updater.mjs",
    ],
    {
      allowFailure: true,
    },
  );
  return {
    forbiddenNames,
    markerFiles: markerSearch.status === 0 ? markerSearch.output : "",
  };
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

const scriptsMatching = (pattern) =>
  fs
    .readdirSync(path.join(repoRoot, "scripts"), { withFileTypes: true })
    .filter((entry) => entry.isFile() && pattern.test(entry.name))
    .map((entry) => path.join(repoRoot, "scripts", entry.name));

const discoverSignerPath = (workflow) => {
  const candidates = scriptsMatching(
    /^(?:sign|create)(?=.*(?:project|macos))(?=.*update).*\.mjs$/i,
  );
  const referenced = candidates.filter((candidate) =>
    workflow.includes(path.basename(candidate)),
  );
  assert.equal(
    referenced.length,
    1,
    `expected one workflow-referenced project update signer, found ${referenced
      .map((file) => path.basename(file))
      .join(", ") || "none"}`,
  );
  return referenced[0];
};

const createIsolatedSignerTree = ({
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
        value[key] = "Ed25519";
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
      algorithm: "Ed25519",
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

const signingVariableNames = (workflow) => {
  const secretNames = [
    ...workflow.matchAll(/secrets\.([A-Z][A-Z0-9_]*ED25519[A-Z0-9_]*)/g),
  ].map((match) => match[1]);
  const environmentNames = [
    ...workflow.matchAll(
      /^\s*([A-Z][A-Z0-9_]+):\s*\${{\s*secrets\.[A-Z0-9_]+\s*}}/gm,
    ),
  ].map((match) => match[1]);
  return [...new Set([...secretNames, ...environmentNames])];
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
  const statePath = path.join(tempRoot, "ShipItState.plist");
  fs.writeFileSync(
    statePath,
    JSON.stringify({
      updateBundleURL: new URL(`file://${updateApp}`).href,
      targetBundleURL: new URL(`file://${targetApp}`).href,
      bundleIdentifier: null,
      launchAfterInstallation: false,
      useUpdateBundleName: false,
    }),
    { mode: 0o644 },
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
  const result = command(
    shipIt,
    [`com.logseq.project-signed-test.${process.pid}.ShipIt`, statePath],
    { allowFailure: true },
  );
  const after = fs.existsSync(targetApp)
    ? command("plutil", [
        "-extract",
        "CFBundleShortVersionString",
        "raw",
        path.join(targetApp, "Contents", "Info.plist"),
      ]).output
    : "<missing>";
  return { ...result, before, after };
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
    if (
      shipIt.output.includes("SQRLShipItRequestErrorDomain") ||
      shipIt.output.includes("Could not read update request")
    ) {
      console.log(
        `[project-updater] BLOCK physical ShipIt fixture exit=${shipIt.status} before=${shipIt.before} after=${shipIt.after}: request unreadable`,
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
  command(
    sevenZipExecutable(),
    [
      "a",
      "-bd",
      "-bb0",
      "-tzip",
      "-mtc=off",
      "-mta=off",
      "-mtm=off",
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
      "-mtc=off",
      "-mta=off",
      "-mtm=off",
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

const runNativeHelperContract = async () => {
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
  } = loadPolicy();
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
    const signingKeys = generateKeyPairSync("ed25519");
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
      signerSecrets.length > 0 &&
      signerSecrets.some((name) => Boolean(process.env[name]));
    const productionCompositeBlockReason = !productionPolicyConfigured
      ? "production Ed25519 policy is UNCONFIGURED; managed signer/native composite is blocked"
      : !managedSignerAvailable
        ? "managed production Ed25519 private key is unavailable; signer/native composite is blocked"
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
    const signerEnv = { ...process.env };
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
      return { ...fixture, signature };
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
    const invokeViaJsRuntime = (fixture, targetApp) =>
      command(
        process.execPath,
        [
          helperRunnerPath,
          "--helper",
          helperPath,
          "--",
          ...nativeInstallArgs({ ...fixture, targetApp }),
        ],
        { allowFailure: true },
      );
    const expectVersionTransition = ({
      accepted,
      candidateVersion,
      currentVersion,
      fixture,
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
      const result = invokeViaJsRuntime(updateFixture, target.targetApp);
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

const cases = [];
const releaseBlockSelfTest = process.argv.includes(
  "--release-block-self-test",
);

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

addCase(cases, "release signing is fail-closed on an external private key", () => {
  const workflow = fs.readFileSync(workflowPath, "utf8");
  const secretNames = signingVariableNames(workflow);
  const ed25519SecretNames = [
    ...workflow.matchAll(/secrets\.([A-Z][A-Z0-9_]*ED25519[A-Z0-9_]*)/g),
  ].map((match) => match[1]);
  assert.ok(
    ed25519SecretNames.some((name) => /PRIVATE|SIGNING/i.test(name)),
    "release workflow does not consume an external Ed25519 signing secret",
  );
  assert.match(workflow, /arm64/i);
  assert.match(workflow, /x64/i);
  const signerPath = discoverSignerPath(workflow);
  const signerReferences =
    workflow.match(new RegExp(path.basename(signerPath), "g"))?.length ?? 0;
  assert.ok(
    signerReferences >= 2 ||
      /matrix:[\s\S]{0,500}(?:arm64[\s\S]{0,100}x64|x64[\s\S]{0,100}arm64)/i.test(
        workflow,
      ),
    "project signer is not wired to both macOS architectures",
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
    const missingKey = command(
      process.execPath,
      [
        signerPath,
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
          ...Object.fromEntries(secretNames.map((name) => [name, ""])),
        },
      },
    );
    assert.notEqual(
      missingKey.status,
      0,
      "release signer accepted a missing private key",
    );
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

addCase(cases, "nightly signing is reachable for both macOS architectures", () => {
  const workflow = fs.readFileSync(workflowPath, "utf8");
  const signerPath = discoverSignerPath(workflow);
  for (const [jobName, arch] of [
    ["build-macos-x64", "x64"],
    ["build-macos-arm64", "arm64"],
  ]) {
    const job = workflowJobSource(workflow, jobName);
    const signerIndex = job.indexOf(path.basename(signerPath));
    assert.notEqual(
      signerIndex,
      -1,
      `${jobName} does not invoke the project signer`,
    );
    const signerContext = job.slice(
      Math.max(0, signerIndex - 800),
      signerIndex + 1200,
    );
    assert.match(
      signerContext,
      new RegExp(`(?:--arch\\s+${arch}\\b|--${arch}\\b)`),
      `${jobName} does not sign its ${arch} artifact`,
    );
    assert.match(
      signerContext,
      /--version[\s\S]{0,120}steps\.ref\.outputs\.version/,
      `${jobName} does not pass the exact workflow version to the signer`,
    );
    assert.doesNotMatch(
      signerContext,
      /build-target\s*!=\s*['"]nightly|build-target\s*==\s*['"]stable/,
      `${jobName} makes the signer unreachable for nightly builds`,
    );
  }
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
  for (const [label, source] of [
    ["handler.cljs", handler],
    ["updater.cljs", updater],
  ]) {
    for (const match of source.matchAll(/\.quitAndInstall\s+autoUpdater/g)) {
      const guardedContext = source.slice(
        Math.max(0, match.index - 2400),
        match.index,
      );
      const explicitlyNegative =
        /(?:if-not|when-not|not)\s+\(?[\s\S]{0,240}project-signed-macos-updater\?/i.test(
          guardedContext,
        );
      const signedPositiveBranchBeforeFallback =
        /(?:if|cond)\s+\(?[\s\S]{0,240}project-signed-macos-updater\?[\s\S]*(?:native[\s\S]{0,120}helper|project[-_\s]?signed[\s\S]{0,120}(?:install|update))/i.test(
          guardedContext,
        );
      assert.equal(
        explicitlyNegative || signedPositiveBranchBeforeFallback,
        true,
        `${label} has quitAndInstall outside the negative branch of project-signed-macos-updater?`,
      );
    }
  }
});

addCase(cases, "isolated signer fixture reaches verified output", () =>
  runIsolatedSignerTreeSelfTest(),
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

if (releaseBlockSelfTest) {
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
