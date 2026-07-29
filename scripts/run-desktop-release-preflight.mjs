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
const allowDirty = process.argv.includes("--allow-dirty");
const cliDir = path.join(repoRoot, "cli");

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
run(
  "RTC client and Electron tests",
  process.execPath,
  [
    "--require",
    "scripts/fixtures/electron-test-preload.cjs",
    "static/tests.js",
    "-r",
    "^(electron\\.(db-worker-manager|power-monitor|proxy|updater|updater-config)-test|frontend\\.handler\\.db-based\\.(rtc-background-tasks|sync)-test|frontend\\.worker\\.(db-core|db-sync|db-sync-sim|db-worker|pipeline|platform-node|state)-test|frontend\\.worker\\.sync\\..*-test|logseq\\.cli\\.command\\.sync-test|logseq\\.db-worker\\.daemon-test)$",
    "-e",
    "fix-me",
  ],
  {
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

pnpm("isolated packaging install", [
  "--dir",
  "static",
  "install",
  "--frozen-lockfile",
  "--ignore-workspace",
]);
pnpm("self-host updater provider and SemVer contract", [
  "test:selfhost-updater-provider-contract",
]);
pnpm("rebuild desktop native modules", ["--dir", "static", "rebuild:all"]);

const outputDir = fs.mkdtempSync(
  path.join(os.tmpdir(), "logseq-desktop-preflight-"),
);
const outputOverride = `-c.directories.output=${outputDir}`;
const stagedProjectUpdater = path.join(
  staticDir,
  "sidecar",
  "logseq-project-updater",
);
let previousProjectUpdater;
if (fs.existsSync(stagedProjectUpdater)) {
  const stats = fs.lstatSync(stagedProjectUpdater);
  if (stats.isSymbolicLink() || !stats.isFile()) {
    throw new Error(
      `refusing to replace non-regular staged helper: ${stagedProjectUpdater}`,
    );
  }
  previousProjectUpdater = {
    bytes: fs.readFileSync(stagedProjectUpdater),
    mode: stats.mode,
  };
}
let temporaryHelperRoot;

try {
  if (process.platform === "darwin") {
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
    pnpm("package host macOS application", [
      "--dir",
      "static",
      "electron:make-unsigned",
      "--mac",
      "dmg",
      "zip",
      `--${process.arch}`,
      outputOverride,
    ]);
  } else if (process.platform === "win32") {
    pnpm("package host Windows application", [
      "--dir",
      "static",
      "exec",
      "electron-builder",
      "--win",
      "nsis",
      "zip",
      `--${process.arch}`,
      "--publish",
      "never",
      outputOverride,
    ]);
  } else if (process.platform === "linux") {
    pnpm("package host Linux application", [
      "--dir",
      "static",
      "exec",
      "electron-builder",
      "--linux",
      "AppImage",
      "zip",
      `--${process.arch}`,
      "--publish",
      "never",
      outputOverride,
    ]);
  } else {
    throw new Error(`unsupported local packaging platform: ${process.platform}`);
  }

  run(
    "verify host packaged application",
    process.execPath,
    [
      "static/verify-packaged-desktop.mjs",
      "--search-root",
      outputDir,
      "--platform",
      process.platform,
      "--arch",
      process.arch,
      "--version",
      version,
    ],
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
  if (previousProjectUpdater) {
    fs.mkdirSync(path.dirname(stagedProjectUpdater), { recursive: true });
    fs.writeFileSync(stagedProjectUpdater, previousProjectUpdater.bytes);
    fs.chmodSync(stagedProjectUpdater, previousProjectUpdater.mode);
  } else {
    fs.rmSync(stagedProjectUpdater, { force: true });
  }
  if (temporaryHelperRoot) {
    fs.rmSync(temporaryHelperRoot, { recursive: true, force: true });
  }
}

console.log(
  `\n[desktop-release-preflight] FULL PASS version=${version} platform=${process.platform}/${process.arch} output=${outputDir}`,
);
