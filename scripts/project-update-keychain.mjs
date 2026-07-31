import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { loadProjectUpdatePrivateKey } from "./project-update-private-key.mjs";

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
    return loadProjectUpdatePrivateKey({
      allowSurroundingWhitespace: true,
      encodedKey: lookup.stdout,
      policy,
      sourceLabel: "login Keychain project update key",
    });
  } finally {
    lookup.stdout.fill(0);
  }
};
