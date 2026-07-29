#!/usr/bin/env node

import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { finished } from "node:stream/promises";
import { fileURLToPath } from "node:url";
import {
  loadBaseline,
  runGate,
  UpdaterSignatureGateError,
} from "./verify-macos-updater-signature.mjs";

const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);

const command = (executable, args) => {
  const result = spawnSync(executable, args, {
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
  });
  if (result.error || result.status !== 0) {
    throw new Error(
      `${executable} ${args.join(" ")} failed\n${result.stderr || result.stdout || ""}`,
    );
  }
  return `${result.stdout || ""}${result.stderr || ""}`.trim();
};

const hashFile = async (algorithm, file, encoding) => {
  const hash = createHash(algorithm);
  const input = fs.createReadStream(file);
  input.on("data", (chunk) => hash.update(chunk));
  await finished(input);
  return hash.digest(encoding);
};

const parseOverrides = (argv) => {
  const result = {};
  for (let index = 0; index < argv.length; index += 2) {
    const key = argv[index];
    const value = argv[index + 1];
    if (!key?.startsWith("--") || !value) {
      throw new Error(`invalid argument near ${key ?? "<end>"}`);
    }
    if (key === "--baseline-zip") result.baselineZip = value;
    else if (key === "--baseline-metadata") result.baselineMetadata = value;
    else throw new Error(`unknown argument ${key}`);
  }
  return result;
};

const main = async () => {
  if (process.platform !== "darwin") {
    throw new Error("the updater signature regression requires macOS");
  }
  const overrides = parseOverrides(process.argv.slice(2));
  const scratch = fs.mkdtempSync(
    path.join(os.tmpdir(), "logseq-updater-regression-candidate-"),
  );
  try {
    const baselineManifest = path.join(
      repoRoot,
      "scripts",
      "fixtures",
      "macos-updater-baseline.json",
    );
    const baseline = await loadBaseline(
      {
        arch: "arm64",
        baselineManifest,
        baselineMetadata:
          overrides.baselineMetadata ||
          process.env.LOGSEQ_UPDATER_BASELINE_METADATA,
        baselineZip:
          overrides.baselineZip || process.env.LOGSEQ_UPDATER_BASELINE_ZIP,
      },
      scratch,
    );
    const baselineZip = baseline.zip;
    const extracted = path.join(scratch, "extracted");
    fs.mkdirSync(extracted);
    command("ditto", ["-x", "-k", path.resolve(baselineZip), extracted]);
    const oldApp = path.join(extracted, "Logseq.app");
    if (!fs.existsSync(oldApp)) {
      throw new Error(`${baselineZip} does not contain Logseq.app`);
    }

    const candidateRoot = path.join(scratch, "candidate");
    const candidateApp = path.join(candidateRoot, "Logseq.app");
    fs.mkdirSync(candidateRoot);
    command("ditto", [oldApp, candidateApp]);
    for (const key of ["CFBundleShortVersionString", "CFBundleVersion"]) {
      command("plutil", [
        "-replace",
        key,
        "-string",
        "2.0.1-selfhost.5",
        path.join(candidateApp, "Contents", "Info.plist"),
      ]);
    }
    command("codesign", [
      "--force",
      "--deep",
      "--options",
      "runtime",
      "--timestamp=none",
      "--entitlements",
      path.join(repoRoot, "resources", "entitlements.local-signed.plist"),
      "--sign",
      "-",
      candidateApp,
    ]);

    const candidateZip = path.join(
      scratch,
      "Logseq-darwin-arm64-2.0.1-selfhost.5.zip",
    );
    command("ditto", [
      "-c",
      "-k",
      "--sequesterRsrc",
      "--keepParent",
      candidateApp,
      candidateZip,
    ]);
    const sha512 = await hashFile("sha512", candidateZip, "base64");
    const size = fs.statSync(candidateZip).size;
    const candidateMetadata = path.join(scratch, "latest-mac.yml");
    fs.writeFileSync(
      candidateMetadata,
      [
        "version: 2.0.1-selfhost.5",
        "files:",
        "  - url: Logseq-darwin-arm64-2.0.1-selfhost.5.zip",
        `    sha512: ${sha512}`,
        `    size: ${size}`,
        "path: Logseq-darwin-arm64-2.0.1-selfhost.5.zip",
        `sha512: ${sha512}`,
        "releaseDate: '2026-07-29T00:00:00.000Z'",
        "",
      ].join("\n"),
      "utf8",
    );

    await runGate({
      arch: "arm64",
      candidateMetadata,
      candidateVersion: "2.0.1-selfhost.5",
      candidateZip,
      baselineManifest,
      baselineMetadata: baseline.metadata,
      baselineZip,
    });
  } finally {
    fs.rmSync(scratch, { recursive: true, force: true });
  }
};

try {
  await main();
} catch (error) {
  const message = error instanceof Error ? error.message : String(error);
  const expectedSignatureFailure =
    error instanceof UpdaterSignatureGateError &&
    error.kind === "signature-incompatible" &&
    /code failed to satisfy specified code requirement\(s\)|SQRLCodeSignatureErrorDomain|Code=-67050/.test(
      message,
    );
  if (expectedSignatureFailure) {
    console.error(`[macos-updater-regression] EXPECTED SIGNATURE RED: ${message}`);
    process.exitCode = 1;
  } else {
    console.error(
      `[macos-updater-regression] FIXTURE OR HARNESS ERROR: ${message}`,
    );
    process.exitCode = 2;
  }
}
