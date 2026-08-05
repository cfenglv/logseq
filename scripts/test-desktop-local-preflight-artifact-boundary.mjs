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
const invalidButWellFormedSha =
  "499b5dcc9cbb65579140707eff3745fd9432777b";

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
  const isolationMarker = path.join(root, "isolation-evidence.log");
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
  write("static/required-static.txt", "required\n");
  write("static/node_modules/source-only.txt", "must not be copied\n");
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
if [ "\${1:-}" = "install" ] && [ "$PWD" != "$PREFLIGHT_FIXTURE_ROOT/static" ]; then
  {
    if [ -e "$PWD/node_modules/source-only.txt" ]; then echo node_modules-copied; else echo node_modules-excluded; fi
    if [ -f "$PWD/required-static.txt" ]; then echo static-file-present; else echo static-file-missing; fi
    if [ -f "$PWD/../scripts/verify-desktop-runtime-revisions.mjs" ]; then echo verifier-present; else echo verifier-missing; fi
    if [ -f "$PWD/../dist/db-worker-node.js" ]; then echo db-worker-present; else echo db-worker-missing; fi
  } > "$PREFLIGHT_ISOLATION_MARKER"
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
      PREFLIGHT_ISOLATION_MARKER: isolationMarker,
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
      isolationEvidence: fs.existsSync(isolationMarker)
        ? fs.readFileSync(isolationMarker, "utf8").trim().split("\n")
        : [],
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
      ["malformed source", { releaseSourceSha: "499b5dcc9cbb", revision: head }],
      ["source with whitespace", { releaseSourceSha: `${head} `, revision: head }],
      ["revision with whitespace", { releaseSourceSha: head, revision: ` ${head}` }],
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

    for (const [label, environment] of [
      ["bare command", {}],
      ["source only", { releaseSourceSha: head }],
      ["revision only", { revision: head }],
      ["exact pair", { releaseSourceSha: head, revision: head }],
    ]) {
      const matching = probe.run(environment);
      assert.equal(
        matching.externalCommandReached,
        true,
        `${label} was rejected before the external-command control:\n${matching.output}`,
      );
    }
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

test("release workspace excludes source node_modules and retains required inputs", () => {
  const fixture = createFullPreflightFixture();
  try {
    const result = fixture.run();
    assert.equal(
      result.status,
      0,
      `isolation behavior fixture did not reach FULL PASS:\n${result.output}`,
    );
    assert.deepEqual(result.isolationEvidence, [
      "node_modules-excluded",
      "static-file-present",
      "verifier-present",
      "db-worker-present",
    ]);
  } finally {
    fixture.dispose();
  }
});
