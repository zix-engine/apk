'use strict';
const http = require('http');
const path = require('path');
const { loadEnvFile, fromEnv, validate } = require('./config');
const { createMysql } = require('./db');
const { createRepo } = require('./repo');
const { createApp } = require('./app');

loadEnvFile(path.join(__dirname, '..', '.env'));
const config = validate(fromEnv());
const db = createMysql(config.db);
const app = createApp({ repo: createRepo(db), config });

const server = http.createServer(app.handler);
server.requestTimeout = 15000;
server.headersTimeout = 10000;
server.listen(config.port, config.host, () => {
  console.log('ZixFunction API mendengarkan di http://' + config.host + ':' + config.port);
});

function shutdown() {
  console.log('Menutup server...');
  server.close(async () => {
    app.close();
    try {
      await db.close();
    } catch (_) {
      /* abaikan */
    }
    process.exit(0);
  });
  setTimeout(() => process.exit(1), 10000).unref();
}
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
