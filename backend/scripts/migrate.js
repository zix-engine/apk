'use strict';
const path = require('path');
const { boot } = require('./_cli');
const { runMigrations } = require('../src/migrate');

(async () => {
  const { db } = boot();
  try {
    await runMigrations(db, path.join(__dirname, '..', 'migrations'));
  } finally {
    await db.close();
  }
})().catch((e) => {
  console.error('Migrasi gagal:', e.message);
  process.exit(1);
});
