'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { start } = require('./helpers');

async function ownerSession(ctx) {
  await ctx.seedOwner();
  const r = await ctx.login('owner', 'OwnerPass12345');
  assert.equal(r.status, 200);
  return r.json;
}

async function createUser(ctx, token, body) {
  const r = await ctx.call('POST', '/api/users', { token, body });
  assert.equal(r.status, 201, r.text);
  return r.json;
}

/** Login user baru dengan password sementara lalu ganti password agar bebas dari "wajib ganti". */
async function activate(ctx, username, temp, newPass = 'GantiBaru12345') {
  const l = await ctx.login(username, temp);
  assert.equal(l.status, 200, l.text);
  const t = l.json.accessToken;
  const p = await ctx.call('POST', '/api/me/password', { token: t, body: { currentPassword: temp, newPassword: newPass } });
  assert.equal(p.status, 200, p.text);
  const l2 = await ctx.login(username, newPass);
  assert.equal(l2.status, 200, l2.text);
  return l2.json.accessToken;
}

test('health tanpa login', async () => {
  const ctx = await start();
  const r = await ctx.call('GET', '/health');
  assert.equal(r.status, 200);
  assert.equal(r.json.ok, true);
  await ctx.stop();
});

test('login sukses, gagal, dan pesan tidak membocorkan keberadaan user', async () => {
  const ctx = await start();
  await ctx.seedOwner();
  const ok = await ctx.login('owner', 'OwnerPass12345');
  assert.equal(ok.status, 200);
  assert.equal(ok.json.user.role, 'owner');
  assert.ok(ok.json.accessToken && ok.json.refreshToken);
  assert.ok(ok.json.user.permissions.includes('audit.view'));
  const wrong = await ctx.login('owner', 'salah-banget-1');
  const ghost = await ctx.login('tidakada', 'salah-banget-1');
  assert.equal(wrong.status, 401);
  assert.equal(ghost.status, 401);
  assert.equal(wrong.json.message, ghost.json.message);
  assert.ok(!ok.text.includes('passwordHash') && !ok.text.includes('password_hash'));
  await ctx.stop();
});

test('kunci akun setelah 5 gagal beruntun, termasuk password benar saat terkunci', async () => {
  const ctx = await start();
  await ctx.seedOwner();
  for (let i = 0; i < 4; i++) assert.equal((await ctx.login('owner', 'salah-banget-1')).status, 401);
  const fifth = await ctx.login('owner', 'salah-banget-1');
  assert.equal(fifth.status, 429);
  const good = await ctx.login('owner', 'OwnerPass12345');
  assert.equal(good.status, 429);
  assert.ok(good.headers.get('retry-after'));
  ctx.clock.t += 16 * 60 * 1000;
  assert.equal((await ctx.login('owner', 'OwnerPass12345')).status, 200);
  await ctx.stop();
});

test('batas laju login per IP', async () => {
  const ctx = await start({ loginIpMax: 3, lockMaxFails: 100 });
  await ctx.seedOwner();
  for (let i = 0; i < 3; i++) await ctx.login('x' + i, 'abcdefghij1');
  const r = await ctx.login('owner', 'OwnerPass12345');
  assert.equal(r.status, 429);
  assert.equal(r.json.error, 'RATE_LIMITED');
  await ctx.stop();
});

test('/api/me butuh token; token kedaluwarsa ditolak', async () => {
  const ctx = await start();
  const s = await ownerSession(ctx);
  assert.equal((await ctx.call('GET', '/api/me')).status, 401);
  assert.equal((await ctx.call('GET', '/api/me', { token: 'x'.repeat(30) })).status, 401);
  assert.equal((await ctx.call('GET', '/api/me', { token: s.accessToken })).status, 200);
  ctx.clock.t += 16 * 60 * 1000;
  const r = await ctx.call('GET', '/api/me', { token: s.accessToken });
  assert.equal(r.status, 401);
  assert.equal(r.json.error, 'TOKEN_EXPIRED');
  await ctx.stop();
});

test('refresh merotasi token; token lama tidak berlaku lagi', async () => {
  const ctx = await start();
  const s = await ownerSession(ctx);
  const r = await ctx.call('POST', '/api/auth/refresh', { body: { refreshToken: s.refreshToken } });
  assert.equal(r.status, 200);
  assert.notEqual(r.json.refreshToken, s.refreshToken);
  assert.equal((await ctx.call('POST', '/api/auth/refresh', { body: { refreshToken: s.refreshToken } })).status, 401);
  assert.equal((await ctx.call('GET', '/api/me', { token: s.accessToken })).status, 401);
  assert.equal((await ctx.call('GET', '/api/me', { token: r.json.accessToken })).status, 200);
  ctx.clock.t += 31 * 86400000;
  assert.equal((await ctx.call('POST', '/api/auth/refresh', { body: { refreshToken: r.json.refreshToken } })).status, 401);
  await ctx.stop();
});

