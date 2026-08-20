#!/usr/bin/env node

import { spawnSync } from "node:child_process";
import { generateKeyPairSync } from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const exactShaPattern = /^[0-9a-f]{40}$/;
const identityGit = (args, label) => {
  const result = spawnSync("git", args, {
    cwd: repoRoot,
    encoding: "utf8",
    shell: false,
    stdio: ["ignore", "pipe", "pipe"],
  });
  if (result.error) throw result.error;
  if (result.signal) throw new Error(`${label} terminated by ${result.signal}`);
  if (result.status !== 0) {
    throw new Error(`${label} failed: ${result.stderr.trim()}`);
  }
  return result.stdout;
};
const resolveIdentityHead = () => {
  const head = identityGit(["rev-parse", "HEAD"], "git rev-parse HEAD").trim();
  if (!exactShaPattern.test(head)) {
    throw new Error("git rev-parse HEAD must be an exact lowercase 40-hex SHA");
  }
  return head;
};
const identityWorktreeStatus = ({ includeUntracked = true } = {}) =>
  identityGit(
    [
      "status",
      "--porcelain=v1",
      includeUntracked ? "--untracked-files=all" : "--untracked-files=no",
      "--",
      ".",
      ":(exclude)static/package.json",
      ":(exclude)static/pnpm-lock.yaml",
    ],
    "git status",
  );
const resolveProvidedIdentity = (name, head) => {
  const value = process.env[name];
  if (value === undefined) return head;
  if (!exactShaPattern.test(value)) {
    throw new Error(`${name} must be an exact lowercase 40-hex SHA`);
  }
  if (value !== head) {
    throw new Error(`${name} must equal the actual HEAD ${head}`);
  }
  return value;
};
const establishReleaseSourceIdentity = ({ allowDirty = false }) => {
  const head = resolveIdentityHead();
  const sourceSha = resolveProvidedIdentity("LOGSEQ_RELEASE_SOURCE_SHA", head);
  const revision = resolveProvidedIdentity("LOGSEQ_REVISION", head);
  const worktreeStatus = identityWorktreeStatus({
    includeUntracked: !allowDirty,
  });
  if (!allowDirty && worktreeStatus !== "") {
    throw new Error("release source worktree must be clean");
  }
  process.env.LOGSEQ_RELEASE_SOURCE_SHA = sourceSha;
  process.env.LOGSEQ_REVISION = revision;
  return Object.freeze({ allowDirty, head, revision, sourceSha, worktreeStatus });
};
const assertReleaseSourceIdentityUnchanged = (identity, { phase }) => {
  for (const [name, expected] of [
    ["LOGSEQ_RELEASE_SOURCE_SHA", identity.sourceSha],
    ["LOGSEQ_REVISION", identity.revision],
  ]) {
    const actual = process.env[name];
    if (!exactShaPattern.test(actual ?? "") || actual !== expected) {
      throw new Error(`${name} changed during ${phase}`);
    }
  }
  const head = resolveIdentityHead();
  if (head !== identity.head) {
    throw new Error(`release source HEAD changed during ${phase}`);
  }
  const status = identityWorktreeStatus({
    includeUntracked: !identity.allowDirty,
  });
  if (status !== identity.worktreeStatus) {
    throw new Error(
      `release source worktree changed during ${phase}: ` +
        `baseline=${JSON.stringify(identity.worktreeStatus)} ` +
        `current=${JSON.stringify(status)}`,
    );
  }
  return identity;
};
const allowDirty = process.argv.includes("--allow-dirty");
const cliDir = path.join(repoRoot, "cli");
const electronTestPreloadRelativePath =
  "scripts/fixtures/electron-test-preload.cjs";
const electronTestPreloadPath = path.join(
  repoRoot,
  "scripts",
  "fixtures",
  "electron-test-preload.cjs",
);
const electronTestPreloadProbe =
  "--electron-test-preload-contract-probe";
const releaseSourceBindingProbe =
  "--release-source-binding-contract-probe";
