# One application, several cooperatives: member defaults and feature switches

Each database is its own cooperative (`coop` and `coop_bgempc` are two different cooperatives with different products), so the application assumes nothing about one cooperative's setup in another. What used to be built in from `coop` is now chosen per database by a super administrator, and the areas a cooperative does not offer are switched off.

## Member defaults (Settings > Membership > Member defaults)

`MemberDefaults` (reads), `MemberDefaultsController`, rules `MemberDefaultsRules`, screen `MemberDefaultsPage`, view `memberdefaults`. Kept in `ecis_setting`:

| Setting | What it is | Was built in |
|---|---|---|
| `membership.default_department` | the department a new member is given when the registration gives none | 2 |
| `membership.default_office` | the office a new member is given when none is chosen | 1 |
| `membership.registration_types` | the member types (`clienttype` codes) that may be registered or reactivated into | 1, 2 (Regular Individual, Associate) |

- `GET /api/member-defaults` (anyone signed in): what applies, for New Member (offers only these types) and Reactivate.
- `GET /api/admin/member-defaults` (administrators), `PUT` (super administrators only; reason; audited `membership.defaults.changed` per setting).
- A setting left empty is allowed; registration then refuses clearly (`LifecycleRules.noDefault`, `typeProblem`) and says where to choose it.
- PostgreSQL `V037` stored the old built-in values, only where that database has them and never over a choice already made (applied to `coop`; MySQL `V074` not). Only the old MySQL database, which has no settings table, keeps the fixed values.

**SL Controls no longer fall back to `coop`'s account list.** A member type with nothing switched on opens nothing, and registration says to choose the accounts (`LifecycleRules.noAccountsChosen`). `LifecycleRules.accountsFor` is used only where the SL Controls table is missing (MySQL). `coop` has its switches stored since V031, so nothing changed there.

## Features (Settings > System > Features)

`FeatureRules` (pure: areas, their addresses and ledger classes), `Features` (reads `feature.<key>` in `ecis_setting`; not switched off = on; kept 30 s, forgotten on change and on a database switch), `FeatureGuard` (filter), `FeatureController`, screen `FeaturesPage`, view `features`.

Areas: savings (class 22), passbook printing, time deposits (24), loans (12), dividends, point of sale, inventory, purchasing, fixed assets, CIC submission. Always on: membership, share capital, the books, the teller's cash and setup.

Switching an area off (super administrators, reason, audited `feature.switched`; nothing is deleted):

- **Server**: every request to its addresses is refused with 409, code `FEATURE_OFF`, `feature` and a sentence naming Settings > System > Features.
- **New members** no longer get its accounts (`SlControlController.accountsFor` skips its classes) and SL Controls stop offering them.
- **Screens**: `/api/me` carries `featuresOff`; `nav/screens.tsx` (`featureOf`, `featureAllows`, `visibleScreens`) hides its screens from the menu, tiles and search; Settings hides its cards; the member profile's account sections and balance tiles, the Teller area's sections and quick tiles, and Front Office's Passbook and Time Deposits areas are left out (`ui/features.ts`: `useFeaturesOff`, `SECTION_FEATURE`).
- The access watcher's fingerprint includes the switches, so open windows refresh within about 15 seconds.

## Tests

`MemberDefaultsTest`, `LifecycleRulesTest`, `FeatureRulesTest`, `FeatureSwitchTest` (switches savings off through the real security chain, rolled back), `MemberDefaultsPage.test.tsx`, `FeaturesPage.test.tsx`.

## Still to do (agreed order)

3. One Lists screen to add, rename, switch off and delete unused entries of every standard list and the member types.
4. Branches and the holiday calendar screens.
