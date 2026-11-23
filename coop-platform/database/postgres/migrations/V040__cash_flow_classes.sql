-- Statement of Cash Flows (Accounting > Statements, FinancialStatementController): the class of each balance-sheet account for the indirect method: CASH (cash
-- and cash equivalents, whose change the statement explains), OPERATING, INVESTING or FINANCING. A row may name a heading (it then applies to every account
-- under it) or an account; the nearest row above an account wins, and an account with none takes the class suggested from the chart's headings
-- (CashFlowRules.suggest). Accountants and administrators change them with a reason, audited accounting.cashflow.class.
-- The MySQL copy is V077. Applied with scripts/migrate-postgres.ps1, or from the application (Apply them now).
CREATE TABLE legacy.ecis_cash_flow_class (
    branch_code  integer      NOT NULL,
    account_code varchar(10)  NOT NULL,
    category     varchar(12)  NOT NULL,
    updated_by   varchar(60)  NOT NULL,
    updated_at   timestamp(3) NOT NULL,
    PRIMARY KEY (branch_code, account_code),
    CONSTRAINT ecis_cash_flow_class_category CHECK (category IN ('CASH', 'OPERATING', 'INVESTING', 'FINANCING'))
);
GRANT SELECT, INSERT, UPDATE, DELETE ON legacy.ecis_cash_flow_class TO coop_app;
