#!/usr/bin/env node

import assert from "node:assert/strict";
import crypto from "node:crypto";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const verifierPath = path.join(
  repositoryRoot,
  "scripts",
  "verify-desktop-release-assets.mjs",
);
const nightlyVersion = "2.0.1-selfhost.5.nightly.20260804";
const stableVersion = "2.0.1-selfhost.5";
const legacyVersion = "2.0.1-selfhost.4";

const versionContracts = new Map([
  [
    nightlyVersion,
    {
      currentMac: {
        arm64: "selfhost-macos-v2-nightly-arm64-mac.yml",
        x64: "selfhost-macos-v2-nightly-x64-mac.yml",
      },
      includeLegacySentinels: true,
    },
  ],
  [
    stableVersion,
    {
      currentMac: {
        arm64: "selfhost-macos-v2-arm64-mac.yml",
        x64: "selfhost-macos-v2-x64-mac.yml",
      },
      includeLegacySentinels: true,
    },
  ],
  [
    legacyVersion,
    {
      currentMac: {
        arm64: "latest-arm64-mac.yml",
        x64: "latest-x64-mac.yml",
      },
      includeLegacySentinels: false,
    },
  ],
]);

const commonMetadataTargets = (version) => ({
  "latest-arm64.yml": `Logseq-win-arm64-${version}-nsis.exe`,
  "latest-linux-arm64.yml": `Logseq-linux-arm64-${version}.AppImage`,
  "latest-linux.yml": `Logseq-linux-x86_64-${version}.AppImage`,
  "latest-x64.yml": `Logseq-win-x64-${version}-nsis.exe`,
});

const payloadNames = (version) => [
  `Logseq-darwin-arm64-${version}.dmg`,
  `Logseq-darwin-arm64-${version}.dmg.blockmap`,
  `Logseq-darwin-arm64-${version}.zip`,
  `Logseq-darwin-arm64-${version}.zip.blockmap`,
  `Logseq-darwin-x64-${version}.dmg`,
  `Logseq-darwin-x64-${version}.dmg.blockmap`,
  `Logseq-darwin-x64-${version}.zip`,
  `Logseq-darwin-x64-${version}.zip.blockmap`,
  `Logseq-linux-arm64-${version}.AppImage`,
  `Logseq-linux-arm64-${version}.zip`,
  `Logseq-linux-x86_64-${version}.AppImage`,
  `Logseq-linux-x86_64-${version}.zip`,
  `Logseq-win-arm64-${version}-nsis.exe`,
  `Logseq-win-arm64-${version}-nsis.exe.blockmap`,
  `Logseq-win-arm64-${version}.zip`,
  `Logseq-win-x64-${version}-nsis.exe`,
  `Logseq-win-x64-${version}-nsis.exe.blockmap`,
  `Logseq-win-x64-${version}.zip`,
];

const metadataTargets = (version) => {
  const contract = versionContracts.get(version);
  assert.ok(contract, `missing independent fixture contract for ${version}`);
  return {
    ...commonMetadataTargets(version),
    [contract.currentMac.arm64]: `Logseq-darwin-arm64-${version}.zip`,
    [contract.currentMac.x64]: `Logseq-darwin-x64-${version}.zip`,
  };
};

const expectedArtifactNames = (version) => {
  const contract = versionContracts.get(version);
  const metadataNames = Object.keys(metadataTargets(version));
  if (contract.includeLegacySentinels) {
    metadataNames.push("latest-arm64-mac.yml", "latest-x64-mac.yml");
  }
  return [...payloadNames(version), ...metadataNames, "VERSION"].sort();
};

const sha = (algorithm, payload) =>
  crypto.createHash(algorithm).update(payload).digest(
    algorithm === "sha512" ? "base64" : "hex",
  );

const escapeRegExp = (value) => value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

const writeUpdateMetadata = (root, name, version, payloadName) => {
  const payload = fs.readFileSync(path.join(root, payloadName));
  fs.writeFileSync(
    path.join(root, name),
    [
      `version: ${version}`,
      "files:",
      `  - url: ${encodeURIComponent(payloadName)}`,
      `    sha512: ${sha("sha512", payload)}`,
      `    size: ${payload.length}`,
      "",
    ].join("\n"),
  );
};

