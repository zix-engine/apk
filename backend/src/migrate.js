'use strict';
const fs = require('fs');
const path = require('path');

/** Memecah file SQL menjadi pernyataan (membuang komentar "--"). Cukup untuk file migrasi kita (tanpa ';' di dalam string). */
function splitSql(text) {
  const cleaned = text.split(/\r?\n/).filter((l) => !l.trim().startsWith('--')).join('\n');
  return cleaned.split(/;\s*(?:\n|$)/).map((s) => s.trim()).filter(Boolean);
}

async function runMigrations(db, dir, log = console.log) {
  await db.run(
    'CREATE TABLE IF NOT EXISTS schema_migrations (name VARCHAR(100) NOT NULL PRIMARY KEY, applied_at BIGINT NOT NULL)',
    []
  );
  const done = new Set((await db.all('SELECT name FROM schema_migrations', [])).map((r) => r.name));
  const files = fs.readdirSync(dir).filter((f) => f.endsWith('.sql')).sort();
  let applied = 0;
  for (const f of files) {
    if (done.has(f)) continue;
    log('Menjalankan migrasi ' + f + ' ...');
    for (const stmt of splitSql(fs.readFileSync(path.join(dir, f), 'utf8'))) await db.run(stmt, []);
    await db.run('INSERT INTO schema_migrations (name, applied_at) VALUES (?, ?)', [f, Date.now()]);
    applied++;
  }
  log(applied ? 'Selesai: ' + applied + ' migrasi diterapkan.' : 'Skema sudah terbaru.');
  return applied;
}

module.exports = { splitSql, runMigrations };
