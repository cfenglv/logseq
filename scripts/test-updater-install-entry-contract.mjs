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
assert.match(
  installDownloadedUpdate,
  /run-project-signed-install!/,
  "downloaded-update installer bypasses the mocked project-signed timing seam",
);

console.log(
  "[updater-install-entry-contract] PASS header and settings share guarded main install flow",
);
