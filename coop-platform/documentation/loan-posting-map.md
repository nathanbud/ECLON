# How the legacy system releases a loan and takes a loan payment

Mapped from real posted transactions in `iaccsbsucmpc01` (read only, nothing was changed). Use it as the spec for loan release and
loan payment postings, and compare any new posting against a real one before trusting it (see CLAUDE.md). The ledger conventions are
those of `share-payment-posting-map.md` and `savings-posting-map.md`: `transdt` debit positive and credit negative, one `sldtl` row per
member-account line, `gltrans` per entry, only `DTUPD_TAG` 1 or 5 reaching the ledger.

## What a loan is made of

| Table | Role |
|---|---|
| `loanapp` (32k) | The application. Statuses (`loanappstatus`): New, Verified, Approved, Disapproved, Cancelled, Deferred, RELEASED |
| `loan` (43k) | The loan itself: amount, terms, term unit (`term`), payment mode (`paymentmode`), amortization (`amortype`), rates, penalty rate and grace days, first due and maturity dates, collateral, `LoanSTATUS` |
| `amortsched` (580k) | The repayment schedule: one row per due date, the first row (the release date) has nothing due |
| `comaker` (16k) | Co-makers of a loan |
| `sldtl` class 12 | The subsidiary ledger of the loan: event 11 principal, 23 interest paid, 31 service fee, 22 unearned interest, 24 interest earned, 41 penalty, 42 penalty waived |
| `loandeduc` (148k) | What is deducted at release (share capital, fees, other loans); not mapped in detail |

`LoanSTATUS` is the number `glcontrol` (class 12, event 11) keys the loan's general ledger account by: **11 current, 12 past due, 13 in
litigation, 14 restructured**. Each status has its own receivable account, for example type 48 "Back to back loan": 10110151 current,
10110251 past due, 10110451 in litigation, 10110351 restructured.

## Release: a Cash Voucher (type 21, module 2), posted from the back office

### The simple case: control no. 39888 (2026-04-23)

A back-to-back first-time loan of **500,000.00** to client 94724 (type 3, ref 235), `IS_FO` 0, `TR_DOCNO` `00039888` (the control number as
8 digits, not `*`), explanation "per Approved Back to back First Time Loan of ...", `BATCH_NO` the date.

| Seq | Account | Client / ledger account | Event, CTS | Amount |
|---|---|---|---|---|
| 1 | `10110103` Back-to-back-first-time - current | 94724, class 12 type 3 ref 235, `DTADJ_FLAG` 2 | 11, 11 (status current) | **+500,000.00** |
| 2 | `412` Filing fees/Service fee | 94724, class 12 type 3 ref 235 | 31, 99 | **-5,000.00** (1% of the loan) |
| 3 | `2012305` Vouchers payable | 94724, class 29 type 1 ref 19416 | 11, 15 | **-495,000.00** (net proceeds) |

`sldtl` gets two rows on the loan (principal +500,000 and service fee -5,000, sequence numbers 1 and 2) and one on the member's
vouchers-payable account. The proceeds are **not** paid in cash by the release: they are credited to a "vouchers payable" account of the
member (class 29 type 1, its own reference number) and paid out by a later disbursement. Encoded by one person (`DTENCDBY` 8), approved by another (`DTAPPRVBY` 52), posted by a third (`DTPOSTBY` 6), `DTUPD_TAG` 1.

### With deductions: control no. 36867 (2026-02-10)

A loan of 44,000 (type 48) shows what the release does with the member's other balances: the same voucher also credits **share capital** (3010101,
class 31: -1,000.00 and 3010102: -320.00), a miscellaneous liability (2021403, class 29: -61.90), **pays off an earlier loan** (type 48 ref 10241: principal
-18,059.35 and interest -118.75) and credits vouchers payable with the rest (2012305: -24,000.00). The six credits above plus the service fee (-440.00) equal the 44,000 debit.
So a release is "one debit for the new loan, credits for everything deducted and for the net proceeds", all in one voucher.

