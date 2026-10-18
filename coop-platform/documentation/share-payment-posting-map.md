# How the legacy system posts a share capital payment

Mapped from a real posted transaction in `iaccsbsucmpc01` (read only, nothing was changed). Use it as the spec for the
share capital payment write, and compare any new posting against a real one before trusting it (see CLAUDE.md).

## The example

Cash Receipt (transaction type 10, module 3), branch 1, year 2026, control no. **352779**, document no. `00091141`, 2026-04-24 15:09:56,
entered from the front office window (`IS_FO = 1`) by user 58 (a teller, number 92, department 4).
Client 99678 paid **200,000.00** in cash into Paid up Share Cap (type 1), account ref 28722.

A cash receipt for share capital is every row of `transsum` with `TR_MODULE = 3, TR_CODE = 10` that has a `transdt` line with `DTSLC_CODE = 31`.
In 2026 up to 24 April there were 5,772 share capital credits from cash receipts, against 4,192 from cash vouchers, 2,892 from general journals and 938 from dividends; the receipt is the normal way in.

## Rows written (one transaction, six rows in four tables)

| Table | Rows | What it holds |
|---|---|---|
| `transsum` | 1 | The header |
| `transdt` | 2 | The two sides: cash in, share capital out |
| `sldtl` | 1 | The member's subsidiary ledger entry |
| `gltrans` | 2 | The two general ledger lines |

