-- Department kinds and statements per unit (Settings > Organization > Departments; Accounting > Statements). The old table dept is a tree (DeptParentID):
-- consolidations hold a main office, branches and satellite offices, which may hold smaller units (buildings, projects). Every ledger entry carries its
-- department (gltrans.GLTransDEPT), so a statement can be made for any unit with everything under it, and a consolidating statement shows each unit beside
-- the others. A branch and a satellite office are different kinds of unit, so each department records what it is. A department with no row here is shown
-- with a kind suggested from its name and place in the tree until an administrator chooses one (reason required, audited department.kind).
-- Finished years are summed once per department, as ecis_gl_year_balance does for the whole branch (see V009 in MySQL), by the same Build balances run;
-- a year-end close rebuilds its year. The MySQL copy is V075. Applied with scripts/migrate-postgres.ps1, or from the application (Apply them now).
CREATE TABLE legacy.ecis_department_kind (
    dept_id    integer      PRIMARY KEY,
    kind       varchar(20)  NOT NULL,
    updated_by varchar(60)  NOT NULL,
    updated_at timestamp(3) NOT NULL,
    CONSTRAINT ecis_department_kind_kind CHECK (kind IN ('MAIN_OFFICE', 'BRANCH', 'SATELLITE_OFFICE', 'CONSOLIDATION', 'OTHER'))
);
GRANT SELECT, INSERT, UPDATE ON legacy.ecis_department_kind TO coop_app;

CREATE TABLE legacy.ecis_gl_year_dept_built (
    branch_code integer     NOT NULL,
    tr_year     integer     NOT NULL,
    built_by    varchar(60) NOT NULL,
    built_at    timestamp   NOT NULL,
    PRIMARY KEY (branch_code, tr_year)
);
-- net = sum(debit) + sum(credit); credits are stored negative in gltrans, so a debit balance is positive.
CREATE TABLE legacy.ecis_gl_year_dept_balance (
    branch_code  integer       NOT NULL,
    tr_year      integer       NOT NULL,
    dept         integer       NOT NULL,
    account_code varchar(10)   NOT NULL,
    net          numeric(17,2) NOT NULL,
    PRIMARY KEY (branch_code, tr_year, dept, account_code)
);
GRANT SELECT, INSERT, DELETE ON legacy.ecis_gl_year_dept_built TO coop_app;
GRANT SELECT, INSERT, DELETE ON legacy.ecis_gl_year_dept_balance TO coop_app;
