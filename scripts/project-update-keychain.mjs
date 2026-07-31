import {
  createPrivateKey,
  createPublicKey,
} from "node:crypto";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { projectUpdateKeyId } from "./project-update-signing.mjs";

export const projectUpdateKeychainService =
  "com.logseq.selfhost.project-update-signing.ed25519-pkcs8-base64";

export const projectUpdateKeychainLookupFailure = ({
  error,
  status,
  stdoutLength,
}) => {
  if (!error && status === 44) {
    return new Error("Keychain signing key missing/not found");
  }
  if (error || status !== 0 || stdoutLength === 0) {
    return new Error("Keychain read failed/unavailable");
  }
  return null;
};

const activeCiEnvironment = () =>
  process.env.GITHUB_ACTIONS === "true" ||
  !["", "0", "false"].includes(
    String(process.env.CI ?? "").trim().toLowerCase(),
  );

export const assertLocalMacosProjectUpdatePublisher = () => {
  if (activeCiEnvironment()) {
    throw new Error(
      "project update signing is for the local macOS publisher only and refuses CI",
    );
  }
  if (process.platform !== "darwin") {
    throw new Error(
      "project update signing requires the local publisher's macOS login Keychain",
    );
  }
};

const trimAsciiWhitespace = (value) => {
  let start = 0;
  let end = value.length;
  while (
    start < end &&
    [0x09, 0x0a, 0x0d, 0x20].includes(value[start])
  ) {
    start += 1;
  }
  while (
    end > start &&
    [0x09, 0x0a, 0x0d, 0x20].includes(value[end - 1])
  ) {
    end -= 1;
  }
  return value.subarray(start, end);
};

const privateKeyFromBase64Bytes = (encodedKey) => {
  const trimmed = trimAsciiWhitespace(encodedKey);
  const encodedText = trimmed.toString("ascii");
  if (
    !/^[A-Za-z0-9+/]+={0,2}$/.test(encodedText) ||
    encodedText.length % 4 !== 0
  ) {
    throw new Error(
      "login Keychain project update key is not canonical base64",
    );
  }
  const der = Buffer.from(encodedText, "base64");
  try {
    if (der.toString("base64") !== encodedText) {
      throw new Error(
        "login Keychain project update key is not canonical base64",
      );
    }
    const privateKey = createPrivateKey({
      key: der,
      format: "der",
      type: "pkcs8",
    });
    if (privateKey.asymmetricKeyType !== "ed25519") {
      throw new Error(
        "login Keychain project update key is not Ed25519 PKCS#8 DER",
      );
    }
    return privateKey;
  } catch (error) {
    if (
      error instanceof Error &&
      error.message.startsWith("login Keychain")
    ) {
      throw error;
    }
    throw new Error(
      "login Keychain project update key is not valid PKCS#8 DER",
    );
  } finally {
    der.fill(0);
  }
};

export const loadProjectUpdateSigningKey = (policy) => {
  assertLocalMacosProjectUpdatePublisher();
  const loginKeychain = path.join(
    os.homedir(),
    "Library",
    "Keychains",
    "login.keychain-db",
  );
  if (!fs.existsSync(loginKeychain)) {
    throw new Error("the publisher login Keychain is unavailable");
  }
  const lookup = spawnSync(
    "/usr/bin/security",
    [
      "find-generic-password",
      "-s",
      projectUpdateKeychainService,
      "-a",
      policy.keyId,
      "-w",
      loginKeychain,
    ],
    {
      encoding: null,
      stdio: ["ignore", "pipe", "pipe"],
    },
  );
  const lookupFailure = projectUpdateKeychainLookupFailure({
    error: lookup.error,
    status: lookup.status,
    stdoutLength: lookup.stdout?.length ?? 0,
  });
  if (lookupFailure) {
    lookup.stdout?.fill(0);
    throw lookupFailure;
  }

  try {
    const privateKey = privateKeyFromBase64Bytes(lookup.stdout);
    const publicKey = createPublicKey(privateKey);
    const spki = publicKey.export({ format: "der", type: "spki" });
    const rawPublicKey = spki.subarray(-32);
    if (
      rawPublicKey.toString("base64") !== policy.publicKeyBase64 ||
      projectUpdateKeyId(rawPublicKey) !== policy.keyId
    ) {
      throw new Error(
        "private key does not match the fixed project update public key/policy",
      );
    }
    return privateKey;
  } finally {
    lookup.stdout.fill(0);
  }
};
