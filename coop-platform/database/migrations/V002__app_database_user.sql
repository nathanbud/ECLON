-- A limited database user for the app, to replace root (see CLAUDE.md). NOT YET APPLIED.
-- Run once by an administrator, after V001__accounting_core.sql (which creates ecis_audit_log).
-- Replace CHANGE_ME with a real password, then start the backend with DB_USER=coop_app and that password.
--
-- What it can do:
--   * read everything in the schema (the app is read-only against the legacy tables);
--   * update the client table (member profile edits; which columns may change is enforced by the app);
--   * add rows to the audit log, but not change or delete them.

CREATE USER 'coop_app'@'localhost' IDENTIFIED BY 'CHANGE_ME';

GRANT SELECT ON iaccsbsucmpc01.* TO 'coop_app'@'localhost';
GRANT UPDATE ON iaccsbsucmpc01.client TO 'coop_app'@'localhost';
GRANT INSERT ON iaccsbsucmpc01.ecis_audit_log TO 'coop_app'@'localhost';

FLUSH PRIVILEGES;

-- Editing stays off until the backend is started with COOP_EDIT_ENABLED=true.
