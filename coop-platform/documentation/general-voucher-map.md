# General vouchers posted from a front office draft

What a front office draft becomes when it is posted, following the old system's own vouchers. Keep this note current when the feature changes.

Code: `DraftPostRules` (pure: what each kind may hold), `DraftPostController` (`/api/front-office/drafts/{id}/check` and `/post`), `GeneralVoucherController` (`/api/front-office/vouchers`: waiting, detail, approve, post), `WaitingVoucher` and `Ledger.encodeWaiting` / `approveWaiting` / `postWaiting`. Screens: `DraftPostDialog` (Post… on a saved draft), `WaitingVouchersPanel` (Front Office > Disburse > Vouchers waiting). Tables: `ecis_voucher_cheque` (PostgreSQL `V030` applied; MySQL `V067` written, not applied). Tested by `TellerCompletionTest` and `TellerCompletion.test.tsx`.

## Made from Back Office

Back Office > Disbursement Transactions makes Cash Vouchers and Check Vouchers without typing the credit line: the window adds it (the payee's vouchers payable from `GET /api/backoffice/voucher-setup`, or the chosen bank's ledger account), saves a draft, checks it (`/check`) and encodes it (`/post`); a draft that cannot be encoded is deleted, so nothing is left in the front office drafts. Approving and posting are the same as on Disburse > Vouchers waiting.

## The old data (read only)

| Kind | Old type | Vouchers 2023 to 2026 |
|---|---|---|
| Cash Voucher | module 2, type 21 | 39,884 (includes loan releases, share withdrawals and time deposit payouts, which have their own screens) |
| Check Voucher | module 2, type 22 | 1,153 |
| Misc Disbursement (payment) | module 2, type 24 | 39,374 (includes vouchers-payable payouts) |
| Cash Receipt with no client | module 3, type 10 | 6,868 |

## The four kinds

| Draft type | Becomes | Lines allowed | Window side | When it posts |
|---|---|---|---|---|
| 10 Cash Receipt | an **other receipt** | credits to plain ledger accounts (a fee, rent, a collection for someone else); never a member's account (those are on the Teller tab) | cash (and cheques) debited to the poster's drawer | at once, through `Ledger.writeCash`; the payor is named when there is no client |
| 24 Misc Disbursement | a **mixed payment** | debits to one member's vouchers-payable accounts (class 29, any type) and to plain ledger accounts | the poster's drawer credited | at once; over the cashier's limit a supervisor allows it first (as for a voucher payout) |
| 21 Cash Voucher | a **general voucher** | debits to plain ledger accounts (an expense, an asset) | the credit is a **new** vouchers-payable account (29/1) opened for the payee, as a loan release does | waits (tag 2): approved by another person, posted by a third; the payee is then paid on Disburse > Pay out vouchers payable |
| 22 Check Voucher | **paid by the cooperative's cheque** | debits to plain ledger accounts, a member's vouchers payable or savings | the credit is the ledger account of the bank account the cheque is drawn on (`bankcode.BCCOAID`) | waits: approved by another person, posted by a third, when the cheque's tender row is written |

The other types stay drafts and say why: 20 is made by the Loan Module, 23 withdrawals on the Teller tab, 31/32 on Cash Handover, 33/35 bank deposits on Cash Handover, 41/42 at the point of sale, and the rest have no posted example in the old data to follow.

## Checks (again inside the posting transaction)

- Debits equal credits; every amount above zero; the cash lines on the right side for a receipt or a payment.
- A member's account must belong to the member, be active and hold what is paid out of it (savings: the available amount after holds and uncleared cheques). Savings paid by a Check Voucher are checked again when it is posted.
- Closed, disapproved or not yet approved clients are refused.
- A Cash Voucher or Check Voucher is never approved by its encoder, and never posted by the encoder or the approver (the old system let the encoder post).
- The draft is deleted once it has become a voucher; the request key (`Idempotency-Key`) keeps a second press from encoding twice.

## The cooperative's own cheque (Check Voucher)

- Kept in `ecis_voucher_cheque` while the voucher waits: the bank (BCID), number (digits, up to 15, written zero padded as the old Check Vouchers are), date, payee and amount.
- A unique index on branch, bank and number: the same cheque of a bank account is never written on two vouchers.
- On posting, the tender row (`transck`) is written from it as the old Check Vouchers have it: sequence 0, the cheque, kind 5 "On Us", no clearing days, cleared, the payee.

## Not done

- Check Vouchers that pay a loan or share capital (use the member's own screens).
- Printing the cooperative's cheque: the app records it, it does not print it.
