#!/usr/bin/env node

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
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

const workflowJobs = (() => {
  const starts = [];
  const pattern = /^  ([A-Za-z0-9_-]+):\s*$/gm;
  let match;
  while ((match = pattern.exec(workflow)) !== null) {
    starts.push({ name: match[1], start: match.index });
  }
  return starts.map((entry, index) => ({
    ...entry,
    source: workflow.slice(
      entry.start,
      starts[index + 1]?.start ?? workflow.length,
    ),
  }));
})();

const jobSource = (jobName) => {
  const job = workflowJobs.find((entry) => entry.name === jobName);
  assert.ok(job, `workflow is missing ${jobName}`);
  return job.source;
};

const workflowSteps = (source) => {
  const starts = [];
  const pattern = /^      - /gm;
  let match;
  while ((match = pattern.exec(source)) !== null) starts.push(match.index);
  return starts.map((start, index) => ({
    end: starts[index + 1] ?? source.length,
    source: source.slice(start, starts[index + 1] ?? source.length),
    start,
  }));
};

const staticArtifactConsumers = workflowJobs.flatMap((job) => {
  const steps = workflowSteps(job.source);
  const download = steps.find(
    (step) =>
      /uses:\s*actions\/download-artifact@v4/.test(step.source) &&
      /^\s{10}name:\s*static\s*$/m.test(step.source),
  );
  return download ? [{ ...job, download, steps }] : [];
});
const staticArtifactBuilderConsumers = staticArtifactConsumers.filter(
  (consumer) => buildJobs.includes(consumer.name),
);
const staticArtifactSnapConsumers = staticArtifactConsumers.filter(
  (consumer) => /\belectron:publish-snap\b/.test(consumer.source),
);

const setting = (source, key) =>
  source.match(new RegExp(`^\\s{8,10}${key}:\\s*(.+?)\\s*$`, "m"))?.[1];

