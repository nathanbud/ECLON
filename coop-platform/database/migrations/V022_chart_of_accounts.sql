-- Chart of accounts setup: lets the app add accounts to coa and change their name, parent, posting flag and disabled flag.
-- Accounts are never deleted and the account number never changes. Run by an administrator. Idempotent.
GRANT INSERT ON coa TO 'coop_app'@'localhost';
GRANT INSERT ON coa TO 'coop_app'@'%';
GRANT UPDATE (COADesc, COAacct_sum, COAisdisabled, COAcanGLOnly, COAUpdUser, COAupdDateTime) ON coa TO 'coop_app'@'localhost';
GRANT UPDATE (COADesc, COAacct_sum, COAisdisabled, COAcanGLOnly, COAUpdUser, COAupdDateTime) ON coa TO 'coop_app'@'%';
