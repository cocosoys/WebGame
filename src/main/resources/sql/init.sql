-- WebGame MySQL 初始化脚本（SOYS DataSpec.sqlRoots / auto.ops.init 使用）
-- 仅 MySQL；表名与实体 @TableName 一致，字段与 ORM 列名一致。
-- 先由 WebGameDataBootstrap 直接执行一遍，再交给 SOYS AutoOps 幂等执行。

CREATE TABLE IF NOT EXISTS `eagler-clients` (
  `id` VARCHAR(64) NOT NULL,
  `mc_versions` TEXT NULL,
  `eagler_protocol` INT NOT NULL DEFAULT 0,
  `enabled` TINYINT NOT NULL DEFAULT 1,
  `order` INT NOT NULL DEFAULT 0,
  `package_id` BIGINT NULL,
  `aliases` TEXT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `client-packages` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `package_type` VARCHAR(16) NOT NULL DEFAULT 'cloud',
  `name` VARCHAR(255) NULL,
  `version` VARCHAR(255) NULL,
  `file` VARCHAR(255) NULL,
  `entry_path` VARCHAR(255) NULL,
  `target_dir` VARCHAR(255) NULL,
  `external_url` TEXT NULL,
  `sha256` VARCHAR(255) NULL,
  `size` BIGINT NOT NULL DEFAULT 0,
  `files` INT NOT NULL DEFAULT 0,
  `uploaded_at` VARCHAR(255) NULL,
  `enabled` TINYINT NOT NULL DEFAULT 1,
  `source` VARCHAR(255) NULL,
  `notes` TEXT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `cloud-clients` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(255) NULL,
  `enabled` TINYINT NOT NULL DEFAULT 1,
  `order` INT NOT NULL DEFAULT 0,
  `aliases` TEXT NULL,
  `pool` VARCHAR(255) NULL,
  `admin_scope` VARCHAR(255) NULL,
  `ip` VARCHAR(255) NULL,
  `port` INT NOT NULL DEFAULT 0,
  `tls` TINYINT NOT NULL DEFAULT 0,
  `tls_cert_sha256` VARCHAR(255) NULL,
  `tls_cert_pem` TEXT NULL,
  `tls_cert_subject` VARCHAR(255) NULL,
  `tls_cert_expires_at` VARCHAR(255) NULL,
  `status` VARCHAR(255) NULL,
  `version` VARCHAR(255) NULL,
  `uptime_seconds` BIGINT NOT NULL DEFAULT 0,
  `last_report` VARCHAR(255) NULL,
  `last_ok` VARCHAR(255) NULL,
  `last_check` VARCHAR(255) NULL,
  `fail_count` INT NOT NULL DEFAULT 0,
  `cloud_key` VARCHAR(255) NULL,
  `latency_ms` BIGINT NOT NULL DEFAULT 0,
  `health_reason` TEXT NULL,
  `package_id` BIGINT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
