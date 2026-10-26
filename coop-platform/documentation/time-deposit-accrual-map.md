# Time deposit interest accrual (module 8, type 52)

"Accrued Interest on Deposits (Computer Generated) - A.D.B." is the old batch that credits interest earned so far to each certificate (ledger **event 71**,
GL 2011601 for the Lock In types 2 and 4, 2011602 for the Short Term types 1 and 3, `DTCTS_NUM` 99) and debits the interest expense **50105**. A payout or
rollover later takes the accrued part back and expenses the rest (`time-deposit-map.md`).

## What the old system wrote (2025 and 2026 runs, vouchers 79 to 106)

* **Header** (`transsum`, module 8, type 52): `TR_DATE` = the period end, `IS_FO` 0, no client, `TR_DOCNO` = control number as 8 digits, `BATCH_NO` = the date as yyyymmdd. The explanation is text: "Accrued Interest on Deposits (Computer Generated) - A.D.B. Computed for the period :: 03/01/2026 to 03/31/2026 :: Parameters: Accrual Type: Normal Accrual / TD - Lock In / IntExpAcct: 501.05 ...".
* **One voucher per certificate type**, run by a person who picks the type. Lock In runs every **month end** ("Normal Accrual", the month only); Short Term runs once a year on **December 31** ("End of Period Accrual", January 1 to December 31). In December both run. Type 4 (Lock In New) was run only in January to March 2026, type 3 (Short Term New) never.
* **Lines**: one credit per certificate (class 24, event 71, status 99, `DTSL_DATE` = period end, `DTADJ_FLAG` 1, no tender type, `DTDEPT` = the owner's `ClientDEPT`), then one debit of 50105 per department. Tag 1 once posted; encoder, approver and poster are recorded (the old poster was often the encoder).
* One `sldtl` row per certificate (event 71, `TR_CODE` 52, `TR_DOCNO` = the 8-digit number, `ADJ_FLAG` 1, `COCI_TYPE` NULL) and one `gltrans` line per line.

## The amount (checked against the real runs)

Interest = sum over each day of the period of the certificate's **end-of-day balance** (ledger event 11, money in counted from its placement day) x the certificate's own rate (`td.TDINT_RATE`) / 100 / 360, rounded half up. A certificate placed mid-period earns only for the days it holds money (a 1,000,000 certificate at 5% placed March 30 earned 277.78 = 2 days). Only certificates that still hold 1 peso or more at the period end are accrued (paid out or rolled over earlier in the period: nothing).

`TimeDepositAccrualRealDataTest` compares the calculation with the old runs: March 2026 Lock In 794 of 794 old lines exact, February 797 of 797, December 2025 Lock In 782 of 784, December 2025 Short Term 1,499 of 1,504 (the few differences are certificates with an adjusted rate or amount). The calculation also finds a few certificates the old run did not accrue: types the operator did not choose that month (type 3 at the 2025 year end, type 4 in December 2025) and one or two certificates left out.

## What the app does (`TimeDepositAccrualController`, `/api/front-office/time-deposit-accruals`; tab Front Office > TD Accruals)

* `GET /preview?group=LOCK_IN|SHORT_TERM&to=` (read only): per type, the certificates and the total; which types already have a voucher for the date. `to` must be a month end (Lock In) or December 31 (Short Term) and not in the future.
* `POST /encode` (group, date, optional list of types; all of the group by default): writes the vouchers as the old run did, every line tagged 2, up to 7,000 certificates a voucher. A type that already has a voucher dated that day is refused, so a run cannot be repeated. Owners without a department are left out and counted in the preview.
* `POST /{year}/{control}/approve` and `/post`: a second person approves and a third posts (stricter than the old system, like the quarterly savings interest run); posting writes the event 71 rows, creates the `slsum` row on first use, and writes the general ledger lines dated the period end. Needs access_trans type 52 (find to look, new to encode, approve and post) and front office access.
* **Nothing is scheduled**: a person starts every run, as for the maturity notices. Audit actions `td.accrual.encode`, `.approve`, `.post`.
* Tested by `TimeDepositAccrualPostingTest` (a Lock In month the old data has not run, three people, the rows compared with the old shape; old dates, wrong dates and wrong types refused; rolled back).

## Not built

* A reversal of a wrongly posted accrual (a General Journal today).
* Withholding tax and a non-zero rate parameter (the old runs show 0.00% for both).
* Accrual for other deposit kinds (savings interest has its own quarterly run, `savings-interest-map.md`).
