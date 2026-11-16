# Paying cash out of vouchers payable (module 2, type 24)

Loan releases (`loan-posting-map.md`), share withdrawals (`share-withdrawal-posting-map.md`) and time deposit payouts and renewals
(`time-deposit-map.md`) all end by crediting the member's **vouchers-payable account** (class 29, type 1, GL 2012305). The member gets the money
later, when a cashier pays that account out in cash. The old system's transaction for it is the **Cash Voucher payment, module 2, type 24**
(about 4,700 of them in 2026, 14,400 in 2025).

## What the old system wrote (checked on vouchers 39000, 40042, 40043 of 2026)

One cashier encodes and posts it in one step (`DTENCDBY` = `DTPOSTBY`, no approver, `DTUPD_TAG` 1 at once, `IS_FO` 1 for the newer ones).

| seq | account | ledger | amount |
| --- | --- | --- | --- |
| 0 | `2012305` vouchers payable | 29 / 1 / ref, event 11, `DTCTS_NUM` 15 (the account status), `DTCOCI_TYPE` 1, `ClientIDDT` = the member | **+ balance** (debit) |
| 1 | the cashier's cash account (`10002` cashier event 52, `10003` / `10004` teller event 53) | 11 / 1 / 1, `DTCTS_NUM` = the drawer number | **- balance** (credit) |

* `transsum.TR_CLIENTID` is the account owner, `EXPLANATION` free text (per diem, honorarium and so on).
* One `sldtl` row: class 29 type 1, event 11, `TR_CODE` 24, `AMT` + balance, `COCI_TYPE` 1, `CLRG_DAYS` 0.
* Two `gltrans` lines (debit 2012305, credit the cash account), dated the voucher day.
* **No `denomsum`, no `transck`**: the cash count is not recorded (same as a savings withdrawal).
* Every payment checked in April 2026 (25 of 25) **paid the whole balance** of the account: the amount is never typed, it is the balance.
* A few vouchers of type 24 (about 5 in 3,000) carry three to five lines, tag 5, and pay several payable accounts of different types (2021401, 2021403) and a loan fee at once. That is a different, mixed disbursement and is **not built**.
* In the data every release has been paid out: only two accounts in 2026 still hold a few pesos.

## What the app does (`VoucherPayoutController`, `/api/front-office/voucher-payouts`)

* `GET /waiting`: accounts credited by a posted voucher (types 20, 21, 22) in the last 45 days that still hold money. `GET /member/{clientId}`: one member's accounts that hold money.
* `POST /check` and `POST /`: pays the **whole balance**, by `MemberLedgerPoster.postVoucher` with `Direction.PAYABLE_OUT` (module 2, type 24): the payable debit first, then the cash line on the cashier's own drawer, the `sldtl` row and the two `gltrans` lines, posted at once (tag 1). Audit action `voucher.payout.post`.
* Who: a cashier or teller with a drawer, a department and `access_trans` CAN_NEW for type 24 (the Disburse tab already carried type 24).
* Refused: account not active (status 15 in `osl`), closed or unapproved client, nothing in the account, and **not enough cash in the cashier's own drawer** (new rule of the app: the drawer must hold the amount; the old system is not known to check; the head cashier hands cash over first on the Cash Handover tab).
* **Over the cashier's limit** (the smaller of `AccessDisbursementLimit` and `AccessMaxWithdrawal`): a supervisor must allow it first, asked through `ecis_fo_override` with kind `VOUCHER_DISB_LIMIT`, the same flow as a savings withdrawal; the approval is spent once (`used_ref`, audit action `voucher.payout.override.used`).
* The account row in `osl` is locked while paying, so two cashiers cannot pay the same balance twice.
* Screen: Front Office > Disburse > **Pay out vouchers payable** (`VoucherPayoutPanel`): the people waiting, a client-number lookup, and a dialog showing the balance, the drawer cash, the problems and the supervisor request.
* Tested by `VoucherPayoutTest` (rules; a seeded credit paid out and compared with the old rows, repeat refused; the limit approval spent once; the waiting list). The tests credit an account themselves because the real vouchers are all paid; everything rolls back.

## Not built

* The mixed type 24 vouchers (several payable types and fees at once).
* Payment by cheque, or a partial payment (the old system never did a partial one).
* A cash count (`denomsum`) for the payment.
* Paying vouchers-payable types other than class 29 type 1.
