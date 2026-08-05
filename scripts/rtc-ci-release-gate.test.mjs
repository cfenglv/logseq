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
const rtcWorkflowPath = ".github/workflows/clj-rtc-e2e.yml";
const releaseWorkflowPath =
  ".github/workflows/build-desktop-release.yml";
const requiredTasks = [
  "run-rtc-extra-test",
  "run-rtc-extra-part2-test",
];

const read = (relativePath) =>
  fs.readFileSync(path.join(repoRoot, relativePath), "utf8");

const scalar = (value) =>
  value.trim().replace(/^(['"])(.*)\1$/, "$2");

const indentation = (line) => line.match(/^ */)[0].length;

const truthyLiteral = (value) =>
  ["true", "yes", "on", "1"].includes(scalar(value).toLowerCase());

const parseInlineList = (value) => {
  const match = value.trim().match(/^\[(.*)\]$/);
  if (!match) return null;
  return match[1]
    .split(",")
    .map((item) => scalar(item))
    .filter(Boolean);
};

const parseJobs = (source) => {
  const lines = source.split(/\r?\n/);
  const jobsLine = lines.findIndex((line) => line === "jobs:");
  assert.notEqual(jobsLine, -1, "workflow must define top-level jobs");

  const starts = [];
  for (let index = jobsLine + 1; index < lines.length; index += 1) {
    const match = lines[index].match(/^  ([A-Za-z0-9_-]+):\s*$/);
    if (match) starts.push({ index, name: match[1] });
  }

  return starts.map((entry, entryIndex) => {
    const end = starts[entryIndex + 1]?.index ?? lines.length;
    const block = lines.slice(entry.index + 1, end);
    const field = (indent, name) => {
      const match = block
        .map((line) => line.match(new RegExp(`^ {${indent}}${name}:\\s*(.*?)\\s*$`)))
        .find(Boolean);
      return match?.[1];
    };

    const strategyStart = block.findIndex(
      (line) => line === "    strategy:",
    );
    let strategy = [];
    if (strategyStart !== -1) {
      let strategyEnd = block.length;
      for (let index = strategyStart + 1; index < block.length; index += 1) {
        if (block[index].trim() && indentation(block[index]) <= 4) {
          strategyEnd = index;
          break;
        }
      }
      strategy = block.slice(strategyStart + 1, strategyEnd);
    }

    const strategyField = (name) => {
      const match = strategy
        .map((line) => line.match(new RegExp(`^ {6}${name}:\\s*(.*?)\\s*$`)))
        .find(Boolean);
      return match?.[1];
    };

    const matrix = new Map();
    const matrixStart = strategy.findIndex(
      (line) => line === "      matrix:",
    );
    if (matrixStart !== -1) {
      for (let index = matrixStart + 1; index < strategy.length; index += 1) {
        const line = strategy[index];
        if (line.trim() && indentation(line) <= 6) break;
        const keyMatch = line.match(
          /^ {8}([A-Za-z0-9_-]+):\s*(.*?)\s*$/,
        );
        if (!keyMatch) continue;
        const [, key, rawValue] = keyMatch;
        const inline = parseInlineList(rawValue);
        if (inline) {
          matrix.set(key, inline);
          continue;
        }
        const values = [];
        for (let item = index + 1; item < strategy.length; item += 1) {
          const itemMatch = strategy[item].match(/^ {10}-\s*(.*?)\s*$/);
          if (!itemMatch) break;
          values.push(scalar(itemMatch[1]));
          index = item;
        }
        matrix.set(key, values);
      }
    }

    const stepsStart = block.findIndex((line) => line === "    steps:");
    const steps = [];
    if (stepsStart !== -1) {
      const stepStarts = [];
      for (let index = stepsStart + 1; index < block.length; index += 1) {
        if (/^ {6}-(?:\s|$)/.test(block[index])) stepStarts.push(index);
      }
      for (let index = 0; index < stepStarts.length; index += 1) {
        const start = stepStarts[index];
        const stepEnd = stepStarts[index + 1] ?? block.length;
        const stepLines = block.slice(start, stepEnd);
        const normalized = stepLines.map((line, lineIndex) =>
          lineIndex === 0 ? line.replace(/^ {6}-\s*/, "        ") : line,
        );
        const stepField = (name) => {
          const match = normalized
            .map((line) => line.match(new RegExp(`^ {8}${name}:\\s*(.*?)\\s*$`)))
            .find(Boolean);
          return match?.[1];
        };
        const runIndex = normalized.findIndex((line) =>
          /^ {8}run:\s*/.test(line),
        );
        let command = "";
        if (runIndex !== -1) {
          const raw = normalized[runIndex].replace(/^ {8}run:\s*/, "");
          if (["|", ">", "|-", ">-"].includes(raw.trim())) {
            const commandLines = [];
            for (let lineIndex = runIndex + 1; lineIndex < normalized.length; lineIndex += 1) {
              const line = normalized[lineIndex];
              if (line.trim() && indentation(line) <= 8) break;
              commandLines.push(line.replace(/^ {10}/, ""));
            }
            command = commandLines.join("\n");
          } else {
            command = raw;
          }
        }
        steps.push({
          command,
          continueOnError: stepField("continue-on-error"),
          condition: stepField("if"),
          uses: stepField("uses"),
        });
      }
    }

    return {
      block,
      condition: field(4, "if"),
      continueOnError: field(4, "continue-on-error"),
      failFast: strategyField("fail-fast"),
      matrix,
      maxParallel: strategyField("max-parallel"),
      name: entry.name,
      needs: field(4, "needs"),
      steps,
      uses: field(4, "uses"),
    };
  });
};

const taskFromValue = (value) =>
  requiredTasks.includes(value) ? value : null;

const taskInvocations = (job) => {
  const invocations = [];
  for (const [stepIndex, step] of job.steps.entries()) {
    for (const [matrixName, values] of job.matrix) {
      const matrixReference = new RegExp(
        `matrix\\.${matrixName.replaceAll("-", "[-_]")}(?:\\s*}})?`,
      );
      if (!matrixReference.test(step.command)) continue;
      for (const value of values) {
        const task = taskFromValue(value);
        if (task) {
          invocations.push({ job, matrixName, step, stepIndex, task });
        }
      }
    }
    for (const task of requiredTasks) {
      const pattern = new RegExp(
        `(^|[\\s'\"\\x60])${task}($|[\\s'\"\\x60])`,
      );
      if (pattern.test(step.command)) {
        invocations.push({
          job,
          matrixName: null,
          step,
          stepIndex,
          task,
        });
      }
    }
  }
  return invocations;
};

const commandTimeoutSeconds = (command) => {
  const match = command.match(/(?:^|\s)timeout\s+(\d+)([smh])(?:\s|$)/i);
  if (!match) return null;
  const factors = { s: 1, m: 60, h: 3600 };
  return Number(match[1]) * factors[match[2].toLowerCase()];
};

const conditionRunsAfterFailure = (condition) => {
  const normalized = scalar(condition ?? "")
    .replace(/^\$\{\{\s*/, "")
    .replace(/\s*}}$/, "")
    .replaceAll(/\s/g, "")
    .toLowerCase();
  return normalized === "always()" || normalized === "!cancelled()";
};

const jobNeeds = (job) => {
  const value = job.needs ?? "";
  return (
    parseInlineList(value) ??
    scalar(value)
      .split(/\s*,\s*/)
      .filter(Boolean)
  );
};

const workflowViolations = (source) => {
  const jobs = parseJobs(source);
  const invocations = jobs.flatMap(taskInvocations);
  const violations = [];
  const tasks = invocations.map(({ task }) => task).sort();
  assert.deepEqual(
    [...requiredTasks].sort(),
    ["run-rtc-extra-part2-test", "run-rtc-extra-test"],
  );
  if (
    tasks.length !== requiredTasks.length ||
    tasks.some((task, index) => task !== [...requiredTasks].sort()[index])
  ) {
    violations.push(
      `RTC workflow must schedule each required shard exactly once; found ${tasks.join(", ") || "none"}`,
    );
  }

  const taskJobs = [...new Set(invocations.map(({ job }) => job))];
  for (const job of taskJobs) {
    if (truthyLiteral(job.continueOnError ?? "false")) {
      violations.push(`${job.name} RTC job must not continue on error`);
    }
  }

  for (const { step, task } of invocations) {
    if (truthyLiteral(step.continueOnError ?? "false")) {
      violations.push(`${task} step must not continue on error`);
    }
    if (
      /\b(?:retry|retries|attempts?)\b/i.test(
        `${step.uses ?? ""}\n${step.command}`,
      )
    ) {
      violations.push(`${task} must not be wrapped in retries`);
    }
    if (
      /\|\|\s*true\b|;\s*true\b|\bset\s+\+e\b|\bexit\s+0\b/i.test(
        step.command,
      )
    ) {
      violations.push(`${task} must not soften a failing exit status`);
    }
    const timeoutSeconds = commandTimeoutSeconds(step.command);
    if (timeoutSeconds === null || timeoutSeconds > 30 * 60) {
      violations.push(
        `${task} must keep the existing bounded 30 minute outer timeout`,
      );
    }
  }

  const oneMatrixJob =
    taskJobs.length === 1 &&
    invocations.length === requiredTasks.length &&
    invocations.every(({ matrixName }) => matrixName) &&
    new Set(invocations.map(({ matrixName }) => matrixName).filter(Boolean))
      .size === 1;
  const oneSerialStepJob =
    taskJobs.length === 1 &&
    invocations.length === requiredTasks.length &&
    invocations.every(({ matrixName }) => !matrixName) &&
    new Set(
      invocations
        .map(({ stepIndex }) => stepIndex)
        .filter((value) => value !== undefined),
    ).size === requiredTasks.length;
  const twoSerialJobs =
    taskJobs.length === requiredTasks.length &&
    invocations.length === requiredTasks.length &&
    invocations.every(({ matrixName }) => !matrixName);

  if (oneMatrixJob) {
    const taskJob = taskJobs[0];
    if (Number(scalar(taskJob.maxParallel ?? "")) !== 1) {
      violations.push(
        "shared RTC matrix shards need a statically proven serial scheduler",
      );
    }
    if (scalar(taskJob.failFast ?? "").toLowerCase() !== "false") {
      violations.push(
        "serialized matrix must disable fail-fast so both shards are attempted",
      );
    }
    if (taskJob.condition) {
      violations.push("RTC matrix job must not be conditionally skipped");
    }
    for (const { step, task } of invocations) {
      if (step.condition) {
        violations.push(`${task} matrix step must not be conditionally skipped`);
      }
    }
  } else if (oneSerialStepJob) {
    const ordered = [...invocations].sort(
      (left, right) => left.stepIndex - right.stepIndex,
    );
    if (taskJobs[0].condition || ordered[0].step.condition) {
      violations.push(
        "first RTC shard in a serial step job must be unconditional",
      );
    }
    for (const { step, task } of ordered.slice(1)) {
      if (!conditionRunsAfterFailure(step.condition)) {
        violations.push(
          `${task} must use always() or !cancelled() so a prior shard failure cannot skip it`,
        );
      }
    }
  } else if (twoSerialJobs) {
    const [left, right] = taskJobs;
    const leftNeedsRight = jobNeeds(left).includes(right.name);
    const rightNeedsLeft = jobNeeds(right).includes(left.name);
    if (leftNeedsRight === rightNeedsLeft) {
      violations.push(
        "two RTC jobs need exactly one dependency edge to prove they cannot overlap",
      );
    } else {
      const first = leftNeedsRight ? right : left;
      const second = leftNeedsRight ? left : right;
      if (first.condition) {
        violations.push("first RTC job must be unconditional");
      }
      if (!conditionRunsAfterFailure(second.condition)) {
        violations.push(
          "dependent RTC job must use always() or !cancelled() so predecessor failure cannot skip it",
        );
      }
      for (const { job, step, task } of invocations) {
        if (step.condition) {
          violations.push(
            `${task} step in ${job.name} must rely on the proven job edge, not a step condition`,
          );
        }
      }
    }
  } else if (invocations.length === requiredTasks.length) {
    violations.push(
      "RTC shard topology does not prove serial execution plus failure-safe scheduling",
    );
  }
  return violations;
};

const assertReleaseWorkflowBinding = (source) => {
  const jobs = parseJobs(source);
  const rtcJobs = jobs.filter(
    (job) => scalar(job.uses ?? "") === "./.github/workflows/clj-rtc-e2e.yml",
  );
  assert.equal(
    rtcJobs.length,
    1,
    "desktop release workflow must invoke the RTC browser workflow exactly once",
  );
  assert.ok(
    !truthyLiteral(rtcJobs[0].continueOnError ?? "false"),
    "desktop release RTC browser job must not continue on error",
  );
  const dependentJobs = jobs.filter((job) => {
    const needs = job.needs ?? "";
    return new RegExp(`(?:^|[\\s[,]\\s*)${rtcJobs[0].name}(?:$|[\\s,]])`).test(
      needs,
    );
  });
  assert.ok(
    dependentJobs.length > 0,
    "a successful RTC browser workflow must be a hard dependency of release compilation",
  );
  assert.ok(
    dependentJobs.every((job) => !job.block.some((line) => /needs\.[^.]+\.result\s*!=\s*['"]success/.test(line))),
    "release compilation must not override a failed RTC browser dependency",
  );
};

const assertLocalGateContract = () => {
  const gate = read("scripts/run-rtc-prepush.mjs");
  const part1 = gate.indexOf('["scripts/run-rtc-e2e.mjs", "rtc-extra-test"]');
  const part2 = gate.indexOf(
    '["scripts/run-rtc-e2e.mjs", "rtc-extra-part2-test"]',
  );
  assert.ok(part1 !== -1, "local RTC gate must run part 1");
  assert.ok(part2 !== -1, "local RTC gate must run part 2");
  assert.ok(part1 < part2, "local RTC gate must run shared-state shards serially");
  assert.match(
    gate,
    /if \(result\.status !== 0\)[\s\S]*?throw new Error/,
    "local RTC gate must reject a non-zero child status",
  );
  assert.doesNotMatch(
    gate.slice(part1, part2),
    /Promise\.all|\bretry\b|continue-on-error|\|\|\s*true/i,
    "local RTC gate must not parallelize, retry, or soften the shards",
  );
  const gateTimeouts = [
    ...gate.matchAll(/timeout:\s*(\d+)\s*\*\s*60\s*\*\s*1000/g),
  ].map((match) => Number(match[1]));
  assert.equal(
    gateTimeouts.length,
    2,
    "both local RTC shards must declare a bounded timeout",
  );
  assert.ok(
    gateTimeouts.every((minutes) => minutes <= 30),
    "local RTC shard timeout must not be relaxed beyond 30 minutes",
  );

  const graph = read("clj-e2e/src/logseq/e2e/graph.clj");
  const cloudReadyTimeout = graph.match(
    /\(def \^:private cloud-ready-timeout-ms\s+(\d+)\)/,
  );
  assert.ok(
    cloudReadyTimeout && Number(cloudReadyTimeout[1]) <= 60_000,
    "the regression gate must not be made green by relaxing cloud readiness beyond 60 seconds",
  );
};

const createBbShim = () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "rtc-ci-shim-"));
  const executable = path.join(root, process.platform === "win32" ? "bb.cmd" : "bb");
  const marker = path.join(root, "calls.log");
  const preload = path.join(root, "no-network-preload.cjs");
  fs.writeFileSync(
    preload,
    `const net = require("node:net");
net.createServer = () => {
  const server = {
    address: () => ({ address: "127.0.0.1", family: "IPv4", port: 43127 }),
    close: (callback) => queueMicrotask(() => callback?.()),
    listen: (...args) => {
      const callback = args.at(-1);
      if (typeof callback === "function") queueMicrotask(callback);
      return server;
    },
    once: () => server,
    unref: () => server,
  };
  return server;
};
global.fetch = async () => ({ status: 200 });
`,
  );
  const shim = `#!/usr/bin/env node
const fs = require("node:fs");
const args = process.argv.slice(2);
if (args[0] === "serve") {
  process.on("SIGTERM", () => process.exit(0));
  process.on("SIGINT", () => process.exit(0));
  setInterval(() => {}, 1000);
} else {
  fs.appendFileSync(process.env.RTC_FAKE_MARKER, args.join(" ") + "\\n");
  process.exit(Number(process.env.RTC_FAKE_TASK_EXIT));
}
`;
  fs.writeFileSync(executable, shim);
  fs.chmodSync(executable, 0o755);
  return { marker, preload, root };
};

const runRtcWrapperWithFakeTask = (task, exitCode) => {
  const { marker, preload, root } = createBbShim();
  const result = spawnSync(
    process.execPath,
    [path.join(repoRoot, "scripts/run-rtc-e2e.mjs"), task],
    {
      cwd: repoRoot,
      encoding: "utf8",
      env: {
        ...process.env,
        PATH: `${root}${path.delimiter}${process.env.PATH}`,
        NODE_OPTIONS: `${process.env.NODE_OPTIONS ?? ""} --require ${preload}`.trim(),
        RTC_FAKE_MARKER: marker,
        RTC_FAKE_TASK_EXIT: String(exitCode),
      },
      timeout: 15_000,
    },
  );
  const calls = fs.existsSync(marker) ? fs.readFileSync(marker, "utf8") : "";
  fs.rmSync(root, { force: true, recursive: true });
  return { ...result, calls, output: `${result.stdout ?? ""}${result.stderr ?? ""}` };
};

test("RTC GitHub job serializes both shared-state shards and preserves failures", () => {
  assert.deepEqual(workflowViolations(read(rtcWorkflowPath)), []);
});

test("desktop release workflow is hard-bound to RTC browser success", () => {
  assertReleaseWorkflowBinding(read(releaseWorkflowPath));
});

test("local RTC gate keeps the same serial, bounded, shared-resource contract", () => {
  assertLocalGateContract();
});

test("workflow contract is order-independent across matrix syntax variants", () => {
  const baseline = read(rtcWorkflowPath);
  const serialized = baseline.replace(
    "      fail-fast: false\n",
    "      fail-fast: false\n      max-parallel: 1\n",
  );
  assert.deepEqual(workflowViolations(serialized), []);
  assert.deepEqual(
    workflowViolations(
      serialized.replace(
        "[run-rtc-extra-test, run-rtc-extra-part2-test]",
        "[run-rtc-extra-part2-test, run-rtc-extra-test]",
      ),
    ),
    [],
    "matrix ordering must not affect the contract",
  );
  assert.deepEqual(
    workflowViolations(serialized.replace("  test:\n", "  browser-contract:\n")),
    [],
    "job renaming must not affect the contract",
  );
  assert.deepEqual(
    workflowViolations(
      serialized.replace(
        "        test-task: [run-rtc-extra-test, run-rtc-extra-part2-test]",
        "        test-task:\n          - run-rtc-extra-part2-test\n          - run-rtc-extra-test",
      ),
    ),
    [],
    "block-style matrix lists must preserve the same semantics",
  );
});

test("topology proof accepts controlled alternatives and rejects unsafe scheduling", () => {
  const matrixTopology = ({
    continueOnError = false,
    failFast = false,
    maxParallel = 1,
    taskValues = requiredTasks,
    command = "timeout 30m bb ${{ matrix.shard }}",
  } = {}) => `jobs:
  rtc-shards:
${continueOnError ? "    continue-on-error: true\n" : ""}    strategy:
      fail-fast: ${failFast}
      max-parallel: ${maxParallel}
      matrix:
        shard: [${taskValues.join(", ")}]
    steps:
      - run: ${command}
`;
  const stepTopology = ({ secondCondition = "${{ always() }}" } = {}) => `jobs:
  rtc-shards:
    steps:
      - run: timeout 30m bb run-rtc-extra-test
      - if: ${secondCondition}
        run: timeout 30m bb run-rtc-extra-part2-test
`;
  const jobTopology = ({
    needs = "rtc-part-1",
    secondCondition = "${{ always() }}",
  } = {}) => `jobs:
  rtc-part-1:
    steps:
      - run: timeout 30m bb run-rtc-extra-test
  rtc-part-2:
${needs === null ? "" : `    needs: ${needs}\n`}${secondCondition === null ? "" : `    if: ${secondCondition}\n`}    steps:
      - run: timeout 30m bb run-rtc-extra-part2-test
`;

  const safeTopologies = [
    ["serialized matrix", matrixTopology()],
    ["one job with failure-safe second step", stepTopology()],
    ["dependent jobs with failure-safe successor", jobTopology()],
    [
      "reversed dependent jobs",
      `jobs:
  rtc-part-2:
    steps:
      - run: timeout 30m bb run-rtc-extra-part2-test
  rtc-part-1:
    needs: rtc-part-2
    if: \${{ !cancelled() }}
    steps:
      - run: timeout 30m bb run-rtc-extra-test
`,
    ],
  ];
  for (const [name, topology] of safeTopologies) {
    assert.deepEqual(
      workflowViolations(topology),
      [],
      `${name} should satisfy the semantic RTC gate contract`,
    );
  }

  const maskingMutations = [
    ["parallel matrix", matrixTopology({ maxParallel: 2 })],
    ["fail-fast matrix", matrixTopology({ failFast: true })],
    ["continue on error", matrixTopology({ continueOnError: true })],
    [
      "missing shard",
      matrixTopology({ taskValues: [requiredTasks[0]] }),
    ],
    [
      "serial steps skip after failure",
      stepTopology({ secondCondition: "${{ success() }}" }),
    ],
    [
      "independent jobs",
      jobTopology({ needs: null, secondCondition: null }),
    ],
    [
      "dependent job skips after failure",
      jobTopology({ secondCondition: null }),
    ],
    [
      "relaxed timeout",
      matrixTopology({ command: "timeout 31m bb ${{ matrix.shard }}" }),
    ],
    [
      "retry wrapper",
      matrixTopology({
        command: "retry timeout 30m bb ${{ matrix.shard }}",
      }),
    ],
    [
      "soft exit",
      matrixTopology({
        command: "timeout 30m bb ${{ matrix.shard }} || true",
      }),
    ],
  ];
  for (const [name, mutation] of maskingMutations) {
    assert.notDeepEqual(
      workflowViolations(mutation),
      [],
      `${name} mutation unexpectedly satisfied the RTC release contract`,
    );
  }
});

for (const task of ["rtc-extra-test", "rtc-extra-part2-test"]) {
  test(`${task} wrapper propagates the test process exit status`, () => {
    const failed = runRtcWrapperWithFakeTask(task, 23);
    assert.notEqual(failed.status, 0, failed.output);
    assert.match(
      failed.calls,
      new RegExp(`^${task}(?:\\s|$)`),
      failed.output,
    );
    assert.doesNotMatch(failed.output, /\[rtc-e2e\] PASS/);

    const passed = runRtcWrapperWithFakeTask(task, 0);
    assert.equal(passed.status, 0, passed.output);
    assert.match(passed.calls, new RegExp(`^${task}(?:\\s|$)`));
    assert.match(passed.output, /\[rtc-e2e\] PASS/);
  });
}