test('logout mencabut sesi', async () => {
  const ctx = await start();
  const s = await ownerSession(ctx);
  assert.equal((await ctx.call('POST', '/api/auth/logout', { token: s.accessToken })).status, 200);
  assert.equal((await ctx.call('GET', '/api/me', { token: s.accessToken })).status, 401);
  await ctx.stop();
});

test('Owner membuat akun: password sementara, wajib ganti, lalu bisa dipakai', async () => {
  const ctx = await start();
  const s = await ownerSession(ctx);
  const c = await createUser(ctx, s.accessToken, { username: 'Budi', displayName: 'Budi', email: 'budi@contoh.id' });
  assert.equal(c.user.username, 'budi');
  assert.ok(c.temporaryPassword && c.temporaryPassword.length >= 12);
  assert.equal(c.user.mustChangePassword, true);
  assert.deepEqual(c.user.permissions.sort(), ['backup.use', 'server.view', 'trading.use']);

  const l = await ctx.login('budi', c.temporaryPassword);
  assert.equal(l.status, 200);
  assert.equal(l.json.user.mustChangePassword, true);
  const blocked = await ctx.call('GET', '/api/me/sessions', { token: l.json.accessToken });
  assert.equal(blocked.status, 403);
  assert.equal(blocked.json.error, 'PASSWORD_CHANGE_REQUIRED');
  assert.equal((await ctx.call('GET', '/api/me', { token: l.json.accessToken })).status, 200);

  const weak = await ctx.call('POST', '/api/me/password', { token: l.json.accessToken, body: { currentPassword: c.temporaryPassword, newPassword: 'pendek1' } });
  assert.equal(weak.status, 400);
  const wrongOld = await ctx.call('POST', '/api/me/password', { token: l.json.accessToken, body: { currentPassword: 'bukan-itu-12', newPassword: 'BaruAman12345' } });
  assert.equal(wrongOld.status, 403);
  const good = await ctx.call('POST', '/api/me/password', { token: l.json.accessToken, body: { currentPassword: c.temporaryPassword, newPassword: 'BaruAman12345' } });
  assert.equal(good.status, 200);
  assert.equal((await ctx.call('GET', '/api/me/sessions', { token: l.json.accessToken })).status, 200);
  assert.equal((await ctx.login('budi', c.temporaryPassword)).status, 401);
  assert.equal((await ctx.login('budi', 'BaruAman12345')).status, 200);
  await ctx.stop();
});

test('validasi pembuatan akun: username, email, duplikat, role owner ditolak', async () => {
  const ctx = await start();
  const s = await ownerSession(ctx);
  const t = s.accessToken;
  assert.equal((await ctx.call('POST', '/api/users', { token: t, body: { username: 'a', displayName: 'A' } })).status, 400);
  assert.equal((await ctx.call('POST', '/api/users', { token: t, body: { username: 'ada baik', displayName: 'A' } })).status, 400);
  assert.equal((await ctx.call('POST', '/api/users', { token: t, body: { username: 'andi', displayName: 'A', email: 'bukan-email' } })).status, 400);
  assert.equal((await ctx.call('POST', '/api/users', { token: t, body: { username: 'andi', displayName: 'A', role: 'owner' } })).status, 400);
  assert.equal((await ctx.call('POST', '/api/users', { token: t, body: { username: 'andi', displayName: 'A', password: 'lemah' } })).status, 400);
  assert.equal((await ctx.call('POST', '/api/users', { token: t, body: { username: 'andi', displayName: 'A', permissions: ['hack.all'] } })).status, 400);
  await createUser(ctx, t, { username: 'andi', displayName: 'Andi' });
  const dup = await ctx.call('POST', '/api/users', { token: t, body: { username: 'ANDI', displayName: 'Lain' } });
  assert.equal(dup.status, 409);
  await ctx.stop();
});

