'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), os = require('node:os');
const cp = require('node:child_process'), crypto = require('node:crypto');
const lifecycle = require('../index.cjs');
const encode = name => encodeURIComponent(name).replace(/%20/g, ' ').replace(/~/g, '%7E').replace(/%/g, '~');
const hash = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
function fixture() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'graph-directory-normalization-'));
  const storage = lifecycle.resolveStorage(root, path.join(root, ' graphs '));
  return { root, storage, close: () => fs.rmSync(root, { recursive: true, force: true }) };
}
function graph(storage, name) {
  const directory = path.join(storage.graphsDir, encode(name));
  fs.mkdirSync(path.join(directory, 'assets'), { recursive: true });
  fs.writeFileSync(path.join(directory, 'db.sqlite'), Buffer.from('synthetic graph ' + name));
  fs.writeFileSync(path.join(directory, 'assets', 'attachment.bin'), Buffer.from([0, 10, 255, 1]));
  return directory;
}
function data(directory) {
  return ['db.sqlite', 'assets/attachment.bin'].map(name => [name, hash(path.join(directory, name))]);
}

test('closed spaced graph moves once and retains an exact durable source without changing data', async () => {
  const f = fixture();
  try {
    const source = graph(f.storage, ' Foo '), before = data(source);
    const identity = fs.statSync(source);
    assert.deepEqual(await lifecycle.normalizeGraphDirectories(f.storage), [{ from: 'logseq_db_ Foo ', to: 'logseq_db_Foo' }]);
    const target = path.join(f.storage.graphsDir, 'Foo');
    assert.deepEqual(data(target), before);
    assert.equal(fs.statSync(target).ino, identity.ino);
    assert.equal(fs.existsSync(source), false);
    assert.equal(path.basename(f.storage.graphsDir), ' graphs ');
    const secondStorage = lifecycle.resolveStorage(f.root, f.storage.graphsDir);
    assert.deepEqual(await lifecycle.normalizeGraphDirectories(secondStorage), lifecycle.readGraphDirectoryRenames(f.storage));
    assert.deepEqual(fs.readdirSync(f.storage.graphsDir), ['Foo']);
  } finally { f.close(); }
});

test('collision numbering preserves an owned canonical sibling and occupied files', async () => {
  const f = fixture();
  let owner;
  try {
    const canonical = graph(f.storage, 'Foo'), before = data(canonical);
    owner = lifecycle.acquireOwnership(lifecycle.context(f.storage, 'Foo'));
    fs.writeFileSync(path.join(f.storage.graphsDir, 'Foo(2)'), 'occupied');
    const left = graph(f.storage, ' Foo'), right = graph(f.storage, 'Foo ');
    const leftData = data(left), rightData = data(right);
    assert.deepEqual(await lifecycle.normalizeGraphDirectories(f.storage), [
      { from: 'logseq_db_ Foo', to: 'logseq_db_Foo(3)' }, { from: 'logseq_db_Foo ', to: 'logseq_db_Foo(4)' }]);
    assert.deepEqual(data(canonical), before);
    assert.deepEqual(data(path.join(f.storage.graphsDir, 'Foo(3)')), leftData);
    assert.deepEqual(data(path.join(f.storage.graphsDir, 'Foo(4)')), rightData);
    owner.assert();
    assert.equal(fs.readFileSync(path.join(f.storage.graphsDir, 'Foo(2)'), 'utf8'), 'occupied');
  } finally { if (owner) owner.release(); f.close(); }
});

test('normalization accepts encoded Unicode and skips non-graph, reserved and symlink entries', async () => {
  const f = fixture();
  try {
    for (const name of ['internal space', ' backup ', ' Unlinked graphs ', ' logseq_db_hidden ', '   ']) graph(f.storage, name);
    fs.mkdirSync(path.join(f.storage.graphsDir, ' Empty '));
    fs.symlinkSync(path.join(f.storage.graphsDir, 'internal space'), path.join(f.storage.graphsDir, ' Link '), 'dir');
    const source = graph(f.storage, ' 图/名 '), before = data(source);
    assert.deepEqual(await lifecycle.normalizeGraphDirectories(f.storage), [{ from: 'logseq_db_ 图/名 ', to: 'logseq_db_图/名' }]);
    assert.deepEqual(data(path.join(f.storage.graphsDir, encode('图/名'))), before);
    assert(fs.lstatSync(path.join(f.storage.graphsDir, ' Link ')).isSymbolicLink());
    assert(fs.existsSync(path.join(f.storage.graphsDir, ' backup ')));
  } finally { f.close(); }
});

test('source ownership and live legacy lock block a move; only the exact dead lock is removed', async () => {
  const f = fixture();
  let owner;
  try {
    const source = graph(f.storage, 'Foo '), before = data(source);
    owner = lifecycle.acquireOwnership({ graphDir: source });
    await assert.rejects(lifecycle.normalizeGraphDirectories(f.storage), { code: 'repo-locked' });
    assert.deepEqual(data(source), before);
    owner.release(); owner = null;
    const lockFile = path.join(source, 'db-worker.lock');
    const lock = { repo: 'logseq_db_Foo ', pid: process.pid, 'lock-id': 'synthetic-lock', 'owner-source': 'cli' };
    fs.writeFileSync(lockFile, JSON.stringify(lock));
    await assert.rejects(lifecycle.normalizeGraphDirectories(f.storage), { code: 'repo-locked' });
    assert.deepEqual(JSON.parse(fs.readFileSync(lockFile)), lock);
    assert.deepEqual(data(source), before);
    lock.pid = 2147483647;
    assert.equal(lifecycle.pidExists(lock.pid), false);
    fs.writeFileSync(lockFile, JSON.stringify(lock));
    await lifecycle.normalizeGraphDirectories(f.storage);
    const target = path.join(f.storage.graphsDir, 'Foo');
    assert.deepEqual(data(target), before);
    assert.equal(fs.existsSync(path.join(target, 'db-worker.lock')), false);
  } finally { if (owner) owner.release(); f.close(); }
});

