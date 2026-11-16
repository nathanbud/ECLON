-- Loan deductions setup (Loan Setup > a loan type > Deductions): an administrator adds, changes and removes the deduction lines a loan type starts an application with
-- (table loansldeduc). INSERT and SELECT were already granted; this adds UPDATE and DELETE. Each change needs a reason and is audited with the values before and after.
-- The PostgreSQL copy is V020. Run by an administrator. Idempotent.
GRANT UPDATE, DELETE ON loansldeduc TO 'coop_app'@'localhost';
GRANT UPDATE, DELETE ON loansldeduc TO 'coop_app'@'%';
