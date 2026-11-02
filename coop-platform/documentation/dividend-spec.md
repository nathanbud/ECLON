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

## Not built
* **Stored annual history** (spec section 5 tables such as `net_surplus`, `distribution_batches`, `member_isc`): the vouchers (module 6 type 54) and the audit rows are the record; the net surplus, statutory funds and resolution are kept in the audit row and the voucher explanation, not in tables of their own, and monthly share capital balances are computed from the ledger when needed.
* **Appropriation entries** (spec section 9: debit appropriation, credit payable, then pay): the old system, and so this app, credits the members directly from the fund account 20126 in one voucher; the statutory funds are not posted by this screen.
* **Reports not built**: annual net surplus report, statutory fund allocation report, ISC and PR summaries as separate pages, general ledger posting report, audit and reconciliation report; the Member role seeing their own statement.
* **Qualified patronage** is the loan interest, service fees and event 24 charges paid (what the old run used); the spec's filing fees and "earned versus billed" distinction are not separated.
* **Eligibility rules and rates by member type** are not configurable.
* **Transferred share capital** is not shown apart from other movements.
