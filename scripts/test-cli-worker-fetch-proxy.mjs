#!/usr/bin/env node

import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import fs from "node:fs";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import http from "node:http";
import https from "node:https";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { after, before, describe, test } from "node:test";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const cliPath = path.join(repoRoot, "static", "logseq-cli.js");
const workerProbePath = path.join(
  repoRoot,
  "scripts",
  "fixtures",
  "cli-worker-fetch-proxy-probe.mjs",
);
const caPath = path.join(
  repoRoot,
  "scripts",
  "fixtures",
  "proxy-fetch-test-ca.cert.pem",
);
const serverCertPath = path.join(
  repoRoot,
  "scripts",
  "fixtures",
  "proxy-fetch-test-server.cert.pem",
);
const serverKeyPath = path.join(
  repoRoot,
  "scripts",
  "fixtures",
  "proxy-fetch-test-server.key.pem",
);
const proxyEnvKeys = [
  "HTTP_PROXY",
  "HTTPS_PROXY",
  "ALL_PROXY",
  "NO_PROXY",
  "http_proxy",
  "https_proxy",
  "all_proxy",
  "no_proxy",
];
const expectedBody = "worker-global-fetch-reached-local-https-target";

const listen = (server) =>
  new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => resolve(server.address().port));
  });

const close = (server) =>
  new Promise((resolve) => {
    server.closeAllConnections?.();
    server.close(() => resolve());
  });

const run = (command, args, { env, timeoutMs = 12_000 } = {}) =>
  new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      cwd: repoRoot,
      env,
      stdio: ["ignore", "pipe", "pipe"],
    });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk) => {
      stdout += chunk;
    });
    child.stderr.on("data", (chunk) => {
      stderr += chunk;
    });
    child.once("error", reject);
    const timeout = setTimeout(() => {
      child.kill("SIGKILL");
      reject(
        new Error(
          `command timed out: ${command} ${args.join(" ")}\n${stdout}\n${stderr}`,
        ),
      );
    }, timeoutMs);
    child.once("close", (code, signal) => {
      clearTimeout(timeout);
      resolve({ code, signal, stdout, stderr });
    });
  });

const cleanProxyEnv = () => {
  const env = { ...process.env };
  for (const key of proxyEnvKeys) delete env[key];
  delete env.NODE_USE_ENV_PROXY;
  return env;
};

const cliArgs = (rootDir, graph, action) => [
  cliPath,
  "--root-dir",
  rootDir,
  "--graph",
  graph,
  "--timeout-ms",
  "5000",
  "server",
  action,
];

const processExists = (pid) => {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    return error?.code !== "ESRCH";
  }
};

