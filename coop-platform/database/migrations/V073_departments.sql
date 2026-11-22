-- Departments (Settings > Organization > Departments): the application may add, change, switch off and delete an unused department in dept; see the
-- PostgreSQL copy V036. Run with scripts/migrate.ps1.
GRANT INSERT, DELETE ON dept TO 'coop_app'@'localhost';
GRANT INSERT, DELETE ON dept TO 'coop_app'@'%';
GRANT UPDATE (DeptDesc, DeptParentID, DeptTIN, DeptAddress, DeptIsDisabled) ON dept TO 'coop_app'@'localhost';
GRANT UPDATE (DeptDesc, DeptParentID, DeptTIN, DeptAddress, DeptIsDisabled) ON dept TO 'coop_app'@'%';
