-- Closing reviews (Accounting > Closing, CloseController): the month-end review and the year-end checklist. Each item is checked by the application where it can
-- (the books balance, entries posted, banks reconciled, depreciation run, business days ended) and otherwise confirmed by an accountant with a note; an item the
-- application finds wanting can be accepted with an explanation. A month is signed off when its review is complete; when the policy
-- accounting.monthly_close is on, signing it off also closes its accounting period. The year-end close cannot be prepared or posted until the year's checklist
-- is complete, and it is shown as overdue once accounting.year_end_due_days have passed after 31 December. Every tick and sign-off is audited.
-- The MySQL copy is V079. Applied with scripts/migrate-postgres.ps1, or from the application (Apply them now).
CREATE TABLE legacy.ecis_close_review (
    branch_code integer      NOT NULL,
    scope       varchar(5)   NOT NULL,
    period      varchar(7)   NOT NULL,
    item        varchar(40)  NOT NULL,
    note        varchar(500),
    done_by     varchar(60)  NOT NULL,
    done_at     timestamp(3) NOT NULL,
    PRIMARY KEY (branch_code, scope, period, item),
    CONSTRAINT ecis_close_review_scope CHECK (scope IN ('MONTH', 'YEAR'))
);
CREATE TABLE legacy.ecis_close_signoff (
    branch_code integer      NOT NULL,
    scope       varchar(5)   NOT NULL,
    period      varchar(7)   NOT NULL,
    signed_by   varchar(60)  NOT NULL,
    signed_at   timestamp(3) NOT NULL,
    note        varchar(500),
    PRIMARY KEY (branch_code, scope, period)
);
GRANT SELECT, INSERT, UPDATE, DELETE ON legacy.ecis_close_review TO coop_app;
GRANT SELECT, INSERT ON legacy.ecis_close_signoff TO coop_app;

INSERT INTO legacy.ecis_setting (setting_key, setting_value, updated_by, updated_at)
VALUES ('accounting.monthly_close', 'false', 'system', CURRENT_TIMESTAMP), ('accounting.year_end_due_days', '120', 'system', CURRENT_TIMESTAMP)
ON CONFLICT (setting_key) DO NOTHING;
