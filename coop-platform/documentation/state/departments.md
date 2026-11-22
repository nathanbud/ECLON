# Departments (Settings > Organization > Departments)

The cooperative's departments are the old table `dept` (`DeptID`, `DeptDesc` up to 30 characters, `DeptParentID`, `DeptTIN`, `DeptAddress`, `DeptIsDisabled`). On `coop` there are 27: 1 CONSOLIDATED ALL, 2 MAIN OFFICE, the satellites, LABCOOP ones and buildings, under consolidating departments (1, 10, 15 and others). They are the cooperative's own, so not part of Starter setup.

What points to a department: `client.ClientDEPT` (a new member is given 2 when none is chosen, `MemberLifecycleController.DEFAULT_DEPT`), `access.AccessDepartment`, and the books: `gltrans.GLTransDept`, `transdt.DTDept`, `transck.CKDept`, `loanapp.Dept`, `conn.ConnDept`, `holidays.DeptID`, `inv_itemtype.Dept`, `transtype_invoice.DeptID`, `client_temp.ClientDEPT` (`DepartmentController.USED_BY`).

## The screen

`DepartmentsPage` (view `departments`, administrators; Settings > Organization and the search), `api/departments.ts`, server `ph.coop.core.organization.DepartmentController` with the pure rules in `DepartmentRules`:

- `GET /api/admin/departments`: each with its parent, TIN, address, switched off, members, users and departments under it; whether the database allows changes; the next free number; department 2; the text lengths.
- `POST /api/admin/departments`: add (number 1 to 32767, not in use; name required, unique ignoring case; parent must exist).
- `PUT /api/admin/departments/{id}`: change name, parent (no loops), TIN, address. The number never changes.
- `POST /api/admin/departments/{id}/switch` `{off, reason}`: switch off or on. A switched-off department keeps its records.
- `DELETE /api/admin/departments/{id}`: only when no member, user, department under it or record of the books points to it, and never department 2.

Each change needs a reason and is audited `department.add|change|switch-off|switch-on|delete`; the cached lookups are forgotten. A database without department 2 shows a warning, and the first Add starts as 2, MAIN OFFICE.

## Registration checks every link

Before the new member's row is written, `MemberLifecycleController.missingLinks` reads the `client` table's own foreign keys (`Db.tableLayouts`) and checks each value about to be written (other members excepted). Anything missing is refused at once with `LifecycleRules.missingLinks`: each thing by name and number, whether it was the default, and where to add it (`whereToAdd`: Departments, Offices, Settings > Users, otherwise Starter setup). This replaced the office-only check; before, `coop_bgempc` failed with general server errors E-261121-33HQ (office) and E-261121-LU3V (department).

## Database

PostgreSQL `V036__departments.sql` (applied to `coop` 2026-11-21; on `coop_bgempc` through Apply them now), MySQL `V073_departments.sql` (written, not applied): `GRANT INSERT, DELETE` on `dept` and `UPDATE` of its five data columns.

## Tests

`DepartmentRulesTest`, `DepartmentControllerTest` (database, rolled back), `LifecycleRulesTest.aMissingListEntryIsNamedWithWhereToAddIt`, `DepartmentsPage.test.tsx`.
