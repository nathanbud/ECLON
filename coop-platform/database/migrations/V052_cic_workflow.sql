-- CIC submission, the workflow (guide phase 11): the records a batch holds, what validation found in them, the result the CIC sends back, and the steps a batch goes through.
-- A batch (V047) is made with its header, then GENERATED (records built from the ledger as at the cut-off date), VALIDATED, APPROVED by someone other than its maker, SUBMITTED
-- (the file handed to the CIC), RESULT (the CIC's answer recorded) and RECONCILED. Records are kept as the exact file line, so what is approved is what is sent.
-- mapping_version names the column layout used (CSDF-1); a new CIC layout becomes a new version and old batches keep theirs. Run with scripts/migrate.ps1.

ALTER TABLE ecis_cic_batch
  ADD COLUMN mapping_version      VARCHAR(12)  NOT NULL DEFAULT 'CSDF-1' AFTER version,
  ADD COLUMN subjects             INT UNSIGNED NOT NULL DEFAULT 0,       -- ID records
  ADD COLUMN installment_contracts INT UNSIGNED NOT NULL DEFAULT 0,      -- CI records
  ADD COLUMN other_contracts      INT UNSIGNED NOT NULL DEFAULT 0,       -- CN records (lump-sum loans)
  ADD COLUMN errors               INT UNSIGNED NOT NULL DEFAULT 0,
  ADD COLUMN warnings             INT UNSIGNED NOT NULL DEFAULT 0,
  ADD COLUMN excluded             INT UNSIGNED NOT NULL DEFAULT 0,       -- records left out of the file at approval because they had errors
  ADD COLUMN generated_at         DATETIME NULL,
  ADD COLUMN validated_at         DATETIME NULL,
  ADD COLUMN approved_by          VARCHAR(60)  NULL,
  ADD COLUMN approved_at          DATETIME NULL,
  ADD COLUMN approval_note        VARCHAR(255) NULL,
  ADD COLUMN submitted_by         VARCHAR(60)  NULL,
  ADD COLUMN submitted_at         DATETIME NULL,
  ADD COLUMN submission_reference VARCHAR(80)  NULL,                     -- the CIC's reference or the upload receipt
  ADD COLUMN corrects_batch_id    BIGINT UNSIGNED NULL;                  -- the batch a correction batch corrects

CREATE TABLE IF NOT EXISTS ecis_cic_record (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  batch_id     BIGINT UNSIGNED NOT NULL,
  seq          INT UNSIGNED NOT NULL,                    -- position among the batch's records of the same type
  record_type  VARCHAR(2) NOT NULL,                      -- ID individual, CI installment contract, CN non-installment contract
  subject_key  VARCHAR(11) NOT NULL,                     -- branch (2 digits) and client number (9 digits)
  contract_no  VARCHAR(16) NULL,                         -- CI and CN only
  line         MEDIUMTEXT NOT NULL,                      -- the record exactly as it goes into the file
  status       VARCHAR(7) NOT NULL DEFAULT 'VALID',      -- VALID, WARNING, ERROR (worst finding)
  excluded     TINYINT NOT NULL DEFAULT 0,               -- left out of the file at approval
  PRIMARY KEY (id),
  KEY ix_cicr_batch (batch_id, record_type, seq),
  KEY ix_cicr_status (batch_id, status),
  KEY ix_cicr_subject (batch_id, subject_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_cic_validation (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  batch_id   BIGINT UNSIGNED NOT NULL,
  record_id  BIGINT UNSIGNED NULL,                       -- empty for a finding about the whole batch
  severity   VARCHAR(7) NOT NULL,                        -- WARNING or ERROR
  field      VARCHAR(40) NOT NULL,                       -- what the finding is about, in words
  message    VARCHAR(255) NOT NULL,
  PRIMARY KEY (id),
  KEY ix_cicv_batch (batch_id, severity),
  KEY ix_cicv_record (record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_cic_response (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  batch_id     BIGINT UNSIGNED NOT NULL,
  recorded_at  DATETIME NOT NULL,
  recorded_by  VARCHAR(60) NOT NULL,
  accepted     INT UNSIGNED NOT NULL,
  rejected     INT UNSIGNED NOT NULL,
  note         VARCHAR(500) NULL,                        -- the CIC's result, in the person's words, or where its result file is kept
  PRIMARY KEY (id),
  KEY ix_cicp_batch (batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- MySQL 5.6 grants on one table at a time. Records and findings of a batch are replaced when it is generated again, so they may be deleted; a response is never changed.
GRANT SELECT, INSERT, UPDATE, DELETE ON ecis_cic_record TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE, DELETE ON ecis_cic_record TO 'coop_app'@'%';
GRANT SELECT, INSERT, DELETE ON ecis_cic_validation TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, DELETE ON ecis_cic_validation TO 'coop_app'@'%';
GRANT SELECT, INSERT ON ecis_cic_response TO 'coop_app'@'localhost';
GRANT SELECT, INSERT ON ecis_cic_response TO 'coop_app'@'%';
