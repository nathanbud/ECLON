-- Budgets (Accounting > Budget, BudgetController): the annual budget of each income and expense account, per branch and year. The Budget versus actual report
-- spreads it evenly over the months of the year and sets it against the ledger. Accountants and administrators change it, a whole list at a time, with a
-- reason, audited accounting.budget.change per account. The MySQL copy is V078. Applied with scripts/migrate-postgres.ps1, or from the application.
CREATE TABLE legacy.ecis_budget (
    branch_code  integer       NOT NULL,
    budget_year  integer       NOT NULL,
    account_code varchar(10)   NOT NULL,
    amount       numeric(17,2) NOT NULL,
    updated_by   varchar(60)   NOT NULL,
    updated_at   timestamp(3)  NOT NULL,
    PRIMARY KEY (branch_code, budget_year, account_code),
    CONSTRAINT ecis_budget_amount CHECK (amount >= 0)
);
GRANT SELECT, INSERT, UPDATE, DELETE ON legacy.ecis_budget TO coop_app;
