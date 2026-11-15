-- Loan deductions setup (Loan Setup > a loan type > Deductions): an administrator adds, changes and removes the deduction lines a loan type starts an application with
-- (table loansldeduc). INSERT and SELECT were already granted; this adds UPDATE and DELETE. Each change needs a reason and is audited with the values before and after.
-- A line that was already used keeps its amounts on the applications (they are copied into loandeduc), so removing a line from a type does not touch them.
-- The MySQL copy is V057. Apply with scripts/migrate-postgres.ps1.
GRANT UPDATE, DELETE ON legacy.loansldeduc TO coop_app;
