'use strict';
const fs = require('fs');

/** Parser .env sederhana (KEY=VALUE, komentar #). Tidak menimpa variabel yang sudah ada. */
function loadEnvFile(file) {
  if (!fs.existsSync(file)) return;
  for (const raw of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const i = line.indexOf('=');
    if (i < 1) continue;
    const key = line.slice(0, i).trim();
    let val = line.slice(i + 1).trim();
    if ((val.startsWith('"') && val.endsWith('"')) || (val.startsWith("'") && val.endsWith("'"))) {
      val = val.slice(1, -1);
    }
    if (process.env[key] === undefined) process.env[key] = val;
  }
}

const int = (v, d) => {
  const n = parseInt(v, 10);
  return Number.isFinite(n) ? n : d;
};

function fromEnv(env = process.env) {
  return {
    port: int(env.PORT, 3000),
    host: env.HOST || '127.0.0.1',
    trustProxy: env.TRUST_PROXY === '1',
    db: {
      host: env.DB_HOST || '127.0.0.1',
      port: int(env.DB_PORT, 3306),
      user: env.DB_USER,
      password: env.DB_PASSWORD,
      database: env.DB_NAME,
    },
    scryptN: int(env.SCRYPT_N, 32768),
    accessTtlMs: int(env.ACCESS_TTL_SEC, 900) * 1000,
    refreshTtlMs: int(env.REFRESH_TTL_DAYS, 30) * 86400000,
    loginIpMax: int(env.LOGIN_IP_MAX, 30),
    loginIpWindowMs: 10 * 60 * 1000,
    apiIpMax: int(env.API_IP_MAX, 240),
    lockMaxFails: int(env.LOCK_MAX_FAILS, 5),
    lockMs: 15 * 60 * 1000,
  };
}

/** Pastikan konfigurasi wajib terisi (dipanggil saat server produksi mulai). */
function validate(cfg) {
  const missing = [];
  if (!cfg.db.user) missing.push('DB_USER');
  if (!cfg.db.password) missing.push('DB_PASSWORD');
  if (!cfg.db.database) missing.push('DB_NAME');
  if (missing.length) throw new Error('Konfigurasi .env belum lengkap: ' + missing.join(', '));
  return cfg;
}

module.exports = { loadEnvFile, fromEnv, validate };
