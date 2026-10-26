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
* **Interest** at rollover: of 1,629 rollovers from 2025 to April 2026 with a single old certificate, **1,181 (72%)** credit exactly `principal x rate% x term days / 360 - 10.00`, where the principal is the amount debited. The flat 10.00 is not explained in the data (a fee on the new certificate?). Most of the rest are partial rollovers or top-ups where the principal rolled differs from the certificate's, or another rounding. The app uses this only as an estimate.
* **Pre-termination** (Cash Voucher type 21, e.g. "pretermination of TD @ 1%"): a debit of event 11 for what is paid out and, in the same voucher, the credit for the new certificate if the money is re-placed. Certificate 11994 was cut to 4,014.49 that way. The reduced rate ("@ 1%") is typed in the explanation; there is no field for it.
* **Payout at maturity**: a Cash Voucher (type 21), a Deposit Withdrawal (23) or a transfer to savings ("withdrawal of matured TD to be transferred to SD").
* **Placement**: new money comes in by Cash Receipt (type 10, 2,044 rows in 15 months) or from savings by journal (type 1) or voucher (21).

## What the app shows (read only)

`GET /api/members/{b}/{c}/time-deposits` (`?includeClosed=`) and `.../{type}/{ref}` (entries, newest first, with the principal after each principal row).

* **Status** from the ledger: Running (money in, maturity not passed), Matured (money in, maturity passed, waiting for rollover or payout), Closed (the master says 17, or the ledger is empty again), Certificate only (no ledger rows).
* **Balance** = minus the sum of event 11. **Accrued interest** = minus the sum of event 71. **Interest at maturity (estimate)** = balance x rate x term days / 360.

## Not built, and open

1. **Placing a certificate** (cash receipt into class 24, new `td` row, `slsum`). Needs a real placement receipt mapped and INSERT on `td`.
2. **Rollover and payout at maturity**, **pre-termination** (the reduced rate), **year-end accrual** (event 71).
3. What the flat 10.00 is, and the rule for the 28% of rollovers that do not match.
4. Event 72 and the unused `TDRO_*` / `TDAI_*` (auto roll over, auto interest) columns.
