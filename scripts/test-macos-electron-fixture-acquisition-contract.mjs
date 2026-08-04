#!/usr/bin/env node

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const workflow = fs.readFileSync(
  path.join(repoRoot, ".github", "workflows", "build-desktop-release.yml"),
  "utf8",
);
const desktopPackage = JSON.parse(
  fs.readFileSync(path.join(repoRoot, "resources", "package.json"), "utf8"),
);
const lockedElectronVersion = desktopPackage.devDependencies?.electron;

assert.match(
  lockedElectronVersion ?? "",
  /^\d+\.\d+\.\d+$/,
  "desktop Electron must be pinned exactly for the acquisition contract",
);

const jobSource = (jobName) => {
  const marker = `  ${jobName}:\n`;
  const start = workflow.indexOf(marker);
  assert.notEqual(start, -1, `workflow is missing ${jobName}`);
  const remainder = workflow.slice(start + marker.length);
  const nextJob = remainder.search(/^  [A-Za-z0-9_-]+:\s*$/m);
  return nextJob === -1 ? remainder : remainder.slice(0, nextJob);
};

const workflowSteps = (source) => {
  const starts = [];
  const pattern = /^      - /gm;
  let match;
  while ((match = pattern.exec(source)) !== null) starts.push(match.index);
  return starts.map((start, index) => {
    const stepSource = source.slice(
      start,
      starts[index + 1] ?? source.length,
    );
    return {
      name: stepSource.match(/^      - name:\s*(.+?)\s*$/m)?.[1],
      source: stepSource,
      start,
    };
  });
};

const setting = (source, key) =>
  source.match(new RegExp(`^\\s{8}${key}:\\s*(.+?)\\s*$`, "m"))?.[1];

const runScript = (step) => {
  const lines = step.source.split("\n");
  const runIndex = lines.findIndex((line) => line === "        run: |");
  if (runIndex === -1) {
    return step.source.match(/^        run:\s*(.+?)\s*$/m)?.[1] ?? null;
  }
  const scriptLines = [];
  for (const line of lines.slice(runIndex + 1)) {
    if (/^ {8}\S/.test(line)) break;
    scriptLines.push(line.startsWith("          ") ? line.slice(10) : "");
  }
  return scriptLines.join("\n");
};

const writeExecutable = (file, source) => {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, source, { mode: 0o700 });
};