const createFixture = (version) => {
  const root = fs.mkdtempSync(
    path.join(os.tmpdir(), "logseq-release-assets-nightly-"),
  );
  for (const name of payloadNames(version)) {
    fs.writeFileSync(path.join(root, name), `fixture payload for ${name}\n`);
  }
  fs.writeFileSync(path.join(root, "VERSION"), `${version}\n`);
  for (const [name, payloadName] of Object.entries(metadataTargets(version))) {
    writeUpdateMetadata(root, name, version, payloadName);
  }

  const contract = versionContracts.get(version);
  if (contract.includeLegacySentinels) {
    for (const arch of ["arm64", "x64"]) {
      const name = `latest-${arch}-mac.yml`;
      fs.copyFileSync(
        path.join(
          repositoryRoot,
          "resources",
          "updater",
          "legacy-macos",
          name,
        ),
        path.join(root, name),
      );
    }
  }

  return {
    dispose: () => fs.rmSync(root, { force: true, recursive: true }),
    root,
    version,
  };
};

const runVerifier = (fixture, ...extraArgs) => {
  const result = spawnSync(
    process.execPath,
    [
      verifierPath,
      "--dir",
      fixture.root,
      "--version",
      fixture.version,
      ...extraArgs,
    ],
    { encoding: "utf8" },
  );
  if (result.error) throw result.error;
  return result;
};

const assertFailsClosed = (result, ...patterns) => {
  assert.notEqual(result.status, 0);
  assert.equal(result.stdout, "");
  for (const pattern of patterns) assert.match(result.stderr, pattern);
};

const checksumEntries = (root, names) =>
  names.map((name) => {
    const payload = fs.readFileSync(path.join(root, name));
    return `${sha("sha256", payload)}  ${name}`;
  });

const writeChecksums = (
  fixture,
  names = expectedArtifactNames(fixture.version),
) => {
  fs.writeFileSync(
    path.join(fixture.root, "SHA256SUMS.txt"),
    `${checksumEntries(fixture.root, names).join("\n")}\n`,
  );
};

const parseChecksumNames = (fixture) =>
  fs
    .readFileSync(path.join(fixture.root, "SHA256SUMS.txt"), "utf8")
    .trim()
    .split("\n")
    .map((line) => line.match(/^[0-9a-f]{64} {2}(.+)$/)?.[1])
    .sort();

test("Android-disabled release accepts the desktop-only artifact set", () => {
  const fixture = createFixture(stableVersion);
  try {
    const result = runVerifier(
      fixture,
      "--android-enabled",
      "false",
      "--write-checksums",
    );
    assert.equal(result.status, 0, result.stderr);
    assert.deepEqual(
      parseChecksumNames(fixture),
      expectedArtifactNames(stableVersion),
    );
  } finally {
    fixture.dispose();
  }
});

test("Android-enabled release fails closed when its versioned APK is absent", () => {
  const fixture = createFixture(stableVersion);
  try {
    const result = runVerifier(fixture, "--android-enabled", "true");
    assertFailsClosed(
      result,
      /release artifact set mismatch/,
      /Logseq-android-2\.0\.1-selfhost\.5\.apk/,
    );
  } finally {
    fixture.dispose();
  }
});

test("Android-enabled release requires and checksums the exact versioned APK", () => {
  const fixture = createFixture(stableVersion);
  const apkName = `Logseq-android-${stableVersion}.apk`;
  try {
    fs.writeFileSync(path.join(fixture.root, apkName), "signed APK fixture\n");
    const result = runVerifier(
      fixture,
      "--android-enabled",
      "true",
      "--write-checksums",
    );
    assert.equal(result.status, 0, result.stderr);
    assert.deepEqual(
      parseChecksumNames(fixture),
      [...expectedArtifactNames(stableVersion), apkName].sort(),
    );
  } finally {
    fixture.dispose();
  }
});

test("Android-disabled release rejects an unexpected APK", () => {
  const fixture = createFixture(stableVersion);
  try {
    fs.writeFileSync(
      path.join(fixture.root, `Logseq-android-${stableVersion}.apk`),
      "unexpected APK fixture\n",
    );
    const result = runVerifier(fixture, "--android-enabled", "false");
    assertFailsClosed(result, /release artifact set mismatch/, /unexpected=/);
  } finally {
    fixture.dispose();
  }
});

test("nightly preflight recognizes both architecture-specific selfhost macOS metadata files", () => {
  const fixture = createFixture(nightlyVersion);
  try {
    const result = runVerifier(fixture, "--write-checksums");
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /OK version=2\.0\.1-selfhost\.5\.nightly\.20260804/);
    assert.deepEqual(
      parseChecksumNames(fixture),
      expectedArtifactNames(nightlyVersion),
    );
  } finally {
    fixture.dispose();
  }
});

