#!/usr/bin/env node

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import {
  createHash,
  generateKeyPairSync,
} from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const signerRelativePath = "scripts/sign-macos-project-update.mjs";
const verifierRelativePath =
  "scripts/verify-project-signed-macos-update.mjs";
const workflowRelativePath =
  ".github/workflows/build-desktop-release.yml";
const productionPolicyRelativePath =
  "resources/updater/project-signing-policy.json";
const privateKeyEnvironmentName =
  "LOGSEQ_MACOS_UPDATE_ED25519_PRIVATE_KEY_BASE64";

const read = (relativePath, root = repoRoot) =>
  fs.readFileSync(path.join(root, relativePath), "utf8");

const relativeModuleClosure = (
  entryPaths,
  root = repoRoot,
  seen = new Set(),
) => {
  for (const relativePath of entryPaths) {
    if (seen.has(relativePath)) continue;
    seen.add(relativePath);
    const source = read(relativePath, root);
    const dependencies = [
      ...source.matchAll(
        /(?:from\s+|import\s*\(\s*|import\s+)["'](\.[^"']+\.(?:c?js|mjs))["']/g,
      ),
    ].map((match) =>
      path
        .normalize(path.join(path.dirname(relativePath), match[1]))
        .replaceAll(path.sep, "/"),
    );
    for (const dependency of dependencies) {
      assert.doesNotMatch(
        dependency,
        /^(?:\.\.(?:\/|$)|\/)/,
        `${relativePath} imports outside the repository: ${dependency}`,
      );
    }
    relativeModuleClosure(dependencies, root, seen);
  }
  return seen;
};

const copyRelativeModuleClosure = (entryPaths, destinationRoot) => {
  const closure = relativeModuleClosure(entryPaths);
  for (const relativePath of closure) {
    const destination = path.join(destinationRoot, relativePath);
    fs.mkdirSync(path.dirname(destination), { recursive: true });
    fs.copyFileSync(path.join(repoRoot, relativePath), destination);
  }
  return closure;
};

const securityLookupLocations = (closure, root = repoRoot) => {
  const pattern = /(["'])\/usr\/bin\/security\1/g;
  const locations = [];
  for (const relativePath of closure) {
    const source = read(relativePath, root);
    for (const match of source.matchAll(pattern)) {
      locations.push({ relativePath, index: match.index });
    }
  }
  return locations;
};

const run = (executable, args, options = {}) => {
  const result = spawnSync(executable, args, {
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

const sha256 = (value) =>
  createHash("sha256").update(value).digest("hex");

const rawPublicKey = (publicKey) =>
  Buffer.from(publicKey.export({ format: "jwk" }).x, "base64url");

const projectPolicyFor = (publicKey) => {
  const raw = rawPublicKey(publicKey);
  return {
    algorithm: "ed25519-sha512-manifest-v1",
    bundleIdentifier: "com.logseq.logseq",
    keyId: `ed25519:${sha256(raw)}`,
    minimumBootstrapRevision: 5,
    payloadDomain: "logseq-selfhost-macos-update-v1",
    publicKeyBase64: raw.toString("base64"),
  };
};

const writeJson = (filePath, value) => {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`);
};

const writeKeychainProbe = ({
  filePath,
  privateKeyBase64,
  status = 0,
}) => {
  const source = `#!/usr/bin/env node
// TEST-ONLY Keychain probe. This file exists only inside a disposable temp tree.
const fs = require("node:fs");
const args = process.argv.slice(2);
if (process.env.LOGSEQ_TEST_KEYCHAIN_TRACE) {
  fs.writeFileSync(process.env.LOGSEQ_TEST_KEYCHAIN_TRACE, JSON.stringify(args));
}
const privateKey = ${JSON.stringify(privateKeyBase64 ?? "")};
if (${status} !== 0) {
  process.stderr.write(${JSON.stringify(
    status === 44
      ? "security: SecKeychainSearchCopyNext: The specified item could not be found in the keychain.\\n"
      : "security: Keychain read failed.\\n",
  )});
  process.exit(${status});
}
process.stdout.write(privateKey);
`;
  fs.writeFileSync(filePath, source, { mode: 0o700 });
};

const replaceSecurityExecutableForTest = (
  closure,
  isolatedRoot,
  probePath,
) => {
  const locations = securityLookupLocations(closure, isolatedRoot);
  assert.equal(
    locations.length,
    1,
    `signer import closure must contain exactly one absolute /usr/bin/security lookup, found ${locations.length}`,
  );
  const securityModulePath = path.join(
    isolatedRoot,
    locations[0].relativePath,
  );
  const source = fs.readFileSync(securityModulePath, "utf8");
  const pattern = /(["'])\/usr\/bin\/security\1/g;
  fs.writeFileSync(
    securityModulePath,
    source.replaceAll(pattern, JSON.stringify(probePath)),
  );
};

const createIsolatedSignerFixture = () => {
  const root = fs.mkdtempSync(
    path.join(os.tmpdir(), "logseq-local-keychain-signer-contract-"),
  );
  const signerClosure = copyRelativeModuleClosure(
    [signerRelativePath, verifierRelativePath],
    root,
  );

  const signingKeys = generateKeyPairSync("ed25519");
  const wrongKeys = generateKeyPairSync("ed25519");
  const policy = projectPolicyFor(signingKeys.publicKey);
  writeJson(
    path.join(root, productionPolicyRelativePath),
    policy,
  );

  const archive = path.join(root, "Logseq-darwin-arm64-2.0.1-selfhost.6.zip");
  const metadata = path.join(root, "latest-mac.yml");
  const signatureOutput = path.join(root, "project-signature.json");
  const trace = path.join(root, "keychain-invocation.json");
  const probe = path.join(root, "test-keychain-probe.cjs");
  const archiveBytes = Buffer.from("isolated macOS update artifact\n");
  const archiveSha512 = createHash("sha512")
    .update(archiveBytes)
    .digest("base64");
  fs.writeFileSync(archive, archiveBytes);
  fs.writeFileSync(
    metadata,
    [
      "version: 2.0.1-selfhost.6",
      "files:",
      `  - url: ${path.basename(archive)}`,
      `    sha512: ${archiveSha512}`,
      `    size: ${archiveBytes.length}`,
      `path: ${path.basename(archive)}`,
      `sha512: ${archiveSha512}`,
      "",
    ].join("\n"),
  );

  const privateKeyBase64 = signingKeys.privateKey
    .export({ format: "der", type: "pkcs8" })
    .toString("base64");
  const wrongPrivateKeyBase64 = wrongKeys.privateKey
    .export({ format: "der", type: "pkcs8" })
    .toString("base64");
  writeKeychainProbe({ filePath: probe, privateKeyBase64 });
  replaceSecurityExecutableForTest(
    signerClosure,
    root,
    probe,
  );

  const signerArgs = [
    path.join(root, signerRelativePath),
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
    path.join(root, verifierRelativePath),
    "--arch",
    "arm64",
    "--version",
    "2.0.1-selfhost.6",
    "--archive",
    archive,
    "--metadata",
    metadata,
  ];
  const cleanEnvironment = () => {
    const env = {
      ...process.env,
      LOGSEQ_TEST_KEYCHAIN_TRACE: trace,
    };
    delete env[privateKeyEnvironmentName];
    delete env.CI;
    delete env.GITHUB_ACTIONS;
    return env;
  };

  return {
    archive,
    cleanEnvironment,
    metadata,
    policy,
    privateKeyBase64,
    probe,
    root,
    signerArgs,
    signatureOutput,
    trace,
    verifierArgs,
    wrongPrivateKeyBase64,
  };
};

const withFixture = async (test) => {
  const fixture = createIsolatedSignerFixture();
  try {
    await test(fixture);
  } finally {
    fs.rmSync(fixture.root, { force: true, recursive: true });
  }
};

const assertUnchangedUnsignedMetadata = (
  fixture,
  metadataBefore,
  label,
) => {
  assert.equal(
    fs.readFileSync(fixture.metadata, "utf8"),
    metadataBefore,
    `${label} modified updater metadata`,
  );
  assert.equal(
    fs.existsSync(fixture.signatureOutput),
    false,
    `${label} emitted a detached signature`,
  );
};

const assertKeychainLookup = (args, expectedKeyId) => {
  assert.equal(args[0], "find-generic-password");
  const serviceIndex = args.indexOf("-s");
  const accountIndex = args.indexOf("-a");
  assert.notEqual(serviceIndex, -1, "Keychain lookup must pin a service");
  assert.notEqual(accountIndex, -1, "Keychain lookup must pin an account");
  assert.match(
    args[serviceIndex + 1] ?? "",
    /logseq[\s\S]*(?:project|update)[\s\S]*(?:sign|key|ed25519)|(?:project|update)[\s\S]*logseq/i,
    "Keychain service must be scoped to Logseq project-update signing",
  );
  assert.equal(
    args[accountIndex + 1],
    expectedKeyId,
    "Keychain account must equal the fixed policy keyId",
  );
  assert.ok(
    args.includes("-w"),
    "Keychain lookup must request only the stored secret value",
  );
};

const workflowJobSource = (source, jobName) => {
  const match = source.match(
    new RegExp(
      `^  ${jobName}:\\n([\\s\\S]*?)(?=^  [a-zA-Z0-9_-]+:\\n|\\Z)`,
      "m",
    ),
  );
  assert.ok(match, `missing workflow job ${jobName}`);
  return match[0];
};

const assertSelfhostPublicReleaseBlocked = (workflow, jobName) => {
  const job = workflowJobSource(workflow, jobName);
  const publisherIndex = job.search(
    /uses:\s*(?:andelf\/nightly-release|softprops\/action-gh-release)@/i,
  );
  assert.notEqual(
    publisherIndex,
    -1,
    `${jobName} does not contain its public GitHub Release publisher`,
  );
  const beforePublisher = job.slice(0, publisherIndex);
  const skippedByJobOrStepCondition =
    /if:\s*\$\{\{[^\n]*(?:!\s*contains\([^)]*['"]-selfhost\.['"]|contains\([^)]*['"]-selfhost\.['"]\)\s*==\s*false)[^\n]*\}\}/i.test(
      beforePublisher,
    );
  const blockedByFailClosedGuard =
    /(?:if|case)\b[\s\S]{0,320}-selfhost\.[\s\S]{0,320}(?:exit\s+[1-9][0-9]*|(?:PUBLICATION|RELEASE)\s+BLOCKED)/i.test(
      beforePublisher,
    );
  assert.ok(
    skippedByJobOrStepCondition || blockedByFailClosedGuard,
    `${jobName} can publish selfhost stable/beta/nightly candidates from CI`,
  );
};

const discoverLocalFinalizer = () => {
  const packageJson = JSON.parse(read("package.json"));
  const candidates = Object.entries(packageJson.scripts ?? {}).filter(
    ([name]) =>
      /^project-update:(?=.*local)(?=.*finali[sz]).+$/i.test(name),
  );
  assert.equal(
    candidates.length,
    1,
    `expected one local project-update release finalizer, found ${
      candidates.map(([name]) => name).join(", ") || "none"
    }`,
  );
  const [scriptName, command] = candidates[0];
  const scriptPath = command.match(
    /\bnode\s+(?:\.\/)?(scripts\/[^\s"'`]+\.mjs)\b/,
  )?.[1];
  assert.ok(
    scriptPath,
    `${scriptName} must invoke one tracked Node finalizer under scripts/`,
  );
  return {
    command,
    scriptName,
    scriptPath,
    source: read(scriptPath),
  };
};

const cases = [];
const addCase = (name, test) => cases.push([name, test]);

addCase(
  "local signer reads a matching private key from macOS Keychain and pins policy keyId",
  () =>
    withFixture((fixture) => {
      const result = run(process.execPath, fixture.signerArgs, {
        cwd: fixture.root,
        env: fixture.cleanEnvironment(),
      });
      assert.equal(result.status, 0, result.output);
      assert.equal(fs.existsSync(fixture.trace), true);
      const keychainArgs = JSON.parse(fs.readFileSync(fixture.trace, "utf8"));
      assertKeychainLookup(keychainArgs, fixture.policy.keyId);
      assert.equal(
        keychainArgs.includes(fixture.privateKeyBase64),
        false,
        "private key appeared in Keychain command argv",
      );
      assert.equal(
        result.output.includes(fixture.privateKeyBase64),
        false,
        "private key appeared in signer logs",
      );
      for (const filePath of [
        fixture.archive,
        fixture.metadata,
        fixture.signatureOutput,
      ]) {
        assert.equal(
          fs.readFileSync(filePath).includes(fixture.privateKeyBase64),
          false,
          `private key appeared in ${path.basename(filePath)}`,
        );
      }
      const signedMetadata = fs.readFileSync(fixture.metadata, "utf8");
      assert.match(
        signedMetadata,
        new RegExp(
          `^  keyId: ${fixture.policy.keyId.replaceAll(":", "\\:")}$`,
          "m",
        ),
      );
      const verified = run(process.execPath, fixture.verifierArgs, {
        cwd: fixture.root,
      });
      assert.equal(verified.status, 0, verified.output);
    }),
);

for (const [label, status, key, errorPattern] of [
  [
    "missing Keychain item",
    44,
    "",
    /Keychain[\s\S]*(?:missing|not found|unavailable)|(?:missing|not found|unavailable)[\s\S]*Keychain/i,
  ],
  [
    "Keychain read failure",
    77,
    "",
    /Keychain[\s\S]*(?:failed|error|unavailable)|(?:failed|error|unavailable)[\s\S]*Keychain/i,
  ],
]) {
  addCase(`${label} fails closed before metadata mutation`, () =>
    withFixture((fixture) => {
      writeKeychainProbe({
        filePath: fixture.probe,
        privateKeyBase64: key,
        status,
      });
      const metadataBefore = fs.readFileSync(fixture.metadata, "utf8");
      const result = run(process.execPath, fixture.signerArgs, {
        cwd: fixture.root,
        env: fixture.cleanEnvironment(),
      });
      assert.notEqual(result.status, 0, `${label} was accepted`);
      assert.match(result.output, errorPattern, result.output);
      assertUnchangedUnsignedMetadata(fixture, metadataBefore, label);
    }),
  );
}

addCase("wrong Keychain private key is rejected against fixed policy", () =>
  withFixture((fixture) => {
    writeKeychainProbe({
      filePath: fixture.probe,
      privateKeyBase64: fixture.wrongPrivateKeyBase64,
    });
    const metadataBefore = fs.readFileSync(fixture.metadata, "utf8");
    const result = run(process.execPath, fixture.signerArgs, {
      cwd: fixture.root,
      env: fixture.cleanEnvironment(),
    });
    assert.notEqual(result.status, 0, "wrong Keychain key was accepted");
    assert.match(
      result.output,
      /private key does not match the fixed project update public key/i,
      result.output,
    );
    assert.equal(
      result.output.includes(fixture.wrongPrivateKeyBase64),
      false,
      "wrong private key appeared in signer logs",
    );
    assertUnchangedUnsignedMetadata(
      fixture,
      metadataBefore,
      "wrong Keychain key",
    );
  }),
);

for (const [label, environment, nodePrefix, errorPattern] of [
  [
    "CI",
    { CI: "true", GITHUB_ACTIONS: "true" },
    [],
    /local[\s-]*(?:macOS|release|sign)|(?:CI|GitHub Actions)[\s\S]*(?:blocked|forbidden|unsupported)/i,
  ],
  [
    "non-macOS",
    {},
    ["--require", "force-non-macos.cjs"],
    /macOS|darwin/i,
  ],
]) {
  addCase(`${label} signing misuse fails before Keychain access`, () =>
    withFixture((fixture) => {
      if (label === "non-macOS") {
        fs.writeFileSync(
          path.join(fixture.root, "force-non-macos.cjs"),
          'Object.defineProperty(process, "platform", { value: "linux" });\n',
        );
      }
      const metadataBefore = fs.readFileSync(fixture.metadata, "utf8");
      const env = { ...fixture.cleanEnvironment(), ...environment };
      fs.rmSync(fixture.trace, { force: true });
      const absoluteNodePrefix = nodePrefix.map((value) =>
        value.endsWith(".cjs") ? path.join(fixture.root, value) : value,
      );
      const result = run(
        process.execPath,
        [...absoluteNodePrefix, ...fixture.signerArgs],
        { cwd: fixture.root, env },
      );
      assert.notEqual(result.status, 0, `${label} signer misuse was accepted`);
      assert.match(result.output, errorPattern, result.output);
      assert.equal(
        fs.existsSync(fixture.trace),
        false,
        `${label} misuse reached Keychain`,
      );
      assertUnchangedUnsignedMetadata(fixture, metadataBefore, `${label} misuse`);
    }),
  );
}

addCase(
  "production signer is read-only toward Keychain and never accepts secret argv, env, or files",
  () => {
    const closure = relativeModuleClosure([signerRelativePath]);
    const locations = securityLookupLocations(closure);
    assert.equal(
      locations.length,
      1,
      `signer import closure must contain exactly one absolute /usr/bin/security lookup, found ${locations.length}`,
    );
    const source = [...closure]
      .map((relativePath) => read(relativePath))
      .join("\n");
    assert.match(source, /find-generic-password/);
    assert.doesNotMatch(
      source,
      new RegExp(privateKeyEnvironmentName),
      "production signer still accepts the private key through environment",
    );
    assert.doesNotMatch(
      source,
      /process\.env(?:\.[A-Z0-9_]*(?:PRIVATE|SECRET|SEED|PKCS8|SIGNING_KEY)|\[[^\]]*(?:PRIVATE|SECRET|SEED|PKCS8|SIGNING_KEY)[^\]]*\])/i,
      "production signer accepts private signing material through environment",
    );
    assert.doesNotMatch(
      source,
      /--(?:private[-_]?key|secret|seed|pkcs8|credential)\b/i,
      "production signer exposes a private-material argv option",
    );
    assert.doesNotMatch(
      source,
      /readFileSync\s*\([^)]*(?:private|secret|seed|pkcs8|credential)|createReadStream\s*\([^)]*(?:private|secret|seed|pkcs8|credential)/i,
      "production signer loads private material from a file",
    );
    assert.doesNotMatch(
      source,
      /\b(?:default-keychain|list-keychains|create-keychain|delete-keychain|add-generic-password|delete-generic-password|add-trusted-cert|remove-trusted-cert|trust-settings-export|trust-settings-import)\b/i,
      "signer may mutate Keychain, trust settings, defaults, or search lists",
    );
  },
);

addCase(
  "GitHub Actions never receives a project-update private key or signs macOS metadata",
  () => {
    const workflowDirectory = path.join(repoRoot, ".github", "workflows");
    for (const entry of fs.readdirSync(workflowDirectory)) {
      if (!/\.ya?ml$/i.test(entry)) continue;
      const source = fs.readFileSync(path.join(workflowDirectory, entry), "utf8");
      assert.doesNotMatch(
        source,
        new RegExp(privateKeyEnvironmentName),
        `${entry} references the project-update private key environment`,
      );
      assert.doesNotMatch(
        source,
        /secrets\.[A-Z][A-Z0-9_]*(?:ED25519|PROJECT_UPDATE)[A-Z0-9_]*(?:PRIVATE|SIGN|KEY)|secrets\.[A-Z][A-Z0-9_]*(?:PRIVATE|SIGN)[A-Z0-9_]*(?:ED25519|PROJECT_UPDATE|KEY)/i,
        `${entry} consumes a project-update signing secret`,
      );
      assert.doesNotMatch(
        source,
        /sign-macos-project-update\.mjs/,
        `${entry} signs project-update metadata inside GitHub Actions`,
      );
    }
  },
);

addCase(
  "CI may upload private unsigned candidates but cannot publish a selfhost GitHub Release",
  () => {
    const workflow = read(workflowRelativePath);
    for (const jobName of ["build-macos-x64", "build-macos-arm64"]) {
      const job = workflowJobSource(workflow, jobName);
      assert.match(
        job,
        /uses:\s*actions\/upload-artifact@/,
        `${jobName} no longer exposes its private candidate artifact`,
      );
      assert.doesNotMatch(
        job,
        /uses:\s*(?:andelf\/nightly-release|softprops\/action-gh-release)@/,
        `${jobName} directly publishes its candidate to a GitHub Release`,
      );
    }
    assertSelfhostPublicReleaseBlocked(workflow, "nightly-release");
    assertSelfhostPublicReleaseBlocked(workflow, "release");
  },
);

addCase(
  "local finalizer signs and verifies both macOS architectures before full asset validation",
  () => {
    const {
      command,
      scriptName,
      scriptPath,
      source,
    } = discoverLocalFinalizer();
    assert.match(
      `${command}\n${source}`,
      /(?:local|publisher|maintainer)/i,
      `${scriptName} is not identified as a publisher-local operation`,
    );
    assert.match(
      source,
      /process\.platform[\s\S]{0,160}(?:darwin|macOS)|(?:darwin|macOS)[\s\S]{0,160}process\.platform/i,
      `${scriptPath} does not fail closed outside macOS`,
    );
    assert.match(
      source,
      /(?:process\.env\.)?(?:CI|GITHUB_ACTIONS)[\s\S]{0,240}(?:throw|exit|blocked|forbidden)|(?:throw|exit|blocked|forbidden)[\s\S]{0,240}(?:CI|GITHUB_ACTIONS)/i,
      `${scriptPath} does not reject CI execution`,
    );
    assert.match(source, /sign-macos-project-update\.mjs/);
    assert.match(source, /verify-project-signed-macos-update\.mjs/);
    assert.match(source, /verify-desktop-release-assets\.mjs/);
    assert.match(
      source,
      /(?:\[\s*["']x64["']\s*,\s*["']arm64["']\s*\]|\[\s*["']arm64["']\s*,\s*["']x64["']\s*\])/,
      `${scriptPath} does not enumerate both x64 and arm64`,
    );
    assert.match(
      source,
      /(?:execFileSync|spawnSync|spawn)[\s\S]{0,400}(?:status|throw|reject|exit|check|assert)/i,
      `${scriptPath} does not make child verification failures fail closed`,
    );

    const signerReference = source.lastIndexOf(
      "sign-macos-project-update.mjs",
    );
    const verifierReference = source.lastIndexOf(
      "verify-project-signed-macos-update.mjs",
    );
    const fullAssetReference = source.lastIndexOf(
      "verify-desktop-release-assets.mjs",
    );
    assert.ok(
      signerReference < verifierReference &&
        verifierReference < fullAssetReference,
      `${scriptPath} must sign, cryptographically verify each architecture, then validate the complete asset set`,
    );

    const publicReleaseReference = source.search(
      /\bgh\s+release\b|action-gh-release|nightly-release/i,
    );
    if (publicReleaseReference !== -1) {
      assert.ok(
        fullAssetReference < publicReleaseReference,
        `${scriptPath} publishes before complete asset validation`,
      );
    }
    const workflowSource = fs
      .readdirSync(path.join(repoRoot, ".github", "workflows"))
      .filter((entry) => /\.ya?ml$/i.test(entry))
      .map((entry) => read(path.join(".github", "workflows", entry)))
      .join("\n");
    assert.equal(
      workflowSource.includes(path.basename(scriptPath)),
      false,
      "GitHub Actions invokes the publisher-local finalizer",
    );
  },
);

addCase(
  "tracked source and packaged release inputs contain no project-update private material",
  () => {
    const privateMaterialGate = run(process.execPath, [
      "scripts/test-updater-private-material-policy-contract.mjs",
    ]);
    assert.equal(
      privateMaterialGate.status,
      0,
      privateMaterialGate.output,
    );

    const suspiciousNames = run("git", [
      "ls-files",
      "-z",
      "--",
      ".github",
      "resources",
      "scripts",
      "static",
    ]).output
      .split("\0")
      .filter(Boolean)
      .filter(
        (file) =>
          /(?:project|update|release)[\s\S]*(?:private|secret|seed|pkcs8)|(?:private|secret|seed|pkcs8)[\s\S]*(?:project|update|release)/i.test(
            file,
          ) && !/^scripts\/test-/i.test(file),
      );
    assert.deepEqual(
      suspiciousNames,
      [],
      `suspicious project-update private-material files: ${suspiciousNames.join(", ")}`,
    );

    const policy = JSON.parse(read(productionPolicyRelativePath));
    assert.equal(
      Object.keys(policy).some((key) =>
        /private|secret|seed|pkcs8|credential|password|token/i.test(key),
      ),
      false,
      "public signing policy contains a private-material field",
    );
  },
);

addCase(
  "release documentation preserves user experience and legacy data compatibility",
  () => {
    const releaseNotes = read("docs/releases/2.0.1-selfhost.5.md");
    const guide = read("docs/selfhost-sync.md");
    const readme = read("README.md");
    const combined = `${releaseNotes}\n${guide}\n${readme}`;
    assert.match(
      combined,
      /(?:private key|私钥)[\s\S]{0,220}(?:publisher|release machine|maintainer|发布者|发布机器|维护者)[\s\S]{0,220}(?:macOS\s+)?Keychain|(?:macOS\s+)?Keychain[\s\S]{0,220}(?:private key|私钥)/i,
      "docs do not say that only the publisher's local macOS Keychain holds the private key",
    );
    assert.match(
      combined,
      /(?:private key|私钥)[\s\S]{0,320}(?:(?:never|does not|will not|must not|is not|outside|out of|external to|不(?:会|得|上传|在))[\s\S]{0,180}(?:GitHub(?: Actions)?|repository secrets?)|(?:GitHub(?: Actions)?|repository secrets?)[\s\S]{0,180}(?:never|does not|will not|must not|outside|out of|external to|不(?:会|得|上传|在)))|(?:GitHub(?: Actions)?|repository secrets?)[\s\S]{0,320}(?:private key|私钥)[\s\S]{0,180}(?:never|does not|will not|must not|outside|out of|external to|不(?:会|得|上传|在))/i,
      "docs do not prohibit uploading the project private key to GitHub",
    );
    assert.match(
      combined,
      /(?:users?|clients?|用户|客户端)[\s\S]{0,260}(?:do not|never|no need|无需|不需要)[\s\S]{0,180}(?:Keychain|private key|私钥|password|密码)/i,
      "docs do not make clear that users never handle the publisher signing key",
    );
    assert.match(
      combined,
      /\.5[\s\S]{0,500}(?:\.6\+?|later|future|后续)|(?:\.6\+?|later|future|后续)[\s\S]{0,500}\.5/i,
      "docs do not identify the .5 -> .6+ update transition",
    );
    assert.match(
      combined,
      /(?:automatic(?:ally)?|自动)[\s\S]{0,220}(?:check|检查)[\s\S]{0,220}(?:download|下载)|(?:check|检查)[\s\S]{0,220}(?:download|下载)[\s\S]{0,220}(?:automatic(?:ally)?|自动)/i,
      "docs do not preserve automatic update checks and downloads",
    );
    assert.match(
      combined,
      /(?:click|select|choose|点击|选择)[\s\S]{0,120}Restart and install|Restart and install[\s\S]{0,120}(?:click|select|choose|点击|选择)/i,
      "docs do not preserve the Restart and install interaction",
    );
    assert.match(
      combined,
      /(?:2\.0\.1-selfhost\.)?(?:1|\.1)[\s\S]{0,100}(?:2\.0\.1-selfhost\.)?(?:4|\.4)[\s\S]{0,320}(?:data|graph|sync|RTC|数据|图谱|同步)[\s\S]{0,180}(?:compatible|unchanged|unaffected|兼容|不受影响|保持不变)/i,
      "docs do not state that .1-.4 data and sync compatibility is unaffected by release signing",
    );
  },
);

addCase("formal desktop release contracts execute this Keychain gate", () => {
  const packageJson = JSON.parse(read("package.json"));
  assert.equal(
    packageJson.scripts?.["project-update:test-local-keychain-contract"],
    "node ./scripts/test-local-keychain-release-signing-contract.mjs",
  );
  assert.match(
    packageJson.scripts?.["desktop:test-release-contracts"] ?? "",
    /test-local-keychain-release-signing-contract\.mjs/,
    "formal desktop release contracts omit the local-Keychain signing gate",
  );
});

let passed = 0;
let failed = 0;
for (const [name, test] of cases) {
  try {
    await test();
    passed += 1;
    console.log(`[local-keychain-signing-contract] PASS ${name}`);
  } catch (error) {
    failed += 1;
    console.error(
      `[local-keychain-signing-contract] FAIL ${name}: ${
        error instanceof Error ? error.message : error
      }`,
    );
  }
}

console.log(
  `[local-keychain-signing-contract] SUMMARY ${passed} passed, ${failed} failed`,
);
if (failed > 0) process.exitCode = 1;
