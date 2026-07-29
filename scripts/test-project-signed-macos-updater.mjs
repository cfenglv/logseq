#!/usr/bin/env node

import assert from "node:assert/strict";
import {
  createHash,
  generateKeyPairSync,
  sign as cryptoSign,
} from "node:crypto";
import { spawn, spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  signedBaselineManifest,
  updaterSignatureGatePlan,
} from "./run-macos-updater-signature-policy.mjs";

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
const verifierPath = path.join(
  repoRoot,
  "resources",
  "project-updater-signature.mjs",
);
const signerPath = path.join(
  repoRoot,
  "scripts",
  "sign-project-update.mjs",
);
const helperBuildPath = path.join(
  repoRoot,
  "scripts",
  "build-project-update-helper.mjs",
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

const loadPolicy = () => {
  const policy = JSON.parse(fs.readFileSync(policyPath, "utf8"));
  assert.equal(policy.schema, "logseq-selfhost-update/v1");
  assert.equal(policy.algorithm, "Ed25519");
  assert.match(policy.publicKeySha256, /^[a-f0-9]{64}$/);
  assert.equal(path.isAbsolute(policy.publicKeyPath), false);
  assert.equal(path.isAbsolute(policy.nativeHelperPath), false);
  assert.doesNotMatch(
    policy.nativeHelperPath,
    /\.(?:[cm]?js|ts|cljs)$/i,
    "replacement helper must be a native executable, not JavaScript",
  );
  const publicKeyPath = path.resolve(
    path.dirname(policyPath),
    policy.publicKeyPath,
  );
  const nativeHelperPath = path.resolve(
    path.dirname(policyPath),
    policy.nativeHelperPath,
  );
  const publicKeyPem = fs.readFileSync(publicKeyPath, "utf8");
  assert.match(publicKeyPem, /^-----BEGIN PUBLIC KEY-----/);
  assert.equal(fileSha256(publicKeyPath), policy.publicKeySha256);
  return { policy, publicKeyPath, publicKeyPem, nativeHelperPath };
};

const makeManifest = ({
  artifact,
  arch = "arm64",
  applicationId = "com.logseq.logseq",
  artifactName,
  channel = `selfhost-macos-v2-${arch}`,
  platform = "darwin",
  version = "2.0.1-selfhost.6",
} = {}) => ({
  schema: "logseq-selfhost-update/v1",
  applicationId,
  channel,
  version,
  platform,
  arch,
  artifact: {
    name: artifactName ?? `Logseq-darwin-${arch}-${version}.zip`,
    size: artifact.length,
    sha512: createHash("sha512").update(artifact).digest("base64"),
  },
});

const importProductionVerifier = async () => {
  if (!fs.existsSync(verifierPath)) return null;
  return import(`${new URL(`file://${verifierPath}`).href}?test=${Date.now()}`);
};

const exerciseProductionVerifier = async (module) => {
  const {
    canonicalProjectUpdatePayload,
    createProjectUpdateInstallGate,
    verifyProjectUpdate,
  } = module;
  assert.equal(typeof canonicalProjectUpdatePayload, "function");
  assert.equal(typeof verifyProjectUpdate, "function");
  assert.equal(typeof createProjectUpdateInstallGate, "function");

  const artifact = Buffer.from("valid selfhost.6 update payload");
  const keys = generateKeyPairSync("ed25519");
  const wrongKeys = generateKeyPairSync("ed25519");
  const publicKeyPem = keys.publicKey.export({
    format: "pem",
    type: "spki",
  });
  const manifest = makeManifest({ artifact });
  const signature = cryptoSign(
    null,
    Buffer.from(canonicalProjectUpdatePayload(manifest)),
    keys.privateKey,
  ).toString("base64");
  const valid = {
    artifact,
    manifest,
    publicKeyPem,
    signature,
    currentVersion: "2.0.1-selfhost.5",
    expectedPlatform: "darwin",
    expectedArch: "arm64",
    expectedChannel: "selfhost-macos-v2-arm64",
  };
  await verifyProjectUpdate(valid);

  const rejectionCases = [
    ["tampered artifact", { artifact: Buffer.from("tampered") }],
    ["missing signature", { signature: "" }],
    [
      "wrong private key",
      {
        signature: cryptoSign(
          null,
          Buffer.from(canonicalProjectUpdatePayload(manifest)),
          wrongKeys.privateKey,
        ).toString("base64"),
      },
    ],
    ["replayed already-installed version", { currentVersion: manifest.version }],
    [
      "version substitution",
      { manifest: { ...manifest, version: "2.0.1-selfhost.7" } },
    ],
    ["architecture mismatch", { expectedArch: "x64" }],
    ["platform mismatch", { expectedPlatform: "linux" }],
    ["channel mismatch", { expectedChannel: "selfhost-macos-v2-x64" }],
    [
      "artifact name substitution",
      {
        manifest: {
          ...manifest,
          artifact: { ...manifest.artifact, name: "different.zip" },
        },
      },
    ],
  ];
  for (const [label, overrides] of rejectionCases) {
    await assert.rejects(
      () => verifyProjectUpdate({ ...valid, ...overrides }),
      undefined,
      label,
    );
  }

  let replaceCalls = 0;
  const gate = createProjectUpdateInstallGate({
    publicKeyPem,
    currentVersion: valid.currentVersion,
    platform: valid.expectedPlatform,
    arch: valid.expectedArch,
    channel: valid.expectedChannel,
    replace: async () => {
      replaceCalls += 1;
    },
  });
  const invalidInstallPayloads = [
    ["tampered artifact", { artifact: Buffer.from("tampered") }],
    ["missing signature", { signature: "" }],
    [
      "wrong private key",
      {
        signature: cryptoSign(
          null,
          Buffer.from(canonicalProjectUpdatePayload(manifest)),
          wrongKeys.privateKey,
        ).toString("base64"),
      },
    ],
    [
      "version substitution",
      { manifest: { ...manifest, version: "2.0.1-selfhost.7" } },
    ],
    [
      "architecture substitution",
      { manifest: { ...manifest, arch: "x64" } },
    ],
    [
      "platform substitution",
      { manifest: { ...manifest, platform: "linux" } },
    ],
  ];
  for (const [label, overrides] of invalidInstallPayloads) {
    await assert.rejects(
      () =>
        gate.install({
          artifact: overrides.artifact ?? artifact,
          manifest: overrides.manifest ?? manifest,
          signature: overrides.signature ?? signature,
        }),
      undefined,
      label,
    );
    assert.equal(
      replaceCalls,
      0,
      `replace was called before ${label} was rejected`,
    );
  }
  await gate.install({ artifact, manifest, signature });
  assert.equal(replaceCalls, 1);
};

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

const signManifestFixture = ({
  artifactPath,
  manifest,
  privateKeyPem,
  root,
}) => {
  const manifestPath = path.join(root, "manifest.json");
  const signaturePath = path.join(root, "manifest.sig");
  fs.writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`);
  const result = command(
    process.execPath,
    [
      signerPath,
      "--manifest",
      manifestPath,
      "--artifact",
      artifactPath,
      "--signature-out",
      signaturePath,
    ],
    {
      allowFailure: true,
      env: {
        ...process.env,
        SELFHOST_UPDATER_ED25519_PRIVATE_KEY: privateKeyPem,
      },
    },
  );
  assert.equal(result.status, 0, result.output);
  assert.equal(fs.existsSync(signaturePath), true);
  return { manifestPath, signaturePath };
};

const makeSignedNativeUpdate = ({
  applicationId = "com.logseq.logseq",
  arch = "arm64",
  escapeSymlink,
  privateKeyPem,
  root,
  version = "2.0.1-selfhost.6",
}) => {
  fs.mkdirSync(root, { recursive: true });
  const app = makeNativeHelperApp({
    applicationId,
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
  const artifact = fs.readFileSync(artifactPath);
  const manifest = makeManifest({
    applicationId,
    arch,
    artifact,
    artifactName: path.basename(artifactPath),
    version,
  });
  return {
    app,
    artifactPath,
    manifest,
    ...signManifestFixture({
      artifactPath,
      manifest,
      privateKeyPem,
      root,
    }),
  };
};

const makeTraversalArchive = ({ archive, sentinel }) => {
  const root = fs.mkdtempSync(
    path.join(os.tmpdir(), "logseq-traversal-zip-"),
  );
  try {
    fs.writeFileSync(path.join(root, "payload.txt"), "path traversal");
    command("zip", ["-q", archive, "payload.txt"], { cwd: root });
    const note = command("zipnote", [archive]).output;
    assert.match(note, /^@ payload\.txt$/m);
    const renamed = note.replace(
      /^@ payload\.txt$/m,
      `@ payload.txt\n@=../../../../../../private/tmp/${sentinel}`,
    );
    const result = spawnSync("zipnote", ["-w", archive], {
      encoding: "utf8",
      input: `${renamed}\n`,
      stdio: ["pipe", "pipe", "pipe"],
    });
    assert.equal(
      result.status,
      0,
      `${result.stdout || ""}${result.stderr || ""}`,
    );
    assert.match(
      command("zipinfo", ["-1", archive]).output,
      new RegExp(sentinel),
    );
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
};

const nativeInstallArgs = ({
  artifactPath,
  manifestPath,
  signaturePath,
  targetApp,
}) => [
  "--artifact",
  artifactPath,
  "--manifest",
  manifestPath,
  "--signature",
  signaturePath,
  "--target-app",
  targetApp,
  "--expected-current-version",
  "2.0.1-selfhost.5",
  "--expected-bundle-id",
  "com.logseq.logseq",
  "--expected-platform",
  "darwin",
  "--expected-arch",
  "arm64",
  "--expected-channel",
  "selfhost-macos-v2-arm64",
];

const makeOldTarget = (root) => {
  const parent = path.join(root, "installed");
  const targetApp = makeNativeHelperApp({
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
    fs.existsSync(helperBuildPath),
    true,
    `${helperBuildPath} is missing`,
  );
  assert.equal(
    fs.existsSync(helperRunnerPath),
    true,
    `${helperRunnerPath} is missing`,
  );
  const { policy } = loadPolicy();
  const runnerSource = fs.readFileSync(helperRunnerPath, "utf8");
  assert.doesNotMatch(
    runnerSource,
    /--(?:public-key|helper(?:-path)?)(?:\s|["'])/i,
    "production runner exposes a public-key or helper override",
  );

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
    const privateKeyPem = signingKeys.privateKey.export({
      format: "pem",
      type: "pkcs8",
    });
    const wrongPrivateKeyPem = wrongKeys.privateKey.export({
      format: "pem",
      type: "pkcs8",
    });

    const sandbox = path.join(tempRoot, "runner-sandbox");
    fs.cpSync(path.join(repoRoot, "scripts"), path.join(sandbox, "scripts"), {
      recursive: true,
    });
    fs.cpSync(
      path.join(repoRoot, "resources"),
      path.join(sandbox, "resources"),
      { recursive: true },
    );
    const helperPath = path.resolve(
      path.join(sandbox, "resources", "updater"),
      policy.nativeHelperPath,
    );
    fs.mkdirSync(path.dirname(helperPath), { recursive: true });
    const build = command(
      process.execPath,
      [
        helperBuildPath,
        "--test-only-public-key",
        publicKeyPath,
        "--output",
        helperPath,
      ],
      { allowFailure: true },
    );
    assert.equal(build.status, 0, build.output);
    assert.equal(fs.existsSync(helperPath), true);
    assert.match(
      command("file", [helperPath]).output,
      /Mach-O/,
      "helper build did not produce a native Mach-O executable",
    );

    const invokeNative = (fixture, options = {}) =>
      command(
        helperPath,
        nativeInstallArgs({
          artifactPath: fixture.artifactPath,
          manifestPath: fixture.manifestPath,
          signaturePath: fixture.signaturePath,
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
    }) => {
      const caseRoot = path.join(tempRoot, `reject-${label.replaceAll(" ", "-")}`);
      const { parent, targetApp } = makeOldTarget(caseRoot);
      const before = treeDigest(targetApp);
      const oldQuarantine = quarantineValue(targetApp);
      try {
        mutateTargetParent?.(parent);
        const result = invokeNative(fixture, { targetApp });
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

    const base = makeSignedNativeUpdate({
      privateKeyPem,
      root: path.join(tempRoot, "valid-base"),
    });

    const wrongKey = makeSignedNativeUpdate({
      privateKeyPem: wrongPrivateKeyPem,
      root: path.join(tempRoot, "wrong-key"),
    });
    expectNoDamage({ fixture: wrongKey, label: "wrong signing key" });

    const tampered = makeSignedNativeUpdate({
      privateKeyPem,
      root: path.join(tempRoot, "tampered"),
    });
    fs.appendFileSync(tampered.artifactPath, "tampered after signing");
    expectNoDamage({ fixture: tampered, label: "tampered zip bytes" });

    for (const [label, mutate] of [
      ["signed bundle-id substitution", (manifest) => {
        manifest.applicationId = "com.attacker.logseq";
      }],
      ["signed version substitution", (manifest) => {
        manifest.version = "2.0.1-selfhost.7";
      }],
      ["signed architecture substitution", (manifest) => {
        manifest.arch = "x64";
      }],
      ["signed zip-size substitution", (manifest) => {
        manifest.artifact.size += 1;
      }],
      ["signed zip-hash substitution", (manifest) => {
        manifest.artifact.sha512 = Buffer.alloc(64, 7).toString("base64");
      }],
    ]) {
      const changed = path.join(tempRoot, label.replaceAll(" ", "-"));
      fs.mkdirSync(changed);
      const manifest = structuredClone(base.manifest);
      mutate(manifest);
      const manifestPath = path.join(changed, "manifest.json");
      fs.writeFileSync(manifestPath, `${JSON.stringify(manifest)}\n`);
      expectNoDamage({
        fixture: { ...base, manifestPath },
        label,
      });
    }

    for (const [label, version] of [
      ["replayed current version", "2.0.1-selfhost.5"],
      ["signed downgrade", "2.0.1-selfhost.4"],
    ]) {
      expectNoDamage({
        fixture: makeSignedNativeUpdate({
          privateKeyPem,
          root: path.join(tempRoot, label.replaceAll(" ", "-")),
          version,
        }),
        label,
      });
    }

    expectNoDamage({
      fixture: makeSignedNativeUpdate({
        arch: "x64",
        privateKeyPem,
        root: path.join(tempRoot, "wrong-architecture"),
      }),
      label: "validly signed wrong architecture",
    });

    const sentinel = `logseq-update-traversal-${process.pid}-${Date.now()}`;
    const traversalRoot = path.join(tempRoot, "path-traversal");
    fs.mkdirSync(traversalRoot);
    const traversalArchive = path.join(traversalRoot, "traversal.zip");
    makeTraversalArchive({ archive: traversalArchive, sentinel });
    const traversalBytes = fs.readFileSync(traversalArchive);
    const traversalManifest = makeManifest({
      artifact: traversalBytes,
      artifactName: path.basename(traversalArchive),
    });
    const traversalFixture = {
      artifactPath: traversalArchive,
      ...signManifestFixture({
        artifactPath: traversalArchive,
        manifest: traversalManifest,
        privateKeyPem,
        root: traversalRoot,
      }),
    };
    const escaped = path.join("/private/tmp", sentinel);
    expectNoDamage({
      fixture: traversalFixture,
      label: "signed zip path traversal",
      postcondition: () => assert.equal(fs.existsSync(escaped), false),
    });

    const symlinkDestination = path.join(tempRoot, "outside-symlink-target");
    fs.writeFileSync(symlinkDestination, "must not be reachable");
    expectNoDamage({
      fixture: makeSignedNativeUpdate({
        escapeSymlink: symlinkDestination,
        privateKeyPem,
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
    const rollbackTarget = makeOldTarget(rollbackRoot);
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
    const successTarget = makeOldTarget(successRoot);
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
    const sandboxRunner = path.join(
      sandbox,
      "scripts",
      "run-project-signed-macos-update.mjs",
    );
    const success = await runAsync(
      process.execPath,
      [
        sandboxRunner,
        ...nativeInstallArgs({
          artifactPath: base.artifactPath,
          manifestPath: base.manifestPath,
          signaturePath: base.signaturePath,
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

addCase(cases, "fixed Ed25519 project public key policy is internally pinned", () =>
  loadPolicy(),
);

addCase(cases, ".4 legacy feed remains pinned and .5 remains manual", () => {
  assert.equal(
    updaterSignatureGatePlan("2.0.1-selfhost.5", false).mode,
    "manual-migration",
  );
  assert.equal(fs.existsSync(signedBaselineManifest), false);
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
  assert.ok(
    (workflow.match(/secrets\.SELFHOST_UPDATER_ED25519_PRIVATE_KEY/g)?.length ??
      0) >= 2,
    "both macOS architectures must consume the external project private key",
  );
  assert.ok(
    (workflow.match(/sign-project-update\.mjs/g)?.length ?? 0) >= 2,
    "both macOS architectures must sign their update manifest",
  );
  assert.match(workflow, /Missing project updater private key/i);
  assert.equal(fs.existsSync(signerPath), true);
  const tempRoot = fs.mkdtempSync(
    path.join(os.tmpdir(), "logseq-project-signer-no-key-"),
  );
  try {
    const artifact = path.join(tempRoot, "update.zip");
    const manifest = path.join(tempRoot, "manifest.json");
    const signature = path.join(tempRoot, "manifest.sig");
    fs.writeFileSync(artifact, "release artifact");
    fs.writeFileSync(
      manifest,
      JSON.stringify(makeManifest({ artifact: fs.readFileSync(artifact) })),
    );
    const missingKey = command(
      process.execPath,
      [
        signerPath,
        "--manifest",
        manifest,
        "--artifact",
        artifact,
        "--signature-out",
        signature,
      ],
      {
        allowFailure: true,
        env: {
          ...process.env,
          SELFHOST_UPDATER_ED25519_PRIVATE_KEY: "",
        },
      },
    );
    assert.notEqual(
      missingKey.status,
      0,
      "release signer accepted a missing private key",
    );
    assert.equal(
      fs.existsSync(signature),
      false,
      "release signer emitted a signature without the private key",
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
  assert.equal(
    /\.quitAndInstall\s+autoUpdater/.test(handler),
    false,
    "IPC handler still calls quitAndInstall directly",
  );
  assert.equal(
    /\.quitAndInstall\s+autoUpdater/.test(updater),
    false,
    "automatic restart path still calls quitAndInstall directly",
  );
  const directReplacement = command(
    "rg",
    [
      "-n",
      String.raw`\.quitAndInstall\s+autoUpdater`,
      path.join(repoRoot, "src", "electron"),
    ],
    { allowFailure: true },
  );
  assert.notEqual(
    directReplacement.status,
    0,
    `electron runtime retains unverified replacement entries:\n${directReplacement.output}`,
  );
  assert.match(
    `${handler}\n${updater}`,
    /run-project-signed-macos-update/,
    "macOS selfhost updater does not route through the signed native helper",
  );
});

let productionModule;
addCase(cases, "production project verifier exposes the behavior contract", async () => {
  productionModule = await importProductionVerifier();
  assert.ok(productionModule, `${verifierPath} is missing`);
  await exerciseProductionVerifier(productionModule);
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
for (const [name, test] of cases) {
  try {
    await test();
    passed += 1;
    console.log(`[project-updater] PASS ${name}`);
  } catch (error) {
    if (error instanceof SkipTest) {
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
  `[project-updater] SUMMARY passed=${passed} failed=${failed} skipped=${skipped} total=${cases.length}`,
);
if (failed > 0) process.exitCode = 1;
