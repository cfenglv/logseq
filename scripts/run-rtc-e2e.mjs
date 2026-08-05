#!/usr/bin/env node

import { spawn } from "node:child_process";
import net from "node:net";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  createShutdownController,
  createWindowsProcessTreeSignaler,
  releaseOwnedChildHandles,
  reportRtcE2eErrors,
  trackOwnedChild,
} from "./rtc-e2e-shutdown.mjs";

const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const e2eDir = path.join(repoRoot, "clj-e2e");
const defaultBbCommand = process.platform === "win32" ? "bb.exe" : "bb";
const bbInvocation = (() => {
  const encoded = process.env.LOGSEQ_RTC_E2E_BB_COMMAND;
  if (!encoded) return [defaultBbCommand];
  let parsed;
  try {
    parsed = JSON.parse(encoded);
  } catch (error) {
    throw new Error("LOGSEQ_RTC_E2E_BB_COMMAND must be a JSON command array", {
      cause: error,
    });
  }
  if (
    !Array.isArray(parsed) ||
    parsed.length === 0 ||
    !parsed.every((part) => typeof part === "string" && part.length > 0)
  ) {
    throw new Error(
      "LOGSEQ_RTC_E2E_BB_COMMAND must contain non-empty command tokens",
    );
  }
  return parsed;
})();
const [bbCommand, ...bbCommandPrefix] = bbInvocation;
const testTask = process.argv[2];
const testArgs = process.argv.slice(3);
const supportedTasks = new Set([
  "rtc-extra-test",
  "rtc-extra-part2-test",
]);

if (!supportedTasks.has(testTask)) {
  console.error(
    `usage: node scripts/run-rtc-e2e.mjs <${[...supportedTasks].join("|")}> [--var fully.qualified/test-var]`,
  );
  process.exit(2);
}

const children = new Set();
const detached = process.platform !== "win32";
const ownedChildren = new Set();
const signalWindowsProcessTree = createWindowsProcessTreeSignaler({
  ownedChildren,
});
const shutdownExpectedChildren = new WeakSet();
const requestedSignalShutdown = Object.freeze({ requestedSignalShutdown: true });
let shutdownController;
let requestedExitCode;
let primaryError;
let cleanupError;

const getFreePort = () =>
  new Promise((resolve, reject) => {
    const socket = net.createServer();
    socket.unref();
    socket.once("error", reject);
    socket.listen(0, "127.0.0.1", () => {
      const address = socket.address();
      const port = typeof address === "object" ? address?.port : undefined;
      socket.close((error) => {
        if (error) reject(error);
        else if (!port) reject(new Error("failed to allocate an E2E port"));
        else resolve(port);
      });
    });
  });

const startChild = (command, args) => {
  if (shutdownController?.isShuttingDown()) {
    if (requestedExitCode !== undefined) throw requestedSignalShutdown;
    throw new Error(`refusing to start ${command} after shutdown began`);
  }
  const child = trackOwnedChild(
    detached ? undefined : ownedChildren,
    spawn(command, args, {
      cwd: e2eDir,
      detached,
      env: process.env,
      shell: false,
      stdio: detached ? "inherit" : ["inherit", "pipe", "pipe"],
    }),
  );
  if (!detached) {
    child.stdout?.pipe(process.stdout, { end: false });
    child.stderr?.pipe(process.stderr, { end: false });
  }
  children.add(child);
  child.once("exit", () => children.delete(child));
  return child;
};

const signalChild = async (child, signal) => {
  if (!child || child.exitCode !== null || child.signalCode !== null) return;
  shutdownExpectedChildren.add(child);
  if (!detached) {
    await signalWindowsProcessTree(child, signal);
    return;
  }
  try {
    process.kill(-child.pid, signal);
  } catch (error) {
    if (error?.code !== "ESRCH") throw error;
  }
};

