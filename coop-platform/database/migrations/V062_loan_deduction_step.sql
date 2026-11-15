-- Profiling and Deduction as separate permissions and steps; see the PostgreSQL copy V025. Run with scripts/migrate.ps1. Not idempotent: run once.
ALTER TABLE access ADD COLUMN AccessCanDoLoanDeduction TINYINT NULL;
UPDATE access SET AccessCanDoLoanDeduction = COALESCE(AccessCanAddLoanApplication, 0);
GRANT UPDATE (AccessCanDoLoanDeduction) ON access TO 'coop_app'@'localhost';
GRANT UPDATE (AccessCanDoLoanDeduction) ON access TO 'coop_app'@'%';

CREATE TABLE IF NOT EXISTS ecis_loanapp_step (
  branch_code   INT NOT NULL,
  loanapp_id    BIGINT NOT NULL,
  profiled_by   VARCHAR(60) NOT NULL,
  profiled_at   DATETIME NOT NULL,
  deductions_by VARCHAR(60) NULL,
  deductions_at DATETIME NULL,
  PRIMARY KEY (branch_code, loanapp_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
GRANT SELECT, INSERT, UPDATE ON ecis_loanapp_step TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_loanapp_step TO 'coop_app'@'%';
