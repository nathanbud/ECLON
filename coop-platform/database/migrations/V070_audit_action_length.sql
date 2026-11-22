-- The MySQL copy of PostgreSQL V033: the audit log's action holds 100 characters instead of 40, so saving the account settings with long column
-- names (AccessCanEditClientSourcesOfIncome, AccessCanOverrideModifyCheckStatusRemarks) no longer fails. Only the length grows; stored values are unchanged.
-- Apply with scripts/migrate.ps1.
ALTER TABLE ecis_audit_log MODIFY action VARCHAR(100) NOT NULL;
