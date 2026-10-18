# How the legacy system posts a savings deposit and a savings withdrawal

Mapped from real posted transactions in `iaccsbsucmpc01` (read only, nothing was changed). Use it as the spec for the savings
deposit and withdrawal writes, and compare any new posting against a real one before trusting it (see CLAUDE.md). Share capital
works the same way; see `share-payment-posting-map.md`.

## Deposit: Cash Receipt (type 10, module 3)

Example: control no. **352846**, document no. `*0352846`, 2026-04-24 17:11:31, entered from the front office window (`IS_FO = 1`) by user 5
(a cashier, number 32, department 2). Client 1118 deposited **800.00** in cash into SD - Regular, account ref 828.

**It is the same posting as a share capital payment, with class 22 instead of 31.** Six rows in four tables:

| Table | Row | Notes |
|---|---|---|
| `transsum` | module 3, code 10, ctl 352846, `TR_DOCNO` `*0352846` (typed receipt numbers also occur), `TR_CLIENTID` 1118, `BATCH_NO` `20260424`, `EXPLANATION` free text, `IS_FO` 1 | control number = highest of the year + 1 |
| `transdt` seq 0 | account `10002` (cash on hand - cashiers) debit +800.00, class 11 / type 1 / ref 1, event 52 (cashier drawer), `DTCTS_NUM` 32 (the cashier's number), `DTCOCI_TYPE` 1, `DTENCDBY` = `DTPOSTBY` = 5, `DTUPD_TAG` 1 | a teller uses `10003` and event 53 |
| `transdt` seq 1 | account `2010101` (savings deposits) credit -800.00, `ClientIDDT` 1118, class 22 / type 1 / ref 828, event 11, `DTCTS_NUM` 15 (the account status), `DTSL_DATE` the date, `DTADJ_FLAG` 1, `DTCOCI_TYPE` 1 | |
| `sldtl` | client 1118, class 22, type 1, ref 828, event 11, `SEQNO` 707 (= highest of the account over all dates + 1), `AMT` -800.00, `TR_CODE` 10, `TR_CTLNO`, `TR_DOCNO`, `ADJ_FLAG` 1, `COCI_TYPE` 1, `CLRG_DAYS` 0 | |
| `gltrans` x2 | `10002` debit 800.00 and `2010101` credit -800.00, same date, type 10, dept of the poster | |

The GL account comes from `glcontrol` (class 22, the type, event 11, the account's status as the number):

| Type | Active (15) | Dormant (16) | Closed (17) |
|---|---|---|---|
| 1 SD - Regular, 2 Associate, 3 Kiddies | `2010101` | `2010101` | `2010101` |
| 8 Kaya | `2010106` | `2010106` | `2010106` |

Nothing else changes: `sd` (`SDUPDDATETIME` stays empty), `slsum` (its date stays at the account's first entry), the cash counts and `hold` are not touched.

## Withdrawal: Deposit Withdrawal (type 23, module 2)

Example: control no. **113758**, document no. `*0113758`, 2026-04-24 16:18:29, `IS_FO = 1`, by user 5 (cashier 32, dept 2). Client 7771 withdrew
**21,000.00** from SD - Regular, ref 6327 ("WTH TO LOAN PAYT"). The picture is the deposit turned around:

| Table | Row |
|---|---|
| `transsum` | module **2**, code **23**, ctl 113758 (own control sequence per module and type), `TR_DOCNO` `*0113758`, `TR_CLIENTID` 7771, `EXPLANATION` free text, `IS_FO` 1 |
| `transdt` seq 0 | `2010101` **debit +21000.00**, `ClientIDDT` 7771, class 22 / type 1 / ref 6327, event 11, `DTCTS_NUM` 15, `DTSL_DATE`, `DTADJ_FLAG` 1, `DTCOCI_TYPE` 1 |
| `transdt` seq 1 | `10002` **credit -21000.00**, class 11 / type 1 / ref 1, event 52, `DTCTS_NUM` 32, `DTCOCI_TYPE` 1 |
| `sldtl` | `AMT` **+21000.00** (a debit), `SEQNO` 50 (= highest + 1), `TR_CODE` 23, `TR_CTLNO` 113758, same date in `TR_DATE` and `GL_DATE` |
| `gltrans` x2 | `2010101` debit 21000.00 and `10002` credit -21000.00, type 23 |

Both `transdt` lines also carry `DTCHECKISRELEASE` 0, `DTISTRANSIT` 0 and `DTISUNDENTIFIED` 0, `DTUPD_TAG` 1, and the poster as `DTENCDBY` and `DTPOSTBY`.
A withdrawal pays cash out of the poster's own drawer, so the app's drawer balance falls by the amount.
Not written: `sd`, `slsum`, `hold`, the cash counts. (A "Misc Disbursement", type 24, is a different transaction.)

## Rules the old system applied (override log)

Rules that were not met are not refused; the old window asked a supervisor to allow them and logged the decision in `actvtlog`
(list 9 "New Transaction Added", `UserID` the cashier, `OvrdID` the person who allowed it, `ClientID`, class, type, ref,
`TR_CODE`, `TR_CTLNO`, `TR_DATE`, `TR_TIME`, `TR_AMT`, and a free-text `ActvtInfo`). In 2026 (to 24 April) there were 958 such rows for savings accounts, and for 443 of
2,148 override rows the person was their own overrider (a supervisor with the right).

| Rule | What the data shows | Override text and log |
|---|---|---|
| **Minimum balance** | A withdrawal that leaves the balance below the account's minimum (`sd.SDMIN_BAL`, 500.00 for most types) needs an override. 948 of the 958 savings overrides were withdrawals, mostly closing an account down to 0.00 or the "maintaining balance" hold. 9 were deposits. | `<balance after> Resulting Balance will exceed the Minimum Balance: Allow exceeded minimum balance of Php <balance after>\nTO Client: <branch>-<client id>-<check digit> \| <name>\nWITH MINIMUM BALANCE OF ONLY: Php <minimum>\nExplanation: <explanation>` |
| **Disbursement limit** | A withdrawal over the poster's limits needs an override. All 1,200 override rows for type 23 in 2026 were above both `AccessDisbursementLimit` and `AccessMaxWithdrawal` of the poster (the two are normally equal: 0, 50,000, 300,000, 1,000,000), and none was below both. Misc Disbursements (type 24) work the same way (782 rows). The row has no class, type or ref. | `<amount> Exceed Disbursement Limit: Allow disbursing of Php <amount>\nTransaction: Deposit Withdrawal Control # 01-<ctl>\nExplanation: <explanation>` |
| Funds on hold | Holds (`hold`) reduce what can be withdrawn; the list has "Exceed Available Balance" (list 83) and the hold reasons Collateral, Earmarked, Lost Passbook, Garnished, Maintaining Balance and TD Certificate. No savings overrides of this kind appeared in 2026, so the exact check is **not confirmed**. | list 83 |
| Dormant account (status 16) | The list has "Dormant Allow Transaction" (list 79); a dormant account may need an allow. There are no 2026 rows of that list for savings; the only trace is 2 minimum-balance overrides whose explanation reads "dormant account" (a withdrawal to 0.00). **Not confirmed.** | list 79 |
| Closed client | The list has "Allow Transaction for Closed Client" (list 123). **Not confirmed.** | list 123 |

## Open questions (ask the people who know the old system)

1. **Funds on hold and dormant accounts:** is a withdrawal over the available balance (balance minus holds) refused outright or allowed with list 83? Can a dormant account (16) take a deposit without an override? None appeared in the 2026 data, so the data cannot answer.
2. **Which hold reasons reduce the available balance.** Maintaining balance and TD certificate holds probably do; all holds are treated as reducing it in the app today.
3. **Receipt number source** (same question as for share payments): `*` plus 7 digits, or typed.
4. **Passbook printing and the teller's own drawer limit** (the `ecis_drawer_limit` feature) before a withdrawal pays cash out.
5. **Deposit by cheque** (`COCI_TYPE` 2 and `transck`): not mapped; same open question as share capital.
6. **Interest, dividends and year-end** postings (types 53 and 54) are batch jobs, not mapped here.

## What to build from this

* **Savings deposit:** a second use of the share payment code with class 22, the savings types and the `sd` account; the same checks (active account, client not closed) and the same rows. Dormant accounts (16) should be refused until question 1 is answered.
* **Savings withdrawal:** the mirrored rows above, cash paid out of the person's own drawer, refused when the balance would go below zero or past the available balance, with two supervisor overrides built on the app's override mechanism: minimum balance and disbursement limit. Each override must write an `actvtlog`-style row (list 9, the text formats above) or an equivalent audit row, with the overrider as a different person who is a supervisor (the head cashier or an administrator), as `ecis_fo_override` already requires. That table needs the V007 grants first.
* A database test per transaction that posts and rolls back, comparing the rows with these examples, as the share payment test does.
