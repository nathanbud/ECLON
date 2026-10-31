-- CIC submission (guide phase 11), first stage: the submission batch and its status log. A batch is one header record (the HD row) plus what was done for it. The member and loan
-- record layouts, encryption and sending are not built: the CIC file layout is not in the guide or the repository, so nothing here invents it.
-- Run with scripts/migrate.ps1.

CREATE TABLE IF NOT EXISTS ecis_cic_batch (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  ref              VARCHAR(20) NULL,                   -- CIC-yyyy-nnnn
  submission_type  TINYINT NOT NULL DEFAULT 0,         -- 0 Standard, 1 Correction, 2 Full resubmission (the numbering is a guess to confirm against the CIC layout)
  cut_off_date     DATE NOT NULL,
  provider_code    VARCHAR(20) NOT NULL,               -- assigned to the cooperative by the CIC
  version          VARCHAR(10) NOT NULL,
  remarks          VARCHAR(255) NULL,
  status           VARCHAR(12) NOT NULL DEFAULT 'DRAFT', -- DRAFT, GENERATED, ENCRYPTED, SUBMITTED
  created_by       VARCHAR(60) NOT NULL,
  created_at       DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_cic_ref (ref),
  KEY ix_cic_cutoff (cut_off_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- The black status log on the screen, kept per batch and per tab (MEMBERS, LOANS, FILES). Insert only.
CREATE TABLE IF NOT EXISTS ecis_cic_log (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  batch_id   BIGINT UNSIGNED NOT NULL,
  kind       VARCHAR(8) NOT NULL,
  at         DATETIME NOT NULL,
  line       VARCHAR(255) NOT NULL,
  PRIMARY KEY (id),
  KEY ix_cicl_batch (batch_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- MySQL 5.6 grants on one table at a time.
GRANT SELECT, INSERT, UPDATE ON ecis_cic_batch TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_cic_batch TO 'coop_app'@'%';
GRANT SELECT, INSERT ON ecis_cic_log TO 'coop_app'@'localhost';
GRANT SELECT, INSERT ON ecis_cic_log TO 'coop_app'@'%';
