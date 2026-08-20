import assert from "node:assert/strict";
import test from "node:test";

import {
  blockPayload,
  countTree,
  pageName,
  parseArgs,
} from "./phase7-large-graph-seed.mjs";

test("large graph seed identity and payload are deterministic", () => {
  const sha = "a".repeat(40);
  assert.equal(pageName(sha, 7), "selfhost6-phase7c-100k-aaaaaaaa-007");
  assert.deepEqual(blockPayload(42), { content: "p7c-000042" });
  assert.equal(countTree([{ children: [{ children: [] }, {}] }, {}]), 4);
});

test("large graph seed accepts only the frozen session and private output", () => {
  const parsed = parseArgs([
    "--session-file", "/private/tmp/session.json",
    "--output", "/private/tmp/result.json",
  ]);
  assert.equal(parsed.sessionFile, "/private/tmp/session.json");
  assert.equal(parsed.output, "/private/tmp/result.json");
  assert.throws(() => parseArgs(["--pages", "99"]));
  assert.throws(() => parseArgs(["--output", "/tmp/result.json"]));
});

