-- Sign-in and approval of member changes. Additive only: new ecis_* tables, no legacy table is altered.
-- Idempotent for the tables; the GRANTs can be repeated safely.

-- App passwords. The legacy access.AccessPassword uses an encrypted format that cannot be checked, so each person
-- gets a new password here, stored as a bcrypt hash. People and their enabled/disabled state still come from `access`.
CREATE TABLE IF NOT EXISTS ecis_user_credential (
  username      VARCHAR(60) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  set_by        VARCHAR(60) NOT NULL,
  set_at        DATETIME NOT NULL,
  PRIMARY KEY (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- Roles that the legacy user list does not have, such as MEMBERSHIP_OFFICER (the only role that approves changes).
CREATE TABLE IF NOT EXISTS ecis_user_role (
  username   VARCHAR(60) NOT NULL,
  role       VARCHAR(40) NOT NULL,
  granted_by VARCHAR(60) NOT NULL,
  granted_at DATETIME NOT NULL,
  PRIMARY KEY (username, role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- A change to a member's profile that is waiting for, or has had, a decision.
CREATE TABLE IF NOT EXISTS ecis_member_change (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  branch_code     SMALLINT UNSIGNED NOT NULL,
  client_id       MEDIUMINT UNSIGNED NOT NULL,
  requested_by    VARCHAR(60) NOT NULL,
  requested_at    DATETIME NOT NULL,
  base_updated_at DATETIME DEFAULT NULL,
  status          ENUM('PENDING','APPROVED','REJECTED','WITHDRAWN') NOT NULL DEFAULT 'PENDING',
  changes         TEXT NOT NULL,
  reason          VARCHAR(255) DEFAULT NULL,
  decided_by      VARCHAR(60) DEFAULT NULL,
  decided_at      DATETIME DEFAULT NULL,
  decision_note   VARCHAR(255) DEFAULT NULL,
  PRIMARY KEY (id),
  KEY ix_change_client (branch_code, client_id, status),
  KEY ix_change_status (status, requested_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

GRANT INSERT, UPDATE ON iaccsbsucmpc01.ecis_member_change TO 'coop_app'@'localhost';
GRANT INSERT, UPDATE ON iaccsbsucmpc01.ecis_user_credential TO 'coop_app'@'localhost';
GRANT INSERT, DELETE ON iaccsbsucmpc01.ecis_user_role TO 'coop_app'@'localhost';
FLUSH PRIVILEGES;
