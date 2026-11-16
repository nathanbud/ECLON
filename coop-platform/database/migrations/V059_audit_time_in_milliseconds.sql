-- The audit trail now records the moment of every change to the millisecond, and the tamper seals protect those milliseconds too.
-- DATETIME kept whole seconds; DATETIME(3) keeps milliseconds (MySQL 5.6.4 and later). The values already stored are kept as they are (with .000).
-- A seal remembers how it fingerprinted its rows: 1 = the time to the second (every seal made before this), 2 = to the millisecond (every seal from now on).
-- The PostgreSQL copy is V022. Run by an administrator. Not idempotent for the column: run once.
ALTER TABLE ecis_audit_log MODIFY at DATETIME(3) NOT NULL;
ALTER TABLE ecis_audit_seal ADD COLUMN fingerprint_version SMALLINT NOT NULL DEFAULT 1;
