#!/usr/bin/env node

import assert from "node:assert/strict";
import { createHash, createPublicKey } from "node:crypto";
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
const runtimePreparationPath = path.join(
  repoRoot,
  "scripts",
  "prepare-desktop-runtime-js.mjs",
);
const preflightPath = path.join(
  repoRoot,
  "scripts",
  "run-desktop-release-preflight.mjs",
);
const version = "2.0.1-selfhost.5";
const electronVersion = "42.4.1";
const verifierSource = fs.readFileSync(verifierPath, "utf8");

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
    platform !== process.platform ||
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
    const [compiler, args] =
      platform === "darwin"
        ? [
            "xcrun",
            [
              "clang",
              "-arch",
              arch === "arm64" ? "arm64" : "x86_64",
              "-Os",
              source,
              "-o",
              filePath,
            ],
          ]
        : ["cc", ["-Os", source, "-o", filePath]];
    const result = run(compiler, args);
    assert.equal(result.status, 0, result.output);
    fs.chmodSync(filePath, 0o755);
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

const sourceTokens = verifierSource.match(
  /[A-Za-z0-9_.-]+(?:\/[A-Za-z0-9_.-]+)*/g,
) ?? [];
const repositoryResourcePaths = [];
const collectResourcePaths = (directory, relative = "") => {
  if (!fs.existsSync(directory)) return;
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const entryRelativePath = path.join(relative, entry.name);
    if (entry.isDirectory()) {
      collectResourcePaths(path.join(directory, entry.name), entryRelativePath);
    } else {
      repositoryResourcePaths.push(entryRelativePath);
    }
  }
};
collectResourcePaths(path.join(repoRoot, "resources"));

const unique = (values) => [...new Set(values)];
const resourceVariants = (tokens, predicate) =>
  unique(
    tokens.flatMap((token) => {
      const normalized = token.replaceAll("/", path.sep);
      const basename = path.basename(normalized);
      if (!predicate(basename)) return [];
      if (
        normalized.includes(path.sep) &&
        ["scripts", "sidecar", "updater"].includes(
          normalized.split(path.sep)[0],
        )
      ) {
        return [normalized];
      }
      return [
        basename,
        ...["scripts", "sidecar", "updater"].map((directory) =>
          path.join(directory, basename)
        ),
      ];
    }),
  );

