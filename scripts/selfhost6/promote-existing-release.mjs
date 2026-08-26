#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { readReleasePolicy } from "../lib/selfhost6-release-identity.mjs";
import {
  PromotionError,
  promoteExistingRelease,
} from "../lib/selfhost6-existing-release-promotion.mjs";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");

function parseArgs(argv) {
  const result = {};
  for (let index = 0; index < argv.length; index += 2) {
    const flag = argv[index];
    const value = argv[index + 1];
    assert.ok(value, `${flag} requires a value`);
    if (flag === "--artifact-directory") result.artifactDirectory = path.resolve(value);
    else if (flag === "--pointer-directory") result.pointerDirectory = path.resolve(value);
    else if (flag === "--release-line-id") result.releaseLineId = value;
    else if (flag === "--expected-source-full-sha") result.expectedSourceFullSha = value;
    else if (flag === "--expected-version") result.expectedVersion = value;
    else if (flag === "--receipt") result.receiptPath = path.resolve(value);
    else throw new Error(`unknown argument: ${flag}`);
  }
  for (const field of [
    "artifactDirectory",
    "pointerDirectory",
    "releaseLineId",
    "expectedSourceFullSha",
    "expectedVersion",
    "receiptPath",
  ]) assert.ok(result[field], `${field} is required`);
  return result;
}

function readFiles(directory, names) {
  return new Map(names.map((name) => {
    const filePath = path.join(directory, name);
    assert.ok(fs.statSync(filePath).isFile(), `missing promotion input ${name}`);
    return [name, fs.readFileSync(filePath)];
  }));
}

function immutableFiles(directory) {
  const names = fs.readdirSync(directory, { withFileTypes: true })
    .filter((entry) => entry.isFile() && entry.name.startsWith("Logseq-"))
    .map(({ name }) => name)
    .sort();
  assert.equal(names.length, 16, "promotion input must contain sixteen formal release files");
  return readFiles(directory, names);
}

export class GitHubReleaseApi {
  constructor({ repository, token, apiBaseUrl, fetchImpl = fetch }) {
    assert.match(repository ?? "", /^[^/]+\/[^/]+$/, "GITHUB_REPOSITORY is invalid");
    assert.ok(token, "GITHUB_TOKEN or GH_TOKEN is required");
    this.repository = repository;
    this.token = token;
    this.apiBaseUrl = apiBaseUrl.replace(/\/$/, "");
    this.fetch = fetchImpl;
  }

  async request(url, { method = "GET", accept = "application/vnd.github+json", body } = {}) {
    const response = await this.fetch(url, {
      method,
      redirect: "follow",
      headers: {
        Accept: accept,
        Authorization: `Bearer ${this.token}`,
        "Content-Type": "application/octet-stream",
        "User-Agent": "logseq-selfhost-release-promotion",
        "X-GitHub-Api-Version": "2022-11-28",
      },
      body,
    });
    if (!response.ok) {
      throw new Error(`GitHub ${method} request failed with HTTP ${response.status}`);
    }
    return response;
  }

  apiUrl(suffix) {
    return `${this.apiBaseUrl}/repos/${this.repository}${suffix}`;
  }

  async json(suffix) {
    return (await this.request(this.apiUrl(suffix))).json();
  }

  async getReleaseByTag(tag) {
    const value = await this.json(`/releases/tags/${encodeURIComponent(tag)}`);
    return {
      id: value.id,
      tagName: value.tag_name,
      targetCommitish: value.target_commitish,
      uploadUrl: value.upload_url,
    };
  }

  async getTagIdentity(tag) {
    const reference = await this.json(`/git/ref/tags/${encodeURIComponent(tag)}`);
    const objectSha = reference.object.sha;
    let object = reference.object;
    for (let depth = 0; object.type === "tag" && depth < 8; depth += 1) {
      object = (await this.json(`/git/tags/${object.sha}`)).object;
    }
    assert.equal(object.type, "commit", "release tag does not peel to a commit");
    return { objectSha, peeledCommitSha: object.sha };
  }

