-- Appropriation of the net surplus; see the PostgreSQL copy V045. Run with scripts/migrate.ps1.
-- MySQL 5.6 has no partial unique index: one live appropriation per branch and year is checked by the application.
CREATE TABLE IF NOT EXISTS ecis_appropriation (
  id            BIGINT NOT NULL AUTO_INCREMENT,
  branch_code   SMALLINT UNSIGNED NOT NULL,
  year          INT           NOT NULL,
  reference     VARCHAR(20),
  entry_date    DATE          NOT NULL,
  resolution    VARCHAR(200)  NOT NULL,
  net_surplus   DECIMAL(18,2) NOT NULL,
  status        VARCHAR(12)   NOT NULL,
  created_by    VARCHAR(60)   NOT NULL,
  created_at    DATETIME(3)   NOT NULL,
  submitted_at  DATETIME(3),
  approved_by   VARCHAR(60),
  approved_at   DATETIME(3),
  posted_by     VARCHAR(60),
  posted_at     DATETIME(3),
  tr_year       INT,
  tr_ctlno      BIGINT,
  cancel_reason VARCHAR(200),
  PRIMARY KEY (id),
  KEY ecis_appropriation_year (branch_code, year, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
CREATE TABLE IF NOT EXISTS ecis_appropriation_line (
  appropriation_id BIGINT        NOT NULL,
  line_no          INT           NOT NULL,
  kind             VARCHAR(12)   NOT NULL,
  name             VARCHAR(80)   NOT NULL,
  percent          DECIMAL(7,4),
  amount           DECIMAL(18,2) NOT NULL,
  account_code     VARCHAR(20),
  PRIMARY KEY (appropriation_id, line_no),
  CONSTRAINT ecis_appropriation_line_parent FOREIGN KEY (appropriation_id) REFERENCES ecis_appropriation (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
GRANT SELECT, INSERT, UPDATE ON ecis_appropriation TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_appropriation TO 'coop_app'@'%';
GRANT SELECT, INSERT, DELETE ON ecis_appropriation_line TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, DELETE ON ecis_appropriation_line TO 'coop_app'@'%';
