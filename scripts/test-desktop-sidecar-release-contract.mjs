#!/usr/bin/env node

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const workflowPath = path.join(
  repoRoot,
  ".github",
  "workflows",
  "build-desktop-release.yml",
);
const verifierPath = path.join(
  repoRoot,
  "resources",
  "verify-packaged-desktop.mjs",
);
const preflightPath = path.join(
  repoRoot,
  "scripts",
  "run-desktop-release-preflight.mjs",
);
const version = "2.0.1-selfhost.5";
const electronVersion = "42.4.1";

const cases = [];
const test = (name, callback) => cases.push([name, callback]);

const run = (executable, args, options = {}) => {
  const result = spawnSync(executable, args, {
    cwd: options.cwd ?? repoRoot,
    encoding: "utf8",
    env: { ...process.env, ...options.env },
    stdio: ["ignore", "pipe", "pipe"],
  });
  if (result.error) throw result.error;
  return {
    output: `${result.stdout ?? ""}${result.stderr ?? ""}`.trim(),
    status: result.status,
  };
};

const executableHeader = (platform, arch) => {
  if (platform === "darwin") {
    const payload = Buffer.alloc(64);
    payload.writeUInt32BE(0xcffaedfe, 0);
    payload.writeUInt32LE(
      arch === "arm64" ? 0x0100000c : 0x01000007,
      4,
    );
    return payload;
  }
  if (platform === "linux") {
    const payload = Buffer.alloc(64);
    payload.set([0x7f, 0x45, 0x4c, 0x46], 0);
    payload[5] = 1;
    payload.writeUInt16LE(arch === "arm64" ? 183 : 62, 18);
    return payload;
  }
  const payload = Buffer.alloc(256);
  payload.set([0x4d, 0x5a], 0);
  payload.writeUInt32LE(0x80, 0x3c);
  payload.set([0x50, 0x45, 0, 0], 0x80);
  payload.writeUInt16LE(arch === "arm64" ? 0xaa64 : 0x8664, 0x84);
  return payload;
};

const writeExecutable = (filePath, platform, arch) => {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, executableHeader(platform, arch));
  fs.chmodSync(filePath, 0o755);
};

const writeMainExecutable = (filePath, platform, arch) => {
  if (
    platform !== "darwin" ||
    process.platform !== "darwin" ||
    arch !== process.arch
  ) {
    writeExecutable(filePath, platform, arch);
    return;
  }
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  const source = `${filePath}.c`;
  fs.writeFileSync(
    source,
    `#include <stdio.h>\nint main(void) { fputs("${electronVersion}", stdout); return 0; }\n`,
  );
  try {
    const result = run("xcrun", [
      "clang",
      "-arch",
      arch === "arm64" ? "arm64" : "x86_64",
      "-Os",
      source,
      "-o",
      filePath,
    ]);
    assert.equal(result.status, 0, result.output);
  } finally {
    fs.rmSync(source, { force: true });
  }
};

const fixtureLayout = (root, platform, arch) => {
  if (platform === "darwin") {
    const appRoot = path.join(root, "dist", "mac", "Logseq.app");
    return {
      appRoot,
      mainExecutable: path.join(appRoot, "Contents", "MacOS", "Logseq"),
      resourcesDir: path.join(appRoot, "Contents", "Resources"),
    };
  }
  const appRoot = path.join(root, "dist", `${platform}-unpacked`);
  return {
    appRoot,
    mainExecutable: path.join(
      appRoot,
      platform === "win32" ? "Logseq.exe" : "logseq",
    ),
    resourcesDir: path.join(appRoot, "resources"),
  };
};

const helperNames = [
  "project-update-helper",
  "project-signed-update-helper",
  "project-signed-macos-update-helper",
  "logseq-project-update-helper",
];
const policyRelativePaths = [
  path.join("updater", "project-signing-policy.json"),
  path.join("sidecar", "project-signing-policy.json"),
];
const runtimeRelativePaths = [
  path.join("sidecar", "run-project-signed-macos-update.mjs"),
  path.join("updater", "run-project-signed-macos-update.mjs"),
  path.join("scripts", "run-project-signed-macos-update.mjs"),
];

