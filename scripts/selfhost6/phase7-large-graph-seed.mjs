#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

import { buildLaunchSpec } from "./phase7-qualified-launch.mjs";
import {
  defaultSessionFile,
  readQualificationSession,
} from "./phase7-qualified-session.mjs";

const scriptPath = fileURLToPath(import.meta.url);
const root = path.resolve(path.dirname(scriptPath), "../..");
const PAGE_COUNT = 100;
const BLOCKS_PER_PAGE = 1000;
const TARGET_BLOCK_COUNT = PAGE_COUNT * BLOCKS_PER_PAGE;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export function parseArgs(argv) {
  const values = {};
  for (let index = 0; index < argv.length; index += 2) {
    const key = argv[index];
    const value = argv[index + 1];
    assert.ok(key?.startsWith("--") && value, `invalid argument near ${key ?? "end"}`);
    assert.equal(values[key.slice(2)], undefined, `duplicate ${key}`);
    values[key.slice(2)] = value;
  }
  assert.deepEqual(Object.keys(values).sort(), ["output", "session-file"].filter(
    (key) => values[key],
  ).sort(), "only --session-file and optional --output are accepted");
  const output = path.resolve(values.output ?? "/private/tmp/selfhost6-phase7-large-graph-seed.json");
  assert.ok(output.startsWith("/private/tmp/"), "output must stay under /private/tmp");
  return {
    sessionFile: path.resolve(values["session-file"] ?? defaultSessionFile),
    output,
  };
}

export const pageName = (sourceFullSha, index) =>
  `selfhost6-phase7c-100k-${sourceFullSha.slice(0, 8)}-${String(index).padStart(3, "0")}`;

export const blockPayload = (index) => ({ content: `p7c-${String(index).padStart(6, "0")}` });

export function countTree(nodes) {
  return nodes.reduce((total, node) =>
    total + 1 + countTree(Array.isArray(node?.children) ? node.children : []), 0);
}

function addListener(ws, event, handler) {
  if (typeof ws.addEventListener === "function") ws.addEventListener(event, handler);
  else ws.on(event, (...args) => handler(event === "message" ? { data: args[0].toString() } : args[0]));
}

function createClient(ws) {
  let nextId = 0;
  const pending = new Map();
  addListener(ws, "message", ({ data }) => {
    const message = JSON.parse(data);
    const request = pending.get(message.id);
    if (!request) return;
    pending.delete(message.id);
    clearTimeout(request.timeout);
    if (message.error) request.reject(new Error(`${request.method}: ${message.error.message}`));
    else request.resolve(message.result);
  });
  return {
    send(method, params = {}, timeoutMs = 120_000) {
      const id = ++nextId;
      return new Promise((resolve, reject) => {
        const timeout = setTimeout(() => {
          pending.delete(id);
          reject(new Error(`CDP timeout: ${method}`));
        }, timeoutMs);
        pending.set(id, { method, resolve, reject, timeout });
        ws.send(JSON.stringify({ id, method, params }));
      });
    },
  };
}

async function evaluate(cdp, expression, timeoutMs = 120_000) {
  const response = await cdp.send("Runtime.evaluate", {
    expression,
    awaitPromise: true,
    returnByValue: true,
  }, timeoutMs);
  if (response.exceptionDetails) {
    const detail = response.exceptionDetails.exception?.description ?? response.exceptionDetails.text;
    throw new Error(detail ?? "renderer evaluation failed");
  }
  return response.result?.value;
}