### What else a release writes (seen, not reproduced)

* a `loan` row (created at release: `LoanTR_DATE` is the release date, status 11),
* the schedule in `amortsched` (13 rows for a 12-month loan: the release date, then one per month),
* the `loanapp` row moves to RELEASED, and `comaker` rows if there are co-makers.

## Interest, and the instalment split on a payment

The schedule's interest is the interest on the **outstanding principal for the actual days** between due dates on a 365-day year:
44,000 at 10%, 10 February to 10 March (28 days) is 44,000 x 0.10 x 28 / 365 = **337.53**, and the next month (31 days) on 40,333.33 is **342.56**,
both exactly as in `amortsched`.

On a payment the old system recomputes interest on what is really outstanding for the days since the last interest payment, not what the schedule says:

* loan 14415 paid 4,300.00 on 2026-03-10: interest **337.53**, principal **3,962.47** (interest first, the rest to principal),
* it then paid again on 2026-04-17, 38 days later, on 40,037.53 outstanding: 40,037.53 x 0.10 x 38 / 365 = **416.82** interest, 4,533.18 principal.

Checked on a second loan (10061 / type 49 / ref 664, 120,000 at 10%, 19 payments): **15 consecutive payments from 2025-02-24 to 2026-04-24 match
outstanding x rate x days since last payment / 365 to the cent** (for example 2026-04-24: 32 days, 523.02 interest on 59,657.28 outstanding), and the 3 first
payments (October to December 2024) match a 360-day year instead (34 days on 120,000 = 1,133.33). The payment of 2025-01-23 matches neither (802.13). So:
**the day basis changed around the turn of 2024/2025 (360, then 365), and the January 2025 payment is unexplained.** New postings should use 365.

**Rounding:** of 18 real payments checked (the 15 above plus 3), plain rounding to the cent matches the old system exactly on 14 and is one cent off on 4 (for example the old system booked 416.82 where the formula gives 416.83). I tried truncation, rounding the daily rate to 2 to 8 places and half-even; none matches all 18, so the old system has a rounding detail not found yet. A payment taken by this program can therefore differ by a cent from what the old system would have booked.

A first payment long after the first due date (loan 11016, 100,000 at 12%, paid 2026-01-09, 136 days after release): interest 100,000 x 0.12 x 136 / 365 = **4,471.23** exactly, and
the principal portion 6,666.94 equals four instalments (4 x 1,666.67) because four were due by then.

## Payment: a Cash Receipt (type 10, module 3), from the front office window

Control no. 352821 (2026-04-24, `IS_FO` 1, `TR_DOCNO` `*0352821`), client 10061 paid **4,000.00** in cash on loan type 49 ref 664, explanation "LOAN IACCS":

| Seq | Account | Ledger account | Event, CTS | Amount |
|---|---|---|---|---|
| 0 | `10003` Cash on hand - tellers | class 11 type 1 ref 1 | 53 (teller), 91 (teller number) | **+4,000.00** |
| 1 | `10110152` loan receivable, current | 10061, class 12 type 49 ref 664, `DTADJ_FLAG` 1 | 11, 11 | **-3,476.98** (principal) |
| 2 | `41148` Interest income | 10061, class 12 type 49 ref 664 | 23, 99 | **-523.02** (interest) |

`sldtl` gets two rows on the loan (principal -3,476.98, interest -523.02) with sequence numbers continuing the loan's own sequence over **all events** (81, 82). The ledger also needs a **`slsum` row per account and event, dated by the first entry of that event** (a foreign key from `sldtl`): a loan's first interest payment has to add the row for event 23 (loan 14415: events 11 and 31 dated 2026-02-10, event 23 dated 2026-03-10). Share and savings accounts only ever use event 11, whose row already exists. The posting is the same
as a share payment with one more credit line for the interest. A payment with penalties adds an event 41 line crediting the loan's fines account (`41451` for type 48).