const createAsarShim = (root) => {
  const moduleDir = path.join(root, "node_modules", "@electron", "asar");
  fs.mkdirSync(moduleDir, { recursive: true });
  fs.writeFileSync(
    path.join(moduleDir, "index.js"),
    [
      '"use strict";',
      'const fs = require("node:fs");',
      "exports.extractFile = (archive, entry) => {",
      '  if (entry !== "package.json") throw new Error(`unexpected asar entry: ${entry}`);',
      "  return fs.readFileSync(archive);",
      "};",
      "",
    ].join("\n"),
  );
  return path.join(root, "node_modules");
};

const signingPolicy = ({
  keyId,
  publicKey = Buffer.alloc(32, 0x2a),
} = {}) => {
  const digest = createHash("sha256").update(publicKey).digest("hex");
  return JSON.stringify(
    {
      algorithm: "Ed25519",
      bundleId: "com.logseq.logseq",
      keyId: keyId ?? `ed25519:${digest}`,
      payloadDomain: "logseq-selfhost-project-update-signature-v1",
      publicKeyBase64: publicKey.toString("base64"),
      schema: "logseq-selfhost-project-update-signature-v1",
    },
    null,
    2,
  );
};

const createPackagedFixture = ({ arch, platform, root }) => {
  const layout = fixtureLayout(root, platform, arch);
  writeMainExecutable(layout.mainExecutable, platform, arch);
  fs.mkdirSync(layout.resourcesDir, { recursive: true });
  fs.writeFileSync(
    path.join(layout.resourcesDir, "app.asar"),
    JSON.stringify({ main: "electron.js", version }),
  );
  writeExecutable(
    path.join(
      layout.resourcesDir,
      "app.asar.unpacked",
      "node_modules",
      "keytar",
      "build",
      "Release",
      "keytar.node",
    ),
    platform,
    arch,
  );
  fs.writeFileSync(
    path.join(layout.resourcesDir, "app-update.yml"),
    "provider: github\nowner: cfenglv\nrepo: logseq\n",
  );

  const sidecarDir = path.join(layout.resourcesDir, "sidecar");
  fs.mkdirSync(sidecarDir, { recursive: true });
  fs.writeFileSync(
    path.join(sidecarDir, "embedding_server.py"),
    "print('embedding sidecar fixture')\n",
  );
  for (const helperName of helperNames) {
    writeExecutable(path.join(sidecarDir, helperName), "darwin", arch);
  }
  for (const relativePath of policyRelativePaths) {
    const filePath = path.join(layout.resourcesDir, relativePath);
    fs.mkdirSync(path.dirname(filePath), { recursive: true });
    fs.writeFileSync(filePath, signingPolicy());
  }
  for (const relativePath of runtimeRelativePaths) {
    const filePath = path.join(layout.resourcesDir, relativePath);
    fs.mkdirSync(path.dirname(filePath), { recursive: true });
    fs.writeFileSync(filePath, "#!/usr/bin/env node\nprocess.exit(0);\n");
    fs.chmodSync(filePath, 0o755);
  }
  return layout;
};

const removeAll = (root, relativePaths) => {
  for (const relativePath of relativePaths) {
    fs.rmSync(path.join(root, relativePath), {
      force: true,
      recursive: true,
    });
  }
};

const overwritePolicies = (resourcesDir, policy) => {
  for (const relativePath of policyRelativePaths) {
    fs.writeFileSync(path.join(resourcesDir, relativePath), policy);
  }
};

const verifierInvocation = ({ arch, asarNodePath, platform, searchRoot }) =>
  run(
    process.execPath,
    [
      verifierPath,
      "--search-root",
      searchRoot,
      "--platform",
      platform,
      "--arch",
      arch,
      "--version",
      version,
      "--electron-version",
      electronVersion,
    ],
    { env: { NODE_PATH: asarNodePath } },
  );

