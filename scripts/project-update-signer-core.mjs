import {
  createHash,
  createPublicKey,
  sign,
} from "node:crypto";
import fs from "node:fs";
import {
  parseSelfhostProjectVersion,
  projectUpdateAlgorithm,
  projectUpdateBundleIdentifier,
  projectUpdateKeyId,
  projectUpdatePayload,
} from "./project-update-signing.mjs";

const validatePrivateKey = (privateKey, policy) => {
  if (privateKey?.asymmetricKeyType !== "ed25519") {
    throw new Error("project update private key must be Ed25519");
  }
  const publicKey = createPublicKey(privateKey);
  const spki = publicKey.export({ format: "der", type: "spki" });
  const rawPublicKey = spki.subarray(-32);
  if (
    policy.algorithm !== projectUpdateAlgorithm ||
    rawPublicKey.toString("base64") !== policy.publicKeyBase64 ||
    projectUpdateKeyId(rawPublicKey) !== policy.keyId
  ) {
    throw new Error(
      "project update private key does not match the fixed public policy",
    );
  }
};

export const createProjectUpdateSignature = async ({
  arch,
  archive,
  metadata,
  policy,
  privateKey,
  version,
}) => {
  if (!["arm64", "x64"].includes(arch)) {
    throw new Error("unsupported architecture");
  }
  parseSelfhostProjectVersion(version);
  validatePrivateKey(privateKey, policy);

  const currentMetadata = fs.readFileSync(metadata, "utf8");
  if (/^projectUpdateSignature:/m.test(currentMetadata)) {
    throw new Error("metadata already contains a project update signature");
  }
  const archiveHash = createHash("sha512");
  let size = 0;
  for await (const chunk of fs.createReadStream(archive)) {
    size += chunk.length;
    archiveHash.update(chunk);
  }
  const sha512 = archiveHash.digest("hex");
  const payload = projectUpdatePayload({ arch, sha512, size, version });
  const signature = sign(
    null,
    Buffer.from(payload),
    privateKey,
  ).toString("base64");
  const block = [
    "projectUpdateSignature:",
    `  algorithm: ${projectUpdateAlgorithm}`,
    `  keyId: ${policy.keyId}`,
    `  bundleId: ${projectUpdateBundleIdentifier}`,
    `  version: ${version}`,
    `  arch: ${arch}`,
    `  zipSize: '${size}'`,
    `  zipSha512: ${sha512}`,
    `  signature: ${signature}`,
    "",
  ].join("\n");
  return Object.freeze({
    arch,
    keyId: policy.keyId,
    metadata: `${currentMetadata}${block}`,
    sha512,
    signature,
    size: String(size),
    version,
  });
};
