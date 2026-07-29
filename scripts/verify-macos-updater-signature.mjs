#!/usr/bin/env node

import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { Readable } from "node:stream";
import { finished } from "node:stream/promises";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const defaultManifestPath = path.join(
  repoRoot,
  "scripts",
  "fixtures",
  "macos-updater-baseline.json",
);

export class UpdaterSignatureGateError extends Error {
  constructor(kind, message, options) {
    super(message, options);
    this.name = "UpdaterSignatureGateError";
    this.kind = kind;
  }
}

const quoteTrim = (value) =>
  value.trim().replace(/^(['"])(.*)\1$/, "$2");

const parseArgs = (argv) => {
  const values = new Map();
  for (let index = 0; index < argv.length; index += 2) {
    const key = argv[index];
    const value = argv[index + 1];
    if (!key?.startsWith("--") || value === undefined) {
      throw new Error(`invalid argument near ${key ?? "<end>"}`);
    }
    values.set(key.slice(2), value);
  }

  const required = (key) => {
    const value = values.get(key);
    if (!value) throw new Error(`missing required --${key}`);
    return value;
  };

  return {
    arch: required("arch"),
    candidateMetadata: path.resolve(required("candidate-metadata")),
    candidateVersion: required("candidate-version"),
    candidateZip: path.resolve(required("candidate-zip")),
    baselineManifest: path.resolve(
      values.get("baseline-manifest") || defaultManifestPath,
    ),
    baselineMetadata:
      values.get("baseline-metadata") ||
      process.env.LOGSEQ_UPDATER_BASELINE_METADATA,
    baselineZip:
      values.get("baseline-zip") ||
      process.env.LOGSEQ_UPDATER_BASELINE_ZIP,
  };
};

const command = (executable, args, options = {}) => {
  const result = spawnSync(executable, args, {
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
    ...options,
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    const detail = `${result.stdout || ""}${result.stderr || ""}`.trim();
    throw new Error(
      `${executable} ${args.join(" ")} failed with exit ${result.status}${
        detail ? `\n${detail}` : ""
      }`,
    );
  }
  return `${result.stdout || ""}${result.stderr || ""}`.trim();
};

const hashFile = async (algorithm, file) => {
  const hash = createHash(algorithm);
  const input = fs.createReadStream(file);
  input.on("data", (chunk) => hash.update(chunk));
  await finished(input);
  return hash.digest(algorithm === "sha512" ? "base64" : "hex");
};

const assertHash = async (algorithm, file, expected) => {
  const actual = await hashFile(algorithm, file);
  if (actual !== expected) {
    throw new Error(
      `${path.basename(file)} ${algorithm} mismatch: expected ${expected}, got ${actual}`,
    );
  }
};

const download = async (url, destination) => {
  for (let attempt = 1; attempt <= 4; attempt += 1) {
    try {
      const response = await fetch(url, {
        headers: { "user-agent": "logseq-macos-updater-signature-gate" },
        redirect: "follow",
      });
      if (!response.ok || !response.body) {
        throw new Error(`HTTP ${response.status} ${response.statusText}`);
      }
      const output = fs.createWriteStream(destination, { mode: 0o600 });
      await finished(Readable.fromWeb(response.body).pipe(output));
      return;
    } catch (error) {
      fs.rmSync(destination, { force: true });
      if (attempt === 4) {
        throw new Error(`download failed after ${attempt} attempts: ${url}`, {
          cause: error,
        });
      }
    }
  }
};

const parseUpdaterMetadata = (file) => {
  const text = fs.readFileSync(file, "utf8");
  const readTopLevel = (key) =>
    quoteTrim(
      text.match(new RegExp(`^${key}:\\s*(.+?)\\s*$`, "m"))?.[1] || "",
    );
  const version = readTopLevel("version");
  const zipPath = readTopLevel("path");
  const sha512 = readTopLevel("sha512");
  if (!version || !zipPath || !sha512) {
    throw new Error(
      `${file} must contain top-level version, path, and sha512 fields`,
    );
  }

  const fileEntries = [];
  const entryPattern =
    /^\s*-\s+(?:url|path):\s*(.+?)\s*\n\s+sha512:\s*(.+?)\s*\n\s+size:\s*(\d+)\s*$/gm;
  for (const match of text.matchAll(entryPattern)) {
    fileEntries.push({
      path: quoteTrim(match[1]),
      sha512: quoteTrim(match[2]),
      size: Number(match[3]),
    });
  }
  const selected = fileEntries.find((entry) => entry.path === zipPath);
  if (!selected) {
    throw new Error(`${file} files list does not describe top-level path ${zipPath}`);
  }
  if (selected.sha512 !== sha512) {
    throw new Error(`${file} has inconsistent sha512 values for ${zipPath}`);
  }
  return { version, zipPath, sha512, size: selected.size };
};

const assertMetadata = ({
  arch,
  expectedVersion,
  metadataFile,
  zipFile,
}) => {
  const metadata = parseUpdaterMetadata(metadataFile);
  const expectedName = `Logseq-darwin-${arch}-${expectedVersion}.zip`;
  if (metadata.version !== expectedVersion) {
    throw new Error(
      `${path.basename(metadataFile)} version ${metadata.version} != ${expectedVersion}`,
    );
  }
  if (metadata.zipPath !== expectedName) {
    throw new Error(
      `${path.basename(metadataFile)} path ${metadata.zipPath} != ${expectedName}`,
    );
  }
  if (path.basename(zipFile) !== expectedName) {
    throw new Error(`${path.basename(zipFile)} != updater path ${expectedName}`);
  }
  return metadata;
};

const assertDownload = async (metadata, zipFile) => {
  const size = fs.statSync(zipFile).size;
  if (size !== metadata.size) {
    throw new Error(
      `${path.basename(zipFile)} size ${size} != metadata size ${metadata.size}`,
    );
  }
  await assertHash("sha512", zipFile, metadata.sha512);
};

const findSingleApp = (root) => {
  const apps = [];
  const walk = (directory) => {
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
      const item = path.join(directory, entry.name);
      if (entry.isDirectory() && entry.name.endsWith(".app")) {
        apps.push(item);
      } else if (entry.isDirectory()) {
        walk(item);
      }
    }
  };
  walk(root);
  if (apps.length !== 1) {
    throw new Error(`${root} must contain exactly one top-level App; found ${apps.length}`);
  }
  return apps[0];
};

const plistValue = (app, key) =>
  command("plutil", [
    "-extract",
    key,
    "raw",
    path.join(app, "Contents", "Info.plist"),
  ]);

const assertApp = ({ app, arch, version }) => {
  const bundleVersion = plistValue(app, "CFBundleShortVersionString");
  const buildVersion = plistValue(app, "CFBundleVersion");
  if (bundleVersion !== version || buildVersion !== version) {
    throw new Error(
      `${app} bundle versions ${bundleVersion}/${buildVersion} != ${version}`,
    );
  }
  const executableName = plistValue(app, "CFBundleExecutable");
  const executable = path.join(app, "Contents", "MacOS", executableName);
  const architectures = command("lipo", ["-archs", executable]).split(/\s+/);
  if (!architectures.includes(arch)) {
    throw new Error(`${app} executable architectures do not include ${arch}`);
  }
  command("codesign", ["--verify", "--deep", "--strict", "--verbose=2", app]);
  return command("codesign", ["-dvvv", "-r-", app]);
};

const verifyCandidateAgainstOldRequirement = (oldSignatureDetails, newApp) => {
  const requirement = oldSignatureDetails.match(
    /^(?:# )?designated => (.+)$/m,
  )?.[1];
  if (!requirement) {
    throw new UpdaterSignatureGateError(
      "fixture-error",
      "published baseline codesign output did not contain a designated requirement",
    );
  }

  const result = spawnSync(
    "codesign",
    [
      "--verify",
      "--deep",
      "--strict",
      "--all-architectures",
      `-R=${requirement}`,
      newApp,
    ],
    {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    },
  );
  if (result.error) {
    throw new UpdaterSignatureGateError(
      "fixture-error",
      `could not execute codesign requirement check: ${result.error.message}`,
      { cause: result.error },
    );
  }
  const output = `${result.stdout || ""}${result.stderr || ""}`.trim();
  if (result.status === 0) {
    return `candidate satisfies old DR: ${requirement}`;
  }
  if (
    output.includes("code failed to satisfy specified code requirement(s)") ||
    output.includes("CSSMERR_TP_NOT_TRUSTED")
  ) {
    throw new UpdaterSignatureGateError(
      "signature-incompatible",
      [
        `candidate does not satisfy published App designated requirement: ${requirement}`,
        output,
      ]
        .filter(Boolean)
        .join("\n"),
    );
  }
  throw new UpdaterSignatureGateError(
    "fixture-error",
    [
      `codesign could not evaluate published App designated requirement (exit ${result.status})`,
      output,
    ]
      .filter(Boolean)
      .join("\n"),
  );
};

class Reporter {
  passed = 0;
  failed = 0;

  async step(label, action) {
    try {
      const detail = await action();
      this.passed += 1;
      console.log(`[macos-updater-signature] PASS ${label}${detail ? `: ${detail}` : ""}`);
      return detail;
    } catch (error) {
      this.failed += 1;
      console.error(
        `[macos-updater-signature] FAIL ${label}: ${
          error instanceof Error ? error.message : error
        }`,
      );
      throw error;
    }
  }

  summary() {
    console.log(
      `[macos-updater-signature] SUMMARY passed=${this.passed} failed=${this.failed} total=${
        this.passed + this.failed
      }`,
    );
  }
}

export const classifyShipItOutcome = ({
  spawnError,
  status,
  log,
  before,
  after,
  newVersion,
}) => {
  if (spawnError) {
    throw new UpdaterSignatureGateError(
      "fixture-error",
      `could not execute ShipIt: ${spawnError.message}`,
      { cause: spawnError },
    );
  }
  if (
    log.includes("SQRLShipItRequestErrorDomain") ||
    log.includes("Could not read update request")
  ) {
    throw new UpdaterSignatureGateError(
      "fixture-error",
      [
        "ShipIt request fixture was unreadable or invalid; this is not an updater signature regression",
        `ShipIt exit=${status} target-before=${before} target-after=${after}`,
        log,
      ]
        .filter(Boolean)
        .join("\n"),
    );
  }
  if (
    log.includes("SQRLCodeSignatureErrorDomain") ||
    log.includes("code failed to satisfy specified code requirement(s)") ||
    log.includes("Code=-67050")
  ) {
    throw new UpdaterSignatureGateError(
      "signature-incompatible",
      [
        `ShipIt exit=${status ?? "spawn-error"} target-before=${before} target-after=${after}`,
        log,
      ]
        .filter(Boolean)
        .join("\n"),
    );
  }
  if (status !== 0 || after !== newVersion) {
    throw new UpdaterSignatureGateError(
      "install-failure",
      [
        `ShipIt did not replace the target (exit=${status} target-before=${before} target-after=${after})`,
        log,
      ]
        .filter(Boolean)
        .join("\n"),
    );
  }
  return `ShipIt exit=0 target-before=${before} target-after=${after}`;
};

const runShipItInstall = ({
  oldApp,
  oldVersion,
  newApp,
  newVersion,
  tempRoot,
}) => {
  const installRoot = path.join(tempRoot, "shipit-install");
  const targetApp = path.join(installRoot, "target", "Logseq.app");
  const updateApp = path.join(installRoot, "update", "Logseq.app");
  fs.mkdirSync(path.dirname(targetApp), { recursive: true });
  fs.mkdirSync(path.dirname(updateApp), { recursive: true });
  command("ditto", [oldApp, targetApp]);
  command("ditto", [newApp, updateApp]);

  const before = plistValue(targetApp, "CFBundleShortVersionString");
  if (before !== oldVersion) {
    throw new Error(`isolated target starts at ${before}, expected ${oldVersion}`);
  }

  const statePath = path.join(installRoot, "state.json");
  const toFileUrl = (file) => new URL(`file://${path.resolve(file)}`).href;
  fs.writeFileSync(
    statePath,
    JSON.stringify({
      updateBundleURL: toFileUrl(updateApp),
      targetBundleURL: toFileUrl(targetApp),
      bundleIdentifier: null,
      launchAfterInstallation: false,
      useUpdateBundleName: false,
    }),
    { mode: 0o600 },
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
  if (!fs.existsSync(shipIt)) {
    throw new Error(`old App does not contain Squirrel ShipIt at ${shipIt}`);
  }

  const fixedHome = path.join(installRoot, "home");
  fs.mkdirSync(fixedHome, { recursive: true });
  const result = spawnSync(
    shipIt,
    [`com.logseq.updater-signature-gate.${process.pid}.ShipIt`, statePath],
    {
      encoding: "utf8",
      env: {
        ...process.env,
        CFFIXED_USER_HOME: fixedHome,
        HOME: fixedHome,
      },
      stdio: ["ignore", "pipe", "pipe"],
    },
  );
  const log = `${result.stdout || ""}${result.stderr || ""}`.trim();
  const after = fs.existsSync(targetApp)
    ? plistValue(targetApp, "CFBundleShortVersionString")
    : "<missing>";
  return classifyShipItOutcome({
    spawnError: result.error,
    status: result.status,
    log,
    before,
    after,
    newVersion,
  });
};

export const loadBaseline = async (options, tempRoot) => {
  const manifest = JSON.parse(
    fs.readFileSync(options.baselineManifest, "utf8"),
  );
  const architecture = manifest.architectures?.[options.arch];
  if (!architecture) {
    throw new Error(
      `${options.baselineManifest} has no ${options.arch} updater baseline`,
    );
  }
  const baseUrl = `https://github.com/${manifest.repository}/releases/download/${manifest.version}`;
  const materialize = async (override, name) => {
    if (override) return path.resolve(override);
    const destination = path.join(tempRoot, name);
    console.log(`[macos-updater-signature] DOWNLOAD ${baseUrl}/${name}`);
    await download(`${baseUrl}/${name}`, destination);
    return destination;
  };

  const metadata = await materialize(
    options.baselineMetadata,
    architecture.metadata,
  );
  const zip = await materialize(options.baselineZip, architecture.zip);
  await assertHash("sha256", metadata, architecture.metadataSha256);
  await assertHash("sha256", zip, architecture.zipSha256);
  return { manifest, architecture, metadata, zip };
};

export const runGate = async (options) => {
  if (process.platform !== "darwin") {
    throw new Error("macOS updater installation compatibility must run on macOS");
  }
  if (!["arm64", "x64"].includes(options.arch)) {
    throw new Error(`unsupported macOS architecture ${options.arch}`);
  }

  const tempRoot = fs.mkdtempSync(
    path.join(os.tmpdir(), "logseq-macos-updater-signature-"),
  );
  const reporter = new Reporter();
  try {
    const baseline = await loadBaseline(options, tempRoot);
    let oldMetadata;
    await reporter.step("published baseline metadata", async () => {
      oldMetadata = assertMetadata({
        arch: options.arch,
        expectedVersion: baseline.manifest.version,
        metadataFile: baseline.metadata,
        zipFile: baseline.zip,
      });
      return `${baseline.manifest.version} ${path.basename(baseline.metadata)}`;
    });
    await reporter.step("published baseline download", async () => {
      await assertDownload(oldMetadata, baseline.zip);
      return `${path.basename(baseline.zip)} sha256=${baseline.architecture.zipSha256}`;
    });

    let newMetadata;
    await reporter.step("candidate metadata", async () => {
      newMetadata = assertMetadata({
        arch: options.arch,
        expectedVersion: options.candidateVersion,
        metadataFile: options.candidateMetadata,
        zipFile: options.candidateZip,
      });
      return `${options.candidateVersion} ${path.basename(options.candidateMetadata)}`;
    });
    await reporter.step("candidate download payload", async () => {
      await assertDownload(newMetadata, options.candidateZip);
      return `${path.basename(options.candidateZip)} sha512=${newMetadata.sha512}`;
    });

    const oldExtract = path.join(tempRoot, "old");
    const newExtract = path.join(tempRoot, "new");
    fs.mkdirSync(oldExtract);
    fs.mkdirSync(newExtract);
    command("ditto", ["-x", "-k", baseline.zip, oldExtract]);
    command("ditto", ["-x", "-k", options.candidateZip, newExtract]);
    const oldApp = findSingleApp(oldExtract);
    const newApp = findSingleApp(newExtract);

    let oldSignatureDetails;
    await reporter.step("published baseline generic signature", async () => {
      oldSignatureDetails = assertApp({
        app: oldApp,
        arch: options.arch,
        version: baseline.manifest.version,
      });
      return oldSignatureDetails
        .split("\n")
        .filter((line) =>
          /^(Signature|TeamIdentifier|# designated|designated)/.test(line),
        )
        .join("; ");
    });
    await reporter.step("candidate generic signature", async () => {
      const details = assertApp({
        app: newApp,
        arch: options.arch,
        version: options.candidateVersion,
      });
      return details
        .split("\n")
        .filter((line) =>
          /^(Signature|TeamIdentifier|# designated|designated)/.test(line),
        )
        .join("; ");
    });

    await reporter.step(
      "Squirrel designated requirement authorization",
      async () =>
        verifyCandidateAgainstOldRequirement(oldSignatureDetails, newApp),
    );

    await reporter.step("Squirrel physical install", async () =>
      runShipItInstall({
        oldApp,
        oldVersion: baseline.manifest.version,
        newApp,
        newVersion: options.candidateVersion,
        tempRoot,
      }),
    );
  } finally {
    reporter.summary();
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
};

const isEntrypoint =
  process.argv[1] &&
  path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);

if (isEntrypoint) {
  try {
    await runGate(parseArgs(process.argv.slice(2)));
  } catch (error) {
    console.error(
      `[macos-updater-signature] ERROR ${
        error instanceof Error ? error.message : error
      }`,
    );
    process.exitCode = 1;
  }
}
