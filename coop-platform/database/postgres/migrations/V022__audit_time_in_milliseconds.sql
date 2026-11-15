-- The audit trail now records the moment of every change to the millisecond (the application stamps each row itself when it writes it), and the tamper seals protect
-- those milliseconds too. A seal remembers how it fingerprinted its rows: 1 = the time to the second (every seal made before this), 2 = to the millisecond (every seal from now on),
-- so the seals already made keep checking out exactly as they did. legacy.ecis_audit_log.at is already a timestamp with fractions of a second here, so it is not changed
-- (rounding the stored values would break the seals already made). The MySQL copy is V059. Apply with scripts/migrate-postgres.ps1.
ALTER TABLE legacy.ecis_audit_seal ADD COLUMN IF NOT EXISTS fingerprint_version smallint NOT NULL DEFAULT 1;
