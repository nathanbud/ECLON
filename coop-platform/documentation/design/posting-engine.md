# Design: one posting engine for the ledger

Status: **proposed**, waiting for approval. Author: Claude, for the Coop Platform. Date: 2026-11-15.

## Problem

Fourteen classes write vouchers into the old system's ledger themselves (`transsum` header, `transdt` lines), and several also write the
subsidiary ledger (`sldtl`) and the general ledger (`gltrans`):

| Writer | Header and lines | `sldtl` / `gltrans` | Own control number | Closed-period check | Protection against posting twice |
|---|---|---|---|---|---|
| `JournalController` | yes | `gltrans` itself | yes | yes | no |
| `YearEndCloseController` | yes | `gltrans` itself | yes | no (its own rules) | fingerprint only |
| `HandoverController` | yes | `gltrans` itself | yes | yes | **yes** (`request_key`) |
| `MemberLedgerPoster` (savings, share capital, loan payments, time deposit placement, voucher pay-outs) | yes | both | yes | yes | no |
| `BankCashController`, `ChequeReturnController`, `PenaltyWaiverController`, `MemoController` | yes | through `MemberLedgerPoster` | yes | yes | no |
| `DividendController`, `SavingsInterestController`, `TimeDepositAccrualController` (vouchers, posted later) | yes | through `MemberLedgerPoster` on posting | yes | yes | no |
| `ShareWithdrawalController`, `TimeDepositClosureController`, `LoanApplicationWorkflowController` (vouchers) | yes | when `LoanReleaseController` and others post them | yes | yes | no |

Consequences:

* **Rules are repeated.** Adding the closed-period rule meant changing 11 places; the next rule would too. Nothing checks in one place that a
  voucher balances (debits = credits) before it is written.
* **A double click or a retry after a lost connection can post twice.** Only cash handovers are protected.
* **The ledger has no single owner**, which the guide (ECIS.pdf) requires: one general ledger, written one way.

## Goals

1. **One way to write the ledger:** every module hands a voucher to one service in the accounting module, which alone checks it, numbers it,
   writes it and audits it.
2. **Never twice:** every posting carries a request key from the screen; the same key returns the first result instead of posting again.
3. **Exactly the old system's rows.** The old system reads these tables, so the engine writes what each flow writes today, row for row.
4. **Done in small steps, each proven**, with the app working the whole time.

Not goals: changing the ledger tables, the old system's formats, or what any screen does. No new framework or service.

## Design

### The service (module `accounting`, package `ph.coop.core.accounting.ledger`)

```java
public interface Ledger {
    /** Writes a voucher. POSTED: header, lines, subsidiary and general ledger now. PENDING: header and lines only, posted later. */
    Posted write(Voucher v);
    /** Marks a pending voucher approved by a second person. */
    void approve(VoucherRef ref, Person approver);
    /** Posts a pending voucher: the lines get the posted mark, and the subsidiary and general ledger rows are written. */
    Posted post(VoucherRef ref, Person poster);
}

record Voucher(String requestKey, int branch, int module, int code, LocalDate date, String explanation, Integer clientId,
               boolean frontOffice, DocNoStyle docNo, State state, People people, List<Line> lines, LedgerOptions options) {}
record Line(String account, int dept, Money amount, MemberRef member, ChequeInfo cheque, Integer cociType) {}   // amount: + debit, - credit
record MemberRef(int slc, int slt, int refNo, int event, int cts) {}
```

`Money` is a small value type (two decimals, never a plain `double` or a bare `BigDecimal` of any scale).

Inside **one database transaction**, `write` does, in this order:

1. **Request key:** look it up in a new table `ecis_posting_request` (key, user, voucher, created at). Found: return that voucher, write nothing.
2. **Checks:** the lines balance; every account exists, is a detail account and is switched on; a member line's account exists and is not
   closed; the date is in an open period and year (`PostingPeriods`).
3. **Control number** (`Db.nextControlNumber`, under its lock) and document number in the flow's style (`*0000123` or `00000123`).
4. **Rows** in the old system's formats, as today: `transsum`, `transdt`, and for a posted voucher `sldtl` and `gltrans`.
   Differences between flows (a journal writes `gltrans` per line, a handover has no balance columns, and so on) are explicit in
   `LedgerOptions`, not copied code.
5. **Audit** (`AuditLog.write`) and the request key row.

A module never writes `transsum`, `transdt`, `sldtl` or `gltrans` again; a build check (see the module boundaries step) will fail if it does.

### Request keys from the screens

Each posting dialog makes one key when it opens (`crypto.randomUUID()`) and sends it with the request (`Idempotency-Key` header, also accepted in the
body). A second press, or a retry after the connection dropped, sends the same key and gets the same receipt back. The handover's existing
`request_key` becomes this.

### Database

PostgreSQL `V026__posting_request.sql` (and MySQL `V063`): `ecis_posting_request (request_key varchar(64) primary key, username, branch_code, tr_year,
tr_module, tr_code, tr_ctlno, created_at)`, INSERT and SELECT for the application. No change to the ledger tables.

## How it is rolled out

One flow at a time, simplest first. For each:

1. **Characterization test first:** post through the current code in a rolled-back test and record every row it writes (all four tables).
2. Move the flow onto `Ledger`.
3. The same test must produce **the same rows**, column for column (except the generated ids and the time stamps).
4. Commit. The other flows are untouched.

Order: journals; year-end close; `MemberLedgerPoster` (savings, share capital, loan payments, time deposit placement, voucher pay-outs); memos,
bank cash, returned cheques, penalty waivers; the voucher runs (dividends, savings interest, time deposit accruals); share withdrawal, time deposit
closure, loan release; cash handover last (it already has request keys).

## Testing

* `LedgerRulesTest` (pure): balancing, account and period checks, document numbers.
* Characterization tests per flow, as above, on the real database, rolled back.
* `IdempotencyTest`: the same key twice gives one voucher and the same receipt.
* The whole backend suite after each flow.

## Risks and open questions

* **Format details nobody has written down yet** (for example `DTUPD_TAG` 5 seen on one old receipt). The characterization tests catch any
  difference before it ships; nothing is guessed.
* **Speed of the big runs** (dividends and interest write thousands of lines): `write` uses batch inserts, as the year-end close does.
* **MySQL** keeps working the same; the new table is optional there until V063 is applied (no request key protection without it).

## Decision asked for

Approve this design, and the order above, before any code moves.
