# Front office (teller) window

Moved from CLAUDE.md unchanged (one sentence per line). Keep this note current when the feature changes.

**Front office (teller) window**: posted transactions are read from `transsum` (header), `transdt` (entries; positive amount = debit, negative = credit), `transck` (cheques) and `denomsum`/`denomdtl` (cash), all read-only.
New transactions are drafts in `ecis_fo_draft` (checked for real accounts and debits = credits, never posted). **Cash handover is posted** (the owner asked for posted, not draft, from now on): the head cashier (`AccessIsHeadCashier`, drawer type 52 number 37 in practice) takes cash from the vault (type 31) and gives it to tellers (type 32); tellers give it back; the head cashier returns the rest to the vault.
A hand-off between people sits in `ecis_cash_handover` as PENDING until the receiver counts it; if the count agrees it is posted (INSERT only) to `transsum` (module 4), two `transdt` lines (SLC 11, SLT 1, event 50 vault no. 99 / 52 cashier / 53 teller, `DTCTS_NUM` = drawer number, accounts 10001/10002/10003, receiver as `DTAPPRVBY`), `gltrans` (credit negative, no balance columns) and `denomsum`/`denomdtl` (count by note and coin).
Vault moves post at once.
Control numbers are `MAX+1` per transfer type across years; `TR_DOCNO` is `*` plus 7 digits.
The write grants come from `V006_cash_handover.sql`, which an administrator runs by hand (try it on a copy first). `V007_drawer_controls.sql` adds drawer cash limits (`ecis_drawer_limit`, set by the head cashier), end-of-day blind counts with the difference recorded (`ecis_drawer_close`; returning cash to the vault is blocked until hand-offs are settled and tellers who worked have counted, unless the head cashier gives a reason, which is audited), running drawer balances (`ecis_drawer_snapshot` = last year's closing figure, plus this year's entries; rebuilt in the background at start; the date column has no index so always narrow by `TR_YEAR`), supervisor overrides (`ecis_fo_override`, requester cannot approve their own) and a `request_key` that makes a repeated hand-off post only once.
The window polls `GET /api/front-office/handover/pulse` every 5 s for live alerts (EventSource cannot send the Basic header).
The screen locks after 10 idle minutes. `scripts/run-backend.ps1` rebuilds and restarts the backend.
Cash receipts into share capital and savings, and savings withdrawals, are now posted (see the share capital and savings entries below).
A savings withdrawal is refused when it is more than the cash in the teller's own drawer (`SavingsRules.checkWithdrawal`, drawer cash from `DrawerBalances.of`), the same rule as voucher pay-outs; tested by `SavingsRulesTest` and `SavingsLedgerPostingTest`.
Nothing is posted dated in a closed year or in an accounting period that is not open (`PostingPeriods.requireOpen`, see `year-end-close.md`).
Other transaction types (loan and other receipts, vouchers, cheques) are still drafts: map a real posted example first, as for those, and do not add writes for them without it.
A separate table `coopsystem_cash_transfer` and transaction 2026/4/32/33617 exist that this app did not create; leave them alone.
What a person may do comes from `AccessCanAccessFrontOffice` and `access_trans` (find/new per transaction type).