const probeIndex = process.argv.indexOf(electronTestPreloadProbe);
const preloadCandidateIndex = process.argv.indexOf(
  "--electron-test-preload-candidate",
);
const preloadCandidate =
  preloadCandidateIndex === -1
    ? null
    : process.argv[preloadCandidateIndex + 1];
if (preloadCandidateIndex !== -1 && !preloadCandidate) {
  throw new Error("missing --electron-test-preload-candidate value");
}
if (preloadCandidateIndex !== -1 && probeIndex === -1) {
  throw new Error(
    "--electron-test-preload-candidate is restricted to the contract probe",
  );
}
const requireSafeElectronTestPreload = (candidate) => {
  if (!path.isAbsolute(candidate)) {
    throw new Error("Electron test preload must be an absolute path");
  }
  if (candidate !== electronTestPreloadPath) {
    throw new Error(
      `Electron test preload must be ${electronTestPreloadPath}`,
    );
  }

  let preloadStat;
  try {
    preloadStat = fs.lstatSync(candidate);
  } catch (error) {
    throw new Error(`Electron test preload is unavailable: ${candidate}`, {
      cause: error,
    });
  }
  if (preloadStat.isSymbolicLink() || !preloadStat.isFile()) {
    throw new Error(
      "Electron test preload must be a regular, non-symlink file",
    );
  }

  const realRepoRoot = fs.realpathSync(repoRoot);
  const realPreloadPath = fs.realpathSync(candidate);
  const expectedRealPreloadPath = path.join(
    realRepoRoot,
    "scripts",
    "fixtures",
    "electron-test-preload.cjs",
  );
  const realPreloadRelativePath = path.relative(
    realRepoRoot,
    realPreloadPath,
  );
  const realPreloadIsInRepo =
    realPreloadRelativePath !== "" &&
    realPreloadRelativePath !== ".." &&
    !realPreloadRelativePath.startsWith(`..${path.sep}`) &&
    !path.isAbsolute(realPreloadRelativePath);
  if (
    !realPreloadIsInRepo ||
    realPreloadPath !== expectedRealPreloadPath
  ) {
    throw new Error(
      "Electron test preload realpath must remain at its repository path",
    );
  }

  const tracked = spawnSync(
    "git",
    [
      "ls-files",
      "--error-unmatch",
      "--",
      electronTestPreloadRelativePath,
    ],
    {
      cwd: repoRoot,
      encoding: "utf8",
      shell: false,
      stdio: ["ignore", "pipe", "pipe"],
    },
  );
  if (
    tracked.error ||
    tracked.status !== 0 ||
    tracked.stdout.trim() !== electronTestPreloadRelativePath
  ) {
    throw new Error(
      `Electron test preload must be tracked at ${electronTestPreloadRelativePath}`,
      { cause: tracked.error },
    );
  }

  return candidate;
};
const electronTestPreload = requireSafeElectronTestPreload(
  preloadCandidate ?? electronTestPreloadPath,
);
const electronTestInvocationFor = (testBundle, namespaceFilter) => ({
  args: [
    "--require",
    electronTestPreload,
    testBundle,
    "-r",
    namespaceFilter,
    "-e",
    "fix-me",
  ],
  command: process.execPath,
  cwd: repoRoot,
  shell: false,
});
if (probeIndex !== -1) {
  const namespaceFilter = [
    "^(electron\\.",
    "(db-worker-manager|power-monitor|proxy|updater|updater-config)-test|",
    "frontend\\.handler\\.db-based\\.(rtc-background-tasks|sync)-test|",
    "frontend\\.worker\\.(db-core|db-sync|db-sync-sim|db-worker|pipeline|platform-node|state)-test|",
    "frontend\\.worker\\.sync\\..*-test|logseq\\.cli\\.command\\.sync-test|",
    "logseq\\.db-worker\\.daemon-test)$",
  ].join("");
  console.log(
    `ELECTRON_TEST_PRELOAD_CONTRACT ${JSON.stringify(
      electronTestInvocationFor(
        ["static", "tests.js"].join("/"),
        namespaceFilter,
      ),
    )}`,
  );
  process.exit(0);
}

