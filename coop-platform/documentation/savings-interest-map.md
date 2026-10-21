# Savings interest ("Interest on Deposits (Computer Generated) - A.D.B."), mapped read only

Source: the quarterly run of 31 March 2026 in the legacy database (`transsum` module 8, type 53, year 2026, control numbers 138 to 151; 2025 has 44 such
vouchers, one set per quarter end). Nothing was written.

## What the old system does

* **One run per quarter end** (31 March, 30 June, 30 September, 31 December), dated the last day, `BATCH_NO` the date. It is split into many vouchers (14 in
  March 2026), one per savings type and rate, cut into chunks of about 7,000 member lines.
* **Explanation text** states the parameters, for example: `Interest on Deposits (Computer Generated) - A.D.B. Computed for the period :: 01/01/2026 to
  03/31/2026 :: Parameters: SD - Regular Int. Rate: 1.00% p.a. W/Tax Rate: 0.0000% p.a. MinADB: 500.0000 MaxADB: 9,999,999,999,999.0000 IntExpAcct: 501.02
  Interest expense on deposits`. Rates seen: Regular 1.00%, Associate 0.25%, Kiddies 2.00% (withholding tax rate 0 for all).
* **Lines:** one credit per member account (class 22 type 1, 2 or 3, event 11, `DTCTS_NUM` = the account status, 15 active or 16 dormant, `DTSL_DATE` = the
  run date, `DTACCT_CODE` the deposit GL 2010101, `DTDEPT` the account's department) and, at the end of the voucher, one plain GL debit per department to the
  interest expense account (50102 for Regular and Associate, 50103 for Kiddies), so the voucher balances to zero. Example: voucher 151 has 6,275 credits and
  4 debits (542,958.37 / 30,562.48 / 106,217.70 / 55,635.10).
* **People:** encoded by one person (27), approved by another (49), posted by the encoder (27). In the data seven of the fourteen vouchers (138 to 144) were
  still at tag 2 (not posted) on the last day; 145 to 151 were posted (tag 1).

## The computation (reproduced)

```
daily balance      = balance at the end of each day of the period (deposits that day count that day), event 11 of sldtl, sign flipped
period             = first day of the quarter to the run date, both included (90 days for January to March 2026)
A.D.B.             = sum of the daily balances / days
interest           = A.D.B. x rate x days / 365   (= sum of daily balances x rate / 36,500), rounded half up to the cent
no interest when   = A.D.B. < MinADB (500)
rate               = the loan type's (sltype.SLTypeINT_RATE of class 22), NOT the rate stored on the account (`sd.SDINT_RATE`)
```

The interest credited on the run date itself (type 53) is not part of that day's balance in the calculation, but earlier quarters' interest is in the opening
balance. `SavingsInterest` implements this; `SavingsInterestRealDataTest` recomputes 360 real postings (accounts from vouchers 145, 146 and 148): 359 exact, 360
within a cent, and `SavingsInterestTest` covers the arithmetic.

## Not yet known or built

* **Which accounts are included**: status 15 and 16 appear; closed accounts, accounts with holds, accounts under a maximum and the exact MinADB per type were not
  checked against the whole population (only against posted lines). MaxADB is effectively unlimited.
* **Withholding tax** (0 here): the posting for a non-zero rate is unknown.
* **Dormant accounts** (status 16) earn interest in the run; how a dormant account is reactivated is still unmapped.
* **Posting the run** is not built: it would write about 90,000 lines per quarter across fourteen vouchers (`transsum`, `transdt`, `sldtl`, `slsum`, `gltrans`)
  and needs approval and posting by different people, grants, and an idempotence guard so a quarter cannot be run twice. Next step: a read-only preview of the
  whole run (counts and totals per type) compared to the 31 March 2026 vouchers.
* Time deposit interest and the dividends (type 54, module 6, yearly in February) are separate runs, not mapped here.

## Whole-quarter preview (read only, `SavingsInterestPreviewTest`)

Recomputed every savings account for 1 Jan to 31 Mar 2026 (56,751 accounts, 42,768 earn interest) and compared with the posted vouchers 145 to 151. Vouchers 138 to 144 are an unposted earlier copy of the same run (adding them doubles every total), so only 145 to 151 count.

- 42,258 of 42,277 posted accounts agree within a cent (type 1: 27,261, type 2: 6,363, type 3: 8,634).
- 19 differ: 14 by under a few pesos (e.g. 209.82 vs 209.09), one by 14 pesos (45.69 vs 31.95), and one hugely: client 33189, type 1 ref 45805, mine 2,739,743.62, posted 20.33 (a very large balance for part of the quarter; look at its ledger before trusting the run).
- 491 accounts earn interest in my run but were not paid by the old run (status 15: 418, 16: 32, 17: 41; mostly small, largest 1,689.59). Why they were skipped is unknown (a hold, a flag on the account, opened or reactivated late?). No account was paid that I would not pay.
- Total: mine 7,319,087 vs posted 4,563,378. Without the one giant account, mine is about 4.58M, about 0.4% over posted.

### What the preview explained

- **Skipped accounts:** the old run pays only accounts whose balance at the end of the quarter is positive. 486 of the 491 accounts I pay and it did not had an ending balance under 1 peso (withdrawn in full but still status 15, 16 or 17); none of the 42,277 paid accounts did. The other 5 skipped accounts have a positive ending balance and are still unexplained.
- **The outlier (client 33189, type 1, ref 45805):** one journal line, Journal Voucher (TR_CODE 1) 2336 of 2024-04-19, credits 1,111,112,464.00 to the account (a typo-sized entry). The old run did not count it (it paid 20.33, which fits a balance of a few thousand pesos), so how the old system leaves it out is not known (perhaps a posted-status flag on the line, or a balance read from somewhere other than `sldtl`). Until that is known the run must flag any account whose balance moves by more than a set amount in one entry for a person to check instead of paying it.

## The posting run (built, `SavingsInterestController`, `/api/front-office/savings-interest`)

`GET /preview?quarterEnd=`, `POST /encode`, `GET /vouchers?quarterEnd=`, `POST /{year}/{ctl}/approve`, `POST /{year}/{ctl}/post`. Needs `access_trans` find/new on type 53 (not one of the window's own types, so it checks the rights itself). The calculation is `SavingsInterestRun`: rate from `sltype`, minimum A.D.B. 500, only status 15/16, **only accounts ending the quarter with 1 peso or more**, department = `client.ClientDEPT`, expense account 50102 (types 1, 2) or 50103 (type 3); an account with 100,000 or more interest, or no department, is held back and listed. Encode writes the vouchers as the old system does (per type, 7,000 members each, one debit per department, every line tag 2, dated the quarter end) and is refused if the quarter already has any type 53 voucher (the guard against running twice). Approver and poster must each differ from the encoder and from each other; no single-person exemption. Posting writes `sldtl`/`slsum` and `gltrans` dated the quarter end, as the old run did. No new grants are needed (V011, V014, V015 cover it). Preview for 30 June 2026: 42,914 accounts, 4,731,625.21, one held back. `SavingsInterestRunTest` (rolled back): preview, encode and approve passed; the post step could not be completed because the disk was full ("The table 'sldtl' is full") and has not been rerun.
