#!/usr/bin/env node
// Runs Code.gs in Node against a small mock of the Google services. It shows the script's own logic, not what Google does.
//   node tools/log-upload/test.js
'use strict';
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');
const crypto = require('node:crypto');
const assert = require('node:assert');

const code = fs.readFileSync(path.join(__dirname, 'Code.gs'), 'utf8');
const KEY_A = '00112233445566778899aabbccddeeff';
const KEY_B = 'ffeeddccbbaa99887766554433221100';
const hash = k => crypto.createHash('sha256').update(k, 'utf8').digest('hex');
const GZ = zlib.gzipSync('hello').toString('base64');

function world() {
  let clock = Date.UTC(2026, 10, 1, 12, 0, 0);
  const signed = b => Array.from(b, x => (x > 127 ? x - 256 : x));
  class File {
    constructor(name, bytes) { this.name = name; this.bytes = bytes; this.created = new Date(clock); this.id = 'f' + Math.random(); this.parent = null; }
    getId() { return this.id; }
    getDateCreated() { return this.created; }
    setTrashed() { this.parent.files = this.parent.files.filter(f => f !== this); }
  }
  const iter = list => { let i = 0; const c = list.slice(); return { hasNext: () => i < c.length, next: () => c[i++] }; };
  class Folder {
    constructor(name, parent) { this.name = name; this.parent = parent; this.files = []; this.dirs = []; this.description = null; this.id = 'd' + Math.random(); }
    getId() { return this.id; }
    getDescription() { return this.description; }
    setDescription(d) { this.description = d; return this; }
    getFoldersByName(n) { return iter(this.dirs.filter(d => d.name === n)); }
    getFolders() { return iter(this.dirs); }
    getFiles() { return iter(this.files); }
    createFolder(n) { const d = new Folder(n, this); this.dirs.push(d); w.driveWrites++; return d; }
    createFile(b) { const f = new File(b.name, b.bytes); f.parent = this; this.files.push(f); clock += 1000; return f; }
    setTrashed() { this.parent.dirs = this.parent.dirs.filter(d => d !== this); }
  }
  const driveRoot = new Folder('', null);
  const props = {};
  const cache = {};
  const all = () => { const out = []; const walk = d => { out.push(d); d.files.forEach(f => out.push(f)); d.dirs.forEach(walk); }; walk(driveRoot); return out; };
  const w = {
    driveWrites: 0, driveRoot, props, cache,
    advance(ms) { clock += ms; },
  };
  const pad = n => String(n).padStart(2, '0');
  const sandbox = {
    Date: class extends Date { constructor(...a) { if (a.length) super(...a); else super(clock); } static now() { return clock; } },
    Utilities: {
      base64Decode: s => signed(Buffer.from(s, 'base64')),
      formatDate: (d, tz, f) => {
        const y = `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`;
        return f === 'yyyy-MM-dd' ? y : `${y}_${pad(d.getUTCHours())}-${pad(d.getUTCMinutes())}-${pad(d.getUTCSeconds())}`;
      },
      newBlob: (bytes, type, name) => ({ bytes, type, name }),
      computeDigest: (alg, text) => { assert.strictEqual(alg, 'SHA_256'); return signed(crypto.createHash('sha256').update(text, 'utf8').digest()); },
      DigestAlgorithm: { SHA_256: 'SHA_256' },
      Charset: { UTF_8: 'UTF_8' },
    },
    ContentService: { createTextOutput: t => ({ text: t }) },
    LockService: { getScriptLock: () => ({ waitLock() {}, releaseLock() {} }) },
    PropertiesService: { getScriptProperties: () => ({
      getProperty: k => (k in props ? props[k] : null),
      setProperties: o => Object.assign(props, o),
    }) },
    CacheService: { getScriptCache: () => ({
      get: k => (k in cache ? cache[k] : null),
      put: (k, v, ttl) => { assert.ok(ttl <= 21600, 'CacheService allows 6 hours at most'); cache[k] = v; },
    }) },
    DriveApp: {
      getFoldersByName: n => driveRoot.getFoldersByName(n),
      createFolder: n => driveRoot.createFolder(n),
    },
    Drive: { Files: { remove: id => { const x = all().find(i => i.id === id); assert.ok(x, 'remove: unknown id'); if (x.parent) x.setTrashed(); } } },
    ScriptApp: {},
  };
  vm.createContext(sandbox);
  vm.runInContext(code, sandbox);
  w.post = (id, key, body = GZ) => {
    const parameter = {};
    if (id !== undefined) parameter.id = id;
    if (key !== undefined) parameter.key = key;
    return vm.runInContext('doPost', sandbox)({ parameter, postData: { contents: body } }).text;
  };
  w.cleanup = () => vm.runInContext('dailyCleanup', sandbox)();
  w.dir = id => { const r = driveRoot.dirs.find(d => d.name === 'CarPulse logs'); return r && r.dirs.find(d => d.name === id); };
  w.unverified = id => { const d = w.dir(id); return d && d.dirs.find(x => x.name === 'unverified'); };
  w.clearIdCounts = () => Object.keys(cache).forEach(k => delete cache[k]);
  return w;
}

