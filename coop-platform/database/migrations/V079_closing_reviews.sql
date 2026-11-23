-- Closing reviews: the month-end review and the year-end checklist; see the PostgreSQL copy V042. Run with scripts/migrate.ps1.
CREATE TABLE IF NOT EXISTS ecis_close_review (
  branch_code SMALLINT UNSIGNED NOT NULL,
  scope       VARCHAR(5)   NOT NULL,
  period      VARCHAR(7)   NOT NULL,
  item        VARCHAR(40)  NOT NULL,
  note        VARCHAR(500),
  done_by     VARCHAR(60)  NOT NULL,
  done_at     DATETIME(3)  NOT NULL,
  PRIMARY KEY (branch_code, scope, period, item)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
CREATE TABLE IF NOT EXISTS ecis_close_signoff (
  branch_code SMALLINT UNSIGNED NOT NULL,
  scope       VARCHAR(5)   NOT NULL,
  period      VARCHAR(7)   NOT NULL,
  signed_by   VARCHAR(60)  NOT NULL,
  signed_at   DATETIME(3)  NOT NULL,
  note        VARCHAR(500),
  PRIMARY KEY (branch_code, scope, period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
GRANT SELECT, INSERT, UPDATE, DELETE ON ecis_close_review TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE, DELETE ON ecis_close_review TO 'coop_app'@'%';
GRANT SELECT, INSERT ON ecis_close_signoff TO 'coop_app'@'localhost';
GRANT SELECT, INSERT ON ecis_close_signoff TO 'coop_app'@'%';
INSERT IGNORE INTO ecis_setting (setting_key, setting_value, updated_by, updated_at) VALUES ('accounting.monthly_close', 'false', 'system', NOW());
INSERT IGNORE INTO ecis_setting (setting_key, setting_value, updated_by, updated_at) VALUES ('accounting.year_end_due_days', '120', 'system', NOW());