const waitForExit = (child, timeoutMs) =>
  new Promise((resolve) => {
    if (!child || child.exitCode !== null || child.signalCode !== null) {
      resolve(true);
      return;
    }
    const timeout = setTimeout(() => {
      child.removeListener("exit", onExit);
      resolve(false);
    }, timeoutMs);
    timeout.unref();
    const onExit = () => {
      clearTimeout(timeout);
      resolve(true);
    };
    child.once("exit", onExit);
  });

const runChild = (command, args) =>
  new Promise((resolve, reject) => {
    const child = startChild(command, args);
    child.once("error", reject);
    child.once("exit", (code, signal) => {
      if (
        requestedExitCode !== undefined &&
        shutdownExpectedChildren.has(child)
      ) {
        resolve();
      } else if (signal) reject(new Error(`${command} terminated by ${signal}`));
      else if (code !== 0) {
        reject(new Error(`${command} ${args.join(" ")} exited with ${code}`));
      } else resolve();
    });
  });

const waitForServer = async (server, port) => {
  const deadline = Date.now() + 30_000;
  const url = `http://127.0.0.1:${port}/index.html`;
  let spawnError;
  server.once("error", (error) => {
    spawnError = error;
  });
  while (Date.now() < deadline) {
    if (spawnError) throw spawnError;
    if (server.exitCode !== null || server.signalCode !== null) {
      if (
        requestedExitCode !== undefined &&
        shutdownExpectedChildren.has(server)
      ) {
        throw requestedSignalShutdown;
      }
      throw new Error(
        `asset server exited before becoming ready (code=${server.exitCode}, signal=${server.signalCode})`,
      );
    }
    try {
      const response = await fetch(url, {
        signal: AbortSignal.timeout(1_000),
      });
      if (response.status < 500) return;
    } catch {
      // The server may still be binding; retry until the bounded deadline.
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error(`asset server did not become ready within 30s: ${url}`);
};

shutdownController = createShutdownController({
  children,
  releaseOwnedChildren: detached
    ? undefined
    : () => releaseOwnedChildHandles(ownedChildren),
  signalChild,
  waitForExit,
});
const { shutdown } = shutdownController;

const awaitSharedShutdown = () => {
  void shutdown().catch((shutdownError) => {
    cleanupError ??= shutdownError;
    process.exitCode = 1;
  });
};
const requestErrorShutdown = (error) => {
  primaryError ??= error;
  process.exitCode = 1;
  awaitSharedShutdown();
};
const requestSignalShutdown = (exitCode) => {
  requestedExitCode ??= exitCode;
  process.exitCode ??= exitCode;
  awaitSharedShutdown();
};

for (const [signal, exitCode] of [
  ["SIGINT", 130],
  ["SIGTERM", 143],
]) {
  process.on(signal, () => {
    requestSignalShutdown(exitCode);
  });
}
process.on("uncaughtException", requestErrorShutdown);
process.on("unhandledRejection", (reason) =>
  requestErrorShutdown(
    reason instanceof Error ? reason : new Error(String(reason)),
  ),
);

let port;
let runError;
try {
  port = await getFreePort();
  console.log(`[rtc-e2e] task=${testTask} port=${port}`);
  const server = startChild(bbCommand, [
    ...bbCommandPrefix,
    "serve",
    "--port",
    String(port),
  ]);
  await waitForServer(server, port);
  await runChild(bbCommand, [
    ...bbCommandPrefix,
    testTask,
    "--port",
    String(port),
    ...testArgs,
  ]);
} catch (error) {
  if (error !== requestedSignalShutdown) runError = error;
} finally {
  try {
    await shutdown();
  } catch (error) {
    cleanupError ??= error;
  }
}

const runFailure = primaryError ?? runError;
const failed = reportRtcE2eErrors({
  primaryError: runFailure,
  cleanupError,
});
if (requestedExitCode !== undefined) {
  if (failed) {
    process.exitCode = 1;
  } else {
    process.exitCode = requestedExitCode;
  }
} else if (failed) {
  process.exitCode = 1;
} else {
  console.log(`[rtc-e2e] PASS task=${testTask} port=${port}`);
}
