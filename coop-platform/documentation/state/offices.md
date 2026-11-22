# Offices (Settings > Organization > Offices)

The cooperative's offices are the old table `restrik` (`RestrikID` smallint, `RestrikDesc` up to 200 characters). On `coop` they are 1 MAIN OFFICE, 2 BAUKO SATELLITE, 3 BAGUIO SATELLITE, 4 BUGUIAS SATELLITE, 5 BRENTWOOD. They are not part of Starter setup because each cooperative has its own.

What points to an office:

- `client.RestrikID` and `client_temp.RestrikID` (a member's office; foreign key `fk_client_fk_client_restrikid` on PostgreSQL). The column defaults to 1.
- `access.AccessRestrik`, a user's office list as zero-padded codes (`003,002,004,001`), edited in Settings > Users ("Offices").

## The screen

`OfficesPage` (view `offices`, administrators; Settings > Organization and the search), `api/offices.ts`, server `ph.coop.core.organization.OfficeController` with the pure rules in `OfficeRules`:

- `GET /api/admin/offices`: the offices with how many members and users belong to each, whether the database allows changes (`editable`: INSERT on `restrik` and UPDATE of `RestrikDesc`), the next free number and the name length.
- `POST /api/admin/offices` `{id, name, reason}`: add. Number 1 to 32767, not in use; name required, unique ignoring case.
- `PUT /api/admin/offices/{id}` `{name, reason}`: rename. The number never changes.
- `DELETE /api/admin/offices/{id}` `{reason}`: only while no member (either table) and no user's list points to it, and never office 1 (the default for a new member).

Each change needs a reason (3 characters or more) and is audited as `office.add`, `office.rename` or `office.delete` (before and after names). After a change the server forgets its cached lookup lists (`LookupController.forget`) and the screen refreshes `['lookups']`, so the profile's Office list shows the change at once.

A database with no offices shows a warning, and the first Add starts as 1, MAIN OFFICE.

## Registration

`MemberLifecycleController.openMember` now sets the office itself: the one the registration names, else office 1 (`DEFAULT_OFFICE`, as the old system does). If that office is missing it refuses with `LifecycleRules.missingOffice` (who, why, nothing saved, add office N in Settings > Organization > Offices or reject and register again). Before this, a database without office 1 (`coop_bgempc`) failed with a general "refers to something that no longer exists" server error (E-261121-33HQ). A registration that names an office is already checked against the list when it is sent; the approval check covers an office removed in between.

## Database

PostgreSQL `V035__offices.sql` (applied to `coop` 2026-11-21; **not yet on `coop_bgempc`**: a super administrator can apply it from the app with Apply them now on the database-changes notice in Settings or Database connection, giving the `coop_admin` login once, or with the script once its password is in `db.txt`), MySQL `V072_offices.sql` (written, not applied): `GRANT INSERT, DELETE` on `restrik` and `UPDATE (RestrikDesc)`.

## Tests

`OfficeRulesTest`, `OfficeControllerTest` (database, rolled back: list counts, add/rename/delete audited, refusals, a registration whose office was deleted before approval), `OfficesPage.test.tsx`. `MessageStyleTest` now counts `organization` among the areas whose messages are all rewritten.

## Not built

Choosing the office on the New Member screen (it can be set on the profile's Groups section through the usual approval); moving members or users from one office to another in bulk.
