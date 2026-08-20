#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const rootPackagePath = path.join(repoRoot, "package.json");
const desktopPackagePath = path.join(repoRoot, "resources", "package.json");
const rootPackage = JSON.parse(fs.readFileSync(rootPackagePath, "utf8"));
const desktopPackage = JSON.parse(
  fs.readFileSync(desktopPackagePath, "utf8"),
);

const nodes = new Map();
const queue = [];
const addNode = ({ id, kind, source, trace }) => {
  if (nodes.has(id)) return;
  nodes.set(id, { id, kind, source, trace });
  queue.push(nodes.get(id));
};

const runtimeEntry = (name, command) =>
  !/^(?:test|lint|format|cljs:test)/i.test(name) &&
  !/(?:^|\/)test[-_]|static\/tests\.js/i.test(command) &&
  /(?:electron|desktop|release|package|make|build|updat|sign|macos|gulp)/i.test(
    `${name} ${command}`,
  );

const packageScripts = new Map();
for (const [packagePath, packageJson] of [
  [rootPackagePath, rootPackage],
  [desktopPackagePath, desktopPackage],
]) {
  const packageDirectory = path.dirname(packagePath);
  for (const [name, command] of Object.entries(packageJson.scripts ?? {})) {
    const id = `${packagePath}#${name}`;
    packageScripts.set(`${packageDirectory}\0${name}`, id);
    if (runtimeEntry(name, command)) {
      addNode({
        id,
        kind: "command",
        source: command,
        trace: [id],
      });
    }
  }
}

const scriptsDirectory = path.join(repoRoot, "scripts");
for (const entry of fs.readdirSync(scriptsDirectory, {
  withFileTypes: true,
})) {
  if (
    !entry.isFile() ||
    !/\.(?:c?js|mjs|sh)$/i.test(entry.name) ||
    /^(?:test|verify|reproduce)[-_]/i.test(entry.name)
  ) {
    continue;
  }
  const filePath = path.join(scriptsDirectory, entry.name);
  const source = fs.readFileSync(filePath, "utf8");
  if (source.startsWith("#!")) {
    addNode({
      id: filePath,
      kind: "file",
      source,
      trace: [filePath],
    });
  }
}

for (const filePath of [
  path.join(repoRoot, "gulpfile.js"),
  path.join(repoRoot, "resources", "electron-builder.yml"),
  path.join(repoRoot, "resources", "electron-builder.unsigned.yml"),
]) {
  if (fs.existsSync(filePath)) {
    addNode({
      id: filePath,
      kind: "file",
      source: fs.readFileSync(filePath, "utf8"),
      trace: [filePath],
    });
  }
}

const resolveFile = (specifier, fromFile) => {
  if (!specifier.startsWith(".") && !specifier.startsWith("/")) return null;
  const base = specifier.startsWith("/")
    ? specifier
    : path.resolve(path.dirname(fromFile), specifier);
  for (const candidate of [
    base,
    `${base}.mjs`,
    `${base}.cjs`,
    `${base}.js`,
    `${base}.json`,
    `${base}.yml`,
    `${base}.yaml`,
    path.join(base, "index.mjs"),
    path.join(base, "index.js"),
  ]) {
    if (
      candidate.startsWith(`${repoRoot}${path.sep}`) &&
      fs.existsSync(candidate) &&
      fs.statSync(candidate).isFile()
    ) {
      return candidate;
    }
  }
  return null;
};

const enqueueFile = (filePath, trace) => {
  addNode({
    id: filePath,
    kind: "file",
    source: fs.readFileSync(filePath, "utf8"),
    trace: [...trace, filePath],
  });
};