const run = (label, command, args, options = {}) => {
  const startedAt = Date.now();
  console.log(`\n[desktop-release-preflight] START ${label}`);
  const result = spawnSync(command, args, {
    cwd: options.cwd || repoRoot,
    env: { ...process.env, ...options.env },
    stdio: "inherit",
    shell: false,
    timeout: options.timeout,
  });
  if (result.error) throw result.error;
  if (result.signal) {
    throw new Error(`${label} terminated by ${result.signal}`);
  }
  if (result.status !== 0) {
    throw new Error(`${label} failed with exit code ${result.status}`);
  }
  console.log(
    `[desktop-release-preflight] PASS ${label} (${(
      (Date.now() - startedAt) /
      1000
    ).toFixed(1)}s)`,
  );
};

const pnpm = (label, args, options) => run(label, "pnpm", args, options);

if (process.argv.includes(releaseSourceBindingProbe)) {
  const identity = establishReleaseSourceIdentity({
    allowDirty: true,
  });
  console.log(`RELEASE_SOURCE_BINDING_CONTRACT PASS sha=${identity.head}`);
  process.exit(0);
}

const versionSource = fs.readFileSync(
  path.join(repoRoot, "src/main/frontend/version.cljs"),
  "utf8",
);
const version = versionSource.match(/\(defonce version "([^"]+)"\)/)?.[1];
if (!version) throw new Error("could not read desktop version");

run(
  "source and environment checks",
  process.execPath,
  [
    "scripts/desktop-release-preflight.mjs",
    ...(allowDirty ? [] : ["--strict"]),
  ],
);
const releaseSourceIdentity = establishReleaseSourceIdentity({
  allowDirty,
});
run(
  "verify repository OCaml switch",
  "opam",
  [
    "exec",
    `--switch=${cliDir}`,
    "--",
    "ocamlc",
    "-version",
  ],
);
pnpm("root frozen install", ["install", "--frozen-lockfile"]);
pnpm("db-sync frozen install", [
  "--dir",
  "deps/db-sync",
  "install",
  "--frozen-lockfile",
]);
run("compile client tests", "clojure", [
  "-Srepro",
  "-M:test",
  "compile",
  "test",
]);

const staticDir = path.join(repoRoot, "static");
fs.mkdirSync(staticDir, { recursive: true });
for (const name of ["package.json", "pnpm-lock.yaml"]) {
  fs.copyFileSync(
    path.join(repoRoot, "resources", name),
    path.join(staticDir, name),
  );
}

pnpm("isolated desktop test install", [
  "--dir",
  "static",
  "install",
  "--frozen-lockfile",
  "--ignore-workspace",
]);
pnpm("desktop release contract tests", ["desktop:test-release-contracts"]);
const compiledElectronTestBundle = "static/tests.js";
const electronTestNamespaceFilter =
  "^(electron\\.(db-worker-manager|power-monitor|proxy|updater|updater-config)-test|frontend\\.handler\\.db-based\\.(rtc-background-tasks|sync)-test|frontend\\.worker\\.(db-core|db-sync|db-sync-sim|db-worker|pipeline|platform-node|state)-test|frontend\\.worker\\.sync\\..*-test|logseq\\.cli\\.command\\.sync-test|logseq\\.db-worker\\.daemon-test)$";
const electronTestInvocation =
  electronTestInvocationFor(
    compiledElectronTestBundle,
    electronTestNamespaceFilter,
  );
run(
  "RTC client and Electron tests",
  electronTestInvocation.command,
  electronTestInvocation.args,
  {
    cwd: electronTestInvocation.cwd,
    env: { LOGSEQ_STABLE_IDENTS: "1" },
    timeout: 45 * 60 * 1000,
  },
);
pnpm("db-sync server tests", ["--dir", "deps/db-sync", "test"]);
pnpm("db-sync production Worker", ["--dir", "deps/db-sync", "release"]);
pnpm("db-sync API docs", ["--dir", "deps/db-sync", "build:api-docs"]);
pnpm(
  "db-sync Worker dry run",
  ["exec", "wrangler", "deploy", "--dry-run", "--env="],
  { cwd: path.join(repoRoot, "deps/db-sync/worker") },
);
pnpm("db-sync 128 MB memory test", [
  "--dir",
  "deps/db-sync",
  "test:large-op-128m",
]);
run("compile client backup memory test", "clojure", [
  "-Srepro",
  "-M:test",
  "release",
  "db-sync-backup-memory-test",
]);
run(
  "client backup 128 MB memory test",
  process.execPath,
  [
    "--expose-gc",
    "--max-old-space-size=128",
    "static/db-sync-backup-memory-test.js",
  ],
);

