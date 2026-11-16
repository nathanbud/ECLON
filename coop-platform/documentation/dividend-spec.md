# Interest on share capital and patronage refund: the specification, and what the app does with it

Source: `pics/divpat_system.docx` ("Interest on Share Capital and Patronage Refund Computation Module", functional specification v1.0, 2 November 2026). Its own section 12 says the formulas are illustrations that the cooperative's accountant, auditor and compliance officer must validate against RA 9520, the CDA rules and the bylaws before real use. Nothing below replaces that check; the app builds in **no** statutory percentage.

The screen is Accounting > Dividends (`DividendsPage`, `DividendPanel`); the rules are in `DividendRun`, the endpoints in `DividendController` (`/api/front-office/dividends`). How the old system ran the same job, and the rule fitted to its real 2026 run, are in `dividend-map.md`.

## Two ways to set the rates
* **Type the rates** (as the old system was run): dividend rate and patronage refund rate, as the general assembly declared them.
* **From the approved allocation** (the specification's method): the accountant enters the net surplus, the statutory funds set aside (name and percent, typed, none built in), the general assembly resolution, and the amounts allocated to interest on share capital (ISC) and to patronage refund (PR). Then
  * average share-month of a member = the sum of the 12 month-end paid-up share capital balances of the year before the cut-off / 12 (spec 3.4);
  * ISC rate = ISC allocation / total average share-month; member ISC = member average share-month x rate (the real computation divides by 1,200 so the rate in percent can be used as is);
  * PR rate = PR allocation / total qualified patronage; member PR = member qualified patronage x rate (spec 3.5);
  * each amount is rounded half up to the centavo.
The spec's two worked samples (10% of 100,000 = 10,000 and 30% of 50,000 = 15,000) are in `DividendRunRulesTest`.

## Controls from section 6
| Spec | App |
|---|---|
| No distribution without an approved net surplus | allocation method refuses a missing or zero net surplus and a blank resolution |
| Distribution may not exceed the approved allocation | allocations must fit in net surplus less statutory funds; the encode is refused if the interest or the refund to be credited is more than its allocation (beyond half a centavo a member of rounding) |
| No duplicate transactions | a year with dividend vouchers cannot be encoded again |
| Allow recalculation before final approval | Preview can be repeated with other figures until vouchers are encoded |
| Authorization before posting; user and time of every approval | encode, approve (a second person) and post (a third) are separate people; every step is audited (`dividend.encode` records the rates, allocations, net surplus and resolution; `dividend.approve`, `dividend.post`, `dividend.register`) |
| Validate member eligibility | everyone with a total above zero is included (as in the real run); members with no account to credit are listed and left out |

## Reports (section 7)
* **Distribution register**: Download register (CSV): one line per member with average share-month, rates, ISC, qualified patronage, PR, total and where it is credited.
* **Combined member distribution statement** (section 8): enter a member number, Member statement, Print.
* **Reconciliation**: the panel shows allocated, distributed and difference for each of the two, and the figures it spread them over.

## Stored distributions, review and approval (built 2026-11-16; PostgreSQL `V029` applied, MySQL `V066` not)
Spec sections 4, 5, 6 and 11. Accounting > Dividends has three parts: **Prepare**, **Distributions** and **Settings** (`DividendsPage`, `DividendPanel`, `Distributions.tsx`).
* **Prepare** works the year out (as before) and, instead of encoding, stores it as a distribution (`DistributionController`, `POST /api/front-office/dividends/distributions`):
  `ecis_distribution_batch` (the year, method, net surplus, available, allocations, resolution, rates, insurance amounts, totals, the settings used in words,
  who prepared, submitted, decided and encoded, and when), with insert-only rows: `ecis_distribution_batch_fund` (statutory funds as applied),
  `ecis_distribution_member` (each member's average share-month, qualified patronage, interest, refund, total and split), `ecis_distribution_credit`
  (the credits the vouchers will carry), `ecis_distribution_held` (left out: no account to credit, or not eligible, with the reason) and
  `ecis_distribution_patronage` (qualified patronage by loan event).
* **Steps**: DRAFT -> SUBMITTED (`/submit`) -> APPROVED by someone other than the preparer (`/approve`, optional note) -> ENCODED (`/encode {batchId}`, the
  vouchers are written from exactly the stored credits); `/reject` with a reason from draft, submitted or approved. Preparing the year again replaces a version
  not yet approved (kept as SUPERSEDED with `superseded_by`); an approved or encoded one, or vouchers already made for the year, stop it. The voucher approve
  and post steps (a second and a third person) are unchanged. Every step is audited (`dividend.distribution.*`, `dividend.encode`, `dividend.report`).
* **Settings** (`ecis_distribution_policy`, `ecis_distribution_fund`; `GET /settings`, `PUT /settings/policy` and `/settings/funds`, administrators, reason):
  the loan events whose payments are qualified patronage (from `sle_type`, default 23, 24, 31 as the old run), the member statuses that take part, the member
  types earning interest on share capital and those earning a refund (each "every" by default), a minimum average share-month, and the statutory funds
  (none built in). `DividendRun.Policy`; `Policy.OLD_SYSTEM` is the default and keeps `DividendRunRealDataTest` matching the real 2026 run.
* **Reports** (`GET /distributions/{id}/report/{kind}`, read from what was stored, printable): `net-surplus` (revenues, expenses and net surplus per the books
  for the year, against the approved net surplus), `funds` (statutory fund allocation), `isc` and `pr` (summaries, patronage by loan event, members by amount
  band), `ledger` (the entries the vouchers carry or carried, by account, with each voucher's status), `audit` (who did what, every version of the year, and the
  reconciliation: members, credits, voucher debits and what is posted). Also the register (`/register.csv`) and each member's statement (`/statement`).
* Tested by `DividendRunTest` (the whole flow, the settings, every report, the register and a statement, rolled back), `DividendRunRealDataTest`,
  `DividendRunRulesTest`, `Distributions.test.tsx`.

## Not built
* **Appropriation entries** (spec section 9: debit appropriation, credit payable, then pay): the old system, and so this app, credits the members directly from the
  fund account 20126 in one voucher; the statutory funds are not posted by this screen. Needs the accountant's chart of accounts before it is built.
* **The Member role seeing their own statement**: there is no member sign-in yet.
* **Filing fees** as patronage: they are deducted at loan release (`loandeduc`), not in the loan ledger events the settings choose from.
* **Transferred share capital** is not shown apart from other movements.
