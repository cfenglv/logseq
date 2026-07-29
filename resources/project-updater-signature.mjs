import { createHash } from "node:crypto";
import fs from "node:fs";

export const projectUpdateAlgorithm = "ed25519-sha512-manifest-v1";
export const projectUpdateBundleIdentifier = "com.logseq.logseq";
export const projectUpdatePayload = ({ arch, sha512, size, version }) =>
  [
    "logseq-selfhost-macos-update-v1",
    `bundle-id=${projectUpdateBundleIdentifier}`,
    `version=${version}`,
    `arch=${arch}`,
    `zip-size=${size}`,
    `zip-sha512=${sha512}`,
    "",
  ].join("\n");
export const projectUpdateKeyId = (rawPublicKey) =>
  `ed25519:${createHash("sha256")
    .update(rawPublicKey)
    .digest("hex")
    .slice(0, 16)}`;

const policyPath = new URL(
  "./updater/project-signing-policy.json",
  import.meta.url,
);

export const loadProjectSigningPolicy = () => {
  const policy = JSON.parse(fs.readFileSync(policyPath, "utf8"));
  const publicKey = Buffer.from(policy.publicKeyBase64, "base64");
  const derivedKeyId = projectUpdateKeyId(publicKey);
  if (
    policy.algorithm !== projectUpdateAlgorithm ||
    policy.bundleIdentifier !== projectUpdateBundleIdentifier ||
    policy.payloadDomain !== "logseq-selfhost-macos-update-v1" ||
    policy.minimumBootstrapRevision !== 5 ||
    publicKey.length !== 32 ||
    publicKey.toString("base64") !== policy.publicKeyBase64 ||
    derivedKeyId !== policy.keyId
  ) {
    throw new Error(
      "project update signing policy is UNCONFIGURED or invalid; updater is fail-closed",
    );
  }
  return Object.freeze({ ...policy });
};

const parseSelfhostVersion = (version) => {
  const match =
    /^([0-9]+)\.([0-9]+)\.([0-9]+)-selfhost\.([1-9][0-9]*)$/.exec(
      version || "",
    );
  if (!match) throw new Error(`unsupported selfhost version ${version}`);
  return match.slice(1).map(Number);
};

const compareVersions = (left, right) => {
  for (let index = 0; index < left.length; index += 1) {
    if (left[index] !== right[index]) return left[index] - right[index];
  }
  return 0;
};

export const projectSignedMacosUpdater = (
  version,
  platform = process.platform,
) => {
  if (platform !== "darwin") return false;
  const parsed = parseSelfhostVersion(version);
  return parsed[3] >= 5;
};

export const validateProjectUpdateSignature = ({
  arch,
  currentVersion,
  updateInfo,
}) => {
  if (!["arm64", "x64"].includes(arch)) {
    throw new Error(`unsupported macOS updater architecture ${arch}`);
  }
  if (!projectSignedMacosUpdater(currentVersion, "darwin")) {
    throw new Error("current App is not in the project-signed updater chain");
  }
  const policy = loadProjectSigningPolicy();
  const candidateVersion = updateInfo?.version;
  if (
    compareVersions(
      parseSelfhostVersion(candidateVersion),
      parseSelfhostVersion(currentVersion),
    ) <= 0
  ) {
    throw new Error("project updater refuses downgrade or same-version update");
  }
  const signature = updateInfo?.projectUpdateSignature;
  if (!signature || typeof signature !== "object") {
    throw new Error("update metadata has no projectUpdateSignature");
  }
  const size = String(signature.zipSize ?? "");
  const encodedSignature = String(signature.signature ?? "");
  if (
    signature.algorithm !== policy.algorithm ||
    signature.keyId !== policy.keyId ||
    signature.bundleId !== policy.bundleIdentifier ||
    signature.version !== candidateVersion ||
    signature.arch !== arch ||
    !/^[1-9][0-9]*$/.test(size) ||
    !/^[0-9a-f]{128}$/.test(signature.zipSha512 || "") ||
    Buffer.from(encodedSignature, "base64").length !== 64 ||
    Buffer.from(encodedSignature, "base64").toString("base64") !==
      encodedSignature
  ) {
    throw new Error("project update signature metadata is invalid or mismatched");
  }
  return Object.freeze({
    algorithm: signature.algorithm,
    arch,
    bundleId: signature.bundleId,
    keyId: signature.keyId,
    sha512: signature.zipSha512,
    signature: encodedSignature,
    size,
    version: candidateVersion,
  });
};