const createCleanInstallFixture = (arch) => {
  const root = fs.mkdtempSync(
    path.join(os.tmpdir(), `logseq-electron-acquisition-${arch}-`),
  );
  const staticRoot = path.join(root, "static");
  const electronRoot = path.join(staticRoot, "node_modules", "electron");
  const electronApp = path.join(electronRoot, "dist", "Electron.app");
  const installMarker = path.join(root, "electron-install-invoked.txt");
  const binRoot = path.join(root, "test-bin");
  const githubEnv = path.join(root, "github-env");
  const executableArch = arch === "x64" ? "x86_64" : "arm64";

  fs.mkdirSync(electronRoot, { recursive: true });
  fs.writeFileSync(
    path.join(staticRoot, "package.json"),
    `${JSON.stringify(
      {
        devDependencies: { electron: lockedElectronVersion },
        packageManager: desktopPackage.packageManager,
      },
      null,
      2,
    )}\n`,
  );
  fs.writeFileSync(
    path.join(electronRoot, "package.json"),
    `${JSON.stringify(
      {
        name: "electron",
        scripts: { postinstall: "node install.js" },
        version: lockedElectronVersion,
      },
      null,
      2,
    )}\n`,
  );
  fs.writeFileSync(
    path.join(electronRoot, "install.js"),
    [
      'const fs = require("node:fs");',
      'const path = require("node:path");',
      'const root = __dirname;',
      'const pkg = require(path.join(root, "package.json"));',
      'const app = path.join(root, "dist", "Electron.app");',
      'const executable = path.join(app, "Contents", "MacOS", "Electron");',
      'fs.mkdirSync(path.dirname(executable), { recursive: true });',
      'fs.mkdirSync(path.join(app, "Contents", "Resources"), { recursive: true });',
      'fs.writeFileSync(path.join(app, "Contents", "Info.plist"), "fixture");',
      'fs.writeFileSync(executable, [',
      '  "#!/usr/bin/env node",',
      '  `if (process.argv.includes("-e")) process.stdout.write(${JSON.stringify(pkg.version)});`,',
      '  "",',
      '].join("\\n"), { mode: 0o700 });',
      'fs.writeFileSync(`${executable}.arch`, process.env.LOGSEQ_TEST_EXPECTED_ARCH);',
      'fs.writeFileSync(process.env.LOGSEQ_TEST_INSTALL_MARKER, pkg.version);',
    ].join("\n"),
  );

  const sevenZipRoot = path.join(staticRoot, "node_modules", "7zip-bin");
  const sevenZip = path.join(sevenZipRoot, "7za");
  fs.mkdirSync(sevenZipRoot, { recursive: true });
  fs.writeFileSync(
    path.join(sevenZipRoot, "package.json"),
    `${JSON.stringify({ main: "index.js", name: "7zip-bin", version: "5.2.0" })}\n`,
  );
  fs.writeFileSync(
    path.join(sevenZipRoot, "index.js"),
    'exports.path7za = require("node:path").join(__dirname, "7za");\n',
  );
  writeExecutable(
    sevenZip,
    '#!/bin/sh\necho "7-Zip 24.09 electron-builder fixture"\n',
  );
  const nestedSevenZip = path.join(
    staticRoot,
    "node_modules",
    "electron-builder",
    "node_modules",
    "7zip-bin",
  );
  fs.mkdirSync(path.dirname(nestedSevenZip), { recursive: true });
  fs.symlinkSync(sevenZipRoot, nestedSevenZip, "dir");
  fs.writeFileSync(
    path.join(staticRoot, "node_modules", "electron-builder", "package.json"),
    `${JSON.stringify({ name: "electron-builder", version: "26.8.2" })}\n`,
  );
  fs.mkdirSync(path.join(staticRoot, "node_modules", ".bin"), {
    recursive: true,
  });
  fs.symlinkSync(sevenZip, path.join(staticRoot, "node_modules", ".bin", "7za"));

  writeExecutable(
    path.join(binRoot, "pnpm"),
    [
      "#!/bin/sh",
      'package_root="$PWD"',
      'if [ "$1" = "--dir" ]; then',
      '  package_root="$2"',
      "  shift 2",
      "fi",
      'case " $* " in',
      '  *" rebuild electron "*)',
      '    exec node "$package_root/node_modules/electron/install.js"',
      "    ;;",
      "esac",
      "exit 0",
      "",
    ].join("\n"),
  );
  for (const tool of ["lipo", "file"]) {
    writeExecutable(
      path.join(binRoot, tool),
      [
        "#!/bin/sh",
        'for candidate in "$@"; do executable="$candidate"; done',
        'arch="$(cat "${executable}.arch")"',
        'if [ "' + tool + '" = "lipo" ]; then',
        '  printf "%s\\n" "$arch"',
        "else",
        '  printf "%s: Mach-O 64-bit executable %s\\n" "$executable" "$arch"',
        "fi",
        "",
      ].join("\n"),
    );
  }

  return {
    binRoot,
    dispose: () => fs.rmSync(root, { force: true, recursive: true }),
    electronApp,
    executableArch,
    githubEnv,
    installMarker,
    root,
    staticRoot,
  };
};

const readGithubEnv = (file, env) => {
  if (!fs.existsSync(file)) return;
  for (const line of fs.readFileSync(file, "utf8").split(/\r?\n/)) {
    const separator = line.indexOf("=");
    if (separator > 0) env[line.slice(0, separator)] = line.slice(separator + 1);
  }
  fs.writeFileSync(file, "");
};

