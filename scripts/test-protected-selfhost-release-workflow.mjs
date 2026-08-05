#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const read = (relativePath) =>
  fs.readFileSync(path.join(repoRoot, relativePath), "utf8");
const workflow = read(".github/workflows/build-desktop-release.yml");
const androidWorkflow = read(".github/workflows/build-android.yml");

const relativeModuleClosure = (relativePath, seen = new Set()) => {
  if (seen.has(relativePath)) return seen;
  seen.add(relativePath);
  const source = read(relativePath);
  for (const match of source.matchAll(
    /(?:from\s+|import\s*\(\s*)["'](\.[^"']+\.(?:c?js|mjs))["']/g,
  )) {
    relativeModuleClosure(
      path
        .normalize(path.join(path.dirname(relativePath), match[1]))
        .replaceAll(path.sep, "/"),
      seen,
    );
  }
  return seen;
};

const workflowJob = (name) => {
  const match = workflow.match(
    new RegExp(
      `^  ${name}:\\n([\\s\\S]*?)(?=^  [a-zA-Z0-9_-]+:\\n|(?![\\s\\S]))`,
      "m",
    ),
  );
  assert.ok(match, `missing workflow job ${name}`);
  return match[0];
};

const cases = [];
const addCase = (name, test) => cases.push([name, test]);

addCase("push rehearsal trigger is limited to explicit selfhost branches", () => {
  const push = workflow.match(/^  push:\n([\s\S]*?)(?=^  workflow_dispatch:)/m)?.[0];
  assert.ok(push, "missing push trigger");
  assert.match(push, /selfhost\/cloudflare-rtc/);
  assert.match(push, /release\/2\.0\.1-selfhost\.\*/);
  assert.doesNotMatch(push, /release\/\*\*/);
});

addCase("release rehearsal binds a successful push run to the exact SHA", () => {
  const rehearsal = workflowJob("release-rehearsal-gate");
  assert.match(rehearsal, /source-ref:[\s\S]*steps\.source\.outputs\.ref/);
  assert.match(rehearsal, /source-sha:[\s\S]*steps\.source\.outputs\.sha/);
  assert.match(rehearsal, /event:\s*'push'/);
  assert.match(rehearsal, /status:\s*'success'/);
  assert.match(rehearsal, /head_sha:\s*sha/);
});

addCase("Android workflow_call checks out the same frozen release SHA", () => {
  const android = workflowJob("build-android");
  assert.match(android, /needs:\s*\[\s*resolve-release-source\s*\]/);
  assert.match(
    android,
    /source-sha:\s*"\$\{\{ needs\.resolve-release-source\.outputs\.source-sha \}\}"/,
  );
  assert.match(
    androidWorkflow,
    /workflow_call:[\s\S]{0,240}source-sha:[\s\S]{0,120}type:\s*string[\s\S]{0,80}required:\s*true/,
  );
  assert.match(
    androidWorkflow,
    /ref:\s*\$\{\{ inputs\.source-sha \|\| github\.event\.inputs\.git-ref \}\}/,
  );
  assert.doesNotMatch(
    androidWorkflow,
    /^\s*ref:\s*\$\{\{ github\.event\.inputs\.git-ref \}\}\s*$/m,
  );
});

addCase("protected signer is exact-ref workflow_dispatch stable/beta only", () => {
  const signer = workflowJob("selfhost-release-signing");
  for (const needle of [
    "github.repository == 'cfenglv/logseq'",
    "github.event_name == 'workflow_dispatch'",
    "github.event.inputs.build-target == 'beta'",
    "github.event.inputs.build-target == 'stable'",
    "github.event.inputs.desktop-platforms == 'all'",
    "github.ref_name == needs.release-rehearsal-gate.outputs.source-ref",
    "github.sha == needs.release-rehearsal-gate.outputs.source-sha",
    "contains(needs.release-assets-preflight.outputs.version, '-selfhost.')",
    "!contains(needs.release-assets-preflight.outputs.version, '.nightly.')",
  ]) {
    assert.ok(signer.includes(needle), `signer condition is missing ${needle}`);
  }
  assert.match(
    signer,
    /needs:\s*\[\s*release-assets-preflight,\s*release-rehearsal-gate,\s*build-android\s*\]/,
  );
  assert.match(signer, /always\(\)/);
  assert.match(signer, /needs\.release-assets-preflight\.result == 'success'/);
  assert.match(signer, /needs\.release-rehearsal-gate\.result == 'success'/);
  assert.match(
    signer,
    /github\.event\.inputs\.build-android != 'true' \|\| needs\.build-android\.result == 'success'/,
  );
  assert.match(signer, /runs-on:\s*macos-/);
  assert.match(signer, /environment:\s*selfhost-release-signing/);
  assert.match(signer, /permissions:\s*\n\s+actions:\s*read\s*\n\s+contents:\s*read/);
});

addCase("signer alone consumes the Environment secret in process memory", () => {
  const signer = workflowJob("selfhost-release-signing");
  const secretName = "LOGSEQ_PROJECT_UPDATE_SIGNING_KEY_PKCS8_BASE64";
  assert.equal(
    workflow.match(new RegExp(`secrets\\.${secretName}`, "g"))?.length,
    1,
  );
  assert.ok(
    signer.includes(
      `${secretName}: $` + `{{ secrets.${secretName} }}`,
    ),
  );
  assert.match(signer, /finalize-github-macos-project-update\.mjs/);
  assert.doesNotMatch(signer, /add-generic-password|\/usr\/bin\/security|Keychain/);
  assert.doesNotMatch(
    signer,
    /uses:\s*(?:softprops\/action-gh-release|andelf\/nightly-release)@/,
  );
  for (const name of [
    "build-macos-x64",
    "build-macos-arm64",
    "selfhost-release-verifier",
    "selfhost-release",
    "release",
    "nightly-release",
  ]) {
    assert.doesNotMatch(workflowJob(name), new RegExp(secretName));
  }
});

addCase("signer merges six-platform candidates into one finalized artifact", () => {
  const signer = workflowJob("selfhost-release-signing");
  assert.match(signer, /pattern:\s*logseq-\*-builds/);
  assert.match(signer, /merge-multiple:\s*true/);
  assert.match(signer, /verify-desktop-release-assets\.mjs/);
  assert.match(
    signer,
    /--android-enabled\s+"\$\{\{ github\.event\.inputs\.build-android \}\}"/,
  );
  assert.match(signer, /verify-unsigned-macos-project-update-candidate\.mjs/);
  assert.match(signer, /for arch in arm64 x64/);
  assert.equal(
    signer.match(/uses:\s*actions\/upload-artifact@v4/g)?.length,
    1,
  );
  assert.match(signer, /name:\s*selfhost-finalized-release-assets/);
  const finalizer = read("scripts/finalize-github-macos-project-update.mjs");
  assert.match(finalizer, /writeSourceRevision/);
  assert.match(finalizer, /context\.sourceSha/);
});

addCase("secretless verifier rechecks complete assets and signatures", () => {
  const verifier = workflowJob("selfhost-release-verifier");
  assert.match(
    verifier,
    /needs:\s*\[\s*selfhost-release-signing,\s*release-rehearsal-gate\s*\]/,
  );
  assert.match(
    verifier,
    /if:\s*\$\{\{\s*always\(\) && needs\.selfhost-release-signing\.result == 'success' && needs\.release-rehearsal-gate\.result == 'success'\s*\}\}/,
  );
  assert.match(verifier, /permissions:\s*\n\s+actions:\s*read\s*\n\s+contents:\s*read/);
  assert.doesNotMatch(verifier, /environment:|secrets\./);
  assert.match(verifier, /name:\s*selfhost-finalized-release-assets/);
  assert.match(verifier, /verify-finalized-selfhost-release\.mjs/);
  assert.match(
    verifier,
    /--android-enabled\s+"\$\{\{ github\.event\.inputs\.build-android \}\}"/,
  );
  assert.match(
    verifier,
    /--source-revision[\s\S]{0,160}release-rehearsal-gate\.outputs\.source-sha/,
  );
  assert.match(
    verifier,
    /source-ref:\s*\$\{\{ needs\.release-rehearsal-gate\.outputs\.source-ref \}\}/,
  );
  assert.match(
    verifier,
    /source-sha:\s*\$\{\{ needs\.release-rehearsal-gate\.outputs\.source-sha \}\}/,
  );
  assert.match(
    verifier,
    /ref:\s*\$\{\{ needs\.release-rehearsal-gate\.outputs\.source-sha \}\}/,
  );
  assert.match(
    verifier,
    /version:\s*\$\{\{ needs\.selfhost-release-signing\.outputs\.version \}\}/,
  );
  const verifierClosure = [
    ...relativeModuleClosure("scripts/verify-finalized-selfhost-release.mjs"),
  ]
    .map(read)
    .join("\n");
  assert.match(verifierClosure, /verify-desktop-release-assets\.mjs/);
  assert.match(verifierClosure, /verifyProjectSignedMacosUpdate/);
  assert.match(verifierClosure, /verifySourceRevision/);
  assert.match(verifierClosure, /\["arm64", "x64"\]/);
});

addCase("publisher has separate protected write boundary after verifier", () => {
  const publisher = workflowJob("selfhost-release");
  assert.match(publisher, /needs:\s*\[\s*selfhost-release-verifier\s*\]/);
  assert.match(
    publisher,
    /if:\s*\$\{\{\s*always\(\) && needs\.selfhost-release-verifier\.result == 'success'/,
  );
  assert.match(publisher, /environment:\s*selfhost-production/);
  assert.match(publisher, /permissions:[\s\S]{0,100}actions:\s*read/);
  assert.match(publisher, /permissions:[\s\S]{0,100}contents:\s*write/);
  assert.match(publisher, /uses:\s*softprops\/action-gh-release@v2/);
  assert.match(publisher, /name:\s*selfhost-finalized-release-assets/);
  assert.match(publisher, /release-assets\/SOURCE_REVISION/);
  assert.match(publisher, /fail_on_unmatched_files:\s*true/);
  assert.match(
    publisher,
    /\$\{\{ github\.event\.inputs\.build-android == 'true' && 'release-assets\/\*\.apk' \|\| '' \}\}/,
  );
  assert.doesNotMatch(publisher, /^\s*release-assets\/\*\.apk\s*$/m);
  assert.doesNotMatch(
    publisher,
    /LOGSEQ_PROJECT_UPDATE_SIGNING_KEY_PKCS8_BASE64|finalize-github-macos/,
  );
});

addCase("formal selfhost release has a fail-closed terminal audit", () => {
  const audit = workflowJob("selfhost-release-terminal-audit");
  assert.match(
    audit,
    /needs:\s*\[\s*release-assets-preflight,\s*selfhost-release-signing,\s*selfhost-release-verifier,\s*selfhost-release\s*\]/,
  );
  assert.match(audit, /if:\s*\$\{\{\s*always\(\)/);
  for (const eligibility of [
    "github.repository == 'cfenglv/logseq'",
    "github.event_name == 'workflow_dispatch'",
    "github.event.inputs.build-target == 'beta'",
    "github.event.inputs.build-target == 'stable'",
    "github.event.inputs.desktop-platforms == 'all'",
    "contains(needs.release-assets-preflight.outputs.version, '-selfhost.')",
    "!contains(needs.release-assets-preflight.outputs.version, '.nightly.')",
  ]) {
    assert.ok(audit.includes(eligibility), `audit is missing ${eligibility}`);
  }
  assert.doesNotMatch(audit, /github\.ref_name/);
  assert.match(audit, /permissions:\s*\n\s+contents:\s*read/);
  assert.match(
    audit,
    /SIGNER_RESULT:\s*\$\{\{ needs\.selfhost-release-signing\.result \}\}/,
  );
  assert.match(
    audit,
    /VERIFIER_RESULT:\s*\$\{\{ needs\.selfhost-release-verifier\.result \}\}/,
  );
  assert.match(
    audit,
    /PUBLISHER_RESULT:\s*\$\{\{ needs\.selfhost-release\.result \}\}/,
  );
  for (const result of [
    "selfhost-release-signing",
    "selfhost-release-verifier",
    "selfhost-release",
  ]) {
    assert.match(
      audit,
      new RegExp(`needs\\.${result}\\.result != 'success'`),
    );
  }
  assert.match(audit, /exit 1/);
  assert.doesNotMatch(
    audit,
    /environment:|\bsecrets\.|contents:\s*write|action-gh-release|finalize-github-macos/,
  );
});

addCase("push and generic publishers cannot sign or publish selfhost", () => {
  for (const name of ["selfhost-release-signing", "selfhost-release"]) {
    assert.match(workflowJob(name), /github\.event_name == 'workflow_dispatch'/);
  }
  for (const name of ["release", "nightly-release"]) {
    assert.match(
      workflowJob(name),
      /!contains\(needs\.release-assets-preflight\.outputs\.version, '-selfhost\.'\)/,
    );
  }
});

addCase("local Keychain finalizer remains available", () => {
  const packageJson = JSON.parse(read("package.json"));
  assert.equal(
    packageJson.scripts["project-update:finalize-local-macos-candidates"],
    "node ./scripts/finalize-local-macos-project-update.mjs",
  );
  const localFinalizer = read("scripts/finalize-local-macos-project-update.mjs");
  assert.match(localFinalizer, /loadProjectUpdateSigningKey/);
  assert.match(localFinalizer, /finalizeMacosProjectUpdate/);
});

addCase("documentation requires protected Environments and no user key", () => {
  const docs = `${read("docs/selfhost-sync.md")}\n${read(
    "docs/releases/2.0.1-selfhost.5.md",
  )}`;
  assert.match(docs, /selfhost-release-signing/);
  assert.match(docs, /selfhost-production/);
  assert.match(docs, /required reviewers?/i);
  assert.match(docs, /deployment branch[\s\S]{0,80}(?:tag )?restrictions?/i);
  assert.match(docs, /Environment secret[\s\S]{0,160}(?:never public|not public)|(?:never public|not public)[\s\S]{0,160}Environment secret/i);
  assert.match(docs, /users?[\s\S]{0,100}(?:do not|never|no need)[\s\S]{0,100}(?:key|secret)/i);
  assert.match(
    docs,
    /local macOS login Keychain finalizer[\s\S]{0,160}supported compatibility[\s\S]{0,120}(?:alternative|fallback)/i,
  );
});

addCase("formal release contracts execute this workflow gate", () => {
  const packageJson = JSON.parse(read("package.json"));
  assert.equal(
    packageJson.scripts["project-update:test-protected-release-workflow"],
    "node ./scripts/test-protected-selfhost-release-workflow.mjs",
  );
  assert.match(
    packageJson.scripts["desktop:test-release-contracts"],
    /test-protected-selfhost-release-workflow\.mjs/,
  );
});

let passed = 0;
let failed = 0;
for (const [name, test] of cases) {
  try {
    await test();
    passed += 1;
    console.log(`[protected-selfhost-release] PASS ${name}`);
  } catch (error) {
    failed += 1;
    console.error(
      `[protected-selfhost-release] FAIL ${name}: ${
        error instanceof Error ? error.stack || error.message : error
      }`,
    );
  }
}

console.log(
  `[protected-selfhost-release] SUMMARY passed=${passed} failed=${failed} total=${cases.length}`,
);
if (failed > 0) process.exitCode = 1;
