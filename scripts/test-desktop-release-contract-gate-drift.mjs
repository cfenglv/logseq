#!/usr/bin/env node

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const quickPreflightRelativePath =
  "scripts/desktop-release-preflight.mjs";
const formalGateName = "desktop:test-release-contracts";
const regressionCommand =
  "node ./scripts/test-desktop-release-contract-gate-drift.mjs";
const requiredReleaseContracts = [
  "node ./scripts/test-desktop-runtime-packaging-contract.mjs",
  "node ./scripts/test-updater-private-material-policy-contract.mjs",
  "node ./scripts/test-local-keychain-release-signing-contract.mjs",
  "node ./scripts/test-shipit-process-outcome-contract.mjs",
  "node ./scripts/test-project-signed-macos-updater.mjs --physical-shipit-contract",
  "node ./scripts/test-desktop-preflight-preload-contract.mjs",
  "node ./scripts/test-project-signed-macos-updater.mjs --isolated-signer-algorithm-contract",
  "node ./scripts/test-project-signed-macos-updater.mjs --managed-signer-native-key-alignment-contract",
  "node ./scripts/test-project-signing-policy-contract.mjs",
  "node ./scripts/test-local-project-update-signing-contract.mjs",
  "node ./scripts/test-desktop-sidecar-release-contract.mjs",
  "node ./scripts/test-updater-install-entry-contract.mjs",
  "node ./scripts/test-selfhost-macos-user-guidance.mjs",
  "node ./scripts/test-macos-updater-signature-config.mjs",
  "node ./scripts/test-selfhost-nightly-semver-contract.mjs",
  "node ./scripts/test-packaged-project-signature-runtime.mjs",
  regressionCommand,
];

const read = (relativePath, root = repoRoot) =>
  fs.readFileSync(path.join(root, relativePath), "utf8");

const commandSegments = (command) =>
  command
    .split(/\s*&&\s*/)
    .map((segment) => segment.trim().replace(/\s+/g, " "))
    .filter(Boolean);

const run = (executable, args, options = {}) => {
  const result = spawnSync(executable, args, {
    cwd: options.cwd ?? repoRoot,
    encoding: "utf8",
    env: options.env ?? process.env,
    stdio: ["ignore", "pipe", "pipe"],
  });
  if (result.error) throw result.error;
  return {
    output: `${result.stdout || ""}${result.stderr || ""}`,
    status: result.status,
  };
};

const symlinkRepositoryEntry = (source, destination, directory) => {
  fs.symlinkSync(source, destination, directory ? "dir" : "file");
};

const createQuickPreflightProbe = () => {
  const root = fs.mkdtempSync(
    path.join(os.tmpdir(), "logseq-release-contract-drift-"),
  );
  for (const entry of fs.readdirSync(repoRoot, { withFileTypes: true })) {
    if (
      [".git", "node_modules", "package.json", "scripts"].includes(
        entry.name,
      )
    ) {
      continue;
    }
    symlinkRepositoryEntry(
      path.join(repoRoot, entry.name),
      path.join(root, entry.name),
      entry.isDirectory(),
    );
  }

  const scriptsRoot = path.join(root, "scripts");
  fs.mkdirSync(scriptsRoot);
  for (const entry of fs.readdirSync(
    path.join(repoRoot, "scripts"),
    { withFileTypes: true },
  )) {
    if (entry.name === path.basename(quickPreflightRelativePath)) continue;
    symlinkRepositoryEntry(
      path.join(repoRoot, "scripts", entry.name),
      path.join(scriptsRoot, entry.name),
      entry.isDirectory(),
    );
  }
  fs.copyFileSync(
    path.join(repoRoot, quickPreflightRelativePath),
    path.join(root, quickPreflightRelativePath),
  );

  const futureContractPath = path.join(
    scriptsRoot,
    "test-future-desktop-release-contract.mjs",
  );
  fs.writeFileSync(
    futureContractPath,
    '#!/usr/bin/env node\nconsole.log("[future-release-contract] PASS");\n',
  );

  const fakeBin = path.join(root, "test-bin");
  fs.mkdirSync(fakeBin);
  const fakeGit = path.join(fakeBin, "git");
  fs.writeFileSync(
    fakeGit,
    [
      "#!/bin/sh",
      'if [ "$1" = "status" ]; then',
      "  exit 0",
      "fi",
      'exec /usr/bin/git "$@"',
      "",
    ].join("\n"),
    { mode: 0o700 },
  );

  const originalPackage = JSON.parse(read("package.json"));
  const writeFormalGate = (command) => {
    const packageJson = structuredClone(originalPackage);
    packageJson.scripts[formalGateName] = command;
    fs.writeFileSync(
      path.join(root, "package.json"),
      `${JSON.stringify(packageJson, null, 2)}\n`,
    );
  };
  const runQuickPreflight = (command) => {
    writeFormalGate(command);
    return run(
      process.execPath,
      [path.join(root, quickPreflightRelativePath)],
      {
        cwd: root,
        env: {
          ...process.env,
          PATH: `${fakeBin}${path.delimiter}${process.env.PATH ?? ""}`,
        },
      },
    );
  };

  return {
    dispose: () =>
      fs.rmSync(root, { force: true, recursive: true }),
    runQuickPreflight,
  };
};

