# Time deposits in the old system

Mapped on 2026-10-26 from `iaccsbsucmpc01` (read only). Code: `TimeDepositController`, `TimeDepositRules`, tests `TimeDepositTest`. Only the read side is built: the Time Deposits tab on the member profile. Nothing posts yet.

## The tables

* **`td`** (13,003 rows, 2014 to 2031) is the certificate: `TDSLT_CODE` the type (class 24: 1 TD - Short Term, 2 TD - Lock In, 3 TD - Short Term New, 4 TD - Lock In New), `TDREF_NO` the account number and certificate number (`TDCERT_NUM` is the same), `ClientIDTD`, `TDPAMT` the principal as placed, `TDTR_DATE` the placement date, `TDTERMS` the term **in days** (90, 180, 270, 360, 365 for short term; 1,095 to 1,827 for lock in), `TDTERM_PERD` always 1, `TDMAT_DATE` the maturity date, `TDINT_RATE` a percent a year, `TDSTATUS` 15 or 17 (17 on only 4 rows), `TDREMARKS`. The columns `TDRO_*` (roll over into) and `TDAI_*` (auto interest, `TDAI_FREQUENCY`) exist and are empty on the rows seen.
* **The master row is never closed when the money is paid out**: a certificate pre-terminated or rolled over still reads `TDSTATUS` 15. The state is in the ledger.
* **The ledger is class 24 of `sldtl`**. Event **11** is the money in the certificate (a credit, money in, is stored negative, as for savings); the balance is minus the sum. Event **71** is interest accrued at year end (credit, `TR_CODE` 52, dated 12/31, one row a certificate), taken back with a debit when the certificate is paid out or rolled over. Event 72 appears 44 times and its meaning is not known. `TR_CODE` 96 is an opening balance entered when the data was loaded (dated 2023-03-15).
* **GL** (`glcontrol` class 24): event 11 goes to 2010201 to 2010204 by type (status 15, 16, 17 the same); events 71, 72 and 73 to 2011601 (Lock In types) or 2011602 (Short Term types), number 99.
* 473 of the 4,792 certificates placed since 2025 have no ledger rows at all. The app shows them as "Certificate only" with the master amount.

## How a certificate moves

Examples: client 1637, certificate 2403 to 11617 (2023 to 2026); client 36306, certificates 11994 and 12098.

* **Rollover** ("Time Deposit Rollover (Computer Generated) - Maturity Date Less Than or Equal To ..." is a General Journal, module 1 type 4, written by a batch run at maturity): one debit of the old certificate's principal (event 11), one debit that reverses its year-end accrual (event 71), and two credits on the new certificate: the old principal, and the interest earned. The new certificate (`TDPAMT` = principal plus interest) starts the same day with the same type, rate and term.
* **Interest** at rollover: of 1,629 rollovers from 2025 to April 2026 with a single old certificate, **1,181 (72%)** credit exactly `principal x rate% x term days / 360 - 10.00` into the new certificate, where the principal is the amount debited. The 10.00 is a **renewal certificate fee**: the computer-generated rollover journal (module 1 type 4, e.g. 2026 no. 3750) debits the old certificate's principal and the interest expense `50105` (the whole term interest), credits the new certificate with principal plus interest less 10.00, and credits the 10.00 to `442202` (the same account as the fee on a new certificate). Most of the rest are partial rollovers or top-ups where the principal rolled differs from the certificate's, or another rounding.
* **Pre-termination** (Cash Voucher type 21, e.g. "pretermination of TD @ 1%"): a debit of event 11 for what is paid out and, in the same voucher, the credit for the new certificate if the money is re-placed. Certificate 11994 was cut to 4,014.49 that way. The reduced rate ("@ 1%") is typed in the explanation; there is no field for it.
* **Payout at maturity**: a Cash Voucher (type 21), a Deposit Withdrawal (23) or a transfer to savings ("withdrawal of matured TD to be transferred to SD").
* **Placement**: new money comes in by Cash Receipt (type 10, 2,044 rows in 15 months) or from savings by journal (type 1) or voucher (21).

## What the app shows (read only)

`GET /api/members/{b}/{c}/time-deposits` (`?includeClosed=`) and `.../{type}/{ref}` (entries, newest first, with the principal after each principal row).

* **Status** from the ledger: Running (money in, maturity not passed), Matured (money in, maturity passed, waiting for rollover or payout), Closed (the master says 17, or the ledger is empty again), Certificate only (no ledger rows).
* **Balance** = minus the sum of event 11. **Accrued interest** = minus the sum of event 71. **Interest at maturity (estimate)** = balance x rate x term days / 360.

## Placing a certificate (built, needs V027)

Mapped from real placements of 24 April 2026 (e.g. Cash Receipt 352754, "NEW TD FOR 6 MONTHS", client 14261, certificate 1/12110) and the 521 placements of 2026:

