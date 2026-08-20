#!/usr/bin/env node

import { spawn } from "node:child_process";
import net from "node:net";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const e2eDir = path.join(repoRoot, "clj-e2e");
const bbCommand = process.platform === "win32" ? "bb.exe" : "bb";
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
  const child = spawn(command, args, {
    cwd: e2eDir,
    detached,
    env: process.env,
    shell: false,
    stdio: "inherit",
  });
  children.add(child);
  child.once("exit", () => children.delete(child));
  return child;
};

const signalChild = (child, signal) => {
  if (!child || child.exitCode !== null || child.signalCode !== null) return;
  try {
    if (detached) process.kill(-child.pid, signal);
    else child.kill(signal);
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

const stopChild = async (child) => {
  if (!child || child.exitCode !== null || child.signalCode !== null) return;
  signalChild(child, "SIGTERM");
  if (!(await waitForExit(child, 5_000))) {
    signalChild(child, "SIGKILL");
    await waitForExit(child, 5_000);
  }
};

const runChild = (command, args) =>
  new Promise((resolve, reject) => {
    const child = startChild(command, args);
    child.once("error", reject);
    child.once("exit", (code, signal) => {
      if (signal) reject(new Error(`${command} terminated by ${signal}`));
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

let shuttingDown = false;
const shutdown = async () => {
  if (shuttingDown) return;
  shuttingDown = true;
  await Promise.all([...children].map(stopChild));
};

for (const [signal, exitCode] of [
  ["SIGINT", 130],
  ["SIGTERM", 143],
]) {
  process.once(signal, async () => {
    await shutdown();
    process.exit(exitCode);
  });
}

const port = await getFreePort();
console.log(`[rtc-e2e] task=${testTask} port=${port}`);
const server = startChild(bbCommand, ["serve", "--port", String(port)]);

try {
  await waitForServer(server, port);
  await runChild(bbCommand, [
    testTask,
    "--port",
    String(port),
    ...testArgs,
  ]);
} finally {
  await shutdown();
}

console.log(`[rtc-e2e] PASS task=${testTask} port=${port}`);