const normalizeWorkspacePath = (value) => {
  const unquoted = value
    .trim()
    .replace(/^(?:"([\s\S]*)"|'([\s\S]*)')$/, "$1$2")
    .replaceAll("\\\\", "/");
  const workspaceRelative = unquoted.replace(
    /^\$\{\{\s*github\.workspace\s*\}\}\/?/,
    "",
  );
  return workspaceRelative
    .replace(/^\.\//, "")
    .replace(/^\.$/, "")
    .replace(/\/$/, "");
};

const joinWorkspacePath = (...parts) =>
  parts.filter(Boolean).join("/");

const escapeRegExp = (value) =>
  value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

const hasRegularFileCheck = (
  steps,
  { file, root, workingDirectory },
) => {
  const explicitPath = joinWorkspacePath(root, file);
  const explicitPattern = new RegExp(
    `(?:test\\s+-f|Test-Path)[^\\n]*${escapeRegExp(explicitPath)}`,
    "i",
  );
  const localPattern = new RegExp(
    `(?:test\\s+-f|Test-Path)[^\\n]*${escapeRegExp(file)}`,
    "i",
  );
  return steps.some((step) => {
    const normalizedSource = step.source.replaceAll("\\\\", "/");
    if (explicitPattern.test(normalizedSource)) return true;
    const stepWorkingDirectory = setting(
      step.source,
      "working-directory",
    );
    return (
      stepWorkingDirectory !== undefined &&
      normalizeWorkspacePath(stepWorkingDirectory) === workingDirectory &&
      localPattern.test(normalizedSource)
    );
  });
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

assert.ok(
  staticArtifactConsumers.length > 0,
  "workflow must expose at least one consumer of the static artifact",
);
assert.deepEqual(
  staticArtifactBuilderConsumers.map((consumer) => consumer.name).sort(),
  [...buildJobs].sort(),
  "the six desktop builders must remain static artifact consumers",
);
assert.ok(
  staticArtifactSnapConsumers.length > 0,
  "every Snap publishing job must be discovered by artifact usage, not a fixed consumer-name list",
);

for (const consumer of staticArtifactConsumers) {
  test(`${consumer.name} restores the multi-root static artifact at its declared package root`, () => {
    const downloadPath = setting(consumer.download.source, "path");
    assert.ok(
      downloadPath,
      `${consumer.name} must declare an explicit static artifact download path`,
    );
    const artifactRoot = normalizeWorkspacePath(downloadPath);
    const expectedPackageRoot = joinWorkspacePath(artifactRoot, "static");
    const packageSteps = consumer.steps.filter(
      (step) =>
        step.start > consumer.download.start &&
        /\bpnpm\b/.test(step.source) &&
        setting(step.source, "working-directory") !== undefined &&
        normalizeWorkspacePath(
          setting(step.source, "working-directory"),
        ).endsWith("static"),
    );
    assert.ok(
      packageSteps.length > 0,
      `${consumer.name} downloads static but never consumes its package tree`,
    );
    for (const step of packageSteps) {
      const actualPackageRoot = normalizeWorkspacePath(
        setting(step.source, "working-directory"),
      );
      assert.equal(
        actualPackageRoot,
        expectedPackageRoot,
        `${consumer.name} downloads the multi-root artifact at ${downloadPath}, so pnpm must run from ${expectedPackageRoot || "."}, not ${actualPackageRoot || "."}`,
      );
    }

    const beforeFirstPackageStep = consumer.steps.filter(
      (step) => step.end <= packageSteps[0].start,
    );
    for (const file of [
      "package.json",
      "pnpm-lock.yaml",
      "electron-builder.yml",
    ]) {
      assert.ok(
        hasRegularFileCheck(beforeFirstPackageStep, {
          file,
          root: expectedPackageRoot,
          workingDirectory: expectedPackageRoot,
        }),
        `${consumer.name} must fail before pnpm when ${expectedPackageRoot}/${file} is absent`,
      );
    }
    assert.ok(
      hasRegularFileCheck(beforeFirstPackageStep, {
        file: "scripts/verify-desktop-runtime-revisions.mjs",
        root: artifactRoot,
        workingDirectory: artifactRoot,
      }),
      `${consumer.name} must fail before pnpm when its artifact-root runtime verifier is absent`,
    );
  });
}

test("the six builder consumers bind runtime verification to the frozen exact source SHA", () => {
  for (const consumer of staticArtifactBuilderConsumers) {
    const job = consumer.source;
    assert.match(
      job,
      /LOGSEQ_REVISION:\s*\$\{\{\s*needs\.compile-cljs\.outputs\.source-sha\s*\}\}/,
      `${consumer.name} must embed the frozen compile source SHA`,
    );
    assert.match(
      job,
      /LOGSEQ_RELEASE_SOURCE_SHA:\s*\$\{\{\s*needs\.compile-cljs\.outputs\.source-sha\s*\}\}/,
      `${consumer.name} must verify against the same frozen compile source SHA`,
    );
  }
});

for (const consumer of staticArtifactSnapConsumers) {
  test(`${consumer.name} remains a guarded Snap publisher rather than a seventh desktop builder`, () => {
    assert.match(
      consumer.source,
      /github\.event\.inputs\.publish-linux-stores\s*==\s*'true'/,
      `${consumer.name} must remain explicitly opted in`,
    );
    assert.match(
      consumer.source,
      /SNAPCRAFT_STORE_CREDENTIALS:\s*\$\{\{\s*secrets\.SNAPCRAFT_STORE_CREDENTIALS\s*\}\}/,
      `${consumer.name} must source Snap credentials from GitHub secrets`,
    );
    assert.match(
      consumer.source,
      /BUILD_TARGET:\s*\$\{\{\s*github\.event\.inputs\.build-target\s*\}\}/,
      `${consumer.name} must preserve the selected stable or beta channel`,
    );
    assert.doesNotMatch(
      consumer.source,
      /- name:\s*Verify packaged desktop/,
      `${consumer.name} must not masquerade as one of the six packaged-desktop verification roles`,
    );
  });
}

test("quick preflight accepts all artifact consumers without treating Snap as a seventh builder", () => {
  const result = spawnSync(
    process.execPath,
    [path.join(repoRoot, "scripts", "desktop-release-preflight.mjs")],
    {
      cwd: repoRoot,
      encoding: "utf8",
      env: process.env,
      shell: false,
    },
  );
  assert.equal(
    result.status,
    0,
    `quick preflight rejected the role-aware consumer set:\n${result.stdout}\n${result.stderr}`,
  );
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