## Penalty

Loans carry a penalty rate (`LoanPEN_RATE`, 36 per cent a year for these types), a penalty mode and a grace period in days (`LoanPENGP`, 15). Payments
that are late show an event 41 line (loan 11016, paid 2026-01-09, four instalments late: penalty 480.15; paid 2026-04-22: 196.69). The exact base of the penalty (late principal alone, principal and
interest, the days counted from the due date or from the end of the grace period) was **not** reproduced: the plain variants (36 per cent a year on the late principal or on the late instalments, with and without the 15 days) come
close (about 470) but not to the cent. Do not post penalties until this is pinned down.

## The repayment schedule (`amortsched`) for the common loans

Checked against the real schedules of 600 recent loans (types 48, 49, 4, 3; term in months; diminishing balance `LoanAMORTYPE` 1; monthly payment mode 4), read only:

* The first row is the release date: `PRINDUE` 0, `INTDUE` 0, `PRINBAL` = the loan amount.
* One row per month for `LoanTERMS` months. Principal due = amount / terms rounded to the cent, the **last row takes the remainder** so the balance ends at 0.00.
* Interest due = balance before the instalment x rate / 100 x actual days since the previous due date / 365, rounded to the cent.
* **Due dates:** release date plus n months, with the day of month capped at **30** (a loan released on the 31st falls due on the 30th every month; released on the 30th, February falls on the 28th and March goes back to the 30th). The first due date is `LoanFPD_DATE` and the last is `LoanMAT_DATE`.
* The generator matches **599 of 600** exactly. The one miss is one cent of interest in the last third of a 36-month loan (2028, a leap year), so the day basis in leap years is not settled.
* **Lump sum** (mode 9): two rows, the release date and the maturity date; the whole principal and interest for all the days (50,000 at 10% for 181 days = 2,479.45) fall due at maturity.
* **Quarterly** (mode 5): the term stays in months, there is one row every 3 months (`terms` / 3 payments), equal principal, interest on actual days (30,000 at 10% for 90 days = 739.73).
* Not checked: semi-monthly, weekly, daily, yearly, and annuity or straight-line amortization (`LoanAMORTYPE` 2 to 6; for example type 21 uses annuity, years).

## Correction: the loan, its schedule and the release voucher exist before the release is posted

Looking at approved applications that have no ledger entries yet (loans 15261, 15282, 15395, 1405, 15519), the old system **creates the `loan` row (status 11), the whole `amortsched` schedule, the `loandeduc` rows and the Cash Voucher (type 21) when the application is approved**, not at release. The voucher then sits in `transsum`/`transdt` with every line `DTUPD_TAG` **2** (encoded and approved, not posted) and no `sldtl`, `slsum` or `gltrans` rows. **Posting the voucher** is what releases the loan: the lines go to tag 1, `DTPOSTBY` is set, and the ledger rows (`sldtl` per member line, `slsum` for each new account and event, `gltrans`) are written. The application keeps `STATUSID` 3 (Approved) and gets `TR_CTLNO` set; it is not reliably moved to 7 (RELEASED). Three different people appear on a released voucher: `DTENCDBY` (encoder), `DTAPPRVBY` (approver), `DTPOSTBY` (poster); so who may post depends on a posting permission given to people, not on a fixed role.

So the release feature is **"post an approved release voucher"**, not "create the loan": it needs UPDATE on `transdt` (tag, poster) and on `loanapp` (`TR_CTLNO`), INSERT on `sldtl`, `slsum` and `gltrans` (already granted for the other postings), and no writes to `loan` or `amortsched`. Creating the application, loan row, schedule and voucher at approval is a separate, larger feature (where `LoanSchedule` and `ReleasePlan` will be used).

## The loan application, as the old system writes it (mapped read only, stage 2)

