import { parseSelfhostProjectVersion } from "../resources/project-updater-signature.mjs";
import { loadProjectUpdatePrivateKey } from "./project-update-private-key.mjs";

export const githubProjectUpdateSigningSecretName =
  "LOGSEQ_PROJECT_UPDATE_SIGNING_KEY_PKCS8_BASE64";

const requiredEnvironment = (name) => {
  const value = String(process.env[name] ?? "").trim();
  if (!value) {
    throw new Error(`GitHub release signing context is missing ${name}`);
  }
  return value;
};

export const assertGithubProjectUpdateSigningContext = ({ version }) => {
  const parsed = parseSelfhostProjectVersion(version);
  if (parsed.nightlyDate !== undefined) {
    throw new Error(
      "GitHub release signing requires a stable selfhost version",
    );
  }
  if (
    process.env.GITHUB_ACTIONS !== "true" ||
    process.env.GITHUB_EVENT_NAME !== "workflow_dispatch"
  ) {
    throw new Error(
      "GitHub release signing requires workflow_dispatch inside GitHub Actions",
    );
  }
  if (process.env.GITHUB_REPOSITORY !== "cfenglv/logseq") {
    throw new Error("GitHub release signing is restricted to cfenglv/logseq");
  }
  const buildTarget = requiredEnvironment("LOGSEQ_RELEASE_BUILD_TARGET");
  if (!new Set(["stable", "beta"]).has(buildTarget)) {
    throw new Error(
      "GitHub release signing requires the stable or beta target",
    );
  }
  const sourceRef = requiredEnvironment("LOGSEQ_RELEASE_SOURCE_REF");
  const workflowRefName = requiredEnvironment("GITHUB_REF_NAME");
  const workflowRef = requiredEnvironment("GITHUB_REF");
  if (
    sourceRef !== workflowRefName ||
    !new Set([
      `refs/heads/${sourceRef}`,
      `refs/tags/${sourceRef}`,
    ]).has(workflowRef)
  ) {
    throw new Error(
      "GitHub release signing requires the workflow ref to equal the source ref",
    );
  }
  const workflowSha = requiredEnvironment("GITHUB_SHA");
  const sourceSha = requiredEnvironment("LOGSEQ_RELEASE_SOURCE_SHA");
  if (
    !/^[0-9a-f]{40}$/i.test(workflowSha) ||
    workflowSha !== sourceSha
  ) {
    throw new Error(
      "GitHub release signing requires the workflow SHA to equal the resolved source SHA",
    );
  }
};

export const loadGithubProjectUpdateSigningKey = (policy) => {
  let encodedText = process.env[githubProjectUpdateSigningSecretName];
  delete process.env[githubProjectUpdateSigningSecretName];
  if (!encodedText) {
    throw new Error(
      "protected Environment project update signing key is missing",
    );
  }
  const encodedKey = Buffer.from(encodedText, "ascii");
  encodedText = "";
  try {
    return loadProjectUpdatePrivateKey({
      encodedKey,
      policy,
      sourceLabel: "protected Environment project update signing key",
    });
  } finally {
    encodedKey.fill(0);
  }
};
