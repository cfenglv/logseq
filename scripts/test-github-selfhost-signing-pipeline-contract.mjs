#!/usr/bin/env node

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { generateKeyPairSync } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const workflowRelativePath = ".github/workflows/build-desktop-release.yml";
const privateKeyEnvironmentName =
  "LOGSEQ_PROJECT_UPDATE_SIGNING_KEY_PKCS8_BASE64";
const signingEnvironmentName = "selfhost-release-signing";
const publishingEnvironmentName = "selfhost-production";

const read = (relativePath) =>
  fs.readFileSync(path.join(repoRoot, relativePath), "utf8");

const run = (executable, args, options = {}) => {
  const result = spawnSync(executable, args, {
    cwd: options.cwd ?? repoRoot,
    encoding: "utf8",
    env: options.env ?? process.env,
    stdio: ["ignore", "pipe", "pipe"],
  });
  if (result.error) throw result.error;
  return {
    output: `${result.stdout || ""}${result.stderr || ""}`,
    status: result.status,
  };
};

const workflowJobs = (source) => {
  const headerMatches = [...source.matchAll(/^  ([a-zA-Z0-9_-]+):\n/gm)];
  const jobs = new Map();
  for (let index = 0; index < headerMatches.length; index += 1) {
    const match = headerMatches[index];
    const end = headerMatches[index + 1]?.index ?? source.length;
    jobs.set(match[1], source.slice(match.index, end));
  }
  return jobs;
};

const environmentPattern = (name) =>
  new RegExp(
    `^    environment:\\s*(?:${name}\\s*$|\\n(?:      [^\\n]*\\n)*?      name:\\s*${name}\\s*$)`,
    "m",
  );

const singleJobWithEnvironment = (jobs, environmentName) => {
  const matches = [...jobs].filter(([, source]) =>
    environmentPattern(environmentName).test(source),
  );
  assert.equal(
    matches.length,
    1,
    `expected exactly one job using environment ${environmentName}; found ${matches
      .map(([name]) => name)
      .join(", ") || "none"}`,
  );
  return { name: matches[0][0], source: matches[0][1] };
};

const permissionsFor = (jobSource) => {
  const block = jobSource.match(
    /^    permissions:\s*\n((?:^      [a-zA-Z0-9_-]+:\s*[a-z]+\s*\n?)+)/m,
  );
  assert.ok(block, "job must declare its permissions explicitly");
  return new Map(
    [...block[1].matchAll(/^      ([a-zA-Z0-9_-]+):\s*([a-z]+)\s*$/gm)].map(
      (match) => [match[1], match[2]],
    ),
  );
};

const assertOnlyReadPermissions = (jobSource) => {
  const permissions = permissionsFor(jobSource);
  assert.deepEqual(
    [...permissions].sort(),
    [
      ["actions", "read"],
      ["contents", "read"],
    ],
    "job permissions must be exactly actions:read and contents:read",
  );
};