An application writes **everything at once, at status New (1)**, before anyone verifies or approves it:

| Table | What is written |
|---|---|
| `loanapp` | `ID` = max + 1 (32699 was the last), `DOC_NO` = the ID as 8 digits, `SLC_CODE` 12, `SLT_CODE` the loan type, `REF_NO` = the next reference of that type (max + 1 in `loan`; 15524 for type 48, 2906 for type 49), `CLIENTID`, `STATUSID` 1, `TR_DATE`/`RTR_DATE` today, `ENCDBY` the encoder, `TR_CODE` 21, `DEDUCGROUP` the type's deduction group (50 for 48, 51 for 49), `DEPT`, and `NPSLC_CODE`/`NPSLT_CODE`/`NPREF_NO` = 29 / 1 / the **member's vouchers-payable account** (one per member, reused by all their loans, row in `slmastersum`; 27 of 1,712 recent applications have none). There is no amount on `loanapp`. |
| `loan` | One row, `LoanREF_NO` = the application's reference, `LoanSTATUS` 11, `LoanTR_DATE` today. Every setup column is the loan type's default from `sltype` (`SLTypeTERMS`, `TERM_PERD`, `PPMT_MODE`, `IPMT_MODE`, `PEN_MODE`, `AMORTYPE`, `DIMB_FREQ`, `INT_RATE`, `PEN_RATE`, `DD_FLAG`, `PENGP`, `PRIN_GP`, `INT_GP`, `PREPAIDPYMTS`, `ND_FLAG`, `COLLECTTYPE`) unless the clerk changed it (amount, terms, mode, purpose, class, collateral). `LoanFID_DATE` and `LoanFPD_DATE` are the first due date and `LoanMAT_DATE` the last; `LoanMCLASS` the classification (borrowerclass: 1 New, 2 Renewal, 3 Restructured). `LoanUPDDATETIME` is not set. |
| `amortsched` | The schedule (see above): first row the release date, then one per due date; `PENFLAG` 'Y' on due rows (NULL on the first), `CBUAMT`, `SAVAMT`, `COLLFEE`, `SRVFEE`, `INSDUE` 0, and `PRINBAL2` = `PRINBAL`, `PRINDUE_PP` = `PRINDUE`, `INTDUE_PP` = `INTDUE`, `PRINBAL_PP` = `PRINBAL`. |
| `loandeduc` | One row per deduction **with an amount**, numbered from 1 in the template's order (`loansldeduc`), amounts as the clerk accepted them; same ledger fields as the template (class, type, event, the member's account reference, `SLBEHAVIOR`). The net proceeds line is not stored here. |
| `comaker` | One row per co-maker (`ID` auto). |

**Verify, approve and release are three later steps on the same rows:** `STATUSID` 2 (Verified), 3 (Approved, with `APPRV_DATE`/`APPRVBY` and the voucher: `TR_CTLNO`), then the voucher is posted (above). The old `access` table has a flag for each step: `AccessCanAddLoanApplication`, `AccessCanEditLoanApplication`, `AccessCanVerifyLoanApplication`, `AccessCanApproveLoanApplication`, `AccessCanReleaseLoan` (plus `AccessMustUseLoanWizard`). So who may do what is **whoever has the flag**. Most applications in the data jump straight to Approved by the same person who encoded them (for example encoder 8, approver 9), so the old system did not force three different people on the application itself, only on the voucher.

