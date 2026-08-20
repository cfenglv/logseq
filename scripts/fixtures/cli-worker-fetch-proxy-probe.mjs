#!/usr/bin/env node

import fs from "node:fs";
import http from "node:http";
import path from "node:path";

const requiredEnv = (key) => {
  const value = process.env[key];
  if (!value) throw new Error(`missing ${key}`);
  return value;
};

const optionValue = (name) => {
  const index = process.argv.indexOf(name);
  if (index < 0 || index + 1 >= process.argv.length) {
    throw new Error(`missing ${name}`);
  }
  return process.argv[index + 1];
};

const safeError = (error) => ({
  name: error?.name ?? null,
  message: error?.message ?? String(error),
  code: error?.code ?? null,
  cause:
    error?.cause == null
      ? null
      : {
          name: error.cause.name ?? null,
          message: error.cause.message ?? String(error.cause),
          code: error.cause.code ?? null,
        },
});

const fetchUrl = requiredEnv("LOGSEQ_PROXY_TEST_FETCH_URL");
const resultPath = requiredEnv("LOGSEQ_PROXY_TEST_RESULT_PATH");
const rootDir = optionValue("--root-dir");
const repo = optionValue("--repo");
const ownerSource = optionValue("--owner-source");

let fetchResult;
try {
  const response = await globalThis.fetch(fetchUrl, {
    signal: AbortSignal.timeout(2_000),
  });
  fetchResult = {
    ok: response.ok,
    status: response.status,
    body: await response.text(),
  };
} catch (error) {
  fetchResult = {
    ok: false,
    status: null,
    body: null,
    error: safeError(error),
  };
}

const runtime = {
  nodeUseEnvProxy: process.env.NODE_USE_ENV_PROXY ?? null,
  proxyVariablesPresent: Object.fromEntries(
    [
      "HTTP_PROXY",
      "HTTPS_PROXY",
      "ALL_PROXY",
      "NO_PROXY",
      "http_proxy",
      "https_proxy",
      "all_proxy",
      "no_proxy",
    ].map((key) => [key, Boolean(process.env[key])]),
  ),
};

fs.writeFileSync(
  resultPath,
  `${JSON.stringify({
    ...fetchResult,
    pid: process.pid,
    runtime,
  })}\n`,
);

const graphsDir = path.join(rootDir, "graphs");
const graphDirs = fs
  .readdirSync(graphsDir, { withFileTypes: true })
  .filter((entry) => entry.isDirectory());
if (graphDirs.length !== 1) {
  throw new Error(`expected one graph directory, found ${graphDirs.length}`);
}

const graphDir = path.join(graphsDir, graphDirs[0].name);
const lockPath = path.join(graphDir, "db-worker.lock");
const serverListPath = path.join(rootDir, "server-list");
const server = http.createServer((req, res) => {
  if (req.method === "GET" && req.url === "/healthz") {
    res.writeHead(200, { "content-type": "application/json" });
    res.end(
      JSON.stringify({
        repo,
        status: "ready",
        host: "127.0.0.1",
        port: server.address().port,
        pid: process.pid,
        "owner-source": ownerSource,
        "root-dir": rootDir,
        revision: "cli-worker-fetch-proxy-probe",
      }),
    );
    return;
  }

  if (req.method === "POST" && req.url === "/v1/shutdown") {
    res.writeHead(200, { "content-type": "application/json" });
    res.end("{}");
    setImmediate(() => server.close(() => process.exit(0)));
    return;
  }

  res.writeHead(404, { "content-type": "application/json" });
  res.end('{"error":"not found"}');
});

server.listen(0, "127.0.0.1", () => {
  const port = server.address().port;
  fs.writeFileSync(
    lockPath,
    JSON.stringify({
      repo,
      pid: process.pid,
      host: "127.0.0.1",
      port,
      status: "ready",
      "lock-id": "cli-worker-fetch-proxy-probe",
      "owner-source": ownerSource,
      "root-dir": rootDir,
      revision: "cli-worker-fetch-proxy-probe",
    }),
  );
  fs.writeFileSync(serverListPath, `${process.pid} ${port}\n`);
});

const stop = () => server.close(() => process.exit(0));
process.on("SIGINT", stop);
process.on("SIGTERM", stop);
setTimeout(stop, 30_000).unref();
