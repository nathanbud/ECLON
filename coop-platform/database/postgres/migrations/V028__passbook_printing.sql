-- Passbook printing (Front Office > Passbook). The old system keeps where each account's passbook stands in passbk (one row per account: the passbook
-- number, the last line printed, and the last ledger entry printed with its date and the balance after it); this application prints the entries after
-- that one and moves the same row on, so the old system and this one agree. Printed on the foldable savings passbook (5 1/2 x 8 inches, columns Date,
-- Reference, Deposit, Withdrawal, Balance, TC, Teller No.).
-- The MySQL copy is V065. Apply with scripts/migrate-postgres.ps1.

-- Where the lines go on the page, in inches from the top left corner of the open passbook; one row per passbook kind (22 savings). Changed by
-- administrators in Front Office > Passbook > Layout, with a test page to line it up on the real printer.
CREATE TABLE legacy.ecis_passbook_layout (
    passbook_kind   integer      PRIMARY KEY,
    page_width      numeric(5,3) NOT NULL,
    page_height     numeric(5,3) NOT NULL,
    lines_per_page  integer      NOT NULL,
    first_line      numeric(5,3) NOT NULL,   -- the baseline of line 1, from the top edge
    line_height     numeric(5,3) NOT NULL,
    fold_after      integer      NOT NULL,   -- the last line above the fold
    fold_gap        numeric(5,3) NOT NULL,   -- extra space the fold takes
    font_size       numeric(4,1) NOT NULL,   -- points
    date_x          numeric(5,3) NOT NULL,   -- left edge
    reference_x     numeric(5,3) NOT NULL,   -- left edge
    deposit_right   numeric(5,3) NOT NULL,   -- right edge
    withdrawal_right numeric(5,3) NOT NULL,  -- right edge
    balance_right   numeric(5,3) NOT NULL,   -- right edge
    tc_x            numeric(5,3) NOT NULL,   -- left edge
    teller_x        numeric(5,3) NOT NULL,   -- left edge
    shift_x         numeric(5,3) NOT NULL DEFAULT 0, -- the printer's own offset, found with the test page
    shift_y         numeric(5,3) NOT NULL DEFAULT 0,
    updated_by      varchar(60)  NOT NULL,
    updated_at      timestamp(3) NOT NULL
);
INSERT INTO legacy.ecis_passbook_layout VALUES (22, 5.5, 8, 31, 0.55, 0.2, 16, 0.35, 8, 0.15, 0.85, 2.65, 3.6, 4.65, 4.75, 5.1, 0, 0, 'setup', now());
GRANT SELECT, UPDATE ON legacy.ecis_passbook_layout TO coop_app;

-- Every print, reprint and change of position; never changed.
CREATE TABLE legacy.ecis_passbook_print (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    branch_code integer      NOT NULL,
    client_id   integer      NOT NULL,
    slc_code    integer      NOT NULL,
    slt_code    integer      NOT NULL,
    ref_no      varchar(20)  NOT NULL,
    action      varchar(20)  NOT NULL,   -- PRINT, REPRINT, NEW_PASSBOOK, POSITION
    serial      varchar(20),
    from_seq    integer,
    to_seq      integer,
    first_line  integer,
    last_line   integer,
    username    varchar(60)  NOT NULL,
    at          timestamp(3) NOT NULL,
    reason      varchar(300)
);
CREATE INDEX ix_ecis_passbook_print_account ON legacy.ecis_passbook_print (branch_code, client_id, slc_code, slt_code, ref_no);
GRANT SELECT, INSERT ON legacy.ecis_passbook_print TO coop_app;
CREATE TRIGGER deny_update BEFORE UPDATE ON legacy.ecis_passbook_print FOR EACH STATEMENT EXECUTE FUNCTION legacy.deny_update();

-- Moving a passbook on after a print, and recording a new passbook for an account that has none.
GRANT INSERT ON legacy.passbk TO coop_app;
GRANT UPDATE (passbksernum, passbklinenum, passbkltr_date, passbkbal, passbkseqno) ON legacy.passbk TO coop_app;
