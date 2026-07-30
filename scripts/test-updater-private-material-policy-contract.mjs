#!/usr/bin/env node

import assert from "node:assert/strict";
import {
  createHash,
  createPrivateKey,
  createPublicKey,
  generateKeyPairSync,
  X509Certificate,
} from "node:crypto";
import { spawn, spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const scannerPath = "scripts/test-project-signed-macos-updater.mjs";
const proxyTestPath = "scripts/test-cli-worker-fetch-proxy.mjs";
const proxyProbePath =
  "scripts/fixtures/cli-worker-fetch-proxy-probe.mjs";
const proxyCaPath =
  "scripts/fixtures/proxy-fetch-test-ca.cert.pem";
const proxyCertPath =
  "scripts/fixtures/proxy-fetch-test-server.cert.pem";
const proxyKeyPath =
  "scripts/fixtures/proxy-fetch-test-server.key.pem";
const proxyKeySha256 =
  "84b5d6ec5bc56e117b5d8b21bad0de2244a603926a635544e43b446c2d7bb483";
const proxyCertSha256 =
  "307c758041e3b04bcd3eaf359a00532d79843f22f44051ee812af8f54b4aab23";
const policyPath =
  "resources/updater/project-signing-policy.json";
const expectedCaseName = "repository contains no updater private key";
const privateKeyMarker = ["-----BEGIN", "PRIVATE", "KEY-----"].join(" ");
const privateKeyEndMarker =
  ["-----END", "PRIVATE", "KEY-----"].join(" ");

const command = (
  executable,
  args,
  { allowFailure = false, cwd = repoRoot } = {},
) => {
  const result = spawnSync(executable, args, {
    cwd,
    encoding: "utf8",
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

const copyFromRepository = (relativePath, destinationRoot) => {
  const destination = path.join(destinationRoot, relativePath);
  fs.mkdirSync(path.dirname(destination), { recursive: true });
  fs.copyFileSync(path.join(repoRoot, relativePath), destination);
};

const copyRelativeModuleClosure = (
  relativePath,
  destinationRoot,
  seen = new Set(),
) => {
  if (seen.has(relativePath)) return;
  seen.add(relativePath);
  copyFromRepository(relativePath, destinationRoot);
  const source = fs.readFileSync(path.join(repoRoot, relativePath), "utf8");
  for (const match of source.matchAll(
    /(?:from\s+|import\s*\(\s*)["'](\.[^"']+\.(?:c?js|mjs))["']/g,
  )) {
    const dependency = path
      .normalize(path.join(path.dirname(relativePath), match[1]))
      .replaceAll(path.sep, "/");
    copyRelativeModuleClosure(dependency, destinationRoot, seen);
  }
};

const initializeProbeRepository = (mutate = () => {}) => {
  const root = fs.mkdtempSync(
    path.join(os.tmpdir(), "logseq-private-material-policy-"),
  );
  copyRelativeModuleClosure(scannerPath, root);
  for (const relativePath of [
    proxyTestPath,
    proxyProbePath,
    proxyCaPath,
    proxyCertPath,
    proxyKeyPath,
    policyPath,
  ]) {
    copyFromRepository(relativePath, root);
  }
  mutate(root);
  command("git", ["init", "--quiet"], { cwd: root });
  command("git", ["add", "--all"], { cwd: root });
  return root;
};

const probeScannerCase = (root) =>
  new Promise((resolve, reject) => {
    const childTemp = fs.mkdtempSync(
      path.join(os.tmpdir(), "logseq-private-material-child-"),
    );
    const child = spawn(process.execPath, [scannerPath], {
      cwd: root,
      env: { ...process.env, TMPDIR: childTemp },
      stdio: ["ignore", "pipe", "pipe"],
    });
    let output = "";
    let outcome;
    let timeout;
    const cleanChildTemp = () =>
      fs.rmSync(childTemp, { recursive: true, force: true });
    const casePattern = new RegExp(
      `\\[project-updater\\] (PASS|FAIL|BLOCK) ${expectedCaseName.replaceAll(
        " ",
        "\\s+",
      )}`,
    );
    const observe = (chunk) => {
      output += chunk.toString();
      const match = output.match(casePattern);
      if (match && !outcome) {
        outcome = match[1];
        child.kill("SIGKILL");
      }
    };
    child.stdout.on("data", observe);
    child.stderr.on("data", observe);
    child.once("error", (error) => {
      if (timeout) clearTimeout(timeout);
      cleanChildTemp();
      reject(error);
    });
    timeout = setTimeout(() => {
      child.kill("SIGKILL");
      reject(
        new Error(
          `timed out before observing ${expectedCaseName}:\n${output}`,
        ),
      );
    }, 15_000);
    child.once("close", () => {
      clearTimeout(timeout);
      cleanChildTemp();
      if (!outcome) {
        reject(
          new Error(
            `scanner exited before reporting ${expectedCaseName}:\n${output}`,
          ),
        );
        return;
      }
      resolve({ outcome, output: output.trim() });
    });
  });

const expectScannerOutcome = async (root, expected, label) => {
  const result = await probeScannerCase(root);
  assert.equal(
    result.outcome,
    expected,
    `${label}: expected scanner ${expected}, got ${result.outcome}\n${result.output}`,
  );
};

const withProbeRepository = async (mutate, test) => {
  const root = initializeProbeRepository(mutate);
  try {
    await test(root);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
};

const sha256 = (value) =>
  createHash("sha256").update(value).digest("hex");

const cases = [];
const addCase = (name, test) => cases.push([name, test]);

addCase(
  "localhost proxy TLS key is tracked, referenced, certificate-matched, and not the updater key",
  () => {
    for (const relativePath of [proxyKeyPath, proxyCertPath]) {
      const tracked = command(
        "git",
        ["ls-files", "--error-unmatch", relativePath],
        { allowFailure: true },
      );
      assert.equal(
        tracked.status,
        0,
        `${relativePath} is not a tracked fixture`,
      );
    }

    const proxyTest = fs.readFileSync(
      path.join(repoRoot, proxyTestPath),
      "utf8",
    );
    for (const fixtureName of [
      path.basename(proxyKeyPath),
      path.basename(proxyCertPath),
    ]) {
      assert.ok(
        proxyTest.includes(`"${fixtureName}"`),
        `${proxyTestPath} does not reference ${fixtureName}`,
      );
    }

    const privateKey = createPrivateKey(
      fs.readFileSync(path.join(repoRoot, proxyKeyPath)),
    );
    const certificate = new X509Certificate(
      fs.readFileSync(path.join(repoRoot, proxyCertPath)),
    );
    const fixturePublicKey = createPublicKey(privateKey).export({
      format: "der",
      type: "spki",
    });
    const certificatePublicKey = certificate.publicKey.export({
      format: "der",
      type: "spki",
    });
    assert.equal(
      sha256(fs.readFileSync(path.join(repoRoot, proxyKeyPath))),
      proxyKeySha256,
      "tracked proxy TLS private key bytes changed",
    );
    assert.equal(
      sha256(fs.readFileSync(path.join(repoRoot, proxyCertPath))),
      proxyCertSha256,
      "tracked proxy TLS certificate bytes changed",
    );
    assert.deepEqual(
      fixturePublicKey,
      certificatePublicKey,
      "proxy TLS private key does not match its adjacent certificate",
    );

    const policy = JSON.parse(
      fs.readFileSync(path.join(repoRoot, policyPath), "utf8"),
    );
    assert.match(
      String(policy.algorithm),
      /ed25519/i,
      "production updater policy is not Ed25519",
    );
    const policyRaw = Buffer.from(
      String(policy.publicKeyBase64),
      "base64",
    );
    const policyPublicKey =
      policyRaw.length === 32
        ? Buffer.concat([
            Buffer.from("302a300506032b6570032100", "hex"),
            policyRaw,
          ])
        : policyRaw;
    assert.notEqual(
      sha256(fixturePublicKey),
      sha256(policyPublicKey),
      "localhost proxy TLS public key equals the production updater key",
    );
  },
);

addCase("production scanner allows only the legitimate proxy TLS fixture", async () => {
  await expectScannerOutcome(
    repoRoot,
    "PASS",
    "legitimate tracked localhost proxy TLS fixture",
  );
});

for (const [name, mutate] of [
  [
    "copied proxy private key is rejected",
    (root) =>
      fs.copyFileSync(
        path.join(root, proxyKeyPath),
        path.join(
          root,
          "scripts",
          "fixtures",
          "proxy-fetch-test-server-copy.key.pem",
        ),
      ),
  ],
  [
    "renamed proxy private key is rejected",
    (root) =>
      fs.renameSync(
        path.join(root, proxyKeyPath),
        path.join(
          root,
          "scripts",
          "fixtures",
          "renamed-proxy-server.key.pem",
        ),
      ),
  ],
  [
    "replacement private key at the allowed path is rejected",
    (root) => {
      const replacement = generateKeyPairSync("rsa", {
        modulusLength: 2048,
      }).privateKey.export({ format: "pem", type: "pkcs8" });
      fs.writeFileSync(path.join(root, proxyKeyPath), replacement);
    },
  ],
  [
    "byte-modified allowed fixture is rejected even with the same key",
    (root) => fs.appendFileSync(path.join(root, proxyKeyPath), "\n"),
  ],
  [
    "unreferenced proxy private key is rejected",
    (root) => {
      const testPath = path.join(root, proxyTestPath);
      const source = fs.readFileSync(testPath, "utf8");
      fs.writeFileSync(
        testPath,
        source.replace(
          path.basename(proxyKeyPath),
          "missing-proxy-server.key.pem",
        ),
      );
    },
  ],
  [
    "proxy private key with a mismatched adjacent certificate is rejected",
    (root) =>
      fs.copyFileSync(
        path.join(root, proxyCaPath),
        path.join(root, proxyCertPath),
      ),
  ],
  [
    "additional .key private material is rejected",
    (root) =>
      fs.copyFileSync(
        path.join(root, proxyKeyPath),
        path.join(root, "scripts", "fixtures", "unexpected.key"),
      ),
  ],
  [
    "additional .pem private material is rejected",
    (root) =>
      fs.copyFileSync(
        path.join(root, proxyKeyPath),
        path.join(root, "scripts", "fixtures", "unexpected-private.pem"),
      ),
  ],
  [
    "release-key naming is rejected even without a marker",
    (root) => {
      const releaseKey = path.join(
        root,
        "resources",
        "updater",
        "release-key.txt",
      );
      fs.mkdirSync(path.dirname(releaseKey), { recursive: true });
      fs.writeFileSync(releaseKey, "not private material\n");
    },
  ],
  [
    "private-key marker outside the exact fixture is rejected",
    (root) => {
      const markerFile = path.join(root, "docs", "private-marker.txt");
      fs.mkdirSync(path.dirname(markerFile), { recursive: true });
      fs.writeFileSync(
        markerFile,
        `${privateKeyMarker}\nnot-a-real-key\n${privateKeyEndMarker}\n`,
      );
    },
  ],
]) {
  addCase(name, async () => {
    await withProbeRepository(mutate, async (root) => {
      await expectScannerOutcome(root, "FAIL", name);
    });
  });
}

let passed = 0;
let failed = 0;
for (const [name, test] of cases) {
  try {
    await test();
    passed += 1;
    console.log(`[updater-private-material-policy] PASS ${name}`);
  } catch (error) {
    failed += 1;
    console.error(
      `[updater-private-material-policy] FAIL ${name}: ${
        error instanceof Error ? error.stack || error.message : error
      }`,
    );
  }
}

console.log(
  `[updater-private-material-policy] SUMMARY passed=${passed} failed=${failed} total=${cases.length}`,
);
if (failed > 0) process.exit(1);
