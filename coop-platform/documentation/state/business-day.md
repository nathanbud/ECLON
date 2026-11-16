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

## Not done yet (part 2)

- End of day jobs: accruals, loan ageing and penalties, maturities, balance snapshots, run from End Day and restartable.
- Point of sale's own day (`SemPOSStartDay`), per-teller drawer opening at Start Day, back-dating as a right with limits.
- The signed-in browser tests (`npm run e2e`) that post need a day started first.
