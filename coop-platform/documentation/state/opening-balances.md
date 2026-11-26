# Opening balances

A new cooperative's balances on the day it starts on this system. The application is for new cooperatives (no data is converted from an older system), so
everything a cooperative already has (cash, banks, fixed assets, payables, equity, what each member has in savings and share capital, the loans members are still
paying and the time deposits still running) goes in once, here.

## Where

Accounting > Opening balances (menu and search: Opening balances; Settings > Accounting > Opening balances). Accountants and administrators prepare, approve and
post; auditors read. `OpeningBalanceController` (`/api/opening-balances`), rules `OpeningBalanceRules` (pure), screen `OpeningBalancesTab.tsx`, API
`api/openingBalances.ts`; loans and time deposits are checked and opened by `OpeningAccounts`. Tables `ecis_opening_batch` and `ecis_opening_line` (PostgreSQL `V044`, and
`V046` for the loan and time deposit terms, applied to `coop` and `coop_bgempc`; MySQL `V081` and `V083`, not applied).

## A batch

- **Lines**: a ledger account (GL) with a debit or a credit, or a member's savings or share capital account with a credit (the money the member has in it). A
  member line names the member and the account type, and the account number when the member has more than one of that type; otherwise the member's only account
  of the type is used.
- **Control accounts are never typed.** A member line is posted to the control account of its account type (`glcontrol`, event 11, the account's status), so the
  control accounts come out as the sum of the members and the SL reconciliation agrees from the first day. A GL line on a control account is refused, with the
  direction to enter the members instead.
- **An opening balance is an account's first entry**: a member account that already has ledger rows is refused (adjust it with a debit/credit memo). The same
  account in two unposted batches is refused too. A closed account takes none.
- The batch balances (debits = credits), every account appears once, the date is not in the future, a description of 5 to 200 characters, at most 5,000 lines
  (split the members over several batches, each balanced with ledger lines).
- **Trial balance it makes**: the detail view shows, per account, the debit and credit the batch will post and how many member accounts make up each control
  account, so the accountant compares it with the statements the balances come from.

## Loans and time deposits

A loan or time deposit line **opens its account when the batch is posted** (there is no account to choose yet), so it names the member and the type and carries
its terms. Posting opens it with the next number of its type and writes that number back into the line.

- **Loan** (class 12): a **debit** of the principal the member still owes, the loan's status (current, past due, in litigation or restructured; its control
  account follows the status), the **months still to run**, the **next due date** (after the opening date) and the yearly rate (empty = the loan type's rate);
  for the record, the amount and date it was granted (kept in the loan's remarks with the batch's reference). The loan is opened for what is owed, from one
  payment period before the next due date: its `amortsched` is the remaining schedule worked out by `LoanSchedule` with the loan type's method (diminishing
  balance or annuity), payment mode and day count, so the first instalment falls on the next due date, the payments made before the opening are not repeated and
  nothing shows as late. The `loan` row takes the type's settings as a loan application does. Refused: a type whose method or payment mode the schedules do not
  cover, a term that is not whole payment periods, a due day the schedule cannot keep after a short month (the message gives the date it would use), a member owing
  more than was granted. Collateral, co-makers and arrears are not taken; a payment already due is counted in the balance.
- **Time deposit** (class 24): a **credit** of the certificate's principal, its **placement date** (on or before the opening date), **term in days**, **yearly rate**
  and the **interest it earned up to the opening date**. The certificate keeps its own placement date, rate and maturity (placement + term), so the member is paid
  as agreed; the accrued interest is a second credit on the certificate (event 71, status 99, on the account the accrual run uses), and because the certificate's
  ledger rows are dated the opening date, the accrual runs count only the days from then on. The certificate number is its account number (the database's `td`
  trigger sets it so, as the old system did); a paper certificate's old number goes in the note (a file's Certificate no column is put there).
- Both are checked when the batch is saved and again at posting: the member is in the branch and not closed, the type exists and is switched on, and it has a
  control account (and an accrued interest account) for the status. The trial balance shows the loans and certificates added into their control accounts.

## Steps

DRAFT, FOR_REVIEW, APPROVED, POSTED (or CANCELLED). A draft may be saved with problems (they are listed live); it can be sent for review, approved and posted only
with none. Maker, approver and poster are three different people. A reviewer may send a batch waiting for review or posting back to its maker as a draft, with a
reason. The maker or an administrator may cancel an unposted batch with a reason; a posted batch is never changed or cancelled (correct a member's balance with
a memo, a ledger balance with a journal). Audited: `opening.balance.create|update|submit|approve|return|post|cancel`, and the ledger's own `ledger.write`.

## Posting

One General Journal (module 1, type 1) through the `Ledger`, request key `opening-balance-<id>`, dated the opening date: the header names the first member, a
ledger line is written as a journal line, and a member line as the control account's line carrying the member's account (client, class, type, reference number,
event 11, status, adjustment flag 1, as a Debit/Credit Memo writes it) with the member's `sldtl` row (and `slsum` the first time). Posting needs an open business
day and an open accounting period covering the opening date, and the three people must be enabled users. `Voucher.Line` now carries an optional
`Voucher.Member` for this; journals without member lines are written exactly as before (golden snapshots unchanged).

## Files

CSV or Excel (.xlsx), first row the column names: Kind (GL, Savings, Share capital, Loan or Time deposit), Member (member number), Type (account type number),
Account no (when the member has several of that type; never for a loan or time deposit), Ledger account, Debit, Credit, Note; for a loan also Status, Rate, Term
(months still to run), Next due, Amount granted, Granted on; for a time deposit Rate, Term (days), Placed on, Accrued interest, Certificate no (kept in the note). Names are matched without regard to case, spaces or punctuation. Importing reads the file
(nothing is saved) and replaces the lines in the editor; rows that cannot be read are listed with their file line. Download template gives a CSV with three
example rows. A batch of more than 200 lines is not edited line by line on screen: correct the file and import it again.

## Not yet

- Member funds and payables (class 29) and other receivables: their control accounts cannot take an opening balance in a batch yet (refused as control accounts).
- A loan's collateral, co-makers and arrears (overdue instalments shown as late from the first day).
- Comparing the batch with a trial balance typed from the old books, column by column.

Tested by `OpeningBalanceRulesTest`, `OpeningLoansAndDepositsRulesTest`, `OpeningBalanceFlowTest` (posts on `coop` and rolls back, including a loan and a certificate),
`OpeningBalancesTab.test.tsx`.