const collectRelativeModuleClosure = (
  relativePath,
  seen = new Set(),
) => {
  const normalized = path.normalize(relativePath);
  if (seen.has(normalized)) return seen;
  seen.add(normalized);
  const sourcePath = path.join(repoRoot, "resources", normalized);
  if (!fs.existsSync(sourcePath)) return seen;
  const source = fs.readFileSync(sourcePath, "utf8");
  for (const match of source.matchAll(
    /(?:from\s+|import\s*\(\s*)["'](\.[^"']+\.(?:c?js|mjs))["']/g,
  )) {
    const dependency = path.normalize(
      path.join(path.dirname(normalized), match[1]),
    );
    collectRelativeModuleClosure(dependency, seen);
  }
  return seen;
};
const verifierDependencyRelativePaths = unique([
  "packaged-resource-contract.mjs",
  ...collectRelativeModuleClosure("verify-packaged-desktop.mjs"),
]).filter(
  (relativePath) => relativePath !== "verify-packaged-desktop.mjs",
);

const helperNames = unique([
  "project-update-helper",
  "project-signed-update-helper",
  "project-signed-macos-update-helper",
  "logseq-project-update-helper",
  ...[...sourceTokens, ...repositoryResourcePaths]
    .map((token) => path.basename(token))
    .filter(
      (basename) =>
        !path.extname(basename) &&
        (
          /(?:project|signed).*(?:update|helper|installer)/i.test(basename) ||
          /(?:update|helper|installer).*(?:project|signed)/i.test(basename)
        ),
    ),
]);
const policyRelativePaths = unique([
  path.join("updater", "project-signing-policy.json"),
  path.join("sidecar", "project-signing-policy.json"),
  ...resourceVariants(
    [...sourceTokens, ...repositoryResourcePaths],
    (basename) =>
      basename.endsWith(".json") &&
      /(?:project|update|sign).*(?:policy|key)|policy.*(?:project|update|sign)/i.test(
        basename,
      ),
  ),
]);
const runtimeRelativePaths = unique([
  ...verifierDependencyRelativePaths,
  path.join("sidecar", "run-project-signed-macos-update.mjs"),
  path.join("updater", "run-project-signed-macos-update.mjs"),
  path.join("scripts", "run-project-signed-macos-update.mjs"),
  ...resourceVariants(
    [...sourceTokens, ...repositoryResourcePaths],
    (basename) =>
      /\.(?:c?js|mjs)$/i.test(basename) &&
      /(?:project.*update|update.*project|signed.*update|update.*signed|signature)/i.test(
        basename,
      ),
  ),
]);
const trackRuntimeRelativePaths = unique(
  runtimeRelativePaths.filter(
    (relativePath) =>
      path.basename(relativePath) === "project-updater-signature.mjs",
  ),
);
for (const directory of ["", "scripts", "sidecar", "updater"]) {
  const relativePath = path.join(
    directory,
    "project-updater-signature.mjs",
  );
  if (!runtimeRelativePaths.includes(relativePath)) {
    runtimeRelativePaths.push(relativePath);
  }
  if (!trackRuntimeRelativePaths.includes(relativePath)) {
    trackRuntimeRelativePaths.push(relativePath);
  }
}

const productionPolicyPath = policyRelativePaths
  .map((relativePath) => path.join(repoRoot, "resources", relativePath))
  .find((candidate) => fs.existsSync(candidate));
const policyTemplate = productionPolicyPath
  ? JSON.parse(fs.readFileSync(productionPolicyPath, "utf8"))
  : {
      algorithm: "Ed25519",
      bundleId: "com.logseq.logseq",
      keyId: "UNCONFIGURED",
      payloadDomain: "logseq-selfhost-project-update-signature-v1",
      publicKeyBase64: "UNCONFIGURED",
      schema: "logseq-selfhost-project-update-signature-v1",
    };
const inlinePolicyPublicKey = [
  policyTemplate.publicKeyBase64,
  policyTemplate.publicKeyRawBase64,
  policyTemplate.ed25519PublicKeyBase64,
  typeof policyTemplate.publicKey === "string"
    ? policyTemplate.publicKey
    : null,
  policyTemplate.publicKey?.encoding === "base64"
    ? policyTemplate.publicKey.value
    : null,
].find((value) => typeof value === "string" && !/UNCONFIGURED/i.test(value));
const policyPublicKeyPath =
  productionPolicyPath && typeof policyTemplate.publicKeyPath === "string"
    ? path.resolve(
        path.dirname(productionPolicyPath),
        policyTemplate.publicKeyPath,
      )
    : null;
const fixturePublicKey = inlinePolicyPublicKey
  ? Buffer.from(inlinePolicyPublicKey, "base64")
  : policyPublicKeyPath && fs.existsSync(policyPublicKeyPath)
    ? Buffer.from(
        createPublicKey(fs.readFileSync(policyPublicKeyPath))
          .export({ format: "jwk" }).x,
        "base64url",
      )
    : Buffer.alloc(32, 0x2a);
assert.equal(
  fixturePublicKey.length,
  32,
  "production project policy must resolve to a 32-byte Ed25519 public key",
);
const policyCompanionRelativePaths =
  typeof policyTemplate.publicKeyPath === "string"
    ? unique(
        policyRelativePaths.map((relativePolicyPath) => {
          const relativePath = path.normalize(
            path.join(
              path.dirname(relativePolicyPath),
              policyTemplate.publicKeyPath,
            ),
          );
          assert.doesNotMatch(
            relativePath,
            /^(?:\.\.(?:\/|\\|$))/,
            "fixture policy public key escapes packaged resources",
          );
          return relativePath;
        }),
      )
    : [];

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
  publicKey = fixturePublicKey,
} = {}) => {
  const digest = createHash("sha256").update(publicKey).digest("hex");
  const publicKeyPem = createPublicKey({
    format: "jwk",
    key: {
      crv: "Ed25519",
      kty: "OKP",
      x: publicKey.toString("base64url"),
    },
  }).export({ format: "pem", type: "spki" });
  const policy = structuredClone(policyTemplate);
  const normalizedKeyIdFields = new Set([
    "ed25519publickeyid",
    "keyid",
    "publickeyid",
  ]);
  const normalizedPublicKeyFields = new Set([
    "ed25519publickeybase64",
    "publickeybase64",
    "publickeyrawbase64",
  ]);
  let keyFields = 0;
  let publicKeyFields = 0;
  const configure = (value) => {
    if (!value || typeof value !== "object" || Array.isArray(value)) return;
    for (const [field, child] of Object.entries(value)) {
      const normalized = field.toLowerCase().replace(/[^a-z0-9]/g, "");
      if (normalizedKeyIdFields.has(normalized)) {
        value[field] = keyId ?? `ed25519:${digest}`;
        keyFields += 1;
      } else if (normalizedPublicKeyFields.has(normalized)) {
        value[field] = publicKey.toString("base64");
        publicKeyFields += 1;
      } else if (
        normalized === "publickey" &&
        typeof child === "string"
      ) {
        value[field] = publicKey.toString("base64");
        publicKeyFields += 1;
      } else if (
        normalized === "publickey" &&
        child &&
        typeof child === "object" &&
        child.encoding === "base64" &&
        typeof child.value === "string"
      ) {
        child.value = publicKey.toString("base64");
        publicKeyFields += 1;
      }
      configure(value[field]);
    }
  };
  configure(policy);
  const updateHashes = (value) => {
    if (!value || typeof value !== "object" || Array.isArray(value)) return;
    for (const [field, child] of Object.entries(value)) {
      const normalized = field.toLowerCase().replace(/[^a-z0-9]/g, "");
      if (normalized === "publickeyrawsha256") {
        value[field] = digest;
      } else if (normalized === "publickeysha256") {
        value[field] = policyTemplate.publicKeyPath
          ? createHash("sha256").update(publicKeyPem).digest("hex")
          : digest;
      }
      updateHashes(value[field]);
    }
  };
  updateHashes(policy);
  if (keyFields === 0) {
    policy.keyId = keyId ?? `ed25519:${digest}`;
  }
  if (publicKeyFields === 0 && !("publicKeyPath" in policy)) {
    policy.publicKeyBase64 = publicKey.toString("base64");
  }
  if (/\bUNCONFIGURED\b/i.test(JSON.stringify(policy))) {
    delete policy.status;
    Object.assign(policy, {
      algorithm: "Ed25519",
      bundleId: "com.logseq.logseq",
      keyId: keyId ?? `ed25519:${digest}`,
      payloadDomain: "logseq-selfhost-project-update-signature-v1",
      publicKeyBase64: publicKey.toString("base64"),
      schema: "logseq-selfhost-project-update-signature-v1",
    });
  }
  return JSON.stringify(policy, null, 2);
};