run(
  "install CLI OCaml dependencies",
  "opam",
  [
    "install",
    `--switch=${cliDir}`,
    ".",
    "--deps-only",
    "--with-test",
    "--yes",
  ],
  { cwd: cliDir },
);
pnpm("install CLI pnpm dependencies", [
  "--dir",
  "cli",
  "install",
  "--frozen-lockfile",
  "--ignore-workspace",
]);
run("build desktop resources", "pnpm", ["exec", "gulp", "build"]);
pnpm("verify updater provider contract", [
  "--dir",
  "static",
  "electron:verify-updater-provider",
]);
pnpm("compile Electron CLJS", ["cljs:release-electron"]);
pnpm("bundle db-worker-node", ["db-worker-node:bundle"]);
run("build and stage CLI", "opam", [
  "exec",
  `--switch=${cliDir}`,
  "--",
  "pnpm",
  "cli:release",
]);
pnpm("build desktop webpack assets", ["webpack-app-build"]);
pnpm("stage desktop runtimes", ["desktop:prepare-runtime-js"]);
assertReleaseSourceIdentityUnchanged(releaseSourceIdentity, {
  phase: "before runtime verification",
});
pnpm("verify desktop runtime revisions", [
  "desktop:verify-runtime-revisions",
]);
run(
  "verify release configuration",
  process.execPath,
  ["scripts/test-cli-release-config.mjs"],
);
pnpm("self-host updater source contracts", [
  "test:selfhost-updater-source-contracts",
]);
if (process.platform === "darwin") {
  pnpm("build project updater test helper", [
    "project-update:test-helper",
  ]);
}
pnpm("project-signed updater contract", [
  "test:project-signed-macos-updater",
]);

pnpm("self-host updater provider and SemVer contract", [
  "test:selfhost-updater-provider-contract",
]);

const createCiReleaseWorkspace = () => {
  const releaseWorkspaceRoot = fs.mkdtempSync(
    path.join(os.tmpdir(), "logseq-desktop-release-workspace-"),
  );
  const releaseStaticDir = path.join(releaseWorkspaceRoot, "static");
  try {
    fs.cpSync(staticDir, releaseStaticDir, {
      recursive: true,
      filter: (source) => {
        const relativePath = path.relative(staticDir, source);
        return (
          relativePath === "" ||
          !relativePath.split(path.sep).includes("node_modules")
        );
      },
    });
    for (const relativePath of [
      "scripts/verify-desktop-runtime-revisions.mjs",
      "dist/db-worker-node.js",
    ]) {
      const source = path.join(repoRoot, relativePath);
      const sourceStats = fs.lstatSync(source);
      if (sourceStats.isSymbolicLink() || !sourceStats.isFile()) {
        throw new Error(
          `release workspace source must be a regular, non-symlink file: ${relativePath}`,
        );
      }
      const destination = path.join(releaseWorkspaceRoot, relativePath);
      fs.mkdirSync(path.dirname(destination), { recursive: true });
      fs.copyFileSync(source, destination);
    }
    return { releaseStaticDir, releaseWorkspaceRoot };
  } catch (error) {
    fs.rmSync(releaseWorkspaceRoot, { recursive: true, force: true });
    throw error;
  }
};

const outputDir = fs.mkdtempSync(
  path.join(os.tmpdir(), "logseq-desktop-preflight-"),
);
const outputOverride = `-c.directories.output=${outputDir}`;
const { releaseStaticDir, releaseWorkspaceRoot } =
  createCiReleaseWorkspace();
const stagedProjectUpdater = path.join(
  releaseStaticDir,
  "sidecar",
  "logseq-project-updater",
);
let temporaryHelperRoot;