test('RBAC: user biasa tidak bisa kelola akun; admin terbatas pada user biasa', async () => {
  const ctx = await start();
  const s = await ownerSession(ctx);
  const owner = s.accessToken;
  const u = await createUser(ctx, owner, { username: 'usera', displayName: 'User A' });
  const a = await createUser(ctx, owner, { username: 'admina', displayName: 'Admin A', role: 'admin' });
  const a2 = await createUser(ctx, owner, { username: 'adminb', displayName: 'Admin B', role: 'admin' });
  const userTok = await activate(ctx, 'usera', u.temporaryPassword);
  const adminTok = await activate(ctx, 'admina', a.temporaryPassword);

  assert.equal((await ctx.call('GET', '/api/users', { token: userTok })).status, 403);
  assert.equal((await ctx.call('POST', '/api/users', { token: userTok, body: { username: 'zzz', displayName: 'Z' } })).status, 403);
  assert.equal((await ctx.call('GET', '/api/audit', { token: userTok })).status, 403);

  assert.equal((await ctx.call('GET', '/api/users', { token: adminTok })).status, 200);
  assert.equal((await ctx.call('POST', '/api/users', { token: adminTok, body: { username: 'adminc', displayName: 'C', role: 'admin' } })).status, 403);
  const made = await ctx.call('POST', '/api/users', { token: adminTok, body: { username: 'userb', displayName: 'User B' } });
  assert.equal(made.status, 201);
  assert.equal((await ctx.call('PATCH', '/api/users/' + a2.user.id, { token: adminTok, body: { displayName: 'Hack' } })).status, 403);
  assert.equal((await ctx.call('PATCH', '/api/users/' + s.user.id, { token: adminTok, body: { status: 'disabled' } })).status, 403);
  assert.equal((await ctx.call('POST', '/api/users/' + s.user.id + '/reset-password', { token: adminTok })).status, 403);
  assert.equal((await ctx.call('DELETE', '/api/users/' + u.user.id, { token: adminTok })).status, 403);
  assert.equal((await ctx.call('PATCH', '/api/users/' + u.user.id, { token: adminTok, body: { role: 'admin' } })).status, 403);
  assert.equal((await ctx.call('GET', '/api/audit', { token: adminTok })).status, 403);
  await ctx.stop();
});

test('Owner tidak bisa menonaktifkan/hapus diri sendiri atau owner lain lewat API', async () => {
  const ctx = await start();
  const s = await ownerSession(ctx);
  const other = await ctx.seedOwner('owner2', 'OwnerDua12345');
  assert.equal((await ctx.call('PATCH', '/api/users/' + s.user.id, { token: s.accessToken, body: { status: 'disabled' } })).status, 403);
  assert.equal((await ctx.call('DELETE', '/api/users/' + s.user.id, { token: s.accessToken })).status, 403);
  assert.equal((await ctx.call('DELETE', '/api/users/' + other.id, { token: s.accessToken })).status, 403);
  assert.equal((await ctx.call('POST', '/api/users/' + other.id + '/reset-password', { token: s.accessToken })).status, 403);
  await ctx.stop();
});

test('menonaktifkan akun mencabut sesi dan memblokir login; bisa diaktifkan lagi', async () => {
  const ctx = await start();
  const s = await ownerSession(ctx);
  const c = await createUser(ctx, s.accessToken, { username: 'citra', displayName: 'Citra' });
  const tok = await activate(ctx, 'citra', c.temporaryPassword);
  assert.equal((await ctx.call('GET', '/api/me', { token: tok })).status, 200);
  const d = await ctx.call('PATCH', '/api/users/' + c.user.id, { token: s.accessToken, body: { status: 'disabled' } });
  assert.equal(d.status, 200);
  assert.equal((await ctx.call('GET', '/api/me', { token: tok })).status, 401);
  const l = await ctx.login('citra', 'GantiBaru12345');
  assert.equal(l.status, 403);
  assert.equal(l.json.error, 'ACCOUNT_DISABLED');
  await ctx.call('PATCH', '/api/users/' + c.user.id, { token: s.accessToken, body: { status: 'active' } });
  assert.equal((await ctx.login('citra', 'GantiBaru12345')).status, 200);
  await ctx.stop();
});

test('reset password: password sementara baru, sesi lama dicabut, wajib ganti lagi', async () => {
  const ctx = await start();
  const s = await ownerSession(ctx);
  const c = await createUser(ctx, s.accessToken, { username: 'dewi', displayName: 'Dewi' });
  const tok = await activate(ctx, 'dewi', c.temporaryPassword);
  const r = await ctx.call('POST', '/api/users/' + c.user.id + '/reset-password', { token: s.accessToken });
  assert.equal(r.status, 200);
  assert.ok(r.json.temporaryPassword);
  assert.equal((await ctx.call('GET', '/api/me', { token: tok })).status, 401);
  assert.equal((await ctx.login('dewi', 'GantiBaru12345')).status, 401);
  const l = await ctx.login('dewi', r.json.temporaryPassword);
  assert.equal(l.status, 200);
  assert.equal(l.json.user.mustChangePassword, true);
  await ctx.stop();
});

