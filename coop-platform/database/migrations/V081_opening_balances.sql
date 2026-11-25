-- Opening balances: a new cooperative's balances on the day it starts on this system; see the PostgreSQL copy V044. Run with scripts/migrate.ps1.
CREATE TABLE IF NOT EXISTS ecis_opening_batch (
  id            BIGINT NOT NULL AUTO_INCREMENT,
  branch_code   SMALLINT UNSIGNED NOT NULL,
  reference     VARCHAR(20),
  opening_date  DATE         NOT NULL,
  description   VARCHAR(200) NOT NULL,
  status        VARCHAR(12)  NOT NULL,
  created_by    VARCHAR(60)  NOT NULL,
  created_at    DATETIME(3)  NOT NULL,
  submitted_at  DATETIME(3),
  approved_by   VARCHAR(60),
  approved_at   DATETIME(3),
  posted_by     VARCHAR(60),
  posted_at     DATETIME(3),
  tr_year       INT,
  tr_ctlno      BIGINT,
  cancel_reason VARCHAR(200),
  PRIMARY KEY (id),
  KEY ecis_opening_batch_branch (branch_code, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
CREATE TABLE IF NOT EXISTS ecis_opening_line (
  batch_id     BIGINT        NOT NULL,
  line_no      INT           NOT NULL,
  kind         VARCHAR(10)   NOT NULL,
  client_id    INT,
  type_code    INT,
  ref_no       INT,
  account_code VARCHAR(20),
  debit        DECIMAL(15,2) NOT NULL,
  credit       DECIMAL(15,2) NOT NULL,
  note         VARCHAR(100),
  PRIMARY KEY (batch_id, line_no),
  CONSTRAINT ecis_opening_line_batch FOREIGN KEY (batch_id) REFERENCES ecis_opening_batch (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
GRANT SELECT, INSERT, UPDATE ON ecis_opening_batch TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_opening_batch TO 'coop_app'@'%';
GRANT SELECT, INSERT, DELETE ON ecis_opening_line TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, DELETE ON ecis_opening_line TO 'coop_app'@'%';
