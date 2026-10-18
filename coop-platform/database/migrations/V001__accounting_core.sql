-- Accounting core (guide phase 6). Additive only: new ecis_* tables, no legacy table is altered.
-- Target: MySQL 5.6 schema iaccsbsucmpc01. Flyway cannot run on 5.6, so apply with the mysql client.
-- Idempotent: safe to re-run. NOT YET APPLIED.

CREATE TABLE IF NOT EXISTS ecis_accounting_period (
  id          INT UNSIGNED NOT NULL AUTO_INCREMENT,
  branch_code SMALLINT UNSIGNED NOT NULL,
  name        VARCHAR(40) NOT NULL,
  start_date  DATE NOT NULL,
  end_date    DATE NOT NULL,
  status      ENUM('OPEN','SOFT_CLOSED','CLOSED','LOCKED') NOT NULL DEFAULT 'OPEN',
  updated_by  VARCHAR(60) NOT NULL,
  updated_at  DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_period_range (branch_code, start_date, end_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_journal (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  branch_code   SMALLINT UNSIGNED NOT NULL,
  reference     VARCHAR(40) NOT NULL,
  journal_date  DATE NOT NULL,
  description   VARCHAR(255) DEFAULT NULL,
  status        ENUM('DRAFT','FOR_REVIEW','APPROVED','POSTED') NOT NULL DEFAULT 'DRAFT',
  created_by    VARCHAR(60) NOT NULL,
  created_at    DATETIME NOT NULL,
  approved_by   VARCHAR(60) DEFAULT NULL,
  approved_at   DATETIME DEFAULT NULL,
  posted_at     DATETIME DEFAULT NULL,
  reverses_id   BIGINT UNSIGNED DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_journal_ref (branch_code, reference),
  KEY ix_journal_date (branch_code, journal_date),
  CONSTRAINT fk_journal_reverses FOREIGN KEY (reverses_id) REFERENCES ecis_journal (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- MySQL 5.6 parses but ignores CHECK constraints; one-side-only is enforced in the service layer.
CREATE TABLE IF NOT EXISTS ecis_journal_line (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  journal_id   BIGINT UNSIGNED NOT NULL,
  line_no      SMALLINT UNSIGNED NOT NULL,
  account_code VARCHAR(10) NOT NULL,
  debit        DECIMAL(15,2) NOT NULL DEFAULT 0.00,
  credit       DECIMAL(15,2) NOT NULL DEFAULT 0.00,
  PRIMARY KEY (id),
  UNIQUE KEY uq_line (journal_id, line_no),
  CONSTRAINT fk_line_journal FOREIGN KEY (journal_id) REFERENCES ecis_journal (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_audit_log (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  at          DATETIME NOT NULL,
  username    VARCHAR(60) NOT NULL,
  action      VARCHAR(40) NOT NULL,
  entity      VARCHAR(40) NOT NULL,
  entity_id   VARCHAR(40) NOT NULL,
  prev_value  TEXT DEFAULT NULL,
  new_value   TEXT DEFAULT NULL,
  reason      VARCHAR(255) DEFAULT NULL,
  PRIMARY KEY (id),
  KEY ix_audit_entity (entity, entity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
