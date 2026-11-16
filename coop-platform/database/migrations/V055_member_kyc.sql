-- Know Your Customer: one current record per member (the valid ID presented, where the money comes from, politically-exposed status, risk rating and the next review date),
-- with an optional scan of the ID. The PostgreSQL copy is V018. Run with scripts/migrate.ps1. Idempotent.
CREATE TABLE IF NOT EXISTS ecis_member_kyc (
  branch_code     SMALLINT UNSIGNED NOT NULL,
  client_id       INT UNSIGNED NOT NULL,
  id_type         VARCHAR(40) NULL,
  id_number       VARCHAR(60) NULL,
  id_issuer       VARCHAR(80) NULL,
  id_expiry       DATE NULL,
  source_of_funds VARCHAR(255) NULL,
  pep             TINYINT NOT NULL DEFAULT 0,
  pep_note        VARCHAR(255) NULL,
  risk            VARCHAR(6) NULL,
  next_review     DATE NULL,
  remarks         VARCHAR(255) NULL,
  id_scan         MEDIUMBLOB NULL,
  id_scan_type    VARCHAR(20) NULL,
  updated_by      VARCHAR(60) NOT NULL,
  updated_at      DATETIME NOT NULL,
  PRIMARY KEY (branch_code, client_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

GRANT SELECT, INSERT, UPDATE ON ecis_member_kyc TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_member_kyc TO 'coop_app'@'%';
