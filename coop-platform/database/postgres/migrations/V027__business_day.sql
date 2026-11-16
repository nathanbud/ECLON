-- The business day (Start Day and End Day), as core banking systems keep it: each branch has one open business date, and teller and front-office
-- postings take that date instead of the computer's. Start Day opens a date (never later than the real date, always after the last closed one);
-- End Day checks the day (every drawer closed, hand-offs settled, debits equal credits) and closes it, or closes it anyway with a reason.
-- The old system kept this in semaphor (SemTransactionDate, SemStartDay); that table is read once for the starting point and never changed.
-- The rights are the old system's own switches on access, AccessStartDay and AccessEndDay (Settings > Users > Transactions > Business day).
-- The MySQL copy is V064. Apply with scripts/migrate-postgres.ps1.
CREATE TABLE legacy.ecis_business_day (
    branch_code     integer      NOT NULL,
    business_date   date         NOT NULL,
    status          varchar(10)  NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    opened_by       varchar(60)  NOT NULL,
    opened_at       timestamp(3) NOT NULL,
    closed_by       varchar(60),
    closed_at       timestamp(3),
    -- set when End Day went ahead although a check failed: the reason given, and which checks failed
    override_reason varchar(300),
    failed_checks   varchar(2000),
    PRIMARY KEY (branch_code, business_date)
);
-- one open day per branch
CREATE UNIQUE INDEX ux_ecis_business_day_open ON legacy.ecis_business_day (branch_code) WHERE status = 'OPEN';
GRANT SELECT, INSERT, UPDATE ON legacy.ecis_business_day TO coop_app;

-- Every Start Day, End Day and reopening, in order; never changed.
CREATE TABLE legacy.ecis_business_day_log (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    branch_code   integer      NOT NULL,
    business_date date         NOT NULL,
    action        varchar(20)  NOT NULL,
    username      varchar(60)  NOT NULL,
    at            timestamp(3) NOT NULL,
    detail        varchar(2000),
    reason        varchar(300)
);
CREATE INDEX ix_ecis_business_day_log_day ON legacy.ecis_business_day_log (branch_code, business_date);
GRANT SELECT, INSERT ON legacy.ecis_business_day_log TO coop_app;
CREATE TRIGGER deny_update BEFORE UPDATE ON legacy.ecis_business_day_log FOR EACH STATEMENT EXECUTE FUNCTION legacy.deny_update();

-- Settings > Users may now switch the two rights.
GRANT UPDATE (accessstartday, accessendday) ON legacy.access TO coop_app;
