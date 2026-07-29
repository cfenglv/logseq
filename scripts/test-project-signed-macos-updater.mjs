#!/usr/bin/env node

import assert from "node:assert/strict";
import {
  createHash,
  createPublicKey,
  generateKeyPairSync,
  sign as cryptoSign,
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
  const markerSearch = command("git", ["grep", "-l", marker], {
    allowFailure: true,
  });
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

const normalizeKeyId = (value) => {
  assert.equal(typeof value, "string", "project public keyId is missing");
  const payload = value.replace(/^(?:sha256|ed25519)(?::|-)/i, "");
  if (/^[a-f0-9]{32,64}$/i.test(payload) && payload.length % 2 === 0) {
    return payload.toLowerCase();
  }
  if (/^[A-Za-z0-9+/_-]+={0,2}$/.test(payload)) {
    const digest = Buffer.from(payload, /[-_]/.test(payload) ? "base64url" : "base64");
    if (digest.length === 32) return digest.toString("hex");
  }
  assert.fail("project keyId is not a sufficiently strong SHA-256 identifier");
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
      derivedKeyId.startsWith(normalizeKeyId(declaredKeyId)),
      true,
      "project keyId does not match the configured public key",
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

const makeNativeHelperApp = ({
  applicationId = "com.logseq.logseq",
  destination,
  escapeSymlink,
  marker,
  quarantine,
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
  fs.writeFileSync(executable, "#!/bin/sh\nexit 0\n", { mode: 0o755 });
  fs.writeFileSync(path.join(resources, "update-state.txt"), marker);
  if (escapeSymlink) {
    fs.symlinkSync(
      escapeSymlink,
      path.join(resources, "escape-link"),
    );
  }
  if (quarantine) {
    command("xattr", [
      "-w",
      "com.apple.quarantine",
      quarantine,
      destination,
    ]);
  }
  return destination;
};

const archiveApp = ({ app, archive }) => {
  command("ditto", [
    "-c",
    "-k",
    "--sequesterRsrc",
    "--keepParent",
    app,
    archive,
  ]);
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
  const sha512 = createHash("sha512").update(artifact).digest("base64");
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
  applicationId,
  arch = "arm64",
  bundleId,
  escapeSymlink,
  payloadDomain,
  privateKeyPem,
  root,
  version = "2.0.1-selfhost.6",
}) => {
  fs.mkdirSync(root, { recursive: true });
  const app = makeNativeHelperApp({
    applicationId: applicationId ?? bundleId,
    destination: path.join(root, "payload", "Logseq.app"),
    escapeSymlink,
    marker: version,
    quarantine: "0081;5f000000;Logseq project update;test-origin",
    version,
  });
  const artifactPath = path.join(
    root,
    `Logseq-darwin-${arch}-${version}.zip`,
  );
  archiveApp({ app, archive: artifactPath });
  return {
    app,
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

const nativeInstallArgs = ({
  arch,
  artifactPath,
  relaunch = false,
  sha512,
  signature,
  size,
  targetApp,
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
  ...(verifyOnly ? ["--verify-only"] : []),
];

const makeOldTarget = (root, bundleId = "com.logseq.logseq") => {
  const parent = path.join(root, "installed");
  const targetApp = makeNativeHelperApp({
    applicationId: bundleId,
    destination: path.join(parent, "Logseq.app"),
    marker: "2.0.1-selfhost.5",
    quarantine: "0081;4f000000;Logseq legacy install;test-origin",
    version: "2.0.1-selfhost.5",
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
  const { bundleId, payloadDomain } = loadPolicy();
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
      const { parent, targetApp } = makeOldTarget(caseRoot, bundleId);
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

    const base = makeFixture({
      root: path.join(tempRoot, "valid-base"),
    });

    const wrongKey = makeFixture({
      privateKeyPem: wrongPrivateKeyPem,
      root: path.join(tempRoot, "wrong-key"),
    });
    expectNoDamage({ fixture: wrongKey, label: "wrong signing key" });

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

    expectNoDamage({
      fixture: makeFixture({
        arch: helperArch === "arm64" ? "x64" : "arm64",
        root: path.join(tempRoot, "wrong-architecture"),
      }),
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

    const rollbackRoot = path.join(tempRoot, "mid-install-failure");
    const rollbackTarget = makeOldTarget(rollbackRoot, bundleId);
    const rollbackBefore = treeDigest(rollbackTarget.targetApp);
    const rollback = invokeNative(base, {
      env: {
        LOGSEQ_PROJECT_UPDATE_TEST_FAULT: "after-old-app-move",
      },
      targetApp: rollbackTarget.targetApp,
    });
    assert.notEqual(rollback.status, 0, "injected mid-install failure succeeded");
    assert.equal(treeDigest(rollbackTarget.targetApp), rollbackBefore);
    assert.deepEqual(fs.readdirSync(rollbackTarget.parent), ["Logseq.app"]);
    console.log("[project-updater] PASS native rollback after mid-install failure");

    const successRoot = path.join(tempRoot, "success");
    const successTarget = makeOldTarget(successRoot, bundleId);
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
      "0081;5f000000;Logseq project update;test-origin",
      "successful replacement changed candidate quarantine metadata",
    );
    assert.deepEqual(fs.readdirSync(successTarget.parent), ["Logseq.app"]);
    assert.equal(userTrustSettingsDigest(), initialTrust);
  } finally {
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
};

const cases = [];

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

addCase(cases, "native helper is fail-closed, atomic, and rollback-safe", async () =>
  runNativeHelperContract(),
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
