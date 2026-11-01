'use strict';
const path = require('path');
const readline = require('readline');
const { loadEnvFile, fromEnv, validate } = require('../src/config');
const { createMysql } = require('../src/db');
const { createRepo } = require('../src/repo');

function boot() {
  loadEnvFile(path.join(__dirname, '..', '.env'));
  const config = validate(fromEnv());
  const db = createMysql(config.db);
  return { config, db, repo: createRepo(db) };
}

/** Tanya di terminal; hidden=true menyembunyikan ketikan (password). */
function ask(question, hidden = false) {
  return new Promise((resolve) => {
    const rl = readline.createInterface({ input: process.stdin, output: process.stdout, terminal: true });
    if (hidden) {
      rl._writeToOutput = (s) => {
        if (s.includes(question)) rl.output.write(s);
        else rl.output.write('');
      };
    }
    rl.question(question, (answer) => {
      rl.close();
      if (hidden) process.stdout.write('\n');
      resolve(answer);
    });
  });
}

module.exports = { boot, ask };
