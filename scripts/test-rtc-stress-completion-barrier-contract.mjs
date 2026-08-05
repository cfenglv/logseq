#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const stressPath = path.join(
  repoRoot,
  "clj-e2e",
  "test",
  "logseq",
  "e2e",
  "rtc_extra_part2_test.clj",
);
const runnerPath = path.join(repoRoot, "scripts", "run-rtc-e2e.mjs");
const prepushPath = path.join(repoRoot, "scripts", "run-rtc-prepush.mjs");
const rtcHelpersPath = path.join(
  repoRoot,
  "clj-e2e",
  "src",
  "logseq",
  "e2e",
  "rtc.clj",
);
const stressSource = fs.readFileSync(stressPath, "utf8");
const runnerSource = fs.readFileSync(runnerPath, "utf8");
const prepushSource = fs.readFileSync(prepushPath, "utf8");
const rtcHelpersSource = fs.readFileSync(rtcHelpersPath, "utf8");

const formAt = (source, start) => {
  let depth = 0;
  let inComment = false;
  let inString = false;
  let escaped = false;
  for (let index = start; index < source.length; index += 1) {
    const char = source[index];
    if (inComment) {
      if (char === "\n") inComment = false;
      continue;
    }
    if (inString) {
      if (escaped) escaped = false;
      else if (char === "\\") escaped = true;
      else if (char === '"') inString = false;
      continue;
    }
    if (char === ";") {
      inComment = true;
      continue;
    }
    if (char === '"') {
      inString = true;
      continue;
    }
    if (char === "(") depth += 1;
    if (char !== ")") continue;
    depth -= 1;
    if (depth === 0) return source.slice(start, index + 1);
  }
  throw new Error(`unterminated Clojure form at offset ${start}`);
};

const definitions = (source) => {
  const result = new Map();
  const pattern = /\((?:defn-?|deftest)\s+([^\s()[\]{}]+)/g;
  for (const match of source.matchAll(pattern)) {
    result.set(match[1], formAt(source, match.index));
  }
  return result;
};

const equalityForms = (source) =>
  [...source.matchAll(/\(=\s/g)].map((match) => formAt(source, match.index));

const calledSymbols = (source) =>
  new Set(
    [...source.matchAll(/\(([A-Za-z0-9*+!?<>=._/-]+)/g)].map(
      (match) => match[1],
    ),
  );

const calledSymbolsInOrder = (source) =>
  [...source.matchAll(/\(([A-Za-z0-9*+!?<>=._/-]+)/g)].map(
    (match) => ({ index: match.index, name: match[1] }),
  );

const definitionClosure = (name, defs, seen = new Set()) => {
  if (seen.has(name) || !defs.has(name)) return "";
  seen.add(name);
  const source = defs.get(name);
  return [
    source,
    ...[...calledSymbols(source)].map((called) =>
      definitionClosure(called, defs, seen),
    ),
  ].join("\n");
};

const barrierBehaviorViolations = (source) => {
  const violations = [];
  const equalities = equalityForms(source);
  if (!source.includes("@*page1") || !source.includes("@*page2")) {
    violations.push("completion barrier must sample both RTC clients");
  }
  if (
    !source.includes(":blocks") ||
    !source.includes(":rtc-tx")
  ) {
    violations.push("completion barrier must sample both page contents and tx state");
  }
  if (!source.includes("(loop") || !source.includes("(recur")) {
    violations.push("completion barrier must poll state instead of sleeping once");
  }
  if (
    !/(?:attempt|deadline|timeout)/i.test(source) ||
    !source.includes("(throw") ||
    !source.includes("ex-info")
  ) {
    violations.push("completion barrier timeout must be bounded and fail closed");
  }
  if (
    !/(?:wait-timeout|Thread\/sleep|setTimeout|w\/wait-for|\((?:[A-Za-z0-9*+!?<>=._/-]*(?:wait|sleep|pause|yield|pace|tick|cadence)[A-Za-z0-9*+!?<>=._/-]*)\b)/i.test(
      source,
    )
  ) {
    violations.push(
      "completion barrier polling must use a bounded non-busy cadence",
    );
  }
  const hasCausalTrigger =
    /(?:rtc\/)?with-wait-tx-updated/.test(source) ||
    (/rtc\/get-rtc-tx/.test(source) &&
      /rtc\/wait-(?:current-)?tx(?:-update-to|-synced)?/.test(source));
  if (!hasCausalTrigger) {
    violations.push("completion barrier must retain a real RTC causal trigger");
  }
  if (
    !source.includes(":local-tx") ||
    !source.includes(":remote-tx") ||
    equalities.filter(
      (form) => form.includes(":local-tx") && form.includes(":remote-tx"),
    ).length < 2
  ) {
    violations.push("completion barrier must require local/remote tx convergence");
  }
  if (
    !/\(=\s+\(:blocks\s+[^)]+\)\s+\(:blocks\s+[^)]+\)\)/.test(
      source,
    )
  ) {
    violations.push("completion barrier must require equal final page contents");
  }
  const stabilityTerms =
    /(?:previous|prior|last|stable|unchanged|quiescent|consecutive)/i;
  const hasRepeatedStateEquality = equalities.some(
    (form) =>
      stabilityTerms.test(form) &&
      !form.includes(":local-tx") &&
      !form.includes(":remote-tx") &&
      !form.includes(":blocks"),
  );
  const hasStableThreshold =
    /\((?:>=|=|>)\s+[^\s)]*(?:stable|unchanged|quiescent|consecutive)[^\s)]*\s+(?:[2-9]\d*|[^\s)]*(?:stable|unchanged|quiescent|consecutive)[^\s)]*)\)/i.test(
      source,
    ) ||
    (/\(zero\?\s+[^\s)]*(?:stable|unchanged|quiescent|consecutive)[^\s)]*\)/i.test(
      source,
    ) &&
      /[^\s\[]*(?:stable|unchanged|quiescent|consecutive)[^\s\]]*\s+[2-9]\d*/i.test(
        source,
      ));
  if (!hasRepeatedStateEquality || !hasStableThreshold) {
    violations.push(
      "completion barrier must require at least two identical consecutive observations",
    );
  }
  return violations;
};

