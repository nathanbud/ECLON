# Opening balances

A new cooperative's balances on the day it starts on this system. The application is for new cooperatives (no data is converted from an older system), so
everything a cooperative already has (cash, banks, fixed assets, payables, equity, and what each member has in savings and share capital) goes in once, here.

## Where

Accounting > Opening balances (menu and search: Opening balances; Settings > Accounting > Opening balances). Accountants and administrators prepare, approve and
post; auditors read. `OpeningBalanceController` (`/api/opening-balances`), rules `OpeningBalanceRules` (pure), screen `OpeningBalancesTab.tsx`, API
`api/openingBalances.ts`. Tables `ecis_opening_batch` and `ecis_opening_line` (PostgreSQL `V044`, applied to `coop` and `coop_bgempc`; MySQL `V081`, not applied).

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

CSV or Excel (.xlsx), first row the column names: Kind (GL, Savings or Share capital), Member (member number), Type (account type number), Account no (when the
member has several of that type), Ledger account, Debit, Credit, Note. Names are matched without regard to case, spaces or punctuation. Importing reads the file
(nothing is saved) and replaces the lines in the editor; rows that cannot be read are listed with their file line. Download template gives a CSV with three
example rows. A batch of more than 200 lines is not edited line by line on screen: correct the file and import it again.

## Not yet

- Loans outstanding, time deposits and member funds or payables (class 29): a loan needs its terms and schedule, so it is opened as a loan, not as a line here.
  Until then their control accounts cannot take an opening balance in a batch (refused as control accounts).
- Comparing the batch with a trial balance typed from the old books, column by column.

Tested by `OpeningBalanceRulesTest`, `OpeningBalanceFlowTest` (posts on `coop` and rolls back), `OpeningBalancesTab.test.tsx`.
