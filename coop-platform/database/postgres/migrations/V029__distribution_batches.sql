-- Interest on share capital and patronage refund: stored yearly distributions, their review and approval, and the settings they start from
-- (pics/divpat_system.docx sections 2, 4, 5, 6 and 11; documentation/dividend-spec.md). The vouchers (module 6 type 54) are still the ledger record;
-- these tables keep the computation that was approved, member by member, so it can be reported and never changes after approval.
-- The MySQL copy is V066. Apply with scripts/migrate-postgres.ps1.

-- What counts and who is eligible, per branch. Nothing statutory is built in: the defaults reproduce the old system's 2026 run (loan events 23, 24
-- and 31 as patronage; every member with something to receive). Changed by administrators, with a reason.
CREATE TABLE legacy.ecis_distribution_policy (
    branch_code       integer       PRIMARY KEY,
    patronage_events  varchar(100)  NOT NULL,      -- loan ledger events (sle_type) whose payments are qualified patronage, comma separated
    eligible_statuses varchar(200),                -- member statuses (acctstat) that take part; empty = every status
    isc_types         varchar(400),                -- member types (clienttype) that earn interest on share capital; empty = every type
    pr_types          varchar(400),                -- member types that earn a patronage refund; empty = every type
    min_average_share numeric(15,2) NOT NULL DEFAULT 0, -- below this average share-month, no interest on share capital
    updated_by        varchar(60)   NOT NULL,
    updated_at        timestamp(3)  NOT NULL
);
INSERT INTO legacy.ecis_distribution_policy (branch_code, patronage_events, updated_by, updated_at) VALUES (1, '23,24,31', 'setup', now());
GRANT SELECT, INSERT, UPDATE ON legacy.ecis_distribution_policy TO coop_app;

-- The statutory funds set aside from the net surplus before distribution (RA 9520 Article 86 and the bylaws), with their percent. None are built in.
CREATE TABLE legacy.ecis_distribution_fund (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    branch_code integer      NOT NULL,
    name        varchar(80)  NOT NULL,
    percent     numeric(7,4) NOT NULL,
    account     varchar(20),
    position    integer      NOT NULL,
    active      smallint     NOT NULL DEFAULT 1,
    updated_by  varchar(60)  NOT NULL,
    updated_at  timestamp(3) NOT NULL
);
GRANT SELECT, INSERT, UPDATE ON legacy.ecis_distribution_fund TO coop_app;

-- One distribution for a financial year: DRAFT (prepared) -> SUBMITTED (for review) -> APPROVED (by another person) -> ENCODED (vouchers made);
-- or REJECTED (with a reason), or SUPERSEDED (prepared again before approval). Its figures are written when it is prepared and never changed.
CREATE TABLE legacy.ecis_distribution_batch (
    id                     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    branch_code            integer       NOT NULL,
    year                   integer       NOT NULL,   -- the financial year distributed (the calendar year before the cut-off)
    cutoff                 date          NOT NULL,
    method                 varchar(12)   NOT NULL,   -- RATES (typed) or ALLOCATION (from the approved allocation)
    status                 varchar(12)   NOT NULL,
    net_surplus            numeric(18,2),
    available              numeric(18,2),
    isc_allocation         numeric(18,2),
    pr_allocation          numeric(18,2),
    approval_ref           varchar(200),
    dividend_rate          numeric(20,10) NOT NULL,
    patronage_rate         numeric(20,10) NOT NULL,
    mutual_benefit         numeric(15,2),
    hospitalization        numeric(15,2),
    overrides              integer       NOT NULL DEFAULT 0,
    total_average_share    numeric(18,2) NOT NULL,
    total_patronage        numeric(18,2) NOT NULL,
    members                integer       NOT NULL,
    total_isc              numeric(18,2) NOT NULL,
    total_pr               numeric(18,2) NOT NULL,
    total                  numeric(18,2) NOT NULL,
    held                   integer       NOT NULL,
    excluded               integer       NOT NULL,
    policy                 varchar(1000) NOT NULL,   -- the settings it was worked with, in words
    prepared_by            varchar(60)   NOT NULL,
    prepared_at            timestamp(3)  NOT NULL,
    submitted_by           varchar(60),
    submitted_at           timestamp(3),
    decided_by             varchar(60),
    decided_at             timestamp(3),
    decision_note          varchar(300),
    superseded_by          bigint,
    encoded_by             varchar(60),
    encoded_at             timestamp(3),
    voucher_year           integer,
    voucher_first          bigint,
    voucher_last           bigint
);
-- one live distribution per branch and year
CREATE UNIQUE INDEX ux_ecis_distribution_batch_live ON legacy.ecis_distribution_batch (branch_code, year) WHERE status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'ENCODED');
CREATE INDEX ix_ecis_distribution_batch_year ON legacy.ecis_distribution_batch (branch_code, year);
GRANT SELECT, INSERT, UPDATE ON legacy.ecis_distribution_batch TO coop_app;

