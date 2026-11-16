-- Document management (guide phase 16): scanned IDs, contracts, deeds, supplier papers and the like, attached to a member, loan, supplier, asset ... .
-- The file itself lives in the document store (a folder today, MinIO or any S3-compatible store later, under an opaque object key); this table holds what the guide lists:
-- entity, object key, file name, type, hash, classification, who and when, retention date. A file is never overwritten: a new version supersedes the old one, and a
-- document can be removed (hidden, never destroyed here) only after its retention date. Who may read it follows its classification, plus per-person grants.
-- Run by an administrator. Idempotent.
CREATE TABLE IF NOT EXISTS ecis_document (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  branch_code    SMALLINT UNSIGNED NOT NULL,
  doc_no         VARCHAR(20) NOT NULL,                    -- DOC-2026-000001
  entity_type    VARCHAR(20) NOT NULL,                    -- MEMBER, LOAN, SUPPLIER, PURCHASE_ORDER, ASSET, JOURNAL, OTHER ...
  entity_id      VARCHAR(40) NOT NULL,                    -- the member number, asset number ... as text
  title          VARCHAR(120) NOT NULL,
  description    VARCHAR(255) NULL,
  object_key     VARCHAR(120) NOT NULL,                   -- where the store keeps it (never built from the user's file name)
  file_name      VARCHAR(160) NOT NULL,                   -- the cleaned name it is downloaded as
  mime_type      VARCHAR(100) NOT NULL,
  size_bytes     BIGINT UNSIGNED NOT NULL,
  sha256         CHAR(64) NOT NULL,
  classification VARCHAR(12) NOT NULL,                    -- PUBLIC, INTERNAL, CONFIDENTIAL, RESTRICTED
  version_no     SMALLINT UNSIGNED NOT NULL DEFAULT 1,
  supersedes_id  BIGINT UNSIGNED NULL,                    -- the version this one replaces
  status         VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE, SUPERSEDED, REMOVED
  retention_date DATE NOT NULL,                           -- may not be removed before this day
  created_by     VARCHAR(60) NOT NULL,
  created_at     DATETIME NOT NULL,
  removed_by     VARCHAR(60) NULL,
  removed_at     DATETIME NULL,
  remove_reason  VARCHAR(255) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_doc_no (branch_code, doc_no),
  KEY ix_doc_entity (branch_code, entity_type, entity_id, status),
  KEY ix_doc_hash (sha256),
  KEY ix_doc_super (supersedes_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- people allowed to read one particular document beyond what its classification gives them
CREATE TABLE IF NOT EXISTS ecis_document_grant (
  document_id BIGINT UNSIGNED NOT NULL,
  username    VARCHAR(60) NOT NULL,
  granted_by  VARCHAR(60) NOT NULL,
  granted_at  DATETIME NOT NULL,
  PRIMARY KEY (document_id, username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

GRANT SELECT, INSERT, UPDATE ON ecis_document TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, DELETE ON ecis_document_grant TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_document TO 'coop_app'@'%';
GRANT SELECT, INSERT, DELETE ON ecis_document_grant TO 'coop_app'@'%';
