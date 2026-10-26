# Cheque receipts: what the old system writes

Mapped on 2026-10-25 from real posted Cash Receipts (module 3, type 10) in `iaccsbsucmpc01`: savings deposits by cheque (2026 receipt 352690, two local cheques), a mixed cash and cheque savings deposit (352103), and a loan payment by cash and cheque (351194). Code: `MemberLedgerPoster.postVoucher` (cheques parameter), `ChequeRules`, `ChequeSetupController`; grant in `V024_cheque_receipts.sql`; tests `ChequeRulesTest`, `ChequeReceiptPostingTest`.

## Cash accounts versus cheque accounts

Cheques are kept in their own "Coci" accounts, mirroring the cash accounts, with sub-ledger type 3 instead of 1 (same class 11, reference 1):

| Cash | Cheques | Name | Event |
|---|---|---|---|
| 10001 | 100111 | Coci on hand (vault) | 50 cash, 51 cheques |
| 10002 | 100112 | Coci - cashiers | 52 |
| 10003 | 100113 | Coci - tellers | 53 |
| 10004 | 100114 | Coci - collectors | 53 |

`DTCTS_NUM` is the drawer number of the person, as for cash. A head cashier posts to 100112, a teller to 100113.

## What a receipt with cheques writes

* **`transsum`**: as for a cash receipt (`CASHPAYMENT` stays empty).
* **`transdt`**, in this order: one debit line per cheque (account 100112/100113, class 11, type 3, reference 1, the poster's event and drawer number, `DTCOCI_TYPE` 2); then the cash line if any cash is taken (10002/10003, type 1, `DTCOCI_TYPE` 1); then the member's credit line(s), **all marked `DTCOCI_TYPE` 2 as soon as one cheque is in the receipt**. Debits equal credits. A cheque-only receipt has no cash line.
* **`sldtl`**: the member's ledger row is split by tender, one row for each piece: the **cash pays the member lines first, in line order, and the cheques pay the rest, in order**. A cash row has `COCI_TYPE` 1, `CLRG_DAYS` 0. A cheque row has `COCI_TYPE` 2, `CHECKNO` (15 digits), `BANK_CODE` and `CLRG_DAYS`. Loan payment 351194: cash took the first 6,000 of principal; the cheque paid the last 9.86 of principal, then the interest and the penalty, each as its own row. The cash rows come before the cheque rows.
* **`gltrans`**: a debit for each cheque on the cheque account, a debit for the cash on the cash account, and a credit on the member's account for each `sldtl` row (two cheques into one savings account gave two credits, not one).
* **`transck`** (new for this app): one row for each tender, numbered from 0. Cash comes first (seq 0, `CKCOCI_TYPE` 1, `CKAMT` the cash, no cheque type, number `''`, no bank, no date), then each cheque (`CKCOCI_TYPE` 2, `CKCHECKTYPE` the kind, `CKCLRG_DAYS` the kind's days, `CKCHECKNO`, `CKBANK_CODE`, `CKCHECKDATE`, `CKAMT`). Both rows: `CKUPD_TAG` 1, `CKDEPT` the poster's department, `CKENCDBY` and `CKPOSTBY` the poster, `CKAPPRVBY` empty, `CKISCLEARED` 0, `CKPAYEE` `''`.

**Every cash receipt has a cash row in `transck`** (all 41,068 in 2026). Before 2026-10-25 this app wrote none for its own receipts; it now writes the cash row too when the database user may (V024). Withdrawals (type 23) have no `transck` row.

## Lookups

* **`chktype`** is the kind of cheque, with its clearing days: 1 Local 3, 2 Regional 7, 3 Out of Town 15, 4 Foreign 45, 5 On Us 0, 6 Good Check 0. (`check_type` is something else: Personal / Commercial.)
* **`bankcode`** is both the cooperative's own bank accounts (those with a `BCCOAID`) and the banks cheques are drawn on (names like `SecBank-LTB`). `CKBANK_CODE` is a `BCID`.
* **`sldtl.CLRG_DAYS`** is the kind's days plus 2 when it is above 0 (3 becomes 5, 7 becomes 9), and counts down each day until the cheque clears (checked on receipts of 2026-04-15 to 04-24 against the data's last day). 0 for immediate kinds and cash.

## Rules the data shows

* Cheque numbers are 15 digits, zero padded (4,042 of 4,043 since 2025 are numeric).
* The cheque date is between 179 days before and 9 days after the receipt. The app refuses cheques older than 180 days and more than 30 days ahead (the second limit is the program's own).
* Cheques cannot pay out: only receipts take them. Only a cashier or teller (events 52 and 53) takes cheques; the vault's cheque account has its own event (51).
* The same bank and cheque number received twice needs a confirmation in the app (the old system's rule is not known).

## Not built, and open

1. **Clearing (built, needs V026).** What the old data shows (checked 2026-10-26): the state is `transck.CKISCLEARED` (0/1); `CKCLR_DATE` is never filled. Of 826 cheques taken on receipts in 2026, all 736 received before April are cleared and 46 of 168 from April are not. Cheques clear later than the kind's days: kind 1 (3 days) are mostly still open at 4 days old and cleared at 6; kind 2 (7 days) open at 9 days, cleared at 10. Only 19 clearings in three years are logged (`actvtlog`, activity 20 "COCI Clearing", all by the head cashier, 2 to 15 days after the receipt, receipts type 10), so nearly all clearing leaves no trace and the exact rule is not known. `sldtl.CLRG_DAYS` counts down but not exactly by calendar days, and the app leaves it alone. The app lists cheques of Cash Receipts that are not cleared with the expected day (receipt date + kind days + 2), and the head cashier, an accountant or an administrator marks them cleared (`ChequeClearingController`, `/api/front-office/cheque-clearing`, Cheque clearing panel on the Handover tab); a reason is needed to clear before the expected day, each cheque gets an audit row (`cheque.clear`), and the only legacy column written is `CKISCLEARED` (`V026_cheque_clearing.sql`, column UPDATE). Cheques paid out (types 21, 22, 35) are not listed.
2. **Whether uncleared cheques hold back withdrawals.** The savings availability here ignores `CLRG_DAYS`; the old rule is not known.
3. **Handing cheques on (built, needs V025).** Mapped 2026-10-26 from real type 32 transfers (33600, 33608, 33613, 33578). The old window writes one transfer for cash and cheques together: the cash pair first (10002/10003/10004 with sub-ledger type 1), then, when cheques are given, a second pair on the cheque accounts (100111/100112/100113, sub-ledger type 3, events 51 vault, 52 cashier, 53 teller, `DTCOCI_TYPE` 1), credit on the giver and debit on the receiver, with the same people and the same `denomsum`. A cheque-only transfer has just the cheque pair, from sequence 0. Cheques are an amount only (no `transck` rows). There are no real examples of cheques leaving the vault except by a type 33 bank deposit, so the app refuses that. The app adds `cheque_amount` to `ecis_cash_handover` (`V025_cheque_handover.sql`, run by an administrator); the giver enters the cheque total, the receiver confirms it with the count and a different total is a dispute like a wrong cash count. Cheque balances per drawer are the sum of the sub-ledger type 3 lines (`DrawerBalances.allCheques`); a few real tellers have a small negative figure, so the check applies only when cheques are being given. The bank deposit of cheques from the vault is built too (see `bank-cash-posting-map.md`).
4. **Returned (bounced) cheques** and cheques for payments out (class 2 types 21 and 22 also use `transck`).
5. **Loan payments by cheque** are built on the same writer and tested only through the savings deposit; the ordering across penalty, interest and principal follows receipt 351194.