* **Receipt** (module 3, type 10, `TR_CLIENTID` the member, `IS_FO` 1, the poster's own drawer). `transdt`: seq 0 the cash debit on 10002/10003 for the **amount plus the certificate fee**; seq 1 the certificate credited (class 24, type, reference = the new certificate number, event 11, number 15, `DTCOCI_TYPE` 1); seq 2 the fee credited to **442202 "Other income - certificates"** as a plain line (no sub-ledger, but `ClientIDDT`, `DTADJ_FLAG` 1 and the date like the member line). One `sldtl` row (event 11, sequence 1, `COCI_TYPE` 1), one `slsum` row, `gltrans` for the three lines, and the cash row in `transck`.
* **Fee**: 25.00 on 346 receipts that issued a certificate, 10.00 on 11, 50.00 on 2; none on the "ADD'L TD" placements. The app charges 25.00 when the cashier ticks "issue a certificate".
* **New rows**: `slmastersum` (the account master, which the ledger needs first) and **`td`**: `TDPAMT` the amount, `TDTR_DATE` today, `TDSTATUS` 15, `TDINT_RATE`, `TDTERMS` days, `TDTERM_PERD` 1, `TDMAT_DATE` = placement date + term days (checked on the 2025 and 2026 certificates), `TDCERT_NUM` = the certificate number as text; every other column empty. **Certificate numbers run on for each type** (type 1 was at 12,114, type 2 at 1,214): the app takes the highest plus one with a row lock.
* **Rate and term**: no rate table was found (`sltype.SLTypeINT_RATE_CODE` 27 points at nothing readable), so the cashier types the rate. Terms in use for TD - Short Term are 90, 180, 270, 360 and 365 days; rates vary by term and by the size of the deposit (90 days: 1.50 to 2.00, 360 days: 2.25 to 3.00, lock in 1,826 days: 5.00). The app lists the terms used in the year up to the latest placement and the rates used by at least three certificates, defaults to the commonest rate, and asks a supervisor to allow a rate outside the range (`ecis_fo_override` kind `TD_RATE`, spent once, audit `td.rate.override.used`). Interest uses a 360-day year (`SLTypeNDAYS_1YEAR_INTDEPO`).
* **Minimum**: the type's `SLTypeMIN_BAL` (5,000.00 for TD - Short Term). The app refuses less; a few real placements are below it, so this is stricter than the old window.
* **Cheques** are accepted for the payment (the plain fee line takes part in the cash and cheque split like any other line).
* Code: `TimeDepositPlacementController` (`/api/front-office/time-deposits`: `setup`, `check`, `ask`, and `POST` to place), `TimeDepositPlacementRules`, the plain-line support in `MemberLedgerPoster.postVoucher` (a `MemberLine` with class 0); the dialog is `TimeDepositPlaceDialog` on the Time Deposits tab. Needs `V027_time_deposit_placement.sql` (INSERT on `td`, run by an administrator); tests `TimeDepositPlacementPostingTest` skip until it is applied.

## Paying out and renewing a matured certificate (built; nothing runs by itself)

The owner asked that rollover is **never automatic**: a person asks for each payout or renewal, and a notice screen tells staff what is due. The old system also did it by hand in the back office: a Cash Voucher (module 2, type 21, `IS_FO` 0, every line tagged 2 until posted, encoded, approved and posted by different people) such as 2026 nos. 37575 (renewal with 1,000.00 taken out), 36133 (payout) and 39897 (pre-termination).

* **Interest** for the term = balance (the ledger, not `TDPAMT`, which can differ by cents) x rate% x term days / 360. Of it, what was accrued at year end (the sum of event 71, credited as the year-end run did) is taken back by a debit of event 71 (GL 2011601 or 2011602 by type, number 99), and the rest, **interest minus accrued**, is debited to **50105 "Interest expense on time deposit"** (a plain line). Voucher 37575: 8,259.33 = 5,597.99 accrued + 2,661.34 expense.
* **Payout**: debit the certificate (event 11, the whole balance), the accrued interest and the expense; credit the member's vouchers-payable account (class 29 type 1, event 11, GL 2012305) with principal plus interest. The cash is paid out of vouchers payable by a cashier later (voucher-payout-map.md).
* **Renewal**: the same debits; credit the new certificate (same type, event 11) with balance + interest, less what the member takes out, less the 10.00 renewal fee; credit the fee to 442202 and the part taken out to vouchers payable. The new certificate's `td` and `slmastersum` rows are made when the voucher is encoded (they need V027 and V028), numbered like a placement.
* **Who and when**: `TimeDepositClosureController` (`/api/front-office/time-deposit-closures`: `quote`, and `POST` to encode). Only Short Term certificates (types 1 and 3) that have matured, hold money, have no voucher already waiting, and whose member has a vouchers-payable account. Lock In certificates (types 2 and 4) are refused: their interest is not paid at the end of the term. The voucher is approved and posted on the Front Office Disburse tab by two other people (`LoanReleaseController` now also accepts a debit of class 24). Renewal terms are the terms in use for the type (up to 365 days); the rate is typed and the approver sees it in the explanation.
* **Notices** (`TimeDepositMaturityController`, `GET /api/time-deposits/maturities?days=` and `/maturities/count`): certificates holding money that matured in the last 400 days or mature in the next days, with how many days are left or overdue; shown on the Front Office **TD Maturities** tab (badge = matured and waiting), with a badge on the Front Office tile, and with Pay out and Renew buttons. The same buttons are on the member's Time Deposits tab. Tested by `TimeDepositClosureTest` (encode, a second voucher refused, approve and post by three people, the renewed certificate holds the money).

## Not built, and open

1. **Pre-termination** (voucher 39897: the old certificate is cut, interest is paid at a reduced rate typed in the explanation, 1% there, for the days kept; a 100.00 charge is credited to 442201), and partial withdrawals.
2. **Year-end accrual** (the run that credits event 71 on 12/31 and debits the interest expense).
3. **Lock In** certificates (types 2 and 4): how their interest is paid.
4. (Built since: paying the cash out of vouchers payable, see voucher-payout-map.md.)
5. Event 72 and the unused `TDRO_*` / `TDAI_*` (auto roll over, auto interest) columns; the rule for the 28% of rollovers that do not match.
6. Where the old window got its rates, and whether the minimum is really enforced there.
