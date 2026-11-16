-- Passbook printing; see the PostgreSQL copy V028. Run with scripts/migrate.ps1. Not idempotent: run once.
CREATE TABLE IF NOT EXISTS ecis_passbook_layout (
  passbook_kind    INT NOT NULL,
  page_width       DECIMAL(5,3) NOT NULL,
  page_height      DECIMAL(5,3) NOT NULL,
  lines_per_page   INT NOT NULL,
  first_line       DECIMAL(5,3) NOT NULL,
  line_height      DECIMAL(5,3) NOT NULL,
  fold_after       INT NOT NULL,
  fold_gap         DECIMAL(5,3) NOT NULL,
  font_size        DECIMAL(4,1) NOT NULL,
  date_x           DECIMAL(5,3) NOT NULL,
  reference_x      DECIMAL(5,3) NOT NULL,
  deposit_right    DECIMAL(5,3) NOT NULL,
  withdrawal_right DECIMAL(5,3) NOT NULL,
  balance_right    DECIMAL(5,3) NOT NULL,
  tc_x             DECIMAL(5,3) NOT NULL,
  teller_x         DECIMAL(5,3) NOT NULL,
  shift_x          DECIMAL(5,3) NOT NULL DEFAULT 0,
  shift_y          DECIMAL(5,3) NOT NULL DEFAULT 0,
  updated_by       VARCHAR(60) NOT NULL,
  updated_at       DATETIME NOT NULL,
  PRIMARY KEY (passbook_kind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
INSERT INTO ecis_passbook_layout VALUES (22, 5.5, 8, 31, 0.55, 0.2, 16, 0.35, 8, 0.15, 0.85, 2.65, 3.6, 4.65, 4.75, 5.1, 0, 0, 'setup', NOW());
GRANT SELECT, UPDATE ON ecis_passbook_layout TO 'coop_app'@'localhost';
GRANT SELECT, UPDATE ON ecis_passbook_layout TO 'coop_app'@'%';

CREATE TABLE IF NOT EXISTS ecis_passbook_print (
  id          BIGINT NOT NULL AUTO_INCREMENT,
  branch_code INT NOT NULL,
  client_id   INT NOT NULL,
  slc_code    INT NOT NULL,
  slt_code    INT NOT NULL,
  ref_no      VARCHAR(20) NOT NULL,
  action      VARCHAR(20) NOT NULL,
  serial      VARCHAR(20) NULL,
  from_seq    INT NULL,
  to_seq      INT NULL,
  first_line  INT NULL,
  last_line   INT NULL,
  username    VARCHAR(60) NOT NULL,
  at          DATETIME NOT NULL,
  reason      VARCHAR(300) NULL,
  PRIMARY KEY (id),
  KEY ix_ecis_passbook_print_account (branch_code, client_id, slc_code, slt_code, ref_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
GRANT SELECT, INSERT ON ecis_passbook_print TO 'coop_app'@'localhost';
GRANT SELECT, INSERT ON ecis_passbook_print TO 'coop_app'@'%';

GRANT INSERT ON passbk TO 'coop_app'@'localhost';
GRANT INSERT ON passbk TO 'coop_app'@'%';
GRANT UPDATE (PASSBKSERNUM, PASSBKLINENUM, PASSBKLTR_DATE, PASSBKBAL, PASSBKSEQNO) ON passbk TO 'coop_app'@'localhost';
GRANT UPDATE (PASSBKSERNUM, PASSBKLINENUM, PASSBKLTR_DATE, PASSBKBAL, PASSBKSEQNO) ON passbk TO 'coop_app'@'%';
