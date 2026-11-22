-- Chart of accounts details (Accounting > Chart of Accounts, ChartOfAccountsController): what the old coa table has no column for. The normal balance of an
-- account follows its type (debit for assets and expenses, credit for liabilities, equity, revenues and net income); a row here with normal_balance records a
-- contra account that runs the other way (an allowance for losses, accumulated depreciation), and description says what the account is for. A missing row
-- means the type's own normal balance and no description. Changed with the account, reason required, audited in the account's chart.account.* rows.
-- The MySQL copy is V076. Applied with scripts/migrate-postgres.ps1, or from the application (Apply them now).
CREATE TABLE legacy.ecis_coa_detail (
    branch_code    integer      NOT NULL,
    account_code   varchar(10)  NOT NULL,
    normal_balance char(1),
    description    varchar(250),
    updated_by     varchar(60)  NOT NULL,
    updated_at     timestamp(3) NOT NULL,
    PRIMARY KEY (branch_code, account_code),
    CONSTRAINT ecis_coa_detail_balance CHECK (normal_balance IS NULL OR normal_balance IN ('D', 'C'))
);
GRANT SELECT, INSERT, UPDATE ON legacy.ecis_coa_detail TO coop_app;
