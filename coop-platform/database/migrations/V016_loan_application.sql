-- Creating a loan application (status New) writes the loan account row in slmastersum, then one row each to loanapp and loan, the repayment schedule (amortsched), the deductions the
-- clerk accepted (loandeduc) and the co-makers (comaker). Insert only: nothing existing is changed. Verify and approve (later steps) will need
-- column UPDATE on loanapp and are not granted here. Run by an administrator. Idempotent.
GRANT INSERT ON loanapp TO 'coop_app'@'localhost';
GRANT INSERT ON loan TO 'coop_app'@'localhost';
GRANT INSERT ON amortsched TO 'coop_app'@'localhost';
GRANT INSERT ON loandeduc TO 'coop_app'@'localhost';
GRANT INSERT ON comaker TO 'coop_app'@'localhost';
GRANT INSERT ON loanapp TO 'coop_app'@'%';
GRANT INSERT ON loan TO 'coop_app'@'%';
GRANT INSERT ON amortsched TO 'coop_app'@'%';
GRANT INSERT ON loandeduc TO 'coop_app'@'%';
GRANT INSERT ON comaker TO 'coop_app'@'%';
-- The loan's own account row in slmastersum must exist first (foreign key from loanapp, loan, amortsched, comaker).
GRANT INSERT ON slmastersum TO 'coop_app'@'localhost';
GRANT INSERT ON slmastersum TO 'coop_app'@'%';
