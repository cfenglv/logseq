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
      index,
      name: stepSource.match(/^      - name:\s*(.+?)\s*$/m)?.[1],
      source: stepSource,
      start,
    };
  });
};

const uniqueStage = (steps, predicate, label) => {
  const matches = steps.filter(predicate);
  assert.equal(
    matches.length,
    1,
    `${label} must resolve to exactly one workflow step; found ${matches.length}`,
  );
  return matches[0];
};

const macContractStages = (steps, arch) => {
  const dependency = uniqueStage(
    steps,
    (step) =>
      /\bpnpm\s+install\b/.test(step.source) &&
      /working-directory:\s*\.\/static/.test(step.source),
    `macOS ${arch} dependency stage`,
  );
  const materialize = uniqueStage(
    steps,
    (step) => {
      const name = step.name ?? "";
      return (
        /materialize/i.test(name) &&
        /electron/i.test(name) &&
        new RegExp(`(?:^|\\W)${arch}(?:\\W|$)`, "i").test(name)
      );
    },
    `macOS ${arch} materialize/acquisition stage`,
  );
  const resolve = uniqueStage(
    steps,
    (step) =>
      step.name === `Resolve ${arch} native updater contract tools`,
    `macOS ${arch} resolver stage`,
  );
  const probe = uniqueStage(
    steps,
    (step) =>
      step.name === `Probe ${arch} native updater contract tools`,
    `macOS ${arch} contract probe stage`,
  );
  const contracts = uniqueStage(
    steps,
    (step) =>
      step.name ===
      "Run project-signed updater native and provider contracts",
    `macOS ${arch} full updater contract stage`,
  );
  assert.deepEqual(
    [dependency, materialize, resolve, probe, contracts]
      .map((step) => step.index)
      .sort((left, right) => left - right),
    [
      dependency.index,
      materialize.index,
      resolve.index,
      probe.index,
      contracts.index,
    ],
    `macOS ${arch} stages must remain ordered dependency -> materialize -> resolve -> probe -> contracts`,
  );
  return { contracts, dependency, materialize, probe, resolve };
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
  const electronVersion = path.join(electronRoot, "dist", "version");
  const installMarker = path.join(root, "electron-install-invoked.txt");
  const binRoot = path.join(root, "test-bin");
  const githubEnv = path.join(root, "github-env");
  const githubOutput = path.join(root, "github-output");
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
        bin: { "install-electron": "install.js" },
        name: "electron",
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
      'fs.writeFileSync(path.join(root, "dist", "version"), `v${pkg.version}\\n`);',
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
      "#!/usr/bin/env node",
      'const { spawnSync } = require("node:child_process");',
      'const fs = require("node:fs");',
      'const path = require("node:path");',
      'let args = process.argv.slice(2);',
      'let packageRoot = process.cwd();',
      'if (args[0] === "--dir" || args[0] === "-C") {',
      '  packageRoot = path.resolve(args[1]);',
      '  args = args.slice(2);',
      '}',
      'if (args[0] !== "exec") process.exit(0);',
      'const command = args[1];',
      'const electronRoot = path.join(packageRoot, "node_modules", "electron");',
      'const packagePath = path.join(electronRoot, "package.json");',
      'if (!fs.existsSync(packagePath)) process.exit(127);',
      'const pkg = JSON.parse(fs.readFileSync(packagePath, "utf8"));',
      'const relativeBin = typeof pkg.bin === "string" ? pkg.bin : pkg.bin?.[command];',
      'if (!relativeBin) process.exit(127);',
      'const result = spawnSync(process.execPath, [path.join(electronRoot, relativeBin), ...args.slice(2)], {',
      '  env: process.env,',
      '  stdio: "inherit",',
      '});',
      'if (result.error) throw result.error;',
      'process.exit(result.status ?? 1);',
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
    electronVersion,
    executableArch,
    githubEnv,
    githubOutput,
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

const probeArchiveVersion = (fixture, expectedVersion) =>
  spawnSync(
    "/bin/bash",
    [
      "-e",
      "-c",
      'archive_version="$(sed \'s/^v//\' "$ELECTRON_VERSION_FILE")"\n' +
        'test "$archive_version" = "$EXPECTED_ELECTRON_VERSION"',
    ],
    {
      encoding: "utf8",
      env: {
        ...process.env,
        ELECTRON_VERSION_FILE: fixture.electronVersion,
        EXPECTED_ELECTRON_VERSION: expectedVersion,
      },
    },
  );

const workflowOutputExpression =
  /\$\{\{\s*steps\.([A-Za-z0-9_-]+)\.outputs\.([A-Za-z0-9_-]+)\s*\}\}/g;

const expandWorkflowValues = (value, fixtureRoot, outputs) =>
  value
    .replaceAll("${{ github.workspace }}", fixtureRoot)
    .replace(
      workflowOutputExpression,
      (_expression, stepId, outputName) => {
        assert.ok(
          Object.hasOwn(outputs[stepId] ?? {}, outputName),
          `workflow expression references missing output ${stepId}.${outputName}`,
        );
        return outputs[stepId][outputName];
      },
    );

const readGithubOutput = (file, step, outputs) => {
  if (!fs.existsSync(file)) return;
  const entries = fs
    .readFileSync(file, "utf8")
    .split(/\r?\n/)
    .filter(Boolean);
  fs.writeFileSync(file, "");
  if (entries.length === 0) return;
  const stepId = setting(step.source, "id");
  assert.ok(
    stepId,
    `${step.name ?? "unnamed step"} emitted outputs without an id`,
  );
  outputs[stepId] ??= {};
  for (const line of entries) {
    const separator = line.indexOf("=");
    assert.ok(separator > 0, `unsupported GITHUB_OUTPUT entry: ${line}`);
    outputs[stepId][line.slice(0, separator)] = line.slice(separator + 1);
  }
};

const stepEnvironment = (source, fixtureRoot, outputs) => {
  const lines = source.split("\n");
  const envIndex = lines.findIndex((line) => line === "        env:");
  if (envIndex === -1) return {};
  const env = {};
  for (const line of lines.slice(envIndex + 1)) {
    const match = line.match(/^ {10}([A-Za-z_][A-Za-z0-9_]*):\s*(.*?)\s*$/);
    if (!match) break;
    env[match[1]] = expandWorkflowValues(
      match[2].replace(/^['"]|['"]$/g, ""),
      fixtureRoot,
      outputs,
    );
  }
  return env;
};

const escapeRegExp = (value) =>
  value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

const resolverFullProbePattern =
  /(?:ELECTRON_RUN_AS_NODE|(?:^|\s)(?:\/usr\/bin\/)?(?:lipo|file)(?:\s|$)|test:project-signed|project-signed[^\n]*contracts)/m;

const executeAcquisitionThroughProbe = (arch, rejectNonExecutable = false) => {
  const job = jobSource(`build-macos-${arch}`);
  const steps = workflowSteps(job);
  const stages = macContractStages(steps, arch);
  const resolver = stages.resolve.source;
  const resolverId = setting(resolver, "id");
  assert.ok(resolverId, `macOS ${arch} resolver must expose a step id`);
  assert.match(
    resolver,
    /GITHUB_OUTPUT/,
    `macOS ${arch} resolver must expose paths through step outputs`,
  );
  assert.doesNotMatch(
    resolver,
    resolverFullProbePattern,
    `macOS ${arch} resolver may sanity-check resolved paths but must not execute runtime, architecture, or full contract probes`,
  );
  const resolverOutputReference = new RegExp(
    `steps\\.${escapeRegExp(resolverId)}\\.outputs\\.`,
  );
  assert.match(
    stages.probe.source,
    resolverOutputReference,
    `macOS ${arch} probe must consume resolver outputs`,
  );
  const outputConsumers = steps.filter(
    (step) =>
      step.index > stages.resolve.index &&
      resolverOutputReference.test(step.source),
  );
  assert.equal(
    outputConsumers[0]?.index,
    stages.probe.index,
    `macOS ${arch} probe must be the first consumer of resolver outputs`,
  );
  const probe = stages.probe.source;
  assert.match(
    probe,
    /(?:test\s+-x|\[\s+-x\s+)[^\n]*(?:Contents\/MacOS\/Electron|ELECTRON)/,
    `macOS ${arch} probe must reject a non-executable Electron fixture`,
  );
  assert.match(
    probe,
    /ELECTRON_RUN_AS_NODE=1[^\n]*(?:Contents\/MacOS\/Electron|ELECTRON)/,
    `macOS ${arch} probe must execute the acquired Electron runtime`,
  );
  assert.match(
    probe,
    /devDependencies\.electron|package\.json[^\n]*electron|electron[^\n]*package\.json/,
    `macOS ${arch} probe must compare against the locked Electron package version`,
  );
  assert.match(
    probe,
    /(?:lipo|file)[^\n]*(?:Contents\/MacOS\/Electron|ELECTRON)/,
    `macOS ${arch} probe must inspect the acquired executable architecture`,
  );
  assert.match(
    probe,
    /(?:test\s+-x|\[\s+-x\s+)[^\n]*LOGSEQ_7ZIP/,
    `macOS ${arch} probe must verify LOGSEQ_7ZIP is executable`,
  );
  assert.match(
    probe,
    /^\s*"?\$\{?LOGSEQ_7ZIP\}?"?(?:\s+|\|)/m,
    `macOS ${arch} probe must execute LOGSEQ_7ZIP`,
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
      GITHUB_OUTPUT: fixture.githubOutput,
      GITHUB_WORKSPACE: fixture.root,
      LOGSEQ_TEST_EXPECTED_ARCH: fixture.executableArch,
      LOGSEQ_TEST_INSTALL_MARKER: fixture.installMarker,
      PATH: `${fixture.binRoot}${path.delimiter}${process.env.PATH ?? ""}`,
      RUNNER_ARCH: arch === "x64" ? "X64" : "ARM64",
      RUNNER_TEMP: path.join(fixture.root, "runner-temp"),
    };
    fs.mkdirSync(env.RUNNER_TEMP, { recursive: true });

    const outputs = {};
    const candidateSteps = [stages.materialize, stages.resolve, stages.probe];
    for (const step of candidateSteps) {
      if (rejectNonExecutable && step.index === stages.probe.index) {
        fs.chmodSync(
          path.join(
            fixture.electronApp,
            "Contents",
            "MacOS",
            "Electron",
          ),
          0o600,
        );
      }
      const script = runScript(step);
      if (!script) continue;
      fs.writeFileSync(fixture.githubOutput, "");
      const expandedScript = expandWorkflowValues(
        script,
        fixture.root,
        outputs,
      )
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
          env: {
            ...env,
            ...stepEnvironment(step.source, fixture.root, outputs),
          },
          shell: false,
        },
      );
      if (rejectNonExecutable && step.index === stages.probe.index) {
        assert.notEqual(
          result.status,
          0,
          `macOS ${arch} real probe accepted a non-executable Electron fixture`,
        );
        return;
      }
      assert.equal(
        result.status,
        0,
        `macOS ${arch} ${step.name ?? "unnamed step"} failed from clean install:\n${result.stdout}\n${result.stderr}`,
      );
      readGithubEnv(fixture.githubEnv, env);
      readGithubOutput(fixture.githubOutput, step, outputs);
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

test("stage parser proves dependency -> materialize -> resolve -> probe -> contracts roles", () => {
  for (const arch of ["x64", "arm64"]) {
    const steps = workflowSteps(
      [
        "    steps:",
        `      - name: Prepare ${arch} desktop dependencies`,
        "        run: |",
        "          pnpm install --frozen-lockfile",
        "        working-directory: ./static",
        `      - name: Electron ${arch} materialize fixture`,
        "        run: pnpm exec install-electron",
        "        working-directory: ./static",
        `      - name: Resolve ${arch} native updater contract tools`,
        "        id: native-tools",
        "        run: |",
        '          seven_zip="$PWD/static/node_modules/7zip-bin/7za"',
        '          electron_app="$PWD/static/node_modules/electron/dist/Electron.app"',
        '          test -x "$seven_zip"',
        '          test -d "$electron_app"',
        '          echo "electron-app=$PWD/static/node_modules/electron/dist/Electron.app" >> "$GITHUB_OUTPUT"',
        '          echo "sevenzip=$PWD/static/node_modules/7zip-bin/7za" >> "$GITHUB_OUTPUT"',
        "        working-directory: .",
        `      - name: Probe ${arch} native updater contract tools`,
        "        env:",
        "          ELECTRON_APP: ${{ steps.native-tools.outputs.electron-app }}",
        "          LOGSEQ_7ZIP: ${{ steps.native-tools.outputs.sevenzip }}",
        "        run: |",
        '          test -x "$ELECTRON_APP/Contents/MacOS/Electron"',
        '          test -x "$LOGSEQ_7ZIP"',
        "      - name: Run project-signed updater native and provider contracts",
        "        run: pnpm test:project-signed-macos-updater",
        "",
      ].join("\n"),
    );
    const stages = macContractStages(steps, arch);
    assert.equal(
      stages.resolve.name,
      `Resolve ${arch} native updater contract tools`,
    );
    assert.equal(
      stages.materialize.name,
      `Electron ${arch} materialize fixture`,
    );
    assert.equal(
      stages.probe.name,
      `Probe ${arch} native updater contract tools`,
    );
    assert.equal(
      stages.contracts.name,
      "Run project-signed updater native and provider contracts",
    );
    assert.deepEqual(
      [
        stages.dependency.index,
        stages.materialize.index,
        stages.resolve.index,
        stages.probe.index,
        stages.contracts.index,
      ],
      [0, 1, 2, 3, 4],
    );
    assert.equal(setting(stages.resolve.source, "id"), "native-tools");
    assert.match(stages.resolve.source, /test -x "\$seven_zip"/);
    assert.match(stages.resolve.source, /test -d "\$electron_app"/);
    assert.doesNotMatch(stages.resolve.source, resolverFullProbePattern);
    assert.match(
      stages.probe.source,
      /steps\.native-tools\.outputs\.(?:electron-app|sevenzip)/,
    );
  }
});

test("clean-install harness rejects a path-only Electron fixture resolver", () => {
  const fixture = createCleanInstallFixture("x64");
  try {
    const installedElectronPackage = JSON.parse(
      fs.readFileSync(
        path.join(
          fixture.staticRoot,
          "node_modules",
          "electron",
          "package.json",
        ),
        "utf8",
      ),
    );
    assert.equal(
      Object.hasOwn(installedElectronPackage, "scripts"),
      false,
      "fixture must not invent an Electron postinstall lifecycle",
    );
    assert.deepEqual(installedElectronPackage.bin, {
      "install-electron": "install.js",
    });
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

for (const command of [
  ["install", "--frozen-lockfile"],
  ["rebuild", "electron"],
]) {
  test(`clean-install harness does not invent an Electron postinstall for pnpm ${command.join(" ")}`, () => {
    const fixture = createCleanInstallFixture("x64");
    try {
      const result = spawnSync(
        path.join(fixture.binRoot, "pnpm"),
        command,
        {
          cwd: fixture.staticRoot,
          encoding: "utf8",
          env: {
            ...process.env,
            LOGSEQ_TEST_EXPECTED_ARCH: fixture.executableArch,
            LOGSEQ_TEST_INSTALL_MARKER: fixture.installMarker,
          },
        },
      );
      assert.equal(result.status, 0, result.stderr);
      assert.equal(fs.existsSync(fixture.installMarker), false);
      assert.equal(fs.existsSync(fixture.electronApp), false);
    } finally {
      fixture.dispose();
    }
  });
}

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
        ["exec", "install-electron"],
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
      assert.equal(
        fs.readFileSync(fixture.electronVersion, "utf8"),
        `v${lockedElectronVersion}\n`,
        "fake installer must reproduce Electron archive dist/version shape",
      );
      const archiveVersionProbe = probeArchiveVersion(
        fixture,
        lockedElectronVersion,
      );
      assert.equal(archiveVersionProbe.status, 0, archiveVersionProbe.stderr);
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
      fs.writeFileSync(fixture.electronVersion, "v0.0.0\n");
      const mismatchedArchiveVersionProbe = probeArchiveVersion(
        fixture,
        lockedElectronVersion,
      );
      assert.notEqual(
        mismatchedArchiveVersionProbe.status,
        0,
        "version mismatch negative control unexpectedly passed",
      );
    } finally {
      fixture.dispose();
    }
  });
}

for (const arch of ["x64", "arm64"]) {
  test(`macOS ${arch} explicitly acquires and probes Electron.app after clean install`, () => {
    executeAcquisitionThroughProbe(arch);
    executeAcquisitionThroughProbe(arch, true);
  });
}
