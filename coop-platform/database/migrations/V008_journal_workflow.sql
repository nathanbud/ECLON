-- Journal workflow (guide phase 6.5/6.6). Grants only; tables come from V001__accounting_core.sql.
-- Run by an administrator, on a copy first. Idempotent. NOT YET APPLIED.
GRANT SELECT, INSERT, UPDATE ON ecis_journal TO 'coop_app'@'%';
GRANT SELECT, INSERT ON ecis_journal_line TO 'coop_app'@'%';
GRANT SELECT, INSERT, UPDATE ON ecis_accounting_period TO 'coop_app'@'%';
GRANT INSERT ON ecis_audit_log TO 'coop_app'@'%';