const assertQuickPass = (probe, command, label) => {
  const result = probe.runQuickPreflight(command);
  assert.equal(
    result.status,
    0,
    `${label} was rejected by quick preflight:\n${result.output}`,
  );
};

const missingContractPattern = (command) => {
  const scriptName = command.match(/scripts\/([^\s]+\.mjs)/)?.[1];
  assert.ok(scriptName, `cannot identify contract script in ${command}`);
  return new RegExp(
    `(?:missing|required|must\\s+(?:include|run|execute|expose))[\\s\\S]{0,320}${scriptName.replace(
      /[.*+?^${}()|[\]\\]/g,
      "\\$&",
    )}|${scriptName.replace(
      /[.*+?^${}()|[\]\\]/g,
      "\\$&",
    )}[\\s\\S]{0,320}(?:missing|required|must\\s+(?:include|run|execute|expose))`,
    "i",
  );
};

const cases = [];
const addCase = (name, test) => cases.push([name, test]);

const packageJson = JSON.parse(read("package.json"));
const formalGate = packageJson.scripts?.[formalGateName] ?? "";
const formalSegments = commandSegments(formalGate);

addCase("formal gate contains every required release contract exactly once", () => {
  assert.equal(
    new Set(formalSegments).size,
    formalSegments.length,
    "formal desktop release gate contains duplicate commands",
  );
  for (const required of requiredReleaseContracts) {
    assert.equal(
      formalSegments.filter((segment) => segment === required).length,
      1,
      `formal desktop release gate must run exactly once: ${required}`,
    );
  }
});

addCase("quick preflight contains no exact whole-command shadow copy", () => {
  const source = read(quickPreflightRelativePath);
  assert.doesNotMatch(
    source,
    /scripts\?\.\["desktop:test-release-contracts"\]\s*!==\s*["']/,
    "quick preflight compares the formal gate against a hidden whole-command copy",
  );
  assert.doesNotMatch(
    source,
    /scripts\?\.\["desktop:test-release-contracts"\][\s\S]{0,2400}test-packaged-project-signature-runtime\.mjs["']/,
    "quick preflight embeds a shadow list of the complete formal gate",
  );
});

addCase(
  "quick preflight accepts the authoritative gate plus future additions and reordering",
  () => {
    const probe = createQuickPreflightProbe();
    try {
      assertQuickPass(probe, formalGate, "authoritative formal gate");
      assertQuickPass(
        probe,
        [
          ...formalSegments,
          "node ./scripts/test-future-desktop-release-contract.mjs --future",
        ].join(" && "),
        "formal gate with a future contract",
      );
      assertQuickPass(
        probe,
        [...formalSegments].reverse().join(" && "),
        "reordered formal gate",
      );
    } finally {
      probe.dispose();
    }
  },
);

addCase("quick preflight fails closed when any required contract is removed", () => {
  const probe = createQuickPreflightProbe();
  try {
    for (const required of requiredReleaseContracts) {
      const mutated = formalSegments.filter(
        (segment) => segment !== required,
      );
      assert.equal(
        mutated.length,
        formalSegments.length - 1,
        `required mutation did not remove exactly one command: ${required}`,
      );
      const result = probe.runQuickPreflight(mutated.join(" && "));
      assert.notEqual(
        result.status,
        0,
        `quick preflight accepted removal of required contract: ${required}`,
      );
      assert.match(
        result.output,
        missingContractPattern(required),
        `quick preflight failed for the wrong reason after removing ${required}:\n${result.output}`,
      );
    }
  } finally {
    probe.dispose();
  }
});

let passed = 0;
let failed = 0;
for (const [name, test] of cases) {
  try {
    await test();
    passed += 1;
    console.log(`[desktop-release-contract-drift] PASS ${name}`);
  } catch (error) {
    failed += 1;
    console.error(
      `[desktop-release-contract-drift] FAIL ${name}: ${
        error instanceof Error ? error.message : error
      }`,
    );
  }
}

console.log(
  `[desktop-release-contract-drift] SUMMARY ${passed} passed, ${failed} failed`,
);
if (failed > 0) process.exitCode = 1;
