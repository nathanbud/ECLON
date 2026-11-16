-- Stored yearly distributions (interest on share capital and patronage refund) and their settings; see the PostgreSQL copy V029.
-- Run with scripts/migrate.ps1. Not idempotent: run once. MySQL 5.6 has no partial index, so "one live distribution per year" is kept by the application.
CREATE TABLE IF NOT EXISTS ecis_distribution_policy (
  branch_code       INT NOT NULL,
  patronage_events  VARCHAR(100) NOT NULL,
  eligible_statuses VARCHAR(200) NULL,
  isc_types         VARCHAR(400) NULL,
  pr_types          VARCHAR(400) NULL,
  min_average_share DECIMAL(15,2) NOT NULL DEFAULT 0,
  updated_by        VARCHAR(60) NOT NULL,
  updated_at        DATETIME NOT NULL,
  PRIMARY KEY (branch_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
INSERT INTO ecis_distribution_policy (branch_code, patronage_events, updated_by, updated_at) VALUES (1, '23,24,31', 'setup', NOW());

CREATE TABLE IF NOT EXISTS ecis_distribution_fund (
  id          BIGINT NOT NULL AUTO_INCREMENT,
  branch_code INT NOT NULL,
  name        VARCHAR(80) NOT NULL,
  percent     DECIMAL(7,4) NOT NULL,
  account     VARCHAR(20) NULL,
  position    INT NOT NULL,
  active      TINYINT NOT NULL DEFAULT 1,
  updated_by  VARCHAR(60) NOT NULL,
  updated_at  DATETIME NOT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_distribution_batch (
  id                  BIGINT NOT NULL AUTO_INCREMENT,
  branch_code         INT NOT NULL,
  year                INT NOT NULL,
  cutoff              DATE NOT NULL,
  method              VARCHAR(12) NOT NULL,
  status              VARCHAR(12) NOT NULL,
  net_surplus         DECIMAL(18,2) NULL,
  available           DECIMAL(18,2) NULL,
  isc_allocation      DECIMAL(18,2) NULL,
  pr_allocation       DECIMAL(18,2) NULL,
  approval_ref        VARCHAR(200) NULL,
  dividend_rate       DECIMAL(20,10) NOT NULL,
  patronage_rate      DECIMAL(20,10) NOT NULL,
  mutual_benefit      DECIMAL(15,2) NULL,
  hospitalization     DECIMAL(15,2) NULL,
  overrides           INT NOT NULL DEFAULT 0,
  total_average_share DECIMAL(18,2) NOT NULL,
  total_patronage     DECIMAL(18,2) NOT NULL,
  members             INT NOT NULL,
  total_isc           DECIMAL(18,2) NOT NULL,
  total_pr            DECIMAL(18,2) NOT NULL,
  total               DECIMAL(18,2) NOT NULL,
  held                INT NOT NULL,
  excluded            INT NOT NULL,
  policy              VARCHAR(1000) NOT NULL,
  prepared_by         VARCHAR(60) NOT NULL,
  prepared_at         DATETIME NOT NULL,
  submitted_by        VARCHAR(60) NULL,
  submitted_at        DATETIME NULL,
  decided_by          VARCHAR(60) NULL,
  decided_at          DATETIME NULL,
  decision_note       VARCHAR(300) NULL,
  superseded_by       BIGINT NULL,
  encoded_by          VARCHAR(60) NULL,
  encoded_at          DATETIME NULL,
  voucher_year        INT NULL,
  voucher_first       BIGINT NULL,
  voucher_last        BIGINT NULL,
  PRIMARY KEY (id),
  KEY ix_ecis_distribution_batch_year (branch_code, year)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_distribution_batch_fund (
  batch_id BIGINT NOT NULL, position INT NOT NULL, name VARCHAR(80) NOT NULL, percent DECIMAL(7,4) NOT NULL, amount DECIMAL(18,2) NOT NULL,
  PRIMARY KEY (batch_id, position)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
CREATE TABLE IF NOT EXISTS ecis_distribution_member (
  batch_id BIGINT NOT NULL, client_id INT NOT NULL, average_share DECIMAL(18,2) NOT NULL, qualified_patronage DECIMAL(18,2) NOT NULL,
  isc DECIMAL(15,2) NOT NULL, pr DECIMAL(15,2) NOT NULL, total DECIMAL(15,2) NOT NULL, mutual_benefit DECIMAL(15,2) NOT NULL,
  hospitalization DECIMAL(15,2) NOT NULL, rest DECIMAL(15,2) NOT NULL,
  PRIMARY KEY (batch_id, client_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
CREATE TABLE IF NOT EXISTS ecis_distribution_credit (
  batch_id BIGINT NOT NULL, seq INT NOT NULL, client_id INT NOT NULL, slc_code INT NOT NULL, slt_code INT NOT NULL, ref_no INT NOT NULL,
  kind VARCHAR(20) NOT NULL, amount DECIMAL(15,2) NOT NULL,
  PRIMARY KEY (batch_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
CREATE TABLE IF NOT EXISTS ecis_distribution_held (
  batch_id BIGINT NOT NULL, client_id INT NOT NULL, total DECIMAL(15,2) NOT NULL, reason VARCHAR(200) NOT NULL,
  PRIMARY KEY (batch_id, client_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
CREATE TABLE IF NOT EXISTS ecis_distribution_patronage (
  batch_id BIGINT NOT NULL, event INT NOT NULL, amount DECIMAL(18,2) NOT NULL, members INT NOT NULL,
  PRIMARY KEY (batch_id, event)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

GRANT SELECT, INSERT, UPDATE ON ecis_distribution_policy TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_distribution_policy TO 'coop_app'@'%';
GRANT SELECT, INSERT, UPDATE ON ecis_distribution_fund TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_distribution_fund TO 'coop_app'@'%';
GRANT SELECT, INSERT, UPDATE ON ecis_distribution_batch TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_distribution_batch TO 'coop_app'@'%';
GRANT SELECT, INSERT ON ecis_distribution_batch_fund TO 'coop_app'@'localhost';
GRANT SELECT, INSERT ON ecis_distribution_batch_fund TO 'coop_app'@'%';
GRANT SELECT, INSERT ON ecis_distribution_member TO 'coop_app'@'localhost';
GRANT SELECT, INSERT ON ecis_distribution_member TO 'coop_app'@'%';
GRANT SELECT, INSERT ON ecis_distribution_credit TO 'coop_app'@'localhost';
GRANT SELECT, INSERT ON ecis_distribution_credit TO 'coop_app'@'%';
GRANT SELECT, INSERT ON ecis_distribution_held TO 'coop_app'@'localhost';
GRANT SELECT, INSERT ON ecis_distribution_held TO 'coop_app'@'%';
GRANT SELECT, INSERT ON ecis_distribution_patronage TO 'coop_app'@'localhost';
GRANT SELECT, INSERT ON ecis_distribution_patronage TO 'coop_app'@'%';
