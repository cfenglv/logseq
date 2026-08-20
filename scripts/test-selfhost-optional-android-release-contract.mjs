#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const read = (relativePath) =>
  fs.readFileSync(path.join(repositoryRoot, relativePath), "utf8");

const workflow = read(".github/workflows/build-desktop-release.yml");
const androidWorkflow = read(".github/workflows/build-android.yml");

const workflowJobs = (source) =>
  new Map(
    [...source.matchAll(
      /^  ([a-zA-Z0-9_-]+):\n([\s\S]*?)(?=^  [a-zA-Z0-9_-]+:\n|(?![\s\S]))/gm,
    )].map((match) => [match[1], match[0]]),
  );

const jobs = workflowJobs(workflow);
const job = (name) => {
  const source = jobs.get(name);
  assert.ok(source, `missing workflow job ${name}`);
  return source;
};

const jobNeeds = (source) => {
  const inline = source.match(/^    needs:\s*\[([^\]]*)\]/m)?.[1];
  if (inline !== undefined) {
    return inline
      .split(",")
      .map((value) => value.trim())
      .filter(Boolean);
  }
  const block = source.match(/^    needs:\s*\n((?:      - [^\n]+\n?)*)/m)?.[1];
  return block
    ? [...block.matchAll(/^      -\s+([^\s#]+)/gm)].map((match) => match[1])
    : [];
};

const dependencyPath = (start, target, visited = new Set()) => {
  if (start === target) return [start];
  if (visited.has(start)) return undefined;
  visited.add(start);
  for (const dependency of jobNeeds(job(start))) {
    const suffix = dependencyPath(dependency, target, new Set(visited));
    if (suffix) return [start, ...suffix];
  }
  return undefined;
};

const namedSteps = (source) =>
  source.match(/^      - name:[\s\S]*?(?=^      - name:|(?![\s\S]))/gm) ?? [];

const actionStep = (source, actionPattern) => {
  const matches = namedSteps(source).filter((stepSource) =>
    actionPattern.test(stepSource),
  );
  assert.equal(
    matches.length,
    1,
    `expected exactly one step matching ${actionPattern}; found ${matches.length}`,
  );
  return matches[0];
};

const releaseStep = actionStep(
  job("selfhost-release"),
  /uses:\s*softprops\/action-gh-release@v2/,
);

const filesInputLines = (stepSource) => {
  const block = stepSource.match(
    /^          files:\s*\|\s*\n((?: {12}[^\n]*\n?)*)/m,
  )?.[1];
  assert.ok(
    block,
    "protected publisher must expose a literal files block so both input scenarios can be audited",
  );
  return block
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);
};

const conditionalAndroidAsset = /^\$\{\{\s*github\.event\.inputs\.build-android\s*==\s*'true'\s*&&\s*'([^']+\.apk)'\s*\|\|\s*''\s*\}\}$/;

const resolvePublishedFiles = (lines, buildAndroid) =>
  lines.flatMap((line) => {
    const conditional = line.match(conditionalAndroidAsset);
    if (!conditional) return [line];
    return buildAndroid ? [conditional[1]] : [];
  });

const containsApk = (files) => files.some((file) => /\.apk(?:$|[*?])/i.test(file));

const permissions = (source) => {
  const block = source.match(/^    permissions:\s*\n((?:      [^\n]+\n?)*)/m)?.[1];
  assert.ok(block, "job must declare explicit permissions");
  return new Map(
    [...block.matchAll(/^      ([a-z-]+):\s*([^\s#]+)/gm)].map((match) => [
      match[1],
      match[2],
    ]),
  );
};

const artifactNames = (source, direction) => {
  const action = direction === "upload" ? "upload-artifact" : "download-artifact";
  return namedSteps(source)
    .filter((stepSource) =>
      new RegExp(`uses:\\s*actions\\/${action}@v4`).test(stepSource),
    )
    .map((stepSource) => stepSource.match(/^          name:\s*([^\s#]+)/m)?.[1])
    .filter(Boolean);
};

test("fixture model distinguishes optional from unconditional APK release inputs", () => {
  const guarded = [
    "release-assets/*.zip",
    "${{ github.event.inputs.build-android == 'true' && 'release-assets/*.apk' || '' }}",
  ];
  assert.equal(containsApk(resolvePublishedFiles(guarded, false)), false);
  assert.equal(containsApk(resolvePublishedFiles(guarded, true)), true);
  assert.equal(
    containsApk(resolvePublishedFiles(["release-assets/*.apk"], false)),
    true,
    "an unconditional APK glob models softprops fail_on_unmatched_files failure",
  );
});

test("workflow_dispatch declares Android as a required false-by-default boolean", () => {
  const declaration = workflow.match(
    /^      build-android:\n([\s\S]*?)(?=^      [a-zA-Z0-9_-]+:\n|^  # schedule:)/m,
  )?.[0];
  assert.ok(declaration, "missing workflow_dispatch build-android input");
  assert.match(declaration, /^        type:\s*boolean$/m);
  assert.match(declaration, /^        required:\s*true$/m);
  assert.match(declaration, /^        default:\s*false$/m);
});

test("desktop caller passes the frozen full source SHA to reusable Android", () => {
  const android = job("build-android");
  assert.deepEqual(
    jobNeeds(android),
    ["resolve-release-source"],
    "optional Android should depend only on the existing source resolver",
  );
  assert.match(
    android,
    /^      source-sha:\s*["']?\$\{\{\s*needs\.resolve-release-source\.outputs\.source-sha\s*\}\}["']?$/m,
    "reusable Android caller is not pinned to resolve-release-source's full SHA",
  );
});

test("reusable Android checkout uses the caller SHA with dispatch git-ref fallback", () => {
  const workflowCall = androidWorkflow.match(
    /^  workflow_call:\n([\s\S]*?)(?=^env:)/m,
  )?.[0];
  assert.ok(workflowCall, "Android workflow lost its workflow_call trigger");
  const workflowCallSourceSha = workflowCall.match(
    /^      source-sha:\n((?:        [^\n]+\n?)*)/m,
  )?.[0];
  assert.ok(
    workflowCallSourceSha,
    "Android workflow_call must declare a source-sha input",
  );
  assert.match(workflowCallSourceSha, /^        type:\s*string$/m);
  assert.match(workflowCallSourceSha, /^        required:\s*true$/m);

  const checkout = actionStep(
    workflowJobs(androidWorkflow).get("build-apk") ?? "",
    /uses:\s*actions\/checkout@v4/,
  );
  const checkoutRef = checkout.match(/^          ref:\s*(.+)$/m)?.[1] ?? "";
  assert.match(
    checkoutRef,
    /inputs\.source-sha[\s\S]*\|\|[\s\S]*github\.event\.inputs\.git-ref/,
    "workflow_call must prefer source-sha while direct dispatch falls back to git-ref",
  );

  const dispatchGitRef = androidWorkflow.match(
    /^      git-ref:\n([\s\S]*?)(?=^      [a-zA-Z0-9_-]+:\n)/m,
  )?.[0];
  assert.ok(dispatchGitRef, "standalone Android dispatch lost its git-ref input");
  assert.match(dispatchGitRef, /^        required:\s*true$/m);
  assert.match(dispatchGitRef, /^        default:\s*["']master["']$/m);
});

test("disabled Android adds no dependency to compilation or desktop builds", () => {
  const android = job("build-android");
  assert.match(
    android,
    /^    if:\s*\$\{\{\s*github\.event_name == 'schedule' \|\| github\.event\.inputs\.build-android == 'true'\s*\}\}$/m,
    "optional Android job must stay skipped when build-android=false",
  );
  for (const jobName of [
    "compile-cljs",
    "build-linux-x64",
    "build-linux-arm64",
    "build-windows-x64",
    "build-windows-arm64",
    "build-macos-x64",
    "build-macos-arm64",
  ]) {
    assert.equal(
      jobNeeds(job(jobName)).includes("build-android"),
      false,
      `${jobName} unnecessarily waits for optional Android`,
    );
  }
});

test("build-android=false gives the protected release action no APK input", () => {
  const files = resolvePublishedFiles(filesInputLines(releaseStep), false);
  assert.equal(
    containsApk(files),
    false,
    `false scenario still resolves an APK release input: ${files
      .filter((file) => file.includes(".apk"))
      .join(", ")}`,
  );
});

test("build-android=true makes the APK a strict protected release input", () => {
  const files = resolvePublishedFiles(filesInputLines(releaseStep), true);
  assert.equal(
    files.filter((file) => /\.apk(?:$|[*?])/i.test(file)).length,
    1,
    "true scenario must resolve exactly one APK release pattern",
  );
  assert.match(releaseStep, /^          fail_on_unmatched_files:\s*true$/m);
});

test("both scenarios retain every core desktop release input", () => {
  const required = [
    "VERSION",
    "SOURCE_REVISION",
    "SHA256SUMS.txt",
    ".zip",
    ".dmg",
    ".exe",
    ".yml",
    ".blockmap",
    ".AppImage",
  ];
  for (const buildAndroid of [false, true]) {
    const files = resolvePublishedFiles(filesInputLines(releaseStep), buildAndroid);
    for (const asset of required) {
      assert.ok(
        files.some((file) => file.includes(asset)),
        `${buildAndroid ? "true" : "false"} scenario omits core asset ${asset}`,
      );
    }
  }
  assert.match(releaseStep, /^          fail_on_unmatched_files:\s*true$/m);
});

test("enabled Android build produces a named APK artifact without a silent path", () => {
  const android = job("build-android");
  assert.match(android, /uses:\s*\.\/\.github\/workflows\/build-android\.yml/);
  assert.match(
    android,
    /^    if:\s*\$\{\{[^\n]*github\.event\.inputs\.build-android == 'true'[^\n]*\}\}$/m,
  );
  assert.match(androidWorkflow, /mv\s+android\/app-signed\.apk\s+\.\/builds\/Logseq-android-\$\{\{[^\n]+\}\}\.apk/);
  const upload = actionStep(
    workflowJobs(androidWorkflow).get("build-apk") ?? "",
    /uses:\s*actions\/upload-artifact@v4/,
  );
  assert.match(upload, /^          name:\s*logseq-android-builds$/m);
  assert.match(upload, /^          path:\s*builds$/m);
});

test("protected finalized asset chain waits for the optional Android job", () => {
  const pathToAndroid = dependencyPath("selfhost-release-signing", "build-android");
  assert.ok(
    pathToAndroid,
    "protected signer has no dependency path to build-android, so an enabled APK can race the finalized artifact download",
  );

  const firstConsumer = pathToAndroid.at(-2);
  const androidJobIsConditional = /^    if:/m.test(job("build-android"));
  if (androidJobIsConditional) {
    assert.match(
      job(firstConsumer),
      /^    if:\s*\$\{\{[^\n]*\balways\(\)[^\n]*\}\}$/m,
      `${firstConsumer} must explicitly survive a skipped optional Android job`,
    );
  }

  const signer = job("selfhost-release-signing");
  assert.match(signer, /^          pattern:\s*logseq-\*-builds$/m);
  assert.match(signer, /^          merge-multiple:\s*true$/m);
  assert.match(signer, /^          name:\s*selfhost-finalized-release-assets$/m);
});

test("signer, secretless verifier, and protected publisher boundaries stay intact", () => {
  const signer = job("selfhost-release-signing");
  const verifier = job("selfhost-release-verifier");
  const publisher = job("selfhost-release");
  const privateKey = "LOGSEQ_PROJECT_UPDATE_SIGNING_KEY_PKCS8_BASE64";

  assert.match(signer, /^    environment:\s*selfhost-release-signing$/m);
  assert.deepEqual(permissions(signer), new Map([
    ["actions", "read"],
    ["contents", "read"],
  ]));
  assert.match(signer, new RegExp(`secrets\\.${privateKey}`));
  assert.match(signer, /finalize-github-macos-project-update\.mjs/);
  assert.doesNotMatch(signer, /softprops\/action-gh-release|contents:\s*write/);

  assert.ok(jobNeeds(verifier).includes("selfhost-release-signing"));
  assert.deepEqual(permissions(verifier), new Map([
    ["actions", "read"],
    ["contents", "read"],
  ]));
  assert.match(verifier, /^    runs-on:\s*ubuntu-/m);
  assert.match(verifier, /verify-finalized-selfhost-release\.mjs/);
  assert.doesNotMatch(verifier, /environment:|\bsecrets\.|contents:\s*write/);

  assert.ok(jobNeeds(publisher).includes("selfhost-release-verifier"));
  assert.match(publisher, /^    environment:\s*selfhost-production$/m);
  assert.deepEqual(permissions(publisher), new Map([
    ["actions", "read"],
    ["contents", "write"],
  ]));
  assert.doesNotMatch(
    publisher,
    new RegExp(`${privateKey}|finalize-github-macos-project-update`),
  );

  const finalizedArtifact = "selfhost-finalized-release-assets";
  assert.ok(artifactNames(signer, "upload").includes(finalizedArtifact));
  assert.ok(artifactNames(verifier, "download").includes(finalizedArtifact));
  assert.ok(artifactNames(publisher, "download").includes(finalizedArtifact));
});
