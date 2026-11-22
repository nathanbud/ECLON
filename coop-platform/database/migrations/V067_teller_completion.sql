-- The rest of the teller module: the cooperative's own cheque on a Check Voucher, the start-of-day drawer count, and the end-of-day jobs; see the
-- PostgreSQL copy V030 for what each table keeps. Run with scripts/migrate.ps1. Not idempotent: run once.
CREATE TABLE IF NOT EXISTS ecis_voucher_cheque (
  branch_code INT NOT NULL,
  tr_year     INT NOT NULL,
  tr_code     INT NOT NULL,
  tr_ctlno    BIGINT NOT NULL,
  bank_code   INT NOT NULL,
  cheque_no   VARCHAR(15) NOT NULL,
  cheque_date DATE NOT NULL,
  payee       VARCHAR(60) NOT NULL,
  amount      DECIMAL(15,2) NOT NULL,
  created_by  VARCHAR(60) NOT NULL,
  created_at  DATETIME NOT NULL,
  PRIMARY KEY (branch_code, tr_year, tr_code, tr_ctlno),
  UNIQUE KEY ux_voucher_cheque_number (branch_code, bank_code, cheque_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_drawer_open (
  branch_code   INT NOT NULL,
  business_date DATE NOT NULL,
  event         INT NOT NULL,
  drawer_number INT NOT NULL,
  access_id     INT NOT NULL,
  username      VARCHAR(60) NOT NULL,
  expected      DECIMAL(15,2) NOT NULL,
  counted       DECIMAL(15,2) NOT NULL,
  difference    DECIMAL(15,2) NOT NULL,
  counts        VARCHAR(400) NOT NULL,
  reason        VARCHAR(250) NULL,
  opened_at     DATETIME NOT NULL,
  PRIMARY KEY (branch_code, business_date, event, drawer_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_eod_run (
  id            BIGINT NOT NULL AUTO_INCREMENT,
  branch_code   INT NOT NULL,
  business_date DATE NOT NULL,
  status        VARCHAR(12) NOT NULL,
  started_by    VARCHAR(60) NOT NULL,
  started_at    DATETIME NOT NULL,
  finished_at   DATETIME NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_eod_run_day (branch_code, business_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_eod_step (
  run_id      BIGINT NOT NULL,
  step        VARCHAR(30) NOT NULL,
  status      VARCHAR(12) NOT NULL,
  attempts    INT NOT NULL,
  started_at  DATETIME NOT NULL,
  finished_at DATETIME NULL,
  result      VARCHAR(2000) NULL,
  error       VARCHAR(2000) NULL,
  PRIMARY KEY (run_id, step)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_eod_balance (
  branch_code   INT NOT NULL,
  business_date DATE NOT NULL,
  kind          VARCHAR(12) NOT NULL,
  item          VARCHAR(40) NOT NULL,
  name          VARCHAR(100) NULL,
  amount        DECIMAL(18,2) NOT NULL,
  accounts      INT NOT NULL DEFAULT 0,
  PRIMARY KEY (branch_code, business_date, kind, item)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_loan_aging (
  branch_code        INT NOT NULL,
  business_date      DATE NOT NULL,
  client_id          INT NOT NULL,
  slt_code           INT NOT NULL,
  ref_no             INT NOT NULL,
  outstanding        DECIMAL(15,2) NOT NULL,
  past_due_principal DECIMAL(15,2) NOT NULL,
  days_past_due      INT NOT NULL,
  past_due_since     DATE NULL,
  interest_due       DECIMAL(15,2) NOT NULL,
  penalty            DECIMAL(15,2) NULL,
  bucket             VARCHAR(12) NOT NULL,
  PRIMARY KEY (branch_code, business_date, slt_code, ref_no, client_id),
  KEY ix_loan_aging_bucket (branch_code, business_date, bucket)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

GRANT SELECT, INSERT ON ecis_voucher_cheque TO 'coop_app'@'localhost';
GRANT SELECT, INSERT ON ecis_voucher_cheque TO 'coop_app'@'%';
GRANT SELECT, INSERT ON ecis_drawer_open TO 'coop_app'@'localhost';
GRANT SELECT, INSERT ON ecis_drawer_open TO 'coop_app'@'%';
GRANT SELECT, INSERT, UPDATE ON ecis_eod_run TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_eod_run TO 'coop_app'@'%';
GRANT SELECT, INSERT, UPDATE ON ecis_eod_step TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_eod_step TO 'coop_app'@'%';
GRANT SELECT, INSERT, DELETE ON ecis_eod_balance TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, DELETE ON ecis_eod_balance TO 'coop_app'@'%';
GRANT SELECT, INSERT, DELETE ON ecis_loan_aging TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, DELETE ON ecis_loan_aging TO 'coop_app'@'%';
