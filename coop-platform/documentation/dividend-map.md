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

## The rule (fitted 2026-10-28 on the 2026 run, 25,189 members)

For each member the amount credited in total is:

`total = round(8% x average share capital) + round(8.23% x loan charges paid)`

* **Dividend, 8%**: the paid-up share capital (class 31 type 1, event 11) is taken at the **12 month-end balances of the calendar year before the cut-off** (31 January to 31 December 2025), averaged, then x 8%, rounded half up.
* **Patronage refund, 8.23%**: loan events **23 (interest), 31 (service fee) and 24** paid on the member's class 12 loans from 1 January to 31 December 2025 (by `TR_DATE`), x 8.23%, rounded half up. Event 41 (penalty) and 22 do not count (tested: 11,153 of 11,155 members with a refund fit 23+31+24, no other combination comes near).
* **Together they match 25,165 of 25,189 members to the cent** (99.9%); 14,030 members have a dividend only, the rest also a refund. The 24 misses are not examined.
* The rates 8% and 8.23% are 2026's. They are most likely declared yearly, so the app must take them as inputs.

## How the total is split (partly fitted)

Order of credits: mutual benefit fund (2021401) first, then hospitalization (2021403), then the rest to savings (2010101) or, with no savings account, to share capital (3010101).

* **Mutual benefit** = `min(902.17 - paid in since the last run, total)`, where "paid in" is the member's credits to the fund (cash receipts and vouchers, not the small type 81 postings) after the previous cut-off date. Exact for **20,521 of 25,189** members (81%). The rest are members with a smaller, repeated amount (710.69, 87.31, 317.99 and so on, the same value for hundreds of members), so it depends on a date, probably a prorated first year; not fitted.
* **Hospitalization** is paid up to a cap less the same kind of payments. The cap is **864.06, 863.03 or 861.60** for about 13,400 of the roughly 14,000 members who also got savings, and other lower figures for the rest. What decides which of the three is not known.
* The debit is one line per voucher on 20126 for the sum.

## Not known, needed before anything is built

* Why 902.17 for the mutual benefit fund and what the smaller repeated amounts are (a prorated first year?).
* What makes the hospitalization cap 864.06, 863.03 or 861.60 (age band, date joined?).
* Whether the 8% and 8.23% are set each year, and what 2024 and 2025 used (not checked).
* Which members are left out, how the receivable (101204, 60 members) is chosen, and the 24 members the total does not fit.
* Who approves it: the old run was encoded and posted by one person; the app would need three, as for the other runs.

Scratch analysis: the lines were pulled with the `coop_app` user and compared against `sldtl` (class 31 type 1 for share capital, class 12 events 23, 31 and 24 for loan charges, class 29 types 5 and 6 for the funds); the scripts are not kept.
