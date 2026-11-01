'use strict';
const http = require('http');
const { fromEnv } = require('../src/config');
const { createRepo } = require('../src/repo');
const { createApp } = require('../src/app');
const { createSqlite } = require('./sqlite-adapter');
const sec = require('../src/security');

/** Menyalakan API sungguhan di port acak dengan SQLite in-memory dan jam yang bisa dimajukan. */
async function start(overrides = {}) {
  const config = { ...fromEnv({}), scryptN: 1024, trustProxy: false, ...overrides };
  const db = createSqlite();
  const repo = createRepo(db);
  const clock = { t: 1_700_000_000_000 };
  const app = createApp({ repo, config, now: () => clock.t });
  const server = http.createServer(app.handler);
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = 'http://127.0.0.1:' + server.address().port;

  async function call(method, path, { token, body, headers } = {}) {
    const h = { ...(headers || {}) };
    if (token) h.Authorization = 'Bearer ' + token;
    let payload;
    if (body !== undefined) {
      h['Content-Type'] = 'application/json';
      payload = typeof body === 'string' ? body : JSON.stringify(body);
    }
    const res = await fetch(base + path, { method, headers: h, body: payload });
    const text = await res.text();
    let json = null;
    try {
      json = JSON.parse(text);
    } catch (_) {
      /* bukan JSON */
    }
    return { status: res.status, json, text, headers: res.headers };
  }

  async function seedOwner(username = 'owner', password = 'OwnerPass12345') {
    const id = await repo.users.create(
      { username, email: null, displayName: 'Owner', role: 'owner', passwordHash: await sec.hashPassword(password, 1024), mustChangePassword: false },
      [], clock.t
    );
    return { id, username, password };
  }

  async function login(username, password) {
    const r = await call('POST', '/api/auth/login', { body: { username, password, device: 'tes' } });
    return r;
  }

  async function stop() {
    app.close();
    if (server.closeAllConnections) server.closeAllConnections();
    await new Promise((r) => server.close(r));
    await db.close();
  }
  return { base, call, repo, db, clock, seedOwner, login, stop, config };
}

module.exports = { start };
