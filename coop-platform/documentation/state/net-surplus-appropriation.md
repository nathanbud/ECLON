# Net surplus appropriation

This is what the Philippine Cooperative Code (RA 9520, Article 86) asks before members receive anything: a year's net surplus is first set aside to the
statutory funds, and only the rest is allocated to interest on share capital and patronage refund. The old system had no such step (its dividend run credited
members from 20126 with typed rates; the funds were presumably set aside by a hand-made journal), so this is new.

## Where

Accounting > Dividends > **Appropriation** (menu and search: Net surplus appropriation). Accountants and administrators prepare, approve and post; auditors read.
`AppropriationController` (`/api/appropriations`), rules `AppropriationRules` (pure), `Appropriations` (the books' net surplus, the registration date, a year's
posted appropriation; used by the dividend distribution), screen `AppropriationTab.tsx`, API `api/appropriations.ts`. Tables `ecis_appropriation` and
`ecis_appropriation_line` (PostgreSQL `V045`, applied to `coop` and `coop_bgempc`; MySQL `V082`, not applied).

## The figures

- **Net surplus**: what the year's Closing Entry (type 99) credited to the undivided net surplus account (`coop.year-end.surplus-account`, 30198). The year must be
  closed; a year with no surplus or a loss has nothing to appropriate. It is read from the books when the draft is saved and checked again at posting (a change
  stops the posting: send it back and save it again).
- **Funds**: each a percent of the net surplus (to the centavo, half up) credited to its own account: reserve, education and training (the cooperative's half and,
  as a second line, the half for its union or federation, both counted together), community development, optional, or another fund. A new appropriation starts
  from the last posted one's funds, else from the statutory funds in Dividends > Settings (kind guessed from the name).
- **Interest on share capital and patronage refund**: amounts the general assembly allocated, credited to 20126 (`DividendRun.FUND_ACCOUNT`), the account the
  dividend vouchers pay members from. That account is fixed for now because the payout is.
- What is not allocated stays in the undivided net surplus.

## Checks (Article 86 as read here; confirm against the bylaws and the CDA rules)

- Reserve fund at least 10%, or at least **50% while the year ends within five years of registration**. The registration date is recorded by an administrator on
  the Appropriation tab with a reason (`ecis_setting` `organization.registered_on`, audited `accounting.appropriation.registered`).
- Education and training fund at most 10% (both halves); community development fund at least 3%; optional fund at most 7%.
- Funds and allocations together not more than the net surplus.
- A general assembly resolution (5 to 200 characters); the entry dated after the year and not in the future, in an open period of an open year.
- Each fund has its own account; accounts exist, take postings, are switched on, are not members' control accounts and are not income or expense accounts; the
  undivided net surplus and 20126 are not fund accounts.
- Not checked: that interest on share capital does not exceed the normal rate of return (Article 86 leaves it to the general assembly and the CDA).

## Steps and posting

DRAFT, FOR_REVIEW, APPROVED, POSTED (or CANCELLED); one live appropriation per year. Maker, approver and poster are three different people; send back and cancel
need a reason; a posted one is never changed (correct it with a journal approved by the general assembly). Posting writes one General Journal through the
`Ledger` (request key `appropriation-<id>`): a debit of the undivided net surplus for everything allocated, and a credit per account (interest and refund on 20126
joined). Audited `accounting.appropriation.create|update|submit|approve|return|post|cancel` and `ledger.write`.

## The payout follows it

When a year has a posted appropriation, preparing its dividend distribution (`DistributionController.prepare`) is refused if the interest or refund to be
credited is more than appropriated (beyond half a centavo a member), or if a distribution from the allocation uses another net surplus. Prepare's
**Fill in from the posted appropriation** copies the net surplus, funds, allocations and resolution. A year with no appropriation can still be distributed the
old way.

## Not yet

- The fund accounts and percents are not kept as settings of their own (the dividend settings' funds have no kind or account on screen).
- The payable account for interest and refund is 20126 for every cooperative; it should be chosen like the vouchers payable in Product roles.
- Requiring an appropriation before any distribution (a policy switch).
- The tab sits under the Dividends feature, so a cooperative with dividends switched off cannot open it.

Tested by `AppropriationRulesTest`, `AppropriationFlowTest` (closes a made-up 1998 and posts on `coop`, rolled back), `AppropriationTab.test.tsx`.
