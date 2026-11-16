-- Loan setup screen (administrators only): create, edit and disable loan types, and keep the lists of loan purposes and collateral types.
--  * sltype (class 12): INSERT a new loan type and UPDATE an existing one. A new type is a copy of an existing one, so it also copies that
--    type's rows in glcontrol (the general ledger accounts it posts to) and loansldeduc (its deductions): INSERT on both.
--    Loan types are never deleted (loans point at them); an unwanted one is disabled (SLTypeIsDisabled).
--  * loanpurpose, collateraltype: INSERT, UPDATE and, for entries nothing refers to, DELETE.
-- The app only runs these for people with the ADMIN role and writes an audit row (ecis_audit_log) for each change.
-- Run by an administrator. Idempotent.
GRANT INSERT, UPDATE ON sltype TO 'coop_app'@'localhost';
GRANT INSERT, UPDATE ON sltype TO 'coop_app'@'%';
GRANT INSERT ON glcontrol TO 'coop_app'@'localhost';
GRANT INSERT ON glcontrol TO 'coop_app'@'%';
GRANT INSERT ON loansldeduc TO 'coop_app'@'localhost';
GRANT INSERT ON loansldeduc TO 'coop_app'@'%';
GRANT INSERT, UPDATE, DELETE ON loanpurpose TO 'coop_app'@'localhost';
GRANT INSERT, UPDATE, DELETE ON loanpurpose TO 'coop_app'@'%';
GRANT INSERT, UPDATE, DELETE ON collateraltype TO 'coop_app'@'localhost';
GRANT INSERT, UPDATE, DELETE ON collateraltype TO 'coop_app'@'%';
