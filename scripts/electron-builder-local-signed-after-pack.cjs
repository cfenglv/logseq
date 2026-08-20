const { spawnSync } = require("node:child_process");

// macOS 26 can attach com.apple.provenance to newly created files and still
// allows them to be signed. codesign rejects FinderInfo and ResourceFork, so
// only those attributes should block the build.
const forbiddenMetadata = /com\.apple\.(?:FinderInfo|ResourceFork):/;

const runXattr = (args, { capture = false } = {}) => {
  const result = spawnSync("xattr", args, {
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
    throw new Error(`xattr failed with exit code ${result.status}${detail}`);
  }

  return result.stdout || "";
};

module.exports = async ({ electronPlatformName, appOutDir }) => {
  if (electronPlatformName !== "darwin") {
    return;
  }

  // Do not use `xattr -cr` here. macOS 26 protects com.apple.provenance, so a
  // blanket clear can fail even though that attribute is accepted by codesign.
  runXattr(["-dr", "com.apple.FinderInfo", appOutDir]);
  runXattr(["-dr", "com.apple.ResourceFork", appOutDir]);
  const metadata = runXattr(["-lr", appOutDir], { capture: true });
  const remaining = metadata
    .split("\n")
    .filter((line) => forbiddenMetadata.test(line))
    .slice(0, 5);

  if (remaining.length > 0) {
    throw new Error(
      [
        "macOS signing metadata could not be removed before codesign.",
        "Use an output directory outside the project, Documents, and synced folders.",
        "The default is ~/Library/Caches/logseq-selfhost-build/dist; override it with LOGSEQ_LOCAL_SIGNED_OUTPUT_DIR only if the replacement directory is clean.",
        ...remaining,
      ].join("\n"),
    );
  }
};
