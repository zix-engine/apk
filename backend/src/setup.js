'use strict';
const sec = require('./security');

const USERNAME_RE = /^[a-z0-9._-]{3,32}$/;

/** Membuat Owner PERTAMA. Menolak bila sudah ada owner (tidak ada jalur HTTP untuk ini, sengaja). */
async function createFirstOwner(repo, config, { username, displayName, password }, now = Date.now()) {
  const u = String(username || '').trim().toLowerCase();
  if (!USERNAME_RE.test(u)) throw new Error('Username 3–32 karakter: huruf kecil, angka, titik, minus, garis bawah.');
  const name = String(displayName || '').trim() || u;
  if (name.length > 60) throw new Error('Nama tampilan maksimal 60 karakter.');
  const problem = sec.passwordProblem(password, u);
  if (problem) throw new Error(problem);
  const existing = await repo.users.countByRole('owner');
  if (existing > 0) throw new Error('Owner sudah ada. Gunakan reset-password untuk mengganti passwordnya.');
  if (await repo.users.findByUsername(u)) throw new Error('Username sudah dipakai.');
  const id = await repo.users.create(
    { username: u, email: null, displayName: name, role: 'owner', passwordHash: await sec.hashPassword(password, config.scryptN), mustChangePassword: false },
    [], now
  );
  await repo.audit.add({ ts: now, actorUsername: 'cli', action: 'owner.create', target: u, detail: 'dibuat lewat CLI' });
  return id;
}

/** Reset password darurat lewat CLI di server. Mencabut semua sesi akun itu. */
async function resetPassword(repo, config, username, password, now = Date.now()) {
  const u = String(username || '').trim().toLowerCase();
  const user = await repo.users.findByUsername(u);
  if (!user) throw new Error('Akun tidak ditemukan.');
  const problem = sec.passwordProblem(password, u);
  if (problem) throw new Error(problem);
  await repo.users.setPassword(user.id, await sec.hashPassword(password, config.scryptN), false, now);
  await repo.sessions.removeAllForUser(user.id);
  await repo.audit.add({ ts: now, actorUsername: 'cli', action: 'password.reset_cli', target: u, detail: 'reset lewat CLI' });
}

module.exports = { createFirstOwner, resetPassword };