for (const arch of ["arm64", "x64"]) {
  test(`nightly preflight reports only the missing ${arch} macOS metadata`, () => {
    const fixture = createFixture(nightlyVersion);
    const missing = versionContracts.get(nightlyVersion).currentMac[arch];
    try {
      fs.unlinkSync(path.join(fixture.root, missing));
      const result = runVerifier(fixture);
      assertFailsClosed(
        result,
        /release artifact set mismatch/,
        new RegExp(
          `missing=\\[${escapeRegExp(JSON.stringify(missing))}\\] unexpected=\\[\\]`,
        ),
      );
    } finally {
      fixture.dispose();
    }
  });
}

test("a filename masquerading as arm64 nightly metadata cannot satisfy the managed slot", () => {
  const fixture = createFixture(nightlyVersion);
  const correct = versionContracts.get(nightlyVersion).currentMac.arm64;
  try {
    fs.renameSync(
      path.join(fixture.root, correct),
      path.join(fixture.root, `${correct}.bak`),
    );
    const result = runVerifier(fixture);
    assertFailsClosed(
      result,
      /release artifact set mismatch/,
      new RegExp(
        `missing=\\[${escapeRegExp(JSON.stringify(correct))}\\]`,
      ),
    );
    assert.doesNotMatch(result.stderr, /nightly-x64-mac\.yml"/);
  } finally {
    fixture.dispose();
  }
});

test("nightly preflight rejects an extra managed stable-channel macOS metadata file", () => {
  const fixture = createFixture(nightlyVersion);
  const extra = versionContracts.get(stableVersion).currentMac.arm64;
  try {
    fs.writeFileSync(path.join(fixture.root, extra), "managed decoy\n");
    const result = runVerifier(fixture);
    assertFailsClosed(
      result,
      /release artifact set mismatch/,
      new RegExp(
        `missing=\\[\\] unexpected=\\[${escapeRegExp(JSON.stringify(extra))}\\]`,
      ),
    );
  } finally {
    fixture.dispose();
  }
});

test("nightly checksum manifest must include the exact managed artifact set", () => {
  const fixture = createFixture(nightlyVersion);
  const omitted = versionContracts.get(nightlyVersion).currentMac.x64;
  try {
    writeChecksums(
      fixture,
      expectedArtifactNames(nightlyVersion).filter((name) => name !== omitted),
    );
    const result = runVerifier(fixture);
    assertFailsClosed(
      result,
      /SHA256SUMS\.txt does not cover the exact release artifact set/,
    );
  } finally {
    fixture.dispose();
  }
});

test("nightly checksum manifest rejects files outside the exact managed artifact set", () => {
  const fixture = createFixture(nightlyVersion);
  const extra = "release-notes.txt";
  try {
    fs.writeFileSync(path.join(fixture.root, extra), "unmanaged checksum decoy\n");
    writeChecksums(fixture, [
      ...expectedArtifactNames(nightlyVersion),
      extra,
    ]);
    const result = runVerifier(fixture);
    assertFailsClosed(
      result,
      /SHA256SUMS\.txt does not cover the exact release artifact set/,
    );
  } finally {
    fixture.dispose();
  }
});

for (const [label, version] of [
  ["stable signed channel", stableVersion],
  ["legacy upstream-style architecture channels", legacyVersion],
]) {
  test(`${label} behavior remains accepted with an exact checksum set`, () => {
    const fixture = createFixture(version);
    try {
      const result = runVerifier(fixture, "--write-checksums");
      assert.equal(result.status, 0, result.stderr);
      assert.deepEqual(parseChecksumNames(fixture), expectedArtifactNames(version));
    } finally {
      fixture.dispose();
    }
  });
}

test("generic upstream-style macOS metadata cannot replace a legacy architecture slot", () => {
  const fixture = createFixture(legacyVersion);
  const required = "latest-arm64-mac.yml";
  const disguised = "latest-mac.yml";
  try {
    fs.renameSync(
      path.join(fixture.root, required),
      path.join(fixture.root, disguised),
    );
    const result = runVerifier(fixture);
    assertFailsClosed(
      result,
      /release artifact set mismatch/,
      /missing=\["latest-arm64-mac\.yml"\]/,
      /unexpected=\["latest-mac\.yml"\]/,
    );
  } finally {
    fixture.dispose();
  }
});