Nothing else changes: **`slsum`, `sc` and `denomsum`/`denomdtl` are not touched** (`slsum.TR_DATE` stays at the date of the account's first entry; `sc` keeps its old `SCUPDDATETIME`; cash counts by note and coin are only written for cash transfers, not receipts).
`transreprint` gets a row only when a receipt is printed again.

### `transsum` (header)

| Column | Value | Rule |
|---|---|---|
| `TR_BRCODE`, `TR_YEAR`, `TR_MODULE`, `TR_CODE` | 1, 2026, 3, 10 | branch, year of the date, module of the type (`transtype.TransTypeModule`), type |
| `TR_CTLNO` | 352779 | `MAX(TR_CTLNO) + 1` for the same branch, module and type, across all years (lock the read) |
| `TR_DOCNO` | `00091141` | see "Open questions"; other receipts use `*` plus the control number as 7 digits (`*0352774`) |
| `TR_DATE`, `TR_TIME`, `DATETIMEADDED` | 2026-04-24, 15:09:56 | posting moment |
| `TR_CLIENTID` | 99678 | the member |
| `BATCH_NO` | `20260424` | date as `yyyymmdd` |
| `EXPLANATION` | `SC` | free text typed or chosen by the teller |
| `IS_FO` | 1 | 1 when posted from the front office window |
| `CLIENTNAME`, `ANDOR_NAME`, payment columns, `TR_REFNO` | NULL | unused for this receipt |

### `transdt` (two entries, debit positive and credit negative; they sum to zero)

| Column | Seq 0: cash in | Seq 1: share capital |
|---|---|---|
| `DTSEQ_NO` | 0 | 1 |
| `DTDEPT` | 4 | 2 |
| `DTACCT_CODE` | `10003` Cash on hand - tellers | `3010101` Paid-up share capital |
| `ClientIDDT` | NULL | 99678 |
| `DTSLC_CODE`, `DTSLT_CODE`, `DTREF_NO` | 11, 1, 1 (cash, drawer sub-account) | 31, 1, 28722 (class, type, account ref) |
| `DTSLE_CODE`, `DTCTS_NUM` | 53, 92 (teller drawer, teller number) | 11, 15 (principal, account status) |
| `DTSL_DATE` | NULL | the posting date |
| `DTADJ_FLAG` | NULL | 1 |
| `DTCOCI_TYPE` | 1 | 1 (1 = cash; 2 = cheque) |
| `DTAMT` | +200000.00 | -200000.00 |
| `DTENCDBY`, `DTPOSTBY` | 58 | 58 (`access.AccessID` of the person) |
| `DTAPPRVBY`, `DTAMTDUE`, `DTSLE_CODE2`, `DTCTS_NUM2`, `DTREFERENCE`, `DTISCLEARED` | NULL | NULL |
| `DTUPD_TAG` | 1 | 1 |

* The general ledger account comes from `glcontrol` for the share line: class 31, type, event 11, and the account's **status** as the number (`GLControlCTS_NUM` 15, 16 or 17) gives `3010101` (type 1), `3010102` (type 2), `3010103` (type 3), `3010104` (type 7). So 15, 16 and 17 are account statuses; 15 is the normal active one.
* The cash line is tagged with the receiving teller's drawer (event 53, the teller's number), so this payment already counts in the app's running drawer balances.
* `DTDEPT` is normally the department of the person posting (in a sample of 427 recent share receipts it matched the user's department in most; a person who may choose a department can pick another).
* A cheque payment writes the same two lines with `DTCOCI_TYPE = 2` on both, and the cheque details go in `transck` (not mapped yet; 1 of the 133 recent share receipts sampled was by cheque).

### `sldtl` (subsidiary ledger, one row)

Key: client, class 31, type 1, ref 28722, event 11, `TR_DATE`, `SEQNO`.

| Column | Value |
|---|---|
| `SEQNO` | 3, which is `MAX(SEQNO) + 1` over **all dates** of this account (the earlier rows were 1 and 2) |
| `TR_DATE`, `GL_DATE` | the posting date |
| `TR_CODE`, `TR_CTLNO`, `TR_DOCNO` | 10, 352779, `00091141` (same as the header) |
| `AMT` | -200000.00 (credit is negative) |
| `ADJ_FLAG`, `COCI_TYPE` | 1, 1 |
| `CLRG_DAYS` | 0 |
| `CTLNO2`, `CHECKNO`, `BANK_CODE` | NULL (cheque details when paid by cheque) |

Member balance after the payment = `-SUM(AMT)` over the account (here 27,000 + 300,000 + 200,000 = 527,000).

### `gltrans` (two rows, same date and type, no link to the control number)

| Account | Dept | `GLTransDR_AMT` | `GLTransCR_AMT` |
|---|---|---|---|
| `10003` | 4 | 200000.00 | 0.00 |
| `3010101` | 2 | 0.00 | -200000.00 |

Balance-before columns are 0 and `GLTransSYNC = 1`; `GLTransLASTUPDATED` is the posting time. Dept follows the matching `transdt` line.

## Rules the old system applied around it

* **Maximum balance:** the type has a minimum and maximum balance (`sc.SCMIN_BAL`, `sc.SCMAX_BAL`). This payment took the balance to 527,000.00, over the maximum for the account, and the system asked for an override. It logged it in `actvtlog` (list 9, "Resulting Balance will exceed the Maximum Balance: Allow exceeded balance", with the user, client, account, type 10, control number, amount and time). A payment under the limit writes no `actvtlog` row. The app already has supervisor overrides (`ecis_fo_override`) that can play this part.
* **Account must be active:** the share line carries the account's status as `DTCTS_NUM`, so a closed account (17) maps to a different `glcontrol` row; a payment into a closed account should be refused.
* Debits equal credits across the two `transdt` lines (and across the two `gltrans` rows).

## Open questions (ask the people who know the old system)

1. **Where `TR_DOCNO` comes from.** `00091141` looks like a pre-printed official receipt number typed or drawn from a book; `*0352774` looks like the automatic fallback. The fastest check is one teller's receipts in sequence, and whether the old window refuses a duplicate.
2. **Cheque receipts:** the `transck` row layout and when `sldtl.CLRG_DAYS` is not 0.
3. **`DTUPD_TAG` = 5** appears on one receipt. Meaning unknown (probably a correction or an adjusted entry).
4. **Whether the account's status 16 exists in practice** (it has `glcontrol` rows but no `sc` rows in branch 1).
5. **Subscriptions** (agreeing to buy shares) and **withdrawals/transfers** of share capital are not mapped here. Share capital goes out through a Cash Voucher (type 21), which has debits in `sldtl`; map one the same way before building it.

## What to build from this

A "Share capital payment" would be a Cash Receipt in the front office window that writes the rows above in one transaction, with the checks listed (active account, balanced, maximum balance override), an audit row, and a database test that posts and rolls back, as the cash handover and journal tests do. It must not write `slsum`, `sc` or `denom*`.