test('process crashes around the real rename recover the recorded source and reserve its target', async () => {
  for (const phase of ['before', 'after', 'source-published', 'target-published']) {
    const f = fixture();
    try {
      const source = graph(f.storage, 'Foo '), before = data(source);
      const child = cp.spawnSync(process.execPath, [...process.execArgv, '-e', `
        const fs=require('node:fs'),lifecycle=require(${JSON.stringify(require.resolve('../index.cjs'))});
        const original=fs.renameSync;
        fs.renameSync=function(from,to){
          const graphMove=from===${JSON.stringify(source)};
          if(graphMove&&${JSON.stringify(phase)}==='before')process.kill(process.pid,'SIGKILL');
          original.apply(this,arguments);
          if(graphMove&&${JSON.stringify(phase)}==='after')process.kill(process.pid,'SIGKILL');
          if(${JSON.stringify(phase)}==='source-published'&&to.endsWith('/Foo /state.json')
              &&JSON.parse(fs.readFileSync(to)).phase==='absent')process.kill(process.pid,'SIGKILL');
          if(${JSON.stringify(phase)}==='target-published'&&to.endsWith('/Foo/state.json')
              &&JSON.parse(fs.readFileSync(to)).phase==='available')process.kill(process.pid,'SIGKILL');
        };
        lifecycle.normalizeGraphDirectories(${JSON.stringify(f.storage)}).catch(e=>{console.error(e);process.exitCode=1});
      `], {encoding: 'utf8'});
      assert.equal(child.signal, 'SIGKILL', child.stderr);
      if (phase === 'before') await assert.rejects(lifecycle.createGraph(f.storage, 'Foo'), /normalization must finish/);
      await assert.rejects(lifecycle.deleteGraph(f.storage, 'Foo'), /normalization must finish/);
      assert.deepEqual(await lifecycle.normalizeGraphDirectories(f.storage), [{ from: 'logseq_db_Foo ', to: 'logseq_db_Foo' }]);
      assert.deepEqual(data(path.join(f.storage.graphsDir, 'Foo')), before);
      assert.equal(fs.existsSync(source), false);
      assert.deepEqual(fs.readdirSync(f.storage.graphsDir), ['Foo']);
    } finally { f.close(); }
  }
});

test('stores keep independent origins and deleted/recreated targets do not retain a stale mapping', async () => {
  const first = fixture(), second = fixture();
  try {
    graph(first.storage, 'Foo'); graph(first.storage, 'Foo '); graph(second.storage, 'Foo ');
    await Promise.all([lifecycle.normalizeGraphDirectories(first.storage), lifecycle.normalizeGraphDirectories(second.storage)]);
    assert.deepEqual(lifecycle.readGraphDirectoryRenames(first.storage), [{ from: 'logseq_db_Foo ', to: 'logseq_db_Foo(2)' }]);
    assert.deepEqual(lifecycle.readGraphDirectoryRenames(second.storage), [{ from: 'logseq_db_Foo ', to: 'logseq_db_Foo' }]);
    await lifecycle.deleteGraph(first.storage, 'Foo(2)');
    await lifecycle.createGraph(first.storage, 'Foo(2)');
    assert.deepEqual(lifecycle.readGraphDirectoryRenames(first.storage), []);
    assert.deepEqual(await lifecycle.normalizeGraphDirectories(first.storage), []);
  } finally { first.close(); second.close(); }
});

test('lifecycle metadata graph names remain valid before and after normalization', async () => {
  for (const name of ['state.json', 'lease.sqlite']) for (const first of ['graph', 'normalization']) {
    const f = fixture();
    try {
      if (first === 'graph') await lifecycle.createGraph(f.storage, name);
      assert.deepEqual(await lifecycle.normalizeGraphDirectories(f.storage), []);
      if (first === 'normalization') await lifecycle.createGraph(f.storage, name);
      assert(fs.statSync(path.join(f.storage.graphsDir, name)).isDirectory());
      assert.equal(lifecycle.snapshot(f.storage, name).phase, 'available');
      graph(f.storage, name + ' ');
      assert.deepEqual(await lifecycle.normalizeGraphDirectories(f.storage), [
        { from: 'logseq_db_' + name + ' ', to: 'logseq_db_' + name + '(2)' }]);
    } finally { f.close(); }
  }
});

test('an existing failed-close receipt is not hidden by directory normalization', async () => {
  const f = fixture();
  try {
    const source = graph(f.storage, 'Foo '), before = data(source);
    const directory = path.join(f.storage.lifecycleDir, 'Foo ');
    fs.mkdirSync(directory);
    const worker = { ticket: 'synthetic-ticket', pid: 2147483647 };
    assert.equal(lifecycle.pidExists(worker.pid), false);
    fs.writeFileSync(path.join(directory, 'state.json'), JSON.stringify({
      generation: 'synthetic-generation', phase: 'available', workers: [worker] }));
    fs.writeFileSync(path.join(directory, 'runtime-synthetic-ticket.json'), JSON.stringify({
      phase: 'close-error', error: 'synthetic close failure' }));
    await assert.rejects(lifecycle.normalizeGraphDirectories(f.storage), /Worker close failed/);
    assert.deepEqual(data(source), before);
    assert.equal(fs.existsSync(path.join(f.storage.graphsDir, 'Foo')), false);
  } finally { f.close(); }
});
