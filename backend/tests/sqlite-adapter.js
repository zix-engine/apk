'use strict';
// Adapter SQLite (node:sqlite) HANYA untuk pengujian: menjalankan SQL repo yang sama tanpa MariaDB.
const fs = require('fs');
const path = require('path');
const { DatabaseSync } = require('node:sqlite');

function createSqlite() {
  const db = new DatabaseSync(':memory:');
  db.exec('PRAGMA foreign_keys = ON');
  db.exec(fs.readFileSync(path.join(__dirname, 'schema.sqlite.sql'), 'utf8'));

  const syncAll = (sql, params = []) => db.prepare(sql).all(...params).map((r) => ({ ...r }));
  const syncRun = (sql, params = []) => {
    const r = db.prepare(sql).run(...params);
    return { insertId: Number(r.lastInsertRowid), changes: Number(r.changes) };
  };

  // Serialisasi semua operasi agar transaksi tidak disisipi operasi lain.
  let queue = Promise.resolve();
  const enqueue = (fn) => {
    const p = queue.then(fn);
    queue = p.catch(() => {});
    return p;
  };

  return {
    all: (sql, params) => enqueue(() => syncAll(sql, params)),
    run: (sql, params) => enqueue(() => syncRun(sql, params)),
    tx: (fn) =>
      enqueue(async () => {
        db.exec('BEGIN');
        try {
          const r = await fn({ all: async (s, p) => syncAll(s, p), run: async (s, p) => syncRun(s, p) });
          db.exec('COMMIT');
          return r;
        } catch (e) {
          db.exec('ROLLBACK');
          throw e;
        }
      }),
    close: async () => db.close(),
  };
}

module.exports = { createSqlite };
