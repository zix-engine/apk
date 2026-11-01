'use strict';

const ALL_PERMISSIONS = ['server.view', 'server.manage', 'trading.use', 'backup.use', 'ai.use', 'audit.view'];

function mapUser(r) {
  if (!r) return null;
  return {
    id: Number(r.id),
    username: r.username,
    email: r.email || null,
    displayName: r.display_name,
    role: r.role,
    status: r.status,
    passwordHash: r.password_hash,
    mustChangePassword: Number(r.must_change_password) === 1,
    createdAt: Number(r.created_at),
    updatedAt: Number(r.updated_at),
    lastLoginAt: r.last_login_at == null ? null : Number(r.last_login_at),
    createdBy: r.created_by == null ? null : Number(r.created_by),
  };
}

/** Semua SQL aplikasi ada di sini dan memakai subset yang portabel (MariaDB & SQLite untuk tes). */
function createRepo(db) {
  const users = {
    async findById(id) {
      const rows = await db.all('SELECT * FROM users WHERE id = ?', [id]);
      return mapUser(rows[0]);
    },
    async findByUsername(username) {
      const rows = await db.all('SELECT * FROM users WHERE username = ?', [username]);
      return mapUser(rows[0]);
    },
    async findByEmail(email) {
      const rows = await db.all('SELECT * FROM users WHERE email = ?', [email]);
      return mapUser(rows[0]);
    },
    async list(query, limit = 200) {
      if (query) {
        const like = '%' + query.replace(/[%_\\]/g, '') + '%';
        const rows = await db.all(
          'SELECT * FROM users WHERE username LIKE ? OR display_name LIKE ? OR email LIKE ? ORDER BY id ASC LIMIT ?',
          [like, like, like, limit]
        );
        return rows.map(mapUser);
      }
      const rows = await db.all('SELECT * FROM users ORDER BY id ASC LIMIT ?', [limit]);
      return rows.map(mapUser);
    },
    async count() {
      const rows = await db.all('SELECT COUNT(*) AS n FROM users', []);
      return Number(rows[0].n);
    },
    async countByRole(role) {
      const rows = await db.all('SELECT COUNT(*) AS n FROM users WHERE role = ? AND status = ?', [role, 'active']);
      return Number(rows[0].n);
    },
    /** Membuat user + izin dalam satu transaksi. Mengembalikan id. */
    async create(u, perms, now) {
      return db.tx(async (t) => {
        const r = await t.run(
          'INSERT INTO users (username, email, display_name, role, status, password_hash, must_change_password, created_at, updated_at, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
          [u.username, u.email || null, u.displayName, u.role, 'active', u.passwordHash, u.mustChangePassword ? 1 : 0, now, now, u.createdBy || null]
        );
        for (const p of perms) {
          await t.run('INSERT INTO user_permissions (user_id, permission) VALUES (?, ?)', [r.insertId, p]);
        }
        return r.insertId;
      });
    },
    async update(id, f, now) {
      const sets = [];
      const vals = [];
      const map = { displayName: 'display_name', email: 'email', role: 'role', status: 'status' };
      for (const k of Object.keys(map)) {
        if (f[k] !== undefined) {
          sets.push(map[k] + ' = ?');
          vals.push(f[k]);
        }
      }
      sets.push('updated_at = ?');
      vals.push(now);
      vals.push(id);
      await db.run('UPDATE users SET ' + sets.join(', ') + ' WHERE id = ?', vals);
    },
    async setPassword(id, hash, mustChange, now) {
      await db.run('UPDATE users SET password_hash = ?, must_change_password = ?, updated_at = ? WHERE id = ?', [
        hash, mustChange ? 1 : 0, now, id,
      ]);
    },
    async touchLogin(id, now) {
      await db.run('UPDATE users SET last_login_at = ? WHERE id = ?', [now, id]);
    },
    async remove(id) {
      await db.run('DELETE FROM users WHERE id = ?', [id]);
    },
    async permissions(id) {
      const rows = await db.all('SELECT permission FROM user_permissions WHERE user_id = ? ORDER BY permission ASC', [id]);
      return rows.map((r) => r.permission);
    },
    async setPermissions(id, perms) {
      return db.tx(async (t) => {
        await t.run('DELETE FROM user_permissions WHERE user_id = ?', [id]);
        for (const p of perms) await t.run('INSERT INTO user_permissions (user_id, permission) VALUES (?, ?)', [id, p]);
      });
    },
  };

  const sessions = {
    async create(s) {
      const r = await db.run(
        'INSERT INTO sessions (user_id, access_hash, refresh_hash, access_expires, refresh_expires, device, ip, created_at, last_used_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)',
        [s.userId, s.accessHash, s.refreshHash, s.accessExpires, s.refreshExpires, s.device || null, s.ip || null, s.now, s.now]
      );
      return r.insertId;
    },
    async findByAccess(hash) {
      const rows = await db.all('SELECT * FROM sessions WHERE access_hash = ?', [hash]);
      return rows[0] || null;
    },
    async findByRefresh(hash) {
      const rows = await db.all('SELECT * FROM sessions WHERE refresh_hash = ?', [hash]);
      return rows[0] || null;
    },
    async rotate(id, accessHash, refreshHash, accessExpires, refreshExpires, now) {
      await db.run(
        'UPDATE sessions SET access_hash = ?, refresh_hash = ?, access_expires = ?, refresh_expires = ?, last_used_at = ? WHERE id = ?',
        [accessHash, refreshHash, accessExpires, refreshExpires, now, id]
      );
    },
    async touch(id, now) {
      await db.run('UPDATE sessions SET last_used_at = ? WHERE id = ?', [now, id]);
    },
    async listByUser(userId) {
      return db.all('SELECT id, device, ip, created_at, last_used_at FROM sessions WHERE user_id = ? ORDER BY last_used_at DESC', [userId]);
    },
    async remove(id, userId) {
      const r = await db.run('DELETE FROM sessions WHERE id = ? AND user_id = ?', [id, userId]);
      return r.changes > 0;
    },
    async removeAllForUser(userId, exceptId) {
      if (exceptId) await db.run('DELETE FROM sessions WHERE user_id = ? AND id <> ?', [userId, exceptId]);
      else await db.run('DELETE FROM sessions WHERE user_id = ?', [userId]);
    },
    async purgeExpired(now) {
      await db.run('DELETE FROM sessions WHERE refresh_expires < ?', [now]);
    },
  };

  const audit = {
    async add(e) {
      await db.run('INSERT INTO audit_log (ts, actor_id, actor_username, action, target, ip, detail) VALUES (?, ?, ?, ?, ?, ?, ?)', [
        e.ts, e.actorId || null, e.actorUsername || null, e.action, e.target || null, e.ip || null, e.detail ? String(e.detail).slice(0, 500) : null,
      ]);
    },
    async list(limit = 100, beforeId = null) {
      if (beforeId) {
        return db.all('SELECT * FROM audit_log WHERE id < ? ORDER BY id DESC LIMIT ?', [beforeId, limit]);
      }
      return db.all('SELECT * FROM audit_log ORDER BY id DESC LIMIT ?', [limit]);
    },
  };

  return { users, sessions, audit };
}

module.exports = { createRepo, ALL_PERMISSIONS };
