#!/usr/bin/env node

import { randomBytes } from "node:crypto";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const identityName = "Logseq Selfhost Local Code Signing";
export const codesignDir =
  process.env.LOGSEQ_LOCAL_CODESIGN_DIR ||
  path.join(os.homedir(), ".config", "logseq-selfhost", "codesign");
export const certificatePath = path.join(codesignDir, "certificate.pem");
export const identityPath = path.join(codesignDir, "identity.p12");
export const identityFormatPath = path.join(codesignDir, "identity-format");
export const identityPasswordPath = path.join(
  codesignDir,
  "identity-password",
);
export const keychainPasswordPath = path.join(
  codesignDir,
  "keychain-password",
);
export const keychainName = "logseq-selfhost.keychain";
export const keychainPath = path.join(
  os.homedir(),
  "Library",
  "Keychains",
  `${keychainName}-db`,
);

const identityFormat = "pkcs12-legacy-v1";

const run = (command, args, { capture = false } = {}) => {
  const result = spawnSync(command, args, {
    encoding: "utf8",
    stdio: capture ? ["ignore", "pipe", "pipe"] : "inherit",
  });

  if (result.error) {
    throw result.error;
  }
  if (result.status !== 0) {
    const detail = capture
      ? `\n${result.stderr || result.stdout || ""}`.trimEnd()
      : "";
    throw new Error(`${command} failed with exit code ${result.status}${detail}`);
  }

  return result.stdout || "";
};

const readSecret = (file) => fs.readFileSync(file, "utf8").trim();

const ensureSecretFile = (file) => {
  if (!fs.existsSync(file)) {
    fs.writeFileSync(file, `${randomBytes(32).toString("hex")}\n`, {
      mode: 0o600,
    });
  }
  fs.chmodSync(file, 0o600);
};

const createIdentityFiles = () => {
  const privateKeyPath = path.join(codesignDir, "private-key.pem");
  const identityPassword = readSecret(identityPasswordPath);

  run("openssl", [
    "req",
    "-new",
    "-newkey",
    "rsa:3072",
    "-x509",
    "-sha256",
    "-days",
    "3650",
    "-nodes",
    "-subj",
    `/CN=${identityName}/O=Logseq Selfhost/OU=Local Development`,
    "-addext",
    "basicConstraints=critical,CA:TRUE",
    "-addext",
    "keyUsage=critical,digitalSignature,keyCertSign",
    "-addext",
    "extendedKeyUsage=critical,codeSigning",
    "-addext",
    "subjectKeyIdentifier=hash",
    "-keyout",
    privateKeyPath,
    "-out",
    certificatePath,
  ]);

  try {
    run("openssl", [
      "pkcs12",
      "-export",
      "-out",
      identityPath,
      "-inkey",
      privateKeyPath,
      "-in",
      certificatePath,
      "-name",
      identityName,
      "-legacy",
      "-passout",
      `pass:${identityPassword}`,
    ]);
  } finally {
    fs.rmSync(privateKeyPath, { force: true });
  }

  fs.chmodSync(identityPath, 0o600);
  fs.chmodSync(certificatePath, 0o600);
  fs.writeFileSync(identityFormatPath, `${identityFormat}\n`, { mode: 0o600 });
};

const addKeychainToSearchList = () => {
  const output = run("security", ["list-keychains", "-d", "user"], {
    capture: true,
  });
  const keychains = [...output.matchAll(/"([^"]+)"/g)].map((match) => match[1]);

  if (!keychains.includes(keychainPath)) {
    run("security", [
      "list-keychains",
      "-d",
      "user",
      "-s",
      ...keychains,
      keychainPath,
    ]);
  }
};

const findCodesignIdentities = () =>
  run(
    "security",
    ["find-identity", "-v", "-p", "codesigning", keychainPath],
    { capture: true },
  );

const hasValidIdentity = (identities) =>
  !identities.includes("0 valid identities found") &&
  identities.includes(`\"${identityName}\"`);

export const setupLocalMacCodesign = () => {
  if (process.platform !== "darwin") {
    throw new Error("Local macOS code signing setup can only run on macOS");
  }

  fs.mkdirSync(codesignDir, { recursive: true, mode: 0o700 });
  fs.chmodSync(codesignDir, 0o700);
  ensureSecretFile(identityPasswordPath);
  ensureSecretFile(keychainPasswordPath);

  const installedIdentityFormat = fs.existsSync(identityFormatPath)
    ? fs.readFileSync(identityFormatPath, "utf8").trim()
    : null;
  if (
    !fs.existsSync(identityPath) ||
    !fs.existsSync(certificatePath) ||
    installedIdentityFormat !== identityFormat
  ) {
    createIdentityFiles();
  }

  const keychainPassword = readSecret(keychainPasswordPath);
  const identityPassword = readSecret(identityPasswordPath);

  if (!fs.existsSync(keychainPath)) {
    run("security", [
      "create-keychain",
      "-p",
      keychainPassword,
      keychainName,
    ]);
  }

  run("security", ["unlock-keychain", "-p", keychainPassword, keychainPath]);
  run("security", ["set-keychain-settings", "-lut", "21600", keychainPath]);

  const certificateInstalled = spawnSync(
    "security",
    ["find-certificate", "-c", identityName, keychainPath],
    { stdio: "ignore" },
  ).status === 0;

  if (!certificateInstalled) {
    run("security", [
      "import",
      identityPath,
      "-k",
      keychainPath,
      "-P",
      identityPassword,
      "-T",
      "/usr/bin/codesign",
      "-T",
      "/usr/bin/security",
    ]);
  }

  run(
    "security",
    [
      "set-key-partition-list",
      "-S",
      "apple-tool:,apple:,codesign:",
      "-s",
      "-k",
      keychainPassword,
      keychainPath,
    ],
    { capture: true },
  );
  addKeychainToSearchList();

  let identities = findCodesignIdentities();
  if (!hasValidIdentity(identities)) {
    run("security", [
      "add-trusted-cert",
      "-d",
      "-r",
      "trustRoot",
      "-p",
      "codeSign",
      "-k",
      keychainPath,
      certificatePath,
    ]);
    identities = findCodesignIdentities();
  }

  if (!hasValidIdentity(identities)) {
    throw new Error(
      `The fixed code-signing identity is not valid in ${keychainPath}`,
    );
  }

  return {
    codesignDir,
    identityName,
    keychainPassword,
    keychainPath,
  };
};

const isEntrypoint =
  process.argv[1] &&
  path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);

if (isEntrypoint) {
  try {
    const config = setupLocalMacCodesign();
    console.log(`Fixed signing identity ready: ${config.identityName}`);
    console.log(`Private signing material: ${config.codesignDir}`);
    console.log(`Dedicated keychain: ${config.keychainPath}`);
  } catch (error) {
    console.error(error instanceof Error ? error.message : error);
    process.exitCode = 1;
  }
}
