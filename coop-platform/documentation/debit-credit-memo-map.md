# Debit/credit memo posting map

Mapped from real posted memos in `iaccsbsucmpc01` (read only, nothing was changed). The old system calls the type **Debit/Credit Memo (DCM)**, `transtype` 2, module 1. There is also a separate Debit Memo (8) and Credit Memo (9); only DCM is in real use (303 entries since 2023, 19 in 2025, 1 in 2026; DM has one entry and CM none).

## What a memo is used for

Not a one-account adjustment: a general entry that changes members' accounts and, when needed, ledger accounts, in one balanced transaction. The common uses in the data:

* **Transfers between members' accounts**: deceased member to beneficiary (savings, share capital, mutual funds), savings to time deposit ("SD TO TD"), correcting an earlier memo ("to correct dcm 265").
* **Reclassifications and corrections** of earlier entries.
* **Staff and collection entries** such as a clothing allowance or a GCash collection report, with plain ledger lines.

Lines are on savings (class 22), share capital (31), mutual funds (29) and loans (12, events 11, 23 and 41 for principal, interest and penalty), plus plain ledger lines. Memos have 2 to 15 lines, a few have hundreds.

## What posting writes (one transaction)

1. `transsum`: module 1, code 2, control number running on across years for the type, `TR_DOCNO` = `*` + the control number in 7 digits (`*0000266`), `TR_CLIENTID` the first member on the memo, `BATCH_NO` the date as `yyyymmdd`, `EXPLANATION` the reason, `IS_FO` 0.
2. `transdt`: one line per entry, sequence from 0, **debit positive, credit negative**. A member line carries the member (`ClientIDDT`), class, type, reference number, event 11, the account's status in `DTCTS_NUM`, `DTSL_DATE`, and `DTADJ_FLAG` 1 (null on a plain ledger line). `DTENCDBY` maker, `DTAPPRVBY` approver, `DTPOSTBY` poster, `DTUPD_TAG` 1. The line's `DTACCT_CODE` is the control account from `glcontrol` for the account's class, type, event and status.
3. `sldtl`: one row per member line (event 11, `TR_CODE` 2, the control number and document number, the next sequence number on that account, `GL_DATE` the memo date, `ADJ_FLAG` 1, no tender), with the `slsum` row the first time an event is used. The sign is the same as the `transdt` line: debit positive takes money out of the account, credit negative puts it in.
4. `gltrans`: one line per entry, debit in the DR column and credit (negative) in the CR column.

Every posted memo balances (all 303 have debits equal to credits).

## Approval in the old data

268 memos were approved by someone other than the maker, 2 by the maker, and 33 were never approved (old leftover drafts that wrote no ledger rows). The poster is often the maker.

## What the new app does (`MemoController`, `MemoRules`, `V051_memos.sql`)

* Accounting > Memos, for accountants and administrators. Lines are savings, share capital or ledger accounts. **Loans, time deposits and mutual funds are not included yet.**
* The memo must change at least one member's account (a ledger-only entry is a journal), carry a reason of at least 5 characters, be dated no later than today and balance.
* DRAFT, FOR_REVIEW, APPROVED, POSTED (and CANCELLED). **The maker, the approver and the poster must be three different people** (stricter than the old data). Posting needs an open accounting period covering the date.
* A memo may not take a member's account below zero (checked when made, sent, approved and, with the account locked, when posted). Holds, minimum balances and uncleared cheques are not checked: a memo is an authorised adjustment, not a withdrawal.
* A closed account takes no memo lines.
* A posted memo is never edited. Reverse it: a new DRAFT memo with debits and credits swapped (and its own review, approval and posting). A memo that is not posted can be cancelled by its maker or an administrator, with a reason.
* Memos made here are posted as DCM (module 1, code 2) exactly as above, so the old screens and reports show them.
* Every step is audited (`MEMO_CREATE`, `MEMO_SUBMIT`, `MEMO_APPROVE`, `MEMO_POST`, `MEMO_CANCEL`, `MEMO_REVERSE`, and `memo.ledger.post` with the legacy transaction).

Tested by `MemoRulesTest` and `MemoFlowTest` (posts a real memo and rolls it back).