  async listAssets(releaseId) {
    const assets = [];
    for (let page = 1; page <= 10; page += 1) {
      const batch = await this.json(`/releases/${releaseId}/assets?per_page=100&page=${page}`);
      assets.push(...batch.map((asset) => ({
        id: asset.id,
        name: asset.name,
        size: asset.size,
        digest: asset.digest,
      })));
      if (batch.length < 100) return assets;
    }
    throw new Error("release asset inventory exceeds the bounded page limit");
  }

  async downloadAsset(assetId) {
    const response = await this.request(this.apiUrl(`/releases/assets/${assetId}`), {
      accept: "application/octet-stream",
    });
    return Buffer.from(await response.arrayBuffer());
  }

  async uploadAsset(release, name, bytes) {
    assert.equal(typeof release.uploadUrl, "string", "existing release has no upload URL");
    const baseUrl = release.uploadUrl.replace(/\{.*$/, "");
    await this.request(`${baseUrl}?name=${encodeURIComponent(name)}`, {
      method: "POST",
      body: bytes,
    });
  }

  async deleteAsset(assetId) {
    await this.request(this.apiUrl(`/releases/assets/${assetId}`), { method: "DELETE" });
  }
}

function writeReceipt(receiptPath, receipt) {
  fs.mkdirSync(path.dirname(receiptPath), { recursive: true });
  fs.writeFileSync(receiptPath, `${JSON.stringify(receipt, null, 2)}\n`, {
    flag: "wx",
    mode: 0o600,
  });
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  const policy = readReleasePolicy(
    path.join(repoRoot, "resources/updater/selfhost-release-policy.json"),
  );
  assert.equal(options.releaseLineId, policy.releaseLineId);
  assert.equal(options.expectedVersion, policy.sourceVersion);
  const pointerNames = [
    `${options.releaseLineId}.yml`,
    `${options.releaseLineId}-mac.yml`,
    `${options.releaseLineId}-linux.yml`,
    `${options.releaseLineId}-linux-arm64.yml`,
  ];
  const api = new GitHubReleaseApi({
    repository: process.env.GITHUB_REPOSITORY,
    token: process.env.GITHUB_TOKEN ?? process.env.GH_TOKEN,
    apiBaseUrl: process.env.GITHUB_API_URL ?? "https://api.github.com",
  });
  const expectedReleaseId = Number(process.env.SELFHOST_EXISTING_RELEASE_ID);
  assert.ok(Number.isSafeInteger(expectedReleaseId), "SELFHOST_EXISTING_RELEASE_ID is required");
  const expectedReleaseIdentity = {
    releaseId: expectedReleaseId,
    releaseTargetFullSha: process.env.SELFHOST_EXISTING_RELEASE_TARGET_FULL_SHA,
    tagObjectFullSha: process.env.SELFHOST_EXISTING_TAG_OBJECT_FULL_SHA,
    tagPeeledCommitFullSha: process.env.SELFHOST_EXISTING_TAG_PEELED_COMMIT_FULL_SHA,
  };
  try {
    const receipt = await promoteExistingRelease({
      releaseLineId: options.releaseLineId,
      expectedSourceFullSha: options.expectedSourceFullSha,
      expectedVersion: options.expectedVersion,
      expectedReleaseIdentity,
      immutableFiles: immutableFiles(options.artifactDirectory),
      pointerFiles: readFiles(options.pointerDirectory, pointerNames),
      api,
    });
    writeReceipt(options.receiptPath, receipt);
    process.stdout.write(`${JSON.stringify({ status: receipt.status })}\n`);
  } catch (error) {
    const receipt = error instanceof PromotionError
      ? error.receipt
      : { schemaVersion: 1, status: "promotion-input-rejected" };
    writeReceipt(options.receiptPath, receipt);
    process.stderr.write(`existing-release promotion stopped: ${receipt.status}\n`);
    process.exitCode = 1;
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await main();
}
