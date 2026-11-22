-- Department kinds and statements per unit; see the PostgreSQL copy V038. Run with scripts/migrate.ps1.
CREATE TABLE IF NOT EXISTS ecis_department_kind (
  dept_id    INT          NOT NULL PRIMARY KEY,
  kind       VARCHAR(20)  NOT NULL,
  updated_by VARCHAR(60)  NOT NULL,
  updated_at DATETIME(3)  NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_gl_year_dept_built (
  branch_code SMALLINT UNSIGNED NOT NULL,
  tr_year     SMALLINT UNSIGNED NOT NULL,
  built_by    VARCHAR(60) NOT NULL,
  built_at    DATETIME NOT NULL,
  PRIMARY KEY (branch_code, tr_year)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_gl_year_dept_balance (
  branch_code  SMALLINT UNSIGNED NOT NULL,
  tr_year      SMALLINT UNSIGNED NOT NULL,
  dept         INT NOT NULL,
  account_code VARCHAR(10) NOT NULL,
  net          DECIMAL(17,2) NOT NULL,
  PRIMARY KEY (branch_code, tr_year, dept, account_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

GRANT SELECT, INSERT, UPDATE ON ecis_department_kind TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_department_kind TO 'coop_app'@'%';
GRANT SELECT, INSERT, DELETE ON ecis_gl_year_dept_built TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, DELETE ON ecis_gl_year_dept_built TO 'coop_app'@'%';
GRANT SELECT, INSERT, DELETE ON ecis_gl_year_dept_balance TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, DELETE ON ecis_gl_year_dept_balance TO 'coop_app'@'%';
