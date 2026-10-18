-- Front office transaction drafts. A draft is a transaction being prepared: it is checked (accounts exist, debits and
-- credits are counted) but NOT posted. Nothing here touches transsum, transdt, gltrans or any other legacy table.
-- Additive and safe to repeat.

CREATE TABLE IF NOT EXISTS ecis_fo_draft (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  branch_code SMALLINT UNSIGNED NOT NULL,
  tr_code     TINYINT UNSIGNED NOT NULL,
  created_by  VARCHAR(60) NOT NULL,
  created_at  DATETIME NOT NULL,
  updated_at  DATETIME NOT NULL,
  payload     MEDIUMTEXT NOT NULL,
  PRIMARY KEY (id),
  KEY ix_fo_draft_user (created_by, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

GRANT INSERT, UPDATE, DELETE ON iaccsbsucmpc01.ecis_fo_draft TO 'coop_app'@'localhost';
FLUSH PRIVILEGES;
