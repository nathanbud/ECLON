-- Profiling and Deduction become separate permissions and steps of a loan application (Settings > Users > Receivables > Loan process).
-- 1. A new switch on the old system's user table: AccessCanDoLoanDeduction ("2 · Deduction"). The old system does not know it and ignores it.
--    Everyone who may add loan applications today gets it, so nobody loses what they can do: until now saving an application did both steps.
-- 2. ecis_loanapp_step: who did Profiling and Deduction of an application saved by this application, and when. An application with no Deduction yet
--    is "waiting for deductions": verification and approval refuse it. Applications saved before this (no row) count as complete.
-- The MySQL copy is V062. Apply with scripts/migrate-postgres.ps1.
ALTER TABLE legacy.access ADD COLUMN accesscandoloandeduction smallint;
UPDATE legacy.access SET accesscandoloandeduction = COALESCE(accesscanaddloanapplication, 0);
GRANT UPDATE (accesscandoloandeduction) ON legacy.access TO coop_app;

CREATE TABLE legacy.ecis_loanapp_step (
    branch_code   integer     NOT NULL,
    loanapp_id    bigint      NOT NULL,
    profiled_by   varchar(60) NOT NULL,
    profiled_at   timestamp   NOT NULL,
    deductions_by varchar(60),
    deductions_at timestamp,
    PRIMARY KEY (branch_code, loanapp_id)
);
GRANT SELECT, INSERT, UPDATE ON legacy.ecis_loanapp_step TO coop_app;
