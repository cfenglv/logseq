import fs from "node:fs";
import path from "node:path";

export const selfhostSourceRevisionName = "SOURCE_REVISION";

export const assertSourceRevision = (value) => {
  if (!/^[0-9a-f]{40}$/.test(value ?? "")) {
    throw new Error("source revision must be an exact lowercase 40-hex commit SHA");
  }
  return value;
};

const sourceRevisionPath = (dir) =>
  path.join(dir, selfhostSourceRevisionName);

export const assertSourceRevisionAbsent = (dir) => {
  if (fs.existsSync(sourceRevisionPath(dir))) {
    throw new Error("release candidates already contain SOURCE_REVISION");
  }
};

export const writeSourceRevision = ({ dir, sourceRevision }) => {
  const normalized = assertSourceRevision(sourceRevision);
  const destination = sourceRevisionPath(dir);
  const temporary = path.join(
    dir,
    `.${selfhostSourceRevisionName}.${process.pid}.tmp`,
  );
  assertSourceRevisionAbsent(dir);
  try {
    fs.writeFileSync(temporary, `${normalized}\n`, {
      flag: "wx",
      mode: 0o644,
    });
    fs.renameSync(temporary, destination);
  } finally {
    fs.rmSync(temporary, { force: true });
  }
};

export const removeSourceRevision = (dir) => {
  fs.rmSync(sourceRevisionPath(dir), { force: true });
};

export const verifySourceRevision = ({ dir, sourceRevision }) => {
  const normalized = assertSourceRevision(sourceRevision);
  const actual = fs.readFileSync(sourceRevisionPath(dir), "utf8");
  if (actual !== `${normalized}\n`) {
    throw new Error(
      "SOURCE_REVISION does not equal the exact rehearsed source SHA",
    );
  }
  return normalized;
};