try {
  pnpm(
    "isolated packaging install",
    ["install", "--frozen-lockfile", "--ignore-workspace"],
    { cwd: releaseStaticDir },
  );
  pnpm("rebuild desktop native modules", ["rebuild:all"], {
    cwd: releaseStaticDir,
  });

  if (process.platform === "darwin") {
    if (fs.existsSync(stagedProjectUpdater)) {
      const stats = fs.lstatSync(stagedProjectUpdater);
      if (stats.isSymbolicLink() || !stats.isFile()) {
        throw new Error(
          `refusing to replace non-regular staged helper: ${stagedProjectUpdater}`,
        );
      }
    }
    temporaryHelperRoot = fs.mkdtempSync(
      path.join(os.tmpdir(), "logseq-preflight-project-updater-"),
    );
    const temporaryHelper = path.join(
      temporaryHelperRoot,
      "logseq-project-updater",
    );
    const { publicKey } = generateKeyPairSync("ed25519");
    const publicKeyBase64 = publicKey
      .export({ format: "der", type: "spki" })
      .subarray(-32)
      .toString("base64");
    run(
      "build test-only project updater helper for packaging verification",
      process.execPath,
      [
        "scripts/build-project-update-helper.mjs",
        "--test-only",
        "--public-key-base64",
        publicKeyBase64,
        "--arch",
        process.arch,
        "--output",
        temporaryHelper,
      ],
    );
    fs.mkdirSync(path.dirname(stagedProjectUpdater), { recursive: true });
    fs.copyFileSync(temporaryHelper, stagedProjectUpdater);
    fs.chmodSync(stagedProjectUpdater, 0o755);
  }

  if (process.platform === "darwin") {
    pnpm(
      "package host macOS application",
      [
        "electron:make-unsigned",
        "--mac",
        "dmg",
        "zip",
        `--${process.arch}`,
        outputOverride,
      ],
      { cwd: releaseStaticDir },
    );
  } else if (process.platform === "win32") {
    pnpm(
      "package host Windows application",
      [
        "exec",
        "electron-builder",
        "--win",
        "nsis",
        "zip",
        `--${process.arch}`,
        "--publish",
        "never",
        outputOverride,
      ],
      { cwd: releaseStaticDir },
    );
  } else if (process.platform === "linux") {
    pnpm(
      "package host Linux application",
      [
        "exec",
        "electron-builder",
        "--linux",
        "AppImage",
        "zip",
        `--${process.arch}`,
        "--publish",
        "never",
        outputOverride,
      ],
      { cwd: releaseStaticDir },
    );
  } else {
    throw new Error(`unsupported local packaging platform: ${process.platform}`);
  }

  run(
    "verify host packaged application",
    process.execPath,
    [
      path.join(releaseStaticDir, "verify-packaged-desktop.mjs"),
      "--search-root",
      outputDir,
      "--platform",
      process.platform,
      "--arch",
      process.arch,
      "--version",
      version,
    ],
    { cwd: releaseWorkspaceRoot },
  );

  if (process.platform === "darwin") {
    const appPath = path.join(
      outputDir,
      process.arch === "arm64" ? "mac-arm64" : "mac",
      "Logseq.app",
    );
    run("verify macOS bundle signature", "codesign", [
      "--verify",
      "--deep",
      "--strict",
      "--verbose=2",
      appPath,
    ]);
    const dmg = fs
      .readdirSync(outputDir)
      .find((name) => name.endsWith(".dmg"));
    if (!dmg) throw new Error(`missing DMG under ${outputDir}`);
    run("verify macOS DMG", "hdiutil", [
      "verify",
      path.join(outputDir, dmg),
    ]);
  }
} finally {
  if (temporaryHelperRoot) {
    fs.rmSync(temporaryHelperRoot, { recursive: true, force: true });
  }
  fs.rmSync(releaseWorkspaceRoot, { recursive: true, force: true });
}

assertReleaseSourceIdentityUnchanged(releaseSourceIdentity, {
  phase: "before FULL PASS",
});

console.log(
  `\n[desktop-release-preflight] FULL PASS version=${version} platform=${process.platform}/${process.arch} output=${outputDir}`,
);