const writePolicyCompanion = (
  resourcesDir,
  relativePolicyPath,
  publicKey = fixturePublicKey,
) => {
  if (typeof policyTemplate.publicKeyPath !== "string") return;
  const policyDirectory = path.dirname(
    path.join(resourcesDir, relativePolicyPath),
  );
  const destination = path.resolve(
    policyDirectory,
    policyTemplate.publicKeyPath,
  );
  assert.equal(
    destination.startsWith(`${resourcesDir}${path.sep}`),
    true,
    "fixture policy public key path escapes packaged resources",
  );
  const publicKeyPem = createPublicKey({
    format: "jwk",
    key: {
      crv: "Ed25519",
      kty: "OKP",
      x: publicKey.toString("base64url"),
    },
  }).export({ format: "pem", type: "spki" });
  fs.mkdirSync(path.dirname(destination), { recursive: true });
  fs.writeFileSync(destination, publicKeyPem);
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
    writePolicyCompanion(layout.resourcesDir, relativePath);
  }
  for (const relativePath of runtimeRelativePaths) {
    const filePath = path.join(layout.resourcesDir, relativePath);
    fs.mkdirSync(path.dirname(filePath), { recursive: true });
    const repositoryRuntime = path.join(
      repoRoot,
      "resources",
      relativePath,
    );
    fs.writeFileSync(
      filePath,
      fs.existsSync(repositoryRuntime)
        ? fs.readFileSync(repositoryRuntime)
        : "#!/usr/bin/env node\nprocess.exit(0);\n",
    );
    fs.chmodSync(filePath, 0o755);
  }
  return layout;
};

const createStagedVerifier = (root, packagedResourcesDir) => {
  const staticRoot = path.join(root, "staged-static");
  fs.mkdirSync(staticRoot, { recursive: true });
  fs.copyFileSync(verifierPath, path.join(staticRoot, "verify-packaged-desktop.mjs"));
  fs.writeFileSync(
    path.join(staticRoot, "package.json"),
    JSON.stringify({
      devDependencies: { electron: electronVersion },
      type: "module",
    }),
  );
  for (const relativePath of [
    path.join("sidecar", "embedding_server.py"),
    ...helperNames.map((name) => path.join("sidecar", name)),
    ...policyRelativePaths,
    ...policyCompanionRelativePaths,
    ...runtimeRelativePaths,
  ]) {
    const source = path.join(packagedResourcesDir, relativePath);
    const destination = path.join(staticRoot, relativePath);
    fs.mkdirSync(path.dirname(destination), { recursive: true });
    fs.copyFileSync(source, destination);
    fs.chmodSync(destination, fs.statSync(source).mode & 0o777);
  }
  return {
    staticRoot,
    verifierPath: path.join(staticRoot, "verify-packaged-desktop.mjs"),
  };
};

