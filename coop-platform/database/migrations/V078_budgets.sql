-- Budgets: the annual budget of each income and expense account; see the PostgreSQL copy V041. Run with scripts/migrate.ps1.
CREATE TABLE IF NOT EXISTS ecis_budget (
  branch_code  SMALLINT UNSIGNED NOT NULL,
  budget_year  SMALLINT UNSIGNED NOT NULL,
  account_code VARCHAR(10)   NOT NULL,
  amount       DECIMAL(17,2) NOT NULL,
  updated_by   VARCHAR(60)   NOT NULL,
  updated_at   DATETIME(3)   NOT NULL,
  PRIMARY KEY (branch_code, budget_year, account_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
GRANT SELECT, INSERT, UPDATE, DELETE ON ecis_budget TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE, DELETE ON ecis_budget TO 'coop_app'@'%';
