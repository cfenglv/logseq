const selfhostVersionPattern =
  /^(\d+\.\d+\.\d+-selfhost\.)([1-9]\d*)(?:-alpha\.nightly\.(\d{8}))?$/;

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