const withPackagedFixture = ({ arch, platform }, callback) => {
  const root = fs.mkdtempSync(
    path.join(os.tmpdir(), `logseq-packaged-${platform}-${arch}-`),
  );
  try {
    const asarNodePath = createAsarShim(path.join(root, "asar-shim"));
    const layout = createPackagedFixture({ arch, platform, root });
    return callback({
      arch,
      asarNodePath,
      layout,
      platform,
      root,
      searchRoot: path.join(root, "dist"),
    });
  } finally {
    fs.rmSync(root, { force: true, recursive: true });
  }
};

const expectVerifierFailure = (fixture, label) => {
  const result = verifierInvocation(fixture);
  assert.notEqual(
    result.status,
    0,
    `${label} unexpectedly passed packaged verification:\n${result.output}`,
  );
};

const workflowJob = (workflow, jobName) => {
  const match = workflow.match(
    new RegExp(
      `^  ${jobName}:\\n([\\s\\S]*?)(?=^  [a-zA-Z0-9_-]+:\\n|(?![\\s\\S]))`,
      "m",
    ),
  );
  assert.ok(match, `workflow job ${jobName} is missing`);
  return match[1];
};

test("resource preparation stages the repository sidecar inside static", () => {
  const gulpfile = fs.readFileSync(path.join(repoRoot, "gulpfile.js"), "utf8");
  assert.match(
    gulpfile,
    /(?:__dirname|repoRoot)[\s\S]{0,120}sidecar/,
    "resource preparation does not declare the repository sidecar source",
  );
  assert.match(
    gulpfile,
    /(?:outputPath|static)[\s\S]{0,120}sidecar/,
    "resource preparation does not stage the sidecar under static/sidecar",
  );
  const buildDefinition = gulpfile.match(
    /exports\.build\s*=\s*gulp\.series\(([\s\S]*?)\)\s*(?:\n|$)/,
  )?.[1];
  assert.ok(buildDefinition, "gulp build pipeline is missing");
  assert.match(
    buildDefinition,
    /sidecar/i,
    "gulp build does not include the sidecar staging operation",
  );

  const builder = fs.readFileSync(
    path.join(repoRoot, "resources", "electron-builder.yml"),
    "utf8",
  );
  assert.match(
    builder,
    /extraResources:[\s\S]*?from:\s*(?:\.\/)?sidecar\s*[\r\n]+[\s\S]{0,80}?to:\s*sidecar\b/,
    "electron-builder does not consume static/sidecar relative to its static working directory",
  );
  assert.doesNotMatch(
    builder,
    /from:\s*\.\.\/sidecar\b/,
    "electron-builder still depends on a job-root sidecar absent from the compile artifact",
  );
});

test("macOS x64 and arm64 jobs append the helper to static/sidecar", () => {
  const workflow = fs.readFileSync(workflowPath, "utf8");
  for (const [jobName, arch] of [
    ["build-macos-x64", "x64"],
    ["build-macos-arm64", "arm64"],
  ]) {
    const job = workflowJob(workflow, jobName);
    assert.match(
      job,
      /static\/sidecar\/embedding_server\.py/,
      `${jobName} does not prove the downloaded compile artifact retained embedding_server.py`,
    );
    assert.match(
      job,
      /(?:--output[\s\S]{0,160}static\/sidecar|static\/sidecar[\s\S]{0,160}--output)/,
      `${jobName} does not emit the native helper into static/sidecar`,
    );
    assert.match(
      job,
      new RegExp(`(?:--arch\\s+${arch}\\b|--${arch}\\b)`),
      `${jobName} does not build the native helper for ${arch}`,
    );
    assert.match(
      job,
      /embedding_server\.py[\s\S]{0,1600}(?:update-helper|helper[\s\S]{0,80}--output)|(?:update-helper|helper[\s\S]{0,80}--output)[\s\S]{0,1600}embedding_server\.py/i,
      `${jobName} does not preserve embedding_server.py while generating the helper`,
    );
    assert.doesNotMatch(
      job,
      /rm\s+-rf\s+(?:\.\/)?static\/sidecar\b/,
      `${jobName} deletes the compile artifact sidecar before adding the helper`,
    );
  }
});

