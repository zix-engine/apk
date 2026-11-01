'use strict';
const crypto = require('crypto');
const { promisify } = require('util');
const scrypt = promisify(crypto.scrypt);

const COMMON = new Set([
  'password123', 'password1234', '1234567890', 'qwerty12345', 'admin12345', 'zixfunction1', 'welcome1234', 'passw0rd123',
]);

/** Hash password dengan scrypt. Format: scrypt$N$r$p$salt$hash (base64). */
async function hashPassword(password, N = 32768) {
  const salt = crypto.randomBytes(16);
  const r = 8;
  const p = 1;
  const key = await scrypt(password, salt, 64, { N, r, p, maxmem: 128 * N * r + 1024 * 1024 });
  return ['scrypt', N, r, p, salt.toString('base64'), key.toString('base64')].join('$');
}

async function verifyPassword(password, stored) {
  try {
    const parts = String(stored).split('$');
    if (parts.length !== 6 || parts[0] !== 'scrypt') return false;
    const N = parseInt(parts[1], 10);
    const r = parseInt(parts[2], 10);
    const p = parseInt(parts[3], 10);
    const salt = Buffer.from(parts[4], 'base64');
    const expected = Buffer.from(parts[5], 'base64');
    const key = await scrypt(password, salt, expected.length, { N, r, p, maxmem: 128 * N * r + 1024 * 1024 });
    return key.length === expected.length && crypto.timingSafeEqual(key, expected);
  } catch (e) {
    return false;
  }
}

const sha256 = (s) => crypto.createHash('sha256').update(s).digest('hex');
const newToken = () => crypto.randomBytes(32).toString('base64url');

/** Kebijakan password. Mengembalikan pesan error (Indonesia) atau null bila valid. */
function passwordProblem(password, username) {
  if (typeof password !== 'string') return 'Password wajib diisi.';
  if (password.length < 10) return 'Password minimal 10 karakter.';
  if (password.length > 128) return 'Password maksimal 128 karakter.';
  if (!/[A-Za-z]/.test(password) || !/[0-9]/.test(password)) return 'Password harus mengandung huruf dan angka.';
  if (username && password.toLowerCase().includes(String(username).toLowerCase())) return 'Password tidak boleh mengandung username.';
  if (COMMON.has(password.toLowerCase())) return 'Password terlalu umum.';
  return null;
}

/** Password sementara acak (tanpa karakter ambigu), selalu berisi huruf dan angka. */
function randomPassword(len = 14) {
  const letters = 'abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ';
  const digits = '23456789';
  const all = letters + digits;
  const pick = (set) => set[crypto.randomInt(set.length)];
  const chars = [pick(letters), pick(digits)];
  while (chars.length < len) chars.push(pick(all));
  for (let i = chars.length - 1; i > 0; i--) {
    const j = crypto.randomInt(i + 1);
    [chars[i], chars[j]] = [chars[j], chars[i]];
  }
  return chars.join('');
}

/** Pembatas laju sederhana (jendela geser) di memori. */
class Limiter {
  constructor(max, windowMs) {
    this.max = max;
    this.windowMs = windowMs;
    this.hits = new Map();
  }
  hit(key, now = Date.now()) {
    const arr = (this.hits.get(key) || []).filter((t) => now - t < this.windowMs);
    if (arr.length >= this.max) {
      this.hits.set(key, arr);
      return { ok: false, retryMs: this.windowMs - (now - arr[0]) };
    }
    arr.push(now);
    this.hits.set(key, arr);
    return { ok: true, retryMs: 0 };
  }
  sweep(now = Date.now()) {
    for (const [k, arr] of this.hits) {
      const fresh = arr.filter((t) => now - t < this.windowMs);
      if (fresh.length) this.hits.set(k, fresh);
      else this.hits.delete(k);
    }
  }
}

/** Penguncian login: N kegagalan beruntun per kunci (username) → terkunci sementara. */
class LoginGuard {
  constructor(maxFails, lockMs) {
    this.maxFails = maxFails;
    this.lockMs = lockMs;
    this.state = new Map();
  }
  lockedMs(key, now = Date.now()) {
    const s = this.state.get(key);
    if (s && s.until > now) return s.until - now;
    return 0;
  }
  fail(key, now = Date.now()) {
    const s = this.state.get(key) || { fails: 0, until: 0 };
    if (s.until && s.until <= now) {
      s.fails = 0;
      s.until = 0;
    }
    s.fails += 1;
    if (s.fails >= this.maxFails) s.until = now + this.lockMs;
    this.state.set(key, s);
    return s.until > now;
  }
  success(key) {
    this.state.delete(key);
  }
  sweep(now = Date.now()) {
    for (const [k, s] of this.state) if (s.until && s.until <= now) this.state.delete(k);
  }
}

module.exports = { hashPassword, verifyPassword, sha256, newToken, passwordProblem, randomPassword, Limiter, LoginGuard };
