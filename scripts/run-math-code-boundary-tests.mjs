import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const repoRoot = fileURLToPath(new URL("../", import.meta.url));
const maxBuffer = 16 * 1024 * 1024;
const formalTimeoutMs = 120_000;

function run(command, args) {
  const result = spawnSync(command, args, {
    cwd: repoRoot,
    encoding: "utf8",
    env: process.env,
    maxBuffer,
    timeout: formalTimeoutMs,
    shell: false,
  });
  process.stdout.write(result.stdout ?? "");
  process.stderr.write(result.stderr ?? "");
  if (result.error) throw result.error;
  return result;
}

export function validateTestSummary(output, status) {
  const summary = output.match(
    /Ran\s+(\d+)\s+tests\s+containing\s+(\d+)\s+assertions\.\s*(\d+)\s+failures,\s+(\d+)\s+errors\./,
  );
  if (!summary) {
    throw new Error("Math CodeMirror test runner emitted no parseable summary");
  }

  const [, testCountText, assertionCountText, failureCountText, errorCountText] = summary;
  const testCount = Number(testCountText);
  const assertionCount = Number(assertionCountText);
  const failureCount = Number(failureCountText);
  const errorCount = Number(errorCountText);
  if (
    status !== 0 ||
    testCount <= 0 ||
    assertionCount <= 0 ||
    testCount !== 9 ||
    assertionCount !== 42 ||
    failureCount !== 0 ||
    errorCount !== 0
  ) {
    throw new Error(
      `Math CodeMirror gate failed: status=${status}, tests=${testCount}, assertions=${assertionCount}, failures=${failureCount}, errors=${errorCount}`,
    );
  }
  return { testCount, assertionCount, failureCount, errorCount };
}

export function main() {
  const compile = run("clojure", [
    "-M:test:math-code-boundary-test",
    "compile",
    "math-code-boundary-test",
  ]);
  if (compile.status !== 0) {
    throw new Error(`Math CodeMirror test compilation failed (${compile.status})`);
  }

  const tests = run("node", [
    "static/math-code-boundary-tests.js",
    "-n",
    "frontend.extensions.code-boundary-test",
  ]);
  const output = `${tests.stdout ?? ""}\n${tests.stderr ?? ""}`;
  validateTestSummary(output, tests.status);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  main();
}
