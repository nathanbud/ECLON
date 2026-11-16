-- The business day (Start Day and End Day); see the PostgreSQL copy V027. Run with scripts/migrate.ps1. Not idempotent: run once.
-- MySQL 5.6 has no partial index, so "one open day per branch" is kept by the application (it locks the branch's rows before starting a day).
CREATE TABLE IF NOT EXISTS ecis_business_day (
  branch_code     INT NOT NULL,
  business_date   DATE NOT NULL,
  status          VARCHAR(10) NOT NULL,
  opened_by       VARCHAR(60) NOT NULL,
  opened_at       DATETIME NOT NULL,
  closed_by       VARCHAR(60) NULL,
  closed_at       DATETIME NULL,
  override_reason VARCHAR(300) NULL,
  failed_checks   VARCHAR(2000) NULL,
  PRIMARY KEY (branch_code, business_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
GRANT SELECT, INSERT, UPDATE ON ecis_business_day TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_business_day TO 'coop_app'@'%';

CREATE TABLE IF NOT EXISTS ecis_business_day_log (
  id            BIGINT NOT NULL AUTO_INCREMENT,
  branch_code   INT NOT NULL,
  business_date DATE NOT NULL,
  action        VARCHAR(20) NOT NULL,
  username      VARCHAR(60) NOT NULL,
  at            DATETIME NOT NULL,
  detail        VARCHAR(2000) NULL,
  reason        VARCHAR(300) NULL,
  PRIMARY KEY (id),
  KEY ix_ecis_business_day_log_day (branch_code, business_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
GRANT SELECT, INSERT ON ecis_business_day_log TO 'coop_app'@'localhost';
GRANT SELECT, INSERT ON ecis_business_day_log TO 'coop_app'@'%';

GRANT UPDATE (AccessStartDay, AccessEndDay) ON access TO 'coop_app'@'localhost';
GRANT UPDATE (AccessStartDay, AccessEndDay) ON access TO 'coop_app'@'%';
