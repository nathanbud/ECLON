# Dividend and patronage refund (annual run) - partly mapped

Read only, from `iaccsbsucmpc01`. Not built; nothing is written for it.

## What the old system posted

One run a year, in February: Module 6, type 54, "Dividend and Patronage Refund Transaction (Computer Generated)" with a cut-off date (2024-03-22, 2025-02-22, 2026-02-21).
The run is split into 8 to 10 vouchers of about 7,000 lines each (`transsum` module 6, `IS_FO` 0, `BATCH_NO` the cut-off date, no client), all encoded and posted by one person, tag 1.
The explanation names the fund account for both the share capital interest and the patronage refund: **20126** "Int on ShareCap. & Patronage ref payable".

Each voucher has one debit line on 20126 (no client) and, per member, credit lines (event 11, `DTCTS_NUM` 15 except where noted):

| Account | Class / type | What it is |
|---|---|---|
| 2021401 | 29 / 5 | member's Mutual benefit fund payable |
| 2021403 | 29 / 6 | member's Hospitalization assistance (HABP) |
| 2010101 | 22 / 1 | member's regular savings (the rest of the dividend) |
| 3010101 | 31 / 1 | paid-up share capital, for members with no savings account (dividend stays in shares) |
| 101204 | 13 / 3 (event 11, `DTCTS_NUM` 11) | accounts receivable, for members who owe it (60 in 2026) |

2026: 25,189 members, 166,028,494.02 credited in total (14 per cent of members got a share capital credit, 82 per cent a savings credit, 84 per cent a mutual benefit credit).

## The rule so far

* **Dividend = 8% of the paid-up share capital** for members whose balance did not move during the year: 1,225 with 21,000 got 1,680.00, 20,000 got 1,600.00, 127,000 got 10,160.00 (checked on about 20 of 40 sampled members, exactly). Members whose balance moved got between 7.5% and 11.6% of the average balance, so the balance is averaged over some period I have not fitted (the calendar year and the 12 months to the cut-off both come close, neither is exact).
* **Out of that total** the member's insurance-type contributions are taken first: mutual benefit (mostly 902.17, a full year; lower for members who joined or paid part) and HABP (861.60 to 864.06, 697.83 or lower). What is left is credited to savings, or to share capital when there is no savings account, or to the receivable.
* A few members receive more than 8% (one 20%): probably the patronage refund (interest paid on loans), which is not separated in the data.

## Not known, needed before anything is built

* The averaging period and method for the share capital balance, and the rate and whether it is declared by the general assembly each year (8% in 2026; 2024 and 2025 not yet checked).
* How the mutual benefit and HABP amounts are worked out (days covered, age, claims?).
* The patronage refund rule and its fund.
* Which members are excluded (closed, resigned, dormant), and how the receivable is chosen.
* Who approves it: the old run was encoded and posted by one person; the app would need three, as for the other runs.

Scratch analysis: 2024-2026 lines were pulled with the `coop_app` user and compared against `sldtl` class 31 type 1; the scripts are not kept.
