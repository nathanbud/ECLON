# Reporting (ECIS.pdf phase 20)

**A note on phase numbers.** The guide's list near the start (21 phases) is shorter than its body. The body has 33 phases (0 to 33): 12 Procurement, 13 Inventory, 14 Merchandising/POS, 15 Fixed Assets, **16 Document Management, 17 Workflow Engine, 18 Audit and Compliance, 19 Data Privacy, 20 Reporting, 21 Management Dashboard, 22 Data Warehouse**, 23 to 27 testing (unit/integration/end-to-end, reconciliation, security, performance, user acceptance), 28 Data Migration, 29 to 33 production infrastructure, backup and DR, deployment, go-live and post-go-live. Reporting was built before 16 to 19 because it reads what already exists; those four are still to do.

## What the guide asks for (phase 20)

Membership (new, active, inactive, growth); Loans (portfolio, PAR, delinquency, collection, maturity, outstanding balance, releases, payments); Accounting (trial balance, general ledger, balance sheet, income/operations, cash flow); Inventory (stock balance, movement, low stock, valuation, count variance); POS (daily sales, by product, by branch, returns, discounts); CIC (submission status, accepted, rejected, errors, corrections).

## Built (2026-10-29)

A small framework, `ph.coop.core.report.Reports`: a report declares its parameters and returns a typed table (columns, rows, a totals row, a note); a group of reports is a `Provider` bean and `ReportController` collects them. `GET /api/reports` lists what the caller's roles allow, `GET /api/reports/{id}?param=...` runs one and **writes an audit row** (`report.run`, with the parameters). The Reports tile opens one screen (`ReportsPage`) that builds each form from the report's parameters, shows the table, prints it and saves it as CSV; a link leads on to the existing Accounting screen (trial balance, statements, journals, periods, banks).

| Group | Reports | Who (besides administrators and accountants) |
|---|---|---|
| Membership | Members by status (from the `acctstat` lookup), Membership growth (joined and left per month) | Membership Officer |
| Loans | Loan portfolio and outstanding balance (by type), Loans by status, Portfolio at risk (days-late buckets and PAR over 30), Delinquent loans (worst first, with the member), Loans maturing, Loan releases, Loan payments (principal, interest, penalty), Collection efficiency (due by schedule against collected) | accountants and administrators only |
| Accounting | General ledger of one account (opening balance, postings, running balance) | accountants and administrators only |
| Inventory | Stock balance, Stock movement, Low stock, Stock valuation, Stock count variance | Storekeeper, Stock Approver, Purchaser, Purchase Approver |
| POS | Daily sales, Sales by product, Sales by store, Returns, Discounts given | POS Supervisor, POS Cashier |

* **Loan figures** come from the loan ledger (class 12, event 11 principal, 23 interest, 41 penalty) and the repayment schedule; lateness is the existing arrears rule (`LoanRules.arrears`: principal only, days since the oldest unpaid instalment). On 2026-04-24, the last day of the old data, the report finds 10,512 loans with 2,699,050,350.25 outstanding, 2,548 more than 30 days late and a **PAR over 30 days of 31.47%**; the portfolio total agrees with the status and PAR reports, and the PAR buckets over 30 days equal the delinquent list. Loan reports scan the whole ledger and take roughly 10 to 30 seconds (the position table is cached for five minutes per date).
* "By branch" for POS is "by store" (warehouse), because the point of sale sells from a store; the cooperative's branch is a single branch in the data.
* Tests: `ReportsTest` runs every report, checks row widths, who may see what, bad parameters, that members by status add up to the member master, that the general ledger's opening plus postings equals the ledger balance, that the loan reports agree with one another, and that inventory and POS reports read what was written (rolled back).

## Not built

* **Balance sheet, income/operations and cash flow as reports of this screen**: the balance sheet (financial condition) and operations statements already exist on the Accounting screen; **cash flow** is not built (it needs the owner's classification of accounts into operating, investing and financing).
* **CIC reports** (submission status, accepted, rejected, errors, corrections): CIC is the last phase.
* Scheduled or e-mailed reports, saved report parameters, charts, and PDF output (CSV and print only).
* The **Management Dashboard** (phase 21) is separate.
* Savings and share capital portfolio reports (the guide lists none; the savings interest and dividend runs have their own screens).
