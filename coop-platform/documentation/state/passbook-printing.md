# Passbook printing (savings)

Keep this note current when the feature changes.

## The old system

- `passbk`: one row per account (124,505; classes 22 savings, 31 share capital, 12 loans, 24 time deposits, 29), where its passbook stands:
  `PASSBKSERNUM` the passbook number, `PASSBKLINENUM` the last line printed, `PASSBKSEQNO` the last ledger entry printed (`sldtl.SEQNO`, unique per account),
  `PASSBKLTR_DATE` its date and `PASSBKBAL` the balance after it (checked against the ledger: they agree). Kinds in `passbktype`.
- Line numbers run 1 to 31 evenly (32 and 33 are rare): about 31 lines a page.
- Transaction codes are `transtype.TransTypeDesc2` (CR, SW, ISD, DIV, OR ...). The teller number is the encoder's `access.AccessTellerNumber`.
- The page layout was inside the old program; it is not in the database.

## The passbook (from the cooperative)

Foldable savings passbook, 8 inches high by 5 1/2 wide; columns Date, Reference, Deposit, Withdrawal, Balance, Transaction type (TC), Teller No.

## Built (PostgreSQL `V028` applied; MySQL `V065` written, not applied)

- `PassbookRules` (pure, `PassbookRulesTest`): the entries after the last printed one, laid out from the next line; a full page continues at line 1 of
  the next; a new passbook (line 0 with entries printed before) starts with a BAL FWD line; where a line sits (the fold adds a gap); layout checks.
- `PassbookPdf`: a PDF the passbook's size, printed at actual size, Courier; a test page marks every line, column edge and the fold.
- `PassbookController` (`/api/front-office/passbooks`, front office users): accounts, detail with the page plan, `next-page.pdf`, `confirm`
  (moves `passbk` on to the page's last line and entry; refused when the passbook moved since), `reprint.pdf` (printed entries only; does not move;
  reason), `position` (new passbook or correction; head cashier or administrator; reason), `layout` (administrators change it; reason) and `layout/test.pdf`.
  Every action is in `ecis_passbook_print` (insert only) and the audit log (`passbook.reprint`, `passbook.new`, `passbook.position`, `passbook.layout`).
- `ecis_passbook_layout` (kind 22): default 31 lines, line 1 at 0.55 in, 0.2 in apart, fold after line 16 with 0.35 in, Courier 8 pt; **not yet
  checked on the real passbook or printer**: print the test page and adjust.
- Screen: Front Office > Passbook (`PassbookPanel`). `PassbookPrintTest` runs it on a real account part way down a page.

## Not done

- Share capital and loan passbooks (the old system printed them too; same table).
- Printing goes through the Windows print window (the PDF at actual size); no direct printer commands. The passbook printer's model is not known yet.
