# Business day (Start Day and End Day), part 1

Keep this note current when the feature changes.

## What it is

Core banking practice: each branch has **one open business date**, and teller and front-office postings carry that date instead of the computer's.
The date moves only by End Day and Start Day, never by itself at midnight, so every transaction belongs to the day whose cash was counted.
Nothing can be posted while no day is open.

## The old system

- `semaphor` (one row per branch): `SemTransactionDate` is its business date (24 April 2026 in this copy), `SemStartDay` = 1 while the day is started.
  Point of sale has its own date and flag (`SemPOSTransactionDate`, `SemPOSStartDay`) and a Z-counter.
- The rights are switches on `access`: `AccessStartDay`, `AccessEndDay` (five users had both).
- Transaction type 98 rows in `gltrans` (one-sided, on 30199 net surplus) are written with each posting, not at day end; statements leave them out.
- This application reads `semaphor` only to show the old date; it never writes it.
- The old system's working week includes **Saturday** (about as busy as a weekday in 2025); Sundays are rare. Holidays are in `holidays`.

## Tables (PostgreSQL `V027` applied; MySQL `V064` written, not applied)

- `ecis_business_day` (branch, date, OPEN or CLOSED, who and when opened and closed, the reason and failed checks when End Day overrode).
  PostgreSQL keeps one OPEN day per branch with a partial unique index; MySQL relies on the row lock taken before starting a day.
- `ecis_business_day_log`: every START, END, END_OVERRIDE and REOPEN; insert only (`deny_update` trigger).
- `coop_app` may now update `access.AccessStartDay` and `AccessEndDay` (Settings > Users > Transactions > Business day).

## Rules (`BusinessDayRules`, pure, `BusinessDayRulesTest`)

- Start Day: no day open; the date is not later than today and comes after the last closed day. Warnings to confirm: a Sunday or holiday,
  a past date, working days skipped since the last closed day. Suggested date: today if a working day, else the latest working day after the last closed one.
- End Day checks (`DayEndChecks`, read only). Must pass: every teller drawer with cash lines that day has a count in `ecis_drawer_close` for the day;
  no `ecis_cash_handover` PENDING or DISPUTED; every posted voucher of the day sums to zero (`transdt`). For review: vouchers dated that day still waiting
  (tag 2), supervisor approvals pending, front-office drafts, drawers counted with a difference, how many transactions.
  With a must-pass check failing, End Day needs a reason (at least 10 letters); the reason and the failed checks are kept with the day.
- Reopen: administrators only, the last closed day, while no day is open, with a reason.
- Who: the old rights; administrators always (the way in when nobody with the right is present).

## Where the date is used (`BusinessDay`)

- `postingDate(branch)`: the open date, or 409 "The business day has not been started". Used by the ledger's cash writer (`Ledger.writeCash`: deposits,
  withdrawals, share and loan payments, time deposit placement, voucher pay-outs), bank cash, cash hand-offs and drawer counts, returned cheques,
  penalty waivers, share withdrawals, time deposit closures, loan approval (its release voucher) and loan release.
- `today(branch)`: the open date, else the calendar date, for quotes shown before posting (penalties, time deposit closures and maturities, loan payment
  breakdown, the front office's date) and for the dates a loan application is cancelled or disapproved.
- `requireStarted(branch)`: savings interest, time deposit accrual and dividend runs keep their period-end dates but need an open day.
- Not changed: journals and memos (the accountant's date, under period control), the year-end close, the loan application itself.
- Off where `coop.business-day.required` is false (only the backend tests, `src/test/resources/config/application.yml`) or the tables are missing:
  then everything uses the calendar date as before. `BusinessDayPostingTest` switches it on.

## Screens

- Menu > Teller and cash > **Business Day** (`BusinessDayPage`, view `businessday`): today's state, Start Day, the End Day checks and End Day,
  recent days with who opened and ended them and any override, reopen for administrators.
- The title bar shows the business date (`BusinessDayChip`): green while the open day is today, orange with the date when it is not, "Posting closed" when none is open.
- Front Office shows a banner while no day is open, or the open day is not today.

## Part 2: drawer opening and end-of-day jobs (PostgreSQL `V030` applied; MySQL `V067` written, not applied)

- **Opening a drawer** (`DrawerOpening`, `DrawerOpenController`, `/api/front-office/drawer-open`; screen `DrawerOpen`, Open my drawer on Front Office > Teller and Cash Handover):
  each teller and cashier counts their own drawer once per business day, blind (the ledger's figure is shown after the count), by note and coin.
  A difference needs a reason and is kept in `ecis_drawer_open` (insert only, `deny_update`); the drawer opens either way and the count cannot be changed.
  Cash at the window (`Ledger.writeCash`) is refused with 409 from a drawer not opened on the business day.
  Hand-offs between drawers do not need it (receiving cash is itself counted). The head cashier and administrators see every drawer opened that day (`/day`).
  The rule is the setting `teller.require_drawer_open` (`ecis_setting`) (on by default; `PUT /policy`, administrators, with a reason, audited; switch on the Business Day screen).
- **End-of-day jobs** (`DayEndJobs`, `GET /api/business-day/jobs`, `POST /api/business-day/jobs/run`; panel on the Business Day screen with Run the jobs now): five steps, each kept in
  `ecis_eod_run` / `ecis_eod_step` so a failed one is tried again without redoing the others; one run per branch at a time; nothing is posted.
  1. **balances**: each drawer's cash and the balance of every savings, time deposit, share capital, vouchers-payable and loan type as of the day (`ecis_eod_balance`).
     The first snapshot sums the whole ledger once (about 50 s on the full data); later ones add the days since the last (under a second).
  2. **loan_aging**: every open loan (statuses 11, 12, 14) aged on the day by `LoanAging`, with the rules the payment window quotes by: owed, principal past due and since when,
     interest due, the penalty by the formula where the penalty mode is 4 (empty elsewhere), and the bucket (`ecis_loan_aging`). About 30 s on the full data.
  3. **maturities**: time deposits matured and still holding money, counted (they are paid out or renewed on TD Maturities).
  4. **accruals**: interest runs whose period ended in the last 31 days and are not encoded yet (time deposit Lock In each month end, Short Term on 31 December,
     savings each quarter end) are encoded to wait for approval, as when run by hand; one that cannot be made yet is noted and tried at the next End Day. Done once per day.
  5. **cheques**: cheques on receipts past their expected clearing day and not marked cleared, counted.
  The balance, aging, maturity and cheque steps read the day, so End Day runs them again even when already done.
- **End Day order**: the checks first (refused at once when one must pass and no reason is given), then the jobs, then the checks again, then the day is closed.
  Tested by `BusinessDayPostingTest` (including that a refused End Day runs no job) and `TellerCompletion.test.tsx`.

## Not done yet

- Point of sale's own day (`SemPOSStartDay`), back-dating as a right with limits.
- The jobs run inside the End Day request (a minute or two the first time); they are not moved to the background.
- The signed-in browser tests (`npm run e2e`) that post need a day started first.
