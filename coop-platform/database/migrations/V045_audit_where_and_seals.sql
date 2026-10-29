-- Phase 18, audit and compliance. Every audit row now says WHERE it came from (the address and device of the request), and the log is sealed in chained blocks so a changed or removed row
-- can be detected even by someone with direct database access: each seal hashes a block of rows and the previous seal, and a nightly job (or an administrator) adds the next one.
-- The application's own database user can only INSERT into both tables: it can never update or delete a row. Run with scripts/migrate.ps1.
ALTER TABLE ecis_audit_log ADD COLUMN client_ip VARCHAR(45) NULL, ADD COLUMN client_info VARCHAR(200) NULL;

CREATE TABLE IF NOT EXISTS ecis_audit_seal (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  from_id         BIGINT UNSIGNED NOT NULL,        -- first audit row in the block
  to_id           BIGINT UNSIGNED NOT NULL,        -- last audit row in the block
  row_count       INT UNSIGNED NOT NULL,           -- how many rows the block held (ids have gaps where a change was rolled back)
  block_hash      CHAR(64) NOT NULL,               -- SHA-256 over the rows of the block, in order
  prev_seal_hash  CHAR(64) NOT NULL,               -- the previous seal's hash (64 zeros for the first)
  seal_hash       CHAR(64) NOT NULL,               -- SHA-256 over the previous seal, the range, the count and the block hash
  sealed_by       VARCHAR(60) NOT NULL,
  sealed_at       DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY ix_seal_to (to_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

GRANT SELECT, INSERT ON ecis_audit_seal TO 'coop_app'@'localhost';
GRANT SELECT, INSERT ON ecis_audit_seal TO 'coop_app'@'%';