const tests = {
  'first upload registers the id and lands in its folder'() {
    const w = world();
    assert.strictEqual(w.post('ABCD2345', KEY_A), 'ok');
    const d = w.dir('ABCD2345');
    assert.strictEqual(d.getDescription(), hash(KEY_A));
    assert.strictEqual(d.files.length, 1);
    assert.match(d.files[0].name, new RegExp(`^log-\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}-${hash(KEY_A).slice(0, 6)}\\.txt\\.gz$`));
    assert.strictEqual(w.unverified('ABCD2345'), undefined);
    assert.deepStrictEqual(Buffer.from(d.files[0].bytes.map(b => b & 0xff)), zlib.gzipSync('hello'));
  },
  'the plain key is stored nowhere'() {
    const w = world();
    w.post('ABCD2345', KEY_A);
    assert.ok(!JSON.stringify([w.dir('ABCD2345').getDescription(), w.dir('ABCD2345').files[0].name, w.props, w.cache]).includes(KEY_A));
  },
  'the same key goes on to the id folder'() {
    const w = world();
    w.post('ABCD2345', KEY_A);
    assert.strictEqual(w.post('ABCD2345', KEY_A), 'ok');
    assert.strictEqual(w.dir('ABCD2345').files.length, 2);
    assert.strictEqual(w.unverified('ABCD2345'), undefined);
  },
  'another key goes to unverified with its own tag, the owner stays'() {
    const w = world();
    w.post('ABCD2345', KEY_A);
    assert.strictEqual(w.post('ABCD2345', KEY_B), 'ok');
    const u = w.unverified('ABCD2345');
    assert.strictEqual(u.files.length, 1);
    assert.ok(u.files[0].name.includes(`-${hash(KEY_B).slice(0, 6)}.txt.gz`));
    assert.strictEqual(w.dir('ABCD2345').files.length, 1);
    assert.strictEqual(w.dir('ABCD2345').getDescription(), hash(KEY_A));
  },
  'no key, an old client: unverified, registers nothing'() {
    const w = world();
    assert.strictEqual(w.post('ABCD2345', undefined), 'ok');
    const u = w.unverified('ABCD2345');
    assert.strictEqual(u.files.length, 1);
    assert.ok(u.files[0].name.endsWith('-no-key.txt.gz'));
    assert.strictEqual(w.dir('ABCD2345').getDescription(), null);
    // the first real key after it still registers
    w.post('ABCD2345', KEY_A);
    assert.strictEqual(w.dir('ABCD2345').getDescription(), hash(KEY_A));
    assert.strictEqual(w.dir('ABCD2345').files.length, 1);
  },
  'a malformed key counts as no key'() {
    const w = world();
    assert.strictEqual(w.post('ABCD2345', 'short'), 'ok');
    assert.strictEqual(w.post('ABCD2345', KEY_A.toUpperCase()), 'ok');
    assert.strictEqual(w.dir('ABCD2345').getDescription(), null);
    assert.strictEqual(w.unverified('ABCD2345').files.length, 2);
  },
  'forged logs do not push out real ones: separate caps of 20'() {
    const w = world();
    w.post('ABCD2345', KEY_A);
    for (let i = 0; i < 25; i++) { w.post('ABCD2345', KEY_B); w.clearIdCounts(); }
    assert.strictEqual(w.dir('ABCD2345').files.length, 1);
    assert.strictEqual(w.unverified('ABCD2345').files.length, 20);
    for (let i = 0; i < 25; i++) { w.post('ABCD2345', KEY_A); w.clearIdCounts(); }
    assert.strictEqual(w.dir('ABCD2345').files.length, 20);
    assert.strictEqual(w.unverified('ABCD2345').files.length, 20);
    assert.strictEqual(w.dir('ABCD2345').files.filter(f => f.name.includes(hash(KEY_B).slice(0, 6))).length, 0);
  },
  'everything else stops at 100 a day, verified goes on'() {
    const w = world();
    w.post('AAAA2345', KEY_A); // registered: 1 of the other budget
    for (let i = 0; i < 99; i++) { assert.strictEqual(w.post('BBBB2345', undefined), 'ok', `other ${i}`); w.clearIdCounts(); }
    assert.strictEqual(w.post('CCCC2345', KEY_B), 'day limit');
    assert.strictEqual(w.post('CCCC2345', undefined), 'day limit');
    assert.strictEqual(w.dir('CCCC2345'), undefined, 'no folder for a refused upload');
    assert.strictEqual(w.post('AAAA2345', KEY_A), 'ok', 'the real unit still gets through');
  },
  'verified stops at 200 a day, the other budget stays'() {
    const w = world();
    w.post('AAAA2345', KEY_A);
    for (let i = 0; i < 200; i++) { assert.strictEqual(w.post('AAAA2345', KEY_A), 'ok', `verified ${i}`); w.clearIdCounts(); }
    assert.strictEqual(w.post('AAAA2345', KEY_A), 'day limit');
    assert.strictEqual(w.post('DDDD2345', KEY_B), 'ok');
  },
  'a new day opens both budgets'() {
    const w = world();
    w.post('AAAA2345', KEY_A);
    for (let i = 0; i < 200; i++) { w.post('AAAA2345', KEY_A); w.clearIdCounts(); }
    w.props.countOther = '100';
    assert.strictEqual(w.post('AAAA2345', KEY_A), 'day limit');
    assert.strictEqual(w.post('EEEE2345', KEY_B), 'day limit');
    w.advance(24 * 3600 * 1000);
    assert.strictEqual(w.post('AAAA2345', KEY_A), 'ok');
    assert.strictEqual(w.post('EEEE2345', KEY_B), 'ok');
    assert.strictEqual(w.props.countOther, '1');
  },
  'an id stops at 30 a day, other ids go on'() {
    const w = world();
    for (let i = 0; i < 30; i++) assert.strictEqual(w.post('ABCD2345', KEY_A), 'ok');
    assert.strictEqual(w.post('ABCD2345', KEY_A), 'id limit');
    assert.strictEqual(w.post('ABCD2345', KEY_B), 'id limit');
    assert.strictEqual(w.post('EFGH2345', KEY_A), 'ok');
    w.advance(24 * 3600 * 1000);
    assert.strictEqual(w.post('ABCD2345', KEY_A), 'ok', 'next day');
  },
  'a refused id is not counted against the day'() {
    const w = world();
    for (let i = 0; i < 31; i++) w.post('ABCD2345', undefined);
    assert.strictEqual(w.props.countOther, '30');
  },
  'bad id'() {
    const w = world();
    for (const id of ['abcd2345', 'ABCD0000', 'ABC', '', undefined, 'ABCD23456', '../../xx']) assert.strictEqual(w.post(id, KEY_A), 'bad id', String(id));
    assert.deepStrictEqual(w.driveRoot.dirs, []);
  },
  'not gzip, empty, too long'() {
    const w = world();
    assert.strictEqual(w.post('ABCD2345', KEY_A, 'AAAA'), 'not gzip');
    assert.strictEqual(w.post('ABCD2345', KEY_A, ''), 'bad size');
    assert.strictEqual(w.post('ABCD2345', KEY_A, 'A'.repeat(512 * 1024 + 1)), 'bad size');
    assert.deepStrictEqual(w.driveRoot.dirs, []);
    assert.strictEqual(w.props.day, undefined, 'nothing counted');
  },
  'clean-up: old files go, a registered folder stays, an unregistered empty one goes'() {
    const w = world();
    w.post('AAAA2345', KEY_A);
    w.post('AAAA2345', KEY_B);
    w.post('BBBB2345', undefined);
    w.advance(31 * 24 * 3600 * 1000);
    w.post('CCCC2345', KEY_A);
    w.cleanup();
    assert.strictEqual(w.dir('AAAA2345').files.length, 0);
    assert.strictEqual(w.dir('AAAA2345').getDescription(), hash(KEY_A));
    assert.strictEqual(w.unverified('AAAA2345'), undefined);
    assert.strictEqual(w.dir('BBBB2345'), undefined);
    assert.strictEqual(w.dir('CCCC2345').files.length, 1);
  },
  'clean-up: a fresh unverified file keeps its folders'() {
    const w = world();
    w.post('BBBB2345', undefined);
    w.cleanup();
    assert.strictEqual(w.unverified('BBBB2345').files.length, 1);
  },
};

let failed = 0;
for (const [name, fn] of Object.entries(tests)) {
  try { fn(); console.log('ok   ' + name); } catch (e) { failed++; console.log('FAIL ' + name + '\n     ' + (e.message || e)); }
}
console.log(`${Object.keys(tests).length - failed}/${Object.keys(tests).length} passed`);
process.exit(failed ? 1 : 0);
