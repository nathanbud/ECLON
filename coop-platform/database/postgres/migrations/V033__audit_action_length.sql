-- Saving a person's account setup (Settings > Users, UserProfileController.edit) writes one audit row per changed setting with the action
-- UPDATE:<column>. Two settings have column names too long for the 40 characters the action held, so saving them always failed and nothing was saved:
-- AccessCanEditClientSourcesOfIncome (41 with UPDATE:) and AccessCanOverrideModifyCheckStatusRemarks (48). The action now holds 100.
-- Only the length grows: no stored value changes, so the audit seals (which fingerprint the values) still verify. The MySQL copy is V070.
-- Apply with scripts/migrate-postgres.ps1.
ALTER TABLE legacy.ecis_audit_log ALTER COLUMN action TYPE VARCHAR(100);
