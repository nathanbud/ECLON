-- The posting engine's request keys; see the PostgreSQL copy V026. Run with scripts/migrate.ps1. Idempotent.
CREATE TABLE IF NOT EXISTS ecis_posting_request (
  request_key VARCHAR(100) NOT NULL,
  username    VARCHAR(60) NOT NULL,
  branch_code INT NOT NULL,
  tr_year     INT NOT NULL,
  tr_module   INT NOT NULL,
  tr_code     INT NOT NULL,
  tr_ctlno    BIGINT NOT NULL,
  doc_no      VARCHAR(20) NOT NULL,
  tr_date     DATE NOT NULL,
  created_at  DATETIME NOT NULL,
  PRIMARY KEY (request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
GRANT SELECT, INSERT ON ecis_posting_request TO 'coop_app'@'localhost';
GRANT SELECT, INSERT ON ecis_posting_request TO 'coop_app'@'%';