for (const platform of ["linux", "win32", "darwin"]) {
  for (const arch of ["x64", "arm64"]) {
    test(`${platform}/${arch} packaged verifier accepts a complete sidecar fixture`, () =>
    withPackagedFixture({ arch, platform }, (fixture) => {
      const result = verifierInvocation(fixture);
      assert.equal(result.status, 0, result.output);
    }),
    );
    test(`${platform}/${arch} packaged verifier rejects missing embedding_server.py`, () =>
    withPackagedFixture({ arch, platform }, (fixture) => {
      fs.rmSync(
        path.join(
          fixture.layout.resourcesDir,
          "sidecar",
          "embedding_server.py",
        ),
      );
      expectVerifierFailure(
        fixture,
        `${platform}/${arch} missing embedding_server.py`,
      );
    }),
    );
  }
}

test("darwin verifier rejects a missing native helper", () =>
  withPackagedFixture(
    {
      arch: process.arch === "arm64" ? "x64" : "arm64",
      platform: "darwin",
    },
    (fixture) => {
      removeAll(
        path.join(fixture.layout.resourcesDir, "sidecar"),
        helperNames,
      );
      expectVerifierFailure(fixture, "darwin missing native helper");
    },
  ));

test("darwin verifier rejects a helper that is not a regular executable", () =>
  withPackagedFixture(
    {
      arch: process.arch === "arm64" ? "x64" : "arm64",
      platform: "darwin",
    },
    (fixture) => {
      const sidecarDir = path.join(fixture.layout.resourcesDir, "sidecar");
      for (const helperName of helperNames) {
        const helperPath = path.join(sidecarDir, helperName);
        fs.rmSync(helperPath);
        fs.mkdirSync(helperPath);
      }
      expectVerifierFailure(fixture, "darwin non-regular native helper");
    },
  ));

test("darwin verifier rejects a non-executable native helper", () =>
  withPackagedFixture(
    {
      arch: process.arch === "arm64" ? "x64" : "arm64",
      platform: "darwin",
    },
    (fixture) => {
      const sidecarDir = path.join(fixture.layout.resourcesDir, "sidecar");
      for (const helperName of helperNames) {
        fs.chmodSync(path.join(sidecarDir, helperName), 0o644);
      }
      expectVerifierFailure(fixture, "darwin non-executable native helper");
    },
  ));

test("darwin verifier rejects a native helper for the wrong Mach-O architecture", () =>
  withPackagedFixture(
    {
      arch: process.arch === "arm64" ? "x64" : "arm64",
      platform: "darwin",
    },
    (fixture) => {
      const wrongArch = fixture.arch === "arm64" ? "x64" : "arm64";
      const sidecarDir = path.join(fixture.layout.resourcesDir, "sidecar");
      for (const helperName of helperNames) {
        writeExecutable(
          path.join(sidecarDir, helperName),
          "darwin",
          wrongArch,
        );
      }
      expectVerifierFailure(fixture, "darwin wrong-architecture native helper");
    },
  ));

test("darwin verifier rejects missing project signing policy", () =>
  withPackagedFixture(
    {
      arch: process.arch === "arm64" ? "x64" : "arm64",
      platform: "darwin",
    },
    (fixture) => {
      removeAll(fixture.layout.resourcesDir, policyRelativePaths);
      expectVerifierFailure(fixture, "darwin missing project signing policy");
    },
  ));

for (const [label, keyId] of [
  ["16-hex truncated keyId", `ed25519:${"a".repeat(16)}`],
  ["32-hex truncated keyId", `ed25519:${"a".repeat(32)}`],
  ["wrong full keyId", `ed25519:${"b".repeat(64)}`],
]) {
  test(`darwin verifier rejects ${label}`, () =>
    withPackagedFixture(
      {
        arch: process.arch === "arm64" ? "x64" : "arm64",
        platform: "darwin",
      },
      (fixture) => {
        overwritePolicies(
          fixture.layout.resourcesDir,
          signingPolicy({ keyId }),
        );
        expectVerifierFailure(fixture, `darwin ${label}`);
      },
    ));
}

