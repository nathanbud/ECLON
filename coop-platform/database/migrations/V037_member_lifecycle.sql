-- Membership lifecycle: registering a new member and closing a membership, both maker-checker (a Membership Officer other than the requester decides).
-- A request waits here as PENDING; nothing is written to the legacy tables until it is approved. Approving a registration inserts the member's client row and the standard
-- accounts the old system opens with a member (savings, share capital, mutual benefit and hospitalization funds), so coop_app needs INSERT on client, sc and sd
-- (slmastersum and osl were granted by V016 and V018). A member is never deleted: closing sets the status to Closed with a date and reason, as the old system does.
-- Run by an administrator. Idempotent.
CREATE TABLE IF NOT EXISTS ecis_member_request (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  branch_code      SMALLINT UNSIGNED NOT NULL,
  kind             VARCHAR(10) NOT NULL,                    -- REGISTER, CLOSE
  client_id        INT UNSIGNED NULL,                       -- the member a CLOSE is about; for REGISTER, the new number once approved
  summary          VARCHAR(160) NOT NULL,                   -- "Reyes, Ana (Regular Individual)" or "Close 100123: voluntary"
  payload          TEXT NOT NULL,                           -- JSON of what was asked
  status           VARCHAR(10) NOT NULL DEFAULT 'PENDING',  -- PENDING, APPROVED, REJECTED, WITHDRAWN
  requested_by     VARCHAR(60) NOT NULL,
  requested_at     DATETIME NOT NULL,
  decided_by       VARCHAR(60) NULL,
  decided_at       DATETIME NULL,
  decision_note    VARCHAR(255) NULL,
  PRIMARY KEY (id),
  KEY ix_mreq_status (branch_code, status, kind),
  KEY ix_mreq_client (branch_code, client_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

GRANT SELECT, INSERT, UPDATE ON ecis_member_request TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_member_request TO 'coop_app'@'%';
GRANT INSERT ON client TO 'coop_app'@'localhost';
GRANT INSERT ON sc TO 'coop_app'@'localhost';
GRANT INSERT ON sd TO 'coop_app'@'localhost';
GRANT INSERT ON client TO 'coop_app'@'%';
GRANT INSERT ON sc TO 'coop_app'@'%';
GRANT INSERT ON sd TO 'coop_app'@'%';