const completionContractViolations = (
  source,
  runner = runnerSource,
  prepush = prepushSource,
  rtcHelpers = rtcHelpersSource,
) => {
  const violations = [];
  const defs = definitions(source);
  for (const [name, form] of definitions(rtcHelpers)) {
    if (!defs.has(name)) defs.set(name, form);
    defs.set(`rtc/${name}`, form);
  }
  const stress = defs.get("online-two-clients-undo-redo-stress-test") ?? "";
  const assertionIndex = stress.indexOf("(assert-two-pages-synced!");
  const lastParallelIndex = stress.lastIndexOf(
    "(run-two-clients-in-parallel!",
    assertionIndex,
  );
  const synchronizationStart =
    lastParallelIndex === -1
      ? 0
      : lastParallelIndex + formAt(stress, lastParallelIndex).length;
  const synchronizationPhase =
    assertionIndex === -1
      ? ""
      : stress.slice(synchronizationStart, assertionIndex);
  const barrierCandidates = [
    ...new Set(
      calledSymbolsInOrder(synchronizationPhase)
        .map(({ name }) => name)
        .filter((name) => defs.has(name)),
    ),
  ];
  const combinedBarrierSource = [
    synchronizationPhase,
    ...barrierCandidates.map((name) => definitionClosure(name, defs)),
  ].join("\n");
  const barrierAnalyses = [
    {
      name:
        barrierCandidates.length > 0
          ? barrierCandidates.join(" -> ")
          : "post-operation synchronization phase",
      source: combinedBarrierSource,
    },
    ...barrierCandidates.map((name) => ({
      name,
      source: `${synchronizationPhase}\n${definitionClosure(name, defs)}`,
    })),
  ];
  const safeBarrier = barrierAnalyses.find(
    ({ source: barrierSource }) =>
      barrierBehaviorViolations(barrierSource).length === 0,
  );
  if (!safeBarrier) {
    const closest = barrierAnalyses
      .map(({ name, source: barrierSource }) => ({
        name,
        violations: barrierBehaviorViolations(barrierSource),
      }))
      .sort((left, right) => left.violations.length - right.violations.length)[0];
    violations.push(
      `stress test needs a bounded two-client stable completion barrier before assertions${
        closest
          ? `; closest ${closest.name}: ${closest.violations.join("; ")}`
          : ""
      }`,
    );
  }

  const contentAssertion = defs.get("assert-two-pages-synced!") ?? "";
  const assertionTxEqualities = equalityForms(contentAssertion).filter(
    (form) => form.includes(":local-tx") && form.includes(":remote-tx"),
  );
  if (
    !stress.includes("(assert-two-pages-synced!") ||
    !/\(is\s+\(=\s+\(:blocks\s+[^)]+\)\s+\(:blocks\s+[^)]+\)\)/.test(
      contentAssertion,
    ) ||
    assertionTxEqualities.length < 2
  ) {
    violations.push("stress test must retain original content and both-client tx assertions");
  }
  if (
    !stress.includes("(assert-no-severe-sync-errors!") ||
    !source.includes("db-sync/checksum-mismatch") ||
    !source.includes("db-sync/tx-rejected") ||
    !source.includes("db-sync/apply-remote-txs-failed")
  ) {
    violations.push("stress test must retain severe RTC console-log assertions");
  }
  if (
    !runner.includes('"rtc-extra-part2-test"') ||
    !(runner.match(/await\s+runChild\(/g) ?? []).length ||
    !prepush.includes(
      '["scripts/run-rtc-e2e.mjs", "rtc-extra-part2-test"]',
    )
  ) {
    violations.push("contract must continue through the real RTC E2E path");
  }
  if (/(?:retry|rerun)/i.test(stress)) {
    violations.push("stress test must not retry its whole scenario");
  }
  const runnerChildCalls = runner.match(/await\s+runChild\(/g) ?? [];
  const part2Gate = prepush.slice(
    prepush.indexOf("RTC browser E2E part 2"),
    prepush.indexOf("const finalSha"),
  );
  if (runnerChildCalls.length !== 1 || /(?:retry|rerun)/i.test(part2Gate)) {
    violations.push("runner and prepush gate must not retry a failed RTC shard");
  }
  if (
    /\(catch\s+(?:Throwable|Exception)\b/.test(synchronizationPhase) ||
    /\(try[\s\S]{0,1200}\(assert-two-pages-synced![\s\S]{0,500}\(catch\s+(?:Throwable|Exception)/.test(
      stress,
    )
  ) {
    violations.push("stress test must not swallow barrier or assertion failures");
  }
  return violations;
};

const safeFixture = `
(def severe-sync-log-patterns
  ["db-sync/checksum-mismatch"
   "db-sync/tx-rejected"
   "db-sync/apply-remote-txs-failed"])
(defn- page-sync-state [page]
  {:rtc-tx (rtc/get-rtc-tx) :blocks (get-blocks page)})
(defn- sync-by-trigger! []
  (rtc/with-wait-tx-updated
    (new-block-safe! "completion-fence")))
(defn- wait-for-two-client-fixpoint! []
  (loop [attempts 20 previous nil stable-samples 0]
    (when (zero? attempts)
      (throw (ex-info "completion barrier timeout" {:attempts 20})))
    (let [page1-state (w/with-page @*page1 (page-sync-state @*page1))
          page2-state (w/with-page @*page2 (page-sync-state @*page2))
          blocks-equal? (= (:blocks page1-state) (:blocks page2-state))
          tx-converged? (and (= (:local-tx (:rtc-tx page1-state)) (:remote-tx (:rtc-tx page1-state)))
                             (= (:local-tx (:rtc-tx page2-state)) (:remote-tx (:rtc-tx page2-state))))
          current [page1-state page2-state]
          unchanged? (= previous current)
          stable-samples' (if (and blocks-equal? tx-converged? unchanged?)
                            (inc stable-samples)
                            0)]
      (if (>= stable-samples' 2)
        current
        (do (Thread/sleep 100)
            (recur (dec attempts) current stable-samples'))))))
(defn- assert-two-pages-synced! []
  (let [s1 (page-sync-state @*page1) s2 (page-sync-state @*page2)
        tx1 (:rtc-tx s1) tx2 (:rtc-tx s2)]
    (is (= (:blocks s1) (:blocks s2)))
    (is (= (:local-tx tx1) (:remote-tx tx1)))
    (is (= (:local-tx tx2) (:remote-tx tx2)))))
(defn- assert-no-severe-sync-errors! [] severe-sync-log-patterns)
(deftest online-two-clients-undo-redo-stress-test
  (run-two-clients-in-parallel!)
  (sync-by-trigger!)
  (wait-for-two-client-fixpoint!)
  (assert-two-pages-synced!)
  (assert-no-severe-sync-errors!))
`;

const safeRunner = `
const supportedTasks = new Set(["rtc-extra-part2-test"]);
await runChild("bb", [testTask]);
`;
const safePrepush = `
run("RTC browser E2E part 2", process.execPath,
  ["scripts/run-rtc-e2e.mjs", "rtc-extra-part2-test"]);
const finalSha = capture("git", ["rev-parse", "HEAD"]);
`;
const localBarrierForm = definitions(safeFixture).get(
  "wait-for-two-client-fixpoint!",
);
const safeExternalHelperFixture = safeFixture
  .replace(localBarrierForm, "")
  .replaceAll("sync-by-trigger!", "establish-causal-watermark!")
  .replace(
    "(wait-for-two-client-fixpoint!)",
    "(rtc/wait-for-two-client-fixpoint! page-sync-state #(Thread/sleep 100) @*page1 @*page2)",
  );
const safeExternalRtcHelpers = `
(defn wait-for-two-client-fixpoint! [sample cadence page1 page2]
  (loop [attempts 20 previous nil stable-samples 0]
    (when (zero? attempts)
      (throw (ex-info "completion barrier timeout" {:attempts 20})))
    (let [page1-state (sample page1)
          page2-state (sample page2)
          blocks-equal? (= (:blocks page1-state) (:blocks page2-state))
          tx-converged? (and (= (:local-tx (:rtc-tx page1-state)) (:remote-tx (:rtc-tx page1-state)))
                             (= (:local-tx (:rtc-tx page2-state)) (:remote-tx (:rtc-tx page2-state))))
          current [page1-state page2-state]
          unchanged? (= previous current)
          stable-samples' (if (and blocks-equal? tx-converged? unchanged?)
                            (inc stable-samples)
                            0)]
      (if (>= stable-samples' 2)
        current
        (do (cadence)
            (recur (dec attempts) current stable-samples'))))))
`;
const renamedFixture = safeFixture
  .replaceAll("sync-by-trigger!", "establish-causal-watermark!")
  .replaceAll(
    "wait-for-two-client-fixpoint!",
    "observe-two-client-fixpoint!",
  );
const safeRenamedIndirectFixture = renamedFixture
  .replace(
    "(deftest online-two-clients-undo-redo-stress-test",
    `(defn- settle-after-concurrent-ops! []
  (establish-causal-watermark!)
  (observe-two-client-fixpoint!))
(deftest online-two-clients-undo-redo-stress-test`,
  )
  .replace(
    "  (establish-causal-watermark!)\n  (observe-two-client-fixpoint!)",
    "  (settle-after-concurrent-ops!)",
  );

const converged = (tx, blocks) => ({
  blocks,
  rtcTx: { localTx: tx, remoteTx: tx },
});

const firstStableCompletion = (samples, requiredStableSamples = 2) => {
  let previous;
  let stableSamples = 0;
  for (const [index, current] of samples.entries()) {
    const [page1, page2] = current;
    const contentsEqual = JSON.stringify(page1.blocks) === JSON.stringify(page2.blocks);
    const txConverged = [page1, page2].every(
      ({ rtcTx }) => rtcTx.localTx === rtcTx.remoteTx,
    );
    const unchanged = JSON.stringify(previous) === JSON.stringify(current);
    stableSamples = contentsEqual && txConverged && unchanged
      ? stableSamples + 1
      : 0;
    if (stableSamples >= requiredStableSamples) return index;
    previous = current;
  }
  throw new Error("completion barrier timeout");
};

test("late RTC transactions cannot satisfy the deterministic completion model", () => {
  const trace = [
    [converged(301, ["p1-r0-op48-nest"]), converged(301, [""])],
    [converged(303, ["p1-r0-op48-nest"]), converged(301, [""])],
    [converged(303, ["p1-r0-op48-nest"]), converged(303, ["p1-r0-op48-nest"])],
    [converged(305, ["p1-r0-op48-nest", "late"]), converged(303, ["p1-r0-op48-nest"])],
    [converged(305, ["p1-r0-op48-nest", "late"]), converged(305, ["p1-r0-op48-nest", "late"])],
    [converged(305, ["p1-r0-op48-nest", "late"]), converged(305, ["p1-r0-op48-nest", "late"])],
    [converged(305, ["p1-r0-op48-nest", "late"]), converged(305, ["p1-r0-op48-nest", "late"])],
  ];
  assert.equal(firstStableCompletion(trace), 6);
  assert.notDeepEqual(trace[0][0].blocks, trace[0][1].blocks);
  assert.equal(trace[0][0].rtcTx.localTx, trace[0][0].rtcTx.remoteTx);
});

test("the real stress test has a bounded two-client stable completion barrier", () => {
  assert.deepEqual(
    completionContractViolations(stressSource),
    [],
  );
});

test("contract accepts a safe synthetic barrier", () => {
  assert.deepEqual(
    completionContractViolations(safeFixture, safeRunner, safePrepush),
    [],
  );
});

test("contract accepts an equivalent shared RTC helper barrier", () => {
  assert.deepEqual(
    completionContractViolations(
      safeExternalHelperFixture,
      safeRunner,
      safePrepush,
      safeExternalRtcHelpers,
    ),
    [],
  );
});

test("contract discovers renamed causal triggers through helper indirection", () => {
  assert.deepEqual(
    completionContractViolations(
      safeRenamedIndirectFixture,
      safeRunner,
      safePrepush,
    ),
    [],
  );
});

test("contract rejects unsafe completion and assertion mutations", () => {
  const mutations = [
    [
      "sleep only",
      safeFixture.replace(
        "(wait-for-two-client-fixpoint!)",
        "(util/wait-timeout 5000)",
      ),
      safeRunner,
      safePrepush,
    ],
    ["single client", safeFixture.replaceAll("@*page2", "@*page1"), safeRunner, safePrepush],
    [
      "single tx observation",
      safeFixture.replace(
        "(= previous current)",
        "(= (:rtc-tx page1-state) (:rtc-tx page2-state))",
      ),
      safeRunner,
      safePrepush,
    ],
    [
      "different final content accepted",
      safeFixture.replace(
        "(= (:blocks page1-state) (:blocks page2-state))",
        "(not= (:blocks page1-state) (:blocks page2-state))",
      ),
      safeRunner,
      safePrepush,
    ],
    [
      "fixed whole-test retry",
      safeFixture.replace(
        "(run-two-clients-in-parallel!)",
        "(dotimes [retry 3] (run-two-clients-in-parallel!))",
      ),
      safeRunner,
      safePrepush,
    ],
    [
      "runner retry",
      safeFixture,
      `${safeRunner}\nfor (let retry = 0; retry < 3; retry += 1) await runChild("bb", [testTask]);`,
      safePrepush,
    ],
    [
      "canonical real RTC runner path replaced",
      safeFixture,
      safeRunner.replace("rtc-extra-part2-test", "synthetic-rtc-test"),
      safePrepush.replace("rtc-extra-part2-test", "synthetic-rtc-test"),
    ],
    [
      "timeout swallowed",
      safeFixture.replace(
        '(throw (ex-info "completion barrier timeout" {:attempts 20}))',
        "nil",
      ),
      safeRunner,
      safePrepush,
    ],
    [
      "barrier exception swallowed",
      safeFixture.replace(
        "(wait-for-two-client-fixpoint!)",
        "(try (wait-for-two-client-fixpoint!) (catch Throwable _ nil))",
      ),
      safeRunner,
      safePrepush,
    ],
    [
      "original content assertion removed",
      safeFixture.replace(
        "(is (= (:blocks s1) (:blocks s2)))",
        "nil",
      ),
      safeRunner,
      safePrepush,
    ],
    [
      "original tx assertions removed",
      safeFixture
        .replace("(is (= (:local-tx tx1) (:remote-tx tx1)))", "nil")
        .replace("(is (= (:local-tx tx2) (:remote-tx tx2)))", "nil"),
      safeRunner,
      safePrepush,
    ],
    [
      "severe log assertion removed",
      safeFixture.replace("(assert-no-severe-sync-errors!)", "nil"),
      safeRunner,
      safePrepush,
    ],
    [
      "real RTC trigger removed",
      safeFixture.replace("(sync-by-trigger!)", "nil"),
      safeRunner,
      safePrepush,
    ],
    [
      "causal trigger primitive bypassed",
      safeFixture.replace(
        `(rtc/with-wait-tx-updated
    (new-block-safe! "completion-fence"))`,
        '(new-block-safe! "completion-fence")',
      ),
      safeRunner,
      safePrepush,
    ],
    [
      "renamed causal helper bypassed through indirection",
      safeRenamedIndirectFixture.replace(
        "  (establish-causal-watermark!)\n",
        "",
      ),
      safeRunner,
      safePrepush,
    ],
  ];
  for (const [label, source, runner, prepush] of mutations) {
    assert.notDeepEqual(
      completionContractViolations(source, runner, prepush),
      [],
      `${label} unexpectedly satisfied the completion barrier contract`,
    );
  }
});
