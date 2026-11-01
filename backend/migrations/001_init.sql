-- ZixFunction API — skema awal (MariaDB 10.5+ / MySQL 8). Semua waktu = epoch milidetik (BIGINT).
CREATE TABLE IF NOT EXISTS users (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(32) NOT NULL,
  email VARCHAR(190) NULL,
  display_name VARCHAR(60) NOT NULL,
  role ENUM('owner','admin','user') NOT NULL DEFAULT 'user',
  status ENUM('active','disabled') NOT NULL DEFAULT 'active',
  password_hash VARCHAR(255) NOT NULL,
  must_change_password TINYINT(1) NOT NULL DEFAULT 0,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  last_login_at BIGINT NULL,
  created_by BIGINT UNSIGNED NULL,
  UNIQUE KEY uq_users_username (username),
  UNIQUE KEY uq_users_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS user_permissions (
  user_id BIGINT UNSIGNED NOT NULL,
  permission VARCHAR(40) NOT NULL,
  PRIMARY KEY (user_id, permission),
  CONSTRAINT fk_perm_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sessions (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  access_hash CHAR(64) NOT NULL,
  refresh_hash CHAR(64) NOT NULL,
  access_expires BIGINT NOT NULL,
  refresh_expires BIGINT NOT NULL,
  device VARCHAR(120) NULL,
  ip VARCHAR(64) NULL,
  created_at BIGINT NOT NULL,
  last_used_at BIGINT NOT NULL,
  UNIQUE KEY uq_sessions_access (access_hash),
  UNIQUE KEY uq_sessions_refresh (refresh_hash),
  KEY idx_sessions_user (user_id),
  CONSTRAINT fk_sess_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS audit_log (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  ts BIGINT NOT NULL,
  actor_id BIGINT UNSIGNED NULL,
  actor_username VARCHAR(32) NULL,
  action VARCHAR(60) NOT NULL,
  target VARCHAR(120) NULL,
  ip VARCHAR(64) NULL,
  detail VARCHAR(500) NULL,
  KEY idx_audit_ts (ts),
  KEY idx_audit_actor (actor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
