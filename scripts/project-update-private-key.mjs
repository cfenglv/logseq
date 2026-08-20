import {
  createPrivateKey,
  createPublicKey,
} from "node:crypto";
import { projectUpdateKeyId } from "./project-update-signing.mjs";

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

export const loadProjectUpdatePrivateKey = ({
  allowSurroundingWhitespace = false,
  encodedKey,
  policy,
  sourceLabel,
}) => {
  if (!Buffer.isBuffer(encodedKey) || encodedKey.length === 0) {
    throw new Error(`${sourceLabel} is missing`);
  }
  const trimmed = trimAsciiWhitespace(encodedKey);
  if (
    (!allowSurroundingWhitespace && trimmed.length !== encodedKey.length) ||
    trimmed.length === 0
  ) {
    throw new Error(`${sourceLabel} is not canonical base64`);
  }
  const encodedText = trimmed.toString("ascii");
  if (
    !/^[A-Za-z0-9+/]+={0,2}$/.test(encodedText) ||
    encodedText.length % 4 !== 0
  ) {
    throw new Error(`${sourceLabel} is not canonical base64`);
  }
  const der = Buffer.from(encodedText, "base64");
  try {
    if (der.toString("base64") !== encodedText) {
      throw new Error(`${sourceLabel} is not canonical base64`);
    }
    let privateKey;
    try {
      privateKey = createPrivateKey({
        key: der,
        format: "der",
        type: "pkcs8",
      });
    } catch {
      throw new Error(`${sourceLabel} is not valid PKCS#8 DER`);
    }
    if (privateKey.asymmetricKeyType !== "ed25519") {
      throw new Error(`${sourceLabel} is not Ed25519 PKCS#8 DER`);
    }
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
    der.fill(0);
  }
};
