-- Opening balances for loans and time deposits (Accounting > Opening balances, OpeningBalanceController): a loan a member still pays, or a certificate still
-- running, on the day the cooperative starts on this system. Such a line opens the account when the batch is posted (loan with its schedule, or certificate),
-- so it carries the terms: for a loan its status, yearly rate, remaining term in months, next payment date and, for the record, the amount and date it was
-- granted; for a time deposit its rate, term in days, placement date, interest accrued to the opening date and certificate number. The account number given
-- at posting is written back into ref_no. The MySQL copy is V083. Applied with scripts/migrate-postgres.ps1, or from the application (Apply them now).
ALTER TABLE legacy.ecis_opening_line DROP CONSTRAINT ecis_opening_line_kind;
ALTER TABLE legacy.ecis_opening_line ADD CONSTRAINT ecis_opening_line_kind CHECK (kind IN ('GL', 'SAVINGS', 'SHARE', 'LOAN', 'TD'));
ALTER TABLE legacy.ecis_opening_line ADD COLUMN status_code     integer;
ALTER TABLE legacy.ecis_opening_line ADD COLUMN rate            numeric(9,4);
ALTER TABLE legacy.ecis_opening_line ADD COLUMN terms           integer;
ALTER TABLE legacy.ecis_opening_line ADD COLUMN start_date      date;
ALTER TABLE legacy.ecis_opening_line ADD COLUMN original_amount numeric(15,2);
ALTER TABLE legacy.ecis_opening_line ADD COLUMN granted_on      date;
ALTER TABLE legacy.ecis_opening_line ADD COLUMN accrued         numeric(15,2);
ALTER TABLE legacy.ecis_opening_line ADD COLUMN cert_no         varchar(20);
GRANT UPDATE (ref_no) ON legacy.ecis_opening_line TO coop_app;
