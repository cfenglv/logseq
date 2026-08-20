import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";

export const defaultSessionFile = "/private/tmp/selfhost6-phase7-session.json";

const fullSha = /^[0-9a-f]{40}$/;

export function readQualificationSession(sessionFile = defaultSessionFile) {
  const resolvedSessionFile = path.resolve(sessionFile);
  const session = JSON.parse(fs.readFileSync(resolvedSessionFile, "utf8"));
  assert.equal(session.kind, "selfhost6.phase7.qualification-session.v1");
  for (const key of ["qualificationRoot", "testHome", "userData", "config",
    "appExecutable", "sourceFullSha", "debugPort", "graph", "stateFile"]) {
    assert.ok(session[key] !== undefined && session[key] !== null && session[key] !== "",
      `qualification session missing ${key}`);
  }
  assert.match(session.sourceFullSha, fullSha, "qualification session source must be a full Git SHA");
  assert.ok(Number.isSafeInteger(session.debugPort), "qualification session debugPort must be an integer");
  return {
    ...session,
    sessionFile: resolvedSessionFile,
    qualificationRoot: path.resolve(session.qualificationRoot),
    testHome: path.resolve(session.testHome),
    userData: path.resolve(session.userData),
    config: path.resolve(session.config),
    appExecutable: path.resolve(session.appExecutable),
    stateFile: path.resolve(session.stateFile),
  };
}
