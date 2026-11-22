-- Chart of accounts details: a contra account's normal balance and a description; see the PostgreSQL copy V039. Run with scripts/migrate.ps1.
CREATE TABLE IF NOT EXISTS ecis_coa_detail (
  branch_code    SMALLINT UNSIGNED NOT NULL,
  account_code   VARCHAR(10)  NOT NULL,
  normal_balance CHAR(1),
  description    VARCHAR(250),
  updated_by     VARCHAR(60)  NOT NULL,
  updated_at     DATETIME(3)  NOT NULL,
  PRIMARY KEY (branch_code, account_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
GRANT SELECT, INSERT, UPDATE ON ecis_coa_detail TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_coa_detail TO 'coop_app'@'%';
