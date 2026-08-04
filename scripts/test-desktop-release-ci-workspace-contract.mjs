#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const workflowPath = path.join(
  repoRoot,
  ".github",
  "workflows",
  "build-desktop-release.yml",
);
const workflow = fs.readFileSync(workflowPath, "utf8");

const buildJobs = [
  "build-linux-x64",
  "build-linux-arm64",
  "build-windows-x64",
  "build-windows-arm64",
  "build-macos-x64",
  "build-macos-arm64",
];

const jobSource = (jobName) => {
  const marker = `  ${jobName}:\n`;
  const start = workflow.indexOf(marker);
  assert.notEqual(start, -1, `workflow is missing ${jobName}`);
  const remainder = workflow.slice(start + marker.length);
  const nextJob = remainder.search(/^  [A-Za-z0-9_-]+:\s*$/m);
  return nextJob === -1 ? remainder : remainder.slice(0, nextJob);
};

const stepSource = (source, stepName) => {
  const marker = `      - name: ${stepName}\n`;
  const start = source.indexOf(marker);
  assert.notEqual(start, -1, `job is missing step: ${stepName}`);
  const remainder = source.slice(start + marker.length);
  const nextStep = remainder.search(/^      - name:\s/m);
  return nextStep === -1 ? remainder : remainder.slice(0, nextStep);
};

const sourceBeforeStep = (source, stepName) => {
  const marker = `      - name: ${stepName}\n`;
  const end = source.indexOf(marker);
  assert.notEqual(end, -1, `job is missing step: ${stepName}`);
  return source.slice(0, end);
};

test("the published desktop artifact includes its job-root runtime verifier", () => {
  const compileJob = jobSource("compile-cljs");
  const upload = stepSource(compileJob, "Cache Static File");

  assert.match(upload, /uses:\s*actions\/upload-artifact@v4/);
  assert.match(upload, /name:\s*static\s*$/m);
  assert.match(
    upload,
    /path:\s*\|[\s\S]*^\s+static\s*$[\s\S]*^\s+scripts\/verify-desktop-runtime-revisions\.mjs\s*$/m,
    "the static publication must carry both the application tree and its job-root runtime verifier",
  );
});

for (const jobName of buildJobs) {
  test(`${jobName} restores and checks the published runtime verifier before packaging`, () => {
    const job = jobSource(jobName);
    const download = stepSource(job, "Download The Static Asset");
    assert.match(download, /uses:\s*actions\/download-artifact@v4/);
    assert.match(download, /name:\s*static\s*$/m);
    assert.match(
      download,
      /path:\s*(?:\.|\$\{\{\s*github\.workspace\s*\}\})\s*$/m,
      `${jobName} must restore the multi-root artifact at the job root`,
    );

    const beforeBuild = sourceBeforeStep(
      job,
      jobName.startsWith("build-macos-")
        ? `Build/Release Electron App for ${jobName.endsWith("arm64") ? "arm64" : "x64"}`
        : jobName.startsWith("build-windows-")
          ? "Build/Release signed Electron app"
          : "Build/Release Electron App",
    );
    assert.match(
      beforeBuild,
      /scripts\/verify-desktop-runtime-revisions\.mjs/,
      `${jobName} must prove the downloaded job-root verifier exists before packaging`,
    );
    assert.match(
      beforeBuild,
      /(?:test\s+-f|Test-Path)[^\n]*scripts[\\/]verify-desktop-runtime-revisions\.mjs/i,
      `${jobName} must fail before packaging when its published verifier is absent`,
    );
  });
}

test("all six build jobs bind runtime verification to the frozen exact source SHA", () => {
  for (const jobName of buildJobs) {
    const job = jobSource(jobName);
    assert.match(
      job,
      /LOGSEQ_REVISION:\s*\$\{\{\s*needs\.compile-cljs\.outputs\.source-sha\s*\}\}/,
      `${jobName} must embed the frozen compile source SHA`,
    );
    assert.match(
      job,
      /LOGSEQ_RELEASE_SOURCE_SHA:\s*\$\{\{\s*needs\.compile-cljs\.outputs\.source-sha\s*\}\}/,
      `${jobName} must verify against the same frozen compile source SHA`,
    );
  }
});

for (const arch of ["x64", "arm64"]) {
  test(`macOS ${arch} provisions executable updater tools and a usable Electron.app fixture`, () => {
    const job = jobSource(`build-macos-${arch}`);
    const contractStepName =
      "Run project-signed updater native and provider contracts";
    const beforeContracts = sourceBeforeStep(job, contractStepName);
    const contracts = stepSource(job, contractStepName);
    const effectiveSource = `${beforeContracts}\n${contracts}`;

    assert.match(
      effectiveSource,
      /LOGSEQ_7ZIP/,
      `macOS ${arch} must explicitly select the updater archive executable`,
    );
    assert.match(
      effectiveSource,
      /(?:electron-builder|7zip-bin)/,
      `macOS ${arch} must source 7-Zip from the electron-builder dependency chain`,
    );
    assert.doesNotMatch(
      effectiveSource,
      /(?:command\s+-v|which)\s+(?:7z|7za|7zz)|(?:^|[\s"'])\/(?:usr|opt)\/(?:local\/)?bin\/7z/m,
      `macOS ${arch} must not silently select the runner's system p7zip`,
    );
    assert.match(
      effectiveSource,
      /(?:test\s+-x|\[\s+-x\s+)[^\n]*LOGSEQ_7ZIP/,
      `macOS ${arch} must fail if the selected 7-Zip is not executable`,
    );
    assert.match(
      effectiveSource,
      /["']?\$\{?LOGSEQ_7ZIP\}?["']?\s+(?:i|--help)/,
      `macOS ${arch} must execute a compatibility probe before the updater contract`,
    );

    assert.match(
      effectiveSource,
      /LOGSEQ_ELECTRON_APP_FIXTURE/,
      `macOS ${arch} must explicitly provide the physical App fixture`,
    );
    assert.match(
      effectiveSource,
      /static\/node_modules\/electron\/dist\/Electron\.app/,
      `macOS ${arch} must use the Electron.app installed for the packaged desktop`,
    );
    assert.match(
      effectiveSource,
      /(?:test\s+-f|\[\s+-f\s+)[^\n]*Contents\/Info\.plist/,
      `macOS ${arch} must validate the fixture bundle metadata`,
    );
    assert.match(
      effectiveSource,
      /(?:test\s+-x|\[\s+-x\s+)[^\n]*Contents\/MacOS\/Electron/,
      `macOS ${arch} must validate the fixture executable`,
    );
    assert.match(
      effectiveSource,
      /ELECTRON_RUN_AS_NODE=1[^\n]*Contents\/MacOS\/Electron/,
      `macOS ${arch} must prove the fixture can execute Electron`,
    );
    const expectedExecutableArch = arch === "x64" ? "(?:x64|x86_64)" : "arm64";
    assert.match(
      effectiveSource,
      new RegExp(
        `(?:lipo|file)[^\\n]*Contents/MacOS/Electron[^\\n]*${expectedExecutableArch}`,
      ),
      `macOS ${arch} must prove the fixture contains the requested architecture`,
    );
  });
}
