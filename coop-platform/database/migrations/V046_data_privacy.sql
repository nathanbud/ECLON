-- Phase 19, data privacy. The registers a data protection officer works from: requests from people about their own data, incidents, who the data is shared with, impact assessments,
-- privacy notices and who has been given one, retention rules, a classification of the data held, and a log of exports. Starting rows for retention and classification are marked
-- NOT confirmed: they are a starting point to be reviewed against the Data Privacy Act and the cooperative's own rules before production (the guide says so).
-- Run with scripts/migrate.ps1.

CREATE TABLE IF NOT EXISTS ecis_privacy_request (
  id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  ref                VARCHAR(20) NULL,
  request_type       VARCHAR(20) NOT NULL,            -- ACCESS, CORRECTION, ERASURE, OBJECTION, PORTABILITY, WITHDRAW_CONSENT, OTHER
  branch_code        SMALLINT UNSIGNED NULL,
  client_id          INT UNSIGNED NULL,               -- the member, when the person is one
  requester_name     VARCHAR(120) NOT NULL,
  contact            VARCHAR(120) NULL,
  received_on        DATE NOT NULL,
  due_on             DATE NOT NULL,
  identity_verified  TINYINT NOT NULL DEFAULT 0,
  verified_by        VARCHAR(60) NULL,
  status             VARCHAR(12) NOT NULL DEFAULT 'OPEN',  -- OPEN, IN_PROGRESS, FULFILLED, REFUSED, WITHDRAWN
  summary            TEXT NOT NULL,
  outcome            TEXT NULL,
  assigned_to        VARCHAR(60) NULL,
  created_by         VARCHAR(60) NOT NULL,
  created_at         DATETIME NOT NULL,
  closed_by          VARCHAR(60) NULL,
  closed_at          DATETIME NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_pr_ref (ref),
  KEY ix_pr_status (status, due_on),
  KEY ix_pr_client (branch_code, client_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_privacy_incident (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  ref              VARCHAR(20) NULL,
  detected_at      DATETIME NOT NULL,
  description      TEXT NOT NULL,
  data_affected    VARCHAR(255) NULL,
  people_affected  INT UNSIGNED NULL,
  severity         VARCHAR(8) NOT NULL,                -- LOW, MEDIUM, HIGH
  status           VARCHAR(10) NOT NULL DEFAULT 'OPEN', -- OPEN, CONTAINED, RESOLVED
  notify_by        DATETIME NOT NULL,                  -- 72 hours after detection: when the regulator and those affected must have been told, if the law requires it
  notified_at      DATETIME NULL,
  actions          TEXT NULL,
  reported_by      VARCHAR(60) NOT NULL,
  created_at       DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_pi_ref (ref),
  KEY ix_pi_status (status, notify_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_data_sharing (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  recipient        VARCHAR(120) NOT NULL,
  purpose          VARCHAR(255) NOT NULL,
  data_categories  VARCHAR(255) NOT NULL,
  legal_basis      VARCHAR(255) NULL,
  agreement_ref    VARCHAR(80) NULL,
  started_on       DATE NOT NULL,
  ended_on         DATE NULL,
  recorded_by      VARCHAR(60) NOT NULL,
  recorded_at      DATETIME NOT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_dpia (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  ref           VARCHAR(20) NULL,
  title         VARCHAR(160) NOT NULL,
  scope         TEXT NULL,
  risk_level    VARCHAR(8) NOT NULL,                  -- LOW, MEDIUM, HIGH
  status        VARCHAR(12) NOT NULL DEFAULT 'PLANNED', -- PLANNED, IN_PROGRESS, DONE, REVIEW_DUE
  conducted_on  DATE NULL,
  review_due    DATE NULL,
  owner         VARCHAR(80) NULL,
  reference     VARCHAR(160) NULL,                    -- where the assessment document is kept
  recorded_by   VARCHAR(60) NOT NULL,
  recorded_at   DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_dpia_ref (ref)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_privacy_notice (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  version         VARCHAR(20) NOT NULL,
  title           VARCHAR(160) NOT NULL,
  body            TEXT NOT NULL,
  effective_from  DATE NULL,
  status          VARCHAR(8) NOT NULL DEFAULT 'DRAFT', -- DRAFT, ACTIVE, RETIRED
  created_by      VARCHAR(60) NOT NULL,
  created_at      DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_notice_version (version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_privacy_ack (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  branch_code  SMALLINT UNSIGNED NOT NULL,
  client_id    INT UNSIGNED NOT NULL,
  notice_id    BIGINT UNSIGNED NOT NULL,
  how          VARCHAR(12) NOT NULL,                  -- VERBAL, WRITTEN, ELECTRONIC
  recorded_by  VARCHAR(60) NOT NULL,
  recorded_at  DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY ix_ack_client (branch_code, client_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_retention_rule (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  category      VARCHAR(120) NOT NULL,
  retain_years  SMALLINT UNSIGNED NULL,
  starts_from   VARCHAR(80) NULL,
  action        VARCHAR(10) NOT NULL,                 -- KEEP, ARCHIVE, ANONYMIZE, DELETE
  legal_basis   TEXT NULL,
  notes         TEXT NULL,
  confirmed     TINYINT NOT NULL DEFAULT 0,
  confirmed_by  VARCHAR(60) NULL,
  confirmed_at  DATETIME NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_data_classification (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  data_item       VARCHAR(160) NOT NULL,
  where_stored    VARCHAR(255) NOT NULL,
  classification  VARCHAR(12) NOT NULL,               -- PUBLIC, INTERNAL, CONFIDENTIAL, SENSITIVE
  handling        TEXT NULL,
  reviewed        TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_data_export (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  at           DATETIME NOT NULL,
  username     VARCHAR(60) NOT NULL,
  export_type  VARCHAR(20) NOT NULL,                  -- REPORT, MEMBER_PACKAGE, AUDIT
  subject      VARCHAR(160) NULL,
  row_count    INT UNSIGNED NULL,
  purpose      VARCHAR(255) NULL,
  PRIMARY KEY (id),
  KEY ix_export_at (at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- MySQL 5.6 grants on one table at a time.
GRANT SELECT, INSERT, UPDATE ON ecis_privacy_request TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_privacy_request TO 'coop_app'@'%';
GRANT SELECT, INSERT, UPDATE ON ecis_privacy_incident TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_privacy_incident TO 'coop_app'@'%';
GRANT SELECT, INSERT, UPDATE ON ecis_data_sharing TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_data_sharing TO 'coop_app'@'%';
GRANT SELECT, INSERT, UPDATE ON ecis_dpia TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_dpia TO 'coop_app'@'%';
GRANT SELECT, INSERT, UPDATE ON ecis_privacy_notice TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_privacy_notice TO 'coop_app'@'%';
GRANT SELECT, INSERT, UPDATE ON ecis_privacy_ack TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_privacy_ack TO 'coop_app'@'%';
GRANT SELECT, INSERT, UPDATE ON ecis_retention_rule TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_retention_rule TO 'coop_app'@'%';
GRANT SELECT, INSERT, UPDATE ON ecis_data_classification TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_data_classification TO 'coop_app'@'%';
GRANT SELECT, INSERT, UPDATE ON ecis_data_export TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_data_export TO 'coop_app'@'%';

-- Starting retention rules, NOT confirmed. The years for documents are what the application already uses.
INSERT INTO ecis_retention_rule (category, retain_years, starts_from, action, notes, confirmed) VALUES
 ('Member, loan, journal and asset documents', 10, 'the date the document was added', 'ARCHIVE', 'What the document register uses now. Confirm against the record-keeping rules that apply to a cooperative.', 0),
 ('Other documents', 5, 'the date the document was added', 'ARCHIVE', 'What the document register uses now.', 0),
 ('Fingerprints', NULL, 'the member withdraws consent, or the membership ends', 'DELETE', 'Erased at once when consent is withdrawn. Decide whether they are also erased when a membership closes.', 0),
 ('Member photos', NULL, 'the membership ends', 'KEEP', 'The application can add photos but cannot delete one; removal is a database administrator task. Decide the rule.', 0),
 ('Member records, accounts and ledger entries', NULL, 'the membership ends', 'KEEP', 'Financial records are normally kept for a legally set period. To be set.', 0),
 ('Audit log', NULL, 'the entry date', 'KEEP', 'Never deleted by the application; sealed nightly.', 0),
 ('Privacy requests and incident records', NULL, 'the case is closed', 'KEEP', 'To be set.', 0);

-- Starting classification of the data the system holds, NOT reviewed.
INSERT INTO ecis_data_classification (data_item, where_stored, classification, handling, reviewed) VALUES
 ('Cooperative name, tagline and logo', 'ecis_branding (shown on the sign-in screen)', 'PUBLIC', 'Anyone may read it.', 0),
 ('Member name, address, contact details and birth date', 'client', 'CONFIDENTIAL', 'Staff with a reason to see it; changes are approved by a second person and audited.', 0),
 ('Government IDs: TIN, SSS / GSIS, PhilHealth, Pag-IBIG, driver''s licence, voter''s ID', 'client (Contacts & IDs)', 'SENSITIVE', 'Sensitive personal information. Never copied into exports without a purpose; never written to logs.', 0),
 ('Fingerprints', 'ecis_member_fingerprint (encrypted templates)', 'SENSITIVE', 'Consent first; encrypted; never returned by any endpoint; erased on withdrawal.', 0),
 ('Member photos and signatures', 'image', 'CONFIDENTIAL', 'Shown on the member profile to staff; added only with the images permission.', 0),
 ('Share capital, savings, time deposits, loans and the ledger', 'sc, sd, td, loan, sldtl, transsum, transdt, gltrans', 'CONFIDENTIAL', 'Staff with a reason to see it; every posting is audited and immutable.', 0),
 ('Passwords and sign-in records', 'ecis_user_credential, access', 'SENSITIVE', 'Passwords are stored only as hashes; a temporary password is shown once.', 0),
 ('Scanned IDs, contracts and deeds', 'ecis_document (files on disk)', 'CONFIDENTIAL', 'Each document has its own classification and access grants; every download is audited.', 0),
 ('Audit log', 'ecis_audit_log, ecis_audit_seal', 'CONFIDENTIAL', 'Administrators read it; nothing can change it.', 0),
 ('Credit information shared with the credit bureau (CIC)', 'to be built', 'SENSITIVE', 'To be set when the CIC integration is built.', 0);
