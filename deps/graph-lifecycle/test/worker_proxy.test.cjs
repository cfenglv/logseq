'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const cp = require('node:child_process');
const lifecycle = require('../index.cjs');

test('desktop worker enables native fetch proxy support without changing CLI or parent env', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'desktop-worker-proxy-'));
  const storage = lifecycle.resolveStorage(root, path.join(root, 'graphs'));
  const spawn = cp.spawn;
  const userInfo = os.userInfo;
  const original = process.env.NODE_USE_ENV_PROXY;
  const proxyKeys = ['http_proxy', 'HTTP_PROXY', 'https_proxy', 'HTTPS_PROXY'];
  const originalProxies = Object.fromEntries(proxyKeys.map(key => [key, process.env[key]]));
  const stopped = new Error('synthetic spawn boundary');
  let captured;
  try {
    os.userInfo = options => ({ ...userInfo(options), homedir: root });
    process.env.NODE_USE_ENV_PROXY = '0';
    cp.spawn = (_executable, _args, options) => { captured = options.env; throw stopped; };
    for (const [repo, owner, proxy, expected] of [
      ['desktop-http', 'electron', 'http://127.0.0.1:3128', '1'],
      ['desktop-direct', 'electron', null, '1'],
      ['desktop-socks', 'electron', 'socks5://127.0.0.1:1080', '0'],
      ['cli-http', 'cli', 'http://127.0.0.1:3128', '0']]) {
      for (const key of proxyKeys) delete process.env[key];
      if (proxy) process.env.https_proxy = proxy;
      await lifecycle.createGraph(storage, repo);
      await assert.rejects(lifecycle.startGraph({ storage, repo, owner,
        script: path.join(root, 'synthetic-worker.cjs') }), error => error === stopped);
      assert.equal(captured.NODE_USE_ENV_PROXY, expected, repo);
      assert.equal(captured.ELECTRON_RUN_AS_NODE, '1');
      assert.equal(process.env.NODE_USE_ENV_PROXY, '0');
    }
  } finally {
    cp.spawn = spawn;
    os.userInfo = userInfo;
    if (original === undefined) delete process.env.NODE_USE_ENV_PROXY;
    else process.env.NODE_USE_ENV_PROXY = original;
    for (const [key, value] of Object.entries(originalProxies)) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
    fs.rmSync(root, { recursive: true, force: true });
  }
});
