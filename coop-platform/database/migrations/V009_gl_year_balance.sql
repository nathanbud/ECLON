-- Closed-year ledger balances (guide step 6.7). The legacy ledger (gltrans, ~14M rows) has no opening-balance
-- table, so a statement "as at" a date needs every earlier year. Each finished year is summed once and kept here;
-- the current year is read live. Insert-only for the app. Idempotent.
CREATE TABLE IF NOT EXISTS ecis_gl_year_built (
  branch_code SMALLINT UNSIGNED NOT NULL,
  tr_year     SMALLINT UNSIGNED NOT NULL,
  built_by    VARCHAR(60) NOT NULL,
  built_at    DATETIME NOT NULL,
  PRIMARY KEY (branch_code, tr_year)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- net = sum(debit) + sum(credit); credits are stored negative in gltrans, so a debit balance is positive.
CREATE TABLE IF NOT EXISTS ecis_gl_year_balance (
  branch_code  SMALLINT UNSIGNED NOT NULL,
  tr_year      SMALLINT UNSIGNED NOT NULL,
  account_code VARCHAR(10) NOT NULL,
  net          DECIMAL(17,2) NOT NULL,
  PRIMARY KEY (branch_code, tr_year, account_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

GRANT SELECT, INSERT ON ecis_gl_year_built TO 'coop_app'@'%';
GRANT SELECT, INSERT ON ecis_gl_year_balance TO 'coop_app'@'%';
