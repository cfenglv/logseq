#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";

const command = path.basename(process.argv[1]);
const args = process.argv.slice(2);
const tracePath = process.env.LOGSEQ_APP_OUT_XATTR_TRACE;
const outputRoot = path.resolve(
  process.env.LOGSEQ_APP_OUT_XATTR_OUTPUT_ROOT ?? "",
);
const sourceNodeModules = path.resolve(
  process.env.LOGSEQ_APP_OUT_XATTR_SOURCE_NODE_MODULES ?? "",
);

if (!tracePath || !process.env.LOGSEQ_APP_OUT_XATTR_OUTPUT_ROOT) {
  console.error("appOut xattr command shim is missing its contract environment");
  process.exit(96);
}

fs.appendFileSync(
  tracePath,
  `${JSON.stringify({
    command,
    args,
    timestamp: process.hrtime.bigint().toString(),
  })}\n`,
);

if (["security", "spctl", "sudo"].includes(command)) {
  console.error(`${command} is forbidden during unsigned packaging`);
  process.exit(97);
}

if (command === "xattr") {
  const optionText = args
    .filter((arg) => arg.startsWith("-"))
    .join("");
  const mutates = /[cd]/.test(optionText);
  const clearsAll = /c/.test(optionText);
  const allowedAttributes = new Set([
    "com.apple.FinderInfo",
    "com.apple.ResourceFork",
    "com.apple.provenance",
  ]);

  if (mutates) {
    if (clearsAll) {
      console.error("broad xattr clearing would also delete quarantine");
      process.exit(98);
    }

    const attribute = args.find((arg) => allowedAttributes.has(arg));
    if (!attribute) {
      console.error(
        `xattr mutation is not limited to codesign detritus: ${args.join(" ")}`,
      );
      process.exit(98);
    }
    if (args.includes("com.apple.quarantine")) {
      console.error("unsigned packaging must preserve quarantine");
      process.exit(98);
    }

    const targets = args
      .filter((arg) => path.isAbsolute(arg))
      .map((target) => path.resolve(target));
    if (
      targets.length === 0 ||
      targets.some(
        (target) =>
          !target.startsWith(`${outputRoot}${path.sep}`) ||
          target.startsWith(`${sourceNodeModules}${path.sep}`) ||
          target.startsWith(`/Applications${path.sep}`),
      )
    ) {
      console.error(
        `xattr mutation escaped the temporary appOut: ${args.join(" ")}`,
      );
      process.exit(98);
    }
  }
}

const realCommands = {
  codesign: "/usr/bin/codesign",
  xattr: "/usr/bin/xattr",
};
const realCommand = realCommands[command];
if (!realCommand) {
  console.error(`unsupported appOut xattr contract command: ${command}`);
  process.exit(96);
}

const result = spawnSync(realCommand, args, { stdio: "inherit" });
if (result.error) {
  throw result.error;
}
process.exit(result.status ?? 1);