test("darwin verifier rejects an unrelated key claiming the accepted short prefix", () =>
  withPackagedFixture(
    {
      arch: process.arch === "arm64" ? "x64" : "arm64",
      platform: "darwin",
    },
    (fixture) => {
      const acceptedPublicKey = Buffer.alloc(32, 0x2a);
      const acceptedDigest = createHash("sha256")
        .update(acceptedPublicKey)
        .digest("hex");
      overwritePolicies(
        fixture.layout.resourcesDir,
        signingPolicy({
          keyId: `ed25519:${acceptedDigest.slice(0, 16)}`,
          publicKey: Buffer.alloc(32, 0x7e),
        }),
      );
      expectVerifierFailure(
        fixture,
        "darwin unrelated key with claimed short prefix",
      );
    },
  ));

test("darwin verifier rejects missing project update runtime", () =>
  withPackagedFixture(
    {
      arch: process.arch === "arm64" ? "x64" : "arm64",
      platform: "darwin",
    },
    (fixture) => {
      removeAll(fixture.layout.resourcesDir, runtimeRelativePaths);
      expectVerifierFailure(fixture, "darwin missing project update runtime");
    },
  ));

test("full local preflight builds a test-only host helper and guarantees cleanup", () => {
  const source = fs.readFileSync(preflightPath, "utf8");
  assert.match(
    source,
    /build[\w./-]*update[\w./-]*helper/i,
    "full preflight never invokes the native helper builder",
  );
  assert.match(
    source,
    /--test-only\b/,
    "full preflight does not explicitly request a test-only helper",
  );
  assert.match(
    source,
    /--public-key-base64\b|--test-only-public-key\b|--test-public-key(?:-path|-base64)?\b/,
    "full preflight does not provide an isolated test public key",
  );
  assert.match(
    source,
    /process\.arch/,
    "full preflight does not build the helper for the host architecture",
  );
  assert.match(
    source,
    /finally\s*\{[\s\S]{0,1800}(?:rmSync|unlinkSync)/,
    "full preflight does not guarantee helper cleanup after failures",
  );
});

test("formal macOS release jobs cannot use a test key or UNCONFIGURED policy", () => {
  const workflow = fs.readFileSync(workflowPath, "utf8");
  for (const jobName of ["build-macos-x64", "build-macos-arm64"]) {
    const job = workflowJob(workflow, jobName);
    assert.doesNotMatch(
      job,
      /--test-only\b|--test(?:-only)?-public-key|--public-key-base64\b/,
      `${jobName} injects a test-only updater key`,
    );
    assert.match(
      job,
      /project-signing-policy|verify-packaged-desktop|electron:verify-package/,
      `${jobName} has no fail-closed production policy/package gate`,
    );
  }
  const policyPath = path.join(
    repoRoot,
    "resources",
    "updater",
    "project-signing-policy.json",
  );
  assert.equal(fs.existsSync(policyPath), true, "production policy is missing");
  assert.doesNotMatch(
    fs.readFileSync(policyPath, "utf8"),
    /\bUNCONFIGURED\b/i,
    "formal release policy is UNCONFIGURED",
  );
});

let passed = 0;
let failed = 0;
for (const [name, callback] of cases) {
  try {
    await callback();
    passed += 1;
    console.log(`[desktop-sidecar-contract] PASS ${name}`);
  } catch (error) {
    failed += 1;
    console.error(
      `[desktop-sidecar-contract] FAIL ${name}: ${
        error instanceof Error ? error.stack || error.message : error
      }`,
    );
  }
}

console.log(
  `[desktop-sidecar-contract] SUMMARY passed=${passed} failed=${failed} total=${cases.length}`,
);
if (failed > 0) process.exitCode = 1;
