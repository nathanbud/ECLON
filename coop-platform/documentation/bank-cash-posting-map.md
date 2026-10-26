# How the legacy system moves cash between the vault and a bank

Mapped from real posted transactions in `iaccsbsucmpc01` (read only). In 2026 to 24 April: 790 Bank Deposits (module 5, type 33) and 12 Bank Withdrawals (type 35).

## Bank Deposit (33) and Bank Withdrawal (35)

Examples: deposit control no. **6891** (2026-04-22, "CIB-CBC ACCOUNT ULTRA SAVINGS", 10,000,000.00) and withdrawal **554** (2026-04-24, "BW BDO BUGUIAS LOAN RELEASES ...", 2,500,000.00).
Header in `transsum` (module 5, no client, `TR_DOCNO` `*` plus 7 digits, `BATCH_NO` the date, `IS_FO` 1 or 0). Two `transdt` lines, both encoded and posted by the same
person (the head cashier, no approver), `DTUPD_TAG` 1, `DTCOCI_TYPE` 1:

| seq | Account | Class / type / ref | Event | `DTCTS_NUM` | Deposit | Withdrawal |
|---|---|---|---|---|---|---|
| 0 | `10001` cash in vault | 11 / 1 / 1 | 50 | 99 | credit | debit |
| 1 | the bank's ledger account (`10020209` ...) | 11 / 2 / 1 | 62 (savings) or 61 (current) | the bank's id | debit | credit |

* The bank accounts are the `glcontrol` rows of class 11, type 2, event 61 or 62; the number is the bank's id (`bankcode.BCID`, name in `BCDesc`). `bankcode.BCCOAID` is empty for these.
  Examples: BPI-LTB 36 -> `10020209`, LBP-LTB 37 -> `10020216`, PNB-BC 52 -> `10020224`, BDO-Pangasinan 31 -> `10020104` (event 61).
* `gltrans`: one line per account, type 33 or 35. A cash count by note and coin is stored in `denomsum` / `denomdtl` (deposit: `TO_USERID` the head cashier; withdrawal: `FROM_USERID`).
* Explanations read "CIB-<bank> <note>" for deposits and "BW <bank> <note>" for withdrawals.
* About 500 of the 800 deposits (497 in 2026) take cheques instead: the same two lines, but seq 0 is on `100111` (checks and collections on hand) with **sub-ledger type 3**, event 51, number 99, and there is no `denomsum` and no `transck` row. The app builds these (`cheques: true` on the deposit request): the vault's cheque balance must cover the amount, and cash and cheques are never mixed on one deposit (all 497 have exactly two lines).

## What the app does

`BankCashController` (`GET /api/front-office/bank-cash/banks`, `POST .../deposits`, `POST .../withdrawals`): head cashier only, needs "new" rights on type 33 / 35, posts at once.
Deposit: counts must add up to the amount and the vault must hold it. Withdrawal: the ledger balance of the bank account must cover it. Control number from `Db.nextControlNumber`.
Test: `BankCashPostingTest` (posts, compares, rolls back).

## Not built

Bank charges and interest, check clearing, a screen for it (the endpoints exist).
