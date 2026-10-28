-- Member fingerprints (biometric data is sensitive personal information under the Data Privacy Act).
-- Consent comes first and can be withdrawn, which wipes the templates. Templates are stored encrypted (AES-256-GCM, key outside the database), one active template per finger;
-- a replaced or revoked template is wiped and only its record remains. Finger numbers follow ISO/IEC 19794: 1 right thumb ... 5 right little, 6 left thumb ... 10 left little.
-- Run by an administrator. Idempotent.
CREATE TABLE IF NOT EXISTS ecis_biometric_consent (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  branch_code      SMALLINT UNSIGNED NOT NULL,
  client_id        INT UNSIGNED NOT NULL,
  consent_version  VARCHAR(20) NOT NULL,
  given_at         DATETIME NOT NULL,
  recorded_by      VARCHAR(60) NOT NULL,
  withdrawn_at     DATETIME NULL,
  withdrawn_by     VARCHAR(60) NULL,
  withdraw_reason  VARCHAR(255) NULL,
  PRIMARY KEY (id),
  KEY ix_bconsent_client (branch_code, client_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS ecis_member_fingerprint (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  branch_code  SMALLINT UNSIGNED NOT NULL,
  client_id    INT UNSIGNED NOT NULL,
  finger       TINYINT UNSIGNED NOT NULL,
  format       VARCHAR(12) NOT NULL,
  quality      TINYINT UNSIGNED NOT NULL,
  device       VARCHAR(60) NULL,
  template     MEDIUMBLOB NULL,                      -- iv + AES-GCM ciphertext; NULL once replaced or revoked
  status       VARCHAR(10) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, REPLACED, REVOKED
  captured_by  VARCHAR(60) NOT NULL,
  captured_at  DATETIME NOT NULL,
  ended_at     DATETIME NULL,
  end_reason   VARCHAR(255) NULL,
  PRIMARY KEY (id),
  KEY ix_fp_client (branch_code, client_id, status),
  KEY ix_fp_active (status, finger)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

GRANT SELECT, INSERT, UPDATE ON ecis_biometric_consent TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_member_fingerprint TO 'coop_app'@'localhost';
GRANT SELECT, INSERT, UPDATE ON ecis_biometric_consent TO 'coop_app'@'%';
GRANT SELECT, INSERT, UPDATE ON ecis_member_fingerprint TO 'coop_app'@'%';
