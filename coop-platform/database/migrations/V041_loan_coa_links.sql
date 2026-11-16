-- Loan type ledger links (glcontrol, loan class 12): an administrator can add, change and remove a loan type's links to the chart of accounts from Loan Setup > COA Links.
-- INSERT was granted by V020; this adds UPDATE and DELETE. Only links of loan types are touched by the application, each change needs a reason and is audited with the values before and after.
-- Run by an administrator. Idempotent.
GRANT UPDATE, DELETE ON glcontrol TO 'coop_app'@'localhost';
GRANT UPDATE, DELETE ON glcontrol TO 'coop_app'@'%';