-- The statutory funds of a distribution, as they were applied.
CREATE TABLE legacy.ecis_distribution_batch_fund (
    batch_id bigint        NOT NULL,
    position integer       NOT NULL,
    name     varchar(80)   NOT NULL,
    percent  numeric(7,4)  NOT NULL,
    amount   numeric(18,2) NOT NULL,
    PRIMARY KEY (batch_id, position)
);

-- Each member's computation.
CREATE TABLE legacy.ecis_distribution_member (
    batch_id            bigint        NOT NULL,
    client_id           integer       NOT NULL,
    average_share       numeric(18,2) NOT NULL,
    qualified_patronage numeric(18,2) NOT NULL,
    isc                 numeric(15,2) NOT NULL,
    pr                  numeric(15,2) NOT NULL,
    total               numeric(15,2) NOT NULL,
    mutual_benefit      numeric(15,2) NOT NULL,
    hospitalization     numeric(15,2) NOT NULL,
    rest                numeric(15,2) NOT NULL,
    PRIMARY KEY (batch_id, client_id)
);

-- The credits the vouchers will carry, account by account, in order.
CREATE TABLE legacy.ecis_distribution_credit (
    batch_id  bigint        NOT NULL,
    seq       integer       NOT NULL,
    client_id integer       NOT NULL,
    slc_code  integer       NOT NULL,
    slt_code  integer       NOT NULL,
    ref_no    integer       NOT NULL,
    kind      varchar(20)   NOT NULL,
    amount    numeric(15,2) NOT NULL,
    PRIMARY KEY (batch_id, seq)
);

-- Members left out: no account to credit, or not eligible under the settings.
CREATE TABLE legacy.ecis_distribution_held (
    batch_id  bigint        NOT NULL,
    client_id integer       NOT NULL,
    total     numeric(15,2) NOT NULL,
    reason    varchar(200)  NOT NULL,
    PRIMARY KEY (batch_id, client_id)
);

-- Qualified patronage by loan event (interest, service fee and so on), for the patronage refund summary.
CREATE TABLE legacy.ecis_distribution_patronage (
    batch_id bigint        NOT NULL,
    event    integer       NOT NULL,
    amount   numeric(18,2) NOT NULL,
    members  integer       NOT NULL,
    PRIMARY KEY (batch_id, event)
);

GRANT SELECT, INSERT ON legacy.ecis_distribution_batch_fund, legacy.ecis_distribution_member, legacy.ecis_distribution_credit,
    legacy.ecis_distribution_held, legacy.ecis_distribution_patronage TO coop_app;
CREATE TRIGGER deny_update BEFORE UPDATE ON legacy.ecis_distribution_batch_fund FOR EACH STATEMENT EXECUTE FUNCTION legacy.deny_update();
CREATE TRIGGER deny_update BEFORE UPDATE ON legacy.ecis_distribution_member FOR EACH STATEMENT EXECUTE FUNCTION legacy.deny_update();
CREATE TRIGGER deny_update BEFORE UPDATE ON legacy.ecis_distribution_credit FOR EACH STATEMENT EXECUTE FUNCTION legacy.deny_update();
CREATE TRIGGER deny_update BEFORE UPDATE ON legacy.ecis_distribution_held FOR EACH STATEMENT EXECUTE FUNCTION legacy.deny_update();
CREATE TRIGGER deny_update BEFORE UPDATE ON legacy.ecis_distribution_patronage FOR EACH STATEMENT EXECUTE FUNCTION legacy.deny_update();
