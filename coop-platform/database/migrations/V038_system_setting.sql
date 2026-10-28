-- A small key/value table for administrator-controlled policy switches (first user: whether a returning member must pay share capital again).
-- Run by an administrator. Idempotent.
CREATE TABLE IF NOT EXISTS ecis_setting (
  setting_key   VARCHAR(80) NOT NULL,
  setting_value VARCHAR(255) NOT NULL,
  updated_by    VARCHAR(60) NULL,
  updated_at    DATETIME NULL,
  PRIMARY KEY (setting_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

GRANT SELECT, INSERT, UPDATE ON ecis_setting TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_setting TO 'coop_app'@'%';