const stepEnvironment = (source, fixtureRoot) => {
  const lines = source.split("\n");
  const envIndex = lines.findIndex((line) => line === "        env:");
  if (envIndex === -1) return {};
  const env = {};
  for (const line of lines.slice(envIndex + 1)) {
    const match = line.match(/^ {10}([A-Za-z_][A-Za-z0-9_]*):\s*(.*?)\s*$/);
    if (!match) break;
    env[match[1]] = match[2]
      .replaceAll("${{ github.workspace }}", fixtureRoot)
      .replace(/^['"]|['"]$/g, "");
  }
  return env;
};

const executeAcquisitionThroughResolver = (arch) => {
  const job = jobSource(`build-macos-${arch}`);
  const steps = workflowSteps(job);
  const resolverIndex = steps.findIndex(
    (step) => step.name === "Resolve native updater contract tools",
  );
  assert.notEqual(
    resolverIndex,
    -1,
    `macOS ${arch} is missing Resolve native updater contract tools`,
  );
  const resolver = steps[resolverIndex].source;
  assert.match(
    resolver,
    /(?:test\s+-x|\[\s+-x\s+)[^\n]*Contents\/MacOS\/Electron/,
    `macOS ${arch} resolver must reject a non-executable Electron fixture`,
  );
  assert.match(
    resolver,
    /ELECTRON_RUN_AS_NODE=1[^\n]*Contents\/MacOS\/Electron/,
    `macOS ${arch} resolver must execute the acquired Electron runtime`,
  );
  assert.match(
    resolver,
    /devDependencies\.electron|package\.json[^\n]*electron|electron[^\n]*package\.json/,
    `macOS ${arch} resolver must compare against the locked Electron package version`,
  );
  assert.match(
    resolver,
    /(?:lipo|file)[^\n]*Contents\/MacOS\/Electron/,
    `macOS ${arch} resolver must inspect the acquired executable architecture`,
  );
  const dependencyIndex = steps.findLastIndex(
    (step, index) =>
      index < resolverIndex &&
      /\bpnpm\s+install\b/.test(step.source) &&
      /working-directory:\s*\.\/static/.test(step.source),
  );
  assert.notEqual(
    dependencyIndex,
    -1,
    `macOS ${arch} must install the locked desktop package before resolving tools`,
  );

  const fixture = createCleanInstallFixture(arch);
  try {
    assert.equal(
      fs.existsSync(fixture.electronApp),
      false,
      "clean-install fixture unexpectedly started with Electron.app",
    );
    const env = {
      ...process.env,
      GITHUB_ENV: fixture.githubEnv,
      GITHUB_WORKSPACE: fixture.root,
      LOGSEQ_TEST_EXPECTED_ARCH: fixture.executableArch,
      LOGSEQ_TEST_INSTALL_MARKER: fixture.installMarker,
      PATH: `${fixture.binRoot}${path.delimiter}${process.env.PATH ?? ""}`,
      RUNNER_ARCH: arch === "x64" ? "X64" : "ARM64",
      RUNNER_TEMP: path.join(fixture.root, "runner-temp"),
    };
    fs.mkdirSync(env.RUNNER_TEMP, { recursive: true });

    const candidateSteps = steps.slice(dependencyIndex, resolverIndex + 1);
    for (const step of candidateSteps) {
      const script = runScript(step);
      if (!script) continue;
      const expandedScript = script
        .replaceAll("${{ github.workspace }}", fixture.root)
        .replaceAll("/usr/bin/lipo", path.join(fixture.binRoot, "lipo"))
        .replaceAll("/usr/bin/file", path.join(fixture.binRoot, "file"));
      const workingDirectoryValue = setting(step.source, "working-directory");
      const cwd = workingDirectoryValue
        ? path.resolve(
            fixture.root,
            workingDirectoryValue.replace(/^\.\//, ""),
          )
        : fixture.root;
      const result = spawnSync(
        "/bin/bash",
        ["-e", "-o", "pipefail", "-c", expandedScript],
        {
          cwd,
          encoding: "utf8",
          env: { ...env, ...stepEnvironment(step.source, fixture.root) },
          shell: false,
        },
      );
      assert.equal(
        result.status,
        0,
        `macOS ${arch} ${step.name ?? "unnamed step"} failed from clean install:\n${result.stdout}\n${result.stderr}`,
      );
      readGithubEnv(fixture.githubEnv, env);
    }

    assert.ok(
      fs.existsSync(fixture.installMarker),
      `macOS ${arch} did not invoke the locked electron package installer`,
    );
    assert.equal(
      fs.readFileSync(fixture.installMarker, "utf8"),
      lockedElectronVersion,
      `macOS ${arch} did not invoke the locked electron package installer`,
    );
    const executable = path.join(
      fixture.electronApp,
      "Contents",
      "MacOS",
      "Electron",
    );
    assert.ok(
      fs.statSync(executable).mode & 0o111,
      "Electron fixture is not executable",
    );
    const versionProbe = spawnSync(
      executable,
      ["-e", "process.stdout.write(process.versions.electron)"],
      {
        encoding: "utf8",
        env: { ...env, ELECTRON_RUN_AS_NODE: "1" },
      },
    );
    assert.equal(versionProbe.status, 0);
    assert.equal(versionProbe.stdout, lockedElectronVersion);
    const archProbe = spawnSync(
      path.join(fixture.binRoot, "lipo"),
      ["-archs", executable],
      { encoding: "utf8", env },
    );
    assert.equal(archProbe.status, 0);
    assert.match(
      archProbe.stdout,
      new RegExp(`(?:^|\\s)${fixture.executableArch}(?:\\s|$)`),
    );
  } finally {
    fixture.dispose();
  }
};

test("clean-install harness rejects a path-only Electron fixture resolver", () => {
  const fixture = createCleanInstallFixture("x64");
  try {
    const result = spawnSync(
      "/bin/bash",
      ["-c", 'test -d static/node_modules/electron/dist/Electron.app'],
      { cwd: fixture.root, encoding: "utf8" },
    );
    assert.notEqual(
      result.status,
      0,
      "negative control did not reproduce the fresh-runner missing dist state",
    );
    assert.equal(fs.existsSync(fixture.installMarker), false);
  } finally {
    fixture.dispose();
  }
});

for (const arch of ["x64", "arm64"]) {
  test(`clean-install harness materializes and probes locked ${arch} Electron`, () => {
    const fixture = createCleanInstallFixture(arch);
    try {
      const env = {
        ...process.env,
        LOGSEQ_TEST_EXPECTED_ARCH: fixture.executableArch,
        LOGSEQ_TEST_INSTALL_MARKER: fixture.installMarker,
      };
      const result = spawnSync(
        path.join(fixture.binRoot, "pnpm"),
        ["rebuild", "electron"],
        {
          cwd: fixture.staticRoot,
          encoding: "utf8",
          env,
        },
      );
      assert.equal(result.status, 0, result.stderr);
      assert.equal(
        fs.readFileSync(fixture.installMarker, "utf8"),
        lockedElectronVersion,
      );
      const executable = path.join(
        fixture.electronApp,
        "Contents",
        "MacOS",
        "Electron",
      );
      assert.ok(fs.statSync(executable).mode & 0o111);
      const versionProbe = spawnSync(
        executable,
        ["-e", "process.stdout.write(process.versions.electron)"],
        {
          encoding: "utf8",
          env: { ...env, ELECTRON_RUN_AS_NODE: "1" },
        },
      );
      assert.equal(versionProbe.status, 0);
      assert.equal(versionProbe.stdout, lockedElectronVersion);
      const archProbe = spawnSync(
        path.join(fixture.binRoot, "lipo"),
        ["-archs", executable],
        { encoding: "utf8", env },
      );
      assert.equal(archProbe.status, 0);
      assert.match(archProbe.stdout, new RegExp(fixture.executableArch));
    } finally {
      fixture.dispose();
    }
  });
}

for (const arch of ["x64", "arm64"]) {
  test(`macOS ${arch} explicitly acquires and probes Electron.app after clean install`, () => {
    executeAcquisitionThroughResolver(arch);
  });
}
