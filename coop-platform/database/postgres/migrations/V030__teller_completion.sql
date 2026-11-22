-- The rest of the teller module (documentation/general-voucher-map.md, documentation/state/business-day.md): the cooperative's own cheque on a Check
-- Voucher while it waits for approval, the start-of-day count of each drawer, and the end-of-day jobs with what they keep (balances, loan aging).
-- The MySQL copy is V067. Apply with scripts/migrate-postgres.ps1.

-- A Check Voucher (module 2, type 22) encoded here: its cheque, kept until the voucher is posted, when the tender row (transck) is written from it.
-- Also the register of cheque numbers used, so the same cheque of a bank account is not written on two vouchers.
CREATE TABLE legacy.ecis_voucher_cheque (
    branch_code integer       NOT NULL,
    tr_year     integer       NOT NULL,
    tr_code     integer       NOT NULL,
    tr_ctlno    bigint        NOT NULL,
    bank_code   integer       NOT NULL,   -- bankcode.BCID, the cooperative's bank account the cheque is drawn on
    cheque_no   varchar(15)   NOT NULL,
    cheque_date date          NOT NULL,
    payee       varchar(60)   NOT NULL,
    amount      numeric(15,2) NOT NULL,
    created_by  varchar(60)   NOT NULL,
    created_at  timestamp(3)  NOT NULL,
    PRIMARY KEY (branch_code, tr_year, tr_code, tr_ctlno)
);
CREATE UNIQUE INDEX ux_voucher_cheque_number ON legacy.ecis_voucher_cheque (branch_code, bank_code, cheque_no);
GRANT SELECT, INSERT ON legacy.ecis_voucher_cheque TO coop_app;

-- The count of a drawer at the start of a business day (blind: the ledger's figure is shown after); one per drawer per day, never changed.
CREATE TABLE legacy.ecis_drawer_open (
    branch_code   integer       NOT NULL,
    business_date date          NOT NULL,
    event         integer       NOT NULL,   -- 52 cashier, 53 teller
    drawer_number integer       NOT NULL,
    access_id     integer       NOT NULL,
    username      varchar(60)   NOT NULL,
    expected      numeric(15,2) NOT NULL,
    counted       numeric(15,2) NOT NULL,
    difference    numeric(15,2) NOT NULL,
    counts        varchar(400)  NOT NULL,   -- {"denomtype id": count}
    reason        varchar(250),
    opened_at     timestamp(3)  NOT NULL,
    PRIMARY KEY (branch_code, business_date, event, drawer_number)
);
GRANT SELECT, INSERT ON legacy.ecis_drawer_open TO coop_app;
CREATE TRIGGER deny_update BEFORE UPDATE ON legacy.ecis_drawer_open FOR EACH STATEMENT EXECUTE FUNCTION legacy.deny_update();

-- The end-of-day jobs of a business day and each of their steps (run again until done).
CREATE TABLE legacy.ecis_eod_run (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    branch_code   integer      NOT NULL,
    business_date date         NOT NULL,
    status        varchar(12)  NOT NULL,   -- RUNNING, DONE, FAILED
    started_by    varchar(60)  NOT NULL,
    started_at    timestamp(3) NOT NULL,
    finished_at   timestamp(3),
    UNIQUE (branch_code, business_date)
);
GRANT SELECT, INSERT, UPDATE ON legacy.ecis_eod_run TO coop_app;

CREATE TABLE legacy.ecis_eod_step (
    run_id      bigint        NOT NULL REFERENCES legacy.ecis_eod_run (id),
    step        varchar(30)   NOT NULL,
    status      varchar(12)   NOT NULL,   -- DONE, FAILED
    attempts    integer       NOT NULL,
    started_at  timestamp(3)  NOT NULL,
    finished_at timestamp(3),
    result      varchar(2000),
    error       varchar(2000),
    PRIMARY KEY (run_id, step)
);
GRANT SELECT, INSERT, UPDATE ON legacy.ecis_eod_step TO coop_app;

-- Balances as of the end of a business day: each drawer's cash (DRAWER, item = the drawer) and each account type (PRODUCT, item = class/type).
-- A day's rows are replaced when its jobs run again.
CREATE TABLE legacy.ecis_eod_balance (
    branch_code   integer       NOT NULL,
    business_date date          NOT NULL,
    kind          varchar(12)   NOT NULL,
    item          varchar(40)   NOT NULL,
    name          varchar(100),
    amount        numeric(18,2) NOT NULL,
    accounts      integer       NOT NULL DEFAULT 0,
    PRIMARY KEY (branch_code, business_date, kind, item)
);
GRANT SELECT, INSERT, DELETE ON legacy.ecis_eod_balance TO coop_app;

-- Every open loan aged at the end of a business day (nothing posted). A day's rows are replaced when its jobs run again.
CREATE TABLE legacy.ecis_loan_aging (
    branch_code        integer       NOT NULL,
    business_date      date          NOT NULL,
    client_id          integer       NOT NULL,
    slt_code           integer       NOT NULL,
    ref_no             integer       NOT NULL,
    outstanding        numeric(15,2) NOT NULL,
    past_due_principal numeric(15,2) NOT NULL,
    days_past_due      integer       NOT NULL,
    past_due_since     date,
    interest_due       numeric(15,2) NOT NULL,
    penalty            numeric(15,2),            -- by the formula (penalty mode 4); empty where the cashier types it
    bucket             varchar(12)   NOT NULL,
    PRIMARY KEY (branch_code, business_date, slt_code, ref_no, client_id)
);
CREATE INDEX ix_loan_aging_bucket ON legacy.ecis_loan_aging (branch_code, business_date, bucket);
GRANT SELECT, INSERT, DELETE ON legacy.ecis_loan_aging TO coop_app;