test('Owner mengubah role dan izin; izin tidak dikenal ditolak', async () => {
  const ctx = await start();
  const s = await ownerSession(ctx);
  const c = await createUser(ctx, s.accessToken, { username: 'eko', displayName: 'Eko' });
  const r = await ctx.call('PATCH', '/api/users/' + c.user.id, { token: s.accessToken, body: { role: 'admin', permissions: ['trading.use'] } });
  assert.equal(r.status, 200);
  assert.equal(r.json.user.role, 'admin');
  assert.deepEqual(r.json.user.permissions, ['trading.use']);
  assert.equal((await ctx.call('PATCH', '/api/users/' + c.user.id, { token: s.accessToken, body: { permissions: ['x.y'] } })).status, 400);
  assert.equal((await ctx.call('PATCH', '/api/users/' + c.user.id, { token: s.accessToken, body: { role: 'owner' } })).status, 400);
  await ctx.stop();
});

test('Owner menghapus akun; data terkait ikut bersih', async () => {
  const ctx = await start();
  const s = await ownerSession(ctx);
  const c = await createUser(ctx, s.accessToken, { username: 'fani', displayName: 'Fani' });
  await ctx.login('fani', c.temporaryPassword);
  assert.equal((await ctx.call('DELETE', '/api/users/' + c.user.id, { token: s.accessToken })).status, 200);
  assert.equal((await ctx.call('GET', '/api/users/' + c.user.id, { token: s.accessToken })).status, 404);
  assert.equal((await ctx.db.all('SELECT * FROM user_permissions WHERE user_id = ?', [c.user.id])).length, 0);
  assert.equal((await ctx.db.all('SELECT * FROM sessions WHERE user_id = ?', [c.user.id])).length, 0);
  await ctx.stop();
});

test('audit log mencatat kejadian penting dan tidak memuat rahasia', async () => {
  const ctx = await start();
  const s = await ownerSession(ctx);
  await ctx.login('owner', 'salah-banget-1');
  const c = await createUser(ctx, s.accessToken, { username: 'gita', displayName: 'Gita' });
  await ctx.call('POST', '/api/users/' + c.user.id + '/reset-password', { token: s.accessToken });
  const r = await ctx.call('GET', '/api/audit?limit=50', { token: s.accessToken });
  assert.equal(r.status, 200);
  const actions = r.json.entries.map((e) => e.action);
  for (const a of ['login.success', 'login.fail', 'user.create', 'user.reset_password']) assert.ok(actions.includes(a), 'tidak ada ' + a);
  assert.ok(!r.text.includes(c.temporaryPassword));
  assert.ok(!r.text.toLowerCase().includes('scrypt$'));
  await ctx.stop();
});

test('sesi: daftar dan cabut sesi sendiri', async () => {
  const ctx = await start();
  const s = await ownerSession(ctx);
  const second = (await ctx.login('owner', 'OwnerPass12345')).json;
  const list = await ctx.call('GET', '/api/me/sessions', { token: s.accessToken });
  assert.equal(list.json.sessions.length, 2);
  const other = list.json.sessions.find((x) => !x.current);
  assert.equal((await ctx.call('DELETE', '/api/me/sessions/' + other.id, { token: s.accessToken })).status, 200);
  assert.equal((await ctx.call('GET', '/api/me', { token: second.accessToken })).status, 401);
  assert.equal((await ctx.call('DELETE', '/api/me/sessions/99999', { token: s.accessToken })).status, 404);
  await ctx.stop();
});

test('masukan buruk: bukan JSON, content-type salah, terlalu besar, route tak ada', async () => {
  const ctx = await start();
  const bad = await ctx.call('POST', '/api/auth/login', { body: '{rusak', headers: {} });
  assert.equal(bad.status, 400);
  const noCt = await fetch(ctx.base + '/api/auth/login', { method: 'POST', body: '{"username":"a","password":"b"}' });
  assert.equal(noCt.status, 415);
  const big = await ctx.call('POST', '/api/auth/login', { body: JSON.stringify({ username: 'a', password: 'x'.repeat(100000) }) });
  assert.equal(big.status, 413);
  assert.equal((await ctx.call('GET', '/api/tidak-ada')).status, 401);
  assert.equal((await ctx.call('GET', '/foo')).status, 404);
  await ctx.stop();
});

test('rollback transaksi: pembuatan user gagal tidak meninggalkan izin yatim', async () => {
  const ctx = await start();
  await ctx.seedOwner();
  await assert.rejects(() =>
    ctx.repo.users.create(
      { username: 'owner', email: null, displayName: 'Dup', role: 'user', passwordHash: 'x', mustChangePassword: false },
      ['trading.use'], 1
    )
  );
  const rows = await ctx.db.all('SELECT * FROM user_permissions', []);
  assert.equal(rows.length, 0);
  await ctx.stop();
});
