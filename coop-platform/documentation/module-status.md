# What is done, module by module

A short list of what each module can do today and what is still missing. The full detail of each feature is in the linked note under `documentation/state/` (or the map named); when a feature changes, update its note and the line here.

As of 2026-11-19. Modules follow the ECIS.pdf boundaries.

**Status words**: **Built** = the module's core work can be done in the app. **Partly built** = some of it can be done, the rest is listed under Not built. **Not started** = nothing yet.

## Overview

| Module | Status | Main screens |
|---|---|---|
| [Foundation and platform](#foundation-and-platform) | Built | Sign-in, Settings > System |
| [Security and users](#security-and-users) | Built (Keycloak not yet) | Settings > Users |
| [Organization](#organization) | Partly built | Branch picker, Business Day |
| [Membership](#membership) | Built | Client Profile |
| [Share capital](#share-capital) | Built | Profile > Share Capital, Front Office > Teller |
| [Savings](#savings) | Built | Profile > Savings, Front Office > Teller, Passbook |
| [Time deposits](#time-deposits) | Built | Front Office > Teller, TD Maturities, TD Accruals |
| [Loans](#loans) | Built (penalty rule unconfirmed) | Loan Module, Loan Setup |
| [Cash and teller](#cash-and-teller) | Built | Front Office |
| [Accounting](#accounting) | Built | Accounting, Back Office, Chart of Accounts |
| [Dividends and patronage refund](#dividends-and-patronage-refund) | Built | Accounting > Dividends |
| [Treasury and banks](#treasury-and-banks) | Partly built | Accounting > Banks, Front Office > Handover |
| [CIC submission](#cic-submission) | Built, mapping unconfirmed | CIC submission |
| [Procurement](#procurement) | Partly built | Purchasing |
| [Inventory](#inventory) | Partly built | Inventory |
| [Point of sale](#point-of-sale) | Partly built | POS |
| [Fixed assets](#fixed-assets) | Partly built | Fixed Assets |
| [Documents](#documents) | Built (local files) | Profile > Documents, Settings > Documents |
| [Workflow](#workflow) | Built, partly wired | Settings > Approval workflows, Waiting for you |
| [Audit](#audit) | Built | Audit Log |
| [Data privacy](#data-privacy) | Built, periods to confirm | Settings > Compliance and Audit > Data privacy |
| [Reporting](#reporting) | Partly built | Reports |
| [Notifications](#notifications) | Partly built (inbox only) | Dashboard, Waiting for you |
| [Data warehouse and BI](#data-warehouse-and-bi) | Not started | |

---

## Foundation and platform

Built:
- Backend (Spring Boot, Java 21) and desktop app (Tauri 2, React, MUI) with installers (.exe and .msi, WebView2 embedded).
- Runs on PostgreSQL (the default since 2026-11-08) or the old MySQL 5.6 database, same code; portable SQL enforced by `PortableSqlTest`. See [postgresql-later.md](state/postgresql-later.md).
- Choosing the database a server connects to, and the server a desktop connects to. See [database-connection…](state/database-connection-choose-where-the-server-connects.md).
- Migrations (`scripts/migrate-postgres.ps1`, MySQL `scripts/migrate.ps1`), backup of the app's own data (Settings > System > Backup), versioning (`scripts/bump-version.ps1`).
- Navigation: side menu, tile dashboard, search (Ctrl+K), Esc goes back, in-app Documentation page.
- One ledger writer (`Ledger`) with request keys so nothing posts twice. See [design/posting-engine.md](design/posting-engine.md).

Not built: Docker Compose for the whole stack, Redis, Kafka, central logs and monitoring (only `/actuator/health`), code-signed installers. See [release-readiness.md](release-readiness.md).

## Security and users

Built:
- Sign-in for the old system's users with new bcrypt passwords; temporary passwords issued by an administrator; lockout after 10 wrong tries. See [sign-in.md](state/sign-in.md).
- Roles from the old access levels and flags plus the app's own roles (Membership Officer, Accountant, Purchaser, and so on), checked again on every request; a person's screen updates within 15 seconds when an administrator changes their access.
- Settings > Users: every user setting of the old system, per transaction type rights, loan step rights, "Loans alone" exemption.
- Limited database user (`coop_app`). See [database-user.md](state/database-user.md).
- Sign-in screen branding set by a SUPERADMIN.

Not built: Keycloak, single sign-on, dotted permission strings managed in Keycloak.

## Organization

Built:
- Branches read from the old system (`/api/branches`, `BranchSelect`).
- Business day per branch: Start Day, End Day with checks and end-of-day jobs, reopen by an administrator. See [business-day.md](state/business-day.md).
- One application for several cooperatives: Member defaults (default department, office, member types) and Features (switch savings, loans, the store and other areas off per cooperative). See [multi-cooperative.md](state/multi-cooperative.md).
- Departments (the old `dept`): add, change, switch off, delete unused, in Settings > Organization > Departments; registration checks every list a new member points to. See [departments.md](state/departments.md).
- Offices (the old `restrik`): administrators add, rename and delete unused offices in Settings > Organization > Offices; registration refuses clearly when the member's office is missing. See [offices.md](state/offices.md).

Not built: editing branches, the organization tree, choosing a member's office on the New Member screen (it is on the profile's Groups section); any screen filtering by the selected connection's branch.

## Membership

Built:
- Find a member, full profile (Member info, Accounts, Other), new-member screen.
- Profile edits by maker-checker (a Membership Officer's own edit applies directly); register, close and reactivate by maker-checker. See [membership-lifecycle-full-crud.md](state/membership-lifecycle-full-crud.md).
- SL Controls: administrators choose which savings, fund and share capital accounts open by themselves with a new or reactivated member, per member type. See [sl-controls.md](state/sl-controls.md).
- Drafts of new registrations; importing a list of members (administrators). See [advanced-mode…](state/advanced-mode-importing-a-list-of-members.md).
- Photo and signature capture; fingerprints with consent (simulated scanner only). See [fingerprints…](state/fingerprints-new-simulated-scanner.md).
- KYC record per member, KYC review list, optional block on withdrawals.
- New address fields (house number, subdivision).

Not built: KYC on the registration form, watchlist screening, large cash transaction flags, a real fingerprint scanner, member types other than Regular and Associate on registration.

## Share capital

Built:
- Accounts and ledger history with running balance. See [share-capital….md](state/share-capital-phase-7-read-only-so-far.md).
- Share payments in cash or cheque (Cash Receipt), share withdrawals (encoded, then approved and posted by others, then paid out).
- Holds for co-makers refused on withdrawal.

Not built: subscriptions, share certificates, share capital passbook, dormancy fee.

## Savings

Built:
- Accounts, ledger history, holds, available balance. See [savings….md](state/savings-phase-8-read-only-so-far.md).
- Deposits (cash or cheque) and withdrawals at the teller, with supervisor approval for minimum balance and limits.
- Quarterly interest run (preview, encode, approve, post by three people).
- Dormancy: review and mark dormant, reactivate.
- Passbook printing with confirmation, reprint, new passbook. See [passbook-printing.md](state/passbook-printing.md).
- Account type settings (rate, minimum and maximum balance, dormancy period). See [account-types.md](state/account-types.md).

Not built: placing and releasing ordinary holds by hand; passbook layout not yet checked on the real printer.

## Time deposits

Built (see [time-deposit-map.md](time-deposit-map.md) and the savings note):
- Placing a certificate, interest withdrawal (Lock In), pre-termination.
- Payout and renewal at maturity (Short Term and Lock In), never automatic; TD Maturities tab.
- Interest accrual run (monthly Lock In, year-end Short Term). See [time-deposit-accrual-map.md](time-deposit-accrual-map.md).

Not built: partial withdrawal of principal that keeps a certificate running.

## Loans

Built (see [loans….md](state/loans-phase-10-read-only-so-far.md) and [loan-posting-map.md](loan-posting-map.md)):
- Loans, ledger, arrears, Loan Profile, interest and penalty worksheet (PDF and Excel).
- Loan Module: application in steps (Profiling, Deduction, Verification, Approval, Release), each step its own right; step tracker, filters and sorting; amortization schedule print.
- Pay-off of earlier loans, renewal and restructuring; co-maker amounts and holds.
- Release voucher approve and post (three different people); release status followed in the list.
- Loan payments in cash or cheque with interest first and a suggested penalty (other figures need a supervisor); penalty waiver journals.
- Loan Setup: types, purposes, collateral, deductions, COA links, co-maker hold types, amortization reference and calculator. See [loan-setup-window-and-coa-links.md](state/loan-setup-window-and-coa-links.md).
- Diminishing-balance and annuity schedules.

Not built: the exact old penalty formula (modes 1, 5, 6; penalty columns of the worksheet unconfirmed), straight-line and AnnuityRate schedules, defer, releasing co-maker holds, payments by journal or remittance, loans with unearned interest, collection and provisioning, routing applications through the workflow engine.

## Cash and teller

Built (see [front-office-teller-window.md](state/front-office-teller-window.md) and [cash-and-teller-phase-9.md](state/cash-and-teller-phase-9.md)):
- Teller tab for every member transaction (savings, share capital, loans, time deposits); service invoice printing.
- Cash hand-offs between vault, head cashier and tellers with counts; drawer limits; blind end-of-day counts; supervisor overrides.
- Paying out vouchers payable in cash.
- Cheque receipts, clearing, cheque hand-offs, returned cheques (savings, loans and share capital). See [cheque-receipts….md](state/cheque-receipts-built-grant-pending.md).
- Opening each drawer with a blind count at the start of the business day.
- Posting a draft: other receipts and mixed payments at once; Cash Vouchers and Check Vouchers (the cooperative's own cheque) approved and posted by two other people. See [general-voucher-map.md](general-voucher-map.md).
- End-of-day jobs at End Day: balances, loan aging, maturities, interest runs due, cheques past clearing. See [business-day.md](state/business-day.md).
- Every posting needs an open business day and an open period.

Not built: printing the cooperative's cheques, Check Vouchers that pay loans or share capital, the end-of-day jobs running in the background (End Day waits a minute or two for them).

## Accounting

Built:
- Back Office tile for the entries, one window after the old General Journal Transactions window: general journal (draft, review, approve, post, reversal), debit/credit memos, penalty waiver journals.
- Back Office Disbursement Transactions window: Cash Vouchers and Check Vouchers made, approved and posted by three people.
- Accounting tile for the books: trial balance, statements, accounting periods, banks, year-end close. See [debit-credit-memo-map.md](debit-credit-memo-map.md).
- Statements by unit: for a main office, branch, satellite office or consolidation (everything under it), and a consolidating statement with each unit side by side, the consolidation's own entries and the total; department kinds in Settings > Organization > Departments. See [state/departments.md](state/departments.md).
- Comparative statements, Statement of Changes in Equity and Statement of Cash Flows; drill-down from the trial balance and statements to the entries; budgets and budget versus actual; Journal listing and Fixed assets against the ledger reports; a read-only Auditor role.
- Chart of accounts setup and upload from Excel. See [chart-of-accounts-setup.md](state/chart-of-accounts-setup.md).
- Financial statements and trial balance from the general ledger.
- Year-end close (prepare and post by two people). See [year-end-close.md](state/year-end-close.md).
- Opening balances for a new cooperative: ledger accounts and members' savings and share capital, typed or imported from CSV/Excel, posted once as a General Journal by three people, the members' totals going to their control accounts. Loans, time deposits and member funds not yet. See [opening-balances.md](state/opening-balances.md).
Not built: month-end closing and a closing checklist; two-step appropriation of the surplus to statutory funds.

## Dividends and patronage refund

Built: the yearly run stored member by member, submitted, approved by another person, then encoded into vouchers; settings (events counted, statuses, member types, minimum share, funds); the reports of the spec. See [dividend-spec.md](dividend-spec.md).

Built (2026-11-26): the appropriation of a closed year's net surplus to the statutory funds and to members (RA 9520 Article 86 limits), posted as a General Journal by three people; the distribution of that year may not pay more than appropriated. See [net-surplus-appropriation.md](state/net-surplus-appropriation.md).

Not built: the payable account for interest and refund chosen per cooperative (20126 for now); requiring an appropriation before a distribution.

## Treasury and banks

Built: bank reconciliation (prepared and finalized by different people); bank deposits and withdrawals of vault cash; deposits of vault cheques. See [cash-and-teller-phase-9.md](state/cash-and-teller-phase-9.md) and [bank-cash-posting-map.md](bank-cash-posting-map.md).

Not built: cash flow forecasting, investments, fund transfers between banks.

## CIC submission

Built: batches from draft to reconciled, validation, correction batches, the CSDF file in the shape the cooperative submits. See [cic-submission-phase-11.md](state/cic-submission-phase-11.md).

Not built or not confirmed: approval stays blocked until the mapping is confirmed; several columns are left empty; encryption, the Excel copy and the transmittal letter.

## Procurement

Built: suppliers, purchase requests and orders with approval by another person, goods receipts that feed stock. See [procurement….md](state/procurement-ecis-pdf-phase-12-stage-1-built.md).

Not built: supplier invoices, payables, payments and their journals (accounts to be decided).

## Inventory

Built: products, warehouses, batches and expiry, insert-only stock ledger, receive, issue, transfer, adjustments and counts approved by another person. See [inventory….md](state/inventory-ecis-pdf-phase-13-core-built.md).

Not built: bins, stock accounting in the general ledger.

## Point of sale

Built: price list, discounts, promotions, sales with cash, card or other payments, returns approved by a supervisor; stock taken out through the stock ledger. See [point-of-sale….md](state/point-of-sale-ecis-pdf-phase-14-core-built.md).

Not built: general ledger entries for sales and cost, VAT and BIR receipt rules, charging a sale to a member, cashier shifts and counts.

## Fixed assets

Built: categories, register, straight-line monthly depreciation as draft journals, transfers and disposals approved by another accountant, maintenance. See [fixed-assets….md](state/fixed-assets-ecis-pdf-phase-15-core-built.md).

Not built: acquisition postings, other depreciation methods, import of existing assets, register-versus-ledger report.

## Documents

Built: upload with type checks, access by classification and grants, versions, retention, checksum on every download. See [document-management….md](state/document-management-ecis-pdf-phase-16-core-built.md).

Not built: MinIO/S3 storage, encryption at rest, virus scanning, legal holds.

## Workflow

Built: extra approval steps by role and amount, wired to purchase requests, purchase orders and stock adjustments. See [workflow-engine….md](state/workflow-engine-ecis-pdf-phase-17-core-built.md).

Not wired: loan applications, journals, membership changes, cash adjustments, CIC.

## Audit

Built: every change through `AuditLog` with who, when (to the millisecond), where, before and after; tamper-evident chained seals; Audit Log screen with what changed per field.

Not built: nothing major; log redaction review before go-live (see [release-readiness.md](release-readiness.md)).

## Data privacy

Built: requests from people, member data package, fingerprint erasure, incidents, sharing, impact assessments, notices, retention and classification, export log. See [data-privacy….md](state/data-privacy-ecis-pdf-phase-19-built.md).

To confirm: deadlines, retention periods and wording against the Data Privacy Act.

## Reporting

Built: report framework with print and CSV; membership, loan (portfolio, PAR, delinquency, maturity, releases, payments, collection), general ledger, inventory and POS reports; aging of receivables (loans and accounts receivable, PAR or maturity basis, filters, up to three nested groupings with subtotals, accrued interest, Excel and PDF); financial statements; amortization schedule and loan worksheet PDFs. See [reporting….md](state/reporting-ecis-pdf-phase-20-core-built.md).

Not built: cash flow statement, CIC reports, PDF and scheduled report output, management dashboard.

## Notifications

Built: the inbox (what waits on the signed-in person, across modules) on the dashboard and the Waiting for you page; live alerts in the front office window.

Not built: email or SMS, notification preferences, a message queue.

## Data warehouse and BI

Not started.
