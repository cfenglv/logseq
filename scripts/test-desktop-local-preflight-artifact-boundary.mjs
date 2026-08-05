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

const createFullPreflightFixture = () => {
  const root = fs.mkdtempSync(
    path.join(os.tmpdir(), "desktop-preflight-integrity-"),
  );
  const fakeBin = path.join(root, "fake-bin");
  const write = (relativePath, contents, mode) => {
    const destination = path.join(root, relativePath);
    fs.mkdirSync(path.dirname(destination), { recursive: true });
    fs.writeFileSync(destination, contents);
    if (mode) fs.chmodSync(destination, mode);
  };
  fs.copyFileSync(
    preflightPath,
    path.join(root, "run-desktop-release-preflight.mjs"),
  );
  fs.mkdirSync(path.join(root, "scripts"), { recursive: true });
  fs.renameSync(
    path.join(root, "run-desktop-release-preflight.mjs"),
    path.join(root, "scripts", "run-desktop-release-preflight.mjs"),
  );
  write("scripts/desktop-release-preflight.mjs", "process.exit(0);\n");
  write("scripts/test-cli-release-config.mjs", "process.exit(0);\n");
  write(
    "scripts/build-project-update-helper.mjs",
    `import fs from "node:fs";
import path from "node:path";
const outputIndex = process.argv.indexOf("--output");
const output = process.argv[outputIndex + 1];
fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, "fixture helper");
fs.chmodSync(output, 0o755);
`,
  );
  write(
    "scripts/verify-desktop-runtime-revisions.mjs",
    "process.exit(0);\n",
  );
  write("scripts/fixtures/electron-test-preload.cjs", "// fixture\n");
  write(
    "src/main/frontend/version.cljs",
    '(ns frontend.version)\n(defonce version "2.0.1-test")\n',
  );
  write("resources/package.json", '{"name":"fixture","version":"1.0.0"}\n');
  write("resources/pnpm-lock.yaml", "lockfileVersion: '9.0'\n");
  write("static/tests.js", "process.exit(0);\n");
  write("static/db-sync-backup-memory-test.js", "process.exit(0);\n");
  write("static/verify-packaged-desktop.mjs", "process.exit(0);\n");
  write("dist/db-worker-node.js", "// fixture\n");
  write("cli/.keep", "");
  write("deps/db-sync/worker/.keep", "");

  const shim = `#!/bin/sh
set -eu
if [ -n "\${PREFLIGHT_MUTATION_MODE:-}" ] && [ ! -e "$PREFLIGHT_MUTATION_MARKER" ]; then
  : > "$PREFLIGHT_MUTATION_MARKER"
  printf '\n; mutation\n' >> "$PREFLIGHT_FIXTURE_ROOT/src/main/frontend/version.cljs"
  if [ "$PREFLIGHT_MUTATION_MODE" = "head" ]; then
    git -C "$PREFLIGHT_FIXTURE_ROOT" add src/main/frontend/version.cljs
    git -C "$PREFLIGHT_FIXTURE_ROOT" commit --quiet -m mutation
  fi
fi
for argument in "$@"; do
  case "$argument" in
    -c.directories.output=*)
      output_dir="\${argument#-c.directories.output=}"
      mkdir -p "$output_dir"
      : > "$output_dir/fixture.dmg"
      ;;
  esac
done
exit 0
`;
  for (const name of ["clojure", "codesign", "hdiutil", "opam", "pnpm"]) {
    write(path.join("fake-bin", name), shim, 0o755);
  }

  const git = (...args) => {
    const result = spawnSync("git", args, {
      cwd: root,
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    });
    assert.equal(result.status, 0, result.stderr);
    return result.stdout.trim();
  };
  git("init", "--quiet");
  git("config", "user.email", "preflight-fixture@example.invalid");
  git("config", "user.name", "Preflight Fixture");
  git("add", ".");
  git("commit", "--quiet", "-m", "fixture");
  const head = git("rev-parse", "HEAD");

  const run = (options = {}) => {
    const mutationMode = options.mutationMode;
    const releaseSourceSha = Object.hasOwn(options, "releaseSourceSha")
      ? options.releaseSourceSha
      : head;
    const revision = Object.hasOwn(options, "revision")
      ? options.revision
      : head;
    const marker = path.join(root, "mutation-reached");
    fs.rmSync(marker, { force: true });
    const env = {
      ...process.env,
      PATH: `${fakeBin}${path.delimiter}${process.env.PATH ?? ""}`,
      PREFLIGHT_FIXTURE_ROOT: root,
      PREFLIGHT_MUTATION_MARKER: marker,
    };
    delete env.LOGSEQ_RELEASE_SOURCE_SHA;
    delete env.LOGSEQ_REVISION;
    delete env.PREFLIGHT_MUTATION_MODE;
    if (releaseSourceSha !== undefined) {
      env.LOGSEQ_RELEASE_SOURCE_SHA = releaseSourceSha;
    }
    if (revision !== undefined) env.LOGSEQ_REVISION = revision;
    if (mutationMode) env.PREFLIGHT_MUTATION_MODE = mutationMode;
    const result = spawnSync(
      process.execPath,
      [path.join(root, "scripts", "run-desktop-release-preflight.mjs"), "--allow-dirty"],
      {
        cwd: root,
        encoding: "utf8",
        env,
        stdio: ["ignore", "pipe", "pipe"],
        timeout: 30_000,
      },
    );
    if (result.error) throw result.error;
    return {
      markerReached: fs.existsSync(marker),
      output: `${result.stdout ?? ""}${result.stderr ?? ""}`,
      status: result.status,
    };
  };

  return {
    dispose: () => fs.rmSync(root, { force: true, recursive: true }),
    head,
    run,
  };
};

