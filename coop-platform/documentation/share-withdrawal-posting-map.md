# How the legacy system withdraws share capital

Mapped from real posted vouchers in `iaccsbsucmpc01` (read only). Since 2025-01 there are 525 debits on share capital (class 31, event 11) from
Cash Vouchers (type 21), alongside 17,000 credits from the same type (deductions on loan releases).

## The voucher: Cash Voucher (module 2, type 21, back office)

Example: control no. **39787**, document no. `*0039787`, 2026-04-22, `IS_FO = 0`, explanation "WITHDRAWAL OF ASSOC SAVINGS TO REGULAR M...". Client 2258.
It is a transfer between the member's own accounts, so no cash line:

| `transdt` seq | Account | Class / type / ref | Amount |
|---|---|---|---|
| 0 | `2010101` savings deposits | 22 / 2 / 1642 | debit +8,604.71 |
| 1 | `3010103` share capital | 31 / 3 / 80 | debit +100.00 |
| 2 | `2012305` vouchers payable | 29 / 1 / 19389 | credit -8,704.71 |

All lines: event 11, `DTCTS_NUM` 15 (the account status), `DTSL_DATE` the date, `DTADJ_FLAG` 1, `DTENCDBY` and `DTPOSTBY` the encoder (42), `DTAPPRVBY` the approver (52),
`DTUPD_TAG` 1 once posted (2 while waiting). The member's cash comes later, out of the vouchers-payable account. Posting writes `sldtl` (debit +, credit -, `TR_CODE` 21) and `gltrans`.
This is the same voucher the loan release uses (`loan-posting-map.md`), without a loan line.

## What the app does

* `POST /api/front-office/share-withdrawals` (`ShareWithdrawalController`) encodes the voucher: share capital debited, the member's vouchers-payable account credited, every line tag 2,
  `IS_FO` 0, document no. `*` plus 7 digits. Needs "new" rights on type 21. GL accounts come from `glcontrol` (class 31 by type, class 29 type 1, event 11, status 15).
* Approve and post go through `LoanReleaseController` (Front Office > Disburse): encoder, approver and poster must be three different people (a "Loans alone" exemption applies).
* Refused: account not active (15), client closed or not approved, amount over the balance minus the amount already waiting in other unposted withdrawal vouchers, no vouchers-payable account.
* Test: `ShareWithdrawalPostingTest` (encodes, approves, posts, rolls back).

## Not known

* Whether the old system has a minimum capital that must stay, or closes the account (status 17) when it is emptied. The data has 525 debits but the app does not change `sc.SCSTATUS`.
* Withdrawals combined with savings in one voucher (as in the example) are not built; each voucher here has one share line.
* Paying the member in cash out of vouchers payable.
