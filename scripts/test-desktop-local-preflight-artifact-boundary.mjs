#!/usr/bin/env node

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const defaultRepoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const repoRoot = process.env.LOGSEQ_TEST_DESKTOP_PREFLIGHT_ROOT
  ? fs.realpathSync(process.env.LOGSEQ_TEST_DESKTOP_PREFLIGHT_ROOT)
  : defaultRepoRoot;
const preflightPath = path.join(
  repoRoot,
  "scripts",
  "run-desktop-release-preflight.mjs",
);
const preflightSource = fs.readFileSync(preflightPath, "utf8");
const invalidButWellFormedSha =
  "499b5dcc9cbb65579140707eff3745fd9432777b";

const escapeRegExp = (value) =>
  value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

const gitHead = () => {
  const result = spawnSync("git", ["rev-parse", "HEAD"], {
    cwd: repoRoot,
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
  });
  assert.equal(result.status, 0, result.stderr);
  const head = result.stdout.trim();
  assert.match(head, /^[0-9a-f]{40}$/);
  return head;
};

const createFirstExternalCommandProbe = () => {
  const root = fs.mkdtempSync(
    path.join(os.tmpdir(), "desktop-preflight-source-guard-"),
  );
  const fakeBin = path.join(root, "bin");
  const marker = path.join(root, "external-command-reached");
  fs.mkdirSync(fakeBin);
  const opam = path.join(fakeBin, "opam");
  fs.writeFileSync(
    opam,
    `#!/bin/sh
printf '%s\\n' "$*" >> "$PREFLIGHT_EXTERNAL_MARKER"
exit 91
`,
  );
  fs.chmodSync(opam, 0o755);

  const run = ({ releaseSourceSha, revision }) => {
    fs.rmSync(marker, { force: true });
    const env = {
      ...process.env,
      PATH: `${fakeBin}${path.delimiter}${process.env.PATH ?? ""}`,
      PREFLIGHT_EXTERNAL_MARKER: marker,
    };
    delete env.LOGSEQ_RELEASE_SOURCE_SHA;
    delete env.LOGSEQ_REVISION;
    if (releaseSourceSha !== undefined) {
      env.LOGSEQ_RELEASE_SOURCE_SHA = releaseSourceSha;
    }
    if (revision !== undefined) env.LOGSEQ_REVISION = revision;
    const result = spawnSync(
      process.execPath,
      [preflightPath, "--allow-dirty"],
      {
        cwd: repoRoot,
        encoding: "utf8",
        env,
        stdio: ["ignore", "pipe", "pipe"],
        timeout: 30_000,
      },
    );
    if (result.error) throw result.error;
    return {
      externalCommandReached: fs.existsSync(marker),
      output: `${result.stdout ?? ""}${result.stderr ?? ""}`,
      status: result.status,
    };
  };

  return {
    dispose: () => fs.rmSync(root, { force: true, recursive: true }),
    run,
  };
};

