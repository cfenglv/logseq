#!/usr/bin/env node

import assert from "node:assert/strict";
import path from "node:path";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import {
  macosUpdaterChannel,
  macosUpdaterMetadataName,
  resolveSelfhostUpdaterVersions,
} from "./selfhost-updater-version.mjs";

const packageRoot = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(path.join(packageRoot, "package.json"));
const { GitHubProvider } = require(
  "electron-updater/out/providers/GitHubProvider",
);
const semver = require("semver");
const packageJson = require(path.join(packageRoot, "package.json"));

const {
  currentVersion,
  isNightlyRehearsal,
  nextVersion,
} = resolveSelfhostUpdaterVersions(packageJson.version);

const releaseEntry = (version) => `
  <entry>
    <title>${version}</title>
    <link href="https://github.com/cfenglv/logseq/releases/tag/${version}"/>
    <content>Updater provider rehearsal for ${version}.</content>
  </entry>`;
const feed = `<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <title>Release notes from logseq</title>
  ${releaseEntry(nextVersion)}
  ${releaseEntry(currentVersion)}
</feed>`;

const metadataName = (platform, arch) => {
  if (platform === "darwin") {
    return macosUpdaterMetadataName(currentVersion, arch);
  }
  if (platform === "win32") return `latest-${arch}.yml`;
  if (platform === "linux") {
    return arch === "x64" ? "latest-linux.yml" : `latest-linux-${arch}.yml`;
  }
  throw new Error(`unsupported updater rehearsal platform: ${platform}`);
};

const releaseAssetName = (platform, arch) => {
  if (platform === "darwin") {
    return `Logseq-darwin-${arch}-${nextVersion}.zip`;
  }
  if (platform === "win32") {
    return `Logseq-win-${arch}-${nextVersion}-nsis.exe`;
  }
  return `Logseq-linux-${arch === "x64" ? "x86_64" : arch}-${nextVersion}.AppImage`;
};

const channel = (platform, arch) =>
  platform === "darwin"
    ? macosUpdaterChannel(currentVersion, arch)
    : platform === "win32"
      ? `latest-${arch}`
      : null;

const makeProvider = ({ platform, arch, allowPrerelease }) => {
  const expectedMetadata = metadataName(platform, arch);
  const assetName = releaseAssetName(platform, arch);
  const requests = [];
  const metadata = [
    `version: ${nextVersion}`,
    "files:",
    `  - url: ${assetName}`,
    "    sha512: YQ==",
    "    size: 1",
    `path: ${assetName}`,
    "sha512: YQ==",
    "releaseDate: '2026-07-25T00:00:00.000Z'",
    "",
  ].join("\n");
  const updater = {
    allowPrerelease,
    channel: channel(platform, arch),
    currentVersion: semver.parse(currentVersion),
    fullChangelog: false,
  };
  const provider = new GitHubProvider(
    {
      provider: "github",
      owner: "cfenglv",
      repo: "logseq",
    },
    updater,
    {
      executor: {
        request: async (options) => {
          const requestPath = options.path ?? options.pathname ?? "";
          requests.push(requestPath);
          if (requestPath.endsWith(`/${expectedMetadata}`)) return metadata;
          throw new Error(
            `unexpected updater metadata request: ${requestPath}`,
          );
        },
      },
      platform,
      isUseMultipleRangeRequest: false,
    },
  );
  provider.httpRequest = async (url) => {
    requests.push(url.pathname);
    if (url.pathname.endsWith(".atom")) return feed;
    if (url.pathname.endsWith("/releases/latest")) {
      return JSON.stringify({ tag_name: nextVersion });
    }
    throw new Error(`unexpected updater provider request: ${url}`);
  };
  return { provider, requests, expectedMetadata, assetName };
};

for (const [platform, arch] of [
  ["darwin", "x64"],
  ["darwin", "arm64"],
  ["win32", "x64"],
  ["win32", "arm64"],
  ["linux", "x64"],
  ["linux", "arm64"],
]) {
  const previousTestArch = process.env.TEST_UPDATER_ARCH;
  process.env.TEST_UPDATER_ARCH = arch;
  try {
    const { provider, requests, expectedMetadata, assetName } = makeProvider({
      platform,
      arch,
      allowPrerelease: false,
    });
    const info = await provider.getLatestVersion();
    assert.equal(info.tag, nextVersion);
    assert.equal(info.version, nextVersion);
    assert.ok(
      requests.some((request) => request.endsWith("/releases/latest")),
      `${platform}/${arch} must select GitHub's latest production release`,
    );
    assert.ok(
      requests.some((request) => request.endsWith(`/${expectedMetadata}`)),
      `${platform}/${arch} must request ${expectedMetadata}`,
    );
    const resolved = provider.resolveFiles(info);
    assert.equal(resolved.length, 1);
    assert.ok(resolved[0].url.pathname.endsWith(`/${assetName}`));
  } finally {
    if (previousTestArch === undefined) {
      delete process.env.TEST_UPDATER_ARCH;
    } else {
      process.env.TEST_UPDATER_ARCH = previousTestArch;
    }
  }
}

const broken = makeProvider({
  platform: "darwin",
  arch: "arm64",
  allowPrerelease: true,
});
await assert.rejects(
  broken.provider.getLatestVersion(),
  (error) => error?.code === "ERR_UPDATER_NO_PUBLISHED_VERSIONS",
  "the rehearsal must preserve the prerelease/channel mismatch regression case",
);

console.log(
  `[updater-provider] OK ${currentVersion} -> ${nextVersion} across six platform/architecture contracts${isNightlyRehearsal ? ` (normalized from ${packageJson.version})` : ""}`,
);