for (let index = 0; index < queue.length; index += 1) {
  const node = queue[index];
  if (node.kind === "command") {
    for (const match of node.source.matchAll(
      /(?:^|&&|\|\||;)\s*node\s+([^\s"';&|]+)/g,
    )) {
      const packagePath = node.id.slice(0, node.id.indexOf("#"));
      const resolved = resolveFile(match[1], packagePath);
      if (resolved) enqueueFile(resolved, node.trace);
    }
    for (const match of node.source.matchAll(
      /\b(?:run-[sp]|npm-run-all)\s+([^;&|]+)/g,
    )) {
      const packagePath = node.id.slice(0, node.id.indexOf("#"));
      const packageDirectory = path.dirname(packagePath);
      for (const scriptName of match[1].match(/[A-Za-z0-9:._-]+/g) ?? []) {
        const dependencyId = packageScripts.get(
          `${packageDirectory}\0${scriptName}`,
        );
        if (dependencyId && !nodes.has(dependencyId)) {
          const [dependencyPackagePath, name] = dependencyId.split("#");
          const dependencyPackage = dependencyPackagePath === rootPackagePath
            ? rootPackage
            : desktopPackage;
          addNode({
            id: dependencyId,
            kind: "command",
            source: dependencyPackage.scripts[name],
            trace: [...node.trace, dependencyId],
          });
        }
      }
    }
    if (/\bgulp(?:\s|$)/.test(node.source)) {
      enqueueFile(path.join(repoRoot, "gulpfile.js"), node.trace);
    }
    for (const match of node.source.matchAll(
      /electron-builder[^\n]*?--config\s+([^\s"';&|]+)/g,
    )) {
      const configPath = resolveFile(
        match[1],
        node.id.startsWith(desktopPackagePath)
          ? desktopPackagePath
          : rootPackagePath,
      );
      if (configPath) enqueueFile(configPath, node.trace);
    }
  } else {
    for (const match of node.source.matchAll(
      /(?:from\s*|require\(\s*)["']([^"']+)["']/g,
    )) {
      if (/\.json$/i.test(match[1])) continue;
      const resolved = resolveFile(match[1], node.id);
      if (resolved) enqueueFile(resolved, node.trace);
    }
    for (const match of node.source.matchAll(
      /["']([^"']+\.(?:c?js|mjs|sh))["']/g,
    )) {
      const resolved = resolveFile(match[1], node.id);
      if (resolved) enqueueFile(resolved, node.trace);
    }
    for (const match of node.source.matchAll(
      /runStaticScript\(\s*["']([^"']+)["']\s*\)/g,
    )) {
      const dependencyId = packageScripts.get(
        `${path.dirname(desktopPackagePath)}\0${match[1]}`,
      );
      if (dependencyId && !nodes.has(dependencyId)) {
        addNode({
          id: dependencyId,
          kind: "command",
          source: desktopPackage.scripts[match[1]],
          trace: [...node.trace, dependencyId],
        });
      }
    }
  }
}

const forbidden = [
  [
    "callable local certificate/keychain setup",
    /setup-(?:local-)?macos-(?:codesign|signing)|setup-macos-local-signing|electronMakerLocalSigned|make-local-signed|electron-builder-local-signed/i,
  ],
  [
    "certificate import",
    /(?:spawnSync|execFileSync|command|run)\s*\(\s*["'](?:\/usr\/bin\/)?security["'][\s\S]{0,500}["']import["']/i,
  ],
  [
    "trusted root installation",
    /\badd-trusted-cert\b|\btrustRoot\b/i,
  ],
  [
    "user keychain search-list mutation",
    /["']list-keychains["'][\s\S]{0,300}(?:["']-s["']|["']--set["'])/i,
  ],
  [
    "default keychain mutation",
    /["']default-keychain["'][\s\S]{0,200}(?:["']-s["']|["']--set["'])/i,
  ],
  [
    "Trust Settings mutation",
    /\btrust-settings-(?:import|admin|write)\b|authorizationdb[\s\S]{0,120}\bwrite\b/i,
  ],
];

const violations = [];
for (const node of nodes.values()) {
  for (const [label, pattern] of forbidden) {
    if (pattern.test(node.source)) {
      violations.push(
        `${label}: ${node.trace.join(" -> ")}`,
      );
    }
  }
}
assert.deepEqual(
  violations,
  [],
  `packaging/updater command graph can mutate local trust:\n${violations.join(
    "\n",
  )}`,
);

const unsignedBuilderPath = path.join(
  repoRoot,
  "resources",
  "electron-builder-unsigned.mjs",
);
const unsignedConfigPath = path.join(
  repoRoot,
  "resources",
  "electron-builder.unsigned.yml",
);
const unsignedBuilder = fs.readFileSync(unsignedBuilderPath, "utf8");
const unsignedConfig = fs.readFileSync(unsignedConfigPath, "utf8");
assert.match(
  unsignedBuilder,
  /CSC_IDENTITY_AUTO_DISCOVERY:\s*["']false["']/,
  "no-Apple-Developer packaging does not disable identity discovery",
);
const afterSignReference = unsignedConfig.match(
  /^afterSign:\s*(\S+)\s*$/m,
)?.[1];
assert.ok(
  afterSignReference,
  "no-Apple-Developer packaging has no explicit ad-hoc afterSign hook",
);
const afterSignPath = resolveFile(afterSignReference, unsignedConfigPath);
assert.ok(afterSignPath, "ad-hoc afterSign hook is missing");
const afterSign = fs.readFileSync(afterSignPath, "utf8");
assert.match(
  afterSign,
  /["']--sign["']\s*,\s*["']-["']/,
  "no-Apple-Developer package is not explicitly ad-hoc signed",
);
for (const [, pattern] of forbidden) {
  assert.doesNotMatch(
    afterSign,
    pattern,
    "ad-hoc afterSign hook mutates certificate or keychain trust",
  );
}

const guidanceTest = fs.readFileSync(
  path.join(repoRoot, "scripts", "test-selfhost-macos-user-guidance.mjs"),
  "utf8",
);
assert.match(
  guidanceTest,
  /Open Anyway/,
  "no-Apple-Developer path does not enforce Open Anyway user guidance",
);

console.log(
  `[no-local-trust] OK scanned ${nodes.size} reachable packaging/updater command nodes; ad-hoc codesign plus Open Anyway is the only local path`,
);
