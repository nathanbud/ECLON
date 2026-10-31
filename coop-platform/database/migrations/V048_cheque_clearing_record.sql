-- Cheque clearing record. The old system keeps only a cleared flag on transck (CKISCLEARED) and never fills CKCLR_DATE, so the day a cheque cleared, who confirmed it and the bank's
-- reference lived only in the audit log. One row per cleared cheque, written in the same transaction as the flag. Insert only: a clearing is never edited; a cheque that comes back
-- is handled by the returned-cheque process. Run with scripts/migrate.ps1.

CREATE TABLE IF NOT EXISTS ecis_cheque_clearing (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tr_year         SMALLINT UNSIGNED NOT NULL,     -- the receipt's key in transck: year, control number and cheque sequence (Cash Receipts, type 10)
  ctl_no          INT UNSIGNED NOT NULL,
  seq_no          INT UNSIGNED NOT NULL,
  cleared_on      DATE NOT NULL,                  -- the day the bank released the money (not the day it was keyed in)
  bank_reference  VARCHAR(40) NULL,               -- the bank's reference or deposit slip number, when there is one
  early_reason    VARCHAR(250) NULL,              -- why it was cleared before its due date
  cleared_by      VARCHAR(60) NOT NULL,
  recorded_at     DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_cc_cheque (tr_year, ctl_no, seq_no),
  KEY ix_cc_on (cleared_on)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- MySQL 5.6 grants on one table at a time.
GRANT SELECT, INSERT ON ecis_cheque_clearing TO 'coop_app'@'localhost';
GRANT SELECT, INSERT ON ecis_cheque_clearing TO 'coop_app'@'%';
