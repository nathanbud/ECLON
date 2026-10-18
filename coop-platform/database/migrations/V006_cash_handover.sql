-- Cash handover (vault -> head cashier -> tellers, and back). A hand-off between two people is first recorded here as
-- PENDING; when the receiver counts it and it agrees, it is POSTED to the ledger as a legacy "Cash Transfer" (type 31 or 32).
-- Moves to and from the vault are posted at once by the head cashier.
--
-- THIS FILE GIVES THE APP ITS FIRST WRITE ACCESS TO THE LEDGER (transsum, transdt, gltrans, denomsum, denomdtl).
-- Run it as an administrator (root), and try it on a copy of the database first (change the schema name below).
-- Posted rows are never updated or deleted by the app: INSERT only. Safe to repeat.

CREATE TABLE IF NOT EXISTS ecis_cash_handover (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  branch_code   SMALLINT UNSIGNED NOT NULL,
  tr_code       TINYINT UNSIGNED NOT NULL,            -- 31 from the vault, 32 anything else
  from_event    TINYINT UNSIGNED NOT NULL,            -- 50 vault, 52 cashier, 53 teller
  from_number   SMALLINT UNSIGNED NOT NULL,
  to_event      TINYINT UNSIGNED NOT NULL,
  to_number     SMALLINT UNSIGNED NOT NULL,
  amount        DECIMAL(15,2) NOT NULL,
  denominations VARCHAR(500) NOT NULL,                -- JSON: {"<denomtype id>": quantity}
  remarks       VARCHAR(200) NULL,
  status        VARCHAR(12) NOT NULL DEFAULT 'PENDING', -- PENDING, POSTED, DISPUTED, CANCELLED
  issued_by     SMALLINT UNSIGNED NOT NULL,           -- access.AccessID
  issued_at     DATETIME NOT NULL,
  settled_by    SMALLINT UNSIGNED NULL,
  settled_at    DATETIME NULL,
  counted       DECIMAL(15,2) NULL,
  note          VARCHAR(255) NULL,
  tr_year       SMALLINT UNSIGNED NULL,
  tr_ctlno      INT UNSIGNED NULL,
  PRIMARY KEY (id),
  KEY ix_ch_to (branch_code, to_event, to_number, status),
  KEY ix_ch_from (branch_code, from_event, from_number, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

GRANT INSERT, UPDATE ON iaccsbsucmpc01.ecis_cash_handover TO 'coop_app'@'localhost';
GRANT INSERT ON iaccsbsucmpc01.transsum TO 'coop_app'@'localhost';
GRANT INSERT ON iaccsbsucmpc01.transdt  TO 'coop_app'@'localhost';
GRANT INSERT ON iaccsbsucmpc01.gltrans  TO 'coop_app'@'localhost';
GRANT INSERT ON iaccsbsucmpc01.denomsum TO 'coop_app'@'localhost';
GRANT INSERT ON iaccsbsucmpc01.denomdtl TO 'coop_app'@'localhost';
FLUSH PRIVILEGES;
