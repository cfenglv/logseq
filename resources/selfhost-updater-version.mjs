import path from "node:path";
import { fileURLToPath } from "node:url";

const selfhostVersionPattern =
  /^(\d+\.\d+\.\d+-selfhost\.)([1-9]\d*)(?:\.nightly\.(\d{8}))?$/;
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
  const leap = year % 400 === 0 || (year % 4 === 0 && year % 100 !== 0);
  const days = [
    0,
    31,
    leap ? 29 : 28,
    31,
    30,
    31,
    30,
    31,
    31,
    30,
    31,
    30,
    31,
  ];
  return year >= 1 && month >= 1 && month <= 12 && day >= 1 && day <= days[month];
};

export const resolveSelfhostUpdaterVersions = (packageVersion) => {
  const match = packageVersion.match(selfhostVersionPattern);
  if (!match || !validNightlyDate(match[3])) {
    throw new Error(
      `selfhost updater rehearsal requires a numbered selfhost version or its dated nightly rehearsal, got ${packageVersion}`,
    );
  }

  const currentRevision = Number(match[2]);
  if (!Number.isSafeInteger(currentRevision)) {
    throw new Error(`invalid selfhost updater revision: ${match[2]}`);
  }
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

export const selfhostUpdaterRevision = (packageVersion) => {
  const match = packageVersion.match(selfhostVersionPattern);
  if (!match || !validNightlyDate(match[3])) {
    throw new Error(`invalid selfhost updater version: ${packageVersion}`);
  }
  const revision = Number(match[2]);
  if (!Number.isSafeInteger(revision)) {
    throw new Error(`invalid selfhost updater version: ${packageVersion}`);
  }
  return revision;
};

export const macosUpdaterChannel = (packageVersion, arch) => {
  if (!supportedMacosArchitectures.has(arch)) {
    throw new Error(`unsupported macOS updater architecture: ${arch}`);
  }

  const selfhostRevision = selfhostUpdaterRevision(packageVersion);
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
  if (command === "macos-metadata-name" && packageVersion && arch) {
    console.log(macosUpdaterMetadataName(packageVersion, arch));
  } else if (
    command === "selfhost-revision" &&
    packageVersion &&
    arch === undefined
  ) {
    console.log(selfhostUpdaterRevision(packageVersion));
  } else {
    throw new Error(
      "usage: selfhost-updater-version.mjs <macos-metadata-name <version> <x64|arm64>|selfhost-revision <version>>",
    );
  }
}
