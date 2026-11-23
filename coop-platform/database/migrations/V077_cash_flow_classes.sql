-- Statement of Cash Flows: the class of each balance-sheet account or heading; see the PostgreSQL copy V040. Run with scripts/migrate.ps1.
CREATE TABLE IF NOT EXISTS ecis_cash_flow_class (
  branch_code  SMALLINT UNSIGNED NOT NULL,
  account_code VARCHAR(10)  NOT NULL,
  category     VARCHAR(12)  NOT NULL,
  updated_by   VARCHAR(60)  NOT NULL,
  updated_at   DATETIME(3)  NOT NULL,
  PRIMARY KEY (branch_code, account_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
GRANT SELECT, INSERT, UPDATE, DELETE ON ecis_cash_flow_class TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE, DELETE ON ecis_cash_flow_class TO 'coop_app'@'%';
