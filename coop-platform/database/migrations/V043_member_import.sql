-- Advanced mode: registering many members from a CSV or Excel list. An administrator uploads the list; each valid row becomes a pending registration request belonging to one import
-- batch; a Membership Officer other than the uploader approves (or rejects) the whole batch, which opens the members one by one and reports what succeeded.
-- Run once by an administrator (the ALTER is not repeatable).
CREATE TABLE IF NOT EXISTS ecis_member_import (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  branch_code    SMALLINT UNSIGNED NOT NULL,
  file_name      VARCHAR(160) NOT NULL,
  total_rows     INT UNSIGNED NOT NULL,
  ok_rows        INT UNSIGNED NOT NULL,
  uploaded_by    VARCHAR(60) NOT NULL,
  uploaded_at    DATETIME NOT NULL,
  status         VARCHAR(10) NOT NULL DEFAULT 'PENDING',  -- PENDING, APPROVED, REJECTED, WITHDRAWN
  decided_by     VARCHAR(60) NULL,
  decided_at     DATETIME NULL,
  decision_note  VARCHAR(255) NULL,
  opened_count   INT UNSIGNED NULL,
  failed_count   INT UNSIGNED NULL,
  PRIMARY KEY (id),
  KEY ix_mimport_status (branch_code, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

ALTER TABLE ecis_member_request ADD COLUMN batch_id BIGINT UNSIGNED NULL, ADD KEY ix_mreq_batch (batch_id, status);

GRANT SELECT, INSERT, UPDATE ON ecis_member_import TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_member_import TO 'coop_app'@'%';
