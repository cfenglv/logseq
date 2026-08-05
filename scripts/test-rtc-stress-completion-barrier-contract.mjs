#!/usr/bin/env node

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);

const runContractNamespace = (namespace) => {
  const result = spawnSync(
    "clojure",
    [
      "-Srepro",
      "-M:test",
      "-n",
      namespace,
    ],
    {
      cwd: path.join(repoRoot, "clj-e2e"),
      encoding: "utf8",
      env: process.env,
      shell: false,
      stdio: ["ignore", "pipe", "pipe"],
      timeout: 60_000,
    },
  );
  if (result.error) throw result.error;
  assert.equal(
    result.signal,
    null,
    `Clojure RTC contract ${namespace} terminated by ${result.signal}`,
  );
  assert.equal(
    result.status,
    0,
    `Clojure RTC contract ${namespace} failed:\n${result.stdout}\n${result.stderr}`,
  );
};

test("RTC completion barrier satisfies executable quiescence contracts", () => {
  runContractNamespace("logseq.e2e.rtc-quiescence-contract-test");
});

test("RTC marker client writes require durable read-back", () => {
  runContractNamespace("logseq.e2e.rtc-marker-durability-contract-test");
});