const packagingViolations = (source) => {
  const violations = [];
  const artifactRootMatch = source.match(
    /(?:const|let)\s+([A-Za-z_$][\w$]*)\s*=\s*fs\.mkdtempSync\(\s*path\.join\(\s*os\.tmpdir\(\),\s*["'][^"']*(?:artifact|packag)[^"']*["']/i,
  );
  if (!artifactRootMatch) {
    return ["final packaging must create a temporary CI-shaped artifact root"];
  }
  const artifactRoot = artifactRootMatch[1];
  const rootPattern = escapeRegExp(artifactRoot);
  const artifactStaticMatch = source.match(
    new RegExp(
      `(?:const|let)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*path\\.join\\(\\s*${rootPattern}\\s*,\\s*["']static["']\\s*\\)`,
    ),
  );
  if (!artifactStaticMatch) {
    return ["artifact root must expose its own static package root"];
  }
  const artifactStatic = artifactStaticMatch[1];
  const staticPattern = escapeRegExp(artifactStatic);
  const stagingStart = artifactRootMatch.index;
  const installStart = source.indexOf('"isolated packaging install"', stagingStart);
  if (installStart === -1) {
    violations.push("isolated packaging install is missing");
    return violations;
  }
  const staging = source.slice(stagingStart, installStart);
  const packaging = source.slice(installStart);

  if (
    !new RegExp(
      `fs\\.cpSync\\(\\s*staticDir\\s*,\\s*${staticPattern}`,
    ).test(staging)
  ) {
    violations.push("source static must be copied into artifact static");
  }
  const excludesNodeModules =
    /filter\s*:[\s\S]{0,400}node_modules/.test(staging) ||
    new RegExp(
      `fs\\.rmSync\\(\\s*path\\.join\\(\\s*${staticPattern}\\s*,\\s*["']node_modules["']`,
    ).test(staging);
  if (!excludesNodeModules) {
    violations.push("artifact static copy must exclude source node_modules");
  }

  for (const relativePath of [
    "scripts/verify-desktop-runtime-revisions.mjs",
    "dist/db-worker-node.js",
  ]) {
    if (
      !staging.includes(relativePath) ||
      !/(?:copyFileSync|cpSync)\s*\(/.test(staging)
    ) {
      violations.push(`artifact staging must copy exact input ${relativePath}`);
    }
  }
  if (!/(?:lstatSync|statSync)[\s\S]{0,900}\.isFile\(\)/.test(staging)) {
    violations.push("artifact inputs must be verified as regular files");
  }

  const installUsesArtifact = new RegExp(
    `["']--dir["']\\s*,\\s*${staticPattern}[\\s\\S]{0,220}["']install["'][\\s\\S]{0,300}["']--frozen-lockfile["'][\\s\\S]{0,220}["']--ignore-workspace["']`,
  ).test(packaging);
  if (!installUsesArtifact) {
    violations.push("artifact package root needs a frozen isolated install");
  }
  if (
    !new RegExp(
      `["']--dir["']\\s*,\\s*${staticPattern}[\\s\\S]{0,220}["']rebuild:all["']`,
    ).test(packaging)
  ) {
    violations.push("native rebuild must run inside artifact static");
  }
  if (
    !new RegExp(
      `["']--dir["']\\s*,\\s*${staticPattern}[\\s\\S]{0,500}(?:electron-builder|electron:make-unsigned)`,
    ).test(packaging)
  ) {
    violations.push("host electron-builder must run from artifact static");
  }
  if (
    !new RegExp(
      `path\\.join\\(\\s*${staticPattern}\\s*,\\s*["']verify-packaged-desktop\\.mjs["']`,
    ).test(packaging) ||
    !/verify host packaged application/.test(packaging)
  ) {
    violations.push("real packaged verifier must come from artifact static");
  }
  if (
    !packaging.includes("LOGSEQ_REVISION") ||
    !packaging.includes("LOGSEQ_RELEASE_SOURCE_SHA")
  ) {
    violations.push("packaging and verifier must receive exact release revisions");
  }
  if (
    /(?:--dir["']?\s*,?\s*["']static["']|staticDir[\s\S]{0,120}(?:rebuild:all|electron-builder|electron:make-unsigned))/.test(
      packaging,
    )
  ) {
    violations.push("final packaging must not reuse source static");
  }
  if (
    /(?:repoRoot|staticDir|\.\.\/)[\s\S]{0,100}node_modules|NODE_PATH/.test(
      packaging,
    )
  ) {
    violations.push("final packaging must not resolve source node_modules");
  }
  if (
    /(?:unsafe-path|disable[^\n]*safe|ELECTRON_BUILDER_[A-Z_]*UNSAFE|\|\|\s*true|continue-on-error|set\s+\+e)/i.test(
      packaging,
    )
  ) {
    violations.push("packaging must not weaken safety or failure propagation");
  }
  if (!/if \(result\.status !== 0\)[\s\S]{0,160}throw new Error/.test(source)) {
    violations.push("child command failures must propagate");
  }
  for (const required of [
    "process.platform === \"darwin\"",
    "process.platform === \"win32\"",
    "process.platform === \"linux\"",
    "codesign",
    "hdiutil",
  ]) {
    if (!packaging.includes(required)) {
      violations.push(`host packaging verification must retain ${required}`);
    }
  }
  const finallyIndex = packaging.indexOf("finally {");
  if (finallyIndex === -1) {
    violations.push("temporary artifact cleanup must be in finally");
  } else {
    const cleanup = packaging.slice(finallyIndex);
    if (
      !new RegExp(
        `fs\\.rmSync\\(\\s*${rootPattern}[\\s\\S]{0,180}recursive\\s*:\\s*true`,
      ).test(cleanup)
    ) {
      violations.push("finally must recursively remove the artifact root");
    }
    const outputWithinArtifact = new RegExp(
      `(?:const|let)\\s+[A-Za-z_$][\\w$]*\\s*=\\s*path\\.join\\(\\s*${rootPattern}`,
    ).test(staging);
    if (
      !outputWithinArtifact &&
      !/fs\.rmSync\(\s*outputDir[\s\S]{0,180}recursive\s*:\s*true/.test(
        cleanup,
      )
    ) {
      violations.push("finally must remove the temporary packaging output");
    }
  }
  return violations;
};

const compliantPackagingFixture = `
const artifactRoot = fs.mkdtempSync(path.join(os.tmpdir(), "release-artifact-"));
const artifactStatic = path.join(artifactRoot, "static");
const outputDir = path.join(artifactRoot, "output");
fs.cpSync(staticDir, artifactStatic, {
  recursive: true,
  filter: (source) => path.basename(source) !== "node_modules",
});
for (const relativePath of [
  "scripts/verify-desktop-runtime-revisions.mjs",
  "dist/db-worker-node.js",
]) {
  fs.copyFileSync(path.join(repoRoot, relativePath), path.join(artifactRoot, relativePath));
}
const inputStats = fs.lstatSync(path.join(artifactRoot, "dist/db-worker-node.js"));
if (!inputStats.isFile()) throw new Error("artifact input is not a file");
try {
  pnpm("isolated packaging install", ["--dir", artifactStatic, "install", "--frozen-lockfile", "--ignore-workspace"], { env: { LOGSEQ_REVISION, LOGSEQ_RELEASE_SOURCE_SHA } });
  pnpm("rebuild desktop native modules", ["--dir", artifactStatic, "rebuild:all"], { env: { LOGSEQ_REVISION, LOGSEQ_RELEASE_SOURCE_SHA } });
  if (process.platform === "darwin") pnpm("package", ["--dir", artifactStatic, "electron:make-unsigned"]);
  else if (process.platform === "win32") pnpm("package", ["--dir", artifactStatic, "exec", "electron-builder"]);
  else if (process.platform === "linux") pnpm("package", ["--dir", artifactStatic, "exec", "electron-builder"]);
  run("verify host packaged application", process.execPath, [path.join(artifactStatic, "verify-packaged-desktop.mjs"), "--search-root", outputDir], { env: { LOGSEQ_REVISION, LOGSEQ_RELEASE_SOURCE_SHA } });
  run("verify macOS bundle signature", "codesign", []);
  run("verify macOS DMG", "hdiutil", []);
} finally {
  fs.rmSync(artifactRoot, { recursive: true, force: true });
}
if (result.status !== 0) { throw new Error("child failed"); }
`;

test("local preflight validates exact source revision before external build commands", () => {
  const head = gitHead();
  const otherSha = "8".repeat(40);
  const probe = createFirstExternalCommandProbe();
  try {
    const invalidCases = [
      [
        `nonexistent but well-formed source ${invalidButWellFormedSha}`,
        {
          releaseSourceSha: invalidButWellFormedSha,
          revision: invalidButWellFormedSha,
        },
      ],
      ["missing release source", { revision: head }],
      ["missing revision", { releaseSourceSha: head }],
      ["malformed source", { releaseSourceSha: "499b5dcc9cbb", revision: head }],
      ["revision differs from HEAD", { releaseSourceSha: head, revision: otherSha }],
      ["source differs from HEAD", { releaseSourceSha: otherSha, revision: head }],
    ];
    for (const [label, environment] of invalidCases) {
      const result = probe.run(environment);
      assert.equal(
        result.externalCommandReached,
        false,
        `${label} reached the first external build command:\n${result.output}`,
      );
      assert.notEqual(result.status, 0, `${label} unexpectedly passed preflight`);
      assert.match(
        result.output,
        /LOGSEQ_RELEASE_SOURCE_SHA|LOGSEQ_REVISION|HEAD|release source|revision/i,
        `${label} failed without identifying the source binding`,
      );
    }

    const matching = probe.run({
      releaseSourceSha: head,
      revision: head,
    });
    assert.equal(
      matching.externalCommandReached,
      true,
      `exact HEAD binding was rejected before the external-command control:\n${matching.output}`,
    );
  } finally {
    probe.dispose();
  }
});

test("final local packaging consumes only a CI-shaped artifact root", () => {
  assert.deepEqual(packagingViolations(preflightSource), []);
});

test("packaging boundary analyzer rejects unsafe and incomplete mutations", () => {
  assert.deepEqual(packagingViolations(compliantPackagingFixture), []);
  const mutations = [
    compliantPackagingFixture.replace(
      'filter: (source) => path.basename(source) !== "node_modules",',
      "filter: () => true,",
    ),
    compliantPackagingFixture.replace(
      '"scripts/verify-desktop-runtime-revisions.mjs",',
      "",
    ),
    compliantPackagingFixture.replaceAll('"dist/db-worker-node.js"', '"missing-db-worker.js"'),
    compliantPackagingFixture.replace('"--frozen-lockfile",', ""),
    compliantPackagingFixture.replaceAll(
      '["--dir", artifactStatic',
      '["--dir", "static"',
    ),
    compliantPackagingFixture.replace(
      'path.join(artifactStatic, "verify-packaged-desktop.mjs")',
      'path.join(staticDir, "verify-packaged-desktop.mjs")',
    ),
    compliantPackagingFixture.replaceAll(
      "LOGSEQ_RELEASE_SOURCE_SHA",
      "UNBOUND_RELEASE_SOURCE",
    ),
    compliantPackagingFixture.replace(
      'pnpm("package", ["--dir", artifactStatic, "exec", "electron-builder"]);',
      'pnpm("package", ["--dir", artifactStatic, "exec", "electron-builder", "--unsafe-path"]);',
    ),
    compliantPackagingFixture.replace(
      'if (result.status !== 0) { throw new Error("child failed"); }',
      "",
    ),
    compliantPackagingFixture.replace(
      "fs.rmSync(artifactRoot, { recursive: true, force: true });",
      "",
    ),
  ];
  for (const [index, mutation] of mutations.entries()) {
    assert.notDeepEqual(
      packagingViolations(mutation),
      [],
      `unsafe packaging mutation ${index + 1} unexpectedly satisfied the boundary`,
    );
  }
});