const waitForProcessExit = async (pid) => {
  const deadline = Date.now() + 3_000;
  while (Date.now() < deadline) {
    if (!processExists(pid)) return;
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
  process.kill(pid, "SIGTERM");
};

let httpsTarget;
let httpsTargetPort;
let httpsTargetHits;
let proxy;
let proxyPort;
let proxyHits;
let proxySockets;

before(async () => {
  assert.equal(
    fs.existsSync(cliPath),
    true,
    "missing static/logseq-cli.js; run `pnpm cli:release` first",
  );

  httpsTargetHits = 0;
  httpsTarget = https.createServer(
    {
      cert: fs.readFileSync(serverCertPath),
      key: fs.readFileSync(serverKeyPath),
    },
    (_req, res) => {
      httpsTargetHits += 1;
      res.writeHead(200, { "content-type": "text/plain" });
      res.end(expectedBody);
    },
  );
  httpsTargetPort = await listen(httpsTarget);

  proxyHits = [];
  proxySockets = new Set();
  proxy = http.createServer((_req, res) => {
    proxyHits.push({ kind: "request" });
    res.writeHead(502);
    res.end("HTTPS tests require CONNECT");
  });
  proxy.on("connect", (req, clientSocket, head) => {
    proxyHits.push({
      kind: "connect",
      target: req.url,
      hasAuthorization: Boolean(req.headers["proxy-authorization"]),
    });
    proxySockets.add(clientSocket);
    clientSocket.once("close", () => proxySockets.delete(clientSocket));

    const upstream = net.connect(httpsTargetPort, "127.0.0.1");
    proxySockets.add(upstream);
    upstream.once("close", () => proxySockets.delete(upstream));
    upstream.once("connect", () => {
      clientSocket.write("HTTP/1.1 200 Connection Established\r\n\r\n");
      if (head.length > 0) upstream.write(head);
      upstream.pipe(clientSocket);
      clientSocket.pipe(upstream);
    });
    upstream.once("error", () => clientSocket.destroy());
  });
  proxyPort = await listen(proxy);
});

after(async () => {
  for (const socket of proxySockets) socket.destroy();
  await Promise.all([close(proxy), close(httpsTarget)]);
});

const runWorkerScenario = async ({
  name,
  fetchHost,
  proxyVariable,
  proxyUrl,
  noProxyVariable,
  noProxyValue,
}) => {
  const rootDir = await mkdtemp(
    path.join(os.tmpdir(), `logseq-cli-proxy-${name}-`),
  );
  const graph = `proxy-${name}`;
  const resultPath = path.join(rootDir, "fetch-result.json");
  const env = cleanProxyEnv();
  env.LOGSEQ_DB_WORKER_NODE_SCRIPT = workerProbePath;
  env.LOGSEQ_PROXY_TEST_FETCH_URL = `https://${fetchHost}:${httpsTargetPort}/probe`;
  env.LOGSEQ_PROXY_TEST_RESULT_PATH = resultPath;
  env.NODE_EXTRA_CA_CERTS = caPath;
  if (proxyVariable) {
    env[proxyVariable] =
      proxyUrl ?? `http://127.0.0.1:${proxyPort}`;
  }
  if (noProxyVariable) env[noProxyVariable] = noProxyValue;

  let result;
  let start;
  const proxyHitStart = proxyHits.length;
  const targetHitStart = httpsTargetHits;
  try {
    start = await run(process.execPath, cliArgs(rootDir, graph, "start"), {
      env,
    });
    assert.equal(
      start.code,
      0,
      `staged CLI failed to start the probe worker\nstdout:\n${start.stdout}\nstderr:\n${start.stderr}`,
    );
    result = JSON.parse(await readFile(resultPath, "utf8"));
    return {
      result,
      start,
      proxyHits: proxyHits.slice(proxyHitStart),
      targetHits: httpsTargetHits - targetHitStart,
    };
  } finally {
    if (result?.pid) {
      const stop = await run(
        process.execPath,
        cliArgs(rootDir, graph, "stop"),
        { env: cleanProxyEnv() },
      );
      if (stop.code !== 0 && processExists(result.pid)) {
        process.kill(result.pid, "SIGTERM");
      }
      await waitForProcessExit(result.pid);
    }
    await rm(rootDir, { recursive: true, force: true });
  }
};

const assertFetchSucceeded = (name, observation) => {
  assert.equal(
    observation.result.ok,
    true,
    `${name}: the final CLI's real worker global fetch did not reach the local HTTPS target; ` +
      `NODE_USE_ENV_PROXY=${JSON.stringify(
        observation.result.runtime?.nodeUseEnvProxy,
      )}; diagnostic=${JSON.stringify(observation.result.error ?? null)}`,
  );
  assert.equal(observation.result.status, 200, `${name}: HTTP status`);
  assert.equal(observation.result.body, expectedBody, `${name}: response body`);
  assert.equal(observation.targetHits, 1, `${name}: local target hit count`);
};

describe("staged Melange CLI worker global fetch proxy contract", () => {
  test("keeps direct fetch behavior when no proxy is configured", async () => {
    const observation = await runWorkerScenario({
      name: "direct",
      fetchHost: "127.0.0.1",
    });
    assertFetchSucceeded("direct", observation);
    assert.deepEqual(observation.proxyHits, []);
  });

  for (const proxyVariable of [
    "HTTPS_PROXY",
    "https_proxy",
    "ALL_PROXY",
    "all_proxy",
  ]) {
    test(`routes HTTPS global fetch through ${proxyVariable}`, async () => {
      // The target only listens on 127.0.0.1. A request for 127.0.0.2 can
      // succeed solely when the local CONNECT proxy routes it there.
      const observation = await runWorkerScenario({
        name: proxyVariable.toLowerCase().replace("_", "-"),
        fetchHost: "127.0.0.2",
        proxyVariable,
      });
      assertFetchSucceeded(proxyVariable, observation);
      assert.equal(
        observation.proxyHits.some(
          (hit) =>
            hit.kind === "connect" &&
            hit.target === `127.0.0.2:${httpsTargetPort}`,
        ),
        true,
        `${proxyVariable}: expected a CONNECT through the configured proxy`,
      );
    });
  }

  test("honors NO_PROXY without sending the request to the proxy", async () => {
    const observation = await runWorkerScenario({
      name: "no-proxy-bypass",
      fetchHost: "127.0.0.1",
      proxyVariable: "HTTPS_PROXY",
      noProxyVariable: "NO_PROXY",
      noProxyValue: "127.0.0.1",
    });
    assertFetchSucceeded("NO_PROXY", observation);
    assert.deepEqual(observation.proxyHits, []);
  });

  test("does not expose the proxy URL or credentials in CLI diagnostics", async () => {
    const proxyUrl = `http://blind-user:proxy-secret@127.0.0.1:${proxyPort}`;
    const observation = await runWorkerScenario({
      name: "proxy-diagnostic-redaction",
      fetchHost: "127.0.0.2",
      proxyVariable: "https_proxy",
      proxyUrl,
    });
    const diagnostics = JSON.stringify({
      stdout: observation.start.stdout,
      stderr: observation.start.stderr,
      worker: observation.result,
    });
    assert.equal(diagnostics.includes(proxyUrl), false);
    assert.equal(diagnostics.includes("blind-user"), false);
    assert.equal(diagnostics.includes("proxy-secret"), false);
    assertFetchSucceeded("credentialed proxy", observation);
    assert.equal(
      observation.proxyHits.some(
        (hit) => hit.kind === "connect" && hit.hasAuthorization,
      ),
      true,
      "credentialed proxy: expected authenticated CONNECT",
    );
  });
});
