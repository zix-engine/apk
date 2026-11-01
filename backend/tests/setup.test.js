'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const { createSqlite } = require('./sqlite-adapter');
const { createRepo } = require('../src/repo');
const { createFirstOwner, resetPassword } = require('../src/setup');
const { splitSql } = require('../src/migrate');
const sec = require('../src/security');

const config = { scryptN: 1024 };

test('create-owner: membuat owner pertama, menolak yang kedua dan input lemah', async () => {
  const db = createSqlite();
  const repo = createRepo(db);
  await assert.rejects(() => createFirstOwner(repo, config, { username: 'x', password: 'KunciRahasia12345' }), /Username/);
  await assert.rejects(() => createFirstOwner(repo, config, { username: 'owner', password: 'lemah' }), /Password/);
  const id = await createFirstOwner(repo, config, { username: 'Owner', displayName: 'Bos', password: 'KunciRahasia12345' });
  const u = await repo.users.findById(id);
  assert.equal(u.role, 'owner');
  assert.equal(u.username, 'owner');
  assert.ok(u.passwordHash.startsWith('scrypt$'));
  assert.ok(!u.passwordHash.includes('KunciRahasia12345'));
  await assert.rejects(() => createFirstOwner(repo, config, { username: 'owner2', password: 'KunciRahasia12345' }), /Owner sudah ada/);
  const log = await repo.audit.list(10);
  assert.ok(log.some((e) => e.action === 'owner.create'));
  await db.close();
});

test('reset-password CLI: mengganti password dan mencabut sesi', async () => {
  const db = createSqlite();
  const repo = createRepo(db);
  const id = await createFirstOwner(repo, config, { username: 'owner', password: 'KunciRahasia12345' });
  await repo.sessions.create({ userId: id, accessHash: 'a'.repeat(64), refreshHash: 'b'.repeat(64), accessExpires: 9, refreshExpires: 9, now: 1 });
  await resetPassword(repo, config, 'owner', 'BaruSekali98765');
  const u = await repo.users.findById(id);
  assert.ok(await sec.verifyPassword('BaruSekali98765', u.passwordHash));
  assert.equal((await db.all('SELECT * FROM sessions', [])).length, 0);
  await assert.rejects(() => resetPassword(repo, config, 'tidakada', 'BaruSekali98765'), /tidak ditemukan/);
  await db.close();
});

test('migrasi SQL dipecah benar (4 tabel) dan tanpa komentar', () => {
  const sql = fs.readFileSync(path.join(__dirname, '..', 'migrations', '001_init.sql'), 'utf8');
  const stmts = splitSql(sql);
  assert.equal(stmts.length, 4);
  assert.ok(stmts.every((s) => s.startsWith('CREATE TABLE')));
  assert.ok(stmts.every((s) => !s.includes('--')));
});