const packagingViolations = (source) => {
  const violations = [];
  const invocationContaining = (index, functionName) => {
    if (index === -1) return "";
    const start = source.lastIndexOf(`${functionName}(`, index);
    if (start === -1) return "";
    const end = source.indexOf(");", index);
    return end === -1 ? source.slice(start) : source.slice(start, end + 2);
  };
  const labelIndex = (label) => {
    const match = new RegExp(`["']${escapeRegExp(label)}["']`).exec(source);
    return match?.index ?? -1;
  };
  const commandUsesRoot = (call, root) => {
    const rootPattern = escapeRegExp(root);
    return (
      new RegExp(`["']--dir["']\\s*,\\s*${rootPattern}`).test(call) ||
      new RegExp(`cwd\\s*:\\s*${rootPattern}`).test(call)
    );
  };

  const tempRoots = [
    ...source.matchAll(
      /(?:(?:const|let)\s+)?([A-Za-z_$][\w$]*)\s*=\s*fs\.mkdtempSync\(\s*path\.join\(\s*os\.tmpdir\(\),/g,
    ),
  ].map((match) => ({ index: match.index, name: match[1] }));
  if (tempRoots.length === 0) {
    return ["final packaging must create a temporary release workspace"];
  }

  const installStart = labelIndex("isolated packaging install");
  if (installStart === -1) return ["isolated packaging install is missing"];
  const installCall = invocationContaining(installStart, "pnpm");
  const packageRoots = tempRoots.flatMap((root) => {
    const rootPattern = escapeRegExp(root.name);
    const match = new RegExp(
      `(?:const|let)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*path\\.join\\(\\s*${rootPattern}\\s*,\\s*["']static["']\\s*\\)`,
    ).exec(source);
    return match
      ? [{ index: match.index, root: root.name, static: match[1] }]
      : [];
  });
  const packageRoot = packageRoots.find(({ static: staticRoot }) =>
    commandUsesRoot(installCall, staticRoot),
  );
  if (!packageRoot) {
    return [
      "isolated packaging install must run in a temporary workspace static root",
    ];
  }
  const workspaceRoot = packageRoot.root;
  const artifactStatic = packageRoot.static;
  const rootPattern = escapeRegExp(workspaceRoot);
  const staticPattern = escapeRegExp(artifactStatic);
  const stagingStart = tempRoots.find(
    ({ name }) => name === workspaceRoot,
  ).index;
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
    `["']install["'][\\s\\S]{0,300}["']--frozen-lockfile["'][\\s\\S]{0,220}["']--ignore-workspace["']`,
  ).test(installCall);
  if (!installUsesArtifact) {
    violations.push("artifact package root needs a frozen isolated install");
  }
  const rebuildStart = labelIndex("rebuild desktop native modules");
  const rebuildCall = invocationContaining(rebuildStart, "pnpm");
  if (
    !rebuildCall.includes("rebuild:all") ||
    !commandUsesRoot(rebuildCall, artifactStatic)
  ) {
    violations.push("native rebuild must run inside artifact static");
  }
  const packageCalls = [...packaging.matchAll(/pnpm\([\s\S]*?\);/g)]
    .map((match) => match[0])
    .filter((call) => /electron-builder|electron:make-unsigned/.test(call));
  if (
    packageCalls.length === 0 ||
    packageCalls.some((call) => !commandUsesRoot(call, artifactStatic))
  ) {
    violations.push("host electron-builder must run from artifact static");
  }
  const verifierStart = labelIndex("verify host packaged application");
  const verifierCall = invocationContaining(verifierStart, "run");
  const verifierUsesArtifactStatic =
    new RegExp(
      `path\\.join\\(\\s*${staticPattern}\\s*,\\s*["']verify-packaged-desktop\\.mjs["']`,
    ).test(verifierCall) ||
    (verifierCall.includes("verify-packaged-desktop.mjs") &&
      commandUsesRoot(verifierCall, artifactStatic));
  if (!verifierCall || !verifierUsesArtifactStatic) {
    violations.push("real packaged verifier must come from artifact static");
  }
  if (
    [installCall, rebuildCall, ...packageCalls, verifierCall].some((call) =>
      /(?:--dir["']?\s*,?\s*["']static["']|cwd\s*:\s*staticDir|staticDir[\s\S]{0,120}(?:rebuild:all|electron-builder|electron:make-unsigned|verify-packaged-desktop))/.test(
        call,
      ),
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
  const finallyIndex = source.lastIndexOf("finally {");
  if (finallyIndex === -1) {
    violations.push("temporary workspace cleanup must be in finally");
  } else {
    const cleanup = source.slice(finallyIndex);
    if (
      !new RegExp(
        `fs\\.rmSync\\(\\s*${rootPattern}[\\s\\S]{0,180}recursive\\s*:\\s*true`,
      ).test(cleanup)
    ) {
      violations.push("finally must recursively remove the temporary workspace");
    }
    for (const tempRoot of tempRoots) {
      if (tempRoot.name === workspaceRoot) continue;
      const tempPattern = escapeRegExp(tempRoot.name);
      const retainedOutput =
        new RegExp(
          `["']--search-root["'][\\s\\S]{0,180}${tempPattern}`,
        ).test(packaging) &&
        new RegExp(`console\\.log[\\s\\S]{0,600}${tempPattern}`).test(
          source,
        );
      if (
        !retainedOutput &&
        !new RegExp(
          `fs\\.rmSync\\(\\s*${tempPattern}[\\s\\S]{0,180}recursive\\s*:\\s*true`,
        ).test(cleanup)
      ) {
        violations.push(
          `finally must recursively remove temporary helper root ${tempRoot.name}`,
        );
      }
    }
  }
  return violations;
};

const compliantDirPackagingFixture = `
const releaseWorkspace = fs.mkdtempSync(path.join(os.tmpdir(), "ws-"));
const releaseStatic = path.join(releaseWorkspace, "static");
const outputDir = path.join(releaseWorkspace, "output");
fs.cpSync(staticDir, releaseStatic, {
  recursive: true,
  filter: (source) => path.basename(source) !== "node_modules",
});
for (const relativePath of [
  "scripts/verify-desktop-runtime-revisions.mjs",
  "dist/db-worker-node.js",
]) {
  fs.copyFileSync(path.join(repoRoot, relativePath), path.join(releaseWorkspace, relativePath));
}
const inputStats = fs.lstatSync(path.join(releaseWorkspace, "dist/db-worker-node.js"));
if (!inputStats.isFile()) throw new Error("artifact input is not a file");
try {
  pnpm("isolated packaging install", ["--dir", releaseStatic, "install", "--frozen-lockfile", "--ignore-workspace"]);
  pnpm("rebuild desktop native modules", ["--dir", releaseStatic, "rebuild:all"]);
  if (process.platform === "darwin") pnpm("package", ["--dir", releaseStatic, "electron:make-unsigned"]);
  else if (process.platform === "win32") pnpm("package", ["--dir", releaseStatic, "exec", "electron-builder"]);
  else if (process.platform === "linux") pnpm("package", ["--dir", releaseStatic, "exec", "electron-builder"]);
  run("verify host packaged application", process.execPath, [path.join(releaseStatic, "verify-packaged-desktop.mjs"), "--search-root", outputDir]);
  run("verify macOS bundle signature", "codesign", []);
  run("verify macOS DMG", "hdiutil", []);
} finally {
  fs.rmSync(releaseWorkspace, { recursive: true, force: true });
}
if (result.status !== 0) { throw new Error("child failed"); }
`;

const compliantCwdPackagingFixture = `
const scratch = fs.mkdtempSync(path.join(os.tmpdir(), "x-"));
const isolatedStatic = path.join(scratch, "static");
const retainedOutput = fs.mkdtempSync(path.join(os.tmpdir(), "result-"));
let helperRoot;
helperRoot = fs.mkdtempSync(path.join(os.tmpdir(), "h-"));
fs.cpSync(staticDir, isolatedStatic, {
  recursive: true,
  filter: (source) => path.basename(source) !== "node_modules",
});
for (const relativePath of [
  "scripts/verify-desktop-runtime-revisions.mjs",
  "dist/db-worker-node.js",
]) {
  fs.copyFileSync(path.join(repoRoot, relativePath), path.join(scratch, relativePath));
}
const inputStats = fs.statSync(path.join(scratch, "dist/db-worker-node.js"));
if (!inputStats.isFile()) throw new Error("artifact input is not a file");
try {
  pnpm("isolated packaging install", ["install", "--frozen-lockfile", "--ignore-workspace"], { cwd: isolatedStatic });
  pnpm("rebuild desktop native modules", ["rebuild:all"], { cwd: isolatedStatic });
  if (process.platform === "darwin") pnpm("package", ["electron:make-unsigned"], { cwd: isolatedStatic });
  else if (process.platform === "win32") pnpm("package", ["exec", "electron-builder"], { cwd: isolatedStatic });
  else if (process.platform === "linux") pnpm("package", ["exec", "electron-builder"], { cwd: isolatedStatic });
  run("verify host packaged application", process.execPath, ["verify-packaged-desktop.mjs", "--search-root", retainedOutput], { cwd: isolatedStatic });
  run("verify macOS bundle signature", "codesign", []);
  run("verify macOS DMG", "hdiutil", []);
} finally {
  fs.rmSync(scratch, { recursive: true, force: true });
  fs.rmSync(helperRoot, { recursive: true, force: true });
}
console.log("packaged output retained at " + retainedOutput);
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

test("documented bare full preflight binds its exact starting revision", () => {
  const fixture = createFullPreflightFixture();
  try {
    const result = fixture.run({
      releaseSourceSha: undefined,
      revision: undefined,
    });
    assert.equal(
      result.status,
      0,
      `documented bare preflight command failed:\n${result.output}`,
    );
  } finally {
    fixture.dispose();
  }
});

test("full preflight integrity fixture reaches its packaging success control", () => {
  const fixture = createFullPreflightFixture();
  try {
    const result = fixture.run();
    assert.equal(
      result.status,
      0,
      `exact immutable fixture did not reach FULL PASS:\n${result.output}`,
    );
    assert.match(result.output, /FULL PASS/);
  } finally {
    fixture.dispose();
  }
});

test("full preflight rejects padded source revisions", () => {
  const fixture = createFullPreflightFixture();
  try {
    const result = fixture.run({
      releaseSourceSha: ` ${fixture.head}`,
      revision: `${fixture.head} `,
    });
    assert.notEqual(
      result.status,
      0,
      `preflight accepted whitespace-padded SHAs:\n${result.output}`,
    );
  } finally {
    fixture.dispose();
  }
});

for (const mutationMode of ["head", "worktree"]) {
  test(`full preflight rejects ${mutationMode} changes during the build`, () => {
    const fixture = createFullPreflightFixture();
    try {
      const result = fixture.run({ mutationMode });
      assert.equal(
        result.markerReached,
        true,
        `${mutationMode} mutation did not run:\n${result.output}`,
      );
      assert.notEqual(
        result.status,
        0,
        `preflight reported FULL PASS after ${mutationMode} changed:\n${result.output}`,
      );
    } finally {
      fixture.dispose();
    }
  });
}

test("final local packaging consumes only a CI-shaped artifact root", () => {
  assert.deepEqual(packagingViolations(preflightSource), []);
});

test("packaging analyzer accepts arbitrary workspace names and --dir", () => {
  assert.deepEqual(packagingViolations(compliantDirPackagingFixture), []);
});

test("packaging analyzer accepts cwd, inherited env, and retained output", () => {
  assert.deepEqual(packagingViolations(compliantCwdPackagingFixture), []);
});

test("packaging boundary analyzer rejects unsafe and incomplete mutations", () => {
  const mutations = [
    [
      "source node_modules copied",
      compliantDirPackagingFixture.replace(
        'filter: (source) => path.basename(source) !== "node_modules",',
        "filter: () => true,",
      ),
    ],
    [
      "runtime verifier input omitted",
      compliantDirPackagingFixture.replace(
        '"scripts/verify-desktop-runtime-revisions.mjs",',
        "",
      ),
    ],
    [
      "db worker input omitted",
      compliantDirPackagingFixture.replaceAll(
        '"dist/db-worker-node.js"',
        '"missing-db-worker.js"',
      ),
    ],
    [
      "frozen install disabled",
      compliantDirPackagingFixture.replace('"--frozen-lockfile",', ""),
    ],
    [
      "source static reused",
      compliantDirPackagingFixture.replaceAll(
        '["--dir", releaseStatic',
        '["--dir", staticDir',
      ),
    ],
    [
      "source verifier reused",
      compliantDirPackagingFixture.replace(
        'path.join(releaseStatic, "verify-packaged-desktop.mjs")',
        'path.join(staticDir, "verify-packaged-desktop.mjs")',
      ),
    ],
    [
      "repository parent node_modules injected",
      compliantDirPackagingFixture.replace(
        'pnpm("rebuild desktop native modules",',
        'const NODE_PATH = path.join(repoRoot, "node_modules");\npnpm("rebuild desktop native modules",',
      ),
    ],
    [
      "unsafe protection disabled",
      compliantDirPackagingFixture.replace(
        'pnpm("package", ["--dir", releaseStatic, "exec", "electron-builder"]);',
        'pnpm("package", ["--dir", releaseStatic, "exec", "electron-builder", "--unsafe-path"]);',
      ),
    ],
    [
      "packaged verifier skipped",
      compliantDirPackagingFixture.replace(
        '"verify host packaged application"',
        '"skip packaged verifier"',
      ),
    ],
    [
      "child failure swallowed",
      compliantDirPackagingFixture.replace(
        'if (result.status !== 0) { throw new Error("child failed"); }',
        "",
      ),
    ],
    [
      "temporary workspace retained",
      compliantDirPackagingFixture.replace(
        "fs.rmSync(releaseWorkspace, { recursive: true, force: true });",
        "",
      ),
    ],
    [
      "cwd escapes isolated static",
      compliantCwdPackagingFixture.replace(
        "cwd: isolatedStatic",
        "cwd: staticDir",
      ),
    ],
    [
      "temporary helper retained",
      compliantCwdPackagingFixture.replace(
        "fs.rmSync(helperRoot, { recursive: true, force: true });",
        "",
      ),
    ],
    [
      "temporary output retained without reporting its path",
      compliantCwdPackagingFixture.replace(
        'console.log("packaged output retained at " + retainedOutput);',
        "",
      ),
    ],
  ];
  for (const [label, mutation] of mutations) {
    assert.notDeepEqual(
      packagingViolations(mutation),
      [],
      `${label} unexpectedly satisfied the packaging boundary`,
    );
  }
});
