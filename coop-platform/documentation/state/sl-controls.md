# SL Controls

**Where**: Settings > Products > Account opening (SL Controls) (view `slcontrols`, `SlControlsPage.tsx`), administrators only. Also in the side menu and search under Administration.

**What it decides**: which accounts open by themselves, empty, when a Membership Officer approves a new member's registration, or reactivates a member who no longer holds one of them (`MemberLifecycleController.openAccounts`), and which share capital account the returning-member share capital rule looks at. One switch per account type and member type (Regular Individual, Associate: the two this application registers).

**Which accounts can be switched**: savings (class 22, table `sd`), member funds (29, `osl`) and share capital (31, `sc`), the three kinds the old system opened with a membership. Loans, time deposits and receivables open when they are taken out, so they are not offered.

**Rules** (`SlControlRules`, pure; the server checks again on save):
- A reason is needed; it is kept in the audit log with each change (`sl.control.changed`, entity `sl-control`, id `branch/memberType/class/type`, before and after in words).
- An account type switched off in Account Types cannot be switched on here, and is skipped when accounts open even if its switch is on.
- Every member type must keep at least one share capital account that opens by itself (membership is subscribing to share capital); the screen warns and the server refuses.
- Changes are drafted on screen and saved together; leaving with unsaved changes asks first.

**Storage**: `ecis_sl_control` (branch, member type, class, type, `auto_open`, who and when), PostgreSQL `V031__sl_controls.sql` (applied 2026-11-20) and MySQL `V068_sl_controls.sql` (written, not applied). It starts with exactly what the application opened before, for every branch: Regular Individual SD - Regular (22/1), MBFP (29/5), HABP (29/6), Paid up Share Cap (31/1); Associate SD- Associate (22/2), Paid Up Cap Pref (31/3). The same list stays in `LifecycleRules.accountsFor` and is used where the table is not installed, or for a branch and member type with nothing stored.

**Per branch**: the switches are read for the branch of the registration; the screen shows and changes the administrator's own branch.

**Tests**: `SlControlRulesTest`, `SlControlTest` (switching HABP off makes the next Regular registration open three accounts, not four; audit; refusals), `MemberLifecycleTest` (unchanged: four accounts with the starting switches), `SlControlsPage.test.tsx`.

**Not built**: opening an account for an existing member by hand (an extra or replacement account outside registration and reactivation).

## Only what the cooperative switched on (2026-11-24)

A new or reactivated member gets exactly the accounts switched on here for account types the cooperative set up; there is no built-in list behind it any more (`LifecycleRules.accountsFor` was removed). PostgreSQL `V043` removed switches stored for account types a database does not have (they had been copied from the first cooperative's list onto `coop_bgempc`). A switch left on for a type that is not set up is shown as "Type x/y (not set up)" and can only be switched off. The vouchers-payable type, which a loan's net proceeds and vouchers use, is chosen in Settings > Products > Product roles (`ProductRoles`).
