#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const frontendHandler = fs.readFileSync(
  path.join(repoRoot, "src", "main", "frontend", "handler.cljs"),
  "utf8",
);
const settings = fs.readFileSync(
  path.join(
    repoRoot,
    "src",
    "main",
    "frontend",
    "components",
    "settings.cljs",
  ),
  "utf8",
);
const electronUpdater = fs.readFileSync(
  path.join(repoRoot, "src", "electron", "electron", "updater.cljs"),
  "utf8",
);
const fullPreflight = fs.readFileSync(
  path.join(repoRoot, "scripts", "run-desktop-release-preflight.mjs"),
  "utf8",
);
const releaseWorkflow = fs.readFileSync(
  path.join(repoRoot, ".github", "workflows", "build-desktop-release.yml"),
  "utf8",
);

const headerEntry = frontendHandler.match(
  /\(defn\s+quit-and-install-new-version![\s\S]*?(?=\n\(defn|\n\(defmethod|\s*$)/,
)?.[0];
assert.ok(headerEntry, "header update-install entry is missing");
assert.doesNotMatch(
  headerEntry,
  /set-quit-dirty-state/,
  "renderer header disables dirty protection before native verification/spawn",
);
assert.match(
  headerEntry,
  /ipc\/quit-and-install-new-version!/,
  "renderer header does not route through the shared install entry",
);

const settingsDownloadedBranch = settings.match(
  /"update-downloaded"[\s\S]*?(?=\n\s*"error")/,
)?.[0];
assert.ok(settingsDownloadedBranch, "settings update-downloaded branch is missing");
assert.doesNotMatch(
  settingsDownloadedBranch,
  /set-quit-dirty-state/,
  "settings disables dirty protection before native verification/spawn",
);
assert.match(
  settingsDownloadedBranch,
  /ipc\/quit-and-install-new-version!/,
  "settings does not route through the shared install entry",
);

const installListener = electronUpdater.match(
  /install-listener[\s\S]*?(?=\n\s*get-downloaded-listener)/,
)?.[0];
assert.ok(installListener, "header install-updates listener is missing");
assert.match(
  installListener,
  /install-downloaded-update!/,
  "install-updates IPC entry bypasses the guarded project-signed install flow",
);

const installDownloadedUpdate = electronUpdater.match(
  /\(defn-?\s+install-downloaded-update![\s\S]*?(?=\n\(defn|\s*$)/,
)?.[0];
assert.ok(
  installDownloadedUpdate,
  "main downloaded-update installer is missing",
);
const directlyUsesTimingSeam =
  /run-project-signed-install!/.test(installDownloadedUpdate);
const oneLayerDelegateNames = [
  ...installDownloadedUpdate.matchAll(
    /\(([\w<>!?*-]*project-signed[\w<>!?*-]*)\b/g,
  ),
].map((match) => match[1]);
const delegatedToTimingSeam = oneLayerDelegateNames.some((delegateName) => {
  const escaped = delegateName.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const definition = electronUpdater.match(
    new RegExp(
      `\\(defn-?\\s+${escaped}[\\s\\S]*?(?=\\n\\(defn|\\s*$)`,
    ),
  )?.[0];
  return definition && /run-project-signed-install!/.test(definition);
});
assert.ok(
  directlyUsesTimingSeam || delegatedToTimingSeam,
  "downloaded-update installer bypasses the mocked project-signed timing seam",
);

const configureAutoUpdater = electronUpdater.match(
  /\(defn-?\s+configure-auto-updater![\s\S]*?(?=\n\(defn|\s*$)/,
)?.[0];
assert.ok(configureAutoUpdater, "auto-updater configuration is missing");
assert.match(
  configureAutoUpdater,
  /isUpdateSupported/,
  "auto-updater runtime does not install a stable/nightly track policy",
);
assert.match(
  configureAutoUpdater,
  /updater-config\/updater-options/,
  "auto-updater runtime does not consume the black-box provider options",
);
assert.match(
  configureAutoUpdater,
  /setFeedURL/,
  "auto-updater runtime does not apply the isolated rolling Generic feed returned for nightly clients",
);

assert.match(
  fullPreflight,
  /electron\\\\\.\([^\n]*\bupdater\b[^\n]*\)-test/,
  "full desktop preflight does not execute the updater mock-timing behavior test",
);
assert.match(
  releaseWorkflow,
  /rtc-release-gate:[\s\S]*?node[^\n]*static\/tests\.js[\s\S]*?electron\\\.\([^\n]*\bupdater\b[^\n]*\)-test/,
  "desktop release CI does not execute the updater mock-timing behavior test",
);

console.log(
  "[updater-install-entry-contract] PASS shared guarded install flow and formal mock-timing gates",
);
