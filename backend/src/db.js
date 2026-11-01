'use strict';

/**
 * Adapter MariaDB/MySQL (mysql2). Antarmuka yang dipakai repo:
 *   all(sql, params) -> rows[]      run(sql, params) -> { insertId, changes }
 *   tx(fn) -> hasil fn(txDb), otomatis commit / rollback
 * Memakai pool.query (bukan execute) agar "LIMIT ?" berfungsi.
 */
function createMysql(cfg) {
  const mysql = require('mysql2/promise');
  const pool = mysql.createPool({
    host: cfg.host,
    port: cfg.port,
    user: cfg.user,
    password: cfg.password,
    database: cfg.database,
    connectionLimit: 10,
    charset: 'utf8mb4',
    supportBigNumbers: true,
    bigNumberStrings: false,
    waitForConnections: true,
  });

  const wrap = (conn) => ({
    async all(sql, params = []) {
      const [rows] = await conn.query(sql, params);
      return rows;
    },
    async run(sql, params = []) {
      const [res] = await conn.query(sql, params);
      return { insertId: Number(res.insertId || 0), changes: Number(res.affectedRows || 0) };
    },
  });

  const base = wrap(pool);
  return {
    ...base,
    async tx(fn) {
      const conn = await pool.getConnection();
      try {
        await conn.beginTransaction();
        const result = await fn(wrap(conn));
        await conn.commit();
        return result;
      } catch (e) {
        try {
          await conn.rollback();
        } catch (_) {
          /* abaikan */
        }
        throw e;
      } finally {
        conn.release();
      }
    },
    async close() {
      await pool.end();
    },
  };
}

module.exports = { createMysql };
