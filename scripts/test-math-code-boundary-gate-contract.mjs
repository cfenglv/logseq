import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import { validateTestSummary } from "./run-math-code-boundary-tests.mjs";

const packageJson = JSON.parse(
  fs.readFileSync(new URL("../package.json", import.meta.url), "utf8"),
);

test("the formal frontend gate executes the isolated Math CodeMirror target", () => {
  assert.equal(
    packageJson.scripts["cljs:test-math-code-boundary"],
    "node ./scripts/run-math-code-boundary-tests.mjs",
  );
  assert.equal(
    packageJson.scripts.test,
    "run-s cljs:test cljs:run-test cljs:test-math-code-boundary",
    "pnpm test must not omit the browser-isolated Math boundary suite",
  );

  const runner = fs.readFileSync(
    new URL("./run-math-code-boundary-tests.mjs", import.meta.url),
    "utf8",
  );
  for (const requiredToken of [
    "-M:test:math-code-boundary-test",
    "compile",
    "math-code-boundary-test",
    "static/math-code-boundary-tests.js",
    "frontend.extensions.code-boundary-test",
  ]) {
    assert.ok(runner.includes(requiredToken), `runner must include ${requiredToken}`);
  }
  assert.match(runner, /spawnSync\(/, "commands must execute without a shell");
  assert.match(runner, /shell:\s*false/);
  assert.match(runner, /const formalTimeoutMs = 120_000;/,
    "the formal runner timeout is exactly 120000ms");
  assert.match(runner, /timeout:\s*formalTimeoutMs/,
    "compile and test subprocesses must have a finite formal timeout");
  assert.match(runner, /const summary = output\.match\(/);
  assert.match(runner, /if \(!summary\)/, "a missing summary must fail closed");
  assert.match(runner, /testCount\s*<=\s*0/, "zero discovered tests must fail closed");
  assert.match(runner, /failureCount\s*!==\s*0/);
  assert.match(runner, /errorCount\s*!==\s*0/);
});

test("the Math CodeMirror summary gate rejects omission and false green output", () => {
  assert.deepEqual(
    validateTestSummary(
      "Ran 9 tests containing 42 assertions.\n0 failures, 0 errors.",
      0,
    ),
    { testCount: 9, assertionCount: 42, failureCount: 0, errorCount: 0 },
  );
  for (const [output, status] of [
    ["", 0],
    ["Ran 0 tests containing 0 assertions.\n0 failures, 0 errors.", 0],
    ["Ran 8 tests containing 42 assertions.\n0 failures, 0 errors.", 0],
    ["Ran 9 tests containing 41 assertions.\n0 failures, 0 errors.", 0],
    ["Ran 9 tests containing 42 assertions.\n1 failures, 0 errors.", 0],
    ["Ran 9 tests containing 42 assertions.\n0 failures, 1 errors.", 0],
    ["Ran 9 tests containing 42 assertions.\n0 failures, 0 errors.", 1],
  ]) {
    assert.throws(() => validateTestSummary(output, status));
  }
});
