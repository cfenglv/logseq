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
export const projectUpdateKeyId = (rawPublicKey) => {
  if (!rawPublicKey || rawPublicKey.length !== 32) {
    throw new Error("project update public key must be 32 raw Ed25519 bytes");
  }
  return `ed25519:${createHash("sha256").update(rawPublicKey).digest("hex")}`;
};

const policyPath = new URL(
  "./updater/project-signing-policy.json",
  import.meta.url,
);

export const loadProjectSigningPolicy = () => {
  const policy = JSON.parse(fs.readFileSync(policyPath, "utf8"));
  const publicKey = Buffer.from(policy.publicKeyBase64, "base64");
  const derivedKeyId = publicKey.length === 32 ? projectUpdateKeyId(publicKey) : undefined;
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

const validNightlyDate = (value) => {
  if (value === undefined) return true;
  const year = Number(value.slice(0, 4));
  const month = Number(value.slice(4, 6));
  const day = Number(value.slice(6, 8));
  const leap = year % 400 === 0 || (year % 4 === 0 && year % 100 !== 0);
  const days = [0, 31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  return year >= 1 && month >= 1 && month <= 12 && day >= 1 && day <= days[month];
};

export const parseSelfhostProjectVersion = (version) => {
  const match =
    /^([0-9]+)\.([0-9]+)\.([0-9]+)-selfhost\.([1-9][0-9]*)(?:\.nightly\.([0-9]{8}))?$/.exec(
      version || "",
    );
  if (!match || !validNightlyDate(match[5])) {
    throw new Error(`unsupported selfhost version ${version}`);
  }
  const components = match.slice(1, 5).map(Number);
  if (!components.every(Number.isSafeInteger)) {
    throw new Error(`unsupported selfhost version ${version}`);
  }
  return Object.freeze({
    components,
    nightlyDate: match[5] === undefined ? undefined : Number(match[5]),
  });
};

export const compareSelfhostProjectVersions = (leftVersion, rightVersion) => {
  const left = parseSelfhostProjectVersion(leftVersion);
  const right = parseSelfhostProjectVersion(rightVersion);
  for (let index = 0; index < left.components.length; index += 1) {
    if (left.components[index] !== right.components[index]) {
      return left.components[index] - right.components[index];
    }
  }
  if (left.nightlyDate === right.nightlyDate) return 0;
  // This mirrors SemVer precedence for
  // `...-selfhost.N[.nightly.YYYYMMDD]`: a longer prerelease identifier set
  // sorts after its otherwise-identical prefix.
  if (left.nightlyDate === undefined) return -1;
  if (right.nightlyDate === undefined) return 1;
  return left.nightlyDate - right.nightlyDate;
};

export const projectSignedMacosUpdater = (
  version,
  platform = process.platform,
) => {
  if (platform !== "darwin") return false;
  const parsed = parseSelfhostProjectVersion(version);
  return parsed.components[3] >= 5;
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
  if (compareSelfhostProjectVersions(candidateVersion, currentVersion) <= 0) {
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