async function connect(port) {
  const deadline = Date.now() + 30_000;
  let lastError;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/json/list`);
      const targets = await response.json();
      for (const target of targets) {
        if (target.type !== "page" || !target.webSocketDebuggerUrl) continue;
        const ws = new WebSocket(target.webSocketDebuggerUrl);
        await new Promise((resolve, reject) => {
          addListener(ws, "open", resolve);
          addListener(ws, "error", reject);
        });
        const cdp = createClient(ws);
        await cdp.send("Runtime.enable");
        const ready = await evaluate(cdp,
          "!!(globalThis.logseq?.api && typeof globalThis.logseq.api.insert_batch_block === 'function')");
        if (ready) return { cdp, ws };
        ws.close();
      }
    } catch (error) {
      lastError = error;
    }
    await sleep(250);
  }
  throw new Error(`qualification renderer unavailable: ${lastError?.message ?? "no target"}`);
}

function validateCheckoutAndArtifact(session) {
  const spec = buildLaunchSpec({
    qualificationRoot: session.qualificationRoot,
    testHome: session.testHome,
    userData: session.userData,
    executable: session.appExecutable,
    sourceFullSha: session.sourceFullSha,
    debugPort: session.debugPort,
  });
  const head = execFileSync("git", ["rev-parse", "HEAD"], { cwd: root, encoding: "utf8" }).trim();
  assert.equal(head, session.sourceFullSha, "artifact source does not match checkout HEAD");
  assert.equal(execFileSync("git", ["status", "--porcelain"], {
    cwd: root,
    encoding: "utf8",
  }), "", "qualification checkout must be clean");
  return spec.artifact;
}

async function seedPage(cdp, sourceFullSha, pageIndex) {
  const name = pageName(sourceFullSha, pageIndex);
  return evaluate(cdp, `(async () => {
    const api = globalThis.logseq.api;
    const name = ${JSON.stringify(name)};
    const countTree = (nodes) => nodes.reduce((total, node) =>
      total + 1 + countTree(Array.isArray(node?.children) ? node.children : []), 0);
    const existing = await api.get_page(name);
    if (existing) {
      const tree = await api.get_page_blocks_tree(name);
      const nodeCount = countTree(tree ?? []);
      if (nodeCount !== ${BLOCKS_PER_PAGE + 1}) {
        throw new Error('partial qualification seed page: ' + name + ' count=' + nodeCount);
      }
      return { pageIndex: ${pageIndex}, inserted: 0, resumed: true, nodeCount };
    }
    await api.create_page(name, null, { redirect: false });
    const anchor = await api.append_block_in_page(name, 'selfhost6-phase7c-100k-anchor', {});
    if (!anchor?.uuid) throw new Error('failed to create qualification seed anchor');
    const payload = Array.from({ length: ${BLOCKS_PER_PAGE} }, (_, offset) => ({
      content: 'p7c-' + String(${pageIndex} * ${BLOCKS_PER_PAGE} + offset).padStart(6, '0'),
    }));
    const startedAt = performance.now();
    await api.insert_batch_block(anchor.uuid, payload, { sibling: false });
    const tree = await api.get_page_blocks_tree(name);
    const nodeCount = countTree(tree ?? []);
    if (nodeCount !== ${BLOCKS_PER_PAGE + 1}) {
      throw new Error('qualification seed verification failed: ' + name + ' count=' + nodeCount);
    }
    return {
      pageIndex: ${pageIndex},
      inserted: ${BLOCKS_PER_PAGE},
      resumed: false,
      nodeCount,
      elapsedMs: performance.now() - startedAt,
    };
  })()`, 300_000);
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  const session = readQualificationSession(options.sessionFile);
  const artifact = validateCheckoutAndArtifact(session);
  const { cdp, ws } = await connect(session.debugPort);
  try {
    const graph = await evaluate(cdp, "globalThis.logseq.api.get_current_graph()");
    assert.equal(graph?.url, `logseq_db_${session.graph}`);
    assert.equal(path.resolve(graph?.path), path.join(session.testHome, "logseq/graphs", session.graph));
    const pages = [];
    for (let index = 0; index < PAGE_COUNT; index += 1) {
      const result = await seedPage(cdp, session.sourceFullSha, index);
      pages.push(result);
      console.log(JSON.stringify({ status: "progress", ...result, totalPages: PAGE_COUNT }));
    }
    const insertedBlocks = pages.reduce((total, page) => total + page.inserted, 0);
    const verifiedBlocks = pages.reduce((total, page) => total + page.nodeCount - 1, 0);
    assert.equal(verifiedBlocks, TARGET_BLOCK_COUNT);
    const receipt = {
      kind: "selfhost6.phase7.large-graph-seed.v1",
      sourceFullSha: session.sourceFullSha,
      artifact,
      graph: session.graph,
      pageCount: PAGE_COUNT,
      blocksPerPage: BLOCKS_PER_PAGE,
      targetBlockCount: TARGET_BLOCK_COUNT,
      verifiedBlockCount: verifiedBlocks,
      insertedBlockCountThisRun: insertedBlocks,
      resumedPageCount: pages.filter((page) => page.resumed).length,
      pageElapsedMs: pages.filter((page) => Number.isFinite(page.elapsedMs))
        .map((page) => page.elapsedMs),
      productRuntimeChanged: false,
    };
    fs.writeFileSync(options.output, `${JSON.stringify(receipt, null, 2)}\n`, { mode: 0o600 });
    console.log(JSON.stringify({ status: "ok", output: options.output, verifiedBlocks }));
  } finally {
    ws.close();
  }
}

if (path.resolve(process.argv[1] ?? "") === scriptPath) {
  main().catch((error) => {
    console.error(`Phase 7 large-graph seed failed: ${error.message}`);
    process.exitCode = 1;
  });
}

