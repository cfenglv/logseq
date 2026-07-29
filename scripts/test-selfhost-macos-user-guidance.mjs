#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const read = (relativePath) =>
  fs.readFileSync(path.join(repoRoot, relativePath), "utf8");
const failures = [];

const check = (condition, message) => {
  if (!condition) failures.push(message);
};

const normalized = (text) => text.replace(/\s+/g, " ").trim();

const assertMacosGuidance = (label, source) => {
  const text = normalized(source);
  check(
    /2\.0\.1-selfhost\.4[\s\S]{0,900}2\.0\.1-selfhost\.5/i.test(text) &&
      /2\.0\.1-selfhost\.5[\s\S]{0,260}(?:manual(?:ly)?|手动)|(?:manual(?:ly)?|手动)[\s\S]{0,260}2\.0\.1-selfhost\.5/i.test(
        text,
      ),
    `${label} must say that macOS 2.0.1-selfhost.4 -> .5 is a manual migration`,
  );
  check(
    /2\.0\.1-selfhost\.5[\s\S]{0,360}(?:2\.0\.1-selfhost\.6|\.6\+|later|subsequent|future|后续|以后)/i.test(
      text,
    ) &&
      /(?:in[- ]app|inside the app|application UI|应用内)[\s\S]{0,180}(?:automatic(?:ally)?\s+install|install(?:ed|ation)?\s+automatically|默认自动安装|自动安装)|(?:automatic(?:ally)?\s+install|install(?:ed|ation)?\s+automatically|默认自动安装|自动安装)[\s\S]{0,180}(?:in[- ]app|inside the app|application UI|应用内)/i.test(
        text,
      ),
    `${label} must say that .5 -> .6+ defaults to in-application automatic installation`,
  );
  check(
    /(?:each|every|每个|每次)[\s\S]{0,120}(?:new|subsequent|新|后续)[\s\S]{0,120}ad[ -]?hoc|ad[ -]?hoc[\s\S]{0,120}(?:each|every|每个|每次)[\s\S]{0,120}(?:new|subsequent|新|后续)/i.test(
      text,
    ) &&
      /(?:first launch|first open|首次启动|首次打开)/i.test(text) &&
      /Open Anyway/i.test(text) &&
      /(?:may|might|can|可能|也许)[\s\S]{0,160}Open Anyway|Open Anyway[\s\S]{0,160}(?:may|might|can|可能|也许)/i.test(
        text,
      ),
    `${label} must warn that every new ad-hoc version may need Open Anyway on first launch`,
  );
  check(
    /(?:does not|will not|never|不(?:会|要)?)[\s\S]{0,100}(?:change|modify|write|add|alter|更改|修改)[\s\S]{0,100}Trust Settings|Trust Settings[\s\S]{0,100}(?:remain|unchanged|不变)/i.test(
      text,
    ),
    `${label} must state that the updater does not change Trust Settings`,
  );
  check(
    /(?:does not|will not|never|不(?:会|要)?)[\s\S]{0,100}(?:remove|clear|strip|delete|移除|清除|删除)[\s\S]{0,100}(?:quarantine|隔离)|(?:quarantine|隔离)[\s\S]{0,100}(?:preserved|retained|kept|保留)/i.test(
      text,
    ),
    `${label} must state that the updater does not remove quarantine`,
  );

  const openAnywaySentences = source
    .split(/(?<=[.!?。！？])\s+|\n+/)
    .filter((sentence) => /Open Anyway/i.test(sentence));
  check(
    openAnywaySentences.length > 0,
    `${label} must name the macOS Open Anyway action`,
  );
  for (const sentence of openAnywaySentences) {
    check(
      !/(?:only|just)\s+once|one[- ]time|仅(?:需|要)?一次|只(?:需|要)?.{0,20}一次|Open Anyway.{0,100}(?:only|just).{0,40}(?:\.5|selfhost\.5)|(?:\.5|selfhost\.5).{0,100}Open Anyway.{0,60}(?:only|just)/i.test(
        sentence,
      ),
      `${label} must not promise that Open Anyway is needed only once or only for .5: ${sentence.trim()}`,
    );
  }
};

const releaseNotes = read("docs/releases/2.0.1-selfhost.5.md");
const guide = read("docs/selfhost-sync.md");
const readme = read("README.md");
const guideUpdateSection =
  guide.match(
    /### Desktop application updates([\s\S]*?)(?=^##\s+10\.|^##\s+)/m,
  )?.[1] ?? "";
const readmeForkSection =
  readme.match(/## Self-hosted RTC fork([\s\S]*?)(?=^### Release verification)/m)
    ?.[1] ?? "";

assertMacosGuidance("2.0.1-selfhost.5 release notes", releaseNotes);
assertMacosGuidance("self-host guide desktop-update section", guideUpdateSection);
assertMacosGuidance("README self-host summary", readmeForkSection);

if (failures.length > 0) {
  for (const failure of failures) {
    console.error(`[selfhost-macos-guidance] FAIL ${failure}`);
  }
  console.error(
    `[selfhost-macos-guidance] SUMMARY passed=0 failed=${failures.length}`,
  );
  process.exitCode = 1;
} else {
  console.log(
    "[selfhost-macos-guidance] PASS migration, automatic install, recurring Gatekeeper, Trust Settings, and quarantine guidance",
  );
}
