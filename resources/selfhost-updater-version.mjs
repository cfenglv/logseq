import path from "node:path";
import { fileURLToPath } from "node:url";

const selfhostVersionPattern =
  /^(\d+\.\d+\.\d+-selfhost\.)([1-9]\d*)(?:-alpha\.nightly\.(\d{8}))?$/;
const selfhostRevisionPattern =
  /^\d+\.\d+\.\d+-selfhost\.([1-9]\d*)(?:-|$)/;
const supportedMacosArchitectures = new Set(["x64", "arm64"]);
// selfhost.4 was ad-hoc signed, so its designated requirement is its exact
// cdhash. Keep that legacy channel frozen and start a new signed trust chain
// after users manually install selfhost.5.
const signedMacosUpdaterChannelRevision = 5;

const validNightlyDate = (value) => {
  if (value === undefined) return true;
  const year = Number(value.slice(0, 4));
  const month = Number(value.slice(4, 6));
  const day = Number(value.slice(6, 8));
  const date = new Date(Date.UTC(year, month - 1, day));
  return (
    date.getUTCFullYear() === year &&
    date.getUTCMonth() === month - 1 &&
    date.getUTCDate() === day
  );
};

export const resolveSelfhostUpdaterVersions = (packageVersion) => {
  const match = packageVersion.match(selfhostVersionPattern);
  if (!match || !validNightlyDate(match[3])) {
    throw new Error(
      `selfhost updater rehearsal requires a numbered selfhost version or its dated nightly rehearsal, got ${packageVersion}`,
    );
  }

  const currentRevision = Number(match[2]);
  const currentVersion = `${match[1]}${currentRevision}`;
  if (currentRevision < 4) {
    throw new Error(
      `automatic updater bootstrap starts at selfhost.4, got ${currentVersion}`,
    );
  }

  return {
    currentRevision,
    currentVersion,
    isNightlyRehearsal: match[3] !== undefined,
    nextVersion: `${match[1]}${currentRevision + 1}`,
  };
};

export const macosUpdaterChannel = (packageVersion, arch) => {
  if (!supportedMacosArchitectures.has(arch)) {
    throw new Error(`unsupported macOS updater architecture: ${arch}`);
  }

  const selfhostRevision = Number(
    packageVersion.match(selfhostRevisionPattern)?.[1],
  );
  if (selfhostRevision >= signedMacosUpdaterChannelRevision) {
    return `selfhost-macos-v2-${arch}`;
  }
  return `latest-${arch}`;
};

export const macosUpdaterMetadataName = (packageVersion, arch) =>
  `${macosUpdaterChannel(packageVersion, arch)}-mac.yml`;

const isEntrypoint =
  process.argv[1] &&
  path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);

if (isEntrypoint) {
  const [command, packageVersion, arch] = process.argv.slice(2);
  if (command !== "macos-metadata-name" || !packageVersion || !arch) {
    throw new Error(
      "usage: selfhost-updater-version.mjs macos-metadata-name <version> <x64|arm64>",
    );
  }
  console.log(macosUpdaterMetadataName(packageVersion, arch));
}