const removeAll = (root, relativePaths) => {
  for (const relativePath of relativePaths) {
    fs.rmSync(path.join(root, relativePath), {
      force: true,
      recursive: true,
    });
  }
};

const overwritePolicies = (
  resourceRoots,
  policy,
  publicKey = fixturePublicKey,
) => {
  for (const resourcesDir of resourceRoots) {
    for (const relativePath of policyRelativePaths) {
      fs.writeFileSync(path.join(resourcesDir, relativePath), policy);
      writePolicyCompanion(resourcesDir, relativePath, publicKey);
    }
  }
};

const verifierInvocation = ({
  arch,
  asarNodePath,
  platform,
  searchRoot,
  verifierPath: stagedVerifierPath,
}) =>
  run(
    process.execPath,
    [
      stagedVerifierPath,
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
    const stagedVerifier = createStagedVerifier(root, layout.resourcesDir);
    return callback({
      arch,
      asarNodePath,
      layout,
      platform,
      root,
      searchRoot: path.join(root, "dist"),
      ...stagedVerifier,
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

test("desktop runtime preparation stages the repository sidecar inside static", () => {
  const runtimePreparation = fs.readFileSync(runtimePreparationPath, "utf8");
  assert.match(
    runtimePreparation,
    /(?:path\.(?:join|resolve)\([^)]*["']sidecar["'][^)]*\)|new URL\(\s*["'](?:\.\.\/)?sidecar\/?["'])/,
    "desktop runtime preparation does not declare the repository sidecar source",
  );
  assert.match(
    runtimePreparation,
    /(?:path\.(?:join|resolve)\(\s*(?:staticDir|staticRoot|outputDir)\s*,[^)]*["']sidecar["'][^)]*\)|path\.(?:join|resolve)\([^)]*["']static["'][^)]*["']sidecar["'][^)]*\)|new URL\(\s*["'](?:\.\.\/)?static\/sidecar\/?["']|["']static\/sidecar\/?["'])/,
    "desktop runtime preparation does not stage into static/sidecar",
  );
  assert.match(
    runtimePreparation,
    /(?:\bcp(?:Sync)?\s*\(|\bcopy(?:File|Directory|Dir)(?:Sync)?\s*\()/,
    "desktop runtime preparation does not copy the repository sidecar into static",
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

  const workflow = fs.readFileSync(workflowPath, "utf8");
  const compileJob = workflowJob(workflow, "compile-cljs");
  assert.match(
    compileJob,
    /desktop:prepare-runtime-js/,
    "compile artifact job does not run desktop runtime preparation",
  );
  assert.match(
    compileJob,
    /test\s+-f\s+(?:\.\/)?static\/sidecar\/embedding_server\.py/,
    "compile artifact job does not prove static/sidecar/embedding_server.py was staged",
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
      /test\s+-f\s+["']?[^"'\n]*static\/sidecar\/embedding_server\.py["']?/,
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
    assert.doesNotMatch(
      job,
      /(?:cp|ditto|rsync)[^\n]*(?:release-gate-source|checkout)[^\n]*sidecar|(?:cp|ditto|rsync)[^\n]*sidecar[^\n]*(?:release-gate-source|checkout)/i,
      `${jobName} masks a missing compile artifact by copying sidecar from its checkout`,
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
      for (const resourcesDir of [
        fixture.layout.resourcesDir,
        fixture.staticRoot,
      ]) {
        fs.rmSync(
          path.join(resourcesDir, "sidecar", "embedding_server.py"),
        );
      }
      expectVerifierFailure(
        fixture,
        `${platform}/${arch} missing embedding_server.py`,
      );
    }),
    );
    test(`${platform}/${arch} packaged verifier rejects missing shared track-policy runtime`, () =>
    withPackagedFixture({ arch, platform }, (fixture) => {
      for (const resourcesDir of [
        fixture.layout.resourcesDir,
        fixture.staticRoot,
      ]) {
        removeAll(resourcesDir, trackRuntimeRelativePaths);
      }
      expectVerifierFailure(
        fixture,
        `${platform}/${arch} missing project-updater-signature.mjs`,
      );
    }),
    );
    test(`${platform}/${arch} packaged verifier rejects packaged track-policy tampering`, () =>
    withPackagedFixture({ arch, platform }, (fixture) => {
      for (const relativePath of trackRuntimeRelativePaths) {
        fs.appendFileSync(
          path.join(fixture.layout.resourcesDir, relativePath),
          "\n// packaged tamper\n",
        );
      }
      expectVerifierFailure(
        fixture,
        `${platform}/${arch} packaged track-policy tampering`,
      );
    }),
    );
    test(`${platform}/${arch} packaged verifier rejects staged track-policy tampering`, () =>
    withPackagedFixture({ arch, platform }, (fixture) => {
      for (const relativePath of trackRuntimeRelativePaths) {
        fs.appendFileSync(
          path.join(fixture.staticRoot, relativePath),
          "\n// staged tamper\n",
        );
      }
      expectVerifierFailure(
        fixture,
        `${platform}/${arch} staged track-policy tampering`,
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
      for (const resourcesDir of [
        fixture.layout.resourcesDir,
        fixture.staticRoot,
      ]) {
        removeAll(path.join(resourcesDir, "sidecar"), helperNames);
      }
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
      for (const resourcesDir of [
        fixture.layout.resourcesDir,
        fixture.staticRoot,
      ]) {
        const sidecarDir = path.join(resourcesDir, "sidecar");
        for (const helperName of helperNames) {
          const helperPath = path.join(sidecarDir, helperName);
          fs.rmSync(helperPath);
          fs.mkdirSync(helperPath);
        }
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
      for (const resourcesDir of [
        fixture.layout.resourcesDir,
        fixture.staticRoot,
      ]) {
        const sidecarDir = path.join(resourcesDir, "sidecar");
        for (const helperName of helperNames) {
          fs.chmodSync(path.join(sidecarDir, helperName), 0o644);
        }
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
      for (const resourcesDir of [
        fixture.layout.resourcesDir,
        fixture.staticRoot,
      ]) {
        const sidecarDir = path.join(resourcesDir, "sidecar");
        for (const helperName of helperNames) {
          writeExecutable(
            path.join(sidecarDir, helperName),
            "darwin",
            wrongArch,
          );
        }
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
      for (const resourcesDir of [
        fixture.layout.resourcesDir,
        fixture.staticRoot,
      ]) {
        removeAll(resourcesDir, policyRelativePaths);
      }
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
          [fixture.layout.resourcesDir, fixture.staticRoot],
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
        [fixture.layout.resourcesDir, fixture.staticRoot],
        signingPolicy({
          keyId: `ed25519:${acceptedDigest.slice(0, 16)}`,
          publicKey: Buffer.alloc(32, 0x7e),
        }),
        Buffer.alloc(32, 0x7e),
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
      for (const resourcesDir of [
        fixture.layout.resourcesDir,
        fixture.staticRoot,
      ]) {
        removeAll(resourcesDir, runtimeRelativePaths);
      }
      expectVerifierFailure(fixture, "darwin missing project update runtime");
    },
  ));

test("full local preflight invokes the explicit macOS test-helper gate", () => {
  const source = fs.readFileSync(preflightPath, "utf8");
  assert.match(
    source,
    /project-update:test-helper/,
    "full preflight never invokes the explicit native test-helper target",
  );
  assert.match(
    source,
    /process\.arch/,
    "full preflight does not build the helper for the host architecture",
  );
});

test("formal preflight and CI execute updater source, native, and provider contracts", () => {
  const workflow = fs.readFileSync(workflowPath, "utf8");
  const sourcePreflight = workflowJob(workflow, "source-preflight");
  for (const script of [
    "test-desktop-sidecar-release-contract.mjs",
    "test-updater-install-entry-contract.mjs",
    "test-selfhost-macos-user-guidance.mjs",
    "test-no-local-trust-mutation-contract.mjs",
  ]) {
    assert.match(
      sourcePreflight,
      new RegExp(script.replaceAll(".", "\\.")),
      `source-preflight does not execute ${script}`,
    );
  }

  for (const jobName of ["build-macos-x64", "build-macos-arm64"]) {
    const job = workflowJob(workflow, jobName);
    assert.match(
      job,
      /project-update:test-helper/,
      `${jobName} does not build the explicit test-only native helper`,
    );
    assert.match(
      job,
      /test:project-signed-macos-updater/,
      `${jobName} does not execute the native project-signed updater E2E`,
    );
    assert.match(
      job,
      /test-selfhost-macos-updater-release-contract\.mjs/,
      `${jobName} does not execute the real electron-updater provider contract`,
    );
    assert.match(
      job,
      /LOGSEQ_UPDATER_TEST_PACKAGE_ROOT:[^\n]*static/,
      `${jobName} provider contract does not use the downloaded static dependencies`,
    );
  }
  const rtcReleaseGate = workflowJob(workflow, "rtc-release-gate");
  const compileTestsAt = rtcReleaseGate.indexOf("pnpm cljs:test");
  const runCompiledTestsAt = rtcReleaseGate.indexOf("static/tests.js");
  assert.ok(
    compileTestsAt >= 0 &&
      runCompiledTestsAt > compileTestsAt,
    "RTC release gate must recompile CLJS tests before running static/tests.js",
  );
  assert.match(
    rtcReleaseGate.slice(runCompiledTestsAt),
    /electron\\\.\([^)]*\bupdater\b[^)]*\)-test|electron\\\.updater-test/,
    "RTC release gate does not execute electron.updater-test from the freshly compiled bundle",
  );
  assert.match(
    rtcReleaseGate.slice(
      Math.max(0, runCompiledTestsAt - 180),
      runCompiledTestsAt,
    ),
    /--require\s+\.\/scripts\/fixtures\/electron-test-preload\.cjs/,
    "RTC release gate does not preload its Node-only Electron test doubles",
  );

  const packageJson = JSON.parse(
    fs.readFileSync(path.join(repoRoot, "package.json"), "utf8"),
  );
  assert.equal(
    typeof packageJson.scripts?.["project-update:test-helper"],
    "string",
    "package.json does not expose the explicit native updater test-helper target",
  );
  assert.match(
    packageJson.scripts?.["test:selfhost-updater-source-contracts"] ?? "",
    /test-desktop-sidecar-release-contract\.mjs[\s\S]*test-updater-install-entry-contract\.mjs[\s\S]*test-selfhost-macos-user-guidance\.mjs[\s\S]*test-no-local-trust-mutation-contract\.mjs/,
    "package.json does not expose the complete updater source-contract gate",
  );
  assert.match(
    packageJson.scripts?.["test:selfhost-updater-provider-contract"] ?? "",
    /test-selfhost-macos-updater-release-contract\.mjs/,
    "package.json does not expose the real updater provider contract",
  );

  const fullPreflight = fs.readFileSync(preflightPath, "utf8");
  const fullCompileAt = fullPreflight.indexOf('"compile client tests"');
  const fullRunAt = fullPreflight.indexOf('"static/tests.js"');
  assert.ok(
    fullCompileAt >= 0 && fullRunAt > fullCompileAt,
    "full preflight must recompile CLJS tests before running static/tests.js",
  );
  assert.match(
    fullPreflight.slice(fullRunAt),
    /electron\\\\\.\([^)]*\bupdater\b[^)]*\)-test|electron\\\\\.updater-test/,
    "full preflight does not execute electron.updater-test from the freshly compiled bundle",
  );
  assert.match(
    fullPreflight.slice(Math.max(0, fullRunAt - 300), fullRunAt),
    /electron-test-preload\.cjs/,
    "full preflight does not preload its Node-only Electron test doubles",
  );
  assert.match(
    fullPreflight,
    /path\.(?:join|resolve)\(\s*repoRoot,[\s\S]{0,180}electron-test-preload\.cjs/,
    "full preflight only checks the preload filename instead of selecting a repo-rooted path",
  );
  assert.doesNotMatch(
    fullPreflight,
    /electronTestPreload\s*=\s*(?:preloadCandidate\s*\?\?\s*)?["']scripts\/fixtures\/electron-test-preload\.cjs["']/,
    "full preflight retains a bare relative preload specifier",
  );
  for (const commandName of [
    "test:selfhost-updater-source-contracts",
    "test:selfhost-updater-provider-contract",
    "test:project-signed-macos-updater",
  ]) {
    assert.match(
      fullPreflight,
      new RegExp(commandName.replaceAll(":", "\\:")),
      `full desktop preflight does not execute ${commandName}`,
    );
  }
  assert.match(
    fullPreflight,
    /process\.platform === "darwin"[\s\S]{0,300}project-update:test-helper/,
    "full desktop preflight does not require the native test helper on macOS",
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