const assertSelfhostDispatchCondition = (jobSource, label) => {
  const condition = jobSource.match(/^    if:\s*(.+)$/m)?.[1] ?? "";
  assert.match(condition, /github\.event_name\s*==\s*['"]workflow_dispatch['"]/);
  assert.match(condition, /build-target[\s\S]*(?:==\s*['"]stable['"]|['"]stable['"]\s*==)/);
  assert.match(condition, /build-target[\s\S]*(?:==\s*['"]beta['"]|['"]beta['"]\s*==)/);
  assert.match(
    condition,
    /(?:contains\([^\n]*-selfhost\.|-selfhost\.[^\n]*(?:==|!=))/i,
    `${label} is not restricted to selfhost versions`,
  );
  assert.doesNotMatch(
    condition,
    /github\.event_name\s*==\s*['"](?:schedule|push|pull_request)['"]/i,
    `${label} positively enables a non-dispatch event`,
  );
  assert.doesNotMatch(
    condition,
    /build-target[^\n]*(?:==\s*['"]nightly['"]|['"]nightly['"]\s*==)/i,
    `${label} positively enables nightly signing or publishing`,
  );
};

const jobNeeds = (jobSource) => {
  const inline = jobSource.match(/^    needs:\s*\[([^\n]+)\]/m)?.[1];
  if (inline) {
    return inline
      .split(",")
      .map((value) => value.trim())
      .filter(Boolean);
  }
  const block = jobSource.match(/^    needs:\s*\n((?:^      -\s*[^\n]+\n?)+)/m)?.[1];
  return block
    ? [...block.matchAll(/^      -\s*([a-zA-Z0-9_-]+)\s*$/gm)].map(
        (match) => match[1],
      )
    : [];
};

const relativeModuleClosure = (entryPaths, seen = new Set()) => {
  for (const relativePath of entryPaths) {
    if (seen.has(relativePath)) continue;
    const absolutePath = path.join(repoRoot, relativePath);
    assert.ok(fs.existsSync(absolutePath), `missing script ${relativePath}`);
    seen.add(relativePath);
    const source = fs.readFileSync(absolutePath, "utf8");
    const dependencies = [
      ...source.matchAll(
        /(?:from\s+|import\s*\(\s*|import\s+)["'](\.[^"']+\.(?:c?js|mjs))["']/g,
      ),
    ].map((match) =>
      path
        .normalize(path.join(path.dirname(relativePath), match[1]))
        .replaceAll(path.sep, "/"),
    );
    for (const dependency of dependencies) {
      assert.doesNotMatch(
        dependency,
        /^(?:\.\.(?:\/|$)|\/)/,
        `${relativePath} imports outside the repository: ${dependency}`,
      );
    }
    relativeModuleClosure(dependencies, seen);
  }
  return seen;
};

const packageJson = JSON.parse(read("package.json"));

const scriptsInvokedByJob = (jobSource) => {
  const entries = new Set(
    [...jobSource.matchAll(/\bnode\s+(?:\.\/)?(scripts\/[a-zA-Z0-9._/-]+\.mjs)\b/g)].map(
      (match) => match[1],
    ),
  );
  for (const match of jobSource.matchAll(
    /\bpnpm\s+(?:run\s+)?([a-zA-Z0-9:_-]+)\b/g,
  )) {
    const command = packageJson.scripts?.[match[1]] ?? "";
    for (const scriptMatch of command.matchAll(
      /\bnode\s+(?:\.\/)?(scripts\/[a-zA-Z0-9._/-]+\.mjs)\b/g,
    )) {
      entries.add(scriptMatch[1]);
    }
  }
  return [...entries];
};

const jobProductionScriptClosureSource = (jobSource) => {
  const entries = scriptsInvokedByJob(jobSource).filter(
    (relativePath) => !/^scripts\/test-/.test(relativePath),
  );
  const closure = relativeModuleClosure(entries);
  return [...closure].map((relativePath) => read(relativePath)).join("\n");
};

const artifactNamesFor = (jobSource, operation) => {
  const pattern = new RegExp(
    `^        uses:\\s*actions\\/${operation}-artifact@v4[^\\n]*\\n(?:^        [^\\n]+\\n)*?^        with:\\s*\\n((?:^          [^\\n]+\\n?)+)`,
    "gm",
  );
  return [...jobSource.matchAll(pattern)]
    .map((match) => match[1].match(/^          name:\s*(.+)$/m)?.[1]?.trim())
    .filter(Boolean)
    .map((name) => name.replace(/^(["'])(.*)\1$/, "$2"));
};

const discoverCiSigningEntry = (signingJobSource) => {
  const candidates = scriptsInvokedByJob(signingJobSource).filter((entry) => {
    const closure = relativeModuleClosure([entry]);
    return [...closure].some((relativePath) =>
      read(relativePath).includes(privateKeyEnvironmentName),
    );
  });
  assert.equal(
    candidates.length,
    1,
    `signing job must invoke exactly one repository entry point that consumes ${privateKeyEnvironmentName}; found ${candidates.join(", ") || "none"}`,
  );
  return candidates[0];
};

const allWorkflowSources = () => {
  const directory = path.join(repoRoot, ".github", "workflows");
  return fs
    .readdirSync(directory)
    .filter((name) => /\.ya?ml$/i.test(name))
    .map((name) => ({ name, source: fs.readFileSync(path.join(directory, name), "utf8") }));
};

const trackedFiles = () => {
  const result = run("git", ["ls-files", "-z"]);
  assert.equal(result.status, 0, result.output);
  return result.output.split("\0").filter(Boolean);
};

const assertNoClientPrivateMaterial = () => {
  const clientFiles = trackedFiles().filter(
    (relativePath) =>
      /^(?:src|resources|static|deps\/db-sync)\//.test(relativePath) &&
      !/(?:^|\/)test(?:s)?\//.test(relativePath) &&
      !/(?:^|\/)__tests__\//.test(relativePath),
  );
  const offenders = clientFiles.filter((relativePath) => {
    const absolutePath = path.join(repoRoot, relativePath);
    if (!fs.statSync(absolutePath).isFile()) return false;
    const source = fs.readFileSync(absolutePath);
    if (source.includes(Buffer.from(privateKeyEnvironmentName))) return true;
    return /-----BEGIN (?:ENCRYPTED )?PRIVATE KEY-----/.test(source.toString("utf8"));
  });
  assert.deepEqual(
    offenders,
    [],
    `client/package inputs contain project private-key material: ${offenders.join(", ")}`,
  );
};

const signingContext = () => {
  const workflow = read(workflowRelativePath);
  const jobs = workflowJobs(workflow);
  const signing = singleJobWithEnvironment(jobs, signingEnvironmentName);
  return { jobs, signing, workflow };
};

const publishingContext = () => {
  const context = signingContext();
  const publishing = singleJobWithEnvironment(
    context.jobs,
    publishingEnvironmentName,
  );
  return { ...context, publishing };
};

const cases = [];
const addCase = (name, test) => cases.push([name, test]);

addCase(
  "A protected signing job is dispatch-only, selfhost-only, macOS, and read-only",
  () => {
    const { signing } = signingContext();
    assertSelfhostDispatchCondition(signing.source, "signing job");
    assert.match(
      signing.source,
      /^    runs-on:\s*macos-(?:latest|1[3-9])\s*$/m,
      "protected signing job must use a GitHub-hosted macOS runner",
    );
    assertOnlyReadPermissions(signing.source);
    const needs = jobNeeds(signing.source);
    assert.ok(needs.includes("release-rehearsal-gate"));
    assert.ok(needs.includes("release-assets-preflight"));
  },
);

addCase(
  "B signing secret has one fixed environment injection and no alternate exposure path",
  () => {
    const workflows = allWorkflowSources();
    const references = workflows.flatMap(({ name, source }) =>
      [
        ...source.matchAll(
          new RegExp(`secrets\\.${privateKeyEnvironmentName}\\b`, "g"),
        ),
      ].map(() => name),
    );
    assert.deepEqual(
      references,
      [path.basename(workflowRelativePath)],
      `${privateKeyEnvironmentName} must occur exactly once across workflows`,
    );
    const { signing } = signingContext();
    assert.equal(
      [...signing.source.matchAll(/\bsecrets\./g)].length,
      1,
      "signing job must not consume any additional GitHub secret",
    );
    assert.match(
      signing.source,
      new RegExp(
        `^\\s+${privateKeyEnvironmentName}:\\s*\\$\\{\\{\\s*secrets\\.${privateKeyEnvironmentName}\\s*\\}\\}\\s*$`,
        "m",
      ),
      "secret must be injected only through the fixed environment variable",
    );
    assert.doesNotMatch(
      signing.source,
      new RegExp(`(?:\\$\\{?${privateKeyEnvironmentName}\\}?|--[^\\n]*(?:private|secret|pkcs8|seed))`, "i"),
      "signing command passes private material through argv or shell expansion",
    );
    assert.doesNotMatch(
      signing.source,
      /\bsecurity\s+(?:add|delete|create|default|list|set|unlock)|add-generic-password|create-keychain/i,
      "GitHub signing job mutates Keychain or security settings",
    );
    const entry = discoverCiSigningEntry(signing.source);
    const closure = relativeModuleClosure([entry]);
    const closureSource = [...closure].map((relativePath) => read(relativePath)).join("\n");
    assert.match(
      closureSource,
      new RegExp(
        `process\\.env(?:\\.${privateKeyEnvironmentName}|\\[['\"]${privateKeyEnvironmentName}['\"]\\])`,
      ),
      "CI signer does not read the key directly from its fixed environment variable",
    );
    assert.doesNotMatch(
      closureSource,
      /process\.argv[\s\S]{0,160}(?:private|secret|pkcs8|seed)|--(?:private[-_]?key|secret|pkcs8|seed)\b/i,
      "CI signer accepts private material through argv",
    );
    assert.doesNotMatch(
      closureSource,
      /(?:writeFile|appendFile|createWriteStream|copyFile|rename)Sync?\s*\([^)]*(?:private|secret|pkcs8|seed|credential)/i,
      "CI signer can persist private material to a file",
    );
    assert.doesNotMatch(
      closureSource,
      /console\.(?:log|error|warn)\s*\([^)]*(?:privateKey|private_key|secret|pkcs8|seed)/i,
      "CI signer can print private material",
    );
    assert.doesNotMatch(
      signing.source,
      /(?:name|path):[^\n]*(?:private|secret|pkcs8|seed|credential)/i,
      "signing artifacts may contain private material",
    );
    assertNoClientPrivateMaterial();
  },
);

addCase(
  "C malformed, noncanonical, wrong-algorithm, and wrong-keyId secrets fail closed",
  () => {
    const productionConsumers = trackedFiles().filter(
      (relativePath) =>
        /^scripts\/.*\.mjs$/.test(relativePath) &&
        !/^scripts\/test-/.test(relativePath) &&
        read(relativePath).includes(privateKeyEnvironmentName),
    );
    assert.ok(
      productionConsumers.length > 0,
      `no production CI signing entry consumes ${privateKeyEnvironmentName}`,
    );
    const { signing } = signingContext();
    const entry = discoverCiSigningEntry(signing.source);
    const closureSource = [...relativeModuleClosure([entry])]
      .map((relativePath) => read(relativePath))
      .join("\n");
    assert.match(closureSource, /createPrivateKey/);
    assert.match(closureSource, /pkcs8/i);
    assert.match(closureSource, /ed25519/i);
    assert.match(closureSource, /keyId/);
    assert.match(closureSource, /toString\(\s*["']base64["']\s*\)/);

    const rsa = generateKeyPairSync("rsa", { modulusLength: 2048 }).privateKey.export({
      format: "der",
      type: "pkcs8",
    });
    const wrongEd25519 = generateKeyPairSync("ed25519").privateKey.export({
      format: "der",
      type: "pkcs8",
    });
    const rsaBase64 = Buffer.from(rsa).toString("base64");
    const probes = [
      ["missing", undefined],
      ["empty", ""],
      ["whitespace-only", " \n\t"],
      ["invalid base64", "%%%not-base64%%%"],
      ["noncanonical base64", `${rsaBase64.slice(0, 24)}\n${rsaBase64.slice(24)}`],
      ["non-Ed25519 PKCS8", rsaBase64],
      ["wrong policy keyId", Buffer.from(wrongEd25519).toString("base64")],
    ];
    for (const [label, value] of probes) {
      const env = { ...process.env };
      if (value === undefined) delete env[privateKeyEnvironmentName];
      else env[privateKeyEnvironmentName] = value;
      const result = run(process.execPath, [entry, "--validate-key-only"], { env });
      assert.ok(Number.isInteger(result.status), `${label} probe did not exit normally`);
      assert.notEqual(result.status, 0, `${label} secret was accepted`);
      if (value) {
        assert.equal(
          result.output.includes(value),
          false,
          `${label} secret was printed to logs`,
        );
      }
    }
  },
);

addCase(
  "D push, PR, nightly, nonselfhost, and unrehearsed refs cannot sign or publish",
  () => {
    const workflow = read(workflowRelativePath);
    const jobs = workflowJobs(workflow);
    const workflowPreamble = workflow.slice(0, workflow.indexOf("jobs:"));
    assert.doesNotMatch(workflowPreamble, /^\s*pull_request:/m);
    const rehearsal = jobs.get("release-rehearsal-gate") ?? "";
    assert.match(
      rehearsal,
      /^    outputs:\s*\n(?:^      [^\n]+\n)*^      (?:sha|source-sha|source_sha):\s*\$\{\{\s*steps\.source\.outputs\.sha\s*\}\}/m,
      "rehearsal gate does not expose its exact reviewed source SHA",
    );
    const signing = singleJobWithEnvironment(jobs, signingEnvironmentName);
    const publishing = singleJobWithEnvironment(jobs, publishingEnvironmentName);
    assertSelfhostDispatchCondition(signing.source, "signing job");
    assertSelfhostDispatchCondition(publishing.source, "publishing job");
    assert.ok(jobNeeds(signing.source).includes("release-rehearsal-gate"));
    assert.match(
      signing.source,
      /needs\.release-rehearsal-gate\.outputs\.(?:sha|source-sha|source_sha)/,
      "signing job is not pinned to the rehearsed source SHA",
    );
    for (const jobName of ["nightly-release", "release"]) {
      const source = jobs.get(jobName) ?? "";
      assert.match(
        source,
        /!\s*contains\([^\n]*['"]-selfhost\.['"]\)/,
        `${jobName} can publish selfhost artifacts outside the protected path`,
      );
    }
  },
);

addCase(
  "E six-platform candidates and both macOS metadata files bind exact VERSION and source SHA",
  () => {
    const workflow = read(workflowRelativePath);
    const jobs = workflowJobs(workflow);
    const preflight = jobs.get("release-assets-preflight") ?? "";
    const expectedBuilds = [
      "build-macos-x64",
      "build-macos-arm64",
      "build-linux-x64",
      "build-linux-arm64",
      "build-windows-x64",
      "build-windows-arm64",
    ];
    const needs = jobNeeds(preflight);
    for (const build of expectedBuilds) {
      assert.ok(needs.includes(build), `release preflight omits ${build}`);
    }
    const verifier = read("scripts/verify-desktop-release-assets.mjs");
    for (const expected of [
      "Logseq-darwin-arm64-",
      "Logseq-darwin-x64-",
      "Logseq-linux-arm64-",
      "Logseq-linux-x86_64-",
      "Logseq-win-arm64-",
      "Logseq-win-x64-",
      "VERSION",
    ]) {
      assert.ok(verifier.includes(expected), `release verifier omits ${expected}`);
    }
    assert.ok(
      (verifier.includes("selfhost-macos-v2-arm64-mac.yml") &&
        verifier.includes("selfhost-macos-v2-x64-mac.yml")) ||
        (/\["arm64",\s*"x64"\]/.test(verifier) &&
          /macosUpdaterMetadataName/.test(verifier)),
      "release verifier does not require both arm64 and x64 project metadata",
    );
    const signing = singleJobWithEnvironment(jobs, signingEnvironmentName);
    assert.match(
      signing.source,
      /needs\.release-assets-preflight\.outputs\.version/,
      "signing job does not consume the verified VERSION",
    );
    assert.match(
      signing.source,
      /needs\.release-rehearsal-gate\.outputs\.(?:sha|source-sha|source_sha)/,
      "signing job does not consume the rehearsed source SHA",
    );
    assert.match(
      signing.source,
      /(?:SOURCE_REVISION|--source-(?:sha|revision))[\s\S]{0,160}(?:needs\.release-rehearsal-gate\.outputs\.(?:sha|source-sha|source_sha))|(?:needs\.release-rehearsal-gate\.outputs\.(?:sha|source-sha|source_sha))[\s\S]{0,160}(?:SOURCE_REVISION|--source-(?:sha|revision))/,
      "SOURCE_REVISION is not bound directly to the exact rehearsed SHA",
    );
    const signingEffectiveSource = `${signing.source}\n${jobProductionScriptClosureSource(
      signing.source,
    )}`;
    assert.match(
      signingEffectiveSource,
      /SOURCE_REVISION/,
      "signing closure does not create the provenance sentinel",
    );
    assert.match(
      signingEffectiveSource,
      /(?:writeFile(?:Sync)?|appendFile(?:Sync)?)[\s\S]{0,280}SOURCE_REVISION|SOURCE_REVISION[\s\S]{0,280}(?:writeFile(?:Sync)?|appendFile(?:Sync)?)/,
      "signing closure does not write SOURCE_REVISION into the finalized artifact",
    );

    const finalVerifierMatches = [...jobs].filter(([name, source]) => {
      if (name === signing.name || !jobNeeds(source).includes(signing.name)) {
        return false;
      }
      const effectiveSource = `${source}\n${jobProductionScriptClosureSource(source)}`;
      return (
        /verify-project-signed-macos-update\.mjs/.test(effectiveSource) &&
        /verify-desktop-release-assets\.mjs/.test(effectiveSource) &&
        /SOURCE_REVISION/.test(effectiveSource)
      );
    });
    assert.equal(
      finalVerifierMatches.length,
      1,
      "expected one final verifier wrapper that also validates SOURCE_REVISION",
    );
    const [, finalVerifier] = finalVerifierMatches[0];
    assert.ok(
      jobNeeds(finalVerifier).includes("release-rehearsal-gate"),
      "final verifier does not depend on the exact rehearsal SHA",
    );
    assert.match(
      finalVerifier,
      /(?:SOURCE_REVISION|--source-(?:sha|revision))[\s\S]{0,160}(?:needs\.release-rehearsal-gate\.outputs\.(?:sha|source-sha|source_sha))|(?:needs\.release-rehearsal-gate\.outputs\.(?:sha|source-sha|source_sha))[\s\S]{0,160}(?:SOURCE_REVISION|--source-(?:sha|revision))/,
      "final verifier is not passed the exact rehearsed SHA",
    );
    const finalVerifierEffectiveSource = `${finalVerifier}\n${jobProductionScriptClosureSource(
      finalVerifier,
    )}`;
    assert.match(
      finalVerifierEffectiveSource,
      /(?:readFile(?:Sync)?)[\s\S]{0,280}SOURCE_REVISION|SOURCE_REVISION[\s\S]{0,280}(?:readFile(?:Sync)?)/,
      "final verifier does not read SOURCE_REVISION",
    );
    assert.match(
      finalVerifierEffectiveSource,
      /(?:!==|===|assert\.(?:equal|strictEqual)|timingSafeEqual)/,
      "final verifier does not compare provenance exactly",
    );
    const publishing = singleJobWithEnvironment(jobs, publishingEnvironmentName);
    assert.match(
      publishing.source,
      /SOURCE_REVISION/,
      "published release does not include SOURCE_REVISION",
    );
  },
);

addCase(
  "F finalized artifacts are reverified without the key before the sole selfhost writer publishes",
  () => {
    const workflow = read(workflowRelativePath);
    const jobs = workflowJobs(workflow);
    const invokesBothVerifiers = (source) => {
      const effectiveSource = `${source}\n${jobProductionScriptClosureSource(source)}`;
      return (
        /verify-project-signed-macos-update\.mjs/.test(effectiveSource) &&
        /verify-desktop-release-assets\.mjs/.test(effectiveSource)
      );
    };
    const postSigningVerifierCandidates = [...jobs].filter(
      ([, source]) =>
        invokesBothVerifiers(source) &&
        /actions\/download-artifact@v4/.test(source) &&
        /^    runs-on:\s*ubuntu-/m.test(source),
    );
    assert.ok(
      postSigningVerifierCandidates.length > 0,
      "no independent no-secret job reverifies finalized signed artifacts",
    );
    const signing = singleJobWithEnvironment(jobs, signingEnvironmentName);
    const publishing = singleJobWithEnvironment(jobs, publishingEnvironmentName);
    const verifierMatches = [...jobs].filter(([name, source]) => {
      if (name === signing.name || name === publishing.name) return false;
      return (
        jobNeeds(source).includes(signing.name) &&
        invokesBothVerifiers(source)
      );
    });
    assert.equal(
      verifierMatches.length,
      1,
      `expected one independent post-signing verifier; found ${verifierMatches
        .map(([name]) => name)
        .join(", ") || "none"}`,
    );
    const [verifierName, verifier] = verifierMatches[0];
    assert.match(verifier, /^    runs-on:\s*ubuntu-/m);
    assertOnlyReadPermissions(verifier);
    assert.doesNotMatch(verifier, new RegExp(privateKeyEnvironmentName));
    assert.doesNotMatch(verifier, /\bsecrets\./, "verifier references a secret");
    assert.match(verifier, /actions\/download-artifact@/);
    assert.ok(
      jobNeeds(publishing.source).includes(verifierName),
      "publishing job does not depend on the independent verifier",
    );
    const publishPermissions = permissionsFor(publishing.source);
    assert.equal(publishPermissions.get("contents"), "write");
    assert.equal(
      [...publishPermissions].some(([name, access]) => name !== "contents" && access === "write"),
      false,
      "publishing job has unrelated write permissions",
    );
    assert.doesNotMatch(publishing.source, new RegExp(privateKeyEnvironmentName));
    assert.doesNotMatch(
      publishing.source,
      /\bsecrets\./,
      "publisher references a secret instead of its scoped contents permission",
    );
    assert.doesNotMatch(signing.source, /contents:\s*write/);
    assert.doesNotMatch(verifier, /contents:\s*write/);
    assert.match(publishing.source, /actions\/download-artifact@/);
    const signingUploads = artifactNamesFor(signing.source, "upload");
    const verifierDownloads = artifactNamesFor(verifier, "download");
    const verifierUploads = artifactNamesFor(verifier, "upload");
    const publisherDownloads = artifactNamesFor(publishing.source, "download");
    assert.deepEqual(
      verifierUploads.filter((name) => signingUploads.includes(name)),
      [],
      "artifact v4 verifier attempts to overwrite the immutable signing artifact",
    );
    const sharedFinalizedArtifacts = [
      ...new Set(
        signingUploads.filter(
          (name) =>
            verifierDownloads.includes(name) && publisherDownloads.includes(name),
        ),
      ),
    ];
    assert.equal(
      sharedFinalizedArtifacts.length,
      1,
      `signer, verifier, and publisher must share one immutable finalized artifact; found ${
        sharedFinalizedArtifacts.join(", ") || "none"
      }`,
    );
  },
);

addCase(
  "G selfhost stable/beta publishes a public correctly tagged complete GitHub Release",
  () => {
    const workflow = read(workflowRelativePath);
    const jobs = workflowJobs(workflow);
    const selfhostPublishers = [...jobs].filter(([, source]) =>
      /softprops\/action-gh-release@v2/.test(source) &&
      environmentPattern(publishingEnvironmentName).test(source),
    );
    assert.equal(
      selfhostPublishers.length,
      1,
      `expected one selfhost GitHub Release publisher; found ${
        selfhostPublishers.map(([name]) => name).join(", ") || "none"
      }`,
    );
    const publishing = singleJobWithEnvironment(jobs, publishingEnvironmentName);
    assertSelfhostDispatchCondition(publishing.source, "publishing job");
    assert.match(publishing.source, /uses:\s*softprops\/action-gh-release@v2/);
    const verifierName = jobNeeds(publishing.source).find((name) => {
      const source = jobs.get(name) ?? "";
      const effectiveSource = `${source}\n${jobProductionScriptClosureSource(source)}`;
      return (
        /verify-project-signed-macos-update\.mjs/.test(effectiveSource) &&
        /verify-desktop-release-assets\.mjs/.test(effectiveSource)
      );
    });
    assert.ok(verifierName, "publishing job has no signed-artifact verifier dependency");
    assert.match(
      publishing.source,
      new RegExp(
        `tag_name:\\s*\\$\\{\\{\\s*needs\\.${verifierName}\\.outputs\\.version\\s*\\}\\}`,
      ),
      "release tag is not the independently verified VERSION",
    );
    assert.match(
      publishing.source,
      /draft:\s*(?:false|\$\{\{[^\n]*(?:github\.event\.inputs|inputs)\.is-draft[^\n]*\}\})/,
      "release draft state is not explicitly controlled by protected dispatch input",
    );
    assert.match(publishing.source, /prerelease:[^\n]*(?:beta|is-pre-release)/i);
    for (const asset of [
      "VERSION",
      "SOURCE_REVISION",
      "SHA256SUMS.txt",
      ".zip",
      ".dmg",
      ".exe",
      ".yml",
      ".blockmap",
      ".AppImage",
    ]) {
      assert.ok(
        publishing.source.includes(asset),
        `selfhost GitHub Release omits ${asset} assets`,
      );
    }
  },
);

addCase("H local Keychain finalization remains a supported compatible path", () => {
  assert.equal(
    packageJson.scripts?.["project-update:finalize-local-macos-candidates"],
    "node ./scripts/finalize-local-macos-project-update.mjs",
  );
  const workflow = read(workflowRelativePath);
  assert.doesNotMatch(
    workflow,
    /finalize-local-macos-project-update\.mjs|project-update:finalize-local-macos-candidates/,
    "GitHub Actions is using the local-Keychain finalizer",
  );
  const localContract = run(process.execPath, [
    "scripts/test-local-keychain-release-signing-contract.mjs",
  ]);
  assert.equal(localContract.status, 0, localContract.output);
  const docs = `${read("README.md")}\n${read("docs/selfhost-sync.md")}\n${read(
    "docs/releases/2.0.1-selfhost.5.md",
  )}`;
  assert.match(
    docs,
    /(?:GitHub[\s\S]{0,100}(?:protected\s+)?Environment[\s\S]{0,100}(?:secret|私钥)|受保护的?\s*GitHub[\s\S]{0,100}(?:环境|secret|私钥))/i,
    "docs omit the protected GitHub Environment Secret signing path",
  );
  assert.match(
    docs,
    /(?:local|本地)[\s\S]{0,120}(?:macOS\s+)?Keychain[\s\S]{0,160}(?:alternative|fallback|compatible|兼容|保留|仍可)|(?:macOS\s+)?Keychain[\s\S]{0,160}(?:local|本地)[\s\S]{0,160}(?:alternative|fallback|compatible|兼容|保留|仍可)/i,
    "docs do not preserve local Keychain finalization as an alternative",
  );
});

addCase("I packaged clients contain no private key and .5 to .6+ UX is unchanged", () => {
  assertNoClientPrivateMaterial();
  const guidance = run(process.execPath, ["scripts/test-selfhost-macos-user-guidance.mjs"]);
  assert.equal(guidance.status, 0, guidance.output);
  const docs = `${read("docs/releases/2.0.1-selfhost.5.md")}\n${read(
    "docs/selfhost-sync.md",
  )}`;
  assert.match(
    docs,
    /\.5[\s\S]{0,500}(?:\.6\+?|later|future|后续)|(?:\.6\+?|later|future|后续)[\s\S]{0,500}\.5/i,
    "docs do not preserve the .5 to .6+ automatic-update transition",
  );
  assert.match(
    docs,
    /(?:automatic(?:ally)?|自动)[\s\S]{0,220}(?:check|检查)[\s\S]{0,220}(?:download|下载)|(?:check|检查)[\s\S]{0,220}(?:download|下载)[\s\S]{0,220}(?:automatic(?:ally)?|自动)/i,
    "automatic check/download UX changed",
  );
  assert.match(
    docs,
    /(?:click|select|choose|点击|选择)[\s\S]{0,120}Restart and install|Restart and install[\s\S]{0,120}(?:click|select|choose|点击|选择)/i,
    "Restart and install remains user-controlled but is undocumented",
  );
});

addCase("formal desktop release contracts execute this protected pipeline gate", () => {
  assert.equal(
    packageJson.scripts?.["project-update:test-github-signing-pipeline-contract"],
    "node ./scripts/test-github-selfhost-signing-pipeline-contract.mjs",
  );
  assert.match(
    packageJson.scripts?.["desktop:test-release-contracts"] ?? "",
    /test-github-selfhost-signing-pipeline-contract\.mjs/,
  );
});

let passed = 0;
let failed = 0;
for (const [name, test] of cases) {
  try {
    await test();
    passed += 1;
    console.log(`[github-selfhost-signing-contract] PASS ${name}`);
  } catch (error) {
    failed += 1;
    console.error(
      `[github-selfhost-signing-contract] FAIL ${name}: ${
        error instanceof Error ? error.message : error
      }`,
    );
  }
}

console.log(
  `[github-selfhost-signing-contract] SUMMARY ${passed} passed, ${failed} failed`,
);
if (failed > 0) process.exitCode = 1;
