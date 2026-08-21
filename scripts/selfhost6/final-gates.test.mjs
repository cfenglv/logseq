import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { rtcPrepushSteps } from "../run-rtc-prepush.mjs";
import { desktopReleasePreflightSteps } from "../run-desktop-release-preflight.mjs";
import { rehearsalScripts } from "../run-local-release-rehearsal.mjs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const packageJson = JSON.parse(fs.readFileSync(path.join(root, "package.json"), "utf8"));
const flattened = (steps) => steps.flatMap(({ command, args }) => [command, ...args]).join(" ");

test("the three named final gates are executable package scripts", () => {
  assert.equal(packageJson.scripts["rtc:prepush"], "node ./scripts/run-rtc-prepush.mjs");
  assert.equal(packageJson.scripts["desktop:release-preflight"], "node ./scripts/run-desktop-release-preflight.mjs");
  assert.equal(packageJson.scripts["release:rehearsal"], "node ./scripts/run-local-release-rehearsal.mjs");
  assert.deepEqual(rehearsalScripts, ["rtc:prepush", "desktop:release-preflight"]);
});

test("RTC gate binds full client/server tests and the 128 MiB resource gate", () => {
  const plan = flattened(rtcPrepushSteps);
  assert.doesNotMatch(plan, /verify-phase|docs\/selfhost6-phase/);
  assert.match(plan, /bb dev:lint-and-test/);
  assert.match(plan, /deps\/db-sync test/);
  assert.match(plan, /test:large-op-128m/);
});

test("desktop gate builds official owners and only dry-runs the public Worker example", () => {
  const plan = flattened(desktopReleasePreflightSteps);
  assert.match(plan, /scripts\/selfhost6\/candidate-workflow\.test\.mjs/);
  assert.match(plan, /cljs:release-electron/);
  assert.match(plan, /db-worker-node:bundle/);
  assert.match(plan, /cli:release/);
  assert.equal(
    desktopReleasePreflightSteps.find(({ label }) => label === "build and stage official CLI").env.OPAMSWITCH,
    path.resolve(process.env.SELFHOST6_OPAM_SWITCH ?? path.join(root, "cli")),
  );
  assert.match(plan, /deps\/db-sync release/);
  assert.match(plan, /wrangler versions upload --dry-run --config worker\/wrangler\.selfhost6\.example\.jsonc/);
  assert.doesNotMatch(plan, /wrangler (deploy|versions deploy)|--publish|gh release/);
});

test("final gate plans contain no withdrawn or legacy update inputs", () => {
  const plan = `${flattened(rtcPrepushSteps)} ${flattened(desktopReleasePreflightSteps)}`;
  assert.doesNotMatch(plan, /selfhost\.5|\.5.*\.6|latest-x64|latest-arm64|selfhost-macos-v2/i);
});

test("release rehearsal cannot lower the Phase 7 proxy stage", () => {
  const plan = `${flattened(rtcPrepushSteps)} ${flattened(desktopReleasePreflightSteps)} ${rehearsalScripts.join(" ")}`;
  assert.doesNotMatch(plan, /--proxy-stage|SELFHOST6_PHASE7_PROXY_STAGE/);
});
