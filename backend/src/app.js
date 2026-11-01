'use strict';
const sec = require('./security');
const { ALL_PERMISSIONS } = require('./repo');

const USERNAME_RE = /^[a-z0-9._-]{3,32}$/;
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;
const DEFAULT_PERMS = {
  admin: ['server.view', 'server.manage', 'trading.use', 'backup.use', 'ai.use'],
  user: ['server.view', 'trading.use', 'backup.use'],
};
// Endpoint yang tetap boleh dipakai saat akun wajib ganti password.
const ALLOWED_WHEN_MUST_CHANGE = new Set(['GET /api/me', 'POST /api/me/password', 'POST /api/auth/logout']);

class HttpError extends Error {
  constructor(status, code, message, extra) {
    super(message);
    this.status = status;
    this.code = code;
    this.extra = extra || {};
  }
}

function createApp({ repo, config, now = Date.now }) {
  const loginLimiter = new sec.Limiter(config.loginIpMax, config.loginIpWindowMs);
  const apiLimiter = new sec.Limiter(config.apiIpMax, 60 * 1000);
  const guard = new sec.LoginGuard(config.lockMaxFails, config.lockMs);
  const dummyHash = sec.hashPassword('dummy-password-' + Math.random(), config.scryptN);

  const sweeper = setInterval(() => {
    loginLimiter.sweep(now());
    apiLimiter.sweep(now());
    guard.sweep(now());
    repo.sessions.purgeExpired(now()).catch(() => {});
  }, 60 * 1000);
  if (sweeper.unref) sweeper.unref();

  // ---------------------------------------------------------------- util

  function clientIp(req) {
    const direct = req.socket.remoteAddress || '';
    if (config.trustProxy && (direct === '127.0.0.1' || direct === '::1' || direct === '::ffff:127.0.0.1')) {
      const xff = req.headers['x-forwarded-for'];
      if (xff) return String(xff).split(',')[0].trim().slice(0, 64);
    }
    return direct.slice(0, 64);
  }

  function send(res, status, body, headers = {}) {
    const text = JSON.stringify(body);
    res.writeHead(status, {
      'Content-Type': 'application/json; charset=utf-8',
      'Content-Length': Buffer.byteLength(text),
      'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff',
      'Referrer-Policy': 'no-referrer',
      ...headers,
    });
    res.end(text);
  }

  function readJson(req, limit = 65536) {
    return new Promise((resolve, reject) => {
      const chunks = [];
      let size = 0;
      req.on('data', (c) => {
        size += c.length;
        if (size > limit) {
          if (size - c.length <= limit) reject(new HttpError(413, 'TOO_LARGE', 'Permintaan terlalu besar.', { close: true }));
          return; // buang sisa data; koneksi ditutup setelah respons dikirim
        }
        chunks.push(c);
      });
      req.on('end', () => {
        if (size === 0) return resolve({});
        const ct = String(req.headers['content-type'] || '');
        if (!ct.toLowerCase().includes('application/json')) return reject(new HttpError(415, 'BAD_CONTENT_TYPE', 'Content-Type harus application/json.'));
        try {
          const v = JSON.parse(Buffer.concat(chunks).toString('utf8'));
          if (v === null || typeof v !== 'object' || Array.isArray(v)) throw new Error('bukan objek');
          resolve(v);
        } catch (e) {
          reject(new HttpError(400, 'BAD_JSON', 'Isi permintaan bukan JSON objek yang valid.'));
        }
      });
      req.on('error', () => reject(new HttpError(400, 'BAD_REQUEST', 'Permintaan tidak valid.')));
    });
  }

  async function audit(action, actor, target, ip, detail) {
    try {
      await repo.audit.add({
        ts: now(), actorId: actor ? actor.id : null, actorUsername: actor ? actor.username : null,
        action, target, ip, detail,
      });
    } catch (e) {
      console.error('audit gagal:', e.message);
    }
  }

  async function effectivePerms(user) {
    if (user.role === 'owner') return ALL_PERMISSIONS.slice();
    return repo.users.permissions(user.id);
  }

  async function userView(u) {
    return {
      id: u.id, username: u.username, email: u.email, displayName: u.displayName, role: u.role, status: u.status,
      mustChangePassword: u.mustChangePassword, createdAt: u.createdAt, lastLoginAt: u.lastLoginAt,
      permissions: await effectivePerms(u),
    };
  }

  async function authenticate(req) {
    const h = String(req.headers.authorization || '');
    const m = /^Bearer ([A-Za-z0-9_-]{20,128})$/.exec(h);
    if (!m) throw new HttpError(401, 'UNAUTHENTICATED', 'Belum login.');
    const s = await repo.sessions.findByAccess(sec.sha256(m[1]));
    if (!s) throw new HttpError(401, 'UNAUTHENTICATED', 'Sesi tidak valid. Silakan login lagi.');
    if (Number(s.access_expires) < now()) throw new HttpError(401, 'TOKEN_EXPIRED', 'Token kedaluwarsa.');
    const user = await repo.users.findById(Number(s.user_id));
    if (!user || user.status !== 'active') throw new HttpError(401, 'UNAUTHENTICATED', 'Akun tidak aktif.');
    if (now() - Number(s.last_used_at) > 60000) repo.sessions.touch(Number(s.id), now()).catch(() => {});
    return { session: s, user };
  }

  function issue(userId, device, ip) {
    const access = sec.newToken();
    const refresh = sec.newToken();
    const t = now();
    return {
      access, refresh,
      row: {
        userId, accessHash: sec.sha256(access), refreshHash: sec.sha256(refresh),
        accessExpires: t + config.accessTtlMs, refreshExpires: t + config.refreshTtlMs, device, ip, now: t,
      },
    };
  }

  const need = (cond, msg, code = 'BAD_REQUEST') => {
    if (!cond) throw new HttpError(code === 'CONFLICT' ? 409 : code === 'FORBIDDEN' ? 403 : 400, code, msg);
  };

  const isManager = (u) => u.role === 'owner' || u.role === 'admin';

  /** Owner mengelola admin & user biasa; admin hanya user biasa; owner lain tidak bisa diubah lewat API. */
  function canManage(actor, target) {
    if (actor.id === target.id) return false;
    if (actor.role === 'owner') return target.role !== 'owner';
    if (actor.role === 'admin') return target.role === 'user';
    return false;
  }

  function cleanPerms(input, actor, fallback) {
    if (input === undefined) return fallback;
    need(Array.isArray(input) && input.every((p) => typeof p === 'string'), 'Daftar izin tidak valid.');
    const uniq = [...new Set(input)];
    for (const p of uniq) need(ALL_PERMISSIONS.includes(p), 'Izin tidak dikenal: ' + p);
    if (actor.role !== 'owner') {
      const mine = actor.perms || [];
      for (const p of uniq) need(mine.includes(p) && p !== 'audit.view', 'Kamu tidak boleh memberi izin: ' + p, 'FORBIDDEN');
    }
    return uniq;
  }

  // ---------------------------------------------------------------- handler

  async function dispatch(req, res) {
    const url = new URL(req.url, 'http://x');
    const path = url.pathname.replace(/\/+$/, '') || '/';
    const method = req.method;
    const ip = clientIp(req);

    if (method === 'GET' && path === '/health') return send(res, 200, { ok: true, service: 'zixfunction-api', time: now() });
    if (!path.startsWith('/api/')) throw new HttpError(404, 'NOT_FOUND', 'Tidak ditemukan.');

    const lim = apiLimiter.hit(ip, now());
    if (!lim.ok) throw new HttpError(429, 'RATE_LIMITED', 'Terlalu banyak permintaan. Coba sebentar lagi.', { retryAfterSec: Math.ceil(lim.retryMs / 1000) });

    const key = method + ' ' + path;

    // ---------- tanpa autentikasi
    if (key === 'POST /api/auth/login') {
      const l = loginLimiter.hit(ip, now());
      if (!l.ok) throw new HttpError(429, 'RATE_LIMITED', 'Terlalu banyak percobaan dari alamatmu. Coba lagi nanti.', { retryAfterSec: Math.ceil(l.retryMs / 1000) });
      const b = await readJson(req);
      const username = typeof b.username === 'string' ? b.username.trim().toLowerCase() : '';
      const password = typeof b.password === 'string' ? b.password : '';
      need(username && password && username.length <= 64 && password.length <= 128, 'Username dan password wajib diisi.');
      const device = typeof b.device === 'string' ? b.device.slice(0, 120) : '';

      const lockedMs = guard.lockedMs(username, now());
      if (lockedMs > 0) {
        await audit('login.locked', null, username, ip, null);
        throw new HttpError(429, 'ACCOUNT_LOCKED', 'Terlalu banyak percobaan gagal. Coba lagi dalam ' + Math.ceil(lockedMs / 60000) + ' menit.', { retryAfterSec: Math.ceil(lockedMs / 1000) });
      }
      const user = await repo.users.findByUsername(username);
      const ok = await sec.verifyPassword(password, user ? user.passwordHash : await dummyHash);
      if (!user || !ok) {
        const lockedNow = guard.fail(username, now());
        await audit('login.fail', null, username, ip, null);
        if (lockedNow) throw new HttpError(429, 'ACCOUNT_LOCKED', 'Terlalu banyak percobaan gagal. Akun dikunci sementara.', { retryAfterSec: Math.ceil(config.lockMs / 1000) });
        throw new HttpError(401, 'INVALID_CREDENTIALS', 'Username atau password salah.');
      }
      if (user.status !== 'active') {
        await audit('login.disabled', user, user.username, ip, null);
        throw new HttpError(403, 'ACCOUNT_DISABLED', 'Akun dinonaktifkan. Hubungi Owner.');
      }
      guard.success(username);
      const t = issue(user.id, device, ip);
      await repo.sessions.create(t.row);
      await repo.users.touchLogin(user.id, now());
      await audit('login.success', user, user.username, ip, device);
      return send(res, 200, {
        accessToken: t.access, refreshToken: t.refresh, accessExpiresAt: t.row.accessExpires,
        refreshExpiresAt: t.row.refreshExpires, user: await userView(user),
      });
    }

    if (key === 'POST /api/auth/refresh') {
      const l = loginLimiter.hit(ip, now());
      if (!l.ok) throw new HttpError(429, 'RATE_LIMITED', 'Terlalu banyak permintaan.', { retryAfterSec: Math.ceil(l.retryMs / 1000) });
      const b = await readJson(req);
      need(typeof b.refreshToken === 'string' && b.refreshToken.length >= 20 && b.refreshToken.length <= 128, 'Refresh token tidak valid.');
      const s = await repo.sessions.findByRefresh(sec.sha256(b.refreshToken));
      if (!s || Number(s.refresh_expires) < now()) throw new HttpError(401, 'SESSION_EXPIRED', 'Sesi berakhir. Silakan login lagi.');
      const user = await repo.users.findById(Number(s.user_id));
      if (!user || user.status !== 'active') throw new HttpError(401, 'SESSION_EXPIRED', 'Sesi berakhir. Silakan login lagi.');
      const t = issue(user.id, s.device, ip);
      await repo.sessions.rotate(Number(s.id), t.row.accessHash, t.row.refreshHash, t.row.accessExpires, t.row.refreshExpires, now());
      return send(res, 200, {
        accessToken: t.access, refreshToken: t.refresh, accessExpiresAt: t.row.accessExpires,
        refreshExpiresAt: t.row.refreshExpires, user: await userView(user),
      });
    }

    // ---------- wajib login
    const auth = await authenticate(req);
    const me = auth.user;
    me.perms = await effectivePerms(me);
    if (me.mustChangePassword && !ALLOWED_WHEN_MUST_CHANGE.has(key)) {
      throw new HttpError(403, 'PASSWORD_CHANGE_REQUIRED', 'Kamu harus mengganti password sementara dulu.');
    }

    if (key === 'POST /api/auth/logout') {
      await repo.sessions.remove(Number(auth.session.id), me.id);
      await audit('logout', me, me.username, ip, null);
      return send(res, 200, { ok: true });
    }

    if (key === 'GET /api/me') return send(res, 200, { user: await userView(me) });

    if (key === 'POST /api/me/password') {
      const b = await readJson(req);
      need(typeof b.currentPassword === 'string' && typeof b.newPassword === 'string', 'Password lama dan baru wajib diisi.');
      const okOld = await sec.verifyPassword(b.currentPassword, me.passwordHash);
      if (!okOld) {
        await audit('password.change_fail', me, me.username, ip, null);
        throw new HttpError(403, 'WRONG_PASSWORD', 'Password lama salah.');
      }
      const problem = sec.passwordProblem(b.newPassword, me.username);
      need(!problem, problem || '', 'WEAK_PASSWORD');
      need(b.newPassword !== b.currentPassword, 'Password baru tidak boleh sama dengan yang lama.', 'WEAK_PASSWORD');
      await repo.users.setPassword(me.id, await sec.hashPassword(b.newPassword, config.scryptN), false, now());
      await repo.sessions.removeAllForUser(me.id, Number(auth.session.id));
      await audit('password.change', me, me.username, ip, null);
      return send(res, 200, { ok: true });
    }

    if (key === 'GET /api/me/sessions') {
      const rows = await repo.sessions.listByUser(me.id);
      return send(res, 200, {
        sessions: rows.map((r) => ({
          id: Number(r.id), device: r.device, ip: r.ip, createdAt: Number(r.created_at), lastUsedAt: Number(r.last_used_at),
          current: Number(r.id) === Number(auth.session.id),
        })),
      });
    }
    let m = /^\/api\/me\/sessions\/(\d+)$/.exec(path);
    if (m && method === 'DELETE') {
      const ok = await repo.sessions.remove(Number(m[1]), me.id);
      if (!ok) throw new HttpError(404, 'NOT_FOUND', 'Sesi tidak ditemukan.');
      await audit('session.revoke', me, 'session:' + m[1], ip, null);
      return send(res, 200, { ok: true });
    }

    // ---------- manajemen akun (owner/admin)
    if (path === '/api/users' || /^\/api\/users\/\d+/.test(path)) {
      if (!isManager(me)) throw new HttpError(403, 'FORBIDDEN', 'Kamu tidak punya akses ke manajemen akun.');
    }

    if (key === 'GET /api/users') {
      const q = (url.searchParams.get('q') || '').trim().slice(0, 40);
      const list = await repo.users.list(q);
      return send(res, 200, { users: await Promise.all(list.map(userView)) });
    }

    if (key === 'POST /api/users') {
      const b = await readJson(req);
      const username = typeof b.username === 'string' ? b.username.trim().toLowerCase() : '';
      need(USERNAME_RE.test(username), 'Username 3–32 karakter: huruf kecil, angka, titik, minus, garis bawah.');
      const displayName = typeof b.displayName === 'string' ? b.displayName.trim() : '';
      need(displayName.length >= 1 && displayName.length <= 60, 'Nama tampilan 1–60 karakter.');
      const email = typeof b.email === 'string' && b.email.trim() ? b.email.trim().toLowerCase() : null;
      need(email === null || (EMAIL_RE.test(email) && email.length <= 190), 'Format email tidak valid.');
      const role = b.role === undefined ? 'user' : b.role;
      need(role === 'user' || role === 'admin', 'Role harus user atau admin.');
      if (role === 'admin' && me.role !== 'owner') throw new HttpError(403, 'FORBIDDEN', 'Hanya Owner yang boleh membuat Admin.');
      need((await repo.users.findByUsername(username)) === null, 'Username sudah dipakai.', 'CONFLICT');
      if (email) need((await repo.users.findByEmail(email)) === null, 'Email sudah dipakai.', 'CONFLICT');

      let temp = null;
      let password = b.password;
      if (password === undefined || password === '') {
        temp = sec.randomPassword();
        password = temp;
      } else {
        const problem = sec.passwordProblem(password, username);
        need(!problem, problem || '', 'WEAK_PASSWORD');
      }
      const perms = cleanPerms(b.permissions, me, DEFAULT_PERMS[role]);
      const id = await repo.users.create(
        { username, email, displayName, role, passwordHash: await sec.hashPassword(password, config.scryptN), mustChangePassword: true, createdBy: me.id },
        perms, now()
      );
      await audit('user.create', me, username, ip, 'role=' + role);
      const created = await repo.users.findById(id);
      return send(res, 201, { user: await userView(created), temporaryPassword: temp });
    }

    m = /^\/api\/users\/(\d+)$/.exec(path);
    if (m) {
      const target = await repo.users.findById(Number(m[1]));
      if (!target) throw new HttpError(404, 'NOT_FOUND', 'Akun tidak ditemukan.');

      if (method === 'GET') return send(res, 200, { user: await userView(target) });

      if (method === 'PATCH') {
        if (!canManage(me, target)) throw new HttpError(403, 'FORBIDDEN', 'Kamu tidak boleh mengubah akun ini.');
        const b = await readJson(req);
        const f = {};
        if (b.displayName !== undefined) {
          need(typeof b.displayName === 'string' && b.displayName.trim().length >= 1 && b.displayName.trim().length <= 60, 'Nama tampilan 1–60 karakter.');
          f.displayName = b.displayName.trim();
        }
        if (b.email !== undefined) {
          const e = typeof b.email === 'string' && b.email.trim() ? b.email.trim().toLowerCase() : null;
          need(e === null || (EMAIL_RE.test(e) && e.length <= 190), 'Format email tidak valid.');
          if (e) {
            const other = await repo.users.findByEmail(e);
            need(!other || other.id === target.id, 'Email sudah dipakai.', 'CONFLICT');
          }
          f.email = e;
        }
        if (b.role !== undefined) {
          if (me.role !== 'owner') throw new HttpError(403, 'FORBIDDEN', 'Hanya Owner yang boleh mengubah role.');
          need(b.role === 'user' || b.role === 'admin', 'Role harus user atau admin.');
          f.role = b.role;
        }
        if (b.status !== undefined) {
          need(b.status === 'active' || b.status === 'disabled', 'Status harus active atau disabled.');
          f.status = b.status;
        }
        const perms = b.permissions === undefined ? null : cleanPerms(b.permissions, me, null);
        await repo.users.update(target.id, f, now());
        if (perms) await repo.users.setPermissions(target.id, perms);
        if (f.status === 'disabled') await repo.sessions.removeAllForUser(target.id);
        const detail = Object.keys(f).concat(perms ? ['permissions'] : []).join(',');
        await audit(f.status === 'disabled' ? 'user.disable' : f.status === 'active' ? 'user.enable' : 'user.update', me, target.username, ip, detail);
        return send(res, 200, { user: await userView(await repo.users.findById(target.id)) });
      }

      if (method === 'DELETE') {
        if (me.role !== 'owner') throw new HttpError(403, 'FORBIDDEN', 'Hanya Owner yang boleh menghapus akun.');
        if (!canManage(me, target)) throw new HttpError(403, 'FORBIDDEN', 'Akun ini tidak bisa dihapus.');
        await repo.users.remove(target.id);
        await audit('user.delete', me, target.username, ip, null);
        return send(res, 200, { ok: true });
      }
    }

    m = /^\/api\/users\/(\d+)\/reset-password$/.exec(path);
    if (m && method === 'POST') {
      const target = await repo.users.findById(Number(m[1]));
      if (!target) throw new HttpError(404, 'NOT_FOUND', 'Akun tidak ditemukan.');
      if (!canManage(me, target)) throw new HttpError(403, 'FORBIDDEN', 'Kamu tidak boleh mereset password akun ini.');
      const temp = sec.randomPassword();
      await repo.users.setPassword(target.id, await sec.hashPassword(temp, config.scryptN), true, now());
      await repo.sessions.removeAllForUser(target.id);
      await audit('user.reset_password', me, target.username, ip, null);
      return send(res, 200, { temporaryPassword: temp });
    }

    if (key === 'GET /api/audit') {
      if (!(me.role === 'owner' || me.perms.includes('audit.view'))) throw new HttpError(403, 'FORBIDDEN', 'Kamu tidak punya izin melihat audit log.');
      const limit = Math.min(Math.max(parseInt(url.searchParams.get('limit') || '100', 10) || 100, 1), 500);
      const before = parseInt(url.searchParams.get('before') || '', 10) || null;
      const rows = await repo.audit.list(limit, before);
      return send(res, 200, {
        entries: rows.map((r) => ({
          id: Number(r.id), ts: Number(r.ts), actor: r.actor_username, action: r.action, target: r.target, ip: r.ip, detail: r.detail,
        })),
      });
    }

    throw new HttpError(404, 'NOT_FOUND', 'Tidak ditemukan.');
  }

  async function handler(req, res) {
    try {
      await dispatch(req, res);
    } catch (e) {
      if (e instanceof HttpError) {
        const headers = e.extra && e.extra.retryAfterSec ? { 'Retry-After': String(e.extra.retryAfterSec) } : {};
        if (e.extra && e.extra.close) {
          headers.Connection = 'close';
          res.on('finish', () => req.destroy());
        }
        const { close, ...pub } = e.extra || {};
        if (!res.headersSent) send(res, e.status, { error: e.code, message: e.message, ...pub }, headers);
      } else {
        console.error('Kesalahan tak terduga:', e);
        if (!res.headersSent) send(res, 500, { error: 'INTERNAL', message: 'Terjadi kesalahan di server.' });
      }
    }
  }

  return { handler, close: () => clearInterval(sweeper) };
}

module.exports = { createApp, HttpError };