**Approval creates the release voucher (mapped read only, 3,010 approved applications of 2026):** `loanapp.TR_CTLNO` is the control number of a Cash Voucher (module 2, type 21, year of approval) in the ordinary voucher series, so 1167 and 2150 are other vouchers, not a second series. `TR_DOCNO` is the control number as 8 digits (no `*`), `TR_DATE` = `APPRV_DATE` (3,009 of 3,010), `TR_CLIENTID` the borrower, `BATCH_NO` the date as `YYYYMMDD`, `EXPLANATION` = `per Approved <loan type description> of <LNAME, FNAME suffix MNAME>`. The lines are the ones `ReleasePlan` builds, all with `DTSL_DATE` = approval date and the encoder's department: (1) the loan debit, GL from `glcontrol` (12, type, event 11, number = loan status 11 to 14: 10110151 for type 48 status 11), event 11, `DTCTS_NUM` = loan status; (2) the service fee credit, GL `glcontrol` (12, type, 31, 99) = 412, event 31, number 99; (3) one credit per member-balance deduction (share capital 31/2/ref, event 11, number 15 = account status; GL `glcontrol` (31, type, 11, status) = 3010102); (4) the net proceeds credit into 29/1/member's vouchers-payable ref, event 11, number 15, GL 2012305. In the examples `DTENCDBY` = the application's encoder, `DTAPPRVBY` = a third person, `DTPOSTBY` = a fourth, i.e. the application approver (`loanapp.APPRVBY`) is not the voucher approver. Most vouchers are posted (tag 1, 3,020 in 2026) shortly after; 49 sit at tag 2 and a few at tag 5; some of those belong to cancelled applications (status 5 keeps its voucher number).

**Cancelled applications (805 since 2025; none Disapproved or Deferred):** cancel sets `STATUSID` 5 and writes the canceller and the date into `APPRVBY`/`APPRV_DATE` (the same person as the encoder in 665 of 805), no remarks; the `loan` row and schedule stay (status 11), 153 keep their voucher number and that voucher stays unposted (tag 2) in the books.

