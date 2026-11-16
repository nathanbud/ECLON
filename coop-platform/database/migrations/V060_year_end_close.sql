-- Year-end closing of the books (Accounting > Year-end close); see the PostgreSQL copy V023 for what it is for. MySQL has no partial unique index, so
-- "one closing that is not cancelled per branch and year" is checked by the application under a row lock. Run with scripts/migrate.ps1. Idempotent.
CREATE TABLE IF NOT EXISTS ecis_year_end_close (
  id          BIGINT NOT NULL AUTO_INCREMENT,
  branch_code INT NOT NULL,
  tr_year     INT NOT NULL,
  status      VARCHAR(12) NOT NULL,
  surplus     DECIMAL(17,2) NOT NULL,
  line_count  INT NOT NULL,
  fingerprint VARCHAR(64) NOT NULL,
  prepared_by VARCHAR(60) NOT NULL,
  prepared_at DATETIME NOT NULL,
  decided_by  VARCHAR(60) NULL,
  decided_at  DATETIME NULL,
  reason      VARCHAR(500) NULL,
  tr_ctlno    BIGINT NULL,
  PRIMARY KEY (id),
  KEY ecis_year_end_close_year (branch_code, tr_year)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

GRANT SELECT, INSERT, UPDATE ON ecis_year_end_close TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_year_end_close TO 'coop_app'@'%';
GRANT UPDATE ON ecis_gl_year_balance TO 'coop_app'@'localhost';
GRANT UPDATE ON ecis_gl_year_balance TO 'coop_app'@'%';
