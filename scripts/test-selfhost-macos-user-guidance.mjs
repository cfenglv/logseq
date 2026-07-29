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
      /(?:automatic(?:ally)?|默认|自动)[\s\S]{0,180}(?:check|检查)[\s\S]{0,180}(?:download|下载)|(?:check|检查)[\s\S]{0,180}(?:download|下载)[\s\S]{0,180}(?:automatic(?:ally)?|默认|自动)/i.test(
        text,
      ) &&
      /(?:click|select|choose|点击|选择)[\s\S]{0,100}(?:Restart and install|重启并安装)|(?:Restart and install|重启并安装)[\s\S]{0,100}(?:click|select|choose|点击|选择)/i.test(
        text,
      ),
    `${label} must say that .5 -> .6+ checks/downloads automatically and the user clicks Restart and install`,
  );
  check(
    /(?:each|every|每个|每次)[\s\S]{0,120}(?:new|subsequent|新|后续)[\s\S]{0,120}ad[ -]?hoc|ad[ -]?hoc[\s\S]{0,120}(?:each|every|每个|每次)[\s\S]{0,120}(?:new|subsequent|新|后续)/i.test(
      text,
    ) &&
      /(?:first launch|first open|首次启动|首次打开)/i.test(text) &&
      /Open Anyway/i.test(text) &&
      /(?:macOS|Mac)[\s\S]{0,220}Open Anyway|Open Anyway[\s\S]{0,220}(?:macOS|Mac)/i.test(
        text,
      ) &&
      /(?:may|might|can|可能|也许)[\s\S]{0,160}Open Anyway|Open Anyway[\s\S]{0,160}(?:may|might|can|可能|也许)/i.test(
        text,
      ),
    `${label} must warn that every new ad-hoc version may need Open Anyway on first launch`,
  );
  check(
    /(?:does not|will not|never|不(?:会|要)?)[\s\S]{0,100}(?:change|modify|write|add|alter|touch|更改|修改|写入)[\s\S]{0,100}Trust Settings|Trust Settings[\s\S]{0,100}(?:remain|unchanged|untouched|不变)/i.test(
      text,
    ),
    `${label} must state that the updater does not change Trust Settings`,
  );
  check(
    /(?:CI|GitHub Actions|runner)[\s\S]{0,240}(?:Developer ID|Apple)[\s\S]{0,180}(?:secret|ephemeral|temporary|临时|密钥)|(?:Developer ID|Apple)[\s\S]{0,240}(?:secret|ephemeral|temporary|临时|密钥)[\s\S]{0,180}(?:CI|GitHub Actions|runner)/i.test(
      text,
    ),
    `${label} must distinguish ephemeral CI Developer ID secret import from the local/client no-trust-mutation rule`,
  );
  check(
    /(?:does not|will not|never|不(?:会|要)?)[\s\S]{0,100}(?:remove|clear|strip|delete|移除|清除|删除)[\s\S]{0,100}(?:quarantine|隔离)|(?:quarantine|隔离)[\s\S]{0,100}(?:preserved|retained|kept|保留)/i.test(
      text,
    ),
    `${label} must state that the updater does not remove quarantine`,
  );
  check(
    /(?:stable|稳定版)[\s\S]{0,220}(?:never|does not|will not|不(?:会|能)?)[\s\S]{0,180}(?:automatic(?:ally)?|自动)[\s\S]{0,120}(?:enter|switch|update|upgrade|进入|切换|更新|升级)[\s\S]{0,100}(?:nightly|夜间版)|(?:stable|稳定版)[\s\S]{0,220}(?:nightly|夜间版)[\s\S]{0,180}(?:manual|手动)/i.test(
      text,
    ),
    `${label} must state that stable clients never automatically enter the nightly track`,
  );
  check(
    /(?:nightly|夜间版)[\s\S]{0,220}(?:only|仅|只)[\s\S]{0,100}(?:automatic(?:ally)?|自动)[\s\S]{0,160}(?:later|newer|subsequent|后续|更新的)[\s\S]{0,100}(?:dated[\s-]*)?(?:nightly|夜间版)/i.test(
      text,
    ),
    `${label} must limit nightly automatic updates to later dated nightlies`,
  );
  check(
    /(?:nightly|夜间版)[\s\S]{0,260}(?:any|every|任何|任意)[\s\S]{0,100}(?:stable|稳定版)[\s\S]{0,180}(?:manual(?:ly)?|手动)|(?:nightly|夜间版)[\s\S]{0,260}(?:stable|稳定版)[\s\S]{0,180}(?:manual(?:ly)?|手动)[\s\S]{0,180}(?:higher|later|newer|更高|后续)/i.test(
      text,
    ),
    `${label} must require manual installation for nightly to any stable revision, including a higher revision`,
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

  check(
    !/\bxattr\b[\s\S]{0,100}com\.apple\.quarantine|com\.apple\.quarantine[\s\S]{0,100}\bxattr\b/i.test(
      source,
    ),
    `${label} must not instruct users to remove quarantine with xattr`,
  );
  const certificateSentences = source
    .split(/(?<=[.!?。！？])\s+|\n+/)
    .filter(
      (sentence) =>
        /certificate|证书/i.test(sentence) &&
        /import|install|add|trust|导入|安装|添加|信任/i.test(sentence),
    );
  for (const sentence of certificateSentences) {
    const ephemeralCiDeveloperIdException =
      /(?:CI|GitHub Actions|runner)/i.test(sentence) &&
      /(?:Apple|Developer ID)/i.test(sentence) &&
      /(?:secret|ephemeral|temporary|临时|密钥)/i.test(sentence);
    check(
      ephemeralCiDeveloperIdException ||
        (/(?:does not|will not|never|no need|do not|must not|不(?:会|要|需要)?)/i.test(
          sentence,
        ) &&
          !/security\s+add-trusted-cert/i.test(sentence)),
      `${label} must not direct users to import or trust a certificate: ${sentence.trim()}`,
    );
  }
  const trustSettingsSentences = source
    .split(/(?<=[.!?。！？])\s+|\n+/)
    .filter((sentence) => /Trust Settings/i.test(sentence));
  for (const sentence of trustSettingsSentences) {
    check(
      /(?:does not|will not|never|no need|do not|不(?:会|要|需要)?)/i.test(
        sentence,
      ),
      `${label} must not direct users to modify Trust Settings: ${sentence.trim()}`,
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
    "[selfhost-macos-guidance] PASS migration, stable/nightly manual-exit tracks, automatic check/download, user restart install, recurring Gatekeeper, Trust Settings, certificate, and quarantine guidance",
  );
}
