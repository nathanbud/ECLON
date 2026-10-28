# Membership lifecycle: register and close

Read and edit (maker-checker) already existed. This adds the other two letters of CRUD. Nothing is hard-deleted: "delete" is **closing the membership**, as the old system does.

## What the old data shows

- `client.ClientID` runs on per branch (max + 1). Branch 1 stood at 100691 on 2026-04-24.
- **Check digit** `ClientIDChkDgt` = `(9 - S) mod 10`, where S is the digit sum after doubling every second digit starting from the rightmost (subtract 9 from a doubled digit over 9). Fits all 100,614 members (`LifecycleRules.checkDigit`).
- Only three `client` columns have no default: branch, number, check digit. A new real row also has DateOpened, AccountType, ClientType, AccountStatus 1, AlertLevel 1, EncdBy (an `access.AccessID`, foreign key `FK_EncdBy`) and ClientDEPT.
- **Accounts opened with a member** (seen on every 2025-06+ registration; `slmastersum` row each, ref = last ref of that class and type + 1):
  - Regular Individual (ClientType 1): savings 22/1 (`sd`, min 500, rate 1.00), share capital 31/1 (`sc`, min 1000), funds 29/5 and 29/6 (`osl`). All status 15, dated the day opened.
  - Associate (2): savings 22/2 (rate 0.25) and share capital 31/3 (min 0).
  - Vouchers payable 29/1 is not opened here; the first loan application opens it.
  - Kiddie, joint, company and other types are registered in the old system; the app refuses them.
- **Closing**: `AccountStatus` 3, `ClosedDate`, `ClosedReason` (`closedreason`: 1 Voluntary, 2 Transferred, 3 Bad Debts, 4 Offset, 5 Zero Balance, 6 Deceased), `ClientType` becomes 6 (Closed/Withdrawn) or 7 (Deceased), `DateDeceased` for reason 6. Account rows are left as they are (most closed members keep status 15 on `sc`/`sd`).

## What the app does

- `POST /api/member-requests/registrations` and `/closures` (needs edit permission) store a PENDING request in `ecis_member_request` (V037). Nothing else is written.
- `POST /{id}/approve` needs a Membership Officer other than the requester; `/reject` needs a reason; the requester can `/withdraw`.
- Approving a registration re-checks the data, refuses a same last name + first name + birth date, takes the next number, inserts `client`, then `slmastersum` + `sc`/`sd`/`osl` rows, with audit rows `MEMBER_REGISTER_*` and `MEMBER_CREATED`. The requester must be an old-system user (EncdBy).
- A closing is refused unless the member is active or dormant, the reason is known, a date of death goes with reason Deceased only, **every ledger class is at zero** (event 11 sum; a loan balance is allowed only for reason Bad debts) and there is no loan application in progress.
- Screens: Register new member (finder screen and profile), Close membership (profile), New members and closings (Approvals).
- Tests: `LifecycleRulesTest`, `MemberLifecycleTest` (rolls back).

Not built: registering joint, kiddie and company members; reopening a closed membership; moving a member to dormant.
