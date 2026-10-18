-- Bank reconciliation (guide phase 9). The old system has no reconciliation data (bankrecon_setup is empty, DTISCLEARED is unused),
-- so a reconciliation is kept in new tables and the legacy ledger is never changed. A reconciliation states the bank's balance on a
-- date and lists the ledger entries the bank has not cleared yet (outstanding); it is reconciled when
-- book balance - outstanding = statement balance. Run by an administrator. Idempotent.
CREATE TABLE IF NOT EXISTS ecis_bank_recon (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  branch_code       SMALLINT UNSIGNED NOT NULL,
  bank_id           SMALLINT UNSIGNED NOT NULL,          -- bankcode.BCID
  statement_date    DATE NOT NULL,
  statement_balance DECIMAL(17,2) NOT NULL,
  book_balance      DECIMAL(17,2) NOT NULL,              -- ledger balance of the bank's account on that date
  outstanding_total DECIMAL(17,2) NOT NULL,
  difference        DECIMAL(17,2) NOT NULL,              -- statement - (book - outstanding); zero when reconciled
  status            VARCHAR(10) NOT NULL DEFAULT 'DRAFT', -- DRAFT, FINAL
  note              VARCHAR(255) NULL,
  prepared_by       VARCHAR(60) NOT NULL,
  prepared_at       DATETIME NOT NULL,
  finalized_by      VARCHAR(60) NULL,
  finalized_at      DATETIME NULL,
  PRIMARY KEY (id),
  KEY ix_recon_bank (branch_code, bank_id, status, statement_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- the ledger entries (transdt rows) still outstanding at the statement date
CREATE TABLE IF NOT EXISTS ecis_bank_recon_item (
  recon_id  BIGINT UNSIGNED NOT NULL,
  tr_year   SMALLINT UNSIGNED NOT NULL,
  tr_module TINYINT UNSIGNED NOT NULL,
  tr_code   TINYINT UNSIGNED NOT NULL,
  tr_ctlno  INT UNSIGNED NOT NULL,
  seq_no    SMALLINT UNSIGNED NOT NULL,
  amount    DECIMAL(17,2) NOT NULL,
  PRIMARY KEY (recon_id, tr_year, tr_module, tr_code, tr_ctlno, seq_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

GRANT SELECT, INSERT, UPDATE ON ecis_bank_recon TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, DELETE ON ecis_bank_recon_item TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_bank_recon TO 'coop_app'@'%';
GRANT SELECT, INSERT, DELETE ON ecis_bank_recon_item TO 'coop_app'@'%';
