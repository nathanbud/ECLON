-- Drawer controls for the front office: cash limits per drawer, end-of-day drawer counts with the difference recorded, running
-- drawer balances (so the head cashier's screen no longer adds up the whole ledger), supervisor overrides, and a key that stops
-- a hand-off being posted twice. Additive; touches no legacy table. Safe to repeat.

CREATE TABLE IF NOT EXISTS ecis_drawer_limit (
  branch_code SMALLINT UNSIGNED NOT NULL,
  event       TINYINT UNSIGNED NOT NULL,              -- 52 cashier, 53 teller
  number      SMALLINT UNSIGNED NOT NULL,             -- 0 = the limit for every drawer of this kind that has none of its own
  max_cash    DECIMAL(15,2) NOT NULL,
  updated_by  SMALLINT UNSIGNED NOT NULL,
  updated_at  DATETIME NOT NULL,
  PRIMARY KEY (branch_code, event, number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_drawer_close (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  branch_code SMALLINT UNSIGNED NOT NULL,
  tr_date     DATE NOT NULL,
  event       TINYINT UNSIGNED NOT NULL,
  number      SMALLINT UNSIGNED NOT NULL,
  closed_by   SMALLINT UNSIGNED NOT NULL,
  expected    DECIMAL(15,2) NOT NULL,                 -- what the ledger says, worked out when the count was submitted
  counted     DECIMAL(15,2) NOT NULL,
  variance    DECIMAL(15,2) NOT NULL,                 -- counted minus expected: negative is short, positive is over
  counts      VARCHAR(500) NOT NULL,
  reason      VARCHAR(255) NULL,
  closed_at   DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY ix_dc_day (branch_code, tr_date, event, number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_drawer_snapshot (
  branch_code SMALLINT UNSIGNED NOT NULL,
  event       TINYINT UNSIGNED NOT NULL,
  number      SMALLINT UNSIGNED NOT NULL,
  as_of       DATE NOT NULL,                          -- 31 December of the last year the balance covers: every entry of that year and before
  balance     DECIMAL(15,2) NOT NULL,
  PRIMARY KEY (branch_code, event, number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_fo_override (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  branch_code  SMALLINT UNSIGNED NOT NULL,
  draft_id     BIGINT UNSIGNED NULL,
  kind         VARCHAR(30) NOT NULL,                  -- e.g. OVER_LIMIT
  description  VARCHAR(255) NOT NULL,
  amount       DECIMAL(15,2) NOT NULL,
  limit_amount DECIMAL(15,2) NULL,
  requested_by SMALLINT UNSIGNED NOT NULL,
  requested_at DATETIME NOT NULL,
  status       VARCHAR(10) NOT NULL DEFAULT 'PENDING', -- PENDING, APPROVED, REJECTED
  decided_by   SMALLINT UNSIGNED NULL,
  decided_at   DATETIME NULL,
  note         VARCHAR(255) NULL,
  PRIMARY KEY (id),
  KEY ix_ov_status (branch_code, status),
  KEY ix_ov_draft (draft_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- A key the app sends with each hand-off so that a repeated click or a retry cannot post it twice.
SET @has_key = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ecis_cash_handover' AND column_name = 'request_key');
SET @sql = IF(@has_key = 0, 'ALTER TABLE ecis_cash_handover ADD COLUMN request_key VARCHAR(40) NULL, ADD UNIQUE KEY ux_ch_request (request_key)', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

GRANT INSERT, UPDATE ON iaccsbsucmpc01.ecis_drawer_limit    TO 'coop_app'@'localhost';
GRANT INSERT, UPDATE ON iaccsbsucmpc01.ecis_drawer_snapshot TO 'coop_app'@'localhost';
GRANT INSERT         ON iaccsbsucmpc01.ecis_drawer_close    TO 'coop_app'@'localhost';
GRANT INSERT, UPDATE ON iaccsbsucmpc01.ecis_fo_override     TO 'coop_app'@'localhost';
FLUSH PRIVILEGES;