**The member's accounts at application time (mapped read only, April 2026):** class 29 accounts are rows of `osl` (with the `slmastersum` row first): `OSLSLC_CODE` 29, `OSLSLT_CODE` 1 for vouchers payable, `OSLREF_NO` = next number of that type (19436 was the last; shared with accounts opened in other ways), `ClientIDOSL`, `OSLTR_DATE` the day, `OSLSTATUS` 15, other columns empty. **A loan application opens the member's vouchers-payable account when the member has none**: of the 283 type 1 accounts opened in April 2026, the 124 with `OSLPAMT` = 0.00 each have a loan application by the same client the same day (122 are the very account that application's `NPREF_NO` points to); the 159 with `OSLPAMT` empty came from other screens. All 707 clients who applied in April 2026 had one. Share capital (`sc` 31/2) and mutual assistance and hospitalization (`osl` 29/5, 29/6) accounts are opened with the member's account instead (new members get them the same day, before any application), so an application does not open them; `loandeduc.REF_NO` of these lines is empty in the old data and the voucher lines at approval carry the real references.

**Paying off an earlier loan out of a new one, renewals and restructuring (mapped read only; about 350 of the 11,600 applications since mid-2025 pay something off):** the earlier loan appears as `loandeduc` rows with `SLBEHAVIOR` 4 (a few with 5, same shape), `DEDUCDESC` `<loan type description> : Principal` / `: Interest Paid` / `: Penalty/Fines`, class 12, the old loan's type and reference, events 11 / 23 / 41, empty `ACCT_CODE`. They are not part of the type's template (`loansldeduc`), the clerk adds them. At approval they become credit lines of the release voucher on the old loan's own ledger: principal event 11 with `DTCTS_NUM` = the old loan's status (GL from `glcontrol` 12, type, 11, status: 10110151 for type 48 status 11), interest event 23 and penalty event 41 with number 99 (GL 41147 and 41451 for type 48); posting then credits the old loan's ledger, so a full pay-off leaves it at zero. The new loan carries no link to the paid loan unless it is restructured: `LoanMCLASS` 3 with `LoanRESTRUCT` 1 and `LoanXSLT_CODE` / `LoanXREF_NO` = the replaced loan (14 of 16 pay-off cases of that kind); renewals (class 2) and ordinary pay-offs (class 1, 142 of 160) have no link. The penalty amount cannot be worked out (formula unknown), so the clerk types it.

**Still not mapped:** only creating the member's missing accounts and the renewal and restructuring flows. (Old note, now answered above:) the Approved-time writes (the voucher with its numbers: 39191 is in the cash-voucher series but approved applications from 24 April 2026 carry 1167 and 2150, so there is more than one numbering), creating the member's vouchers-payable, share capital, deposit-for-capital and mutual-assistance accounts when missing (the voucher lines point at the member's own accounts), and the renewal and restructuring flows.

## What is deducted at release (`loansldeduc`, `loandeduc`, `codestorage`)

* **`loansldeduc`** is the per-loan-type template: one row per deduction with its order (`DEDUCORDER`), a `FORMULA`, the target ledger account (`DSLC_CODE`/`DSLT_CODE`/`DSLE_CODE`, or a plain GL account in `DACCT_CODE`) and `SLBEHAVIOR` (1 fee, 3 member balance such as share capital, 6 net proceeds, 4 pay-off of an earlier loan). The last row, order 99, is always the net proceeds into vouchers payable (class 29 type 1).
* **`loandeduc`** holds the amounts actually taken for each application (`LOANAPPID`), copied from the template and possibly edited by the clerk.
* Formulas are either a plain expression (`<PRIN_AMT>*0.01`, `<PRE_INTAMT>` for advance interest) or a call to a **VBScript function stored in `codestorage`** (`CODEID`): `sharecap` (17), `dffs` (21), `ma` (25), `ha` (24) and `PROMPT_AMOUNT` (1, the clerk types the amount). Types 48 and 49: filing/service fee 1% (type 48) or 3% (type 49) of the loan, loan insurances 1% each (GL 2010504, 2010503, 2010505), share capital, deposit for capital subscription, mutual assistance fund (`ma`: tops up to 1,000), hospitalization fund (`ha`: tops up to 700), optional ROD and savings deposit typed by the clerk.
* **The fee percentages reproduce exactly** on recent applications (5,000 -> 50.00, 76,500 -> 2,295.00 at 3%, 23,500 -> 235.00). **The share-capital amounts do not:** `sharecap` is 2% of the loan rounded down to whole thousands (nothing when the member's share balance is over 300,000), but real applications show 76,500 -> 2,000 (formula: 1,000), 400,000 -> 12,000, 300,000 -> 15,000, and the deposit for capital subscription ranges from 150 on a 5,000 loan to 713.62 on 23,500 (no 2% match). Either the clerk overrides them or older formula versions were used. **Do not post a release with computed share deductions until this is explained;** a first release feature should let the clerk type each deduction and only check that debits equal credits.

## Open questions (ask the people who know the old system)

1. **The penalty formula**, to the cent, and when it is waived (event 42).
2. **The day basis:** 365 now; why the 360-day payments in 2024 and what happened on 2025-01-23.
3. **Release:** who may approve and post (the examples show three different people for encoding, approval and posting), and the deductions list (`loandeduc`) per loan type.
4. **Loans with add-on (unearned) interest** (types that use events 22 and 24, and the monthly journal "Unearned Amortization", type 51): not mapped here.
5. **Remittance Journal payments** (type 6, payroll deductions) and **General Journal payments**: the same principal and interest lines, posted without cash; not mapped here.
6. Creating the `loan` row, and the schedule for the modes not yet checked (see above). Monthly, quarterly and lump-sum diminishing-balance schedules are now mapped; straight line, annuity variants and the shorter payment modes are not.

## What to build from this

* **Loan payment (cash)** is the nearest to ready: a receipt like the share payment with the interest-first split above on a 365-day year, written by the existing `MemberLedgerPoster` pattern with a third line, refusing penalties until question 1 is answered.
* **Loan release** needs the application workflow, schedule generation and deductions first; do not start before questions 3 and 6.
